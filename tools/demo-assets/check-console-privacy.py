#!/usr/bin/env python3
"""Check demo sources, generated seed records and raw console responses."""
import argparse
import json
import re
import runpy
import subprocess
import tempfile
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote_plus

ROOT = Path(__file__).resolve().parents[2]
DEMO = ROOT / 'tools/demo-assets'
DECLARED = json.loads((DEMO / 'demo-seed/synthetic-identities.json').read_text())
IDS = {str(value) for values in DECLARED.values() for value in values}
FACES = set((ROOT / 'tools/fixture-privacy-allow.txt').read_text().splitlines())
FACE = re.compile(r'hdslb\.com/bfs/face/([0-9a-fA-F]{40})')
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})')
IDENTITY_KEYS = {
    'uid', 'mid', 'ruid', 'useruid', 'biliuid', 'bilibiliuid', 'roomid', 'roomuid',
    'roomno', 'roomnumber', 'userid', 'groupid', 'groupnum', 'groupnumber', 'groupcode',
    'groupuin', 'friendid', 'accountid', 'senderid', 'memberid', 'targetid',
    'qq', 'qqid', 'qqnum', 'qqnumber', 'qqaccount', 'uin', 'num',
    '群', '群号', '群号码', 'qq群号', 'qq号', 'qq号码', '房间号', '直播间号',
}
LABELLED_ID = re.compile(r'(?<!\w)([\w.-]+)[\s\x22\x27:=/：]+(\d+)')
PATH_ID = re.compile(r'/(?:streamers|weekly-report)/[^/\s]+/(\d+)')
ENVIRONMENT_PATH = re.compile(
    r'/(?:private/tmp|tmp|var/folders)(?:/|$|(?=[\s\x22\x27]))|/(?:Users|home)/[^/\s\x22\x27\\]+')
ENVIRONMENT_HIT = 'recording environment path is not allowed'


def normal_key(name):
    return re.sub(r'[\s_-]', '', name.rsplit('.', 1)[-1]).lower()


def reserved(domain):
    return any(domain == base or domain.endswith('.' + base)
               for base in ('example.com', 'example.org', 'example.net')) or domain.endswith(('.invalid', '.test', '.example'))


def inspect(text):
    hits = set()
    def environment(value):
        value = unquote_plus(value.replace('\\/', '/'))
        home = str(Path.home()).rstrip('/')
        if ENVIRONMENT_PATH.search(value) or (home and re.search(re.escape(home) + r'(?:/|$|(?=[\s\x22\x27]))', value)):
            hits.add(ENVIRONMENT_HIT)

    def identity(value):
        if isinstance(value, bool) or value is None:
            return
        value = str(value)
        if value.isdigit() and value not in IDS:
            hits.add('identity ' + value + ' is not declared synthetic')

    def walk(value, users=False, series=False):
        if isinstance(value, dict):
            for name, child in value.items():
                normal = normal_key(name)
                if normal in IDENTITY_KEYS:
                    identity(child)
                if name.isdigit() and not (series and re.fullmatch(r'\d{13}', name)):
                    identity(name)
                if name.startswith('qq-onebot:'):
                    for part in name.split(':')[1:]:
                        identity(part)
                walk(child, users or name == 'userSets', series or name == 'series')
        elif isinstance(value, list):
            for child in value:
                if users:
                    identity(child)
                walk(child, users, series)
        elif isinstance(value, str):
            environment(value)
            if value.lstrip().startswith(('{', '[')):
                try:
                    walk(json.loads(value))
                except ValueError:
                    pass
    try:
        walk(json.loads(text))
    except ValueError:
        pass
    text = unquote_plus(text.replace('\\"', '"'))
    environment(text)
    for face in FACE.findall(text):
        if face not in FACES:
            hits.add('face hash ' + face + ' is not in the allow list')
    for domain in EMAIL.findall(text):
        if not reserved(domain):
            hits.add('mailbox domain ' + domain + ' is not reserved')
    for name, value in LABELLED_ID.findall(text):
        if normal_key(name) in IDENTITY_KEYS:
            identity(value)
    for value in PATH_ID.findall(text):
        identity(value)
    # Also covers compound state keys, prose, YAML and Python literals. Epoch
    # milliseconds have 13 digits and are not mistaken for account identifiers.
    for value in re.findall(r'(?<![\w.])\d{9,12}(?![\w.])', text):
        identity(value)
    return sorted(hits)


