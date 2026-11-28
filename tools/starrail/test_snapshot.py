"""Offline tests for snapshot bookkeeping; device I/O is covered by the on-device cycle.

Run from the repository root:  python -m unittest discover -s tools/starrail -p "test_*.py"
"""

import base64
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import device
import snapshot


class TargetListTest(unittest.TestCase):
    def test_every_database_has_its_sidecars_listed(self):
        for name in snapshot.DATABASES:
            for suffix in ('',) + snapshot.SIDECARS:
                self.assertIn(name + suffix, snapshot.TARGETS)

    def test_targets_are_unique(self):
        self.assertEqual(len(snapshot.TARGETS), len(set(snapshot.TARGETS)))

    def test_account_prefs_are_covered(self):
        for name in ('porte_sdk_account_PRODUCT.xml', 'porte_sdk_token_PRODUCT.xml',
                     'mihoyo_sdk_preference.xml', 'combo_widget.xml'):
            self.assertIn(f'shared_prefs/{name}', snapshot.TARGETS)


class StableFieldsTest(unittest.TestCase):
    def test_only_ownership_and_context_are_stable(self):
        info = dict(uid='10300', gid='10300', mode='660', context='u:object_r:app_data_file:s0',
                    size=120, mtime=1795065282)
        self.assertEqual(snapshot.stable(info),
                         dict(uid='10300', gid='10300', mode='660', context='u:object_r:app_data_file:s0'))


class ShowTest(unittest.TestCase):
    def run_show(self, rows):
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            differing = snapshot.show(rows)
        return differing, buffer.getvalue()

    def test_only_real_differences_are_counted(self):
        rows = [('a', 'same', {}), ('b', 'gone', None), ('c', 'extra', {}),
                ('d', 'different', {}), ('e', 'missing', None), ('f', 'metadata-differs', {})]
        differing, output = self.run_show(rows)
        self.assertEqual([row[0] for row in differing], ['d', 'e', 'f'])
        self.assertIn('differing=3', output)
        self.assertIn('extra=1', output)

    def test_identical_snapshot_reports_no_difference(self):
        differing, output = self.run_show([('a', 'same', {}), ('b', 'gone', None)])
        self.assertEqual(differing, [])
        self.assertIn('differing=0', output)


class SelectTest(unittest.TestCase):
    ENTRIES = [dict(path='shared_prefs/a.xml', present=True),
               dict(path='databases/b.db', present=True),
               dict(path='databases/b.db-journal', present=False)]

    def test_empty_selection_keeps_everything(self):
        self.assertEqual(snapshot.select(self.ENTRIES, []), self.ENTRIES)

    def test_selection_keeps_requested_order_of_entries(self):
        chosen = snapshot.select(self.ENTRIES, ['databases/b.db', 'shared_prefs/a.xml'])
        self.assertEqual([entry['path'] for entry in chosen], ['shared_prefs/a.xml', 'databases/b.db'])

    def test_unknown_path_is_rejected(self):
        with self.assertRaises(ValueError):
            snapshot.select(self.ENTRIES, ['shared_prefs/missing.xml'])

    def test_absent_entry_can_still_be_named(self):
        chosen = snapshot.select(self.ENTRIES, ['databases/b.db-journal'])
        self.assertEqual(len(chosen), 1)
        self.assertFalse(chosen[0]['present'])


class ManifestTest(unittest.TestCase):
    def test_invalid_manifest_is_rejected_before_device_access(self):
        entry = dict(path=snapshot.PREFS[0], present=True, sha256=device.digest(b'x'), size=1,
                     uid='1', gid='1', mode='600', context='ctx')
        record = mock.MagicMock()
        manifest_file = record.__truediv__.return_value
        manifest_file.is_file.return_value = True
        for files in ([dict(entry, mode='bad')], [dict(entry, path='unknown.xml')],
                      [entry, entry], [dict(entry, size=-1)], [dict(entry, present='true')]):
            with self.subTest(files=files):
                manifest_file.read_text.return_value = json.dumps(dict(data_dir=snapshot.DATA_DIR, files=files))
                remote = mock.Mock()
                with self.assertRaises(ValueError):
                    snapshot.restore(remote, record, True)
                self.assertEqual(remote.mock_calls, [])
        valid = dict(data_dir=snapshot.DATA_DIR, files=[entry])
        manifest_file.read_text.return_value = json.dumps(valid)
        self.assertEqual(snapshot.load_manifest(record), valid)

    def test_local_size_must_match_manifest_even_if_hash_matches(self):
        entry = dict(path=snapshot.PREFS[0], present=True, sha256=device.digest(b'x'), size=2)
        local = mock.Mock()
        local.is_file.return_value = True
        local.read_bytes.return_value = b'x'
        with mock.patch.object(snapshot, 'local_path', return_value=local):
            issues, prepared = snapshot.inspect_local(mock.Mock(), {'files': [entry]}, {entry['path']})
        self.assertEqual(len(issues), 1)
        self.assertEqual(prepared, {})


