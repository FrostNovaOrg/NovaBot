/**
 * 另一个控制台标签切了深浅色，这一个跟着变，而且手上的活不丢
 *
 * 抓的用户故障：开两个控制台标签，一边切了深色，另一边还是浅色；或者跟着变了，
 * 却是靠把设置页整个重画一遍——这边正填着、还没保存的那一格被清掉，光标也跑了。
 *
 * 这把尺真跑 theme.js（假 window 收下它挂的 storage 监听，假 localStorage 当两个标签共用的那一份），
 * 再从 settings.js 里切出它交给 subscribeTheme 的那个函数，接到真 theme.js 上。
 * 设置页这一侧只摆一个正在填的输入框和三颗主题钮；重画用一个会清空输入框的桩代替，
 * 订阅回调要是去重画，那一格就丢了。
 *
 * 各格：①另一标签切深色：本页换肤，钮跟着变，输入框的字与焦点都在；
 * ②另一标签 localStorage.clear()（key 为 null）：回到跟随系统，去掉手动那一档；
 * ③别的键、别的存储区（sessionStorage）的事件不理；④本页自己点钮：订阅同样把钮改对。
 *
 * 由 ThemeCrossTabTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

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

// —— 两个标签共用的那一份 localStorage，与另一个存储区 ——
const bag = new Map();
const storage = {
  getItem: k => (bag.has(k) ? bag.get(k) : null),
  setItem: (k, v) => bag.set(k, String(v)),
  removeItem: k => bag.delete(k),
};
globalThis.localStorage = storage;
const otherArea = {getItem: () => null};

// —— 本页：根元素、三颗主题钮、一个正在填的输入框 ——
const attrs = new Map();
function button(value) {
  const classes = new Set();
  const own = {};
  return {
    value, own,
    classList: {
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      add(c) { classes.add(c); },
      remove(c) { classes.delete(c); },
      contains: c => classes.has(c),
    },
    getAttribute: k => (k === 'data-theme-choice' ? value : (own[k] ?? null)),
    setAttribute(k, v) { own[k] = String(v); },
  };
}
const buttons = ['auto', 'light', 'dark'].map(button);
const draft = {value: '还没保存的字'};
let rendered = 0;
globalThis.document = {
  documentElement: {
    setAttribute: (k, v) => attrs.set(k, String(v)),
    removeAttribute: k => attrs.delete(k),
    getAttribute: k => (attrs.has(k) ? attrs.get(k) : null),
  },
  querySelectorAll: sel => (sel === '[data-theme-choice]' ? buttons : []),
  activeElement: draft,
};
/** 重画的桩：真重画会清空组容器，正在填的那一格跟着没了 */
function renderGeneral() {
  rendered++;
  draft.value = '';
  globalThis.document.activeElement = null;
}

// —— 假 window：收下 theme.js 挂上去的监听 ——
const listeners = {};
globalThis.window = {
  addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
  removeEventListener() {},
  dispatchEvent() { return true; },
};
function fire(type, event) {
  for (const fn of listeners[type] || []) fn(event);
}

let theme = null;
try {
  theme = await import(new URL('../../../main/resources/config-ui/theme.js', import.meta.url).href);
} catch (err) {
  failures.push('载入 theme.js 就抛了：' + err.message);
}

// 设置页交给 subscribeTheme 的是哪个函数：按名字切出来，接到真 theme.js 上
const settingsSrc = readFileSync(join(ui, 'settings.js'), 'utf8');
const wired = /\bsubscribeTheme\(\s*([A-Za-z_$][\w$]*)\s*\)/.exec(settingsSrc);
let onTheme = null;
if (!wired) {
  failures.push('settings.js 没把任何函数交给 subscribeTheme：别的标签切了主题，这一页的钮不会跟着变');
} else {
  const decl = bracedFrom(settingsSrc, 'function ' + wired[1] + '(');
  if (!decl) failures.push('切不出 ' + wired[1]);
  else {
    try {
      onTheme = new Function('document', 'renderGeneral', decl + '\nreturn ' + wired[1] + ';')(
        globalThis.document, renderGeneral);
    } catch (err) {
      failures.push('烘 ' + wired[1] + ' 就抛了：' + err.message);
    }
  }
}
if (theme && onTheme) {
  if (typeof theme.subscribeTheme !== 'function') failures.push('theme.js 没有 subscribeTheme');
  else theme.subscribeTheme(onTheme);
}

const pressed = () => Object.fromEntries(buttons.map(b => [b.value,
  b.classList.contains('cur') + '/' + (b.own['aria-pressed'] ?? '-')]));
const KEY = 'novabot-theme';

// ---------- ① 另一标签切深色 ----------
bag.set(KEY, 'dark');
fire('storage', {key: KEY, oldValue: null, newValue: 'dark', storageArea: storage});
eq(attrs.get('data-theme') ?? '(无)', 'dark', '① 另一标签切深色：本页 data-theme 跟着换');
eq(pressed(), {auto: 'false/false', light: 'false/false', dark: 'true/true'}, '① 深色那颗钮亮起、读屏念「已按下」');
eq({value: draft.value, focused: globalThis.document.activeElement === draft, rendered},
  {value: '还没保存的字', focused: true, rendered: 0}, '① 没保存的字和焦点都还在，设置页没重画');

// ---------- ② 另一标签清空了整个 localStorage ----------
bag.clear();
fire('storage', {key: null, oldValue: null, newValue: null, storageArea: storage});
eq(attrs.has('data-theme'), false, '② clear()（key 为 null）：去掉手动那一档，回到跟随系统');
eq(pressed().auto, 'true/true', '② 跟随系统那颗钮亮起');

// ---------- ③ 不相干的事件不理 ----------
fire('storage', {key: 'other-key', newValue: 'dark', storageArea: storage});
fire('storage', {key: KEY, newValue: 'dark', storageArea: otherArea});
eq(attrs.has('data-theme'), false, '③ 别的键、别的存储区的事件不动主题');

// ---------- ④ 本页自己点钮：同一条订阅把钮改对 ----------
if (theme) theme.applyTheme('light');
eq([attrs.get('data-theme') ?? '(无)', pressed().light, pressed().auto], ['light', 'true/true', 'false/false'],
  '④ 本页选浅色：换肤，钮由订阅改对');
eq(draft.value, '还没保存的字', '④ 本页切主题也不重画');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
