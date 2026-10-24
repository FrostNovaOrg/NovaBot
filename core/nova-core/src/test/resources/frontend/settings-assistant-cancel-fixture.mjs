/** 取消启用智能助手后，开关与待保存的值都保持关闭，也不提交确认。 */
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

const store = {values: {'novabot.assistant.enabled': 'false'}, dirty: {}, legacy: {}};
let asked = 0;
const requests = [];
const ask = async () => { asked++; return false; };
const api = async (path, options) => {
  requests.push({path, method: options?.method || 'GET'});
  return {available: false, acknowledgementVersion: 'current', acknowledgementText: '说明正文'};
};
// 只有实际的开关处理接入确认时，才会加载这段流程。
const confirmAssistantEnable = async () => {
  const source = readFileSync(join(ui, 'settings-assistant.js'), 'utf8')
    .replace(/^import .*?;\s*$/gm, '').replace(/export /g, '');
  return new Function('ask', 'api', '$', 'el', 'store', source + '\nreturn confirmAssistantEnable;')
    (ask, api, () => null, node, store)();
};
const valuesOf = loadFn(src, 'function valuesOf(',
  ['store', 'canonicalValue', 'defaultValue', 'secretDraft'],
  [store, canonicalValue, defaultValue, secretDraft]);
const switchControl = (_id, checked) => {
  const label = node('label');
  const input = node('input');
  input.checked = checked;
  input.syncLabel = () => {};
  label.appendChild(input);
  return {label, input};
};
const buildControl = loadFn(src, 'function buildControl(',
  ['el', 'switchControl'], [node, switchControl]);
const buildRow = loadFn(src, 'function buildRow(',
  ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
    'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR',
    'canonicalValue', 'confirmAssistantEnable', 'say'],
  [node, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
    isChanged, defaultValue, () => {}, ask, dangerOf, secretDraft, CLEAR,
    canonicalValue, confirmAssistantEnable, () => {}]);
const row = buildRow({name: 'novabot.assistant.enabled', label: '启用智能助手',
  widget: 'boolean', defaultValue: 'false', effect: 'RESTART'}, false);
const input = collect(row, 'input')[0];
input.checked = true;
for (const change of input.listeners.change) await change();
eq(input.checked, false, '取消开启后开关退回关闭');
eq(store.dirty['novabot.assistant.enabled'], undefined, '取消后没有开启草稿');
eq(asked, 1, '开启时询问确认');
eq(requests.filter(r => r.path.endsWith('/acknowledge')).length, 0, '取消后不提交确认');
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