class SelectedRemoteReadsTest(unittest.TestCase):
    def test_only_limits_diff_and_restore_reads(self):
        selected = snapshot.PREFS[0]
        unrelated = snapshot.DATABASES[0]
        path = f'{snapshot.DATA_DIR}/{selected}'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        payload = b'snapshot'
        manifest = {'files': [dict(path=relative, present=True, sha256=device.digest(payload), **metadata)
                              for relative in (selected, unrelated)]}
        for action in ('diff', 'dry-run', 'apply'):
            with self.subTest(action=action):
                state = {path: payload if action == 'diff' else b'old'}
                remote = mock.Mock()
                remote.game_pids.return_value = []
                remote.info.side_effect = {path: metadata}.__getitem__
                remote.read.side_effect = state.__getitem__
                remote.write.side_effect = state.__setitem__
                with mock.patch.object(snapshot, 'load_manifest', return_value=manifest), \
                        mock.patch.object(snapshot, 'inspect_local', return_value=([], {selected: payload})), \
                        mock.patch.object(snapshot, 'capture', return_value='fixture') as safety, \
                        redirect_stdout(io.StringIO()):
                    if action == 'diff':
                        status = snapshot.command_diff(remote, mock.Mock(), [selected])
                    else:
                        status = snapshot.restore(remote, mock.Mock(), action == 'apply', [selected])
                self.assertEqual(status, 0)
                self.assertEqual(safety.call_count, int(action == 'apply'))
                if action == 'apply':
                    remote.write.assert_called_once_with(path, payload)
                else:
                    remote.write.assert_not_called()


