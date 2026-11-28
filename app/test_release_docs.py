"""Check release documentation without building or publishing."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class ReleaseDocsTest(unittest.TestCase):
    def test_readme_donation_and_disclaimer_match_app(self):
        readme = (ROOT / 'README.md').read_text(encoding='utf-8')
        resources = ET.parse(ROOT / 'app' / 'res' / 'values' / 'project_info.xml').getroot()
        def styled_markdown(name):
            resource = resources.find(f"string[@name='{name}']")
            self.assertIsNotNone(resource)
            self.assertTrue(resource.findall('b'), f'{name} should emphasize key text')
            return (resource.text or '') + ''.join(
                f'**{span.text}**{span.tail or ""}' for span in resource
            )

        disclaimer = styled_markdown('about_disclaimer')
        donation = styled_markdown('donation_intro')
        lines = readme.splitlines()
        self.assertEqual(lines[2], '## 捐赠二维码')
        donate, notice = readme.split('## 免责声明\n', 1)
        self.assertIn(donation, donate)
        self.assertIn(f'> {disclaimer}\n', notice)
        self.assertLess(notice.index(disclaimer), notice.index('在 **已取得 Root 权限的安卓手机**'))
        for name in ('mm_reward_qrcode_1794446017527.png', '1794446392439.jpg'):
            self.assertIn(f'src="QRcode/{name}"', donate)
            self.assertTrue((ROOT / 'QRcode' / name).is_file())

    def test_documented_version_matches_manifest(self):
        manifest = ET.parse(ROOT / 'app' / 'AndroidManifest.xml').getroot()
        version = manifest.get(f'{ANDROID}versionName')
        version_code = manifest.get(f'{ANDROID}versionCode')
        readme = (ROOT / 'README.md').read_text(encoding='utf-8')
        app_doc, = (ROOT / 'docs' / 'app').glob('*.md')
        text = app_doc.read_text(encoding='utf-8')
        self.assertIn(f'当前版本 **{version}**', readme)
        self.assertIn(f'当前版本 **{version}**（versionCode {version_code}）', text)
        self.assertIn('GitHub Release 下载（已发布版本）：<https://github.com/STC214/HoyoSwitch/releases>', readme)

    def test_markdown_has_no_control_characters_in_text(self):
        for path in [ROOT / 'README.md', *(ROOT / 'docs').rglob('*.md')]:
            data = path.read_bytes()
            match = re.search(rb'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]|\r(?!\n)', data)
            self.assertIsNone(match, f'{path.relative_to(ROOT)} contains a control character')

    def test_install_docs_warn_about_password_data_loss_and_describe_migration(self):
        readme = (ROOT / 'README.md').read_text(encoding='utf-8')
        install = readme.split('## 发布与安装\n', 1)[1].split('\n## ', 1)[0]
        self.assertRegex(install, r'卸载或清除应用数据[^\n]*密码备注[^\n]*本地密钥')
        app_doc, = (ROOT / 'docs' / 'app').glob('*.md')
        migration = next(line for line in app_doc.read_text(encoding='utf-8').splitlines()
                         if '**密码备注迁移**' in line)
        for required in ('保存一次', '重新录入', '非空密码', '确认清除已保存的密码备注',
                         '卸载或清除应用数据', '不等同于密码备份'):
            self.assertIn(required, migration)
