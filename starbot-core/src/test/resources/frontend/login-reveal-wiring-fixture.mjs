/**
 * 登录页 paint 接线、机密按钮样式、加主播空输入句的夹具
 *
 * 量三件事，都是源码里写了才成立、漏写页面上照样能用：
 *   paint 是否按 loginControlState 遍历挂 disabled；
 *   登录页内联 .secret button 与 app.css 那一份数字是否相同；
 *   加主播空输入句与 placeholder 是否同一条常量（推送页与初始设置页）。
 *
 * 眼睛落点改由 password-reveal-fixture 运行期登记。
 * 由 LoginRevealWiringTest 拉起。引用的是源码树里那几份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '../../../main/resources/config-ui');
const pages = join(here, '../../../../../starbot-novabot-console/src/main/resources/config-ui-pages');
const read = name => readFileSync(join(ui, name), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

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

const login = read('login.html');
const paintStart = login.indexOf('function paint()');
const paintEnd = login.indexOf('function tick()');
eq(paintStart >= 0 && paintEnd > paintStart, true, 'login.html 里找得到 paint');
const paint = paintStart >= 0 && paintEnd > paintStart ? login.slice(paintStart, paintEnd) : '';
eq(paint.includes('loginControlState('), true, 'paint 问 loginControlState');
eq(/for\s*\(\s*const id of Object\.keys\(/.test(paint), true, 'paint 遍历挂 disabled');

function secretButtonProps(css) {
  const match = css.match(/(?:^|[\n}])\s*\.secret button\s*\{([^}]+)\}/);
  if (!match) return null;
  const out = {};
  for (const part of match[1].split(';')) {
    const cut = part.indexOf(':');
    if (cut < 0) continue;
    const name = part.slice(0, cut).trim();
    const value = part.slice(cut + 1).trim();
    if (name) out[name] = value;
  }
  return out;
}

const loginSecret = secretButtonProps(login);
const appSecret = secretButtonProps(read('app.css'));
eq(!!loginSecret && !!appSecret, true, '两份 .secret button 都找得到');
eq(loginSecret, appSecret, '.secret button 数值与 app.css 相同');

const push = readFileSync(join(pages, 'push.js'), 'utf8');
eq(/export const STREAMER_INPUT_HINT\s*=/.test(push), true, 'push.js 导出 STREAMER_INPUT_HINT');
eq(push.includes('请先输入 uid、直播间号或链接'), false, '旧空输入句已撤');

const hintMatch = push.match(/export const STREAMER_INPUT_HINT\s*=\s*'([^']+)'/);
const streamerHint = hintMatch ? hintMatch[1] : '';
const sentinelHint = '哨兵HINT_405';

function el(tag, cls) {
  return {
    tag, className: cls || '', id: '', placeholder: '', type: '', textContent: '',
    kids: [],
    setAttribute() {},
    appendChild(child) { this.kids.push(child); return child; },
    addEventListener() {},
    focus() {},
  };
}
function openDrawer(title, sub, fill) {
  const body = {kids: [], appendChild(child) { this.kids.push(child); return child; }};
  fill(body);
  return body;
}
function findById(node, id) {
  if (!node) return null;
  if (node.id === id) return node;
  for (const child of node.kids || []) {
    const hit = findById(child, id);
    if (hit) return hit;
  }
  return null;
}

let qPlaceholder = 'missing';
try {
  const addBody = bracedFrom(push, 'function addStreamer');
  if (!addBody) throw new Error('no addStreamer');
  let captured;
  const wrap = (title, sub, fill) => { captured = openDrawer(title, sub, fill); };
  const addStreamer = new Function('store', 'el', 'openDrawer', 'esc', 'STREAMER_INPUT_HINT',
    addBody + '\nreturn addStreamer;')({platforms: ['bilibili']}, el, wrap, String, sentinelHint);
  addStreamer();
  const input = findById(captured, 'add-uid');
  qPlaceholder = input && input.placeholder === '输入 ' + sentinelHint;
} catch (e) {
  qPlaceholder = 'error:' + e.message;
}
eq(qPlaceholder, true, 'placeholder 用同一条常量且等于「输入 」+ STREAMER_INPUT_HINT');

let qEmpty = 'missing';
try {
  const lookupBody = bracedFrom(push, 'async function lookupStreamer');
  if (!lookupBody) throw new Error('no lookupStreamer');
  const lookupStreamer = new Function('STREAMER_INPUT_HINT', 'api', '$',
    lookupBody + '\nreturn lookupStreamer;')(sentinelHint, null, null);
  const out = {textContent: ''};
  await lookupStreamer(['bilibili'], {value: '  '}, {disabled: false}, out);
  qEmpty = out.textContent === '请先输入 ' + sentinelHint + '。';
} catch (e) {
  qEmpty = 'error:' + e.message;
}
eq(qEmpty, true, '空输入句用同一条常量且等于「请先输入 」+ STREAMER_INPUT_HINT +「。」');

// 向导里那一步已随控制台插件走，因此这三格量的是插件那一份。留在核心 setup.js 上量的话，
// 它会永远绿着——那里已经没有这段接线了，而「找不到就当没这回事」与「查过了」长得一样
const setupStreamer = readFileSync(join(pages, 'setup-streamer.js'), 'utf8');
eq(setupStreamer.includes('STREAMER_INPUT_HINT'), true, '向导主播步用同一条常量');
eq(setupStreamer.includes("'uid、直播间号或链接'"), false, '向导主播步旧标签已撤');
// 核心那一侧不许再留一份：留着的话，两份分叉的那天向导上写着一句、推送页上写着另一句
eq(read('setup.js').includes('STREAMER_INPUT_HINT'), false, '核心 setup.js 不再自带这条常量');
const lookupClick = (() => {
  const from = setupStreamer.indexOf("look.id = 'setup-lookup'");
  const to = setupStreamer.indexOf('host.appendChild(look)');
  return from >= 0 && to > from ? setupStreamer.slice(from, to) : '';
})();
eq(lookupClick.length > 0, true, '找得到向导主播步「找一下」的接线');
eq(lookupClick.includes('STREAMER_INPUT_HINT'), true, '向导主播步空输入句用同一条常量');
const emptyGuard = lookupClick.indexOf('if (!value)');
const lookupCall = lookupClick.indexOf("api('/streamer/lookup'");
eq(emptyGuard >= 0 && lookupCall > emptyGuard, true, '向导主播步空值在发请求前拦住');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
