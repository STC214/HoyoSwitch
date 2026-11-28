"""Offline tests for release signing; run with python -B -m unittest discover -s app."""

import io
import os
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

import build


class ApkOutputTest(unittest.TestCase):
    def test_debug_key_only_publishes_a_complete_key_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            key = root / 'local-test.p12'
            work = root / 'work'
            work.mkdir()
            candidate = work / 'debug-key.p12'

            def fail(*args):
                self.assertEqual(args[args.index('-keystore') + 1], candidate)
                candidate.write_bytes(b'incomplete')
                raise subprocess.CalledProcessError(1, 'keytool')

            with patch.object(build, 'run', side_effect=fail):
                with self.assertRaises(subprocess.CalledProcessError):
                    build.ensure_debug_key(key, work)
            self.assertFalse(key.exists())

            def finish(*args):
                candidate.write_bytes(b'complete')

            with patch.object(build, 'run', side_effect=finish):
                build.ensure_debug_key(key, work)
            self.assertEqual(key.read_bytes(), b'complete')

            other_key = root / 'competing.p12'

            def concurrent(*args):
                candidate.write_bytes(b'other-complete')
                other_key.write_bytes(b'first-wins')

            with patch.object(build, 'run', side_effect=concurrent):
                build.ensure_debug_key(other_key, work)
            self.assertEqual(other_key.read_bytes(), b'first-wins')
            with patch.object(build, 'run') as run:
                build.ensure_debug_key(other_key, work)
                run.assert_not_called()

    def test_each_build_has_a_private_workspace_cleaned_after_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'build'
            output.mkdir()
            existing = output / 'local-test.p12'
            existing.write_bytes(b'existing-fixture')
            seen = []

            def fake_build(work, credentials):
                self.assertEqual(work.parent, output)
                self.assertIsNone(credentials)
                self.assertTrue(work.is_dir())
                (work / 'signed.apk').write_bytes(b'candidate-fixture')
                seen.append(work)
                if len(seen) == 1:
                    raise RuntimeError('fixture failure')

            with patch.object(build, 'OUTPUT', output), patch.object(build, 'build_apk', fake_build):
                with self.assertRaisesRegex(RuntimeError, 'fixture failure'):
                    build.main([])
                build.main([])
            self.assertEqual(len(set(seen)), 2)
            self.assertTrue(all(not work.exists() for work in seen))
            self.assertEqual(existing.read_bytes(), b'existing-fixture')

    def test_only_verified_package_replaces_existing_distribution(self):
        signer = ('apksigner-fixture',)
        for release in (True, False):
            with self.subTest(release=release), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                candidate = root / 'candidate'
                destination = root / 'dist' / 'current'
                destination.parent.mkdir()
                candidate.write_bytes(b'new-fixture')
                destination.write_bytes(b'old-fixture')
                verifier = 'verify_release_certificate' if release else 'run'
                failure = SystemExit('wrong key') if release else subprocess.CalledProcessError(1, signer)
                with patch.object(build, verifier, side_effect=failure):
                    with self.assertRaises(type(failure)):
                        build.publish_apk(signer, candidate, destination, release=release)
                self.assertEqual(destination.read_bytes(), b'old-fixture')
                self.assertTrue(candidate.is_file())

                with patch.object(build, verifier) as verify:
                    build.publish_apk(signer, candidate, destination, release=release)
                if release:
                    verify.assert_called_once_with(signer, candidate)
                else:
                    verify.assert_called_once_with(*signer, 'verify', '--verbose', candidate)
                self.assertEqual(destination.read_bytes(), b'new-fixture')
                self.assertFalse(candidate.exists())


