/**
 * 推送页「这个通道最近推送」：日志链接带全通道串，空表句写明本次启动以来
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 PushRecentLinkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
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

/**
 * dir 下以 suffix 结尾的普通文件记进 acc，读不了的目录记进 unreadable
 *
 * 读不了的目录记成「相对路径（errno 码）」，不静默跳过：漏读一个目录，
 * 里面那份重复件就数不到，「恰 1 份」也就作不得准。
 */
function collectBySuffix(dir, root, suffix, acc, unreadable) {
  let entries;
  try {
    entries = readdirSync(dir, {withFileTypes: true});
  } catch (error) {
    const rel = relative(root, dir).split('\\').join('/') || '.';
    unreadable.push(rel + '（' + (error.code || String(error)) + '）');
    return;
  }
  for (const entry of entries) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (nestedHasGit(path)) continue;
      collectBySuffix(path, root, suffix, acc, unreadable);
      continue;
    }
    if (!entry.isFile()) continue;
    const rel = relative(root, path).split('\\').join('/');
    if (('/' + rel).endsWith(suffix)) acc.push(rel);
  }
}

/**
 * 以 suffix 结尾的件恰 1 份、且没有读不了的目录时交回 {rel}，否则交回 {error}
 */
function uniqueBySuffix(root, suffix) {
  const found = [];
  const unreadable = [];
  collectBySuffix(root, root, suffix, found, unreadable);
  if (unreadable.length) {
    return {error: '找以 ' + suffix + ' 结尾的件时有 ' + unreadable.length + ' 个目录读不了，份数作不得准：'
      + unreadable.join('、')};
  }
  if (found.length !== 1) {
    return {error: '以 ' + suffix + ' 结尾的件应恰 1 份，实得 ' + found.length + ' 份'
      + (found.length ? '：' + found.join('、') : '')};
  }
  return {rel: found[0]};
}

