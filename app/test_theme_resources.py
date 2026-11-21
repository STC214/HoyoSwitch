"""Check color and system-bar resources for both appearances."""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


RES = Path(__file__).resolve().parent / 'res'


def colors_for(variant):
    return {node.attrib['name']: node.text for node in ET.parse(RES / variant / 'colors.xml').getroot()}


def luminance(color):
    rgb = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in rgb]
    return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


class ThemeResourcesTest(unittest.TestCase):
    def test_palettes_have_matching_tokens_and_readable_text(self):
        light, dark = colors_for('values'), colors_for('values-night')
        self.assertEqual(light.keys(), dark.keys())
        for palette in (light, dark):
            for foreground, background in (('text_primary', 'window_bg'), ('text_primary', 'card_bg'),
                                           ('text_muted', 'card_bg'), ('text_muted', 'log_bg'),
                                           ('accent', 'pill_bg'), ('accent', 'accent_tonal'),
                                           ('on_accent', 'accent'), ('on_accent', 'danger'),
                                           ('danger', 'pill_bg')):
                with self.subTest(foreground=palette[foreground], background=palette[background]):
                    low, high = sorted(map(luminance, (palette[foreground], palette[background])))
                    self.assertGreaterEqual((high + 0.05) / (low + 0.05), 4.5)

    def test_system_bar_icons_follow_the_palette(self):
        for variant, light_icons_background in (('values', 'true'), ('values-night', 'false')):
            root = ET.parse(RES / variant / 'styles.xml').getroot()
            style = root.find("style[@name='AppTheme']")
            items = {node.attrib['name']: node.text for node in style}
            self.assertEqual(items['android:windowLightStatusBar'], light_icons_background)
            self.assertEqual(items['android:windowLightNavigationBar'], light_icons_background)
            self.assertEqual(items['android:forceDarkAllowed'], 'false')
