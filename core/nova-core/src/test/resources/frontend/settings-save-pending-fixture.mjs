/**
 * 设置页保存：回包之前又改的那几项，不许被当成已经存上
 *
 * 抓的用户故障：设置页改一项点「保存」，接口还没回来，又改了另一项（或把同一项
 * 改成别的值）。回包一到，那一趟整体重取把草稿整个清零——屏幕上显示的是服务端的
 * 旧值，改动条说 0 处，再点保存什么也不发。那几处从没进过配置文件，刷新页面就没了。
 * 推送页的保存早改成了「快照对齐到真发出去的那一份」（markPushSaved），设置页这一路
 * 一直没跟上。
 *
 * 这把尺真走保存那条路：settings.js 的 save 与 main.js 的 load 按花括号配平切出
 * 函数体来跑，供数方那一套（改动条计数、重取）用真 core.js，草稿账用真 store.js。
 * 回包放不放行由夹具攥着——真机上凑齐「回包之前又改」那个窗口要试很多回。
 *
 * 八格：①回包前改了另一项：那一项照旧算没存，已发的那项清掉；②回包前把同一项
 * 改成别的值：留新值；③回包前把同一项改回发出的值：这一项算存上了；④对照——
 * 途中什么都没改：草稿清零、值取回新的。①②先红后绿，③④改前本来就绿。
 *
 * ⑤—⑧量的是「发出之后又改回原值」那一条对账：服务端此刻存的是发出去的那一份，
 * 改回去的那一下不能被当成已经存上。⑤普通项改回发出前的值：仍是草稿、值是改回的那个、
 * 改动条数 1；⑥挂着清除时点「撤回清除」：同上，值是发出前那一位；⑦机密项改回留空：
 * 留空＝没动、旧值还原不出来，不留草稿，状态栏说清存上的是哪一份；⑧改成第三个值：
 * 留新值（守着现在的行为）。⑤⑥⑦先红后绿，⑧改前本来就绿。
 *
 * 由 SettingsSavePendingTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLEAR, MASK, defaultValue} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

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

/**
 * 切出 async 函数的函数体，包成异步函数交回调用方自己跑
 *
 * save／load 的体里有 await，套进普通 Function 会当场炸「await is only valid in async
 * functions」——那句红说的是夹具自己，不是产品码。因此剥掉声明头，只把语句体放进
 * 异步函数里，形参槽照旧注入。
 */
function loadAsyncFn(text, marker, params, args, formals) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async ' + (formals || '()') + ' => {' + inner + '}')(...args);
}

// —— 真 core.js／真 store.js：改动条计数、重取那一套不另抄一份 ——

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

// —— 接口桩：/values 保存那条路攥在夹具手里，回包什么时候放行由各格自己定 ——

let serverValues = {};
let postBodies = [];
let pendingPost = null;

async function api(path, opt) {
  if (path === '/values' && opt && opt.method === 'POST') {
    postBodies.push(JSON.parse(opt.body));
    // 服务端按送来的那份落盘；此后 /values 读回的就是它。清除标记是删键，不是存值
    for (const [key, value] of Object.entries(postBodies[postBodies.length - 1])) {
      if (value === CLEAR) delete serverValues[key];
      else serverValues[key] = value;
    }
    return await new Promise(resolve => { pendingPost = resolve; });
  }
  if (path === '/schema') return {
    groups: [{fields: [
      {name: 'demo.a', label: '示例甲'},
      {name: 'demo.b', label: '示例乙'},
      {name: 'demo.secret', label: '示例口令', sensitive: true},
    ]}],
    ungrouped: [],
  };
  if (path === '/values') return {values: Object.assign({}, serverValues), legacy: {}, locked: {}, fontTables: {}};
  if (path === '/status') return {configPath: '/tmp/demo.yml', senders: []};
  if (path === '/handlers') return {handlers: []};
  if (path === '/platforms') return {platforms: []};
  if (path === '/vocab') return {terms: {}};
  if (path === '/alert/channels') return {channels: []};
  return {};
}

