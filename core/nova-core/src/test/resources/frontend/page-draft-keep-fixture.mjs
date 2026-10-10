/**
 * 一页上保存或放弃，只动这一页的草稿：别页改过还没存的不跟着丢
 *
 * 抓的用户故障：推送页改了几处没存，去设置页存一项（或点「放弃」），回到推送页那几处全没了；
 * 反过来，设置页改了几项没存，去推送页点「放弃」，设置页那几项也一起没了——两条路都走
 * 整体载入，而整体载入会清掉设置页草稿、并让每一页重取（重取就是拿磁盘上的现值盖掉草稿）。
 *
 * 这把尺真走保存、放弃那两条路：把 settings.js 的 save／discard 与 main.js 的
 * load／callPage／refreshPages 按花括号配平切出函数体来跑，供数方那一套（登记、
 * 改动数、重取）用的是真 core.js，草稿账用的是真 store.js。断言的是假供数方的
 * reload／refresh 被调了几次、草稿还在不在。
 *
 * 七格：①设置页保存不丢别页草稿；②设置页放弃不丢别页草稿；③插件页放弃不丢设置页草稿
 * （也不丢别页草稿）；④对照——没改动的页照旧重取、初次载入每页都取；⑤放弃的确认框与
 * 提示只说这一页，不说「全部」；⑥插件页放弃时它自己写在状态栏上的话不被抹掉；
 * ⑦插件页放弃后这一页要按新取回的数据重画一次，别的页不画。
 * ①②③⑤⑥⑦ 先红后绿，④改前本来就绿。
 *
 * 由 PageDraftKeepTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
const greens = [];
let checks = 0;

function ask(name, fn) {
  checks++;
  try {
    fn();
    greens.push(name);
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function src(name) {
  return readFileSync(join(ui, name), 'utf8');
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

/**
 * 切出函数的函数体，包成函数交回调用方自己跑
 *
 * 形参槽照旧注入；formals 是被切那个函数自己的形参名（load 收一个 how），
 * 少了它，调用方传进来的那份「怎么取」在体里是个找不到的名。
 * sync 为真时包同步的：save／discard／load 是 async 的，而 callPage／refreshPages
 * 这类随手就调、没人 await 的包成 async 也能跑，只是那在赌它体内没有 await。
 */
function loadFn(text, marker, params, args, formals, sync) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return ' + (sync ? '' : 'async ')
    + '(' + (formals || '') + ') => {' + inner + '}')(...args);
}

// —— 真 core.js／真 store.js：供数方登记、改动数、重取那一套不另抄一份 ——

const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

const htmlClasses = new Set();

