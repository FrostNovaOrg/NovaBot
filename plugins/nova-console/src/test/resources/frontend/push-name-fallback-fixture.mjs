/**
 * 推送页主播名：现场去平台查不回时，退回程序手上已有的昵称
 *
 * 平台断网或被风控时打开推送页，现场查名全落空，一排主播都显示成「uid 数字」，认不出谁是谁；
 * 同一时刻运行状态页与主播页却显示得出名字。查不回（回失败或抛错）时改问 /streamer/name，
 * 那里先给内存名、再给最近一场归档里的昵称；都没有才照旧显示 uid。
 * 添加主播那张确认小卡只认现场查回来的结果，不走这条退路。
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 PushNameFallbackTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');
const src = readFileSync(join(ui, 'push.js'), 'utf8');
const {streamerName, pushTree} = await import(pathToFileURL(join(ui, 'push-model.js')).href);

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

const decorateSrc = bracedFrom(src, 'async function decoratePushData');
if (!decorateSrc) {
  console.log('跑了 0 格，切不出 decoratePushData');
  process.exit(1);
}

/**
 * 用假接口跑一趟 decoratePushData
 * @param routes 按路径给回包的函数，路径不带查询串；抛错即模拟网络失败
 */
async function decorate(pushData, routes) {
  const calls = [];
  const api = async (path, opts) => {
    calls.push(path);
    const route = routes[path.split('?')[0]];
    if (!route) throw new Error('未设的接口 ' + path);
    return route(path, opts);
  };
  const store = {platforms: ['bilibili']};
  const fn = new Function('api', 'store', 'renderStreamers', 'pushData',
    'let datasourceGeneration = 0; const userVersions = new Map(), userLoads = new Map(), conflictedUsers = new Set(); const userKey = user => user.platform + ":" + user.uid; const picked = {}; const showPush = async () => {}; const paintTree = renderStreamers; let nameLookups = new Set();\n' + decorateSrc + '\nreturn decoratePushData;')(api, store, () => {}, pushData);
  await fn();
  return calls;
}

const liveMiss = () => ({success: false, message: '未查到 uid 7 对应的主播，请确认 uid 是否正确'});
const liveDown = () => {
  throw new Error('网络断了');
};
const knownName = uname => path => {
  const q = new URLSearchParams(path.split('?')[1] || '');
  return q.get('platform') === 'bilibili' && q.get('uid') === '7'
    ? {success: true, uid: 7, uname}
    : {success: false};
};

// ① 现场查回失败、程序手上有昵称（归档里的）→ 显示该昵称，不显示 uid
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveMiss, '/streamer/name': knownName('归档里的昵称')});
  eq(streamerName(user), '归档里的昵称', '① 现场查回失败、手上有昵称 → 显示归档昵称');
}

// ② 现场查抛错（平台断网）、程序手上有昵称 → 显示该昵称
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveDown, '/streamer/name': knownName('内存里的昵称')});
  eq(streamerName(user), '内存里的昵称', '② 现场查抛错、手上有昵称 → 显示该昵称');
}

// ③ 两处都没有 → 照旧显示 uid
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveMiss, '/streamer/name': () => ({success: false})});
  eq(streamerName(user), 'uid 7', '③ 现场查不回、手上也没有 → 照旧显示 uid');
}

// ④ 退路本身也抛错 → 照旧显示 uid，不连累别的主播
{
  const a = {uid: 7, platform: 'bilibili'};
  const b = {uid: 8, platform: 'bilibili'};
  await decorate([a, b], {
    '/streamer/lookup': (path, opts) => (JSON.parse(opts.body).uid === '8'
      ? {success: true, uid: 8, uname: '现查回来的'}
      : liveMiss()),
    '/streamer/name': liveDown,
  });
  eq([streamerName(a), streamerName(b)], ['uid 7', '现查回来的'], '④ 退路也抛错 → 这位照旧显示 uid，另一位不受连累');
}