// —— 整体载入里与本组判定无关的那几手只当它跑过；重画记下草稿账，供「屏幕上显示什么」那一问 ——

const renderSnaps = [];
const renderGeneral = () => {
  renderSnaps.push({dirty: Object.assign({}, store.dirty), values: Object.assign({}, store.values)});
};
const noop = () => {};
const parseHash = () => ({name: 'settings', card: ''});

let save = null;
try {
  const load = loadAsyncFn(readFileSync(join(ui, 'main.js'), 'utf8'), 'function load(',
    ['say', 'api', 'store', 'renderGeneral', 'parseHash', 'focusCard', 'reloadChangeSources',
      'renderStatus', 'renderConfigPath', 'refreshPages', 'refreshHome', 'fillBotForms',
      'applyConnectionVocab', 'markDirty', 'notePlatformsArrived', 'CLEAR', 'defaultValue'],
    [core.say, api, store, renderGeneral, parseHash, noop, core.reloadChangeSources,
      noop, noop, noop, noop, noop, noop, core.markDirty, noop, CLEAR, defaultValue],
    'how');
  save = loadAsyncFn(readFileSync(join(ui, 'settings.js'), 'utf8'), 'function save(',
    ['saveTarget', '$', 'api', 'showIssues', 'say', 'store', 'load', 'markDirty'],
    [core.saveTarget, node, api, noop, core.say, store, load, core.markDirty]);
} catch (err) {
  failures.push('切 save／load 就抛了：' + err.message);
}

/** 开一格之前的账：设置页草稿、服务端现值、发出的请求、重画快照都归零 */
function reset(dirty) {
  store.tab = 'settings';
  store.dirty = Object.assign({}, dirty || {});
  store.restartPending = [];
  serverValues = {'demo.a': '1', 'demo.b': '1', 'demo.secret': MASK};
  // 发出之前屏幕上那份「已保存」：对账要按它把「改回原值」那一手还原成草稿
  store.values = {'demo.a': '1', 'demo.b': '1', 'demo.secret': MASK};
  postBodies = [];
  pendingPost = null;
  renderSnaps.length = 0;
  node('#save').disabled = false;
  node('#status-text').textContent = '';
}

/**
 * 走一遍「点保存 → 途中又改 → 回包」：during 在保存请求挂起的那段里改草稿
 * @param during 途中对草稿动的手
 */
async function saveWith(during) {
  const saving = save();
  if (during) during();
  // pendingPost 攥的是那个 resolve 本身：这一下就是把保存的回包放行
  pendingPost({success: true});
  await saving;
}

// ---------- ① 回包前改了另一项 ----------
// 病：那一项没随这趟发出去，回包后的整体重取却把草稿清了零——
// 屏幕显示旧值、改动条归零、再点保存什么也不发，那处改动再也要不回来
let cell1 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(() => { store.dirty['demo.b'] = '3'; });
  cell1 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '还挂着的是哪一项': store.dirty['demo.b'] === undefined ? '(没了)' : store.dirty['demo.b'],
    '重画时屏幕上还挂着': renderSnaps[renderSnaps.length - 1].dirty,
    '取回的已保存值': store.values['demo.a'],
  });
} catch (e) {
  cell1 = 'error:' + e.message;
}
eq(JSON.parse(cell1), {
  '发出去的是哪几项': {'demo.a': '2'},
  '改动条还剩几处': 1,
  '还挂着的是哪一项': '3',
  '重画时屏幕上还挂着': {'demo.b': '3'},
  '取回的已保存值': '2',
}, '① 回包前改了另一项：那一项照旧算没存（改动条计它、重画显示它），已发的那项清掉、值取回新的');