class ReleaseIdentityTest(unittest.TestCase):
    def test_release_manifest_disables_debugging_without_editing_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'AndroidManifest.xml'
            destination = Path(tmp) / 'AndroidManifest.release.xml'
            for attr in ("d:debuggable = 'true'", ''):
                with self.subTest(attr=attr):
                    original = (f'<manifest xmlns:d="{build.ANDROID_NS}" package="fixture.app">'
                                f'<application {attr} d:allowBackup="false">'
                                '<activity d:name=".MainActivity" />'
                                '</application></manifest>')
                    source.write_text(original, encoding='utf-8')
                    build.write_release_manifest(source, destination)
                    manifest = ET.parse(destination).getroot()
                    android_attr = f'{{{build.ANDROID_NS}}}'
                    app = manifest.find('application')
                    self.assertEqual(app.get(android_attr + 'debuggable'), 'false')
                    self.assertEqual(app.get(android_attr + 'allowBackup'), 'false')
                    self.assertEqual(app.find('activity').get(android_attr + 'name'), '.MainActivity')
                    self.assertEqual(source.read_text(encoding='utf-8'), original)

    def test_invalid_cli_and_missing_release_key_leave_output_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / 'build'
            with patch.object(build, 'OUTPUT', output), patch.object(build, 'run') as run:
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as invalid:
                    build.main(['--releas'])
                self.assertEqual(invalid.exception.code, 2)
                with patch.object(build, 'RELEASE_KEY', root / 'missing.p12'):
                    with self.assertRaisesRegex(SystemExit, 'restore the original'):
                        build.main(['--release'])
                self.assertFalse(output.exists())
                run.assert_not_called()

    def test_requires_exactly_the_pinned_apk_signer(self):
        pinned = build.EXPECTED_CERT_SHA256
        other = 'f' * 64
        good = f'Signer #1 certificate SHA-256 digest: {pinned.upper()}'
        wrong = f'Signer #1 certificate SHA-256 digest: {other}'
        stamp = f'Source Stamp Signer certificate SHA-256 digest: {pinned}'
        extra = f'Signer #2 certificate SHA-256 digest: {other}'
        for output, accepted in ((good, True), (f'{good}\n{stamp}', True),
                                 (wrong, False), (f'{wrong}\n{stamp}', False),
                                 (f'{good}\n{extra}', False), ('', False)):
            with self.subTest(output=output):
                result = subprocess.CompletedProcess([], 0, output, '')
                with patch.object(build.subprocess, 'run', return_value=result):
                    if accepted:
                        build.verify_release_certificate(('signer',), Path('fixture.apk'))
                    else:
                        with self.assertRaises(SystemExit):
                            build.verify_release_certificate(('signer',), Path('fixture.apk'))

    def test_missing_keystore_preserves_existing_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = Path(tmp) / 'release.p12'
            props = Path(tmp) / 'keystore.txt'
            original = b'alias=fixture\nstorepass=fixture-password\n'
            props.write_bytes(original)
            with patch.object(build, 'RELEASE_KEY', key), patch.object(build, 'RELEASE_PROPS', props):
                with self.assertRaisesRegex(SystemExit, 'restore the original'):
                    build.release_credentials()
                self.assertFalse(key.exists())
                self.assertEqual(props.read_bytes(), original)
                key.write_bytes(b'fixture')
                self.assertEqual(build.release_credentials(), (key, 'fixture', 'fixture-password'))
                props.write_bytes(b'alias=fixture\nstorepass=\n')
                with self.assertRaisesRegex(SystemExit, 'non-empty alias and storepass'):
                    build.release_credentials()


class SigningPasswordTest(unittest.TestCase):
    def test_failed_process_redacts_output_and_uses_only_child_environment(self):
        secret = 'offline-test-password'
        for command, flag in (('keytool', '-storepass:env'), ('apksigner', '--ks-pass')):
            with self.subTest(command=command):
                result = subprocess.CompletedProcess([], 7, f'out={secret}', f'err={secret}')
                with patch.object(build.subprocess, 'run', return_value=result) as spawn:
                    with self.assertRaises(SystemExit) as error:
                        build.run_with_password(command, secret, command, flag, build.PASSWORD_ENV)
                args, kwargs = spawn.call_args
                self.assertNotIn(secret, repr(args))
                self.assertEqual(kwargs['env'][build.PASSWORD_ENV], secret)
                self.assertIsNot(kwargs['env'], os.environ)
                self.assertNotIn(secret, str(error.exception))
                self.assertIn('exit 7', str(error.exception))
                self.assertIn('err=[redacted]', str(error.exception))
                self.assertIn('out=[redacted]', str(error.exception))

    def test_process_start_error_is_redacted(self):
        secret = 'offline-test-password'
        with patch.object(build.subprocess, 'run', side_effect=OSError(f'fixture {secret}')):
            with self.assertRaisesRegex(SystemExit, r'fixture \[redacted\]') as error:
                build.run_with_password('sign', secret, 'apksigner')
        self.assertNotIn(secret, str(error.exception))
