"""Offline tests for the login_status parsing and rewrite rules.

Run from the repository root:  python -m unittest discover -s tools/starrail -p "test_*.py"
"""

import unittest
from unittest import mock

import login_state
from login_state import login_status, with_value

BASIC = (
    b'<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n'
    b'<map>\n'
    b'    <boolean name="login_status" value="true" />\n'
    b'    <string name="env">PRODUCT</string>\n'
    b'</map>\n'
)

REVERSED_ATTRIBUTES = b'<map><boolean value="false" name="login_status" /></map>'


class ReadingTest(unittest.TestCase):
    def test_reads_true(self):
        self.assertEqual(login_status(BASIC), 'true')

    def test_reads_false_with_reversed_attributes(self):
        self.assertEqual(login_status(REVERSED_ATTRIBUTES), 'false')

    def test_missing_entry_is_rejected(self):
        with self.assertRaises(ValueError):
            login_status(b'<map><string name="env">PRODUCT</string></map>')

    def test_duplicate_entry_is_rejected(self):
        duplicated = b'<map><boolean name="login_status" value="true" />' \
                     b'<boolean name="login_status" value="false" /></map>'
        with self.assertRaises(ValueError):
            login_status(duplicated)

    def test_same_named_string_is_not_confused(self):
        data = b'<map><string name="login_status">true</string></map>'
        with self.assertRaises(ValueError):
            login_status(data)


class RewriteTest(unittest.TestCase):
    def test_xml_comment_is_neither_counted_nor_rewritten(self):
        comment = b'<!-- <boolean name="login_status" value="true" /> -->'
        with self.assertRaises(ValueError):
            login_status(b'<map>' + comment + b'</map>')
        live = b"<boolean name='login_status' value = 'true' />"
        source = b'<map>' + comment + live + b'</map>'
        self.assertEqual(login_status(source), 'true')
        self.assertEqual(with_value(source, 'false'),
                         b'<map>' + comment + live.replace(b'true', b'false') + b'</map>')

    def test_invalid_xml_is_not_treated_as_a_verified_value(self):
        with self.assertRaises(ValueError):
            login_status(BASIC.removesuffix(b'</map>\n'))

    def test_flip_changes_only_the_boolean(self):
        changed = with_value(BASIC, 'false')
        self.assertEqual(login_status(changed), 'false')
        self.assertEqual(changed.replace(b'value="false"', b'value="true"', 1), BASIC)
        self.assertEqual(len(changed) - len(BASIC), 1)

    def test_flip_keeps_neighbouring_entries(self):
        changed = with_value(BASIC, 'false')
        self.assertIn(b'<string name="env">PRODUCT</string>', changed)

    def test_reversed_attributes_are_rewritten(self):
        self.assertEqual(login_status(with_value(REVERSED_ATTRIBUTES, 'true')), 'true')

    def test_unchanged_value_is_rejected(self):
        with self.assertRaises(ValueError):
            with_value(BASIC, 'true')

    def test_unknown_value_is_rejected_elsewhere(self):
        with self.assertRaises(ValueError):
            login_status(BASIC.replace(b'value="true"', b'value="yes"'))


class SafetyTest(unittest.TestCase):
    def test_running_game_is_refused_without_name_error(self):
        device = mock.Mock()
        device.game_pids.return_value = [1234]
        self.assertEqual(login_state.command_set(device, 'true', False), 1)

    def test_exception_during_verification_still_restores_backup(self):
        device = mock.Mock()
        device.read.return_value = BASIC
        writes = []

        def flaky_write_checked(_device, _path, data, expected, _info, *, repair_metadata=False):
            writes.append((data, expected))
            if len(writes) == 1:
                self.assertFalse(repair_metadata)
                raise RuntimeError('parse failed')
            self.assertTrue(repair_metadata)
            return []

        with mock.patch('login_state.backup', return_value={'sha256_before': 'x'}), \
                mock.patch('login_state.write_checked', side_effect=flaky_write_checked):
            code = login_state.apply_change(device, '/data/prefs.xml', BASIC, b'new', 'true', 'false')
        self.assertEqual(code, 1)
        self.assertEqual(writes, [(b'new', 'false'), (BASIC, 'true')])


