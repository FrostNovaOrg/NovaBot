/**
 * 设置页开关值跟随 store.values：改值后重画，checkbox 与旁注跟着变
 *
 * 设置页 DOM 只在建的那一刻读一次 valuesOf。本文件切 settings.js 的 valuesOf
 * 与 buildControl，按花括号配平截到块尾后真执行（DOM 用最小桩，不装 jsdom），
 * 钉开关那一格在 store.values 改完再画时 checked 与「已启用／已关闭」跟着走，
 * 以及未保存草稿优先于已保存值。
 *
 * 跑法：
 *   bash tools/settings-model-check.sh
 * 退码 0 即各格全对；任一格对不上打印差异并以 1 退出。
 */

import {existsSync, readFileSync, readdirSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..');
if (!existsSync(join(repo, 'pom.xml'))) {
  console.log('仓根没有 pom.xml：' + repo);
  process.exit(1);
}

const SKIP = new Set(['.git', 'node_modules', 'target', 'scratch', 'dist']);

function findUnique(suffix) {
  const found = [];
  const sweep = dir => {
    let entries;
    try {
      entries = readdirSync(dir, {withFileTypes: true});
    } catch {
      return;
    }
    for (const entry of entries) {
      if (SKIP.has(entry.name)) continue;
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (existsSync(join(path, '.git'))) continue;
        sweep(path);
      } else if (entry.isFile()) {
        const rel = path.slice(repo.length + 1).split('\\').join('/');
        if (rel === suffix || rel.endsWith('/' + suffix)) found.push(rel);
      }
    }
  };
  sweep(repo);
  if (found.length !== 1) {
    console.log('以 ' + suffix + ' 结尾的件应恰 1 份，实得 ' + found.length + ' 份'
      + (found.length ? '：' + found.join('、') : ''));
    process.exit(1);
  }
  return join(repo, found[0]);
}

const settingsSrc = readFileSync(findUnique('src/main/resources/config-ui/settings.js'), 'utf8');
const coreSrc = readFileSync(findUnique('src/main/resources/config-ui/core.js'), 'utf8');
const {canonicalValue, defaultValue} = await import(
  pathToFileURL(findUnique('src/main/resources/config-ui/settings-model.js')).href);

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
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
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

function makeNode(tag) {
  const children = [];
  const attributes = {};
  return {
    tagName: String(tag).toUpperCase(),
    className: '',
    type: '',
    checked: false,
    id: '',
    textContent: '',
    children,
    attributes,
    setAttribute(name, value) {
      attributes[name] = String(value);
    },
    append(...nodes) {
      for (const node of nodes) this.appendChild(node);
    },
    appendChild(node) {
      children.push(node);
      return node;
    },
  };
}

function loadFns(store) {
  const el = (tag, className) => {
    const node = makeNode(tag);
    if (className) node.className = className;
    return node;
  };
  const switchSrc = bracedFrom(coreSrc, 'function switchControl');
  const valuesSrc = bracedFrom(settingsSrc, 'function valuesOf');
  const buildSrc = bracedFrom(settingsSrc, 'function buildControl');
  if (!switchSrc || !valuesSrc || !buildSrc) throw new Error('no function');
  return new Function('store', 'el', 'canonicalValue', 'defaultValue',
    switchSrc + '\n' + valuesSrc + '\n' + buildSrc
    + '\nreturn {valuesOf, buildControl};')(store, el, canonicalValue, defaultValue);
}

const FIELD = {
  name: 'novabot.core.push.enabled',
  label: '全局推送开关',
  widget: 'boolean',
  defaultValue: true,
};

function paint(fns) {
  const cell = makeNode('div');
  const {current} = fns.valuesOf(FIELD);
  const input = fns.buildControl(FIELD, current, cell);
  const label = cell.children[0];
  const text = label && label.children.find(node => node.tagName === 'SPAN');
  return {
    checked: !!(input && input.checked),
    caption: text ? text.textContent : '',
  };
}

function snapshot(values, dirty) {
  const store = {values: Object.assign({}, values), dirty: Object.assign({}, dirty || {})};
  return paint(loadFns(store));
}

const on = snapshot({'novabot.core.push.enabled': 'true'});
const off = snapshot({'novabot.core.push.enabled': 'false'});
eq({checked: on.checked, caption: on.caption, nextChecked: off.checked, nextCaption: off.caption},
  {checked: true, caption: '已启用', nextChecked: false, nextCaption: '已关闭'},
  'true→false 重画后 checkbox 与旁注跟着关');

const fromOff = snapshot({'novabot.core.push.enabled': 'false'});
const toOn = snapshot({'novabot.core.push.enabled': 'true'});
eq({checked: fromOff.checked, caption: fromOff.caption, nextChecked: toOn.checked, nextCaption: toOn.caption},
  {checked: false, caption: '已关闭', nextChecked: true, nextCaption: '已启用'},
  'false→true 重画后 checkbox 与旁注跟着开');

const draft = snapshot(
  {'novabot.core.push.enabled': 'true'},
  {'novabot.core.push.enabled': 'false'},
);
eq({checked: draft.checked, caption: draft.caption},
  {checked: false, caption: '已关闭'},
  '草稿优先于已保存值');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
