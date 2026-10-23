/**
 * 设置页两选一的配置项：选完存进去的值与原来文本框存的一字不差
 *
 * 抓的用户故障：「扫码登录方式」只认 tv 与 web 两种写法，界面上给的却是自由文本框——
 * 手一滑填出别的字，保存后登录那条路当场走不通，还得回配置文件里找是哪一项填岔了。
 * 改成两项选择之后，选出来存进改动条、送到服务端的值必须与原来文本框里存的那一份
 * 完全一样（仍然是 tv／web 这两个字），不能换成中文标签、序号或别的编码。
 *
 * 三格各盯一段：①没给候选值的普通文本项照旧是文本框（这一格是阳性对照）；
 * ②给了候选值的项换成两项选择框，候选项按给定顺序摆；③选 tv／web 存下的值与同值
 * 文本框存下的一字不差，且当前值不在候选表里时照样留着（不悄悄换掉、不丢掉）。
 * ③先红后绿。
 *
 * 由 SettingsChoicesTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {
  CLEAR, canonicalValue, dangerOf, defaultText, defaultValue, effectOf, isChanged, isDangerous,
  secretDraft,
} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 从 marker 起按花括号配平截出声明（字符串与注释里的花括号不算层） */
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

function loadFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  return new Function(...params,
    body + '\nreturn ' + marker.slice(marker.indexOf(' ') + 1).replace(/\(.*/, '') + ';')(...args);
}

/** 最小元素桩：记下属性、子节点与监听器 */
function node(tag, cls) {
  return {
    tag, className: cls || '', dataset: {},
    textContent: '', type: '', value: '', placeholder: '', title: '',
    autocomplete: '', name: '', disabled: false, checked: false,
    attrs: {}, kids: [], listeners: {}, style: {},
    classList: {toggle() {}, add() {}, remove() {}},
    setAttribute(key, value) {
      this.attrs[key] = String(value);
    },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
  };
}

function collect(root, tag, out) {
  const acc = out || [];
  if (root && root.tag === tag) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, tag, acc);
  return acc;
}

/** 造一行普通字符串项；要不要给候选值由 over 决定 */
function fieldOf(over) {
  return Object.assign({
    name: 'novabot.bilibili.account.qr-code-login-mode', label: '扫码登录方式', widget: 'string',
    description: '扫码登录时走哪一路', defaultValue: 'tv', effect: 'IMMEDIATE',
    sensitive: false, group: 'collect', order: 0,
  }, over);
}

/** 一份页面：同一份 store，反复建行 */
function page(values) {
  const store = {values: Object.assign({}, values), dirty: {}, legacy: {}};
  const valuesOf = loadFn(src, 'function valuesOf(',
    ['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR'],
    [store, canonicalValue, defaultValue, secretDraft, CLEAR]);
  const buildControl = loadFn(src, 'function buildControl(',
    ['el', 'bindPasswordReveal', 'switchControl'], [node, () => {}, () => {}]);
  const buildRow = loadFn(src, 'function buildRow(',
    ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
      'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR',
      'canonicalValue'],
    [node, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
      isChanged, defaultValue, () => {}, () => true, dangerOf, secretDraft, CLEAR,
      canonicalValue]);
  return {store, build(field) { return buildRow(field, false); }};
}

/** 换成另一个值并触发记录：与人在框里改一下等价；控件不在就是红，不抛 */
function choose(input, value) {
  if (!input) throw new Error('这一项没摆出可选的控件');
  input.value = value;
  for (const fn of (input.listeners.change || [])) fn();
}

/** 少了控件时把红记下来，别让整份夹具当场炸掉 */
function caught(what, run) {
  try {
    run();
  } catch (error) {
    failures.push(what + '：' + error.message);
  }
}

