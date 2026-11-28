"""Offline tests of tab-test assertions; never connects to a device."""

import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

import ui_tab_smoke as smoke


def hierarchy(selected):
    root = ET.Element('hierarchy')
    for label in smoke.TABS:
        ET.SubElement(root, 'node', {'package': smoke.PACKAGE, 'text': label,
                                    'class': 'android.widget.Button',
                                    'selected': str(label in selected).lower()})
    return root


class RapidSequenceTest(unittest.TestCase):
    def test_ui_dump_is_cleaned_even_if_parsing_fails(self):
        with patch.object(smoke, 'adb', side_effect=[b'', b'broken XML', b'']) as adb:
            with self.assertRaises(ET.ParseError):
                smoke.tree()
            self.assertEqual(adb.call_args.args, ('shell', 'rm', '-f', smoke.REMOTE_TREE))

    def test_no_input_cannot_pass_from_any_initial_tab(self):
        for current in smoke.TABS:
            with self.subTest(current=current):
                sequence = smoke.rapid_sequence(hierarchy({current}))
                self.assertNotEqual(sequence[-1], current)
                self.assertEqual(set(sequence), set(smoke.TABS))
                self.assertTrue(all(a != b for a, b in zip(sequence, sequence[1:])))

    def test_missing_or_multiple_selected_tabs_send_no_input(self):
        for selected in (set(), set(smoke.TABS)):
            with self.assertRaises(RuntimeError):
                smoke.rapid_sequence(hierarchy(selected))


if __name__ == '__main__':
    unittest.main()
