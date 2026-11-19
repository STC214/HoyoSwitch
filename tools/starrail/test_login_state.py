"""Offline tests for the login_status parsing and rewrite rules.

Run from the repository root:  python -m unittest discover -s tools/starrail -p "test_*.py"
"""

import unittest

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


if __name__ == '__main__':
    unittest.main()