globalThis.document = {
  querySelector: node,
  documentElement: {
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

const coreUrl = new URL('../../../main/resources/config-ui/core.js', import.meta.url).href;
const storeUrl = new URL('../../../main/resources/config-ui/store.js', import.meta.url).href;

let core = null;
let store = null;
try {
  core = await import(coreUrl);
  store = (await import(storeUrl)).store;
} catch (err) {
  failures.push('载入 core.js／store.js 就抛了：' + err.message);
}

// —— 接口桩：/values 保存那条路与整体载入那几条读路各回各的 ——

const sent = [];

async function api(path, opt) {
  sent.push({path, opt});
  if (path === '/values' && opt && opt.method === 'POST') return {success: true};
  if (path === '/schema') return {groups: [{fields: [{name: 'demo.key', effect: 'RESTART'}]}], ungrouped: []};
  if (path === '/values') return {values: {'demo.key': '1'}, legacy: {}, locked: {}, fontTables: {}};
  if (path === '/status') return {configPath: '/tmp/demo.yml', senders: []};
  if (path === '/handlers') return {handlers: []};
  if (path === '/platforms') return {platforms: []};
  if (path === '/vocab') return {terms: {}};
  if (path === '/alert/channels') return {channels: []};
  return {};
}

// —— 整体载入里与本组判定无关的那几手：画版式、画运行状态等，这里只当它跑过 ——

const noop = () => {};
const parseHash = () => ({name: 'settings', card: ''});

// 确认框收到的话记下来，⑤ 要量的是真 discard 弹出的那一句
const asked = [];
const askStub = async (q) => {
  asked.push(q);
  return true;
};

// —— 宿主那份页清单与调页钩子：也从 main.js 切，重画要经 callPage 调页上的 refresh 才算数 ——

const pages = [];

let callPage = null;
let refreshPages = null;
try {
  callPage = loadFn(src('main.js'), 'function callPage(',
    ['say'], [core.say], 'page, hook, ...args', true);
  refreshPages = loadFn(src('main.js'), 'function refreshPages(',
    ['pages', 'callPage'], [pages, callPage], 'id', true);
} catch (err) {
  failures.push('切 callPage／refreshPages 就抛了：' + err.message);
}

// —— 切出产品那三个函数，按名字喂进上面这些桩；供数方那一套喂真的 ——

let save = null;
let discard = null;
let load = null;
try {
  load = loadFn(src('main.js'), 'function load(',
    ['say', 'api', 'store', 'renderGeneral', 'parseHash', 'focusCard', 'reloadChangeSources',
      'renderStatus', 'renderConfigPath', 'refreshPages', 'refreshHome', 'fillBotForms',
      'applyConnectionVocab', 'markDirty'],
    [core.say, api, store, noop, parseHash, noop, core.reloadChangeSources,
      noop, noop, noop, noop, noop, noop, core.markDirty],
    'how');
  save = loadFn(src('settings.js'), 'function save(',
    ['saveTarget', '$', 'api', 'showIssues', 'say', 'store', 'load', 'markDirty'],
    [core.saveTarget, node, api, noop, core.say, store, load, core.markDirty]);
  discard = loadFn(src('settings.js'), 'function discard(',
    ['ask', 'load', 'say', 'saveTarget', 'reloadChangeSources', 'refreshPages', 'markDirty'],
    [askStub, load, core.say, core.saveTarget, core.reloadChangeSources, refreshPages, core.markDirty]);
} catch (err) {
  failures.push('切 save／discard／load 就抛了：' + err.message);
}

/**
 * 登记一页假的供数方：草稿若干处，reload／refresh 被调几次记在账上
 *
 * reload 走的是插件页那份契约：自己把自己那一页重取一遍，顺带丢掉自己的草稿。
 * 页清单那一份同时挂上 refresh 钩子：⑦ 要数的是「重取之后这一页重画了没有」，
 * 两个钩子谁先谁后也记下——先画后取的话，画上去的还是旧数据。
 * quiet 那一档是「取回来的东西有问题、话已经写在状态栏上了」——回 false 让调用方别再盖话。
 */
function fakePage(id, opts) {
  const state = {reloaded: 0, refreshed: 0, draft: (opts && opts.draft) || 0, order: []};
  core.registerChangeSource(id, {
    displayName: id,
    changeCount: () => state.draft,
    save: () => {},
    reload: () => {
      state.reloaded++;
      state.order.push('reload');
      if (opts && opts.quiet) {
        core.say(opts.message || (id + '：这一页自己的话'), 'err');
        return false;
      }
      state.draft = 0;
    },
  });
  pages.push({
    meta: {id, displayName: id},
    module: {refresh: () => { state.refreshed++; state.order.push('refresh'); }},
  });
  return state;
}

/** 开一格之前的账：设置页草稿、当前页、页清单、发出的请求、状态栏都归零 */
function reset(tab, dirty) {
  store.tab = tab;
  store.dirty = Object.assign({}, dirty || {});
  store.restartPending = [];
  pages.length = 0;
  sent.length = 0;
  asked.length = 0;
  node('#status-text').textContent = '';
}

// ---------- ① 设置页保存不丢别页草稿 ----------
// 病：推送页改了几处没存，去设置页改一项再保存，回到推送页那几处全没了
let cell1 = '没跑';
try {
  reset('settings', {'demo.key': '2'});
  const push = fakePage('推送甲', {draft: 1});
  await save();
  cell1 = JSON.stringify({
    '那一页的 reload 调了几次': push.reloaded,
    '那一页还剩几处改动': push.draft,
    '设置页草稿剩几处': Object.keys(store.dirty).length,
  });
} catch (e) {
  cell1 = 'error:' + e.message;
}
ask('① 设置页保存成功后，别页有草稿的不重取、草稿还在', () => {
  const want = JSON.stringify({
    '那一页的 reload 调了几次': 0, '那一页还剩几处改动': 1, '设置页草稿剩几处': 0,
  });
  if (cell1 !== want) throw new Error('得到 ' + cell1 + '，应为 ' + want);
});

// ---------- ② 设置页放弃不丢别页草稿 ----------
// 病：同上，换成在设置页点「放弃」——设置页草稿丢掉是应该的，别页的不该跟着丢
let cell2 = '没跑';
try {
  reset('settings', {'demo.key': '2'});
  const push = fakePage('推送乙', {draft: 1});
  await discard();
  cell2 = JSON.stringify({
    '那一页的 reload 调了几次': push.reloaded,
    '那一页还剩几处改动': push.draft,
    '设置页草稿剩几处': Object.keys(store.dirty).length,
  });
} catch (e) {
  cell2 = 'error:' + e.message;
}
ask('② 设置页放弃后，设置页草稿清零，别页有草稿的不重取、草稿还在', () => {
  const want = JSON.stringify({
    '那一页的 reload 调了几次': 0, '那一页还剩几处改动': 1, '设置页草稿剩几处': 0,
  });
  if (cell2 !== want) throw new Error('得到 ' + cell2 + '，应为 ' + want);
});

// ---------- ③ 插件页放弃不丢设置页草稿 ----------
// 病：设置页改了几项没存，去推送页点「放弃」，设置页那几项也一起没了
let cell3 = '没跑';
try {
  reset('推送丙', {'demo.key': '2'});
  const push = fakePage('推送丙', {draft: 2});
  const other = fakePage('推送丁', {draft: 1});
  await discard();
  cell3 = JSON.stringify({
    '这一页的 reload 调了几次': push.reloaded,
    '别页的 reload 调了几次': other.reloaded,
    '设置页草稿剩几处': Object.keys(store.dirty).length,
    '设置页草稿那项还在吗': store.dirty['demo.key'] === '2',
  });
} catch (e) {
  cell3 = 'error:' + e.message;
}
ask('③ 插件页放弃只让这一页重取，设置页草稿与别页草稿都留着', () => {
  const want = JSON.stringify({
    '这一页的 reload 调了几次': 1, '别页的 reload 调了几次': 0,
    '设置页草稿剩几处': 1, '设置页草稿那项还在吗': true,
  });
  if (cell3 !== want) throw new Error('得到 ' + cell3 + '，应为 ' + want);
});

// ---------- ④ 对照：没改动的照旧重取，初次载入每页都取 ----------
// 这一格是给上面三格垫底的：留草稿的那一手收得太宽（谁都不重取）时，它先红
let cell4 = '没跑';
try {
  reset('settings', {'demo.key': '2'});
  const clean = fakePage('干净页', {draft: 0});
  await save();
  const afterSave = clean.reloaded;

  reset('settings', {});
  const first = fakePage('初载甲', {draft: 0});
  const second = fakePage('初载乙', {draft: 0});
  await load();
  cell4 = JSON.stringify({
    '保存后没改动的页重取了几次': afterSave,
    '初次载入甲重取了几次': first.reloaded,
    '初次载入乙重取了几次': second.reloaded,
  });
} catch (e) {
  cell4 = 'error:' + e.message;
}
ask('④ 对照：没改动的页保存后照旧重取，初次载入每一页都取', () => {
  const want = JSON.stringify({
    '保存后没改动的页重取了几次': 1, '初次载入甲重取了几次': 1, '初次载入乙重取了几次': 1,
  });
  if (cell4 !== want) throw new Error('得到 ' + cell4 + '，应为 ' + want);
});

// ---------- ⑤ 放弃的确认框与提示只说这一页 ----------
// 病：弹窗里写着「全部回到上一次保存的样子」，可丢掉的其实不止这一页——话和事对不上
let cell5 = '没跑';
try {
  reset('settings', {'demo.key': '2'});
  fakePage('推送戊', {draft: 1});
  await discard();
  const q = asked[asked.length - 1] || {};
  const words = String(q.title || '') + ' ' + String(q.body || '') + ' ' + String(node('#status-text').textContent || '');
  cell5 = JSON.stringify({
    '说没说全部': words.includes('全部'),
    '说没说这一页': words.includes('这一页') || words.includes('本页'),
  });
} catch (e) {
  cell5 = 'error:' + e.message;
}
ask('⑤ 放弃的确认框与事后那句话只说这一页，不说「全部」', () => {
  const want = JSON.stringify({'说没说全部': false, '说没说这一页': true});
  if (cell5 !== want) throw new Error('得到 ' + cell5 + '，应为 ' + want);
});

// ---------- ⑥ 插件页放弃时它写在状态栏上的话不被抹掉 ----------
// 病：那一趟取回来的东西有问题，话由那一页自己写在状态栏上；放弃一按又被一句盖掉，
// 出事的那句话还没等人看见就没了
let cell6 = '没跑';
try {
  reset('推送己', {});
  fakePage('推送己', {draft: 1, quiet: true, message: '推送己：这份数据取回来有问题'});
  await discard();
  cell6 = JSON.stringify({'状态栏上的话': node('#status-text').textContent});
} catch (e) {
  cell6 = 'error:' + e.message;
}
ask('⑥ 插件页放弃时，它自己写在状态栏上的话不被抹掉', () => {
  const want = JSON.stringify({'状态栏上的话': '推送己：这份数据取回来有问题'});
  if (cell6 !== want) throw new Error('得到 ' + cell6 + '，应为 ' + want);
});

// ---------- ⑦ 插件页放弃后这一页要按新取回的数据重画 ----------
// 病：在推送页关掉某一路的推送（或改一项本群设置），点「放弃」并确认。改动条消失了，
// 状态栏也写着放弃了，可屏幕上还是改过的样子；接着在这屏上再改，改的是画屏那一刻的旧对象，
// 改动条不计数、保存也存不进去，要切到别页再切回来才恢复正常——重取只换了数据，
// 没人叫这一页重画，屏上的控件还连着旧对象
let cell7 = '没跑';
try {
  reset('推送庚', {});
  const self = fakePage('推送庚', {draft: 1});
  const other = fakePage('推送辛', {draft: 1});
  await discard();
  cell7 = JSON.stringify({
    '这一页的 reload 调了几次': self.reloaded,
    '这一页的 refresh 调了几次': self.refreshed,
    '先重取后重画': self.order.join('>') === 'reload>refresh',
    '另一页的 refresh 调了几次': other.refreshed,
  });
} catch (e) {
  cell7 = 'error:' + e.message;
}
ask('⑦ 插件页放弃后这一页按新数据重画一次，别的页不画', () => {
  const want = JSON.stringify({
    '这一页的 reload 调了几次': 1, '这一页的 refresh 调了几次': 1,
    '先重取后重画': true, '另一页的 refresh 调了几次': 0,
  });
  if (cell7 !== want) throw new Error('得到 ' + cell7 + '，应为 ' + want);
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of greens) console.log('  绿：' + line);
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