// ---------- 阳性对照：没给候选值的项照旧是文本框 ----------
{
  const p = page({'novabot.bilibili.account.qr-code-login-mode': 'tv'});
  const row = p.build(fieldOf());
  const boxes = collect(row, 'input');
  const picks = collect(row, 'select');
  eq({inputs: boxes.length, selects: picks.length, value: boxes[0] && boxes[0].value},
    {inputs: 1, selects: 0, value: 'tv'}, '阳性对照：普通字符串项仍是一只文本框');
}

// ---------- ① 两选一的项：摆的是两只候选，不是自由文本框 ----------
{
  const p = page({'novabot.bilibili.account.qr-code-login-mode': 'tv'});
  const row = p.build(fieldOf({choices: ['tv', 'web']}));
  const picks = collect(row, 'select');
  const options = picks.length ? collect(picks[0], 'option') : [];
  eq({selects: picks.length, inputs: collect(row, 'input').length,
    options: options.map(o => o.value), shown: picks[0] && picks[0].value},
    {selects: 1, inputs: 0, options: ['tv', 'web'], shown: 'tv'},
    '① 只认 tv／web 的那一项摆的是两项选择框，候选项按给定顺序');
}

// ---------- ② 选完存下的值与原来文本框存的一字不差 ----------
caught('② 选 web 存下的值与文本框里敲 web 存下的一字不差', () => {
  const free = page({'novabot.bilibili.account.qr-code-login-mode': 'tv'});
  const freeRow = free.build(fieldOf());
  choose(collect(freeRow, 'input')[0], 'web');

  const picked = page({'novabot.bilibili.account.qr-code-login-mode': 'tv'});
  const pickRow = picked.build(fieldOf({choices: ['tv', 'web']}));
  choose(collect(pickRow, 'select')[0], 'web');

  eq({fromTextBox: free.store.dirty['novabot.bilibili.account.qr-code-login-mode'],
    fromChoice: picked.store.dirty['novabot.bilibili.account.qr-code-login-mode'],
    same: free.store.dirty['novabot.bilibili.account.qr-code-login-mode']
      === picked.store.dirty['novabot.bilibili.account.qr-code-login-mode']},
    {fromTextBox: 'web', fromChoice: 'web', same: true},
    '② 选 web 存下的值与文本框里敲 web 存下的一字不差');

  choose(collect(pickRow, 'select')[0], 'tv');
  eq(picked.store.dirty['novabot.bilibili.account.qr-code-login-mode'] === undefined
    ? 'no draft' : picked.store.dirty['novabot.bilibili.account.qr-code-login-mode'],
  'no draft', '② 选回已存的那份就不再是改动，与文本框同一条规矩');
});

// ---------- ③ 当前值不在候选表里：照样摆出来，不悄悄换掉 ----------
{
  const p = page({'novabot.bilibili.account.qr-code-login-mode': 'beta'});
  const row = p.build(fieldOf({choices: ['tv', 'web']}));
  const picks = collect(row, 'select');
  const options = picks.length ? collect(picks[0], 'option') : [];
  eq({options: options.map(o => o.value), shown: picks[0] && picks[0].value},
    {options: ['tv', 'web', 'beta'], shown: 'beta'},
    '③ 配置文件里存着候选表以外的写法时照样留着，不替人改掉');
}

// 目标类型显示群与好友，保存的仍为原来的数字，切回原值不算改动。
caught('目标类型的显示名与存值分开', () => {
  const name = 'novabot.adapter.onebot.alert.type';
  const p = page({[name]: 0});
  const row = p.build(fieldOf({name, widget: 'integer', defaultValue: 0,
    choices: ['1', '0'], choiceLabels: {'1': '群', '0': '好友'}}));
  const select = collect(row, 'select')[0];
  eq(collect(row, 'option').map(o => [o.value, o.textContent]), [['1', '群'], ['0', '好友']],
    '目标类型显示名');
  choose(select, '1');
  eq(p.store.dirty[name], '1', '群仍保存为 1');
  choose(select, '0');
  eq(p.store.dirty[name], undefined, '选回好友不再有改动');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
