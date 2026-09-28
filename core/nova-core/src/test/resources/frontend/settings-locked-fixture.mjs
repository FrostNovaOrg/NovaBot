/**
 * 设置页：配置文件里手写成界面改不了的项，只读显示程序读到的值并说明去配置文件里改
 *
 * 抓的用户故障：值写成块标量、带着换行，设置页照常摆一个单行框。框里装不下那几行，
 * 使用者在框里一改一存，多行的值就被压成一行；而界面上一个字也没说这一项不该在这里改。
 *
 * 各格：①读数标了不能改的项：不出输入框，只读摆出读到的值（换行照留），并附读数给的说明；
 * ②没标的项照常是输入框；③读数里压根没有那一栏（旧回包、别的夹具）照常是输入框。
 *
 * 由 SettingsLockedViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

const KEY = 'novabot.demo.signature';
const VALUE = 'first\nsecond\n';
const REASON = '这一项在配置文件里写成了多行，界面改不了';

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

/** 最小元素桩：记下属性、子节点与监听器 */
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

/** 按条件递归收节点 */
function collect(root, test, out) {
  const acc = out || [];
  if (root && test(root)) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, test, acc);
  return acc;
}

function textField(over) {
  return Object.assign({
    name: KEY, label: '签名', widget: 'string',
    description: '', defaultValue: null, effect: 'RESTART',
    sensitive: false, group: 'report', order: 0,
  }, over);
}

/**
 * 建一行：values 是配置文件里的值，locked 是回包里「界面不能改」那一栏（undefined 即回包里没有）
 */
function build(field, values, locked) {
  const store = {values, dirty: {}, legacy: {}, fontTables: {}};
  if (locked !== undefined) store.locked = locked;
  const valuesOf = loadFn(src, 'function valuesOf(',
    ['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR'],
    [store, canonicalValue, defaultValue, secretDraft, CLEAR]);
  const buildControl = loadFn(src, 'function buildControl(',
    ['el', 'bindPasswordReveal', 'switchControl'], [node, () => {}, () => {}]);
  const buildRow = loadFn(src, 'function buildRow(',
    ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
      'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR', 'canonicalValue'],
    [node, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
      isChanged, defaultValue, () => {}, () => true, dangerOf, secretDraft, CLEAR, canonicalValue]);
  return buildRow(field, false);
}

const inputs = row => collect(row, n => n.tag === 'input' || n.tag === 'textarea');

// ---------- ① 标了不能改 ----------
{
  let seen = 'missing';
  try {
    const row = build(textField(), {[KEY]: VALUE}, {[KEY]: REASON});
    const shown = collect(row, n => n.className.split(' ').includes('lockedval'));
    const notes = collect(row, n => n.textContent === REASON);
    seen = {inputs: inputs(row).length, shown: shown.map(n => n.textContent), notes: notes.length};
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {inputs: 0, shown: [VALUE], notes: 1}, '① 标了不能改：不出输入框，只读摆出读到的值，附说明');
}

// ---------- ② 没标的项 ----------
{
  let seen = 'missing';
  try {
    const row = build(textField({name: 'novabot.demo.other'}), {'novabot.demo.other': 'x'}, {[KEY]: REASON});
    seen = inputs(row).map(n => n.value);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, ['x'], '② 没标的项照常是输入框');
}

// ---------- ③ 回包里没有那一栏 ----------
{
  let seen = 'missing';
  try {
    seen = inputs(build(textField(), {[KEY]: 'x'}, undefined)).map(n => n.value);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, ['x'], '③ 回包里没有那一栏照常是输入框');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
