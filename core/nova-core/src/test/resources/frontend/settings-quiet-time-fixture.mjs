/**
 * 静音时段两格：时间框，只交出 HH:mm 或空串；配置文件里认不出的旧值要说一声
 *
 * 抓的用户故障：①手敲「HH:mm」敲成 8:00、8点、0800，存进去服务端认不出（PushGate 用
 * LocalTime.parse 收，格式错就忽略、只打一行 WARN），静音整段不生效，界面上看不出任何异常；
 * ②换成时间框以后，配置文件里早就存着的 8:00 在框里显示成空，使用者以为本来就没设。
 *
 * 认「这一项是时刻」用的是服务端给的 format（TIME_OF_DAY），界面上没有写着键名的表。
 *
 * 各格：①服务端标了时刻的项出时间框（type=time、step=60），已存的 23:00 照显示；
 * ②框里改了：交出去的是 HH:mm，浏览器带了秒也收成 HH:mm；③旁边有「清空」，点了交空串；
 * ④存着 8:00：框里空着，行里说一句认不出、现在不生效，且不凭空记一笔改动；
 * ⑤存着空串：不说那一句；
 * ⑥小数秒的位数与服务端一样（LocalTime.parse 至多 9 位，小数点后不带数字也收）：存着
 * 23:59:59.1234567890（10 位）时框里空着、行里说认不出，因为服务端也认不出、静音整段不生效；
 * 9 位的、只带小数点的照常显示 23:59。
 * ⑦时刻两端带全角空格、不换行空格或 U+2003 时不能显示有效，因为服务端认不出；
 * 普通空格和编码不大于 U+0020 的字符照服务端去掉，仍显示有效时刻。
 *
 * 由 SettingsQuietTimeTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import * as model from '../../../main/resources/config-ui/settings-model.js';

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

function loadFn(text, marker, names, values) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('切不出 ' + marker);
  const name = marker.slice(marker.indexOf(' ') + 1).replace(/\(.*/, '');
  return new Function(...names, body + '\nreturn ' + name + ';')(...values);
}

/** 元素桩：记下类、属性、子节点与监听器 */
function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  return {
    tag, dataset: {}, textContent: '', type: '', value: '', placeholder: '', title: '', step: '',
    autocomplete: '', disabled: false, checked: false, attrs: {}, kids: [], listeners: {}, style: {},
    get className() { return [...classes].join(' '); },
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      contains: c => classes.has(c),
    },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    fire(type) { for (const fn of this.listeners[type] || []) fn({type}); },
  };
}

function collect(root, test, out) {
  const acc = out || [];
  if (root && test(root)) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, test, acc);
  return acc;
}

const KEY = 'novabot.core.push.quiet-start';
function quietField() {
  return {name: KEY, label: '静音时段 · 开始', widget: 'string', format: 'TIME_OF_DAY',
    description: '静音开始时间，格式 HH:mm', defaultValue: '', effect: 'IMMEDIATE', sensitive: false,
    level: 'COMMON', group: 'push', order: 0};
}

/** 建一行：saved 是配置文件里的值 */
function build(saved) {
  const store = {values: {[KEY]: saved}, dirty: {}, legacy: {}, fontTables: {}, locked: {}};
  const common = {el: node, store, bindPasswordReveal: () => {}, switchControl: () => ({}),
    markDirty: () => {}, ask: async () => true, buildHelp: () => node('button')};
  const pick = names => [names, names.map(n => (n in common ? common[n] : model[n]))];
  const [vn, vv] = pick(['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR']);
  const valuesOf = loadFn(src, 'function valuesOf(', vn, vv);
  const ctrlNames = ['el', 'bindPasswordReveal', 'switchControl', 'timeOfDay'];
  const buildControl = loadFn(src, 'function buildControl(', ...pick(ctrlNames));
  const rowNames = ['el', 'isDangerous', 'store', 'defaultText', 'effectOf', 'isChanged', 'defaultValue',
    'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR', 'canonicalValue', 'timeOfDay', 'timeOfDayValue',
    'buildHelp'];
  const [rn, rv] = pick(rowNames);
  const buildRow = loadFn(src, 'function buildRow(', [...rn, 'valuesOf', 'buildControl'],
    [...rv, valuesOf, buildControl]);
  return {row: buildRow(quietField(), false), store};
}

