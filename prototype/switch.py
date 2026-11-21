import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

PACKAGE = 'dev.hoyoswitch.fixture'
ACTIVITY = f'{PACKAGE}/.MainActivity'
BUILD = Path(__file__).resolve().parent / 'build'


class Fixture:
    def __init__(self, serial):
        self.serial = serial
        self.trace = []

    def adb_result(self, *args, ok=(0,)):
        command = ['adb', '-s', self.serial, *args]
        result = subprocess.run(command, capture_output=True, timeout=45)
        stdout = result.stdout.decode('utf-8', errors='replace').strip()
        stderr = result.stderr.decode('utf-8', errors='replace').strip()
        self.trace.append(dict(command=command, exit_code=result.returncode, stdout=stdout, stderr=stderr))
        if result.returncode not in ok:
            raise RuntimeError(f'{shlex.join(command)}: exit {result.returncode}: {stderr or stdout}')
        return result

    def adb(self, *args, ok=(0,)):
        return self.adb_result(*args, ok=ok).stdout.decode('utf-8', errors='replace').strip()

    def local(self, command):
        return self.adb('shell', f'run-as {PACKAGE} sh -c {shlex.quote(command)}')

    def current(self):
        value = self.local('if test -e files/profile.txt; then cat files/profile.txt; else printf A; fi')
        if value not in ('A', 'B'):
            raise RuntimeError('Current config invalid; no switch attempted')
        return value

    def stop(self):
        self.adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
        result = self.adb_result('shell', 'pidof', PACKAGE, ok=(0, 1))
        if result.returncode != 1 or result.stdout.strip() or result.stderr.strip():
            raise RuntimeError('Could not confirm test app stopped; see pidof diagnostics')

    def open(self):
        output = self.adb('shell', 'am', 'start', '-W', '--user', '0', '-n', ACTIVITY)
        if 'Status: ok' not in output:
            raise RuntimeError(f'Test app did not launch: {output}')

    def stage(self, account):
        self.local(f'mkdir -p files && printf %s {shlex.quote(account)} > files/profile.next')

    def publish(self):
        self.local('mv -f files/profile.next files/profile.txt')

    def switch(self, account, launch=False, fault=None):
        if account not in ('A', 'B'):
            raise ValueError('Expected mock account A or B')
        self.stop()
        before = self.current()
        publication_attempted = False
        try:
            self.stage('invalid' if fault == 'invalid-stage' else account)
            if self.local('cat files/profile.next') != account:
                raise RuntimeError('Staged config rejected; original preserved')
            publication_attempted = True
            self.publish()
            if self.current() != account or fault == 'after-publish':
                raise RuntimeError('Post-publish verification failed')
        except Exception as error:
            if publication_attempted:
                self.stage(before)
                self.publish()
                if self.current() != before:
                    raise RuntimeError('Rollback verification failed') from error
                raise RuntimeError(f'{error}; restored {before}') from error
            raise
        if launch:
            self.open()
        print(f'Test profile {before} -> {account}; ' + ('reopened' if launch else 'left stopped'))

    def verify_screen(self, account):
        label = f'Test account {account}'
        # Keep evidence limited to the fixture's expected label, never store the raw view tree.
        for _ in range(3):
            path = f'/data/local/tmp/hoyoswitch-fixture-ui-{uuid.uuid4().hex}.xml'
            command = ['adb', '-s', self.serial, 'shell',
                       f'trap "rm -f {path}" EXIT; uiautomator dump {path} >/dev/null && cat {path}']
            process = subprocess.run(command, capture_output=True, timeout=30)
            marker = f'text="{label}"'.encode()
            if process.returncode == 0 and marker in process.stdout and f'package="{PACKAGE}"'.encode() in process.stdout:
                self.trace.append(dict(command=command, exit_code=0, stdout=label,
                                       stdout_kind='matched fixture text only'))
                return
            time.sleep(1)
        raise RuntimeError(f'Fixture startup screen did not show {label}; keep the phone unlocked')

    def smoke(self):
        self.switch('A', launch=True)
        self.verify_screen('A')
        self.switch('B')
        if self.adb('shell', 'pidof', PACKAGE, ok=(0, 1)):
            raise RuntimeError('Manual-launch mode unexpectedly reopened the app')
        self.open()  # Separate launch command stands in for a launcher tap in smoke tests.
        self.verify_screen('B')
        self.switch('A', launch=True)
        self.verify_screen('A')
        for fault, expected in [('invalid-stage', 'original preserved'), ('after-publish', 'restored A')]:
            try:
                self.switch('B', fault=fault)
            except RuntimeError as error:
                if expected not in str(error):
                    raise
                self.trace.append(dict(fault=fault, result=str(error)))
                print(error)
            else:
                raise RuntimeError(f'Expected failure: {fault}')
            self.open()
            self.verify_screen('A')
        print('PASS: startup screens A/B/A, invalid-stage preservation, and post-publish rollback')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--account', choices=('A', 'B'))
    action.add_argument('--smoke', action='store_true')
    action.add_argument('--install', action='store_true')
    parser.add_argument('--launch', action='store_true')
    args = parser.parse_args()
    fixture = Fixture(args.serial)
    try:
        if args.install:
            print(fixture.adb('install', '-r', str(BUILD / 'switch-fixture.apk')))
        elif args.smoke:
            fixture.smoke()
        else:
            fixture.switch(args.account, args.launch)
        return 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1
    finally:
        if args.smoke:
            BUILD.mkdir(exist_ok=True)
            (BUILD / 'smoke.json').write_text(json.dumps(fixture.trace, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    sys.exit(main())
