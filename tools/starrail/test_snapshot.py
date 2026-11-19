"""Offline tests for snapshot bookkeeping; device I/O is covered by the on-device cycle.

Run from the repository root:  python -m unittest discover -s tools/starrail -p "test_*.py"
"""

import io
import unittest
from contextlib import redirect_stdout

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


if __name__ == '__main__':
    unittest.main()
