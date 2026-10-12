/**
 * 直接打开或刷新设置页：QQ 告警卡存在，配置项留在卡内。
 * 执行 main.js 的 load、设置页 buildGroup 和告警卡绘制，接口使用本地响应。
 */
import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLEAR, MASK, secretDraft, defaultValue} from '../../../main/resources/config-ui/settings-model.js';
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


function loadAsyncFn(text, marker, params, args, formals) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async ' + (formals || '()') + ' => {' + inner + '}')(...args);
}

const key = 'novabot.adapter.onebot.alert.platform';
const group = {group: 'alert', title: '告警', fields: [{name: key}]};
const channel = {id: 'qq', name: 'QQ', recipient: [
  {key, type: 'hidden', fill: 'sender'},
  {key: 'novabot.adapter.onebot.alert.type', type: 'hidden', fill: 'kind'},
  {key: 'novabot.adapter.onebot.alert.num', type: 'select', fill: 'num'},
]};
const noop = () => {};

// 抓的错：刷新后 QQ 卡缺失，QQ 平台项散成普通行；定位告警时三卡没有全部展开。
// 抓的错：状态接口一时失败，直接载入设置页后整页空白、QQ 卡不见。
for (const [card, channelFailure, statusFailure] of [['', false], ['alert', false], ['', true], ['', false, true]]) {
  const store = {dirty: {}, values: {}, alertChannels: [], locked: {}, vocab: {}};
  let root;
  const api = async path => {
    if (path === '/schema') return {groups: [group]};
    if (path === '/values') return {values: {}};
    if (path === '/alert/channels') {
      if (channelFailure) throw new Error('unavailable');
      return {channels: [channel]};
    }
    if (path === '/status') {
      // 设置页画出后、后续接口仍在载入时填写的内容仍需保留。
      store.dirty['demo.pending'] = 'draft';
      if (statusFailure) throw new Error('status unavailable');
      return {alerts: {mail: false}};
    }
    return {};
  };
  const cards = loadUi(store, api);
  const buildGroup = new Function('el', 'ALERT_GROUP', 'AUTH_GROUP', 'AUTH_CARD_FIELDS',
    'cardFields', 'alertCards', 'authCards', 'buildRow',
    bracedFrom(readFileSync(join(ui, 'settings.js'), 'utf8'), 'function buildGroup(')
      + '\nreturn buildGroup;')(
    makeEl, 'alert', 'auth', new Set(), cards.cardFields, cards.alertCards, noop,
    field => { const row = makeEl('div', 'field'); row.textContent = field.name; return row; });
  const openCards = new Function('cardOpen', bracedFrom(src, 'export function openCards').replace(/^export /, '')
    + '\nreturn openCards;')(new Map());
  const load = loadAsyncFn(readFileSync(join(ui, 'main.js'), 'utf8'), 'function load(',
    ['say', 'api', 'store', 'renderGeneral', 'parseHash', 'focusCard', 'reloadChangeSources',
      'renderStatus', 'renderConfigPath', 'refreshPages', 'refreshHome', 'fillBotForms',
      'applyConnectionVocab', 'markDirty', 'notePlatformsArrived', 'CLEAR', 'defaultValue'],
    [noop, api, store, () => { root = buildGroup(group); }, () => ({name: 'settings', card}),
      (_name, target) => { if (target === 'alert') openCards(root); }, async () => true,
      noop, noop, noop, noop, noop, noop, noop, noop, CLEAR, defaultValue], 'how');
  try {
    await load();
    await new Promise(resolve => setImmediate(resolve));
    const all = collect(root, n => hasClass(n, 'alcard'));
    if (statusFailure) {
      eq({rendered: !!root, channels: all.map(n => n.dataset.channel)},
        {rendered: true, channels: ['qq', 'webhook', 'mail']},
        '状态接口失败时直接载入设置页：页面照常画出，QQ 卡存在');
      continue;
    }
    eq(store.dirty['demo.pending'], 'draft', '设置页画出后载入途中填写的内容保留');
    if (channelFailure) {
      eq({channels: all.map(n => n.dataset.channel), ordinaryRows: collect(root, n => hasClass(n, 'field') && n.textContent === key).length},
        {channels: ['webhook', 'mail'], ordinaryRows: 1}, '通道表取失败：按空表照常绘制设置页');
      continue;
    }
    const qq = all.find(n => n.dataset.channel === 'qq');
    const picker = collect(qq, n => n.id === 'alert-bot-target')[0];
    if (picker) {
      picker.value = 'QQ|1|123';
      for (const listener of picker.listeners.change || []) listener();
    }
    eq({channels: all.map(n => n.dataset.channel), inCard: store.dirty[key] === 'QQ',
      ordinaryRows: collect(root, n => hasClass(n, 'field') && n.textContent === key).length,
      expanded: card ? all.every(n => n.open) : true},
    {channels: ['qq', 'webhook', 'mail'], inCard: true, ordinaryRows: 0, expanded: true},
    '直接载入 #/settings' + (card ? '?card=alert' : '') + '：QQ 卡存在，平台项在卡内，定位时三卡展开');
  } catch (e) { failures.push('设置页载入抛错：' + e.message); }
}
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
