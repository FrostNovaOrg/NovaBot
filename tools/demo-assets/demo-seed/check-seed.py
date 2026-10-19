#!/usr/bin/env python3
"""Check the generated demo world, including early and late start times."""
import json
import runpy
import subprocess
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent


class DemoSeedTest(unittest.TestCase):
    def test_transport_list_and_failure(self):
        bot = runpy.run_path(str(HERE.parent / 'fake-onebot.py'))
        identities = json.loads((HERE / 'synthetic-identities.json').read_text())
        self.assertEqual(identities['groups'], [row['group_id'] for row in bot['data_of']('/get_group_list')])
        self.assertEqual(0, bot['reply']('/send_group_msg', {'message': '演示推送·开播通知'})['retcode'])
        self.assertNotEqual(0, bot['reply']('/send_group_msg', {'message': '演示推送·动态发送失败'})['retcode'])

    def test_page_content(self):
        for moment in ('2026-10-19T01:01:00', '2026-10-19T23:59:00'):
            with self.subTest(moment=moment), tempfile.TemporaryDirectory() as directory:
                work = Path(directory)
                subprocess.run(['python3', str(HERE / 'make-demo-seed.py'), '--now', moment,
                                directory], check=True, capture_output=True)
                read = lambda name: json.loads((work / name).read_text())
                rows = lambda name: [json.loads(line) for line in (work / name).read_text().splitlines()]
                users = read('datasource.json')
                self.assertEqual(4, len(users), 'four different streamer states')
                # The datasource endpoint embeds its configuration as a JSON
                # string, so outer pretty printing cannot shorten that line.
                content = (work / 'datasource.json').read_text()
                self.assertLessEqual(len(json.dumps(content, ensure_ascii=False)), 9000)
                self.assertEqual(1, sum(not user['enabled'] for user in users))
                sessions = rows('sessions.jsonl')
                now = datetime.fromisoformat(moment).timestamp() * 1000
                self.assertTrue(all(row['endTime'] < now for row in sessions))
                self.assertEqual(57, len(sessions))
                detailed = {id(row) for user in users for row in sorted(
                    [row for row in sessions if row['uid'] == user['uid']],
                    key=lambda row: row['startTime'], reverse=True)[:3]}
                self.assertEqual(12, len(list((work / 'details').glob('*/detail.json'))))
                for session in sessions:
                    directory = f"details/bilibili-{session['uid']}-{session['startTime']}"
                    if id(session) not in detailed:
                        self.assertFalse((work / directory).exists(), 'old detail expires')
                        continue
                    detail = read(directory + '/detail.json')
                    self.assertEqual(session['metrics']['danmu_count'], len(rows(directory + '/danmu.jsonl')))
                    for metric in ('danmu_count', 'enter_count', 'gift_value', 'super_chat_value'):
                        self.assertAlmostEqual(session['metrics'][metric], sum(detail['series'][metric].values()))
                    self.assertAlmostEqual(session['metrics']['gift_value'],
                                           sum(gift['price'] * gift['count'] for gift in detail['gifts']))
                    self.assertTrue(all(not gap['from'] <= int(at) < gap['to']
                                        for gap in detail['gaps'] for at in detail['series']['danmu_count']))
                for user in users:
                    archive = [row for row in sessions if row['uid'] == user['uid']]
                    self.assertGreaterEqual(len(archive), 6)
                    self.assertTrue(any(row['maintenanceGapSeconds'] for row in archive))
                    self.assertTrue(any(row['endReason'] != 'NORMAL' for row in archive))
                    newest = max(archive, key=lambda row: row['startTime'])
                    detail = read(f"details/bilibili-{user['uid']}-{newest['startTime']}/detail.json")
                    self.assertTrue(detail['series']['online_count'])
                    self.assertTrue(detail['rankings']['danmu_users'])
                    self.assertTrue(detail['gifts'])
                    snapshots = [row for row in rows('snapshots.jsonl') if row['uid'] == user['uid']]
                    self.assertGreaterEqual(len(snapshots), 90)
                    self.assertGreater(len({row['metrics']['fans'] for row in snapshots}), 10)
                targets = {(t['type'], t['num']) for user in users for t in user['targets']}
                self.assertEqual(3, sum(kind == 1 for kind, _ in targets))
                self.assertEqual(1, sum(kind == 0 for kind, _ in targets))
                self.assertEqual(2, len(read('state.json')['passkey']))
                self.assertEqual(2, len(rows('event-stream-tokens.jsonl')))
                deliveries = read('weekly-report-deliveries.json')['records']
                self.assertTrue(any(row['status'] == 'SENT' for row in deliveries))
                monitoring = read('data.json')['Monitoring:bilibili']
                self.assertEqual(4, len(monitoring))


if __name__ == '__main__':
    unittest.main()
