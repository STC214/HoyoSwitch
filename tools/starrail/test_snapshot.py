"""Offline tests for snapshot bookkeeping; device I/O is covered by the on-device cycle.

Run from the repository root:  python -m unittest discover -s tools/starrail -p "test_*.py"
"""

import io
import unittest
from contextlib import redirect_stderr, redirect_stdout
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
        metadata = dict(uid='1', gid='1', mode='600', context='ctx', size=4)
        payload = {db: b'main', journal: b'new journal'}
        manifest = {'files': [dict(path=path, present=True, sha256=device.digest(data), **metadata)
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
    def test_restore_writes_the_exact_bytes_it_verified(self):
        rel = 'shared_prefs/fixture.xml'
        path = snapshot.DATA_DIR + '/' + rel
        original, verified = b'original', b'verified'
        metadata = dict(uid='1', gid='1', mode='600', context='ctx')
        entry = dict(path=rel, present=True, sha256=device.digest(verified), **metadata)
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


class DeviceCleanupTest(unittest.TestCase):
    def test_local_cleanup_runs_and_original_write_exception_survives(self):
        remote = device.Device('fixture')
        failure = OSError('write failure')
        remote._run = mock.Mock()
        remote.root = mock.Mock(side_effect=[failure, RuntimeError('offline')])
        with mock.patch.object(device.tempfile, 'NamedTemporaryFile',
                               mock.mock_open()) as temp, \
                mock.patch.object(device.os, 'unlink') as unlink, \
                redirect_stderr(io.StringIO()):
            temp.return_value.name = 'synthetic-local-file'
            with self.assertRaises(OSError) as caught:
                remote.write('/fixture', b'data')
        self.assertIs(caught.exception, failure)
        unlink.assert_called_once_with('synthetic-local-file')

    def test_remote_cleanup_failure_after_success_is_reported(self):
        remote = device.Device('fixture')
        remote._run = mock.Mock()
        remote.root = mock.Mock(side_effect=[None, RuntimeError('offline')])
        with mock.patch.object(device.tempfile, 'NamedTemporaryFile',
                               mock.mock_open()) as temp, \
                mock.patch.object(device.os, 'unlink') as unlink, \
                redirect_stderr(io.StringIO()):
            temp.return_value.name = 'synthetic-local-file'
            with self.assertRaisesRegex(RuntimeError, 'write completed'):
                remote.write('/fixture', b'data')
        unlink.assert_called_once_with('synthetic-local-file')


if __name__ == '__main__':
    unittest.main()
