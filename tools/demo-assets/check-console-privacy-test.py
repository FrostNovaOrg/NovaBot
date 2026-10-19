#!/usr/bin/env python3
"""Regression checks for identifiers and environment paths in demo responses."""
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    'privacy', Path(__file__).with_name('check-console-privacy.py'))
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)


class ConsolePrivacyTest(unittest.TestCase):
    def test_recorded_text_line_limit(self):
        self.assertEqual([], privacy.inspect_lines('x' * 10000 + '\n'))
        self.assertEqual(['line 2 exceeds 10000 characters (10001)'],
                         privacy.inspect_lines('short\n' + 'x' * 10001 + '\n'))
        self.assertEqual([], privacy.inspect_lines(('x' * 6000 + '\n') * 2))

    def test_short_platform_and_messaging_identifiers(self):
        for key in ('mid', 'MID', 'ruid', 'rUid', 'roomid', 'room_id', 'roomId',
                    'roomID', 'UID', 'user_uid', 'user-id', 'group_id', 'groupCode',
                    'groupNumber', 'groupUin', 'qq', 'QQ号', 'qqNumber', 'qq_id',
                    'uin', '群号', '房间号'):
            for value in (271828, '271828'):
                with self.subTest(key=key, value=value):
                    self.assertIn('identity 271828 is not declared synthetic',
                                  privacy.inspect(json.dumps({key: value}, ensure_ascii=False)))
            with self.subTest(query=key):
                self.assertIn('identity 271828 is not declared synthetic',
                              privacy.inspect(key + '=271828'))

    def test_environment_paths_in_response_strings(self):
        for prefix in ('/tmp/', '/private/tmp/', '/var/folders/', '/Users/person/',
                       '/home/person/', str(Path.home()) + '/'):
            for value in (prefix + 'application.yml',
                          'Could not read ' + prefix + 'application.yml'):
                with self.subTest(value=value):
                    self.assertIn(privacy.ENVIRONMENT_HIT, privacy.inspect(json.dumps({'configPath': value})))
            with self.subTest(escaped=prefix):
                self.assertIn(privacy.ENVIRONMENT_HIT,
                              privacy.inspect(json.dumps({'configPath': prefix}).replace('/', '\\/')))

    def test_synthetic_identifiers_and_deployment_paths(self):
        value = next(iter(privacy.IDS))
        self.assertEqual([], privacy.inspect(json.dumps({
            'mid': value, 'room_id': int(value), 'groupNumber': value,
            'configPath': '/opt/novabot/application.yml', 'path': '/config/api/status',
            'host': '127.0.0.1', 'elapsedSeconds': 271828})))


if __name__ == '__main__':
    unittest.main()