const inputOf = row => collect(row, n => n.tag === 'input')[0] || {};
const clearOf = row => collect(row, n => n.tag === 'button' && n.textContent === '清空')[0];
const noteOf = row => collect(row, n => n.classList.contains('timenote'))[0];
const noteShown = row => {
  const note = noteOf(row);
  return !!note && !note.classList.contains('hide') && note.textContent !== '';
};

function guard(what, fn) {
  try {
    fn();
  } catch (err) {
    checks++;
    failures.push(what + ' 抛了：' + err.message);
  }
}

// ---------- ① 时间框 ----------
guard('①', () => {
  const {row} = build('23:00');
  const input = inputOf(row);
  eq({type: input.type, step: String(input.step), value: input.value}, {type: 'time', step: '60', value: '23:00'},
    '① 标了时刻的项出时间框，已存的 23:00 照显示');
});

// ---------- ② 交出去的是 HH:mm ----------
guard('②', () => {
  const {row, store} = build('23:00');
  const input = inputOf(row);
  input.value = '07:30';
  input.fire('input');
  eq(store.dirty[KEY], '07:30', '② 改成 07:30：记下 07:30');
  input.value = '07:30:15';
  input.fire('input');
  eq(store.dirty[KEY], '07:30', '② 浏览器带了秒：收成 HH:mm 再记');
});

// ---------- ③ 清空 ----------
guard('③', () => {
  const {row, store} = build('23:00');
  const clear = clearOf(row);
  eq(!!clear, true, '③ 时间框旁有「清空」');
  if (clear) {
    clear.fire('click');
    eq([inputOf(row).value, store.dirty[KEY]], ['', ''], '③ 点了清空：框空了，交出去的是空串（停用静音）');
  }
});

// ---------- ④ 认不出的旧值 ----------
guard('④', () => {
  const {row, store} = build('8:00');
  eq(inputOf(row).value, '', '④ 存着 8:00：时间框显示不出，是空的');
  eq(noteShown(row), true, '④ 行里说一句配置文件里的值认不出');
  const note = noteOf(row);
  eq(!!note && note.textContent.includes('8:00') && note.textContent.includes('不生效'), true,
    '④ 那一句带出原值，并说清现在不生效');
  eq(store.dirty[KEY], undefined, '④ 只是画出来，不凭空记一笔改动');
});

// ---------- ⑤ 空串不说 ----------
guard('⑤', () => {
  const {row} = build('');
  eq(noteShown(row), false, '⑤ 存着空串：不说认不出');
});

// ---------- ⑥ 小数秒至多 9 位，与服务端一样 ----------
guard('⑥', () => {
  const {row} = build('23:59:59.1234567890');
  eq({value: inputOf(row).value, note: noteShown(row)}, {value: '', note: true},
    '⑥ 存着 23:59:59.1234567890（小数 10 位，服务端认不出）：框里空着，行里说认不出');
  const nine = build('23:59:59.123456789').row;
  eq({value: inputOf(nine).value, note: noteShown(nine)}, {value: '23:59', note: false},
    '⑥ 存着 23:59:59.123456789（9 位，服务端照收）：照常显示 23:59，不说认不出');
  const dot = build('23:59:59.').row;
  eq({value: inputOf(dot).value, note: noteShown(dot)}, {value: '23:59', note: false},
    '⑥ 存着 23:59:59.（小数点后一位不带，服务端照收）：照常显示 23:59，不说认不出');
});

// ---------- ⑦ 首尾空白与服务端一样 ----------
guard('⑦', () => {
  for (const saved of ['\u300008:00\u3000', '\u00a008:00', '\u200308:00\u2003']) {
    const {row} = build(saved);
    eq({value: inputOf(row).value, note: noteShown(row)}, {value: '', note: true},
      '⑦ 存着 ' + JSON.stringify(saved) + '（服务端不去掉这些空白）：框里空着，行里说认不出');
  }
  for (const saved of [' 08:00 ', '\u000008:00\u0020', '\t08:00\r\n']) {
    const {row} = build(saved);
    eq({value: inputOf(row).value, note: noteShown(row)}, {value: '08:00', note: false},
      '⑦ 存着 ' + JSON.stringify(saved) + '（服务端去掉首尾编码不大于 U+0020 的字符）：照常显示 08:00');
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