class SidecarIssuesTest(unittest.TestCase):
    def _device(self, present=()):
        class FakeDevice:
            def __init__(self, present):
                self.present = set(present)

            def info(self, path):
                rel = path
                prefix = snapshot.DATA_DIR + '/'
                if rel.startswith(prefix):
                    rel = rel[len(prefix):]
                return None if rel not in self.present else dict(
                    uid='1', gid='1', mode='660', context='u:object_r:x:s0', size=0, mtime=0)

            def read(self, path):
                return b'x'

        return FakeDevice(present)

    def test_in_scope_sidecar_is_not_flagged(self):
        manifest = {'files': [
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db', 'present': True, 'sha256': 'x'},
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db-journal', 'present': True, 'sha256': 'y'},
        ]}
        scope = {'databases/PORTE_ACCOUNT_PRODUCT.db',
                 'databases/PORTE_ACCOUNT_PRODUCT.db-journal'}
        # device has only the db; journal (in scope) must not block
        device = self._device(present=('databases/PORTE_ACCOUNT_PRODUCT.db',))
        self.assertEqual(snapshot.sidecar_issues(device, manifest, scope), [])

    def test_out_of_scope_missing_sidecar_is_flagged(self):
        manifest = {'files': [
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db', 'present': True, 'sha256': 'x'},
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db-journal', 'present': True, 'sha256': 'y'},
        ]}
        scope = {'databases/PORTE_ACCOUNT_PRODUCT.db'}
        device = self._device(present=('databases/PORTE_ACCOUNT_PRODUCT.db',))
        issues = snapshot.sidecar_issues(device, manifest, scope)
        self.assertEqual(issues, ['databases/PORTE_ACCOUNT_PRODUCT.db-journal: '
                                  'missing but snapshot expects it'])

    def test_stale_sidecar_marked_absent_but_in_scope_is_flagged(self):
        manifest = {'files': [
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db', 'present': True, 'sha256': 'x'},
            {'path': 'databases/PORTE_ACCOUNT_PRODUCT.db-journal', 'present': False},
        ]}
        scope = {'databases/PORTE_ACCOUNT_PRODUCT.db',
                 'databases/PORTE_ACCOUNT_PRODUCT.db-journal'}
        device = self._device(present=('databases/PORTE_ACCOUNT_PRODUCT.db',
                                       'databases/PORTE_ACCOUNT_PRODUCT.db-journal'))
        issues = snapshot.sidecar_issues(device, manifest, scope)
        self.assertEqual(issues, ['databases/PORTE_ACCOUNT_PRODUCT.db-journal: '
                                  'present but absent from snapshot'])

    def test_journal_only_restore_checks_main_before_any_write(self):
        db = snapshot.DATABASES[0]
        journal = db + '-journal'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        payload = {db: b'main', journal: b'new journal'}
        manifest = {'files': [dict(path=path, present=True, sha256=device.digest(data), size=len(data), **metadata)
                              for path, data in payload.items()]}
        prefix = snapshot.DATA_DIR + '/'
        for selected, main, expected_status, expected_writes in (
            ((journal,), b'wrong main', 1, 0),
            ((journal,), payload[db], 0, 1),
            ((db, journal), b'wrong main', 0, 2),
        ):
            with self.subTest(selected=selected, main=main):
                state = {prefix + db: main, prefix + journal: b'old journal'}
                remote = mock.Mock()
                remote.game_pids.return_value = []
                remote.info.side_effect = lambda path: metadata if path in state else None
                remote.read.side_effect = state.__getitem__
                remote.write.side_effect = state.__setitem__

                def local_path(record, relative):
                    return mock.Mock(read_bytes=mock.Mock(return_value=payload[relative]))

                with mock.patch.object(snapshot, 'load_manifest', return_value=manifest), \
                        mock.patch.object(snapshot, 'capture', return_value='fixture'), \
                        mock.patch.object(snapshot, 'local_path', side_effect=local_path), \
                        redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    status = snapshot.restore(remote, mock.Mock(), True, selected)
                self.assertEqual(status, expected_status)
                self.assertEqual(remote.write.call_count, expected_writes)
                if expected_status == 0:
                    self.assertEqual(state[prefix + db], payload[db])
                    self.assertEqual(state[prefix + journal], payload[journal])

    def test_remote_verify_prints_database_group_mismatch(self):
        db = snapshot.DATABASES[0]
        journal = db + '-journal'
        manifest = {'files': [dict(path=db, present=True, sha256=device.digest(b'x')),
                              dict(path=journal, present=False)]}
        remote = self._device(present=(db, journal))
        stderr = io.StringIO()
        with mock.patch.object(snapshot, 'load_manifest', return_value=manifest), \
                mock.patch.object(snapshot, 'verify_local', return_value=[]), \
                mock.patch.object(snapshot, 'remote_state', return_value=[]), redirect_stderr(stderr):
            self.assertEqual(snapshot.command_verify(remote, mock.Mock(), True), 1)
        self.assertIn(f'{journal}: present but absent from snapshot', stderr.getvalue())


class RestorePayloadTest(unittest.TestCase):
    def test_missing_metadata_after_write_reports_safety_snapshot(self):
        relative = snapshot.PREFS[0]
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        data = b'backup'
        entry = dict(path=relative, present=True, sha256=device.digest(data), **metadata)
        remote = mock.Mock()
        remote.game_pids.return_value = []
        remote.info.side_effect = [metadata, metadata, None]
        remote.read.side_effect = [b'old', b'old', data]
        stderr = io.StringIO()
        with mock.patch.object(snapshot, 'load_manifest', return_value={'files': [entry]}), \
                mock.patch.object(snapshot, 'capture', return_value='safe-copy'), \
                mock.patch.object(snapshot, 'inspect_local', return_value=([], {relative: data})), \
                redirect_stdout(io.StringIO()), redirect_stderr(stderr):
            self.assertEqual(snapshot.restore(remote, mock.Mock(), True), 1)
        self.assertIn('safe-copy', stderr.getvalue())
        remote.set_metadata.assert_not_called()

    def test_restore_writes_the_exact_bytes_it_verified(self):
        rel = 'shared_prefs/fixture.xml'
        path = snapshot.DATA_DIR + '/' + rel
        original, verified = b'original', b'verified'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        entry = dict(path=rel, present=True, sha256=device.digest(verified), size=len(verified), **metadata)
        state = {path: original}
        remote = mock.Mock()
        remote.game_pids.return_value = []
        remote.info.return_value = metadata
        remote.read.side_effect = state.__getitem__
        remote.write.side_effect = state.__setitem__
        local = mock.Mock()
        local.is_file.return_value = True
        local.read_bytes.side_effect = [verified, b'changed after verification']
        with mock.patch.object(snapshot, 'load_manifest', return_value={'files': [entry]}), \
                mock.patch.object(snapshot, 'capture', return_value='fixture'), \
                mock.patch.object(snapshot, 'local_path', return_value=local), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(snapshot.restore(remote, mock.Mock(), True), 0)
        local.read_bytes.assert_called_once_with()
        remote.write.assert_called_once_with(path, verified)
        self.assertEqual(state[path], verified)


