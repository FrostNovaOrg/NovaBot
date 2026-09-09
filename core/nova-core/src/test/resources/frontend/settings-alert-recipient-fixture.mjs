/**
 * 告警卡收件人栏按通道申报渲染
 *
 * 空／缺 channels 时核心九键、只画 Webhook 与邮件两张卡。
 * 桩通道申报三键时十二键、三张卡，选名单后草稿落到桩键——
 * 若 setValue 仍写死适配器键名，末格红。
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 SettingsAlertRecipientTest 拉起。量的是源码树里那一份。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');
const model = readFileSync(join(ui, 'alert-model.js'), 'utf8');

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
    setAttribute(k, v) { node.attrs[k] = String(v); },
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
  return new Function('store', 'api', 'el', 'term', 'markDirty', 'mailAlertConfigured',
    body + '\nreturn {cardFields, alertCards};')(
    store, api, makeEl, (_k, fallback) => fallback, () => {}, loadMailConfigured());
}

const CORE_NINE = [
  'novabot.core.alert.webhook-url', 'novabot.core.alert.webhook-method',
  'novabot.core.alert.webhook-title-field', 'novabot.core.alert.webhook-content-field',
  'novabot.core.mail.default-to',
  'spring.mail.host', 'spring.mail.port', 'spring.mail.username', 'spring.mail.password',
];

const STUB = {
  id: 'stub-bot',
  name: '桩通道',
  recipient: [
    {key: 'plug.alert.platform', label: '', type: 'hidden', placeholder: '', pattern: '', fill: 'sender'},
    {key: 'plug.alert.type', label: '', type: 'hidden', placeholder: '', pattern: '', fill: 'kind'},
    {key: 'plug.alert.num', label: '发给谁', type: 'select', placeholder: '', pattern: '^\\d+$', fill: 'num'},
  ],
};

function stubApi(path) {
  if (String(path).startsWith('/bot/targets')) {
    return Promise.resolve({items: [{sender: 'bot-a', num: '1001', name: '群甲'}]});
  }
  if (path === '/status') return Promise.resolve({alerts: {mail: false}});
  return Promise.resolve({});
}

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

function sameSet(actual, expected) {
  if (actual.length !== expected.length) return false;
  const a = [...actual].sort();
  const b = [...expected].sort();
  return a.every((k, i) => k === b[i]);
}

let q1 = 'missing';
try {
  const store = {dirty: {}, values: {}, vocab: {}, alertChannels: []};
  const uiEmpty = loadUi(store, stubApi);
  const emptyKeys = [...uiEmpty.cardFields()];
  delete store.alertChannels;
  const missingKeys = [...uiEmpty.cardFields()];
  q1 = sameSet(emptyKeys, CORE_NINE) && sameSet(missingKeys, CORE_NINE);
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '① 空／缺 channels 时 cardFields 恰九键');

let q2 = 'missing';
try {
  const store = {dirty: {}, values: {}, vocab: {}, alertChannels: []};
  const uiEmpty = loadUi(store, stubApi);
  const emptyWrap = uiEmpty.alertCards();
  delete store.alertChannels;
  const missingWrap = uiEmpty.alertCards();
  q2 = emptyWrap.querySelectorAll('.alcard').length === 2
    && missingWrap.querySelectorAll('.alcard').length === 2;
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '② 空／缺 channels 时 alertCards 恰 2 张不抛');

let q3 = 'missing';
try {
  const store = {dirty: {}, values: {}, vocab: {}, alertChannels: [STUB]};
  const uiStub = loadUi(store, stubApi);
  const keys = [...uiStub.cardFields()];
  const wrap = uiStub.alertCards();
  const cards = wrap.querySelectorAll('.alcard');
  q3 = sameSet(keys, CORE_NINE.concat(STUB.recipient.map(f => f.key)))
    && cards.length === 3
    && cards[0].dataset.channel === STUB.id;
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③ 桩通道时十二键、3 张、首张 dataset.channel 为桩 id');

let q4 = 'missing';
try {
  const store = {dirty: {}, values: {}, vocab: {}, alertChannels: [STUB]};
  const uiStub = loadUi(store, stubApi);
  const wrap = uiStub.alertCards();
  await settle();
  const select = wrap.querySelector('#alert-bot-target');
  if (!select) throw new Error('no select');
  select.value = 'bot-a|1|1001';
  for (const fn of select.listeners.change || []) fn();
  const dirtyKeys = Object.keys(store.dirty).sort();
  const stubKeys = STUB.recipient.map(f => f.key).sort();
  q4 = dirtyKeys.length === 3 && dirtyKeys.every((k, i) => k === stubKeys[i]);
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, true, '④ select 变更后 store.dirty 落的三键＝桩键');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
