/**
 * 向导主播步：改了 uid 或平台后，先前找到的那位必须作废；没找到人／没选目标不许过
 *
 * 找到 A 再把格子改成 B、不点「找一下」就下一步时，落盘的必须不能还是 A。
 * 切段按花括号配平截到块尾后<b>真执行</b>，不是只 includes。
 *
 * 由 SetupStreamerDraftTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const pages = join(dirname(fileURLToPath(import.meta.url)),
  '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'setup-streamer.js'), 'utf8');

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

// ① 切出 invalidateStreamer 真执行：{streamer:{uid:1}} → streamer===null
let after = 'missing';
try {
  const body = bracedFrom(src, 'function invalidateStreamer');
  if (!body) throw new Error('no function');
  const fn = new Function(body + '\nreturn invalidateStreamer;')();
  const draft = {streamer: {uid: 1}};
  fn(draft);
  after = draft.streamer;
} catch (e) {
  after = 'error:' + e.message;
}
eq(after, null, '① invalidateStreamer({streamer:{uid:1}}) → streamer===null');

// ② setup-uid 那句 field( 调用末参不是 null（切出该调用文本）
const uidCall = sliceCall(src, "field(row, STREAMER_INPUT_HINT, 'setup-uid'");
eq(uidCall.length > 0 && lastArg(uidCall) !== 'null' && uidCall.includes('invalidateStreamer'), true,
  '② setup-uid 的 field( 末参不是 null 且调用 invalidateStreamer');

// ③ 平台下拉变更处含对 invalidateStreamer 的调用
const plat = sliceCall(src, "picker.addEventListener('change'");
eq(plat.length > 0 && plat.includes('invalidateStreamer'), true,
  '③ 平台下拉变更处调用 invalidateStreamer');

// ④ 阴性对照：同一套切法切一个必定不含它的函数，得判「不含」——
//    ①②③ 的「含」不能是因为这套切法回的是整份源码
const picker = bracedFrom(src, 'function targetPicker');
eq(picker.length > 0 && !picker.includes('invalidateStreamer'), true,
  '④ 阴性对照：targetPicker 切得出来且不含 invalidateStreamer');

// ⑤ 放行由这一步自己判：没找到人、没选目标各有一条拦法，过了才落盘
const next = bracedFrom(src, 'export async function next');
eq(next.length > 0, true, '⑤ 找得到 next');
eq(next.includes('draft.streamer') && next.includes('draft.targets.length')
  && next.includes('stop(') && next.includes('saveStreamer('), true,
  '⑤ next 两条拦法俱在，且过了才 saveStreamer');

// ⑥ 跳过要先过一段后果确认，不是直接放行
const skip = bracedFrom(src, 'export async function skip');
eq(skip.length > 0 && skip.includes('ask('), true, '⑥ skip 走 ask 的后果确认');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
