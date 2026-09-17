/**
 * 设置页开关值跟随 store.values：改值后重画，checkbox 与旁注跟着变
 *
 * 设置页 DOM 只在建的那一刻读一次 valuesOf。本文件切 settings.js 的 valuesOf
 * 与 buildControl，按花括号配平截到块尾后真执行（DOM 用最小桩，不装 jsdom），
 * 钉开关那一格在 store.values 改完再画时 checked 与「已启用／已关闭」跟着走，
 * 以及未保存草稿优先于已保存值。
 *
 * 取件按唯一后缀找，不写模块目录名：不跟软链、子目录有 .git 不下去、读不了的目录判红；
 * 这几条由末尾「找件自证」几格在临时目录里现造现量。
 *
 * 跑法：
 *   bash tools/settings-model-check.sh
 * 退码 0 即各格全对；任一格对不上打印差异并以 1 退出。
 */

import {chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..');
if (!existsSync(join(repo, 'pom.xml'))) {
  console.log('仓根没有 pom.xml：' + repo);
  process.exit(1);
}

const SKIP = new Set(['.git', 'node_modules', 'target', 'scratch', 'dist']);

/**
 * root 下以 suffix 结尾的普通文件，连同读不了的目录一并交回
 *
 * Dirent 不跟软链：指向目录或文件的软链既不算目录也不算文件，不进也不收。
 * 子目录里有 .git（件或目录）就不下去，入口不判。读不了的目录记名交回、不静默跳过：
 * 漏读一个目录，里面那份重复件就数不到，「恰 1 份」也就作不得准。
 */
function sweepBySuffix(root, suffix) {
  const found = [];
  const unreadable = [];
  const relOf = path => path.slice(root.length + 1).split('\\').join('/');
  const sweep = dir => {
    let entries;
    try {
      entries = readdirSync(dir, {withFileTypes: true});
    } catch (error) {
      unreadable.push({rel: relOf(dir) || '.', code: error.code || String(error)});
      return;
    }
    for (const entry of entries) {
      if (SKIP.has(entry.name)) continue;
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (existsSync(join(path, '.git'))) continue;
        sweep(path);
      } else if (entry.isFile()) {
        const rel = relOf(path);
        if (rel === suffix || rel.endsWith('/' + suffix)) found.push(rel);
      }
    }
  };
  sweep(root);
  return {found, unreadable};
}

/**
 * 以 suffix 结尾的件恰 1 份、且没有读不了的目录时交回 {rel}，否则交回 {error}
 */
function uniqueBySuffix(root, suffix) {
  const {found, unreadable} = sweepBySuffix(root, suffix);
  if (unreadable.length) {
    return {error: '找以 ' + suffix + ' 结尾的件时有 ' + unreadable.length + ' 个目录读不了，份数作不得准：'
      + unreadable.map(item => item.rel + '（' + item.code + '）').join('、')};
  }
  if (found.length !== 1) {
    return {error: '以 ' + suffix + ' 结尾的件应恰 1 份，实得 ' + found.length + ' 份'
      + (found.length ? '：' + found.join('、') : '')};
  }
  return {rel: found[0]};
}

function findUnique(suffix) {
  const {rel, error} = uniqueBySuffix(repo, suffix);
  if (error) {
    console.log(error);
    process.exit(1);
  }
  return join(repo, rel);
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

/**
 * 找件自证：临时目录里现造一棵树，过同一个 sweepBySuffix／uniqueBySuffix
 *
 * 目录软链、文件软链、带 .git 件或 .git 目录的子目录、target 里的同名件都不该收；
 * 权限 000 的目录必须记名交回，且此时不认那唯一一份。临时目录无论上面是否抛错都要删，删净单独算一格。
 */
function sweepSelfProof() {
  const probe = 'src/main/resources/config-ui/settings.js';
  let tree = '';
  let outside = '';
  let locked = '';
  try {
    tree = mkdtempSync(join(tmpdir(), 'novabot-settings-values-tree-'));
    outside = mkdtempSync(join(tmpdir(), 'novabot-settings-values-outside-'));
    const put = (base, rel) => {
      mkdirSync(dirname(join(base, rel)), {recursive: true});
      writeFileSync(join(base, rel), '// ' + rel + '\n');
    };
    put(outside, probe);
    put(tree, 'a/' + probe);
    put(tree, 'nested-file/' + probe);
    writeFileSync(join(tree, 'nested-file/.git'), '');
    put(tree, 'nested-dir/' + probe);
    mkdirSync(join(tree, 'nested-dir/.git'));
    put(tree, 'target/' + probe);
    mkdirSync(dirname(join(tree, 'b', probe)), {recursive: true});
    symlinkSync(join(outside, probe), join(tree, 'b', probe));
    symlinkSync(outside, join(tree, 'link'));
    put(tree, 'locked/' + probe);
    locked = join(tree, 'locked');
    chmodSync(locked, 0o000);
    const {found, unreadable} = sweepBySuffix(tree, probe);
    eq(found.sort(), ['a/' + probe], '找件自证：不跟软链、不进带 .git 的子目录、跳过 target');
    eq(unreadable.map(item => item.rel), ['locked'], '找件自证：读不了的目录记名交回');
    const picked = uniqueBySuffix(tree, probe);
    eq({rel: picked.rel || '', named: String(picked.error || '').includes('locked')},
      {rel: '', named: true}, '找件自证：有目录读不了时不认那唯一一份');
  } catch (error) {
    checks++;
    failures.push('找件自证没造完或没跑完：' + (error && error.message ? error.message : error));
  } finally {
    try {
      if (locked) chmodSync(locked, 0o700);
    } catch {
      // 权限改不回，下面删不净，由删净那一格报
    }
    for (const dir of [tree, outside]) {
      try {
        if (dir) rmSync(dir, {recursive: true, force: true});
      } catch {
        // 删不掉，由删净那一格报
      }
    }
  }
  eq([tree, outside].filter(dir => dir && existsSync(dir)), [], '找件自证：临时目录删净');
}

sweepSelfProof();

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
