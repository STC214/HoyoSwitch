import argparse
import json
import re
import time
import uuid
import xml.etree.ElementTree as ET

from switch import BUILD, PACKAGE, Fixture


class ButtonTest(Fixture):
    def node(self, label):
        for _ in range(4):
            path = f'/data/local/tmp/hoyoswitch-fixture-ui-{uuid.uuid4().hex}.xml'
            output = self.adb('shell', f'trap "rm -f {path}" EXIT; uiautomator dump {path} >/dev/null && cat {path}',
                              ok=(0, 1))
            self.trace[-1]['stdout'] = '[view tree omitted]'
            start = output.find('<hierarchy')
            end = output.find('</hierarchy>')
            if start >= 0 and end >= 0:
                for node in ET.fromstring(output[start:end + len('</hierarchy>')]).iter('node'):
                    if node.get('package') == PACKAGE and node.get('text', '').casefold() == label.casefold():
                        self.trace[-1]['stdout'] = f'{node.get("text")} checked={node.get("checked")}'
                        return node
            time.sleep(1)
        raise RuntimeError(f'Fixture UI did not show {label}')

    def tap(self, label):
        coords = [int(n) for n in re.findall(r'\d+', self.node(label).get('bounds', ''))]
        x1, y1, x2, y2 = coords
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        command = ['adb', '-s', self.serial, 'shell', 'input', 'tap', str(x), str(y)]
        try:
            self.adb(*command[3:])
        except RuntimeError as error:
            if 'INJECT_EVENTS' not in str(error):
                raise
            self.adb('shell', f"su -c 'input tap {x} {y}'")

    def pid(self):
        return self.adb('shell', 'pidof', PACKAGE, ok=(0, 1))

    def verify(self):
        self.switch('A', launch=True)
        self.node('Test account A')
        option = '切换后自动打开'
        if self.node(option).get('checked') != 'true':
            self.tap(option)
        original_pid = self.pid()
        self.tap('切换到 B')
        self.node('Test account B')
        new_pid = self.pid()
        if not original_pid or not new_pid or original_pid == new_pid:
            raise RuntimeError('Auto-open did not create a new fixture process')
        print(f'PASS auto-open: A -> B, PID {original_pid} -> {new_pid}', flush=True)

        self.tap(option)
        if self.node(option).get('checked') != 'false':
            raise RuntimeError('Auto-open option did not turn off')
        self.tap('切换到 A')
        for _ in range(20):
            if not self.pid() and self.current() == 'A':
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('Manual-open mode did not stop and save fixture')
        time.sleep(1)
        if self.pid():
            raise RuntimeError('Fixture reopened with option off')
        self.open()
        self.node('Test account A')
        if self.node(option).get('checked') != 'false':
            raise RuntimeError('Auto-open option was not retained')
        print('PASS manual open: B -> A, left stopped and option persisted', flush=True)
        self.tap(option)
        if self.node(option).get('checked') != 'true':
            raise RuntimeError('Could not restore default testing option')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    fixture = ButtonTest(parser.parse_args().serial)
    try:
        fixture.verify()
    finally:
        (BUILD / 'buttons-smoke.json').write_text(json.dumps(fixture.trace, ensure_ascii=False, indent=2) + '\n',
                                                 encoding='utf-8')


if __name__ == '__main__':
    main()
