"""Byte-exact snapshot and restore of StarRail's confirmed account-state files.

The snapshot keeps the multi-file state together (prefs plus each SQLite database
with its -journal/-wal/-shm sidecars), because restoring a database without its
sidecar files can make SQLite replay a stale journal.

Capture and restore both refuse to run while the game is running. Restore is
dry-run by default, takes a fresh "pre-restore" snapshot first, and verifies every
file by re-reading it from the device.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from device import DATA_DIR, Device, digest

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / 'account-snapshots' / 'state'
MANIFEST = 'manifest.json'


class RestoreAbort(RuntimeError):
    """A restore could not be completed; the device may hold a partial write."""

PREFS = [
    'shared_prefs/porte_sdk_account_PRODUCT.xml',
    'shared_prefs/porte_sdk_token_PRODUCT.xml',
    'shared_prefs/mihoyo_sdk_preference.xml',
    'shared_prefs/combo_widget.xml',
    'shared_prefs/mhy_plat_porte_crypto.xml',
    'shared_prefs/mdk_passport_sp.xml',
]
DATABASES = [
    'databases/PORTE_ACCOUNT_PRODUCT.db',
    'databases/mihoyo_account',
]
SIDECARS = ('-journal', '-wal', '-shm')

TARGETS = PREFS + [name + suffix for name in DATABASES for suffix in ('',) + SIDECARS]


def local_path(record, rel):
    return record / 'files' / rel


def stable(info):
    if info is None:
        return None
    return {key: info[key] for key in ('uid', 'gid', 'mode', 'context')}


def select(entries, only):
    """Restrict snapshot entries to the requested paths, rejecting unknown names."""
    if not only:
        return entries
    known = {entry['path'] for entry in entries}
    unknown = sorted(set(only) - known)
    if unknown:
        raise ValueError(f'not part of this snapshot: {", ".join(unknown)}')
    return [entry for entry in entries if entry['path'] in set(only)]


def load_manifest(record):
    path = record / MANIFEST
    if not path.is_file():
        raise RuntimeError(f'{record}: not a snapshot directory (missing {MANIFEST})')
    manifest = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(manifest, dict):
        raise ValueError(f'{path}: expected a manifest object')
    if manifest.get('data_dir') != DATA_DIR:
        raise RuntimeError(f'{record}: snapshot was taken from {manifest.get("data_dir")}')
    entries = manifest.get('files')
    if not isinstance(entries, list) or not entries:
        raise ValueError(f'{path}: files must be a nonempty list')
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError(f'{path}: invalid file entry')
        relative = entry.get('path')
        if not isinstance(relative, str) or relative not in TARGETS or relative in seen:
            raise ValueError(f'{path}: unknown or duplicate file entry: {relative!r}')
        seen.add(relative)
        present = entry.get('present')
        if type(present) is not bool:
            raise ValueError(f'{path}: {relative}: invalid present flag')
        if not present:
            continue
        for key, pattern in (('uid', r'[0-9]+'), ('gid', r'[0-9]+'),
                             ('mode', r'[0-7]{1,4}'), ('sha256', r'[0-9a-f]{64}')):
            value = entry.get(key)
            if not isinstance(value, str) or not re.fullmatch(pattern, value):
                raise ValueError(f'{path}: {relative}: invalid {key}')
        if not isinstance(entry.get('context'), str):
            raise ValueError(f'{path}: {relative}: invalid context')
        size = entry.get('size')
        if type(size) is not int or size < 0:
            raise ValueError(f'{path}: {relative}: invalid size')
    return manifest


def save_manifest(record, manifest):
    path = record / MANIFEST
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temp.replace(path)


def capture(device, record=None):
    if device.game_pids():
        raise RuntimeError(f'{device.serial}: {DATA_DIR} is in use, stop the game first')
    if record is None:
        stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
        record = STATE / stamp
        suffix = 0
        while record.exists():
            suffix += 1
            record = STATE / f'{stamp}-{suffix}'
    if record.exists():
        raise FileExistsError(f'snapshot record already exists: {record}')
    # Write into a unique staging directory and rename on success: a failed capture must never
    # leave a manifest-less half snapshot, and two concurrent captures must not share staging.
    record.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=record.name + '.partial-', dir=record.parent))
    try:
        entries = []
        for rel in TARGETS:
            path = f'{DATA_DIR}/{rel}'
            info = device.info(path)
            if info is None:
                entries.append(dict(path=rel, present=False))
                continue
            data = device.read(path)
            if digest(data) != digest(device.read(path)):
                raise RuntimeError(f'{path}: device content changed during capture')
            target = local_path(staging, rel)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            if digest(target.read_bytes()) != digest(data):
                raise RuntimeError(f'{target}: local copy does not match the device content')
            if info['size'] != len(data):
                raise RuntimeError(f'{path}: stat size {info["size"]} differs from read size {len(data)}')
            entries.append(dict(path=rel, present=True, sha256=digest(data), **info))
            print(f'{rel:52} {len(data):>7} B  {digest(data)[:12]}  {info["uid"]}:{info["gid"]} {info["mode"]}')
        for rel in [entry['path'] for entry in entries if not entry['present']]:
            print(f'{rel:52}   absent')
        if not any(entry['present'] for entry in entries):
            # A wrong --serial or an uninstalled game must not produce an "empty but valid" record.
            raise RuntimeError(f'{device.serial}: none of the {len(entries)} targets exist under '
                               f'{DATA_DIR}; is the game installed on this device?')
        if device.game_pids():
            raise RuntimeError(f'{device.serial}: {DATA_DIR} is in use, stop the game first')
        save_manifest(staging, dict(
            tool='snapshot', version=1,
            captured_at=datetime.now(timezone.utc).isoformat(),
            serial=device.serial, data_dir=DATA_DIR, game_stopped=True, files=entries,
        ))
        staging.rename(record)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    present = sum(1 for entry in entries if entry['present'])
    print(f'snapshot={record} files={present}/{len(entries)}')
    print(f'restore: python tools/starrail/snapshot.py --serial {device.serial} restore --record "{record}" --apply')
    return record


def remote_state(device, manifest, scope=None):
    rows = []
    for entry in manifest['files']:
        if scope is not None and entry['path'] not in scope:
            continue
        path = f'{DATA_DIR}/{entry["path"]}'
        info = device.info(path)
        if not entry['present']:
            rows.append((entry['path'], 'gone' if info is None else 'extra', info))
            continue
        if info is None:
            rows.append((entry['path'], 'missing', None))
            continue
        actual = device.read(path)
        status = 'same' if digest(actual) == entry['sha256'] else 'different'
        if status == 'same' and stable(info) != stable(entry):
            status = 'metadata-differs'
        rows.append((entry['path'], status, info))
    return rows


SAME = ('same', 'gone')


def show(rows):
    for rel, status, _ in rows:
        print(f'{rel:52} {status}')
    differing = [row for row in rows if row[1] not in SAME + ('extra',)]
    extra = [row for row in rows if row[1] == 'extra']
    print(f'identical={len(rows) - len(differing) - len(extra)} differing={len(differing)} extra={len(extra)}')
    if extra:
        print('note: files absent from the snapshot are reported but never deleted')
    return differing


def is_database(rel):
    """Database paths: the account db ends in .db, mihoyo_account has no extension."""
    return rel.endswith('.db') or rel == 'databases/mihoyo_account'


def sidecar_issues(device, manifest, scope, *, pending_writes=True):
    """Check complete database groups touched by the selection, including sidecar-only selection."""
    scope = set(scope)
    present = {entry['path']: entry for entry in manifest['files'] if entry['present']}
    databases = set()
    for rel in scope:
        base = rel
        for suffix in SIDECARS:
            if rel.endswith(suffix) and is_database(rel[:-len(suffix)]):
                base = rel[:-len(suffix)]
                break
        if is_database(base):
            databases.add(base)
    issues = []
    for db in sorted(databases):
        if db not in present and any(db + suffix in present for suffix in SIDECARS):
            issues.append(f'{db}: snapshot has a sidecar without its main database')
        for suffix in ('',) + SIDECARS:
            rel = db + suffix
            snap = present.get(rel)
            if pending_writes and snap is not None and rel in scope:
                continue
            path = f'{DATA_DIR}/{rel}'
            info = device.info(path)
            if snap is not None:
                if info is None:
                    issues.append(f'{rel}: missing but snapshot expects it')
                elif digest(device.read(path)) != snap['sha256']:
                    issues.append(f'{rel}: differs from snapshot')
            elif info is not None:
                issues.append(f'{rel}: present but absent from snapshot')
    return issues


def verify_local(record, manifest):
    problems, _ = inspect_local(record, manifest)
    return problems


def inspect_local(record, manifest, retain=()):
    """Verify local copies and retain the exact bytes that will be written by restore."""
    problems = []
    prepared = {}
    for entry in manifest['files']:
        if not entry['present']:
            continue
        relative = entry['path']
        path = local_path(record, relative)
        if not path.is_file():
            problems.append(f'{relative}: missing from the snapshot copy')
            continue
        data = path.read_bytes()
        if len(data) != entry['size'] or digest(data) != entry['sha256']:
            problems.append(f'{relative}: snapshot copy does not match its manifest')
        elif relative in retain:
            prepared[relative] = data
    return problems, prepared


def restore(device, record, apply, only=(), _rollback=False):
    manifest = load_manifest(record)
    recorded = manifest.get('serial')
    if recorded and recorded != device.serial:
        print(f'warning: snapshot was captured on device {recorded}, restoring to {device.serial}',
              file=sys.stderr)
    entries = select(manifest['files'], only)
    wanted = {entry['path'] for entry in entries}
    problems, prepared = inspect_local(record, manifest, wanted if apply else ())
    if problems:
        for line in problems:
            print(f'{line}', file=sys.stderr)
        print('snapshot is damaged; refusing to restore', file=sys.stderr)
        return 1
    if not _rollback and device.game_pids():
        # A rollback must not be blocked by a game that started after the safety capture: leaving
        # the mixed state is worse than overwriting while it runs.
        print(f'{device.serial}: {DATA_DIR} is in use, stop the game first', file=sys.stderr)
        return 1
    rows = remote_state(device, manifest, wanted)
    differing = show(rows)
    # A rollback must also remove files the safety snapshot says were absent, otherwise a stale
    # sidecar survives and "already matches" would falsely report a clean rollback.
    extras = [row for row in rows if row[1] == 'extra']
    if not _rollback:
        stale = sidecar_issues(device, manifest, wanted)
        if stale:
            # Refuse before any write: applying the main file while a stale WAL remains would leave
            # a mixed state (new main + old WAL). A rollback intentionally overwrites a mixed state.
            print('database sidecar mismatch: ' + '; '.join(sorted(stale)), file=sys.stderr)
            return 1
    if not differing and not (_rollback and extras):
        print('device already matches the snapshot; nothing to restore')
        return 0
    if not apply:
        print('dry run; add --apply to restore')
        return 0
    safety = None if _rollback else capture(device)
    try:
        return _apply_restore(device, manifest, entries, prepared, wanted, safety, only,
                              remove_extras=_rollback)
    except RestoreAbort as abort:
        print(str(abort), file=sys.stderr)
        if not _rollback and safety is not None:
            _rollback_from(device, safety)
        return 1
    except BaseException:
        if _rollback or safety is None:
            print('rollback failed: device may be in a mixed state', file=sys.stderr)
            raise
        # An interrupt (Ctrl+C, adb drop) between the main database and its sidecars would leave
        # SQLite replaying a stale journal. Undo the partial write from the safety snapshot.
        print(f'restore interrupted; rolling back from safety snapshot {safety}', file=sys.stderr)
        _rollback_from(device, safety)
        raise


def _rollback_from(device, safety):
    """Best effort: restore the pre-operation state, and say clearly when that failed."""
    print(f'rolling back from safety snapshot {safety}', file=sys.stderr)
    status = None
    error = None
    try:
        status = restore(device, safety, True, _rollback=True)
    except BaseException as rollback_error:
        error = rollback_error
    if status == 0:
        print('rollback verified: device is back at the pre-restore state', file=sys.stderr)
    elif error is not None:
        print(f'ROLLBACK FAILED: device may be in a mixed state; safety snapshot {safety}: '
              f'{error}', file=sys.stderr)
    else:
        print(f'ROLLBACK INCOMPLETE: safety snapshot {safety}', file=sys.stderr)


def _apply_restore(device, manifest, entries, prepared, wanted, safety, only, remove_extras):
    written = skipped = 0
    for entry in entries:
        rel = entry['path']
        path = f'{DATA_DIR}/{rel}'
        if not entry['present']:
            if remove_extras and device.info(path) is not None:
                # The safety snapshot recorded this file as absent: a rollback must delete the one
                # the interrupted restore created (typically a fresh -wal/-journal).
                device.root(f'rm -f {shlex.quote(path)}')
                print(f'{rel}: removed (absent in the snapshot)')
            continue
        data = prepared[rel]
        info = device.info(path)
        if info is not None and digest(device.read(path)) == entry['sha256']:
            if stable(info) != stable(entry):
                device.set_metadata(path, entry['uid'], entry['gid'], entry['mode'], entry['context'])
                if stable(device.info(path)) != stable(entry):
                    raise RestoreAbort(
                        f'{rel}: could not restore owner/mode/context; safety snapshot {safety}')
                print(f'{rel}: owner/mode/context restored')
            skipped += 1
            continue
        device.write(path, data)
        written += 1
        after_info = device.info(path)
        if after_info is None:
            raise RestoreAbort(f'{rel}: missing after write; safety snapshot {safety}')
        after = device.read(path)
        if digest(after) != entry['sha256']:
            print(f'{rel}: read-back does not match the snapshot; safety snapshot {safety}',
                  file=sys.stderr)
            raise RestoreAbort(f'{rel}: snapshot write was not verified; aborting restore')
        if stable(after_info) != stable(entry):
            device.set_metadata(path, entry['uid'], entry['gid'], entry['mode'], entry['context'])
            if stable(device.info(path)) != stable(entry):
                print(f'{rel}: could not restore owner/mode/context; safety snapshot {safety}', file=sys.stderr)
                raise RestoreAbort(f'{rel}: snapshot write was not verified; aborting restore')
            print(f'{rel}: owner/mode/context restored')
        print(f'{rel:52} written {len(data)} B  {entry["sha256"][:12]}')
    rows = remote_state(device, manifest, wanted)
    leftover = show(rows)
    stale = sidecar_issues(device, manifest, wanted, pending_writes=False)
    if stale:
        raise RestoreAbort('database sidecar mismatch: ' + '; '.join(sorted(stale)))
    print(f'written={written} skipped={skipped} safety_snapshot={safety} '
          f'scope={"selected" if only else "all"}')
    if leftover:
        raise RestoreAbort('restore finished with differences; safety snapshot can undo it')
    print('restore verified: every restored file matches the device')
    return 0


def command_verify(device, record, remote):
    manifest = load_manifest(record)
    problems = verify_local(record, manifest)
    if remote:
        problems += [f'{row[0]}: {row[1]}' for row in remote_state(device, manifest) if row[1] not in SAME + ('extra',)]
        problems += sidecar_issues(device, manifest, {entry['path'] for entry in manifest['files']},
                                   pending_writes=False)
    if problems:
        for line in dict.fromkeys(problems):
            print(line, file=sys.stderr)
        print(f'snapshot {record.name}: FAILED', file=sys.stderr)
        return 1
    print(f'snapshot {record.name}: OK' + (' (local + device)' if remote else ' (local copy)'))
    return 0


def command_diff(device, record, only=()):
    manifest = load_manifest(record)
    wanted = {entry['path'] for entry in select(manifest['files'], only)}
    rows = remote_state(device, manifest, wanted)
    differing = show(rows)
    sidecar = sidecar_issues(device, manifest, wanted, pending_writes=False)
    for issue in sidecar:
        print(issue, file=sys.stderr)
    return 1 if (differing or sidecar) else 0


def main():
    parser = argparse.ArgumentParser(description='Snapshot and restore StarRail account-state files')
    parser.add_argument('--serial', required=True)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('capture', help='take a new snapshot (game must be stopped)')
    verifier = sub.add_parser('verify', help='check a snapshot copy, optionally against the device')
    verifier.add_argument('--record', required=True)
    verifier.add_argument('--remote', action='store_true')
    differ = sub.add_parser('diff', help='compare the device with a snapshot')
    differ.add_argument('--record', required=True)
    differ.add_argument('--only', action='append', default=[], metavar='PATH')
    restorer = sub.add_parser('restore', help='restore a snapshot (dry run by default)')
    restorer.add_argument('--record', required=True)
    restorer.add_argument('--apply', action='store_true')
    restorer.add_argument('--only', action='append', default=[], metavar='PATH',
                          help='restore only this file (repeatable)')
    args = parser.parse_args()
    device = Device(args.serial)
    try:
        if args.command == 'capture':
            capture(device)
            return 0
        if args.command == 'verify':
            return command_verify(device, Path(args.record).expanduser().resolve(), args.remote)
        if args.command == 'diff':
            return command_diff(device, Path(args.record).expanduser().resolve(), args.only)
        return restore(device, Path(args.record).expanduser().resolve(), args.apply, args.only)
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
