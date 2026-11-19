"""Shared adb + su helpers for the StarRail root tools.

Every remote read goes through ``exec-out`` so bytes are not line-ending
translated, and every write overwrites the existing inode (``cat > path``) so the
owner, mode and SELinux context of game files survive.
"""

import hashlib
import shlex
import subprocess
import tempfile
import uuid

PACKAGE = 'com.miHoYo.hkrpg'
DATA_DIR = f'/data/user/0/{PACKAGE}'
REMOTE_TMP = '/data/local/tmp'


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
        return self._run(['exec-out', 'su', '-c', script], ok=ok).stdout

    def root_text(self, script, ok=(0,)):
        return self.root(script, ok=ok).decode('utf-8', errors='replace').strip()

    def game_pids(self):
        return self.shell('pidof', PACKAGE, ok=(0, 1))

    def info(self, path):
        """Metadata for one remote path, or None when it does not exist."""
        quoted = shlex.quote(path)
        result = self._run(['exec-out', 'su', '-c',
                            f'if [ ! -e {quoted} ]; then exit 3; fi; '
                            f'stat -c "%u %g %a %s %Y" {quoted}; ls -Z {quoted}'],
                           ok=(0, 3))
        if result.returncode == 3:
            return None
        text = result.stdout.decode('utf-8', errors='replace').strip()
        lines = [line for line in text.splitlines() if line.strip()]
        if not lines:
            raise RuntimeError(f'info returned no output for {path}')
        fields = lines[0].split()
        if len(fields) != 5:
            return None
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
        return self.root(f'cat {shlex.quote(path)}')

    def write(self, path, data):
        stamp = uuid.uuid4().hex
        remote = f'{REMOTE_TMP}/hoyoswitch-{stamp}'
        local = tempfile.gettempdir() + f'/hoyoswitch-{stamp}'
        with open(local, 'wb') as handle:
            handle.write(data)
        try:
            self._run(['push', local, remote])
            self.root(f'cat {shlex.quote(remote)} > {shlex.quote(path)}')
        finally:
            self.root(f'rm -f {shlex.quote(remote)}', ok=(0, 1))
            try:
                import os
                os.unlink(local)
            except OSError:
                pass

    def set_metadata(self, path, uid, gid, mode, context):
        quoted = shlex.quote(path)
        self.root(f'chown {uid}:{gid} {quoted}; chmod {mode} {quoted}')
        if context:
            self.root(f'chcon {shlex.quote(context)} {quoted}', ok=(0, 1))
