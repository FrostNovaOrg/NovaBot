/**
 * Webhook 预设来回切：没配的提交方式写回屏上那一位，不算改动
 *
 * 文件里没写提交方式时，屏上显示 POST，预设能认成「Server 酱」。
 * 记账若仍按空串当基线，切到 Bark 再切回来，三栏都回到原值，
 * 底部却仍挂着这一处改动，保存还会多写出一行提交方式。
 * 切到 Bark 停住时，变了的栏照旧记改动。文件里已经写过的，仍按文件里那一位比。
 *
 * 切段按花括号配平截到块尾后真执行。由 SettingsAlertWebhookPresetTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {CLEAR, MASK} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');

const METHOD = 'novabot.core.alert.webhook-method';
const TITLE = 'novabot.core.alert.webhook-title-field';
const CONTENT = 'novabot.core.alert.webhook-content-field';

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
 * 字符串与注释里的花括号不算层。
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

function constArrow(text, name) {
  return bracedFrom(text, 'const ' + name + ' = ');
}

const presets = bracedFrom(src, 'const WEBHOOK_PRESETS = ');
const shown = bracedFrom(src, 'const SHOWN_WHEN_UNSET = ');
const valueOfSrc = bracedFrom(src, 'function valueOf');
const setValueSrc = bracedFrom(src, 'function setValue');
const applySrc = constArrow(src, 'applyWebhook');
const matchAt = src.indexOf('preset.value = Object.keys(WEBHOOK_PRESETS).find');
const matchEndMark = '|| CUSTOM;';
const matchEnd = matchAt < 0 ? -1 : src.indexOf(matchEndMark, matchAt);
const matchStmt = matchAt < 0 || matchEnd < 0 ? '' : src.slice(matchAt, matchEnd + matchEndMark.length);

eq(presets.includes('Server 酱') && valueOfSrc.includes('SHOWN_WHEN_UNSET')
  && setValueSrc.includes('store.dirty') && applySrc.includes('setValue') && matchStmt.includes('valueOf'),
  true, '切得到预设、取值、记账、套用预设与初值认预设');

function load(store) {
  return new Function('store', 'markDirty', 'MASK', 'CLEAR',
    presets + '\n' + shown + "\nconst CUSTOM = '自定义';\n"
    + valueOfSrc + '\n' + setValueSrc + '\n'
    + 'return function bind(preset, hookCustom, method, titleField, contentField) {\n'
    + applySrc + '\n'
    + 'function matchPreset() {\n' + matchStmt + '\nreturn preset.value;\n}\n'
    + 'return {applyWebhook, matchPreset};\n};\n')(store, () => {}, MASK, CLEAR);
}

function fields() {
  return {
    preset: {value: ''},
    hookCustom: {classList: {toggle() {}}},
    method: {value: ''},
    titleField: {value: ''},
    contentField: {value: ''},
  };
}

const store = {
  values: {[TITLE]: 'title', [CONTENT]: 'desp'},
  dirty: {},
};
const box = fields();
const bound = load(store)(box.preset, box.hookCustom, box.method, box.titleField, box.contentField);

eq(bound.matchPreset(), 'Server 酱', '没配提交方式、标题与内容凑成 Server 酱时，预设显示 Server 酱');

box.preset.value = 'Bark';
bound.applyWebhook();
box.preset.value = 'Server 酱';
bound.applyWebhook();
eq(Object.keys(store.dirty).sort(), [], '切到 Bark 再切回 Server 酱，改动为 0');
eq(box.method.value, 'POST', '切回后提交方式仍是 POST');
eq(box.titleField.value, 'title', '切回后标题字段名回到 title');
eq(box.contentField.value, 'desp', '切回后内容字段名回到 desp');

box.preset.value = 'Bark';
bound.applyWebhook();
eq(store.dirty[METHOD], 'GET', '切到 Bark 停住，提交方式记成改动');
eq(store.dirty[CONTENT], 'body', '切到 Bark 停住，内容字段名记成改动');
eq(store.dirty[TITLE], undefined, '标题字段名没变，不记改动');

store.values[METHOD] = 'POST';
store.dirty = {};
eq(bound.matchPreset(), 'Server 酱', '文件里已经写了 POST 时仍认 Server 酱');
box.preset.value = 'Bark';
bound.applyWebhook();
box.preset.value = 'Server 酱';
bound.applyWebhook();
eq(Object.keys(store.dirty).sort(), [], '提交方式文件里已经是 POST，来回切回也不算改动');

store.values[METHOD] = 'GET';
store.values[TITLE] = 'title';
store.values[CONTENT] = 'body';
store.dirty = {};
eq(bound.matchPreset(), 'Bark', '三栏是 Bark 时预设显示 Bark');
box.preset.value = 'Server 酱';
bound.applyWebhook();
eq(store.dirty[METHOD], 'POST', '已配的提交方式从 GET 改成 POST，仍算改动');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