// ---------- ② 回包前把同一项改成别的值 ----------
// 病：同一项也一样——发出去的是 2，回来之前改成了 9，重取把它抹回 2，
// 而那个人刚刚敲进去的 9 无声无息地没了
let cell2 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(() => { store.dirty['demo.a'] = '9'; });
  cell2 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '这一项此刻算什么': store.dirty['demo.a'] === undefined ? '(没了)' : store.dirty['demo.a'],
    '重画时屏幕上显示': renderSnaps[renderSnaps.length - 1].dirty['demo.a'] === undefined
      ? '(服务端的值)' : renderSnaps[renderSnaps.length - 1].dirty['demo.a'],
    '取回的已保存值': store.values['demo.a'],
  });
} catch (e) {
  cell2 = 'error:' + e.message;
}
eq(JSON.parse(cell2), {
  '发出去的是哪几项': {'demo.a': '2'},
  '改动条还剩几处': 1,
  '这一项此刻算什么': '9',
  '重画时屏幕上显示': '9',
  '取回的已保存值': '2',
}, '② 回包前把同一项改成别的值：留新值（算没存），取回的是真发出去的那份');

// ---------- ③ 回包前把同一项改回发出的值 ----------
// 发出去 2、途中先改成 9 又改回 2：与发出的那份一致，算存上了。
// 这格盯的是对账别对过头——把改回原值的也留成没存，改动条会永远清不了零
let cell3 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(() => { store.dirty['demo.a'] = '9'; store.dirty['demo.a'] = '2'; });
  cell3 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '取回的已保存值': store.values['demo.a'],
  });
} catch (e) {
  cell3 = 'error:' + e.message;
}
eq(JSON.parse(cell3), {
  '发出去的是哪几项': {'demo.a': '2'},
  '改动条还剩几处': 0,
  '取回的已保存值': '2',
}, '③ 回包前把同一项改回发出的值：这一项算存上了，草稿清干净');

// ---------- ④ 对照：途中什么都没改 ----------
// 垫底的一格：留草稿那一手收得太宽（谁都清不掉）时，它先红
let cell4 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(null);
  cell4 = JSON.stringify({
    '改动条还剩几处': Object.keys(store.dirty).length,
    '取回的已保存值': store.values['demo.a'],
  });
} catch (e) {
  cell4 = 'error:' + e.message;
}
eq(JSON.parse(cell4), {
  '改动条还剩几处': 0,
  '取回的已保存值': '2',
}, '④ 对照：途中什么都没改，草稿清零、值取回新存的');

// ---------- ⑤ 保存途中把普通项改回发出前的值 ----------
// 病：A 原来是 1，改成 2 点保存，回包之前又改回 1——这一下等于「没改」，草稿被对账
// 清掉，可服务端此刻存的是 2。屏幕上显示 1（服务端其实不是），改动条归零，
// 再点保存什么也不发，而 A 一直停在 2
let cell5 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(() => { delete store.dirty['demo.a']; });
  cell5 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '还挂着的是哪一项': store.dirty['demo.a'] === undefined ? '(没了)' : store.dirty['demo.a'],
    '重画时屏幕上还挂着': renderSnaps[renderSnaps.length - 1].dirty,
    '取回的已保存值': store.values['demo.a'],
    '状态栏上的话': node('#status-text').textContent,
  });
} catch (e) {
  cell5 = 'error:' + e.message;
}
eq(JSON.parse(cell5), {
  '发出去的是哪几项': {'demo.a': '2'},
  '改动条还剩几处': 1,
  '还挂着的是哪一项': '1',
  '重画时屏幕上还挂着': {'demo.a': '1'},
  '取回的已保存值': '2',
  '状态栏上的话': '已保存',
}, '⑤ 保存途中把普通项改回发出前的值：保存完它仍是草稿，值是改回的那个，改动条数 1');

