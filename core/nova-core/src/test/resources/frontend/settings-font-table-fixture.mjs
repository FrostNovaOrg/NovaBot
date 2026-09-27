/**
 * 设置页成图字体表：旧默认表标明已按未设处理，空表时写出在用的默认表
 *
 * 抓的用户故障：老实例的配置文件里存着旧版自动写进去的默认字体表，启动时已按未设处理，
 * 可设置页照原样显示这张表、一个字的说明都没有。使用者改其中一项再存，它就成了使用者
 * 自己的表，表情空白又回来了。框下要说清「这是旧默认表、已按未设处理，点恢复默认清空」，
 * 存的是旧表或空表时再写出现在用的是哪张默认表；使用者自己的表不加字。
 *
 * 各格：①旧表出说明句与默认表那一行；②空表只出默认表那一行；③使用者的表两行都不出；
 * ④别的列表项不出；⑤旧表点「恢复默认」后框为空、记进改动（保存即写空表）；
 * ⑥⑦两行说明跟框里的草稿走，不等保存重载：点了恢复默认不再说是旧表，改成别的表不出字。
 *
 * 由 SettingsFontTableViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

const FONTS = 'novabot.core.paint.fonts';
/** 回包里的本系统默认表，取 macOS 那张的样子；夹具只看它被原样列出来 */
const DEFAULTS = ['内置', 'PingFang SC', 'Apple Color Emoji', 'SansSerif'];
const PAST_NOTE = '这是旧版自动写进配置的默认字体表，启动时已按未设处理。'
  + '点「恢复默认」清空它，以后跟着程序自带的默认表走。';
const DEFAULTS_NOTE = '现在用的是本系统的默认表：' + DEFAULTS.join('、');

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

/** 列表项的字段表那一项；字体表的默认值在元数据里没有，回的是 null */
function listField(over) {
  return Object.assign({
    name: FONTS, label: '成图字体', widget: 'list',
    description: '绘图字体列表，可填字体名称或字体文件路径。', defaultValue: null, effect: 'RESTART',
    sensitive: false, group: 'report', order: 0,
  }, over);
}

/**
 * 建一行：values 是配置文件里的值，fontTables 是回包里字体表那一栏
 * @return {{row, store}} 那一行与它的 store
 */
function build(field, values, fontTables) {
  const store = {values, dirty: {}, legacy: {}, fontTables};
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
  return {row: buildRow(field, false), store};
}

/** 行里所有节点的字，按出现顺序；只收有字的叶子 */
function texts(row) {
  return collect(row, n => n.textContent && !(n.kids && n.kids.length)).map(n => n.textContent);
}

/** 框下那两句：只收两句原文之一，别的字不算 */
function notes(row) {
  return texts(row).filter(t => t === PAST_NOTE || t.startsWith('现在用的是本系统的默认表'));
}

const OLD_MAC = ['内置', 'PingFang SC', 'Apple Color Emoji', 'SansSerif'].join('\n');
const tables = pastDefault => ({[FONTS]: {pastDefault, defaults: DEFAULTS}});

// 阳性对照：桩真建得出一行，字体表那一格是多行文本框、框里是存着的那张表
{
  let seen = 'missing';
  try {
    const {row} = build(listField(), {[FONTS]: OLD_MAC}, tables(true));
    const box = collect(row, n => n.tag === 'textarea')[0];
    seen = box ? box.value : 'no textarea';
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, OLD_MAC, '阳性对照：建得出一行，框里是存着的那张表');
}

// ---------- ① 旧默认表 ----------
// 病：原样显示旧表、一个字都不说，使用者改一项再存就成了自己的表
{
  let seen = 'missing';
  try {
    seen = notes(build(listField(), {[FONTS]: OLD_MAC}, tables(true)).row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [PAST_NOTE, DEFAULTS_NOTE], '① 旧默认表：框下说明已按未设处理，再写出现在用的默认表');
}

// ---------- ② 空表 ----------
// 病：空着的框看不出程序到底在用哪些字体
{
  let seen = 'missing';
  try {
    seen = notes(build(listField(), {[FONTS]: ''}, tables(false)).row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [DEFAULTS_NOTE], '② 空表：只写出现在用的默认表，不说「旧版写下的」');
}

// ---------- ②b 文件里没写这一项 ----------
{
  let seen = 'missing';
  try {
    seen = notes(build(listField(), {}, tables(false)).row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [DEFAULTS_NOTE], '②b 文件里没写这一项：同空表，写出现在用的默认表');
}

// ---------- ③ 使用者的表 ----------
// 病：使用者自己配的表也被说成旧表，或被多挂一行默认表
{
  let seen = 'missing';
  try {
    seen = notes(build(listField(), {[FONTS]: '我的字体\n内置'}, tables(false)).row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [], '③ 使用者的表：两行都不出');
}

// ---------- ④ 别的列表项 ----------
{
  let seen = 'missing';
  try {
    const other = listField({name: 'novabot.core.admins', label: '超级管理员'});
    seen = notes(build(other, {'novabot.core.admins': ''}, tables(true)).row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [], '④ 别的列表项：不挂字体表的字');
}

// ---------- ⑤ 恢复默认即清空 ----------
// 病：「恢复默认」填回的不是空，旧表清不掉
{
  let seen = 'missing';
  try {
    const {row, store} = build(listField(), {[FONTS]: OLD_MAC}, tables(true));
    const reset = collect(row, n => n.tag === 'button' && n.textContent === '恢复默认')[0];
    for (const fn of (reset && reset.listeners.click) || []) fn();
    const box = collect(row, n => n.tag === 'textarea')[0];
    seen = [reset ? 'reset' : 'no reset', box.value, store.dirty[FONTS] === undefined ? 'no draft' : store.dirty[FONTS]];
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, ['reset', '', ''], '⑤ 旧表点「恢复默认」：框为空，改动记成空表');
}

// ---------- ⑥ 说明跟框里的草稿走 ----------
// 病：点了「恢复默认」还没保存，框已经空了，框下还说「这是旧版自动写进配置的默认字体表」，
// 保存重载后才变；改成别的表、或把自己的表清空，框下的字也还是按已存的值说
{
  let seen = 'missing';
  try {
    const {row} = build(listField(), {[FONTS]: OLD_MAC}, tables(true));
    const reset = collect(row, n => n.tag === 'button' && n.textContent === '恢复默认')[0];
    for (const fn of (reset && reset.listeners.click) || []) fn();
    seen = notes(row);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [DEFAULTS_NOTE], '⑥ 旧表点「恢复默认」未保存：不再说是旧表，只写出现在用的默认表');
}

/** 往框里敲字：写值再拨一次 input */
function type(row, text) {
  const box = collect(row, n => n.tag === 'textarea')[0];
  box.value = text;
  for (const fn of box.listeners.input || []) fn();
}

{
  let seen = 'missing';
  try {
    const old = build(listField(), {[FONTS]: OLD_MAC}, tables(true)).row;
    type(old, '我的字体\n内置');
    const edited = notes(old);
    type(old, OLD_MAC);
    const back = notes(old);
    const mine = build(listField(), {[FONTS]: '我的字体\n内置'}, tables(false)).row;
    type(mine, '');
    seen = {edited, back, emptied: notes(mine)};
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {edited: [], back: [PAST_NOTE, DEFAULTS_NOTE], emptied: [DEFAULTS_NOTE]},
    '⑦ 旧表改成别的表不出字、改回旧表两行又出；自己的表清空了写出现在用的默认表');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