class RestoreInterruptTest(unittest.TestCase):
    def test_interrupt_rolls_back_from_the_safety_snapshot(self):
        db = snapshot.DATABASES[0]
        journal = db + '-journal'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        payload = {db: b'main', journal: b'journal'}
        manifest = {'files': [dict(path=path, present=True, sha256=device.digest(data),
                                   size=len(data), **metadata)
                              for path, data in payload.items()]}
        prefix = snapshot.DATA_DIR + '/'
        state = {prefix + db: b'old main', prefix + journal: b'old journal'}
        remote = mock.Mock()
        remote.game_pids.return_value = []
        remote.info.side_effect = lambda path: metadata if path in state else None
        remote.read.side_effect = state.__getitem__
        interrupted = []

        def write(path, data):
            if not interrupted:
                interrupted.append(path)
                raise KeyboardInterrupt()
            state[path] = data

        remote.write.side_effect = write

        def local_path(record, relative):
            return mock.Mock(read_bytes=mock.Mock(return_value=payload[relative]))

        stderr = io.StringIO()
        with mock.patch.object(snapshot, 'load_manifest', return_value=manifest), \
                mock.patch.object(snapshot, 'capture', return_value='safe-copy'), \
                mock.patch.object(snapshot, 'local_path', side_effect=local_path), \
                redirect_stdout(io.StringIO()), redirect_stderr(stderr):
            with self.assertRaises(KeyboardInterrupt):
                snapshot.restore(remote, mock.Mock(), True)
        self.assertIn('rolling back from safety snapshot safe-copy', stderr.getvalue())
        self.assertIn('rollback verified', stderr.getvalue())
        self.assertEqual(state[prefix + db], payload[db])
        self.assertEqual(state[prefix + journal], payload[journal])


class RollbackExtrasTest(unittest.TestCase):
    def test_rollback_removes_extra_sidecar_instead_of_claiming_success(self):
        db = snapshot.DATABASES[0]
        wal = db + '-wal'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        manifest = {'files': [dict(path=db, present=True, sha256=device.digest(b'main'),
                                   size=4, **metadata),
                              dict(path=wal, present=False)]}
        prefix = snapshot.DATA_DIR + '/'
        state = {prefix + db: b'main', prefix + wal: b'stale wal'}
        removed = []
        remote = mock.Mock()
        remote.game_pids.return_value = []
        remote.info.side_effect = lambda path: metadata if path in state else None
        remote.read.side_effect = state.__getitem__

        def root(script):
            removed.append(script)
            state.pop(prefix + wal, None)
            return b''

        remote.root.side_effect = root
        local = mock.Mock(read_bytes=mock.Mock(return_value=b'main'))
        with mock.patch.object(snapshot, 'load_manifest', return_value=manifest), \
                mock.patch.object(snapshot, 'local_path', return_value=local), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(snapshot.restore(remote, mock.Mock(), True, _rollback=True), 0)
        self.assertNotIn(prefix + wal, state)
        self.assertTrue(any('rm -f' in script for script in removed))


class CaptureStagingTest(unittest.TestCase):
    def test_failed_capture_leaves_no_partial_record(self):
        remote = mock.Mock()
        remote.game_pids.return_value = []
        remote.info.return_value = dict(uid='1', gid='1', mode='600', context='ctx',
                                        size=3, mtime=0)
        reads = []

        def read(path):
            reads.append(path)
            if len(reads) > 2:
                raise RuntimeError('adb dropped')
            return b'abc'

        remote.read.side_effect = read
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            with mock.patch.object(snapshot, 'STATE', state), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(RuntimeError):
                    snapshot.capture(remote)
            self.assertEqual(list(state.iterdir()), [])


