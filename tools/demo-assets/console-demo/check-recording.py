#!/usr/bin/env python3
"""Check coverage of the requests exposed by the offline console."""
import json
import unittest
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs

DATA = Path(__file__).resolve().parent / 'data'
INDEX = json.loads((DATA / 'index.json').read_text())
ROWS = INDEX['responses']


class RecordingTest(unittest.TestCase):
    def test_object_key_order(self):
        def visit(value):
            if isinstance(value, dict):
                self.assertEqual(sorted(value), list(value))
                for item in value.values():
                    visit(item)
            elif isinstance(value, list):
                for item in value:
                    visit(item)
        for file in DATA.glob('*.json'):
            visit(json.loads(file.read_text()))

    def test_archive_entrypoints(self):
        for uid in range(990000000101, 990000000105):
            prefix = f'/config/api/streamers/bilibili/{uid}'
            sessions = [parse_qs(row['query'], keep_blank_values=True) for row in ROWS
                        if row['path'] == prefix + '/sessions']
            for row in ROWS:
                if row['path'] != prefix + '/insights':
                    continue
                scope = json.loads((DATA.parent / row['file']).read_text())['insights']
                query = dict(page=['1'], size=['10'], dateFrom=[str(scope['from'])], dateTo=[str(scope['to'])])
                self.assertIn(query, sessions, str(uid))
            self.assertIn(dict(page=['1'], size=['10']), sessions)

    def test_click_intervals_and_pages(self):
        by_key = {(row['path'], tuple(sorted((key, value[0]) for key, value in
                   parse_qs(row['query'], keep_blank_values=True).items()))): row for row in ROWS}
        for row in ROWS:
            if '/streamers/' not in row['path']:
                continue
            response = json.loads((DATA.parent / row['file']).read_text()) if row['contentType'].startswith('application/json') else {}
            prefix, endpoint = row['path'].rsplit('/', 1)
            intervals = []
            if endpoint == 'calendar':
                intervals = [(item['start'], item['end'], item['starts']) for item in response['calendar']['items']]
            elif endpoint == 'trend':
                intervals = [(item['start'], item['end'], item.get('starts', [])) for item in response['trend']['buckets']]
            elif endpoint == 'start-slots':
                slots = response['startSlots']
                intervals = [(slots['from'], slots['to'], [item['startTime'] for item in cell['sessionItems']])
                             for cell in slots['cells']]
            for start, end, starts in intervals:
                query = dict(page='1', size='10', **{'from': str(start), 'to': str(end)},
                             starts=','.join(map(str, starts)))
                self.assertIn((prefix + '/sessions', tuple(sorted(query.items()))), by_key)
            if endpoint == 'sessions' and 'page=' in row['query']:
                query = {key: value[0] for key, value in parse_qs(row['query'], keep_blank_values=True).items()}
                for page in range(1, response['sessions']['pages'] + 1):
                    query['page'] = str(page)
                    self.assertIn((row['path'], tuple(sorted(query.items()))), by_key)

    def test_fixed_observation_times(self):
        for row in ROWS:
            if row['path'].endswith('/live'):
                body = json.loads((DATA.parent / row['file']).read_text())
                self.assertEqual(INDEX['epochMs'], body['checkedAt'])
            if row['path'] == '/config/api/bot/targets':
                body = json.loads((DATA.parent / row['file']).read_text())
                self.assertEqual(datetime.fromisoformat(INDEX['recordedAt']),
                                 datetime.fromisoformat(body['fetchedAt'].replace('Z', '+00:00')))
                self.assertTrue(all(sender['fetchedAt'] == body['fetchedAt'] for sender in body['senders']))

    def test_channel_previews(self):
        bodies = [row.get('body') for row in ROWS if row['path'] == '/config/api/report/preview']
        for num, kind in ((990000000301, 1), (990000000302, 1), (990000000303, 1), (990000000502, 0)):
            for visible in (False, True):
                self.assertIn(dict(platform='qq-onebot', type=kind, num=num, revenueVisible=visible), bodies)

    def test_recorded_nickname_fallback(self):
        streamers = next(row for row in ROWS if row['path'] == '/config/api/streamers')
        users = json.loads((DATA.parent / streamers['file']).read_text())['streamers']
        for user in users:
            uid = str(user['uid'])
            lookup = next(row for row in ROWS if row['path'] == '/config/api/streamer/lookup'
                          and row.get('body', {}).get('uid') == uid)
            self.assertFalse(json.loads((DATA.parent / lookup['file']).read_text())['success'])
            name = next(row for row in ROWS if row['path'] == '/config/api/streamer/name'
                        and parse_qs(row['query'])['uid'] == [uid])
            self.assertEqual(user['uname'], json.loads((DATA.parent / name['file']).read_text())['uname'])

    def test_timeline_clock(self):
        row = next(row for row in ROWS if row['path'] == '/config/api/timeline' and not row['query'])
        events = json.loads((DATA.parent / row['file']).read_text())['events']
        times = [event['at'] for event in events]
        self.assertEqual(sorted(times, reverse=True), times)
        self.assertLessEqual(max(times), INDEX['epochMs'])
        started = next(event for event in events if event['type'] == 'SYSTEM_STARTED')
        startup = started['at']
        self.assertEqual(INDEX['epochMs'] - 3 * 3600000, startup)
        self.assertEqual('2500', started['detail']['startup_ms'])
        self.assertLess(startup, min(event['at'] for event in events if event['type'] == 'PUSH_SENT'
                                            and event['at'] >= INDEX['epochMs'] - 12 * 3600000))


if __name__ == '__main__':
    unittest.main()