// ⑤ 现场查回来了 → 用现查的名，不再问退路
{
  const user = {uid: 7, platform: 'bilibili'};
  const calls = await decorate([user], {
    '/streamer/lookup': () => ({success: true, uid: 7, uname: '现查回来的', roomId: 22}),
    '/streamer/name': knownName('归档里的旧名'),
  });
  eq([streamerName(user), calls.some(p => p.startsWith('/streamer/name'))], ['现查回来的', false],
    '⑤ 现场查回来了 → 用现查的名且不问退路');
}

// ⑥ 添加主播那张确认小卡只认现场查回来的：那一份函数里不出现退路接口
{
  const at = src.indexOf('/streamer/lookup');
  const start = at < 0 ? -1 : src.lastIndexOf('async function', at);
  const addSrc = start < 0 ? '' : bracedFrom(src.slice(start), 'async function');
  eq([addSrc !== '' && addSrc !== decorateSrc, addSrc.includes('/streamer/name')], [true, false],
    '⑥ 添加主播的查询函数不走退路接口');
}

// ⑦ 放弃草稿后沿用已知昵称，只查询重载后仍缺昵称的主播
{
  const initial = [
    {platform: 'bilibili', uid: 7, enabled: false, _uname: '原来的昵称', _roomId: 22, _face: 'avatar'},
    {platform: 'bilibili', uid: 8},
  ];
  const saved = [
    {platform: 'bilibili', uid: '7'},
    {platform: 'bilibili', uid: 8},
    {platform: 'bilibili', uid: 9},
    {platform: 'other', uid: 7},
  ];
  const calls = [];
  const api = async (path, opts) => {
    calls.push(path);
    if (path === '/datasource/users') return {success: true, users: saved};
    if (path === '/streamer/lookup') {
      const {platform, uid} = JSON.parse(opts.body);
      return uid === '8' ? liveMiss() : {success: true, uname: platform + uid};
    }
    if (path.startsWith('/streamer/name?')) return {success: true, uname: '补回的昵称'};
    throw new Error('未设的接口 ' + path);
  };
  let drafts = 1;
  const functions = [
    'async function reload(', 'function serializePush', 'function dropDisplayOnly',
    'function pushChangeCount', 'function changeCount', 'async function decoratePushData',
  ].map(marker => bracedFrom(src, marker)).join('\n');
  const page = new Function('api', 'store', 'initial', 'clearSessionDrafts',
    'sessionDraftCount', 'renderStreamers', 'say',
    'let datasourceGeneration = 0; const userVersions = new Map(), userLoads = new Map(), conflictedUsers = new Set(); const userKey = user => user.platform + ":" + user.uid; const picked = {}; const showPush = async () => {}; const paintTree = renderStreamers; let nameLookups = new Set(); let pushData = initial; let pushSaved = "[]"; let pageLive = true;\n' + functions
    + '\nreturn {reload, serializePush, changeCount, entries: () => pushData};')(
    api, {platforms: ['bilibili', 'other']}, initial, () => { drafts = 0; },
    () => drafts, () => {},
    message => { throw new Error(message); });
  await page.reload();
  const expectedNames = ['原来的昵称', '补回的昵称', 'bilibili9', 'other7'];
  eq(pushTree(page.entries(), [], [], {}).map(u => u.name), expectedNames,
    '⑦ 放弃后左树保留原名，并补全缺失昵称，同号不同平台分别显示');
  eq(page.entries().map(streamerName), expectedNames, '⑦ 通道头部使用的主播名保持一致');
  eq(calls, ['/datasource/users', '/streamer/lookup', '/streamer/lookup', '/streamer/lookup',
    '/streamer/name?platform=bilibili&uid=8'], '⑦ 已知昵称不再查询，缺失昵称仍先现场查再退回');
  eq(page.changeCount(), 0, '⑦ 放弃并补名后仍为零处改动');
  eq(JSON.parse(page.serializePush()), saved, '⑦ 昵称等展示字段不进入保存内容');
}

