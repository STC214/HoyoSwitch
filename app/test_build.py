"""Offline tests for release signing; run with python -B -m unittest discover -s app."""

import os
import subprocess
import unittest
from unittest.mock import patch

import build


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