def inspect_lines(text):
    return [f'line {number} exceeds 10000 characters ({len(line)})'
            for number, line in enumerate(text.splitlines(), 1) if len(line) > 10000]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--negative-control', nargs='?', const='all',
                        choices=('all', 'environment', 'mid', 'long-line'))
    parser.add_argument('files', nargs='*', type=Path)
    args = parser.parse_args()
    if not IDS or any(not re.fullmatch(r'990000000\d{3}', value) for value in IDS):
        raise SystemExit('Synthetic identity declaration must use the reserved demo range 990000000000–990000000999')
    negative = 'https://i0.hdslb.com/bfs/face/' + 'f' * 40 + '.jpg\nuser@not-reserved.com\n' + json.dumps(
        {'uid': 12345, 'roomId': 23456, 'num': 34567, 'user_id': 45678, 'query': 'uid=1'})
    controls = {
        'existing': (negative, 7),
        'environment': (json.dumps({'configPath': '/private/tmp/console-record/application.yml'}), 1),
        'mid': (json.dumps({'mid': '271828'}), 1),
        'long-line': ('x' * 10001, 1),
    }
    for name, (sample, count) in controls.items():
        if len(inspect(sample) + inspect_lines(sample)) != count:
            raise SystemExit('Demo privacy negative control failed: ' + name)
    if args.negative_control:
        rejected = []
        for name, (sample, _) in controls.items():
            if args.negative_control in ('all', name):
                rejected.extend(inspect(sample) + inspect_lines(sample))
        for hit in rejected:
            print('negative-control: ' + hit)
        print(f'Demo privacy: FAIL ({len(rejected)} violations)')
        return 1
    print('Demo privacy negative control: PASS (face, email, uid, room, group, QQ, query uid, mid, environment path, long line rejected)')
    files = args.files or sorted((DEMO / 'demo-seed').glob('*')) + [DEMO / 'fake-onebot.py'] + sorted((DEMO / 'console-demo/data').glob('*'))
    sources = [(str(file.relative_to(ROOT)) if file.is_relative_to(ROOT) else str(file), file.read_text())
               for file in files if file.is_file() and file.suffix != '.png']
    file_sources = list(sources)
    # Check computed identifiers too, including viewer arrays and subscription keys.
    seed = runpy.run_path(str(DEMO / 'demo-seed/make-demo-seed.py'))
    now = datetime(2026, 1, 15, 12)
    sources.append(('generated sessions', '\n'.join(seed['session_lines'](now))))
    for line in seed['session_lines'](now):
        sources.append(('generated session', line))
    sources.append(('generated timeline', '\n'.join(seed['timeline_lines'](now)[0])))
    sources.append(('generated engineering log', '\n'.join(seed['log_lines'](now, 5, 40))))
    bot = runpy.run_path(str(DEMO / 'fake-onebot.py'))
    for path in ('get_login_info', 'get_group_list', 'get_friend_list', 'get_group_member_list'):
        sources.append(('generated OneBot ' + path, json.dumps(bot['data_of']('/' + path))))
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(['python3', str(DEMO / 'demo-seed/make-demo-seed.py'),
                        '--now', '2026-01-15T12:00:00', directory], check=True, capture_output=True)
        for file in sorted(Path(directory).rglob('*')):
            if file.is_file():
                item = ('generated ' + str(file.relative_to(directory)), file.read_text())
                sources.append(item)
                file_sources.append(item)
    violations = 0
    for label, text in file_sources:
        for hit in inspect_lines(text):
            print(label + ": " + hit)
            violations += 1
    for label, text in sources:
        for hit in inspect(text):
            print(label + ': ' + hit)
            violations += 1
    print(f'Demo privacy: {"FAIL" if violations else "PASS"} ({len(sources)} sources, {violations} violations)')
    return int(violations > 0)


if __name__ == '__main__':
    raise SystemExit(main())
