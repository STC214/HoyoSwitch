"""Non-destructive device smoke test: only tap the account tool's game tabs."""

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'dev.hoyoswitch.app'
TABS = ('原神', '星穹铁道', '绝区零')
REMOTE_TREE = '/data/local/tmp/hoyo-ui-review.xml'


def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=30)


def tree():
    adb('shell', 'uiautomator', 'dump', REMOTE_TREE)
    root = ET.fromstring(adb('exec-out', 'cat', REMOTE_TREE))
    if not any(node.get('package') == PACKAGE for node in root.iter('node')):
        raise RuntimeError('Account tool is not foreground; no taps were sent.')
    return root


def timing_lines():
    return [line for line in adb('logcat', '-d', '-v', 'brief', '-s', 'HoyoUiTiming').decode(
        'utf-8', errors='replace').splitlines() if 'frame_callback_ms=' in line]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rapid-only', action='store_true')
    args = parser.parse_args()
    result = {'package': PACKAGE, 'measurement': 'next animation-frame callback, not display latency',
              'samples': []}
    for cycle in range(0 if args.rapid_only else 3):
        for label in TABS:
            before = tree()
            buttons = [node for node in before.iter('node') if node.get('package') == PACKAGE
                       and node.get('text') == label and node.get('class') == 'android.widget.Button']
            if len(buttons) != 1:
                raise RuntimeError('Expected exactly one game tab; no tap sent.')
            button = buttons[0]
            if button.get('selected') == 'true':
                continue
            bounds = [int(value) for value in re.findall(r'\d+', button.get('bounds', ''))]
            if len(bounds) != 4:
                raise RuntimeError('Invalid tab bounds; no tap sent.')
            old = timing_lines()
            x = (bounds[0] + bounds[2]) // 2
            y = (bounds[1] + bounds[3]) // 2
            adb('shell', 'su', '-c', f'input tap {x} {y}')
            time.sleep(1.5)
            after = tree()
            selected = [node for node in after.iter('node') if node.get('text') == label
                        and node.get('class') == 'android.widget.Button' and node.get('selected') == 'true']
            if len(selected) != 1:
                raise RuntimeError('The expected game tab did not become selected.')
            observed = timing_lines()
            events = []
            for line in observed[len(old):]:
                event = re.search(r'tab=(\S+) phase=(\w+) cards=(\d+) frame_callback_ms=(\d+)', line)
                if event:
                    events.append(dict(zip(('tab', 'phase', 'cards', 'ms'), event.groups())))
            cards = sum(node.get('text') == '账号与密码' for node in after.iter('node'))
            result['samples'].append({'cycle': cycle + 1, 'tab': label, 'visible_cards': cards,
                                      'events': events})
    if args.rapid_only:
        root = tree()
        commands = []
        for label in TABS * 3:
            buttons = [node for node in root.iter('node') if node.get('package') == PACKAGE
                       and node.get('text') == label and node.get('class') == 'android.widget.Button']
            if len(buttons) != 1:
                raise RuntimeError('Unexpected tab layout; no rapid taps sent.')
            bounds = [int(value) for value in re.findall(r'\d+', buttons[0].get('bounds', ''))]
            if len(bounds) != 4:
                raise RuntimeError('Invalid tab bounds; no rapid taps sent.')
            commands.append(f'input tap {(bounds[0] + bounds[2]) // 2} {(bounds[1] + bounds[3]) // 2}')
        for command in commands:
            adb('shell', 'su', '-c', command)
        time.sleep(2)
        final = tree()
        result['rapid_passed'] = any(node.get('text') == TABS[-1] and node.get('selected') == 'true'
                                     for node in final.iter('node'))
        if not result['rapid_passed']:
            raise RuntimeError('Final rapid-selection tab mismatch.')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
