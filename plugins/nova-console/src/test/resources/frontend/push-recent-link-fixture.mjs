/**
 * 推送页「这个通道最近推送」：日志链接带全通道串，空表句写明本次启动以来
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 PushRecentLinkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join, relative} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

function repoRoot() {
  let current = dirname(fileURLToPath(import.meta.url));
  while (current !== dirname(current)) {
    if (existsSync(join(current, 'build.sh')) && existsSync(join(current, 'pom.xml'))) {
      return current;
    }
    current = dirname(current);
  }
  throw new Error('未能定位仓库根目录');
}

const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', 'scratch', 'dist']);
const LOG_MODEL_SUFFIX = '/src/main/resources/config-ui/log-model.js';

function nestedHasGit(dir) {
  try {
    return readdirSync(dir).includes('.git');
  } catch {
    return false;
  }
}

function collectBySuffix(dir, root, suffix, acc) {
  let entries;
  try {
    entries = readdirSync(dir, {withFileTypes: true});
  } catch {
    return;
  }
  for (const entry of entries) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (nestedHasGit(path)) continue;
      collectBySuffix(path, root, suffix, acc);
      continue;
    }
    if (!entry.isFile()) continue;
    const rel = relative(root, path).split('\\').join('/');
    if (('/' + rel).endsWith(suffix)) acc.push(rel);
  }
}

const root = repoRoot();
const here = dirname(fileURLToPath(import.meta.url));
const pages = join(here, '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'push.js'), 'utf8');
const model = await import(pathToFileURL(join(pages, 'push-model.js')).href);
const logModelFiles = [];
collectBySuffix(root, root, LOG_MODEL_SUFFIX, logModelFiles);
let logModel = null;
if (logModelFiles.length === 1) {
  logModel = await import(pathToFileURL(join(root, logModelFiles[0])).href);
}

const failures = [];
let checks = 0;

const SESSION = {platform: 'qq', type: '群', num: 12345};
const TARGET = {platform: 'qq', type: 1, num: 12345};
const CHAN = '群 12345';
const NEW_EMPTY = '本次启动以来的最近推送里，没有发往这个通道的。';
const UNLOADED = '这个通道还没被加载出来，取不到它的推送记录。';
const LINK_TEXT = '在日志页看全部 →';

function eq(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error(what + '：得到 ' + a + '，应为 ' + b);
}

function ask(what, fn) {
  checks++;
  try {
    fn();
  } catch (error) {
    failures.push(what + '：' + (error && error.message ? error.message : error));
  }
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

function el(tag, cls) {
  const node = {
    tag,
    className: cls || '',
    innerHTML: '',
    textContent: '',
    href: '',
    children: [],
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    querySelector() {
      return {addEventListener() {}};
    },
  };
  return node;
}

function esc(value) {
  return String(value ?? '');
}

function sectionHead(host) {
  const box = el('div', 'nv-card');
  host.appendChild(box);
  return box;
}

function collect(node, acc) {
  if (!node) return acc;
  acc.push(node);
  for (const child of node.children || []) collect(child, acc);
  return acc;
}

function linkOf(host) {
  return collect(host, []).find(n => n.tag === 'a' && String(n.textContent).includes(LINK_TEXT)) || null;
}

function hintsOf(host) {
  return collect(host, []).filter(n => n.tag === 'p').map(n => n.textContent);
}

function tablesOf(host) {
  return collect(host, []).filter(n => n.tag === 'table');
}

function loadSectionNotices(pushHistory) {
  const body = bracedFrom(src, 'function sectionNotices');
  if (!body) throw new Error('切不出 sectionNotices 整块');
  return new Function(
    'el', 'esc', 'sectionHead', 'noticeSwitches', 'store', 'pushHistory',
    'recentPushes', 'pushChannelOf',
    body + '\nreturn sectionNotices;')(
    el, esc, sectionHead, () => [], {handlerList: []}, pushHistory,
    model.recentPushes, model.pushChannelOf);
}

function renderNotices(session, history) {
  const host = el('div');
  loadSectionNotices(history)(host, {}, TARGET, session);
  return host;
}

ask('① 推送记录为空时日志链接 href 恰为全通道串', () => {
  const host = renderNotices(SESSION, []);
  const link = linkOf(host);
  if (!link) throw new Error('没有「在日志页看全部」链接');
  eq(link.href, '#/log?chan=' + encodeURIComponent(CHAN), 'href');
});

ask('② 空表提示句恰为本次启动以来那句', () => {
  const host = renderNotices(SESSION, []);
  const hints = hintsOf(host);
  if (!hints.includes(NEW_EMPTY)) {
    throw new Error('没有新句，现有 hint=' + JSON.stringify(hints));
  }
});

ask('③ session 为 null 时不出链接且提示句为取不到记录', () => {
  const host = renderNotices(null, []);
  if (linkOf(host)) throw new Error('仍出了「在日志页看全部」链接');
  const hints = hintsOf(host);
  if (!hints.includes(UNLOADED)) {
    throw new Error('提示句不是取不到记录，现有 hint=' + JSON.stringify(hints));
  }
});

ask('④ 链接喂 parseLogHash 后 channel 与 pushChannelOf 相等且 timelineQuery 含全串', () => {
  if (logModelFiles.length !== 1) {
    throw new Error('log-model.js 找到 ' + logModelFiles.length + ' 份：' + logModelFiles.join(', '));
  }
  if (typeof model.pushChannelOf !== 'function') throw new Error('没有 pushChannelOf');
  if (typeof logModel.parseLogHash !== 'function') throw new Error('没有 parseLogHash');
  if (typeof logModel.timelineQuery !== 'function') throw new Error('没有 timelineQuery');
  const host = renderNotices(SESSION, []);
  const link = linkOf(host);
  if (!link) throw new Error('没有链接');
  const state = logModel.parseLogHash(link.href, '2026-09-17');
  eq(state.channel, model.pushChannelOf(SESSION), 'state.channel');
  const query = logModel.timelineQuery(state);
  const needle = 'channel=' + encodeURIComponent(CHAN);
  if (!String(query).includes(needle)) {
    throw new Error('timelineQuery 不含 ' + needle + '：得到 ' + JSON.stringify(query));
  }
});

ask('⑤ 有记录时出表格、不出新句', () => {
  const host = renderNotices(SESSION, [{
    platform: 'qq', target: CHAN, at: '12:00', summary: '开播', success: true,
  }]);
  if (!tablesOf(host).length) throw new Error('没有表格');
  if (hintsOf(host).includes(NEW_EMPTY)) throw new Error('出现了空表新句');
});

ask('⑥ 找件不跟软链、不进带 .git 的子目录、跳过 target', () => {
  let tree;
  let outside;
  try {
    tree = mkdtempSync(join(tmpdir(), 'novabot-push-link-tree-'));
    outside = mkdtempSync(join(tmpdir(), 'novabot-push-link-outside-'));
    const suffixPath = 'src/main/resources/config-ui/log-model.js';
    mkdirSync(join(outside, 'src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(outside, suffixPath), '// outside\n');
    mkdirSync(join(tree, 'a/src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(tree, 'a', suffixPath), '// a\n');
    mkdirSync(join(tree, 'b/src/main/resources/config-ui'), {recursive: true});
    mkdirSync(join(tree, 'nested-file/src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(tree, 'nested-file/.git'), '');
    writeFileSync(join(tree, 'nested-file', suffixPath), '// nested-file\n');
    mkdirSync(join(tree, 'nested-dir/.git'), {recursive: true});
    mkdirSync(join(tree, 'nested-dir/src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(tree, 'nested-dir', suffixPath), '// nested-dir\n');
    mkdirSync(join(tree, 'target/src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(tree, 'target', suffixPath), '// target\n');
    let symlinkFailure = null;
    try {
      symlinkSync(join(outside, suffixPath), join(tree, 'b', suffixPath));
      symlinkSync(outside, join(tree, 'link'));
    } catch (error) {
      symlinkFailure = '本机造不了软链（' + error.constructor.name + ' ' + error.message + '）';
    }
    const got = [];
    collectBySuffix(tree, tree, LOG_MODEL_SUFFIX, got);
    got.sort();
    eq(got, ['a/src/main/resources/config-ui/log-model.js'], '实得');
    if (symlinkFailure) {
      throw new Error(symlinkFailure + '，不跟软链这一半没量');
    }
  } finally {
    if (tree) rmSync(tree, {recursive: true, force: true});
    if (outside) rmSync(outside, {recursive: true, force: true});
  }
  const left = [];
  if (tree && existsSync(tree)) left.push(tree);
  if (outside && existsSync(outside)) left.push(outside);
  if (left.length) throw new Error('临时目录没删净：' + left.join('、'));
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
