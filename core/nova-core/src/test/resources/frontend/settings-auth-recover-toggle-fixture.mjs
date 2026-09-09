/**
 * 二次验证开关：请求没发出去时须拨回原档，不得停在刚拨到的那一档
 *
 * 开侧停在「已启用」会让人以为绑好了，下次登录进不来；
 * 关侧停在「已关闭」时二次验证其实还开着。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 SettingsAuthRecoverToggleTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-auth.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
 */
function bracedFrom(text, marker) {
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const open = text.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const prev = i > 0 ? text[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && text[i + 1] === '/') {
      const nl = text.indexOf('\n', i);
      i = nl < 0 ? text.length : nl;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return '';
}

function loadRecover(report) {
  const body = bracedFrom(src, 'function recoverToggle');
  if (!body) throw new Error('no function');
  return new Function('report', body + '\nreturn recoverToggle;')(report);
}

function runRecover(prev, err) {
  let settled = 'missing';
  let reported = 'missing';
  const report = (_box, payload) => { reported = payload; };
  const settle = value => { settled = value; };
  loadRecover(report)('r', settle, prev, err);
  return {settled, reported};
}

// ① 切出 recoverToggle，桩 report／settle 执行 (r, s, true, Error('x'))
let q1 = 'missing';
try {
  const {settled, reported} = runRecover(true, new Error('x'));
  q1 = settled === true
    && reported && reported.success === false
    && String(reported.message).includes('x');
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '① recoverToggle(r, s, true, Error(x)) settle 收 true 且 report 失败并含 x');

// ② enrollFlow 块内含 catch 且调用 recoverToggle(
const enroll = bracedFrom(src, 'async function enrollFlow');
eq(enroll.includes('catch') && enroll.includes('recoverToggle('),
  true, '② enrollFlow 块内含 catch 且调用 recoverToggle(');

function fakeAuthDom() {
  const byId = {};
  function el() {
    return {
      value: '123456',
      listeners: {},
      addEventListener(type, fn) {
        (this.listeners[type] ||= []).push(fn);
      },
    };
  }
  function $(sel) {
    const id = String(sel).replace(/^#/, '');
    if (!byId[id]) byId[id] = el();
    return byId[id];
  }
  return {$, byId};
}

function loadDisable(api, report, $, esc) {
  const rec = bracedFrom(src, 'function recoverToggle');
  const dis = bracedFrom(src, 'function disableFlow');
  if (!rec || !dis) throw new Error('no disableFlow');
  return new Function('api', 'report', '$', 'esc',
    rec + '\n' + dis + '\nreturn disableFlow;')(api, report, $, esc);
}

// ③ 切出 disableFlow 真执行：确认关闭时 api 抛错 → settle(true) 拨回开启档
let q3 = 'missing';
try {
  const {$, byId} = fakeAuthDom();
  const fn = loadDisable(
    async () => { throw new Error('disable-down'); },
    (box, payload) => { box.payload = payload; },
    $,
    String);
  const settled = [];
  const result = {};
  fn({innerHTML: ''}, result, state => settled.push(state));
  const clicks = byId['totp-off-ok'] && byId['totp-off-ok'].listeners.click;
  if (!clicks || !clicks[0]) throw new Error('no totp-off-ok click');
  await clicks[0]();
  q3 = settled[0] === true
    && result.payload && result.payload.success === false
    && String(result.payload.message).includes('disable-down');
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③ disableFlow 确认关闭时 api 抛错 settle(true) 拨回开启档');

// ④ 阳性对照：recoverToggle(r, s, false, …) → settle 收到 false
let q4 = 'missing';
try {
  q4 = runRecover(false, new Error('y')).settled;
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, false, '④ 阳性对照 recoverToggle(r, s, false, …) settle 收 false');

function loadEnroll(api, report, $, esc) {
  const rec = bracedFrom(src, 'function recoverToggle');
  const enroll = bracedFrom(src, 'async function enrollFlow');
  if (!rec || !enroll) throw new Error('no enrollFlow');
  return new Function('api', 'report', '$', 'esc',
    rec + '\n' + enroll + '\nreturn enrollFlow;')(api, report, $, esc);
}

// ⑤ 真执行 enrollFlow：setup 抛错 → settle(false) 且开关回关闭档
let q5 = 'missing';
try {
  const fn = loadEnroll(
    async () => { throw new Error('setup-down'); },
    (box, payload) => { box.payload = payload; },
    () => ({addEventListener() {}}),
    String);
  const sw = {checked: true, text: '已启用'};
  const settle = state => {
    sw.checked = state;
    sw.text = state ? '已启用' : '已关闭';
  };
  const result = {};
  await fn({innerHTML: ''}, result, settle);
  q5 = sw.checked === false && sw.text === '已关闭'
    && result.payload && result.payload.success === false
    && String(result.payload.message).includes('setup-down');
} catch (e) {
  q5 = 'error:' + e.message;
}
eq(q5, true, '⑤ enrollFlow setup 抛错 settle(false) 且开关回关闭档');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
