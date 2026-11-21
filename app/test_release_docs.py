"""Check release documentation without building or publishing."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class ReleaseDocsTest(unittest.TestCase):
    def test_published_version_matches_manifest(self):
        manifest = ET.parse(ROOT / 'app' / 'AndroidManifest.xml').getroot()
        version = manifest.get(f'{ANDROID}versionName')
        version_code = manifest.get(f'{ANDROID}versionCode')
        readme = (ROOT / 'README.md').read_text(encoding='utf-8')
        app_doc, = (ROOT / 'docs' / 'app').glob('*.md')
        text = app_doc.read_text(encoding='utf-8')
        self.assertIn(f'当前版本 **{version}**', readme)
        self.assertIn(f'当前版本 **{version}**（versionCode {version_code}）', text)
        self.assertIn(f'/releases/tag/v{version}>', readme)

    def test_markdown_has_no_control_characters_in_text(self):
        for path in [ROOT / 'README.md', *(ROOT / 'docs').rglob('*.md')]:
            data = path.read_bytes()
            match = re.search(rb'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]|\r(?!\n)', data)
            self.assertIsNone(match, f'{path.relative_to(ROOT)} contains a control character')
