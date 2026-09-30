/**
 * 首页「今天发生了什么」在当天记录很多时仍要数到更早的失败
 *
 * 开播、下播、推送多的那一天，最近一页全是普通记录。更早的失败与告警
 * 如果只从这一页里挑，首页一条失败都不显示，像没出过事。
 * 当天有多少条失败与告警就数多少条，短条上仍是最新的几条。
 * 超过八条时，末行的数等于当天失败与告警总数减去已经显示的。
 * 不超过八条时，没占满的位置仍补普通记录。
 *
 * 由 HomeStripOlderFailuresTest 拉起。退码 0 ＝ 全绿。
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

function ordinary(text) {
  return {
    at: 1757000000000, type: 'PUSH_SENT', typeText: '推送成功', level: 'info',
    streamer: null, channel: '群 1', text, detail: {},
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

// 最近一页 200 条全是普通记录。当天一共 220 条，20 条失败都在更早的那部分，不在这一页里。
const totalBad = 20;
const recent = {
  success: true,
  events: Array.from({length: 200}, (_, i) => ordinary('已推送 ' + i)),
  matched: 220,
};
const problems = {
  events: Array.from({length: totalBad}, (_, i) => event('error', 'QQ发不出去：第 ' + (i + 1) + ' 条')),
  matched: totalBad,
};
const flooded = homeModel(status, login, Object.assign({}, recent, {problems}));
const rows = flooded.events;
eq(rows.some(item => String(item.text || '').includes('发不出去')), true,
  '当天超过一页、失败都在较早那部分时，首页仍显示失败');
const more = rows[rows.length - 1] || {};
const shown = rows.slice(0, -1).filter(item => !item.href);
const hidden = Number(String(more.text || '').replace(/\D/g, ''));
eq(shown.length + hidden, totalBad, '末行的数等于当天失败与告警总数减去已显示的');
eq(more.href, '#/log', '还有几条那一行点了去日志页');
eq(shown.every(item => String(item.text || '').includes('发不出去')), true, '显示出来的是失败，不是这一页的普通记录');

const few = homeModel(status, login, Object.assign({}, {
  success: true,
  events: Array.from({length: 200}, (_, i) => ordinary('已推送 ' + i)),
  matched: 203,
}, {
  problems: {
    events: [
      event('error', '推送失败一'),
      event('warn', '告警一'),
      event('error', '推送失败二'),
    ],
    matched: 3,
  },
}));
eq(few.events.length, 8, '失败不超过八条时整段仍是八条');
eq(few.events.some(item => item.href), false, '不超过八条时不另起还有几条那一行');
eq(few.events.filter(item => item.text === '推送失败一' || item.text === '告警一' || item.text === '推送失败二').length,
  3, '不超过八条时更早的失败与告警都显示');
eq(few.events.some(item => String(item.text || '').startsWith('已推送')), true, '没占满时仍补普通记录');

const overview = readFileSync(new URL('../../../main/resources/config-ui/overview.js', import.meta.url), 'utf8');
const start = overview.indexOf('export async function refreshHome');
const end = overview.indexOf('function renderVersion');
const refresh = start >= 0 && end > start ? overview.slice(start, end) : '';
eq(refresh.includes('&problems=true'), true, '首页另取当天的失败与告警，不限于最近一页');

console.log('末行：' + (more.text || ''));
console.log('跑了 ' + checks + ' 格');
if (failures.length) {
  for (const line of failures) console.log(line);
  process.exit(1);
}