// ⑧ 从宿主整页载入与导航入口运行，不直接改推送页的活动状态。
const hostSrc = readFileSync(join(ui, '../../../../../../core/nova-core/src/main/resources/config-ui/main.js'), 'utf8');
async function consolePage(hash, slowPlatforms = false, missingNames = false) {
  const calls = [];
  const pending = [];
  const location = {hash};
  const store = {platforms: [], dirty: {}, values: {}};
  let releasePlatforms;
  const platformsReady = new Promise(resolve => { releasePlatforms = resolve; });
  const saved = [{platform: 'bilibili', uid: 7}, {platform: 'bilibili', uid: 8}];
  let names = [];
  const repaints = [];
  const noop = () => {};
  const api = async (path, opts) => {
    calls.push(path);
    if (path === '/datasource/users') {
      if (slowPlatforms) await Promise.all(pending);
      return {success: true, users: saved};
    }
    if (path === '/platforms') {
      if (slowPlatforms) await platformsReady;
      return {platforms: ['bilibili']};
    }
    if (path === '/streamer/lookup') {
      if (missingNames) return {success: false};
      const {uid} = JSON.parse(opts.body);
      return {success: true, uname: '主播' + uid};
    }
    return {};
  };
  const pushFns = [
    'function refresh(', 'function leave(', 'function routeFromHash(',
    'async function loadPushPage(', 'async function reload(', 'function serializePush',
    'function dropDisplayOnly', 'async function decoratePushData',
  ].map(marker => bracedFrom(src, marker)).join('\n');
  const push = new Function('api', 'store', 'location', 'showPush', 'paintTree', 'renderStreamers',
    'clearSessionDrafts', 'closeDrawer', 'say', 'targetOptions', 'buildDirectory',
    'let datasourceGeneration = 0; const userVersions = new Map(), userLoads = new Map(), conflictedUsers = new Set(); const userKey = user => user.platform + ":" + user.uid; const picked = {}; let nameLookups = new Set(); let pageLive = false, pushData = [], pushSaved = "[]", runtime = {}, pushHistory = [], quota, options, directory;\n'
    + pushFns + '\nreturn {refresh, reload, leave, entries: () => pushData};')(
    api, store, location, () => { names = pushTree(push.entries(), [], [], {}).map(u => u.name); },
    () => { names = pushTree(push.entries(), [], [], {}).map(u => u.name); },
    () => {
      repaints.push(push.entries().map(user => user._uname || null));
      names = pushTree(push.entries(), [], [], {}).map(u => u.name);
    },
    noop, noop, message => { throw new Error(message); }, () => [], () => ({}));
  const pages = [{meta: {id: 'push', slot: 'top'}, module: {
    refresh: (...args) => { const result = push.refresh(...args); if (result) pending.push(result); },
    leave: push.leave,
  }}];
  const scope = {api, store, location, pages, SLOT_TOP: 'top', SLOT_SETTINGS: 'settings',
    PAGE_TAB: {home: 'home', log: 'log', links: 'links', settings: 'general'},
    TAB_HASH: {}, topPageIds: new Set(['push', 'streamers']),
    route: '', homeStatus: {}, homeLogin: {}, later: false,
    shouldOpenSetup: () => false, manualHref: () => '',
    $: () => ({scrollIntoView: noop}),
    document: {querySelectorAll: () => [], documentElement: {classList: {toggle: noop}}},
    window: {scrollTo: noop}, clearTimeout,
    reloadChangeSources: async () => { await push.reload(); releasePlatforms(); },
  };
  for (const key of ['say', 'renderGeneral', 'focusCard', 'renderStatus', 'renderConfigPath',
    'refreshHome', 'fillBotForms', 'applyConnectionVocab', 'markDirty', 'notePlatformsArrived',
    'clearIssuedToken', 'stopFollow', 'stopSetupPolling', 'stopWatchingGroups', 'syncLogView',
    'loadTokens', 'refreshLinks', 'loadTargets', 'loadLog', 'openSetup']) scope[key] = noop;
  scope.say = (message, level) => { if (level === 'err') throw new Error(message); };
  const hostFns = ['function callPage(', 'function refreshPages(', 'function parseHash(',
    'function applyRoute(', 'async function load(']
    .map(marker => bracedFrom(hostSrc, marker)).join('\n');
  const host = new Function('scope', 'with (scope) { ' + hostFns
    + '\nreturn {applyRoute, load, refreshPages}; }')(scope);
  async function settled() {
    while (pending.length) await Promise.all(pending.splice(0));
  }
  // main.js 挂好插件页之后的真实顺序：先按路由进页，再整体载入。
  host.applyRoute(false);
  host.applyRoute();
  await host.load();
  await settled();
  return {calls, repaints, names: () => names, async refresh() {
    host.refreshPages(); await settled();
  }, async enter(hash = '#/push') {
    location.hash = hash; host.applyRoute(); await settled();
  }, async discard() {
    await push.reload(); host.refreshPages('push'); await settled();
  }};
}
for (const page of ['home', 'streamers', 'log', 'links', 'settings']) {
  const console = await consolePage('#/' + page);
  eq(console.calls.filter(p => p === '/streamer/lookup').length, 0,
    '⑧ 宿主整页载入 ' + page + ' → lookup 零次');
  console.calls.length = 0;
  await console.enter();
  eq(console.calls.filter(p => p === '/streamer/lookup').length, 2,
    '⑧ 从 ' + page + ' 进入推送页 → 逐个补名');
  eq(console.names(), ['主播7', '主播8'], '⑧ 进入推送页后左树名字补全');
}
{
  const console = await consolePage('#/push');
  eq(console.names(), ['主播7', '主播8'], '⑨ 推送页整页载入后左树名字补全');
  console.calls.length = 0;
  await console.discard();
  eq(console.calls.filter(p => p === '/streamer/lookup').length, 0, '⑨ 放弃不再查询昵称');
  eq(console.names(), ['主播7', '主播8'], '⑨ 放弃后左树名字不丢');
}

