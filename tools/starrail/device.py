"""Shared adb + su helpers for the StarRail root tools.

Control-flow commands run through ``adb shell`` so the remote exit code survives;
``adb exec-out`` returns 0 for everything and merges stderr into stdout, so it is
used only for raw binary payloads. Writes overwrite the existing inode
(``base64 -d > path``) so owner, mode and SELinux context of game files survive.

Every remote read goes through ``exec-out`` so bytes are not line-ending
translated.
"""

import base64
import hashlib
import re
import shlex
import subprocess

PACKAGE = 'com.miHoYo.hkrpg'
DATA_DIR = f'/data/user/0/{PACKAGE}'
REMOTE_TMP = '/data/local/tmp'
# Printed when a read target is missing or unreadable; exec-out gives no exit code.
READ_FAILURE = b'@@hoyoswitch-read-failed@@'


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.trace = []

    def _run(self, argv, ok=(0,), timeout=60):
        command = ['adb', '-s', self.serial, *argv]
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        stdout = result.stdout.decode('utf-8', errors='replace').strip()
        stderr = result.stderr.decode('utf-8', errors='replace').strip()
        self.trace.append(dict(command=command, exit_code=result.returncode, stdout=stdout, stderr=stderr))
        if result.returncode not in ok:
            raise RuntimeError(f'{shlex.join(command)}: exit {result.returncode}: {stderr or stdout}')
        return result

    def shell(self, *args, ok=(0,)):
        return self._run(['shell', *args], ok=ok).stdout.decode('utf-8', errors='replace').strip()

    def root(self, script, ok=(0,)):
        """Runs a root script through ``adb shell`` so its exit code is observable.

        Output is text (CRLF translated); use :meth:`read` for binary payloads.
        """
        return self._run(['shell', 'su -c ' + shlex.quote(script)], ok=ok).stdout

    def root_text(self, script, ok=(0,)):
        return self.root(script, ok=ok).decode('utf-8', errors='replace').strip()

    def _exec_out(self, script, ok=(0,)):
        """Raw byte output. ``exec-out`` reports exit 0 even on failure."""
        return self._run(['exec-out', 'su', '-c', script], ok=ok).stdout

    def game_pids(self):
        result = self._run(['shell', 'pidof', PACKAGE], ok=(0, 1))
        text = result.stdout.decode('utf-8', errors='replace').strip()
        error = result.stderr.decode('utf-8', errors='replace').strip()
        if result.returncode == 1 and not text and not error:
            return ''
        if result.returncode == 0 and re.fullmatch(r'[1-9][0-9]*(\s+[1-9][0-9]*)*', text):
            return text
        raise RuntimeError(f'{self.serial}: could not confirm game process status (pidof exit '
                           f'{result.returncode}): {error or text or "no valid PID output"}')

    def info(self, path):
        """Metadata for one remote path, or None when it does not exist."""
        quoted = shlex.quote(path)
        # adb shell (not exec-out) so `exit 3` actually reaches us.
        result = self._run(['shell', 'su -c ' + shlex.quote(
            f'if [ ! -e {quoted} ]; then exit 3; fi; '
            f'stat -c "%u %g %a %s %Y" {quoted}; ls -Z {quoted}')],
                           ok=(0, 3))
        if result.returncode == 3:
            return None
        text = result.stdout.decode('utf-8', errors='replace').strip()
        lines = [line for line in text.splitlines() if line.strip()]
        if not lines:
            raise RuntimeError(f'info returned no output for {path}')
        fields = lines[0].split()
        if len(fields) != 5:
            raise RuntimeError(f'info output malformed for {path}: {lines[0]!r}')
        uid, gid, mode, size, mtime = fields
        context = ''
        for line in lines[1:]:
            for token in line.split():
                if token.startswith('u:') and token.count(':') >= 2:
                    context = token
                    break
            if context:
                break
        return dict(uid=uid, gid=gid, mode=mode, size=int(size), mtime=int(mtime), context=context)

    def read(self, path):
        quoted = shlex.quote(path)
        # exec-out has no exit code, so the remote side prints a marker on failure; without it a
        # "cat: ...: No such file" message would be mistaken for file content.
        marker = shlex.quote(READ_FAILURE.decode('ascii'))
        data = self._exec_out(
            f'if [ ! -e {quoted} ]; then printf %s {marker}; exit 3; fi; '
            f'cat {quoted} || printf %s {marker}')
        if data.startswith(READ_FAILURE) or data.endswith(READ_FAILURE):
            raise RuntimeError(f'{path}: could not read remote file')
        return data

    def write(self, path, data):
        """Overwrites the existing inode, keeping binary bytes intact.

        Two phases: decode into a sibling temp file first, then copy it over the target. A write cut
        short by Ctrl+C or a timeout can therefore only leave the temp file behind, not a truncated
        game database. ``adb shell`` stdin is text-mode on Windows (0x1A truncates, CRLF is
        rewritten), so the payload travels as base64 and is decoded on the device.
        """
        quoted = shlex.quote(path)
        temp = shlex.quote(path + '.hsw-tmp')
        # Guard first: a missing decoder would otherwise fail after truncating the temp file.
        script = ('if ! command -v base64 >/dev/null 2>&1; then '
                  'echo "base64 not available on device" >&2; exit 127; fi; '
                  f'base64 -d > {temp} && cat {temp} > {quoted} && rm -f {temp} '
                  f'|| {{ rm -f {temp}; exit 1; }}')
        command = ['adb', '-s', self.serial, 'shell', 'su -c ' + shlex.quote(script)]
        result = subprocess.run(command, input=base64.b64encode(data), capture_output=True,
                                timeout=180)
        stderr = result.stderr.decode('utf-8', errors='replace').strip()
        stdout = result.stdout.decode('utf-8', errors='replace').strip()
        self.trace.append(dict(command=command, exit_code=result.returncode, stdout=stdout,
                               stderr=stderr))
        if result.returncode != 0:
            raise RuntimeError(
                f'{shlex.join(command)}: exit {result.returncode}: {stderr or stdout}')

    def set_metadata(self, path, uid, gid, mode, context):
        quoted = shlex.quote(path)
        # Both must succeed: chown alone failing used to be hidden by a successful chmod.
        self.root(f'chown {uid}:{gid} {quoted} && chmod {mode} {quoted}')
        if context:
            # chcon can legitimately be unavailable; a failure is verified by the caller's probe.
            self.root(f'chcon {shlex.quote(context)} {quoted}', ok=(0, 1))
