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
import sys
from datetime import datetime, timezone
from pathlib import Path

from device import DATA_DIR, Device, digest

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / 'account-snapshots' / 'state'
MANIFEST = 'manifest.json'

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
    if manifest.get('data_dir') != DATA_DIR:
        raise RuntimeError(f'{record}: snapshot was taken from {manifest.get("data_dir")}')
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
    record.mkdir(parents=True, exist_ok=False)
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
        target = local_path(record, rel)
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
    save_manifest(record, dict(
        tool='snapshot', version=1,
        captured_at=datetime.now(timezone.utc).isoformat(),
        serial=device.serial, data_dir=DATA_DIR, game_stopped=True, files=entries,
    ))
    present = sum(1 for entry in entries if entry['present'])
    print(f'snapshot={record} files={present}/{len(entries)}')
    print(f'restore: python tools/starrail/snapshot.py --serial {device.serial} restore --record "{record}" --apply')
    return record


def remote_state(device, manifest):
    rows = []
    for entry in manifest['files']:
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


def sidecar_issues(device, manifest, scope):
    """Problems with journal/WAL/SHM for databases in scope (stale or mismatched sidecars)."""
    present = {entry['path']: entry for entry in manifest['files'] if entry['present']}
    issues = []
    for rel in scope:
        if not is_database(rel):
            continue
        for suffix in ('-journal', '-wal', '-shm'):
            side = rel + suffix
            info = device.info(f'{DATA_DIR}/{side}')
            if side in present:
                snap = present[side]
                if info is None:
                    issues.append(f'{side}: missing but snapshot expects it')
                elif digest(device.read(f'{DATA_DIR}/{side}')) != snap['sha256']:
                    issues.append(f'{side}: differs from snapshot')
            elif info is not None:
                issues.append(f'{side}: present but absent from snapshot')
    return issues


def verify_local(record, manifest):
    problems = []
    for entry in manifest['files']:
        if not entry['present']:
            continue
        path = local_path(record, entry['path'])
        if not path.is_file():
            problems.append(f'{entry["path"]}: missing from the snapshot copy')
        elif digest(path.read_bytes()) != entry['sha256']:
            problems.append(f'{entry["path"]}: snapshot copy does not match its manifest')
    return problems


def restore(device, record, apply, only=()):
    manifest = load_manifest(record)
    entries = select(manifest['files'], only)
    wanted = {entry['path'] for entry in entries}
    problems = verify_local(record, manifest)
    if problems:
        for line in problems:
            print(f'{line}', file=sys.stderr)
        print('snapshot is damaged; refusing to restore', file=sys.stderr)
        return 1
    if device.game_pids():
        print(f'{device.serial}: {DATA_DIR} is in use, stop the game first', file=sys.stderr)
        return 1
    rows = [row for row in remote_state(device, manifest) if row[0] in wanted]
    differing = show(rows)
    stale = sidecar_issues(device, manifest, wanted)
    if stale:
        # Refuse before any write: applying the main file while a stale WAL remains would leave
        # a mixed state (new main + old WAL).
        print('database sidecar mismatch: ' + '; '.join(sorted(stale)), file=sys.stderr)
        return 1
    if not differing:
        print('device already matches the snapshot; nothing to restore')
        return 0
    if not apply:
        print('dry run; add --apply to restore')
        return 0
    safety = capture(device)
    written = skipped = 0
    for entry in entries:
        if not entry['present']:
            continue
        rel = entry['path']
        path = f'{DATA_DIR}/{rel}'
        data = local_path(record, rel).read_bytes()
        info = device.info(path)
        if info is not None and digest(device.read(path)) == entry['sha256']:
            if stable(info) != stable(entry):
                device.set_metadata(path, entry['uid'], entry['gid'], entry['mode'], entry['context'])
                if stable(device.info(path)) != stable(entry):
                    print(f'{rel}: could not restore owner/mode/context; safety snapshot {safety}',
                          file=sys.stderr)
                    return 1
                print(f'{rel}: owner/mode/context restored')
            skipped += 1
            continue
        device.write(path, data)
        written += 1
        after_info = device.info(path)
        after = device.read(path)
        if digest(after) != entry['sha256']:
            print(f'{rel}: read-back does not match the snapshot; safety snapshot {safety}', file=sys.stderr)
            return 1
        if stable(after_info) != stable(entry):
            device.set_metadata(path, entry['uid'], entry['gid'], entry['mode'], entry['context'])
            if stable(device.info(path)) != stable(entry):
                print(f'{rel}: could not restore owner/mode/context; safety snapshot {safety}', file=sys.stderr)
                return 1
            print(f'{rel}: owner/mode/context restored')
        print(f'{rel:52} written {len(data)} B  {entry["sha256"][:12]}')
    rows = [row for row in remote_state(device, manifest) if row[0] in wanted]
    leftover = show(rows)
    stale = sidecar_issues(device, manifest, wanted)
    if stale:
        print('database sidecar mismatch: ' + '; '.join(sorted(stale)), file=sys.stderr)
        return 1
    print(f'written={written} skipped={skipped} safety_snapshot={safety} '
          f'scope={"selected" if only else "all"}')
    if leftover:
        print('restore finished with differences; safety snapshot can undo it', file=sys.stderr)
        return 1
    print('restore verified: every restored file matches the device')
    return 0


def command_verify(device, record, remote):
    manifest = load_manifest(record)
    problems = verify_local(record, manifest)
    for line in problems:
        print(line, file=sys.stderr)
    if remote:
        problems += [f'{row[0]}: {row[1]}' for row in remote_state(device, manifest) if row[1] not in SAME + ('extra',)]
        problems += sidecar_issues(device, manifest, {entry['path'] for entry in manifest['files']})
    if problems:
        print(f'snapshot {record.name}: FAILED', file=sys.stderr)
        return 1
    print(f'snapshot {record.name}: OK' + (' (local + device)' if remote else ' (local copy)'))
    return 0


def command_diff(device, record, only=()):
    manifest = load_manifest(record)
    wanted = {entry['path'] for entry in select(manifest['files'], only)}
    rows = [row for row in remote_state(device, manifest) if row[0] in wanted]
    differing = show(rows)
    sidecar = sidecar_issues(device, manifest, wanted)
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
    except (RuntimeError, OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
