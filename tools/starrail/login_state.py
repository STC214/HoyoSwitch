"""Root-only, reversible switch for StarRail's local Porte ``login_status`` flag.

The flag only decides whether the local Porte SDK may hand back a saved account.
It is not an authentication result: a ``true`` value is one precondition, not proof
of a live server session. See docs/research/星铁登录状态点位与验证记录.md.

Every write is preceded by a byte-exact backup, keeps the original inode (owner,
mode and SELinux context therefore survive), and is verified by re-reading the
device file. A failed verification restores the backup before exiting non-zero.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from xml.parsers import expat

from device import DATA_DIR, Device, digest

PORTE_PREFS = f'{DATA_DIR}/shared_prefs/porte_sdk_account_PRODUCT.xml'
SDK_PREFS = f'{DATA_DIR}/shared_prefs/mihoyo_sdk_preference.xml'
PACKAGE = 'com.miHoYo.hkrpg'
ROOT = Path(__file__).resolve().parents[2]
BACKUPS = ROOT / 'account-snapshots' / 'login-state'

ATTRIBUTE = re.compile(rb"""\s+([^\s=<>/]+)\s*=\s*(["'])(.*?)\2""", re.DOTALL)


def find_login_status(data):
    parser = expat.ParserCreate(namespace_separator='}')
    stack = []
    matches = []

    def start(tag, attributes):
        if stack == ['map'] and tag == 'boolean' and attributes.get('name') == 'login_status':
            matches.append((attributes.get('value'), parser.CurrentByteIndex))
        stack.append(tag)

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda _tag: stack.pop()
    try:
        parser.Parse(data, True)
    except expat.ExpatError as error:
        raise ValueError(f'invalid preferences XML: {error}') from error
    if len(matches) != 1:
        raise ValueError(f'expected exactly one login_status entry, found {len(matches)}')
    current, offset = matches[0]
    if current not in ('true', 'false'):
        raise ValueError('login_status entry does not carry a boolean value')
    return current, offset


def login_status(data):
    """Return the single boolean value of ``login_status`` in a prefs XML file."""
    return find_login_status(data)[0]


def with_value(data, value):
    """Return ``data`` with the login_status value replaced, leaving every other byte alone."""
    current, offset = find_login_status(data)
    if value not in ('true', 'false'):
        raise ValueError('expected a boolean replacement value')
    if current == value:
        raise ValueError('login_status already has the requested value')
    if not data.startswith(b'<boolean', offset):
        raise ValueError('login_status is not in a byte-compatible XML encoding')
    offset += len(b'<boolean')
    while attribute := ATTRIBUTE.match(data, offset):
        if attribute[1] == b'value' and attribute[3] == current.encode('ascii'):
            start, end = attribute.span(3)
            candidate = data[:start] + value.encode('ascii') + data[end:]
            if login_status(candidate) == value:
                return candidate
            break
        offset = attribute.end()
    raise ValueError('could not locate literal login_status value without changing other bytes')


def probe(device, path):
    info = device.info(path)
    if info is None:
        raise RuntimeError(f'{path}: no stat output')
    return info


def report(device, label, path, data, info):
    try:
        value = login_status(data)
    except ValueError as error:
        value = f'unparsed ({error})'
    print(f'{label}: login_status={value} sha256={digest(data)[:16]} '
          f'size={info["size"]} mtime={datetime.fromtimestamp(info["mtime"], timezone.utc).isoformat()} '
          f'owner={info["uid"]}:{info["gid"]} mode={info["mode"]} context={info["context"] or "unknown"}')
    return value


def status(device):
    pids = device.game_pids()
    print(f'game_running={"yes pid " + pids if pids else "no"}')
    porte = device.read(PORTE_PREFS)
    report(device, 'porte_current', PORTE_PREFS, porte, probe(device, PORTE_PREFS))
    sdk = device.read(SDK_PREFS)
    report(device, 'sdk_reference', SDK_PREFS, sdk, probe(device, SDK_PREFS))
    print('note: sdk_reference is read-only; it does not follow login/logout in the recorded samples.')
    return 0


def backup(record, path, original, before, after_value, device):
    record.mkdir(parents=True, exist_ok=False)
    (record / 'original.xml').write_bytes(original)
    meta = dict(
        recorded_at=datetime.now(timezone.utc).isoformat(),
        serial=device.serial,
        source_path=path,
        value_before=before,
        value_after=after_value,
        sha256_before=digest(original),
        size_before=len(original),
        restore_command=f'python tools/starrail/login_state.py --serial {device.serial} restore --record "{record}" --apply',
    )
    (record / 'meta.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return meta


def stable(info):
    """Fields a rewrite must never change; size and mtime are expected to move."""
    return {key: info[key] for key in ('uid', 'gid', 'mode', 'context')}


def write_checked(device, path, data, expected_value, info_before, *, repair_metadata=False):
    device.write(path, data)
    actual = device.read(path)
    info_after = probe(device, path)
    if repair_metadata and stable(info_after) != stable(info_before):
        device.set_metadata(path, **stable(info_before))
        info_after = probe(device, path)
    problems = []
    if actual != data:
        problems.append('device bytes differ from what was written')
    if expected_value is not None and login_status(actual) != expected_value:
        problems.append('device login_status does not match the requested value')
    if stable(info_after) != stable(info_before):
        problems.append(f'owner/mode/context changed: {stable(info_before)} -> {stable(info_after)}')
    print(f'size {info_before["size"]} -> {info_after["size"]}, mtime '
          f'{info_before["mtime"]} -> {info_after["mtime"]}')
    return problems


def apply_change(device, path, original, data, before_value, after_value):
    info_before = probe(device, path)
    previous = before_value if before_value is not None else 'unparsed'
    record = BACKUPS / f'{datetime.now().strftime("%Y%m%d-%H%M%S")}-{previous}-to-{after_value}'
    meta = backup(record, path, original, before_value, after_value, device)
    print(f'backup={record}')
    print(f'sha256_before={meta["sha256_before"]}')
    failure_status = 1
    try:
        problems = write_checked(device, path, data, after_value, info_before)
    except KeyboardInterrupt:
        failure_status = 130
        problems = ['write or read-back interrupted by Ctrl+C']
    except Exception as error:  # noqa: BLE001 - any failure must still roll back
        print(f'write verification raised: {error}', file=sys.stderr)
        problems = [f'write verification raised: {error}']
    if problems:
        print('verification failed; restoring backup', file=sys.stderr)
        for line in problems:
            print(f'  - {line}', file=sys.stderr)
        try:
            restore_problems = write_checked(device, path, original, before_value, info_before,
                                             repair_metadata=True)
        except KeyboardInterrupt:
            print(f'rollback interrupted; original backup is at {record}', file=sys.stderr)
            return 130
        except Exception as error:  # noqa: BLE001 - report instead of crashing silently
            print(f'restore raised: {error}; original backup is at {record}', file=sys.stderr)
            return failure_status
        if restore_problems:
            for line in restore_problems:
                print(f'  restore: {line}', file=sys.stderr)
            return failure_status
        print('backup restored and verified; device is back at the original state')
        return failure_status
    print(f'login_status {previous} -> {after_value} verified by read-back')
    print(f'sha256_after={digest(data)}')
    return 0


def command_set(device, target, apply):
    if device.game_pids():
        print(f'{PACKAGE} is running; stop it before writing', file=sys.stderr)
        return 1
    original = device.read(PORTE_PREFS)
    current = login_status(original)
    if current == target:
        print(f'login_status already {target}; nothing to change')
        return 0
    data = with_value(original, target)
    print(f'planned: {PORTE_PREFS} login_status {current} -> {target}')
    print(f'sha256_before={digest(original)} sha256_after={digest(data)}')
    if not apply:
        print('dry run; add --apply to write')
        return 0
    return apply_change(device, PORTE_PREFS, original, data, current, target)


def command_restore(device, record_dir, apply):
    record = Path(record_dir).expanduser().resolve()
    original = (record / 'original.xml').read_bytes()
    meta = json.loads((record / 'meta.json').read_text(encoding='utf-8'))
    if meta.get('source_path') != PORTE_PREFS:
        print(f'record targets {meta.get("source_path")}, refusing to restore', file=sys.stderr)
        return 1
    if digest(original) != meta.get('sha256_before'):
        print('original.xml does not match the recorded sha256_before; refusing to restore',
              file=sys.stderr)
        return 1
    expected = login_status(original)
    if device.game_pids():
        print(f'{PACKAGE} is running; stop it before writing', file=sys.stderr)
        return 1
    previous = device.read(PORTE_PREFS)
    try:
        current = login_status(previous)
    except ValueError:
        current = None  # A valid backup must also be able to repair a damaged device file.
    print(f'restoring record {record.name}: login_status {current or "unparsed"} -> {expected}')
    if not apply:
        print('dry run; add --apply to restore')
        return 0
    result = apply_change(device, PORTE_PREFS, previous, original, current, expected)
    if result == 0:
        print(f'restored from {meta["sha256_before"]}; read-back verified')
    return result


def main():
    parser = argparse.ArgumentParser(description='Reversible switch for StarRail login_status')
    parser.add_argument('--serial', required=True)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('status', help='read both login_status values without writing')
    setter = sub.add_parser('set', help='change the Porte login_status value')
    setter.add_argument('--value', required=True, choices=('true', 'false'))
    setter.add_argument('--apply', action='store_true')
    restorer = sub.add_parser('restore', help='restore a recorded backup')
    restorer.add_argument('--record', required=True)
    restorer.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    device = Device(args.serial)
    try:
        if args.command == 'status':
            return status(device)
        if args.command == 'set':
            return command_set(device, args.value, args.apply)
        return command_restore(device, args.record, args.apply)
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
