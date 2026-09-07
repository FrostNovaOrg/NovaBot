/**
 * 添加主播抽屉：查询失败或主播已在列表后，「找一下」须能再点
 *
 * uid 查不到或已经在名单里时若提前 return、不把按钮解开，
 * 只能关掉抽屉重开才能再查。切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 PushLookupUnlockTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');
const src = readFileSync(join(ui, 'push.js'), 'utf8');

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

/**
 * 以 /streamer/lookup 为锚，切出它所在的那份 async 函数
 */
function lookupFnSrc() {
  const at = src.indexOf('/streamer/lookup');
  if (at < 0) return '';
  const start = src.lastIndexOf('async function', at);
  if (start < 0) return '';
  return bracedFrom(src.slice(start), 'async function');
}

function loadLookup(api, el, esc, store) {
  const body = lookupFnSrc();
  if (!body) throw new Error('no function');
  const named = body.match(/^async function\s+([A-Za-z_$][\w$]*)/);
  if (!named) throw new Error('no function name');
  return new Function('api', 'el', 'esc', 'store', body + '\nreturn ' + named[1] + ';')(
    api, el, esc, store);
}

function fakeEl() {
  const node = {innerHTML: '', type: '', textContent: ''};
  node.appendChild = () => node;
  node.addEventListener = () => {};
  return node;
}

async function runLookup(apiImpl, pushData) {
  const go = {disabled: false};
  const out = {textContent: '', innerHTML: ''};
  const input = {value: '1'};
  const store = {pushData};
  const fn = loadLookup(apiImpl, fakeEl, String, store);
  await fn(['bilibili'], input, go, out);
  return {go, out};
}

function finallyHasUnlock(body) {
  const block = bracedFrom(body, 'finally');
  return block.includes('go.disabled = false');
}

const body = lookupFnSrc();
const fallback = body ? '' : 'finally 段内含 go.disabled = false';

// ① api 回失败 → 按钮解开
let q1 = 'missing';
try {
  if (!body) {
    q1 = finallyHasUnlock(src);
  } else {
    const {go} = await runLookup(async () => ({success: false, message: 'x'}), []);
    q1 = go.disabled === false;
  }
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, body
  ? '① api 回 {success:false,message:x} → go.disabled===false'
  : '① 切不出整块，退 ' + fallback);

// ② 已在列表 → 按钮解开
let q2 = 'missing';
try {
  if (!body) {
    q2 = finallyHasUnlock(src);
  } else {
    const {go} = await runLookup(
      async () => ({success: true, uid: 1, uname: 'a'}),
      [{uid: 1}]);
    q2 = go.disabled === false;
  }
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, body
  ? '② api 回已在列表的主播 → go.disabled===false'
  : '② 切不出整块，退 ' + fallback);

// ③ 阳性对照：api 抛错 → 按钮解开且 out 含「查询失败」
let q3 = 'missing';
try {
  if (!body) {
    q3 = finallyHasUnlock(src);
  } else {
    const {go, out} = await runLookup(async () => {
      throw new Error('boom');
    }, []);
    q3 = go.disabled === false && String(out.textContent).includes('查询失败');
  }
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, body
  ? '③ api 抛错 → go.disabled===false 且 out 含查询失败'
  : '③ 切不出整块，退 ' + fallback);

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