// ---------- ⑥ 保存途中撤回清除 ----------
// 病：口令挂着清除点保存，回包之前点「撤回清除」——那一下把草稿删了，对账当它已经
// 存上。可发出去的是清除标记，口令已经删了；界面上写着原来的遮罩，再点保存什么也不发
let cell6 = '没跑';
try {
  reset({'demo.secret': CLEAR});
  await saveWith(() => { delete store.dirty['demo.secret']; });
  cell6 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '还挂着的是哪一项': store.dirty['demo.secret'] === undefined ? '(没了)' : store.dirty['demo.secret'],
    '重画时屏幕上还挂着': renderSnaps[renderSnaps.length - 1].dirty,
    '状态栏上的话': node('#status-text').textContent,
  });
} catch (e) {
  cell6 = 'error:' + e.message;
}
eq(JSON.parse(cell6), {
  '发出去的是哪几项': {'demo.secret': CLEAR},
  '改动条还剩几处': 1,
  '还挂着的是哪一项': MASK,
  '重画时屏幕上还挂着': {'demo.secret': MASK},
  '状态栏上的话': '已保存',
}, '⑥ 保存途中撤回清除：保存完它仍是草稿，值是改回的那个，改动条数 1');

// ---------- ⑦ 保存途中把机密项改回留空 ----------
// 病：口令改成新值点保存，回包之前把框清空（留空＝没动）——草稿被删，对账当它已经
// 存上。旧值界面手里根本没有，还原不出来：不留草稿可以，但得说清存上的是哪一份，
// 否则那个人会以为存上去的是原来的口令
let cell7 = '没跑';
try {
  reset({'demo.secret': 'new-token'});
  await saveWith(() => { delete store.dirty['demo.secret']; });
  cell7 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '这一项此刻算什么': store.dirty['demo.secret'] === undefined ? '(没了)' : store.dirty['demo.secret'],
    '取回的已保存值': store.values['demo.secret'],
    '状态栏上的话': node('#status-text').textContent,
  });
} catch (e) {
  cell7 = 'error:' + e.message;
}
eq(JSON.parse(cell7), {
  '发出去的是哪几项': {'demo.secret': 'new-token'},
  '改动条还剩几处': 0,
  '这一项此刻算什么': '(没了)',
  '取回的已保存值': 'new-token',
  '状态栏上的话': '「示例口令」在保存途中改回了留空，存上的是刚才发出去的那一份；要换回原来的，请重新填写。',
}, '⑦ 保存途中把机密项改回留空：不留草稿，状态栏说清存上的是哪一份');

// ---------- ⑧ 保存途中改成第三个值 ----------
// 垫底的一格，守着现在的行为：发出 2、回包前改成 9，留的是 9。
// 还原那一手收得不对（草稿里还挂着值的也去还原）时，它先红
let cell8 = '没跑';
try {
  reset({'demo.a': '2'});
  await saveWith(() => { store.dirty['demo.a'] = '9'; });
  cell8 = JSON.stringify({
    '发出去的是哪几项': postBodies[0],
    '改动条还剩几处': Object.keys(store.dirty).length,
    '这一项此刻算什么': store.dirty['demo.a'] === undefined ? '(没了)' : store.dirty['demo.a'],
    '重画时屏幕上显示': renderSnaps[renderSnaps.length - 1].dirty['demo.a'] === undefined
      ? '(服务端的值)' : renderSnaps[renderSnaps.length - 1].dirty['demo.a'],
    '取回的已保存值': store.values['demo.a'],
    '状态栏上的话': node('#status-text').textContent,
  });
} catch (e) {
  cell8 = 'error:' + e.message;
}
eq(JSON.parse(cell8), {
  '发出去的是哪几项': {'demo.a': '2'},
  '改动条还剩几处': 1,
  '这一项此刻算什么': '9',
  '重画时屏幕上显示': '9',
  '取回的已保存值': '2',
  '状态栏上的话': '已保存',
}, '⑧ 保存途中改成第三个值：留新值，取回的是真发出去的那份');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