class DeviceTransportTest(unittest.TestCase):
    def test_info_uses_shell_so_exit_three_means_missing(self):
        remote = device.Device('fixture')
        result = subprocess.CompletedProcess([], 3, b'', b'')
        with mock.patch.object(device.subprocess, 'run', return_value=result) as spawn:
            self.assertIsNone(remote.info('/missing'))
        command = spawn.call_args.args[0]
        self.assertEqual(command[:4], ['adb', '-s', 'fixture', 'shell'])
        self.assertIn('su -c', command[-1])

    def test_info_parses_metadata_from_shell_output(self):
        remote = device.Device('fixture')
        out = b'10300 10300 660 12 1795\n' + b'u:object_r:app_data_file:s0:c1 ctx\n'
        result = subprocess.CompletedProcess([], 0, out, b'')
        with mock.patch.object(device.subprocess, 'run', return_value=result):
            info = remote.info('/p')
        self.assertEqual(info['uid'], '10300')
        self.assertEqual(info['size'], 12)
        self.assertEqual(info['context'], 'u:object_r:app_data_file:s0:c1')

    def test_read_uses_exec_out_and_detects_the_failure_marker(self):
        remote = device.Device('fixture')
        missing = subprocess.CompletedProcess([], 0, device.READ_FAILURE, b'')
        with mock.patch.object(device.subprocess, 'run', return_value=missing) as spawn:
            with self.assertRaises(RuntimeError):
                remote.read('/missing')
        self.assertEqual(spawn.call_args.args[0][:4], ['adb', '-s', 'fixture', 'exec-out'])

    def test_root_uses_shell_so_failures_are_visible(self):
        remote = device.Device('fixture')
        failed = subprocess.CompletedProcess([], 3, b'', b'')
        with mock.patch.object(device.subprocess, 'run', return_value=failed):
            with self.assertRaises(RuntimeError):
                remote.root('exit 3')


class DeviceProcessTest(unittest.TestCase):
    def test_pidof_distinguishes_no_match_from_failed_probe(self):
        remote = device.Device('fixture')
        for code, stdout, stderr, expected in ((0, b'123 456\n', b'', '123 456'),
                                               (1, b'', b'', ''),
                                               (1, b'', b'adb: device offline', None),
                                               (0, b'', b'', None),
                                               (127, b'', b'pidof: not found', None)):
            with self.subTest(code=code, stdout=stdout, stderr=stderr):
                result = subprocess.CompletedProcess([], code, stdout, stderr)
                with mock.patch.object(device.subprocess, 'run', return_value=result):
                    if expected is None:
                        with self.assertRaises(RuntimeError):
                            remote.game_pids()
                    else:
                        self.assertEqual(remote.game_pids(), expected)


class DeviceWriteTest(unittest.TestCase):
    def test_write_streams_base64_through_su_without_a_shared_temp_file(self):
        remote = device.Device('fixture')
        payload = bytes(range(256)) * 2
        result = subprocess.CompletedProcess([], 0, b'', b'')
        with mock.patch.object(device.subprocess, 'run', return_value=result) as spawn:
            remote.write('/data/user/0/com.miHoYo.hkrpg/editable.xml', payload)
        command = spawn.call_args.args[0]
        self.assertEqual(command[:3], ['adb', '-s', 'fixture'])
        self.assertIn('su -c', command[-1])
        self.assertIn('base64 -d >', command[-1])
        self.assertNotIn('/data/local/tmp', ' '.join(command))
        # Base64 carries 0x1A and CR safely; raw stdin would truncate the database.
        self.assertEqual(base64.b64decode(spawn.call_args.kwargs['input']), payload)

    def test_write_failure_surfaces_remote_stderr(self):
        remote = device.Device('fixture')
        result = subprocess.CompletedProcess([], 1, b'', b'perm denied')
        with mock.patch.object(device.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(RuntimeError, 'perm denied'):
                remote.write('/fixture', b'data')


if __name__ == '__main__':
    unittest.main()