{
  const console = await consolePage('#/push', true);
  eq(console.names(), ['主播7', '主播8'], '⑩ 平台清单晚于配置到达，整页载入仍补全左树名字');
}

// ⑪ 查名失败也只在这一趟进页时尝试一次，切换通道与整体刷新不重试。
{
  const page = await consolePage('#/push', false, true);
  const counts = () => [page.calls.filter(p => p === '/streamer/lookup').length,
    page.calls.filter(p => p.startsWith('/streamer/name?')).length];
  eq(counts(), [2, 2], '⑪ 推送页整页载入，两个缺名主播各查一次');
  for (const hash of ['#/push/7', '#/push/8', '#/push/default', '#/push']) {
    await page.enter(hash);
    eq(counts(), [2, 2], '⑪ 切换 ' + hash + ' 不重试失败的查名');
  }
  await page.refresh();
  eq(counts(), [2, 2], '⑪ 停在推送页整体刷新不重试失败的查名');
  await page.enter('#/home');
  await page.enter();
  eq(counts(), [4, 4], '⑪ 离开后重新进页，缺名主播可以再查');
  await page.discard();
  eq(counts(), [6, 6], '⑪ 放弃重取后，缺名主播可以再查一次');
  await page.refresh();
  eq(counts(), [6, 6], '⑪ 放弃后的整体刷新不重复查名');
}

// ⑫ 从首页进入推送页，补到名字才整页重画，重画时名字已全部写入。
for (const missingNames of [false, true]) {
  const page = await consolePage('#/home', false, missingNames);
  page.repaints.length = 0;
  await page.enter();
  eq(page.repaints, missingNames ? [[null, null]] : [[null, null], ['主播7', '主播8']],
    '⑫ 首页进入推送页，' + (missingNames ? '查名失败不追加整页重画' : '补名完成后整页重画恰好一次'));
  page.repaints.length = 0;
  await page.refresh();
  eq(page.repaints, [], '⑫ 已有名字或已试过时不追加整页重画');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
