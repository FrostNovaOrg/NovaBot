/**
 * 设置页清除钮：清除态重画不丢，撤回一次即恢复，保存后键被删
 *
 * 抓的用户故障：点了「清除」、还没保存时页面一重画（切走再回设置页），框里又显示遮点、
 * 不再禁用，钮变回「清除」，「保存后清除」不见了——看着像没动，可一保存已存的值照样被删，
 * 要撤回得连点两下。重画时要认出「这一项挂着清除」，直接进清除态：显示「保存后清除」、
 * 点一次即撤回。删键的下场由后端那格（显式清除删键）钉着，这里量到请求体里送的是清除标记。
 *
 * 四格各盯一段：①重画后仍是清除态；②从重画出来的清除态撤回一次即恢复（看的是当场那一行）；
 * ③重画后保存：删之前界面一直写着「保存后清除」、送上去的是清除标记，保存后没值也没清除钮；
 * ④「保存后这一项就删掉了」那句话挂着清除时是看得见的字，不只在悬停提示里（触屏上看不到）。
 *
 * 由 SettingsClearViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {
  CLEAR, MASK, canonicalValue, dangerOf, defaultText, defaultValue, effectOf, isChanged, isDangerous,
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

function loadFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  return new Function(...params,
    body + '\nreturn ' + marker.slice(marker.indexOf(' ') + 1).replace(/\(.*/, '') + ';')(...args);
}

/**
 * 切出 async 函数的函数体，包成异步函数交回调用方自己跑
 *
 * save 的体里有 await，套进普通 Function 会当场炸「await is only valid in async
 * functions」——那句红说的是夹具自己，不是产品码。因此剥掉声明头，只把语句体放进
 * 异步函数里，形参槽照旧注入。
 */
function loadAsyncFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async () => {' + inner + '}')(...args);
}

/**
 * 最小元素桩：记下属性、子节点与监听器。设置页建行要写 dataset，
 * 清除钮的读数在 textContent／disabled／placeholder 上
 */
function node(tag, cls) {
  return {
    tag, className: cls || '', dataset: {},
    textContent: '', type: '', value: '', placeholder: '', title: '',
    autocomplete: '', name: '', disabled: false, checked: false,
    attrs: {}, kids: [], listeners: {}, style: {},
    classList: {toggle() {}, add() {}},
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

/** 按标签递归收控件 */
function collect(root, tag, out) {
  const acc = out || [];
  if (root && root.tag === tag) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, tag, acc);
  return acc;
}

/** 造一行机密项（已存了值，才有得清） */
function secretField(over) {
  return Object.assign({
    name: 'spring.mail.password', label: '发件授权码', widget: 'string',
    description: '发件那栏填的是授权码', defaultValue: null, effect: 'RESTART',
    sensitive: true, group: 'alert', order: 0,
  }, over);
}

/** 一份可重画的页面：同一份 store，反复建行就是切走再回来的重画 */
function page(over) {
  const store = {
    values: Object.assign({'spring.mail.password': MASK}, (over && over.values) || {}),
    dirty: Object.assign({}, (over && over.dirty) || {}),
    legacy: {},
  };
  const valuesOf = loadFn(src, 'function valuesOf(',
    ['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR'],
    [store, canonicalValue, defaultValue, secretDraft, CLEAR]);
  const buildControl = loadFn(src, 'function buildControl(',
    ['el', 'bindPasswordReveal', 'switchControl'], [node, () => {}, () => {}]);
  const buildRow = loadFn(src, 'function buildRow(',
    ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
      'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR'],
    [node, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
      isChanged, defaultValue, () => {}, () => true, dangerOf, secretDraft, CLEAR]);
  return {
    store,
    /** 重画：重建一行 */
    build(field) { return buildRow(field, false); },
  };
}

/** 当场那一行的读数：框、钮、改动账 */
function reading(store, row, field) {
  const input = collect(row, 'input')[0];
  const clear = collect(row, 'button')
    .find(b => b.textContent === '清除' || b.textContent === '撤回清除');
  return {
    input,
    clear,
    state: [
      clear ? clear.textContent : 'no button',
      input ? input.value : 'no input',
      input ? input.disabled : 'no input',
      input ? input.placeholder : 'no input',
      store.dirty[field.name] === undefined ? 'no draft' : store.dirty[field.name],
    ].join('|'),
  };
}

/** 点钮一下 */
function click(el) {
  for (const fn of (el && el.listeners.click) || []) fn();
}

// 阳性对照：桩真建得出一行，机密框与清除钮都在，没夹具空跑
{
  const p = page();
  const field = secretField();
  const seen = reading(p.store, p.build(field), field);
  eq(seen.input && seen.clear ? true : 'missing', true,
    '阳性对照：建得出一行，机密框与清除钮都在');
}

// ---------- ① 点清除→重画→仍是清除态 ----------
// 病：重画把清除态丢了，框里又显示遮点、钮变回「清除」，「保存后清除」不见了
let clearThenRepaint = 'missing';
try {
  const field = secretField();
  const p = page();
  click(reading(p.store, p.build(field), field).clear);
  clearThenRepaint = reading(p.store, p.build(field), field).state;
} catch (e) {
  clearThenRepaint = 'error:' + e.message;
}
eq(clearThenRepaint, ['撤回清除', '', true, '保存后清除', CLEAR].join('|'),
  '① 点清除→重画→仍是清除态：框空且禁用、写着「保存后清除」、钮是「撤回清除」，清除记账不丢');