class BackupTest(unittest.TestCase):
    def test_restore_command_puts_serial_before_subcommand(self):
        import tempfile
        from pathlib import Path

        class FakeDevice:
            serial = 'SERIAL123'

            def info(self, path):
                return {'uid': '1000', 'gid': '1000', 'mode': '660',
                        'context': 'u:object_r:x:s0'}

        with tempfile.TemporaryDirectory() as tmp:
            meta = login_state.backup(Path(tmp) / 'rec', login_state.PORTE_PREFS,
                                      BASIC, 'true', 'false', FakeDevice())
        parts = meta['restore_command'].split()
        self.assertLess(parts.index('--serial'), parts.index('restore'))


class RestoreTest(unittest.TestCase):
    def test_restore_preserves_previous_content_on_write_or_readback_failure(self):
        import json
        import tempfile
        from pathlib import Path

        target = BASIC.replace(b'value="true"', b'value="false"')
        for previous, failure in ((BASIC, 'partial-write'), (BASIC, 'mismatch'), (BASIC, None),
                                  (b'<map><boolean', 'partial-write'), (b'<map><boolean', None),
                                  (BASIC, 'metadata')):
            with self.subTest(previous=previous, failure=failure), tempfile.TemporaryDirectory() as tmp:
                record = Path(tmp)
                backups = record / 'backups'
                (record / 'original.xml').write_bytes(target)
                meta = dict(source_path=login_state.PORTE_PREFS, sha256_before=login_state.digest(target))
                (record / 'meta.json').write_text(json.dumps(meta), encoding='utf-8')
                state = {'data': previous, 'mode': '600'}
                device = mock.Mock(serial='fixture')
                device.game_pids.return_value = []
                device.read.side_effect = lambda path: state['data']
                device.info.side_effect = lambda path: dict(uid='1', gid='1', mode=state['mode'], context='x',
                                                           size=len(state['data']), mtime=0)

                def write(path, data):
                    self.assertEqual(next(backups.glob('*/original.xml')).read_bytes(), previous)
                    if data == target and failure == 'partial-write':
                        state['data'] = data[:15]
                        raise OSError('fixture interrupted write')
                    state['data'] = data + b'\n' if data == target and failure == 'mismatch' else data
                    if data == target and failure == 'metadata':
                        state['mode'] = '644'

                device.write.side_effect = write
                device.set_metadata.side_effect = lambda path, uid, gid, mode, context: state.update(mode=mode)
                with mock.patch.object(login_state, 'BACKUPS', backups):
                    status = login_state.command_restore(device, str(record), True)
                self.assertEqual(status, int(failure is not None))
                self.assertEqual(state['data'], previous if failure else target)
                self.assertEqual(state['mode'], '600')
                self.assertEqual(device.write.call_count, 2 if failure else 1)
                self.assertEqual((record / 'original.xml').read_bytes(), target)

    def test_restore_rejects_backup_with_changed_bytes(self):
        import json
        import tempfile
        from pathlib import Path

        device = mock.Mock()
        device.game_pids.return_value = []
        device.read.return_value = BASIC

        with tempfile.TemporaryDirectory() as tmp:
            record = Path(tmp)
            (record / 'original.xml').write_bytes(BASIC.replace(b'true', b'false'))
            meta = {'source_path': login_state.PORTE_PREFS, 'sha256_before': 'f' * 64}
            (record / 'meta.json').write_text(json.dumps(meta), encoding='utf-8')
            self.assertEqual(login_state.command_restore(device, str(record), False), 1)


if __name__ == '__main__':
    unittest.main()
