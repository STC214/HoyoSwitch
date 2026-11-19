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


if __name__ == '__main__':
    unittest.main()
