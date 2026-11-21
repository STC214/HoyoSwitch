"""Offline regression for the fixture's switch preflight."""

import subprocess
import unittest
from unittest.mock import patch

import switch


class StopTest(unittest.TestCase):
    def test_only_clean_no_match_confirms_stopped(self):
        stopped = subprocess.CompletedProcess([], 0, b'', b'')
        for code, stdout, stderr in ((1, b'', b''), (1, b'', b'adb: device offline'),
                                     (0, b'', b''), (0, b'123', b'')):
            with self.subTest(code=code, stdout=stdout, stderr=stderr):
                result = subprocess.CompletedProcess([], code, stdout, stderr)
                with patch.object(switch.subprocess, 'run', side_effect=[stopped, result]):
                    fixture = switch.Fixture('fixture')
                    if code == 1 and not stdout and not stderr:
                        fixture.stop()
                    else:
                        with self.assertRaises(RuntimeError):
                            fixture.stop()
