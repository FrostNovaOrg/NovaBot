/**
 * 告警「发一条测试」：结果不得盖掉未保存提醒
 *
 * 发送用的是服务端此刻已保存的配置。有改动时先写出提醒，
 * 成功／失败／异常若再整段覆盖同一格，提醒只在请求在飞的几百毫秒内可见。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 SettingsAlertTestOutcomeTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');

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

function loadOutcome() {
  const body = bracedFrom(src, 'function testOutcomeText');
  if (!body) throw new Error('no function');
  return new Function(body + '\nreturn testOutcomeText;')();
}

// ① 切出 testOutcomeText 执行：有 note 同时含结果与提醒；空 note 恰原文
let q1 = 'missing';
try {
  const fn = loadOutcome();
  const withNote = fn('已发出', '有改动还没保存');
  const empty = fn('没发出去', '');
  q1 = String(withNote).includes('已发出') && String(withNote).includes('有改动还没保存')
    && empty === '没发出去';
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '① testOutcomeText 有 note 同时含结果与提醒；空 note 恰原文');

function loadSend(store, api) {
  const outcome = bracedFrom(src, 'function testOutcomeText');
  const send = bracedFrom(src, 'async function sendTest');
  if (!send) throw new Error('no sendTest');
  return new Function('store', 'api', outcome + '\n' + send + '\nreturn sendTest;')(store, api);
}

function fakeAlertButton() {
  const box = {textContent: '', className: ''};
  const button = {disabled: false, parentElement: {querySelector() { return box; }}};
  return {box, button};
}

// ② 切出 sendTest 真执行：有改动时成功／异常两路 box 都留结果与未保存提醒
let q2ok = 'missing';
try {
  const {box, button} = fakeAlertButton();
  const fn = loadSend({dirty: {a: 1}}, async () => ({success: true, message: '已发出'}));
  await fn('webhook', button);
  q2ok = String(box.textContent).includes('已发出')
    && String(box.textContent).includes('有改动还没保存');
} catch (e) {
  q2ok = 'error:' + e.message;
}
eq(q2ok, true, '② sendTest 成功且有改动：结果与未保存提醒都在 box');

let q2err = 'missing';
try {
  const {box, button} = fakeAlertButton();
  const fn = loadSend({dirty: {a: 1}}, async () => { throw new Error('net'); });
  await fn('webhook', button);
  q2err = String(box.textContent).includes('发不出去')
    && String(box.textContent).includes('有改动还没保存');
} catch (e) {
  q2err = 'error:' + e.message;
}
eq(q2err, true, '② sendTest 异常且有改动：发不出去与未保存提醒都在 box');

// ③ 阳性对照：切段真执行 testOutcomeText('x','')==='x'
let q3 = 'missing';
try {
  q3 = loadOutcome()('x', '');
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, 'x', "③ 阳性对照 testOutcomeText('x','')==='x'");

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
