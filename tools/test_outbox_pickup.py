"""取件程序的文件交接测试，网络响应由本地替身提供。"""

import contextlib
from datetime import datetime, timedelta, timezone
from http.client import IncompleteRead
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPHandler
from urllib.response import addinfourl


SCRIPT = Path(__file__).resolve().parents[1] / 'dist/templates/tools/outbox-pickup.py'
SPEC = importlib.util.spec_from_file_location('outbox_pickup', SCRIPT)
pickup = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = pickup
SPEC.loader.exec_module(pickup)

ADDRESS = 'https://nova.example.org/bot'
API_PATH = '/bot/nova/api/v1/outbox/'
TOKEN = 'test-only-token'
NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
PNG = b'\x89PNG\r\n\x1a\n'

INTERRUPT_DURING_IMAGE = '''
import os
from pathlib import Path
import runpy
import sys

test = runpy.run_path(sys.argv[1])
pickup, NovaBot, message, image = (test[name] for name in ('pickup', 'NovaBot', 'message', 'image'))
nova = NovaBot()
nova.add(message(5, segments=[image(5, 0)]))

def stop_on_image(route):
    if '/images/' in route:
        os._exit(23)

nova.on_request = stop_on_image
root = Path(sys.argv[2])
with pickup.work_lock(root / '.outbox.lock'):
    api = pickup.Api(test['ADDRESS'], test['TOKEN'], nova)
    pickup.Pickup(root, api, print, lambda: test['NOW']).run_round()
'''


class Response(io.BytesIO):
    def __init__(self, data, headers):
        super().__init__(data)
        self.headers = headers


def message(number, name='早班', segments=None):
    return {
        'id': number, 'status': 'PENDING', 'createdAt': '2026-01-01T00:00:00Z',
        'expiresAt': '2026-01-01T00:10:00Z', 'conversation': {'id': 1, 'name': name},
        'streamer': {'platform': 'live', 'uid': 100, 'name': '青山'},
        'kind': 'LIVE_ON', 'atAll': True,
        'segments': segments if segments is not None else [{'type': 'TEXT', 'text': '开播了'}],
    }


def image(number, index, **changes):
    return {'type': 'IMAGE_BYTES', 'index': index, 'byteCount': len(PNG),
            'url': f'{API_PATH}messages/{number}/images/{index}', **changes}


class Stopped(BaseException):
    pass


class NovaBot:
    def __init__(self):
        self.records = {}
        self.requests = []
        self.receipts = {}
        self.on_request = lambda route: None
        self.image_missing = False

    def add(self, *records):
        self.records.update((record['id'], record) for record in records)

    def fail(self, status, code='not_found', headers=None):
        body = json.dumps({'ok': False, 'error': {'code': code, 'message': TOKEN}}).encode()
        raise HTTPError(ADDRESS, status, TOKEN, headers or {}, io.BytesIO(body))

    def __call__(self, request, *, timeout):
        url = urlsplit(request.full_url)
        assert (url.scheme, url.netloc) == ('https', 'nova.example.org')
        assert request.get_header('Authorization') == f'Bearer {TOKEN}'
        assert url.path.startswith(API_PATH)
        route = url.path[len(API_PATH):]
        self.requests.append((request.get_method(), route))
        self.on_request(route)
        if route == 'messages':
            cursor = int(parse_qs(url.query).get('cursor', ['0'])[0])
            pending = [row for number, row in sorted(self.records.items())
                       if number > cursor and row['status'] == 'PENDING']
            result = {'items': pending[:1], 'next': str(pending[0]['id']) if len(pending) > 1 else None}
        else:
            _, number, *action = route.split('/')
            number = int(number)
            record = self.records[number]
            if action == ['claim']:
                if record['status'] != 'PENDING':
                    self.fail(409, 'conflict')
                record['status'] = 'SENDING'
                result = {'message': record, 'claimNumber': 7}
            elif action == ['receipt']:
                if record['status'] not in ('SENDING', 'UNKNOWN'):
                    self.fail(409, 'conflict')
                receipt = json.loads(request.data)
                assert receipt['claimNumber'] == 7
                self.receipts[number] = receipt
                record['status'] = receipt['outcome']
                result = {'id': number, 'status': record['status']}
            elif action and action[0] == 'images':
                if self.image_missing:
                    self.fail(404)
                return Response(PNG, {'Content-Type': 'image/png'})
            else:
                result = record
        return Response(json.dumps({'ok': True, 'data': result}).encode(), {})


class PickupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)
        self.nova = NovaBot()
        self.log = []
        self.now = NOW
        self.api = pickup.Api(ADDRESS, TOKEN, self.nova)
        self.client = self.new_client()

    def new_client(self):
        return pickup.Pickup(self.work, self.api, self.log.append, lambda: self.now)

    def folders(self, name):
        return sorted((self.work / name).iterdir())

    def archived(self):
        return sorted((self.work / '已回报').glob('*/*'))

    def move(self, folder, outcome):
        return folder.rename(self.work / outcome / folder.name)

    def test_complete_folder_and_sent_result(self):
        """半个文件夹先被拿去发，或者整条已发消息没被回报。"""
        self.nova.add(message(41, '早班/通知: 🌄', [
            {'type': 'TEXT', 'text': '开播'}, image(41, 1), {'type': 'TEXT', 'text': '了'},
            {'type': 'IMAGE_URL', 'url': 'https://media.example.org/cover.png'},
        ]))

        def before_image(route):
            if '/images/' in route:
                self.assertEqual(self.folders('待发'), [])

        self.nova.on_request = before_image
        self.client.run_round()
        folder, = self.folders('待发')
        self.assertEqual(folder.name, '41-早班_通知_ 🌄')
        self.assertEqual((folder / '会话.txt').read_text(encoding='utf-8'), '早班/通知: 🌄\n')
        self.assertEqual((folder / '正文.txt').read_text(encoding='utf-8'), '开播了')
        self.assertEqual((folder / '图片-1.png').read_bytes(), PNG)
        details = (folder / '说明.txt').read_text(encoding='utf-8')
        for value in ('青山', '开播通知', '2026-01-01T00:10:00Z', '@ 全体：是', 'https://media.example.org/cover.png'):
            self.assertIn(value, details)
        self.assertEqual(self.nova.records[41]['status'], 'SENDING')
        self.assertEqual([route for _, route in self.nova.requests if '/images/' in route], ['messages/41/images/1'])
        self.move(folder, '已发')
        self.client.run_round()
        self.assertEqual(self.nova.records[41]['status'], 'SENT')
        self.assertEqual([path.name for path in self.archived()], [folder.name])

    def test_restart_keeps_three_claims_and_reports_folder_outcomes(self):
        """重启找不到已取的三条通知，或者回报丢了送达失败的原因。"""
        self.nova.add(message(1), message(2), message(3))
        self.client.run_round()
        restarted = self.new_client()
        restarted.run_round()
        first, second, third = self.folders('待发')
        failed = self.move(second, '没发成')
        (failed / '原因.txt').write_text('\ufeff' + '🌄' * 201 + '\n第二行不应回报', encoding='utf-8')
        self.move(third, '跳过')
        self.move(first, '已发')
        restarted.run_round()
        self.assertEqual([self.nova.records[n]['status'] for n in (1, 2, 3)], ['SENT', 'FAILED', 'SKIPPED'])
        self.assertEqual(self.nova.receipts[2]['note'], '🌄' * 200)
        self.assertEqual(len(self.archived()), 3)
        self.assertEqual(sum(route.endswith('/claim') for _, route in self.nova.requests), 3)

    def test_expired_folder_is_removed_and_skipped(self):
        """发件人还能拿到已过期的文件夹，超时的通知也没有报回。"""
        self.nova.add(message(2))
        self.client.run_round()
        self.now = NOW.replace(minute=10)
        self.client.run_round()
        self.assertEqual(self.folders('待发'), [])
        self.assertEqual(self.nova.receipts[2]['outcome'], 'SKIPPED')
        self.assertEqual(self.nova.receipts[2]['note'], '过期未发')
        self.assertEqual(len(self.archived()), 1)

    def test_restart_resumes_a_saved_claim(self):
        """刚认领就停机后，同一条通知丢了、被放两遍或被放成半条。"""
        self.nova.add(message(5, segments=[image(5, 0)]))
        stopped = subprocess.run([sys.executable, '-B', '-c', INTERRUPT_DURING_IMAGE,
                                  str(Path(__file__).resolve()), str(self.work)],
                                 capture_output=True, text=True, timeout=30)
        self.assertEqual(stopped.returncode, 23, stopped.stderr)
        self.assertEqual(self.folders('待发'), [])
        self.nova.records[5]['status'] = 'SENDING'
        with pickup.work_lock(self.work / '.outbox.lock'):
            restarted = self.new_client()
            restarted.run_round()
            restarted.run_round()
            folder, = self.folders('待发')
            self.assertEqual((folder / '图片-1.png').read_bytes(), PNG)
            self.move(folder, '已发')
            restarted.run_round()
        self.assertEqual(self.nova.records[5]['status'], 'SENT')
        self.assertNotIn(('POST', 'messages/5/claim'), self.nova.requests)

    def test_restart_preserves_evidence_if_claim_number_was_not_received(self):
        """停在服务端认领成功到本机记下编号之间，重启后没有留下任何说明。"""
        self.nova.add(message(5))

        def stop_during_claim(route):
            if route.endswith('/claim'):
                self.nova.records[5]['status'] = 'SENDING'
                raise Stopped()

        self.nova.on_request = stop_during_claim
        with self.assertRaises(Stopped):
            self.client.run_round()
        self.nova.on_request = lambda route: None
        self.new_client().run_round()
        self.assertEqual(self.folders('待发'), [])
        folder, = self.archived()
        self.assertIn('认领编号', (folder / '回报未被接受.txt').read_text(encoding='utf-8'))
        self.assertEqual(self.nova.requests.count(('POST', 'messages/5/claim')), 1)
        self.assertFalse(any(route.endswith('/receipt') for _, route in self.nova.requests))

    def test_rejected_result_is_archived_once(self):
        """管理员重新放回待发后，程序仍一轮一轮地提交旧设备的回报。"""
        self.nova.add(message(7))
        self.client.run_round()
        folder, = self.folders('待发')
        self.move(folder, '已发')
        self.nova.records[7]['status'] = 'PENDING'
        self.client.run_round()
        self.client.run_round()
        folder, = self.archived()
        report = (folder / '回报未被接受.txt').read_text(encoding='utf-8')
        self.assertIn('409', report)
        self.assertIn('待发', report)
        self.assertEqual(self.nova.requests.count(('POST', 'messages/7/receipt')), 1)
        self.assertEqual(len(self.log), 1)
        self.assertNotIn(TOKEN, self.log[0])

    def test_interrupted_receipt_is_retried_without_losing_the_folder(self):
        """发完后断线，已发的通知被丢掉，再也没有回报。"""
        self.nova.add(message(6))
        self.client.run_round()
        folder, = self.folders('待发')
        self.move(folder, '已发')

        def offline(route):
            if route.endswith('/receipt'):
                raise URLError(TOKEN)

        self.nova.on_request = offline
        with self.assertRaises(pickup.RoundError):
            self.client.run_round()
        self.assertEqual(len(self.folders('已发')), 1)
        self.nova.on_request = lambda route: None
        self.new_client().run_round()
        self.assertEqual(self.nova.records[6]['status'], 'SENT')
        self.assertEqual(len(self.archived()), 1)

    def test_missing_images_are_reported_without_a_pending_folder(self):
        """被标记缺图或取图返回 404 的通知，仍被放到待发供人误发。"""
        self.nova.add(message(1, segments=[image(1, 0, unavailable=True)]),
                      message(2, segments=[image(2, 0)]), message(3))
        self.nova.image_missing = True
        self.client.run_round()
        self.assertEqual([self.nova.records[n]['status'] for n in (1, 2, 3)], ['FAILED', 'FAILED', 'SENDING'])
        self.assertEqual([path.name for path in self.folders('待发')], ['3-早班'])
        self.assertNotIn(('GET', 'messages/1/images/0'), self.nova.requests)
        self.assertIn(('GET', 'messages/2/images/0'), self.nova.requests)
        for number in (1, 2):
            self.assertIn('图片', self.nova.receipts[number]['note'])

    def test_unavailable_image_does_not_delay_other_notifications(self):
        """一张图卡住了，后面的开播通知全都没发出去。"""
        self.nova.add(message(3))
        self.client.run_round()
        folder, = self.folders('待发')
        self.move(folder, '已发')
        self.nova.add(message(1, segments=[image(1, 0)]), message(2))
        waits = []

        def fail_image(route):
            if route == 'messages/1/images/0':
                self.nova.fail(503)

        def stop_after_round(seconds):
            waits.append(seconds)
            raise Stopped()

        self.nova.on_request = fail_image
        with self.assertRaises(Stopped):
            pickup.run(self.client, 60, False, stop_after_round)

        self.assertEqual([path.name for path in self.folders('待发')], ['2-早班'])
        self.assertEqual(self.nova.records[1]['status'], 'SENDING')
        self.assertEqual(self.nova.receipts[3]['outcome'], 'SENT')
        archived, = (self.work / '已回报').glob('????-??-??/*')
        self.assertEqual(archived.name, folder.name)
        self.assertEqual(waits, [60])

    def test_retries_images_after_connection_failures_until_expiry(self):
        """网络抖了一下，本来能发的通知被报成没发成。"""
        record = message(9, segments=[image(9, 0)])
        record.update(kind='DYNAMIC', expiresAt='2026-01-01T01:00:00Z')
        self.nova.add(record)
        failures = iter([URLError(TOKEN), TimeoutError(TOKEN), IncompleteRead(PNG[:4], 4), 503, 429])

        def fail_image(route):
            if '/images/' not in route:
                return
            failure = next(failures, None)
            if isinstance(failure, Exception):
                raise failure
            if failure:
                self.nova.fail(failure, headers={'Retry-After': '123'})

        self.nova.on_request = fail_image
        for _ in range(5):
            result = pickup.run(self.new_client(), 60, True)
            self.assertEqual(self.nova.receipts, {})
            self.assertEqual(self.folders('待发'), [])
            self.assertEqual(result, 1)
            self.now += timedelta(minutes=4)
        self.nova.records[9]['status'] = 'UNKNOWN'
        self.new_client().run_round()
        folder, = self.folders('待发')
        self.assertEqual((folder / '图片-1.png').read_bytes(), PNG)
        self.assertEqual(self.nova.requests.count(('POST', 'messages/9/claim')), 1)

        self.nova.add(message(10, segments=[image(10, 0)]))
        self.nova.records[10]['expiresAt'] = '2026-01-01T01:00:00Z'
        failures = iter([TimeoutError(TOKEN)])
        self.assertEqual(pickup.run(self.new_client(), 60, True), 1)
        self.assertEqual(self.folders('待发'), [folder])
        self.assertEqual(self.nova.receipts, {})
        self.now = NOW + timedelta(hours=1)
        self.nova.records[10]['status'] = 'UNKNOWN'
        self.new_client().run_round()
        result, = (entry for entry in self.archived() if entry.name.startswith('10-'))
        self.assertFalse((result / pickup.REJECTION_NAME).exists())
        self.assertEqual(self.nova.receipts[10]['outcome'], 'FAILED')
        self.assertIn('到期', self.nova.receipts[10]['note'])
        self.assertIn('图片', self.nova.receipts[10]['note'])
        self.assertEqual(self.nova.requests.count(('GET', 'messages/10/images/0')), 1)

    def test_outside_image_address_is_never_requested(self):
        """取图地址指向别处时，访问令牌随取图请求被带到另一台服务器。"""
        self.nova.add(message(1, segments=[image(1, 0, url='https://media.example.org/image.png')]))
        self.client.run_round()
        self.assertEqual(self.folders('待发'), [])
        self.assertEqual(self.nova.records[1]['status'], 'FAILED')

    def test_keeps_running_and_backs_off_after_connection_auth_and_rate_errors(self):
        """断线后程序退出，或令牌失效、缺权限、被限速时仍快速请求。"""
        waits = []
        next_errors = iter([URLError(TOKEN), URLError(TOKEN), (401, 'unauthenticated', {}),
                            (429, 'too_many_requests', {'Retry-After': '800'}), None])

        def next_round(route):
            failure = next(next_errors)
            if isinstance(failure, URLError):
                raise failure
            if failure:
                self.nova.fail(*failure)

        def sleep(seconds):
            waits.append(seconds)
            if len(waits) == 5:
                raise Stopped()

        self.nova.on_request = next_round
        with self.assertRaises(Stopped):
            pickup.run(self.client, 10, False, sleep)
        self.assertEqual(waits, [20, 40, 80, 800, 10])
        self.assertEqual(len(self.log), 4)
        self.assertIn('连不上', self.log[0])
        self.assertIn('控制台', self.log[2])
        self.assertIn('重签', self.log[2])
        self.assertIn('429', self.log[3])
        self.assertNotIn(TOKEN, '\n'.join(self.log))

        self.nova.add(message(1), message(2))

        def read_only(route):
            if route.endswith('/claim'):
                self.nova.fail(403, 'insufficient_scope')

        self.nova.on_request = read_only
        self.assertEqual(pickup.run(self.client, 1, True), 1)
        self.assertIn('outbox:deliver', self.log[-1])
        self.assertEqual(sum(route.endswith('/claim') for _, route in self.nova.requests), 1)
        self.assertEqual(self.folders('待发'), [])