// ---------- ② 撤回一次即恢复 ----------
// 病：重画后要撤回得连点两下——第一下反而又进了一次清除态
let undoOnce = 'missing';
try {
  const field = secretField();
  const p = page();
  click(reading(p.store, p.build(field), field).clear);
  const painted = p.build(field);
  click(reading(p.store, painted, field).clear);
  undoOnce = reading(p.store, painted, field).state;
} catch (e) {
  undoOnce = 'error:' + e.message;
}
eq(undoOnce, ['清除', MASK, false, '', 'no draft'].join('|'),
  '② 重画后撤回一次即恢复：框回到遮点、钮是「清除」，清除记账撤干净');

// ---------- ③ 保存后键被删 ----------
// 病：看着没动却一保存就把已存的值删了。删之前界面得一直写着「保存后清除」，
// 送上去的是清除标记（后端据此删键），保存后这一项没值、也不再有清除钮
let saveDeletes = 'missing';
try {
  const field = secretField();
  const p = page();
  const store = p.store;
  click(reading(store, p.build(field), field).clear);
  const live = reading(store, p.build(field), field);
  const announced = live.input.placeholder === '保存后清除' && live.input.disabled === true;

  let sent = null;
  const api = async (path, opt) => {
    sent = {path, body: JSON.parse(opt.body)};
    for (const [key, value] of Object.entries(sent.body)) {
      if (value === CLEAR) delete store.values[key];
    }
    return {success: true, changed: 1};
  };
  const save = loadAsyncFn(src, 'function save(',
    ['saveTarget', '$', 'api', 'showIssues', 'say', 'store', 'load', 'markDirty'],
    [() => 'values', () => node('div'), api, () => {}, () => {}, store,
      async () => { store.dirty = {}; }, () => {}]);
  await save();
  const gone = reading(store, p.build(field), field);
  saveDeletes = announced && sent && sent.path === '/values' && sent.body[field.name] === CLEAR
    && gone.clear === undefined && gone.input.value === ''
    ? true
    : JSON.stringify({announced, sent, after: gone.state, button: gone.clear && gone.clear.textContent});
} catch (e) {
  saveDeletes = 'error:' + e.message;
}
eq(saveDeletes, true,
  '③ 点清除→重画→保存后键被删：删之前界面写着「保存后清除」，送上去的是清除标记，保存后没值也没清除钮');

// ---------- ④ 那句话看得见 ----------
// 病：「保存后这一项就删掉了，要再用得重新填」只挂在悬停提示里，触屏上点了清除
// 看不到，不知道保存后会删。挂着清除时这句要作为字摆在这一项里，撤回后收起
const CLEAR_NOTE = '保存后这一项就删掉了，要再用得重新填';

/** 带真 classList 的元素桩：看得见＝有字且没挂 hide */
function liveNode(tag, cls) {
  const n = node(tag, cls);
  const set = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  n.classList = {
    toggle(name, force) {
      const on = force === undefined ? !set.has(name) : !!force;
      if (on) set.add(name); else set.delete(name);
    },
    add(...names) { for (const name of names) set.add(name); },
    contains(name) { return set.has(name); },
  };
  return n;
}

/** 行里看得见的字（不含悬停提示 title） */
function visible(root, out) {
  const acc = out || [];
  if (!root || root.classList.contains && root.classList.contains('hide')) return acc;
  if (root.textContent && !(root.kids && root.kids.length)) acc.push(root.textContent);
  for (const child of root.kids || []) visible(child, acc);
  return acc;
}

let noteShown = 'missing';
try {
  const field = secretField();
  const store = {values: {'spring.mail.password': MASK}, dirty: {}, legacy: {}};
  const valuesOf = loadFn(src, 'function valuesOf(',
    ['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR'],
    [store, canonicalValue, defaultValue, secretDraft, CLEAR]);
  const buildControl = loadFn(src, 'function buildControl(',
    ['el', 'bindPasswordReveal', 'switchControl'], [liveNode, () => {}, () => {}]);
  const buildRow = loadFn(src, 'function buildRow(',
    ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
      'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR'],
    [liveNode, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
      isChanged, defaultValue, () => {}, () => true, dangerOf, secretDraft, CLEAR]);
  const says = row => visible(row).includes(CLEAR_NOTE) ? 'note' : 'no note';
  const row = buildRow(field, false);
  const before = says(row);
  const clear = reading(store, row, field).clear;
  click(clear);
  const clearing = says(row);
  const repainted = says(buildRow(field, false));
  click(clear);
  noteShown = [before, clearing, repainted, says(row)].join('|');
} catch (e) {
  noteShown = 'error:' + e.message;
}
eq(noteShown, ['no note', 'note', 'note', 'no note'].join('|'),
  '④ 点清除后那句话作为看得见的字摆在这一项里，重画仍在，撤回即收起');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
