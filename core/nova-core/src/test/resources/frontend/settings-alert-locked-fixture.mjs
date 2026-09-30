/**
 * 告警卡：配置文件里写成界面改不了的项，不给输入框，只读摆出读到的值并写上锁的说明
 *
 * 抓的用户故障：告警那几项在配置文件里写成引用别处的写法时，卡上仍给输入框。
 * 改了点保存，整批被拒，同批别的改动也一起没存上。
 *
 * ① 锁住的那一格没有输入框，只读摆出读到的值，说明用读数里现成的那句。
 * ② 没锁的照旧有输入框。③「发一条测试」仍在，且没有被关掉。
 *
 * 由 SettingsAlertLockedViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLEAR, MASK, secretDraft} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');
const model = readFileSync(join(ui, 'alert-model.js'), 'utf8');

const LOCKED = 'novabot.core.mail.default-to';
const OPEN = 'novabot.core.alert.webhook-url';
const VALUE = 'ops@example.invalid';
const REASON = '这一项在配置文件里写成了别名（*名字），引到的是一份名单或一整块设置，'
  + '在界面改会拆掉这层引用；这里显示的是程序实际读到的值，要改请到配置文件里改。';

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

function loadMailConfigured() {
  const body = bracedFrom(model, 'export function mailAlertConfigured').replace(/^export\s+/, '');
  if (!body) throw new Error('no mailAlertConfigured');
  return new Function(body + '\nreturn mailAlertConfigured;')();
}

function makeEl(tag, className) {
  const node = {
    tagName: String(tag).toUpperCase(),
    className: className || '',
    id: '',
    children: [],
    parentElement: null,
    dataset: {},
    style: {},
    attrs: {},
    listeners: {},
    _text: '',
    _value: '',
    type: 'text',
    disabled: false,
    placeholder: '',
    classList: {
      toggle(name, force) {
        const parts = new Set((node.className || '').split(/\s+/).filter(Boolean));
        const on = force === undefined ? !parts.has(name) : !!force;
        if (on) parts.add(name); else parts.delete(name);
        node.className = [...parts].join(' ');
      },
    },
    set textContent(v) { node._text = String(v ?? ''); node.children = []; },
    get textContent() {
      return node._text + node.children.map(c => c.textContent).join('');
    },
    set value(v) { node._value = String(v ?? ''); },
    get value() { return node._value; },
    appendChild(child) {
      child.parentElement = node;
      node.children.push(child);
      return child;
    },
    append(...nodes) { for (const n of nodes) node.appendChild(n); },
    addEventListener(type, fn) { (node.listeners[type] ||= []).push(fn); },
    setAttribute(k, v) {
      node.attrs[k] = String(v);
      if (k === 'aria-label') node.ariaLabel = String(v);
    },
    querySelector(sel) { return qsAll(node, sel)[0] || null; },
    querySelectorAll(sel) { return qsAll(node, sel); },
  };
  return node;
}

function qsAll(root, sel) {
  const out = [];
  const walk = node => {
    for (const child of node.children || []) {
      if (matchSel(child, sel)) out.push(child);
      walk(child);
    }
  };
  walk(root);
  return out;
}

function matchSel(node, sel) {
  if (sel.startsWith('.')) {
    return (node.className || '').split(/\s+/).includes(sel.slice(1));
  }
  if (sel.startsWith('#')) return node.id === sel.slice(1);
  return node.tagName === String(sel).toUpperCase();
}

function loadUi(store, api) {
  const start = src.indexOf('export function cardFields');
  const marker = 'export function alertCards';
  const block = bracedFrom(src, marker);
  const acStart = src.indexOf(marker);
  if (start < 0 || acStart < 0 || !block) throw new Error('no slice');
  const body = src.slice(start, acStart + block.length).replace(/^export /gm, '');
  return new Function('store', 'api', 'el', 'term', 'markDirty', 'mailAlertConfigured', 'MASK', 'CLEAR', 'secretDraft',
    body + '\nreturn {cardFields, alertCards};')(
    store, api, makeEl, (_k, fallback) => fallback, () => {}, loadMailConfigured(), MASK, CLEAR, secretDraft);
}

function collect(root, test, out) {
  const acc = out || [];
  if (root && test(root)) acc.push(root);
  for (const child of (root && root.children) || []) collect(child, test, acc);
  return acc;
}

function hasClass(node, name) {
  return (node.className || '').split(/\s+/).includes(name);
}

function keyWrap(root, key) {
  const hit = collect(root, n => hasClass(n, 'keyname') && n._text === key);
  return hit.length ? hit[0].parentElement : null;
}

function controls(wrap) {
  return collect(wrap, n => n.tagName === 'INPUT' || n.tagName === 'SELECT' || n.tagName === 'TEXTAREA');
}

const store = {
  dirty: {},
  values: {
    [LOCKED]: VALUE,
    [OPEN]: 'https://example.invalid/hook',
  },
  vocab: {},
  alertChannels: [],
  locked: {[LOCKED]: REASON},
};

let lockedSeen = 'missing';
let openSeen = 'missing';
let buttons = 'missing';
try {
  const uiCards = loadUi(store, async () => ({alerts: {mail: false}}));
  const root = uiCards.alertCards();
  const lockedWrap = keyWrap(root, LOCKED);
  if (!lockedWrap) throw new Error('no locked field');
  const shown = collect(lockedWrap, n => hasClass(n, 'lockedval')).map(n => n._text);
  const notes = collect(lockedWrap, n => n._text === REASON);
  lockedSeen = {inputs: controls(lockedWrap).length, shown, notes: notes.length};
  const openWrap = keyWrap(root, OPEN);
  if (!openWrap) throw new Error('no open field');
  openSeen = controls(openWrap).map(n => n.tagName);
  const tests = collect(root, n => n.tagName === 'BUTTON' && n._text === '发一条测试');
  buttons = {count: tests.length, enabled: tests.filter(n => !n.disabled).length};
} catch (e) {
  lockedSeen = openSeen = buttons = 'error:' + e.message;
}

eq(lockedSeen, {inputs: 0, shown: [VALUE], notes: 1},
  '① 锁住的收件邮箱：不出输入框，只读摆出读到的值，附说明');
eq(openSeen, ['INPUT'], '② 没锁的地址照旧是输入框');
eq(buttons, {count: 2, enabled: 2}, '③ 发一条测试仍在且可点');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
