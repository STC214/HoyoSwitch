"""Offline tests for release signing; run with python -B -m unittest discover -s app."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build


class ApkOutputTest(unittest.TestCase):
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
                with patch.object(build, 'run', side_effect=subprocess.CalledProcessError(1, signer)):
                    with self.assertRaises(subprocess.CalledProcessError):
                        build.publish_apk(signer, candidate, destination, release=release)
                self.assertEqual(destination.read_bytes(), b'old-fixture')
                self.assertTrue(candidate.is_file())

                with patch.object(build, 'run') as verify:
                    build.publish_apk(signer, candidate, destination, release=release)
                flags = ('--print-certs',) if release else ()
                verify.assert_called_once_with(*signer, 'verify', '--verbose', *flags, candidate)
                self.assertEqual(destination.read_bytes(), b'new-fixture')
                self.assertFalse(candidate.exists())


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
