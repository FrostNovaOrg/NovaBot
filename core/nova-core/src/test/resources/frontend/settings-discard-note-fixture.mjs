/**
 * 设置页放弃改动后，那句提示要自己消失
 *
 * 抓的用户故障：设置页放弃改动后，「已放弃这一页的改动」一直挂在底部改动条上不消失——
 * 切到别的页它还挂着，一直到下一次有人写状态栏才没。say 只有类别是 ok 时才四秒后自清，
 * 而放弃这句没带类别，于是永远等不到那一下。
 *
 * 用假时钟量三件：刚放弃后这句话在、类别是 ok；过四秒以上它变成空的；
 * 另一句类别是 err 的话过同样时间仍在——报错那句照旧不自动清，它得留到有人看见为止。
 * 设置页与插件页两条放弃的路各量一遍：两处说的都是这句，两处都得带上 ok。
 *
 * 真 core.js 的 say 与真 settings.js 的 discard 都在这里跑：ok 自清走的是产品码那条定时路，
 * 假时钟只把 4000 毫秒接到手上拨。夹具在 node 上没有真时钟可等，因此自己收发定时器。
 *
 * 由 SettingsDiscardNoteTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
const greens = [];
let checks = 0;

function ask(name, fn) {
  checks++;
  try {
    fn();
    greens.push(name);
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
}

function src(name) {
  return readFileSync(join(ui, name), 'utf8');
}

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个空行」。
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

/** 切出函数的函数体，包成函数交回调用方自己跑（save／discard 是 async 的） */
function loadFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async () => {' + inner + '}')(...args);
}

// —— 假时钟：ok 那句的四秒自清在产品码里是 setTimeout(fn, 4000)，这里收发它自己拨 ——

let clockNow = 0;
let timerSeq = 0;
const timers = [];

function fakeSetTimeout(fn, ms) {
  const id = ++timerSeq;
  timers.push({id, fn, at: clockNow + (ms || 0)});
  return id;
}

function fakeClearTimeout(id) {
  const at = timers.findIndex(t => t.id === id);
  if (at >= 0) timers.splice(at, 1);
}

// 先装后用：say 里那句 setTimeout 是调用时才找的全局，装晚了就拨不动它
globalThis.setTimeout = fakeSetTimeout;
globalThis.clearTimeout = fakeClearTimeout;

/** 拨快 ms 毫秒，到点的定时器按先后跑掉 */
function advance(ms) {
  clockNow += ms;
  for (let guard = 0; guard < 100; guard++) {
    const due = timers.filter(t => t.at <= clockNow).sort((a, b) => a.at - b.at || a.id - b.id);
    if (!due.length) break;
    const first = due[0];
    timers.splice(timers.indexOf(first), 1);
    first.fn();
  }
}

// —— 真 core.js：say 与它带的底部改动条重画都走产品码那一份 ——

const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

const htmlClasses = new Set();

globalThis.document = {
  querySelector: node,
  documentElement: {
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

const coreUrl = new URL('../../../main/resources/config-ui/core.js', import.meta.url).href;
const storeUrl = new URL('../../../main/resources/config-ui/store.js', import.meta.url).href;

let core = null;
let store = null;
try {
  core = await import(coreUrl);
  store = (await import(storeUrl)).store;
} catch (err) {
  failures.push('载入 core.js／store.js 就抛了：' + err.message);
}

// —— 切出真 discard，按名字喂进桩。say 喂真的：ok 自清那条路就是要量的 ——

const asked = [];
const askStub = async q => {
  asked.push(q);
  return true;
};
const noop = () => {};

let discard = null;
try {
  discard = loadFn(src('settings.js'), 'function discard(',
    ['ask', 'load', 'say', 'saveTarget', 'reloadChangeSources', 'refreshPages', 'markDirty'],
    [askStub, async () => {}, core.say, () => currentTarget, async () => true, () => true, noop]);
} catch (err) {
  failures.push('切 discard 就抛了：' + err.message);
}

/** 此刻在哪一页上点的放弃：'values' 走设置页那一支，别的走插件页那一支 */
let currentTarget = 'values';

/** 状态栏此刻这句的类别：say 写在 className 上（'status ok' 一类），没类别时记空串 */
function kind() {
  return node('#status-text').className.replace(/^status\s*/, '');
}

/** 回到「刚点完放弃之前」：状态栏清空、假时钟归零 */
function reset(target) {
  currentTarget = target;
  clockNow = 0;
  timers.length = 0;
  asked.length = 0;
  node('#status-text').textContent = '';
  node('#status-text').className = '';
}

// ---------- ① 设置页放弃：那句 ok 四秒后自清；err 那句过同样时间仍在 ----------
// 病：放弃后「已放弃这一页的改动」一直挂着不消失，切到别的页还挂着
let cell1 = '没跑';
try {
  reset('values');
  await discard();
  const just = {'放弃后的话': node('#status-text').textContent, '放弃后的类别': kind()};
  advance(4500);
  const later = node('#status-text').textContent;

  core.say('保存失败：写不进去', 'err');
  const errJust = node('#status-text').textContent;
  advance(4500);
  const errLater = node('#status-text').textContent;

  cell1 = JSON.stringify(Object.assign(just, {
    '过 4 秒以上之后的话': later,
    'err 那句过同样时间还在吗': errLater === errJust && errJust !== '',
  }));
} catch (e) {
  cell1 = 'error:' + e.message;
}
ask('① 设置页放弃后那句带 ok、四秒后自己消失；err 那句过同样时间仍在', () => {
  const want = JSON.stringify({
    '放弃后的话': '已放弃这一页的改动', '放弃后的类别': 'ok',
    '过 4 秒以上之后的话': '',
    'err 那句过同样时间还在吗': true,
  });
  if (cell1 !== want) throw new Error('得到 ' + cell1 + '，应为 ' + want);
});

// ---------- ② 插件页放弃：说的也是这句，同样四秒后自清 ----------
// 病：同上，换成插件页那一支——两处说的都是这句，只修一处的话另一处照旧挂着
let cell2 = '没跑';
try {
  reset('推送页');
  await discard();
  const just = {'放弃后的话': node('#status-text').textContent, '放弃后的类别': kind()};
  advance(4500);
  cell2 = JSON.stringify(Object.assign(just, {'过 4 秒以上之后的话': node('#status-text').textContent}));
} catch (e) {
  cell2 = 'error:' + e.message;
}
ask('② 插件页放弃后那句也带 ok、四秒后自己消失', () => {
  const want = JSON.stringify({
    '放弃后的话': '已放弃这一页的改动', '放弃后的类别': 'ok',
    '过 4 秒以上之后的话': '',
  });
  if (cell2 !== want) throw new Error('得到 ' + cell2 + '，应为 ' + want);
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of greens) console.log('  绿：' + line);
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