class StartupTest(unittest.TestCase):
    def test_template_is_private_and_environment_token_is_used(self):
        """设置生成后其他本机用户可读，或环境中的令牌被打印、写回设置。"""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'outbox-pickup.json'
            output = io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(pickup.main(['--once'], path), 0)
            self.assertIn('已生成设置', output.getvalue())
            if os.name != 'nt':
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            with patch.dict(os.environ, {'NOVABOT_OUTBOX_TOKEN': TOKEN}):
                settings = pickup.load_settings(path)
            self.assertEqual(settings.token, TOKEN)
            self.assertEqual(settings.interval, 60)
            self.assertNotIn(TOKEN, path.read_text(encoding='utf-8'))

    def test_second_process_cannot_use_the_same_work_folder(self):
        """重复启动会同时认领、改写同一个工作文件夹，或停机后旧锁挡住重启。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = root / SCRIPT.name
            shutil.copyfile(SCRIPT, script)
            settings = {**pickup.DEFAULT_SETTINGS, 'work_folder': '.', 'token': TOKEN}
            (root / pickup.SETTINGS_NAME).write_text(json.dumps(settings), encoding='utf-8')
            lock = root / '.outbox.lock'
            with pickup.work_lock(lock):
                second = subprocess.run([sys.executable, '-X', 'utf8', str(script), '--once'], capture_output=True,
                                        encoding='utf-8', timeout=30)
                self.assertEqual(second.returncode, 1)
                self.assertRegex(second.stderr, '已经有.*运行')
            with pickup.work_lock(lock):
                self.assertTrue(lock.is_file())

    def test_redirect_is_not_followed(self):
        """反向代理把请求导向另一台服务器时，取件程序把令牌转过去。"""
        address = 'http://nova.example.org'
        visited = []

        def redirect(request):
            visited.append(request.full_url)
            response = addinfourl(io.BytesIO(), {'location': 'http://media.example.org/'},
                                  request.full_url, code=302)
            response.msg = 'Found'
            return response

        with patch.object(HTTPHandler, 'http_open', side_effect=redirect):
            with self.assertRaisesRegex(pickup.RoundError, '重定向'):
                list(pickup.Api(address, TOKEN).pending())
        self.assertEqual([urlsplit(url).netloc for url in visited], ['nova.example.org'])


if __name__ == '__main__':
    unittest.main()