const root = repoRoot();
const here = dirname(fileURLToPath(import.meta.url));
const pages = join(here, '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'push.js'), 'utf8');
const model = await import(pathToFileURL(join(pages, 'push-model.js')).href);
const logModelPick = uniqueBySuffix(root, LOG_MODEL_SUFFIX);
let logModel = null;
if (logModelPick.rel) {
  logModel = await import(pathToFileURL(join(root, logModelPick.rel)).href);
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
 * 按名字各建一个 novabot-push-link-<名>- 临时目录交给 body，主体的错与没删净合成一句抛出
 *
 * 主体抛错也照删，删完再核目录还在不在。只报没删净会盖住主体的错，只报主体的错会漏掉残留，
 * 所以两样都先记下，末了有一样就抛。
 */
function inTempDirs(names, body) {
  const dirs = [];
  const problems = [];
  try {
    for (const name of names) dirs.push(mkdtempSync(join(tmpdir(), 'novabot-push-link-' + name + '-')));
    body(...dirs);
  } catch (error) {
    problems.push(error && error.message ? error.message : String(error));
  } finally {
    for (const dir of dirs) {
      try {
        rmSync(dir, {recursive: true, force: true});
      } catch {
        // 删不掉，由下面没删净那句报
      }
    }
  }
  const left = dirs.filter(dir => existsSync(dir));
  if (left.length) problems.push('临时目录没删净：' + left.join('、'));
  if (problems.length) throw new Error(problems.join('；'));
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

ask('① 推送记录为空时日志链接落在推送栏、带全通道串', () => {
  const host = renderNotices(SESSION, []);
  const link = linkOf(host);
  if (!link) throw new Error('没有「在日志页看全部」链接');
  eq(link.href, '#/log?cat=PUSH&chan=' + encodeURIComponent(CHAN), 'href');
});

ask('①b 群抽屉的链接落在推送栏而不是全部栏', () => {
  const host = renderNotices(SESSION, []);
  const link = linkOf(host);
  const href = link ? String(link.href) : '';
  eq({
    '带上推送大类': href.includes('cat=PUSH'),
    '带着这个群': href.includes('chan=' + encodeURIComponent(CHAN)),
  }, {
    '带上推送大类': true,
    '带着这个群': true,
  }, '从群抽屉点过去应落在推送栏——开播下播不属任何群，落进全部栏会被群筛掉');
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

/**
 * ④ 的主体，找件结果由调用方交进来：带错就照原句抛出（读不了的目录在错句里点了名），不往下量
 */
function linkAgainstLogModel(logModelPick) {
  if (logModelPick.error) throw new Error(logModelPick.error);
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
}

ask('④ 链接喂 parseLogHash 后 channel 与 pushChannelOf 相等且 timelineQuery 含全串', () => linkAgainstLogModel(logModelPick));

ask('⑤ 有记录时出表格、不出新句', () => {
  const host = renderNotices(SESSION, [{
    platform: 'qq', target: CHAN, at: '12:00', summary: '开播', success: true,
  }]);
  if (!tablesOf(host).length) throw new Error('没有表格');
  if (hintsOf(host).includes(NEW_EMPTY)) throw new Error('出现了空表新句');
});

ask('⑥ 找件不跟软链、不进带 .git 的子目录、跳过 target', () => {
  inTempDirs(['tree', 'outside'], (tree, outside) => {
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
    collectBySuffix(tree, tree, LOG_MODEL_SUFFIX, got, []);
    got.sort();
    eq(got, ['a/src/main/resources/config-ui/log-model.js'], '实得');
    if (symlinkFailure) {
      throw new Error(symlinkFailure + '，不跟软链这一半没量');
    }
  });
});

ask('⑦ 找件遇到读不了的目录记名交回，此时不认那唯一一份', () => {
  inTempDirs(['locked'], tree => {
    const suffixPath = 'src/main/resources/config-ui/log-model.js';
    mkdirSync(join(tree, 'a/src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(tree, 'a', suffixPath), '// a\n');
    const locked = join(tree, 'locked');
    mkdirSync(join(locked, 'src/main/resources/config-ui'), {recursive: true});
    writeFileSync(join(locked, suffixPath), '// locked\n');
    const before = [];
    const beforeUnreadable = [];
    collectBySuffix(tree, tree, LOG_MODEL_SUFFIX, before, beforeUnreadable);
    before.sort();
    eq({got: before, unreadable: beforeUnreadable},
      {got: ['a/' + suffixPath, 'locked/' + suffixPath], unreadable: []}, '没锁时（阳性对照）');
    chmodSync(locked, 0o000);
    try {
      const got = [];
      const unreadable = [];
      collectBySuffix(tree, tree, LOG_MODEL_SUFFIX, got, unreadable);
      eq({got, unreadable}, {got: ['a/' + suffixPath], unreadable: ['locked（EACCES）']}, '锁成 000 后');
      const picked = uniqueBySuffix(tree, LOG_MODEL_SUFFIX);
      eq({rel: picked.rel || '', named: String(picked.error || '').includes('locked（EACCES）')},
        {rel: '', named: true}, '锁成 000 后挑唯一一份');
    } finally {
      chmodSync(locked, 0o700);
    }
  });
});

ask('⑧ 临时目录收尾：主体抛错、删净正常时照报主体的错', () => {
  const made = [];
  let thrown = '';
  try {
    inTempDirs(['probe'], dir => {
      made.push(dir);
      throw new Error('模拟主体出错');
    });
  } catch (error) {
    thrown = error && error.message ? error.message : String(error);
  }
  eq({thrown, left: made.filter(dir => existsSync(dir))}, {thrown: '模拟主体出错', left: []}, '抛出的错与残留');
});

ask('⑨ 找件记名照实带 errno：读时目录已不在记 ENOENT，不写死 EACCES、也不只记 EACCES', () => {
  inTempDirs(['gone'], tree => {
    const got = [];
    const unreadable = [];
    collectBySuffix(join(tree, 'gone'), tree, LOG_MODEL_SUFFIX, got, unreadable);
    eq({got, unreadable}, {got: [], unreadable: ['gone（ENOENT）']}, '目录不在时');
  });
});

ask('⑩ 临时目录收尾：主体抛错又删不掉时两样都报，没删净的点名', () => {
  const made = [];
  let thrown = '';
  let stayed = false;
  try {
    inTempDirs(['stuck'], dir => {
      made.push(dir);
      mkdirSync(join(dir, 'sealed/keep'), {recursive: true});
      chmodSync(join(dir, 'sealed'), 0o000);
      throw new Error('模拟主体出错');
    });
  } catch (error) {
    thrown = error && error.message ? error.message : String(error);
  } finally {
    stayed = made.some(dir => existsSync(dir));
    for (const leftover of made) {
      if (existsSync(join(leftover, 'sealed'))) chmodSync(join(leftover, 'sealed'), 0o700);
      rmSync(leftover, {recursive: true, force: true});
    }
  }
  eq({stayed, thrown: made.length ? thrown.split(made[0]).join('<目录>') : thrown, left: made.filter(dir => existsSync(dir))},
    {stayed: true, thrown: '模拟主体出错；临时目录没删净：<目录>', left: []}, '删不掉时（stayed 为目录确实没删掉）');
});

ask('⑪ ④ 找件交回错句时照原句红，不往下量', () => {
  const said = '有 1 个目录读不了，份数作不得准：locked（EACCES）';
  let thrown = '';
  try {
    linkAgainstLogModel({error: said});
  } catch (error) {
    thrown = error && error.message ? error.message : String(error);
  }
  eq(thrown, said, '④ 抛出的错');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
