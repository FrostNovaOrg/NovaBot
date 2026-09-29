/**
 * 首页「今天发生了什么」遇上几十条失败
 *
 * 告警通道配错、全都发不出去时，失败一条不落地堆在短条最上面。
 * 起动十几分钟，这一段就被「发不出去」占满，底下别的记录一条都看不见。
 * 短条整段仍不超过八条；多出来的失败与告警收成一行，写还有几条失败或告警，点了去日志页。
 * 同一页里夹着普通记录，末行不把它们算进去。
 *
 * 由 HomeStripCapTest 拉起。退码 0 ＝ 全绿。
 */

import {readFileSync} from 'node:fs';
import {homeModel} from '../../../main/resources/config-ui/home-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function event(level, text) {
  return {
    at: 1757000000000, type: 'ALERT_FAILED', typeText: '告警发不出', level,
    streamer: null, channel: 'QQ', text, detail: {},
  };
}

const status = {
  success: true,
  senders: [{id: 'qq'}],
  users: [{uid: 1, uname: '甲', platform: 'bilibili', enabled: true, targets: 1}],
  health: [],
  locked: true,
  totalDataAvailable: true,
  alerts: {webhook: true, mail: true},
  pushEnabled: true,
  live: [],
  quiet: {active: false},
};
const login = {success: true, accounts: []};

const ordinary = Array.from({length: 4}, (_, i) => ({
  at: 1757000000000, type: 'PUSH_SENT', typeText: '推送成功', level: 'info',
  streamer: null, channel: '群 1', text: '已推送 ' + i, detail: {},
}));
const twenty = [
  ordinary[0],
  ...Array.from({length: 20}, (_, i) => event('error', 'QQ发不出去：第 ' + (i + 1) + ' 条')),
  ...ordinary.slice(1),
];
const flooded = homeModel(status, login, {success: true, events: twenty});
const rows = flooded.events;
eq(rows.length <= 8, true, '整段不超过八条');
eq(rows.length, 8, '二十条失败夹着普通记录时，短条连末行一共八行');
const more = rows[rows.length - 1] || {};
const shown = rows.slice(0, -1).filter(item => !item.href);
const hidden = Number(String(more.text || '').replace(/\D/g, ''));
eq(shown.length + hidden, 20, '显示的失败条数加末行的条数要等于二十条失败，普通记录不算');
eq(more.href, '#/log', '还有几条那一行点了去日志页');
eq(more.text, '还有 13 条失败或告警', '末行只数没显示出来的失败与告警');

const mixed = [
  event('error', '推送失败一'),
  event('error', '推送失败二'),
  event('warn', '告警一'),
  ...Array.from({length: 9}, (_, i) => ({
    at: 1757000000000, type: 'PUSH_SENT', typeText: '推送成功', level: 'info',
    streamer: null, channel: '群 1', text: '已推送 ' + i, detail: {},
  })),
];
const usual = homeModel(status, login, {success: true, events: mixed});
eq(usual.events.length, 8, '失败没超过八条时仍是八条');
eq(usual.events.some(item => item.href), false, '没溢出时不另起指到日志页的那一行');

const overview = readFileSync(new URL('../../../main/resources/config-ui/overview.js', import.meta.url), 'utf8');
const start = overview.indexOf('function renderStrip');
const end = overview.indexOf('async function pluginFacts');
const fn = start >= 0 && end > start ? overview.slice(start, end) : '';
eq(fn.includes('item.href'), true, '短条多出来的那一行要点得去日志页');

console.log('还有几条那一行：' + (more.text || ''));
console.log('跑了 ' + checks + ' 格');
if (failures.length) {
  for (const line of failures) console.log(line);
  process.exit(1);
}
