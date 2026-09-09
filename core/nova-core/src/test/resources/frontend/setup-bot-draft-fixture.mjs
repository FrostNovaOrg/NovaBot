/**
 * 初始设置第 2 步：改了机器人地址、端口或 Token 后，先前测通必须作废
 *
 * 测通后再改 WS Token（它根本不参与测试请求），「下一步」不得仍放行。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 SetupBotDraftTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {canAdvance} from '../../../main/resources/config-ui/setup-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

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
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

/** 从 marker 起按圆括号配平截出一次调用（含 marker） */
function sliceCall(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('(', start);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

function lastArg(call) {
  const open = call.indexOf('(');
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  let lastComma = -1;
  for (let i = open; i < call.length; i++) {
    const c = call[i];
    const prev = i > 0 ? call[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) return lastComma < 0 ? '' : call.slice(lastComma + 1, i).trim();
    } else if (c === ',' && depth === 1) lastComma = i;
  }
  return '';
}

function fieldCalls(src) {
  const calls = [];
  let from = 0;
  while (true) {
    const idx = src.indexOf('field(', from);
    if (idx < 0) break;
    const call = sliceCall(src.slice(idx), 'field(');
    if (!call) break;
    calls.push(call);
    from = idx + call.length;
  }
  return calls;
}

const setupSrc = read('setup.js');

// ① 切出 invalidateBot 真执行：{botOk:true} → botOk===false
let after = 'missing';
try {
  const body = bracedFrom(setupSrc, 'function invalidateBot');
  if (!body) throw new Error('no function');
  const fn = new Function(body + '\nreturn invalidateBot;')();
  const draft = {botOk: true};
  fn(draft);
  after = draft.botOk;
} catch (e) {
  after = 'error:' + e.message;
}
eq(after, false, '① invalidateBot({botOk:true}) → botOk===false');

// ② stepBot 内 invalidateBot( ≥5 次，或五个 field( 末参皆经它
const step = bracedFrom(setupSrc, 'function stepBot');
const nInv = (step.match(/invalidateBot\(/g) || []).length;
const fields = fieldCalls(step);
const viaDirect = fields.length === 5 && fields.every(c => lastArg(c).includes('invalidateBot'));
const viaRetest = fields.length === 5
  && fields.every(c => lastArg(c).includes('retest'))
  && nInv >= 1
  && step.includes('const retest');
eq(nInv >= 5 || viaDirect || viaRetest, true,
  '② stepBot 内 invalidateBot( ≥5 或五个 field( 末参皆经它');

// ③ 阳性对照：第 2 步校验真执行，botOk:false → stop、botOk:true → pass
eq(
  {stop: canAdvance(1, {botOk: false}).ok, go: canAdvance(1, {botOk: true}).ok},
  {stop: false, go: true},
  '③ botOk:false → stop、botOk:true → pass');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
