/**
 * 告警卡：锁住的栏与预设
 *
 * 抓的用户故障：
 * ① 打开设置页时，锁住的栏若恰好对上某个预设，这一栏连同说明被收起来，看不见。
 * ② 切到一个要改锁住栏的预设，下拉换成了新预设，没锁的栏也跟着变，存下来的是半套。
 * ③ 锁住的值本来就是预设要的，预设应当照常换，只改没锁的栏。
 * ④ 锁住的项还能写进改动账，一点保存整批被拒，同批别的改动也没存上。
 * ⑤ 收件人是一个下拉、一次改三个键；其中有锁着的仍给下拉，一选就把锁住的键一起改掉。
 *
 * 由 SettingsAlertPresetLockTest 按参数分格拉起。量的是源码树里那一份。
 * 环境变量 ALERT_LOCK_SRC 指向另一份源码时，改量那一份（只给拷贝上删掉一处用）。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLEAR, MASK, secretDraft} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(process.env.ALERT_LOCK_SRC || join(ui, 'settings-alert.js'), 'utf8');
const model = readFileSync(join(ui, 'alert-model.js'), 'utf8');

const METHOD = 'novabot.core.alert.webhook-method';
const TITLE = 'novabot.core.alert.webhook-title-field';
const CONTENT = 'novabot.core.alert.webhook-content-field';
const HOST = 'spring.mail.host';
const PORT = 'spring.mail.port';
const REASON = '这一项在配置文件里写成了别名，引到的是一份名单或一整块设置，'
  + '在界面改会拆掉这层引用；这里显示的是程序实际读到的值，要改请到配置文件里改。';

const WEBHOOK_REFUSE = '提交方式这一栏锁着，改不了，所以没有换成「Bark」。要换的话，得到配置文件里改。';
const MAIL_REFUSE = '服务器这一栏锁着，改不了，所以没有换成「Gmail」。要换的话，得到配置文件里改。';

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

function cardOf(root, channel) {
  const hit = collect(root, n => n.dataset && n.dataset.channel === channel);
  if (!hit.length) throw new Error('no card ' + channel);
  return hit[0];
}

function customOf(card) {
  const hit = collect(card, n => hasClass(n, 'al-cus'));
  if (!hit.length) throw new Error('no custom box');
  return hit[0];
}

function presetOf(card) {
  const hit = collect(card, n => n.tagName === 'SELECT' && n.attrs['aria-label'] === '预设');
  if (!hit.length) throw new Error('no preset');
  return hit[0];
}

function shownValue(root, key) {
  const wrap = keyWrap(root, key);
  if (!wrap) return 'missing';
  const locked = collect(wrap, n => hasClass(n, 'lockedval'))[0];
  if (locked) return locked.value;
  const input = controls(wrap)[0];
  return input ? input.value : 'missing';
}

function noteText(card) {
  const note = collect(card, n => hasClass(n, 'al-preset-note'))[0];
  return note ? note._text : '';
}

function noteVisible(card) {
  const note = collect(card, n => hasClass(n, 'al-preset-note'))[0];
  return !!(note && !hasClass(note, 'hide') && note._text);
}

function fire(node, type) {
  for (const fn of node.listeners[type] || []) fn();
}

function paint(values, locked) {
  const store = {
    dirty: {},
    values,
    vocab: {},
    alertChannels: [],
    locked: locked || {},
  };
  const uiCards = loadUi(store, async () => ({alerts: {mail: false}}));
  return {store, root: uiCards.alertCards()};
}

function openWebhook() {
  const {root} = paint({
    [METHOD]: 'GET',
    [TITLE]: 'title',
    [CONTENT]: 'body',
  }, {[METHOD]: REASON});
  const card = cardOf(root, 'webhook');
  const custom = customOf(card);
  eq(presetOf(card).value, 'Bark', '① 现值仍认成 Bark');
  eq(hasClass(custom, 'hide'), false, '① 首屏：提交方式锁着且对上 Bark，这一栏仍摊开');
  eq(shownValue(card, METHOD), 'GET', '① 摊开后看得到锁住的提交方式');
  eq(collect(custom, n => n._text === REASON).length, 1, '① 说明跟锁住的栏在一起，没有被收起来');
}

function openMail() {
  const {root} = paint({
    [HOST]: 'smtp.qq.com',
    [PORT]: '465',
  }, {[HOST]: REASON});
  const card = cardOf(root, 'mail');
  const custom = customOf(card);
  eq(presetOf(card).value, 'QQ 邮箱', '② 现值仍认成 QQ 邮箱');
  eq(hasClass(custom, 'hide'), false, '② 首屏：服务器锁着且对上 QQ 邮箱，这一栏仍摊开');
  eq(shownValue(card, HOST), 'smtp.qq.com', '② 摊开后看得到锁住的服务器');
  eq(collect(custom, n => n._text === REASON).length, 1, '② 说明跟锁住的栏在一起，没有被收起来');
}

function rejectWebhook() {
  const {store, root} = paint({
    [METHOD]: 'POST',
    [TITLE]: 'title',
    [CONTENT]: 'desp',
  }, {[METHOD]: REASON});
  const card = cardOf(root, 'webhook');
  const select = presetOf(card);
  const before = {
    preset: select.value,
    title: shownValue(card, TITLE),
    content: shownValue(card, CONTENT),
    method: shownValue(card, METHOD),
  };
  select.value = 'Bark';
  fire(select, 'change');
  eq(before.preset, 'Server 酱', '③ 切之前认的是 Server 酱');
  eq(select.value, 'Server 酱', '③ 要改锁住的提交方式，下拉退回 Server 酱');
  eq(shownValue(card, METHOD), before.method, '③ 锁住的提交方式不动');
  eq(shownValue(card, TITLE), before.title, '③ 没锁的标题字段名也不动');
  eq(shownValue(card, CONTENT), before.content, '③ 没锁的内容字段名也不动');
  eq(Object.keys(store.dirty).sort(), [], '③ 改动账是空的');
  eq(noteText(card), WEBHOOK_REFUSE, '③ 旁边说明哪一栏锁着');
  eq(noteVisible(card), true, '③ 这句说明看得见');
}

function rejectMail() {
  const {store, root} = paint({
    [HOST]: 'smtp.qq.com',
    [PORT]: '465',
  }, {[HOST]: REASON});
  const card = cardOf(root, 'mail');
  const select = presetOf(card);
  const before = {
    preset: select.value,
    host: shownValue(card, HOST),
    port: shownValue(card, PORT),
  };
  select.value = 'Gmail';
  fire(select, 'change');
  eq(before.preset, 'QQ 邮箱', '④ 切之前认的是 QQ 邮箱');
  eq(select.value, 'QQ 邮箱', '④ 要改锁住的服务器，下拉退回 QQ 邮箱');
  eq(shownValue(card, HOST), before.host, '④ 锁住的服务器不动');
  eq(shownValue(card, PORT), before.port, '④ 没锁的端口也不动');
  eq(Object.keys(store.dirty).sort(), [], '④ 改动账是空的');
  eq(noteText(card), MAIL_REFUSE, '④ 旁边说明哪一栏锁着');
  eq(noteVisible(card), true, '④ 这句说明看得见');
}

function matchPreset() {
  const hook = paint({
    [METHOD]: 'GET',
    [TITLE]: 'title',
    [CONTENT]: 'zzz',
  }, {[METHOD]: REASON});
  const hookCard = cardOf(hook.root, 'webhook');
  const hookSelect = presetOf(hookCard);
  eq(hookSelect.value, '自定义', '⑤ 内容对不上 Bark 时先显示自定义');
  hookSelect.value = 'Bark';
  fire(hookSelect, 'change');
  eq(hookSelect.value, 'Bark', '⑤ 提交方式锁着的值就是 Bark 要的 GET，预设照常换成 Bark');
  eq(shownValue(hookCard, METHOD), 'GET', '⑤ 锁住的提交方式保持 GET');
  eq(hook.store.dirty[METHOD], undefined, '⑤ 锁住的提交方式不进改动账');
  eq(hook.store.dirty[CONTENT], 'body', '⑤ 没锁的内容字段名照常改成 body');
  eq(noteText(hookCard), '', '⑤ 照常换时不写拒换说明');

  const mail = paint({
    [HOST]: 'smtp.gmail.com',
    [PORT]: '465',
  }, {[HOST]: REASON});
  const mailCard = cardOf(mail.root, 'mail');
  const mailSelect = presetOf(mailCard);
  eq(mailSelect.value, '自定义', '⑤ 端口不是 587 时 Gmail 先不算对上');
  mailSelect.value = 'Gmail';
  fire(mailSelect, 'change');
  eq(mailSelect.value, 'Gmail', '⑤ 服务器锁着的值就是 Gmail 要的，预设照常换成 Gmail');
  eq(shownValue(mailCard, HOST), 'smtp.gmail.com', '⑤ 锁住的服务器保持 smtp.gmail.com');
  eq(mail.store.dirty[HOST], undefined, '⑤ 锁住的服务器不进改动账');
  eq(mail.store.dirty[PORT], '587', '⑤ 没锁的端口照常改成 587');
  eq(noteText(mailCard), '', '⑤ 照常换时不写拒换说明');
}

function lockedSetValue() {
  const store = {values: {[HOST]: 'smtp.qq.com'}, dirty: {}, locked: {[HOST]: REASON}};
  let marks = 0;
  const shown = bracedFrom(src, 'const SHOWN_WHEN_UNSET = ');
  const body = bracedFrom(src, 'function setValue');
  if (!shown || !body) throw new Error('no setValue');
  const setValue = new Function('store', 'markDirty', 'MASK', 'CLEAR',
    shown + '\n' + body + '\nreturn setValue;')(store, () => { marks++; }, MASK, CLEAR);
  setValue(HOST, 'smtp.gmail.com');
  eq(store.dirty[HOST], undefined, '⑥ 锁住的服务器写成别的值，也不进改动账');
  eq(marks, 0, '⑥ 锁住的项不触动改动条');

  const open = {values: {[PORT]: '465'}, dirty: {}, locked: {}};
  const setOpen = new Function('store', 'markDirty', 'MASK', 'CLEAR',
    shown + '\n' + body + '\nreturn setValue;')(open, () => {}, MASK, CLEAR);
  setOpen(PORT, '587');
  eq(open.dirty[PORT], '587', '⑥ 没锁的端口照常记账，锁门没有把整条记账掐掉');
}

const STUB = {
  id: 'stub-bot',
  name: '桩通道',
  recipient: [
    {key: 'plug.alert.platform', label: '平台', type: 'hidden', placeholder: '', pattern: '', fill: 'sender'},
    {key: 'plug.alert.type', label: '种类', type: 'hidden', placeholder: '', pattern: '', fill: 'kind'},
    {key: 'plug.alert.num', label: '发给谁', type: 'select', placeholder: '', pattern: '^\\d+$', fill: 'num'},
  ],
};

function recipientLock() {
  const store = {
    dirty: {},
    values: {
      'plug.alert.platform': 'bot-a',
      'plug.alert.type': '1',
      'plug.alert.num': '1001',
    },
    vocab: {},
    alertChannels: [STUB],
    locked: {'plug.alert.platform': REASON},
  };
  const uiCards = loadUi(store, async () => ({alerts: {mail: false}, items: []}));
  const root = uiCards.alertCards();
  const card = cardOf(root, STUB.id);
  const sender = keyWrap(card, 'plug.alert.platform');
  const num = keyWrap(card, 'plug.alert.num');
  eq(root.querySelector('#alert-bot-target') ? '有下拉' : '无下拉', '无下拉', '⑦ 三个键里平台锁着，不给收件人下拉');
  eq(sender ? controls(sender).length : -1, 0, '⑦ 锁住的平台没有输入框');
  eq(sender ? collect(sender, n => hasClass(n, 'lockedval')).map(n => n._text) : [], ['bot-a'],
    '⑦ 平台只读摆出读到的值');
  eq(sender ? collect(sender, n => n._text === REASON).length : 0, 1, '⑦ 平台旁边写上为什么锁');
  eq(num ? controls(num).length : -1, 0, '⑦ 发给谁也不再是下拉');
  eq(num ? collect(num, n => hasClass(n, 'lockedval')).map(n => n._text) : [], ['1001'],
    '⑦ 发给谁只读显示已经配好的号');
  eq(Object.keys(store.dirty).sort(), [], '⑦ 画出来时改动账是空的');
}

const scenario = process.argv[2] || '';
const cases = {
  'open-webhook': openWebhook,
  'open-mail': openMail,
  'reject-webhook': rejectWebhook,
  'reject-mail': rejectMail,
  'match': matchPreset,
  'setvalue': lockedSetValue,
  'recipient': recipientLock,
};

try {
  const run = cases[scenario];
  if (!run) throw new Error('没有这一格：' + scenario);
  run();
} catch (e) {
  failures.push('抛了：' + (e && e.stack ? e.stack : e));
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
