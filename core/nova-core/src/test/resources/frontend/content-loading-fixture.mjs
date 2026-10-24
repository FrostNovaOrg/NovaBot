// 用户故障：换页后取数还没完成，卡片内容区空着，没有任何加载提示。
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {MiniNode, bracedFrom} from './layout-dom.mjs';
import {targetOptions} from '../../../main/resources/config-ui/links-model.js';

const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const plugin = new URL('../../../../../../plugins/nova-console/src/main/resources/config-ui-pages/', import.meta.url);
const read = (name, root = ui) => readFileSync(new URL(name, root), 'utf8');
const failures = [];
let checks = 0;
function check(label, fn) {
  checks++;
  try { fn(); } catch (error) { failures.push(label + ': ' + error.message); }
}
function compile(source, marker, deps) {
  const code = bracedFrom(source, marker).replace(/^export /, '');
  return new Function(...Object.keys(deps), code + '\nreturn ' + marker.match(/function (\w+)/)[1])(...Object.values(deps));
}
const esc = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;');
const core = read('core.js');
const loadingHtml = core.includes('export function loadingHtml(')
  ? compile(core, 'export function loadingHtml(', {esc}) : undefined;
const clearLoading = core.includes('export function clearLoading(')
  ? compile(core, 'export function clearLoading(', {}) : undefined;
MiniNode.prototype.remove = function () {
  if (!this.parentNode) return;
  this.parentNode.children = this.parentNode.children.filter(node => node !== this);
  if (!this.parentNode.children.length) this.parentNode._html = '';
};
const el = (tag, cls) => { const node = new MiniNode(tag); node.className = cls || ''; return node; };
const root = new MiniNode('main');
root.innerHTML = read('index.html').split('<main')[1]?.split('</main>')[0].replace(/^[^>]*>/, '') || '';
const $ = selector => root.querySelector(selector);
const hasLoading = node => assert.ok(node?.querySelector('.content-loading'), '内容区缺少加载占位');

if (process.argv[2] === 'cards') {
  for (const id of ['linkmap', 'now-box', 'home-health-summary', 'probe-list', 'home-timeline', 'cfg-path', 'setup-main']) {
    check(id, () => {
      hasLoading($('#' + id));
      assert.ok($('#' + id).querySelector('[role="status"]'), '读屏缺少加载状态');
    });
  }
  check('插件主播详情', () => {
    const nodes = new Map();
    const get = key => { if (!nodes.has(key)) nodes.set(key, new MiniNode('div')); return nodes.get(key); };
    compile(read('streamers.js', plugin), 'export function syncStreamersView(', {
      $: get, closeDetailDrawer() {}, releaseComponents() {}, kpiResizeObserver: null, recentResizeObserver: null,
      viewGeneration: 0, stopLiveRefresh() {}, parseStreamersHash: () => ({view: 'detail', platform: 'site', uid: '1'}),
      location: {}, where: {view: 'list'}, detail: null, insightsData: null, periodAudience: null, loadingHtml,
    })();
    hasLoading(get('#sd-head')); hasLoading(get('#sd-body'));
    assert.match(get('#sd-head').textContent, /正在加载主播资料/);
    assert.match(get('#sd-body').textContent, /正在读取该主播的运营数据/);
  });
  check('插件推送配置', () => {
    const host = new MiniNode('div');
    compile(read('push.js', plugin), 'function paintRight(', {
      $: () => host, onRevenueDraftChange() {}, lostChannelOf: '', picked: {uid: '1'},
      pushData: [{uid: '1', _summary: true}], el, loadingHtml,
    })();
    hasLoading(host); assert.match(host.textContent, /主播配置载入中/);
  });
  for (const error of ['', '连接失败']) check('插件平台清单 ' + error, () => {
    const host = new MiniNode('div');
    compile(read('setup-streamer.js', plugin), 'export function render(', {
      seen: {}, result: null, el, draft: {}, store: {platforms: null, platformsError: error},
      STREAMER_INPUT_HINT: 'UID', loadingHtml,
      note: (kind, text) => { const node = el('div', kind); node.textContent = text; return node; },
      platformsFailedText: reason => '直播平台列表获取失败：' + reason,
    })(host, {});
    if (error) { assert.equal(host.querySelector('.content-loading'), null); assert.match(host.textContent, /获取失败/); }
    else { hasLoading(host); assert.match(host.textContent, /正在加载直播平台列表/); }
  });
} else if (process.argv[2] === 'requests') {
  for (const fail of [false, true]) {
    let resolve, reject;
    const pending = new Promise((ok, no) => { resolve = ok; reject = no; });
    const loadTargets = compile(read('links.js'), 'export async function loadTargets(', {
      $, api: () => pending, options: [], targetOptions, esc, loadingHtml,
    });
    const task = loadTargets(false);
    check('目标名单等待中', () => hasLoading($('#test-hint')));
    if (fail) reject(new Error('断网')); else resolve({groups: [], friends: []});
    await task;
    check('目标名单完成 ' + fail, () => {
      assert.equal($('#test-hint').querySelector('.content-loading'), null);
      assert.match($('#test-hint').textContent, fail ? /机器人尚未连接/ : /请先将机器人加入群组/);
      if (fail) assert.match($('#test-target').innerHTML, /群组与好友列表加载失败/);
    });
  }
  let reject;
  const pending = new Promise((ok, no) => { reject = no; });
  const setup = compile(read('setup.js'), 'export async function openSetup(', {
    $, api: () => pending, loadOptions() {}, loadingHtml,
    note: (kind, text) => { const node = el('div', kind); node.textContent = text; return node; },
  });
  const task = setup();
  check('向导等待中', () => hasLoading($('#setup-main')));
  reject(new Error('断网'));
  await task;
  check('向导失败', () => {
    assert.equal($('#setup-main').querySelector('.content-loading'), null);
    assert.match($('#setup-main').textContent, /加载失败：断网/);
  });
  let message;
  const home = compile(read('overview.js'), 'export async function refreshHome(', {
    $, api: () => Promise.reject(new Error('断网')), store: {role: 'MEMBER'}, today: () => '2026-10-24',
    clearLoading, loadingHtml, say: text => { message = text; },
  });
  await home();
  check('首页失败', () => {
    assert.equal($('#page-home').querySelector('.content-loading'), null);
    assert.match(message, /首页加载失败：断网/);
  });
  const retry = home();
  check('首页失败后重试仍有加载反馈', () => hasLoading($('#now-box')));
  await retry;
  let rejectUser;
  const userPending = new Promise((ok, no) => { rejectUser = no; });
  const host = el('div');
  const paintRight = compile(read('push.js', plugin), 'function paintRight(', {
    $: () => host, onRevenueDraftChange() {}, lostChannelOf: '', picked: {uid: '1'},
    pushData: [{uid: '1', _summary: true}], el, loadingHtml,
  });
  const showPush = compile(read('push.js', plugin), 'export async function showPush(', {
    $: () => host, parseChannelTail: () => ({}), picked: {}, expanded: new Set(), pushData: [{uid: '1', _summary: true}],
    renderStreamers: paintRight,
    loadUser: () => userPending, clearLoading, say: text => { message = text; },
  });
  const pushTask = showPush('1', '');
  rejectUser(new Error('主播配置载入失败'));
  await pushTask;
  check('推送配置失败', () => {
    assert.equal(!!host.querySelector('.content-loading'), false, '失败后加载占位仍未收起');
    assert.match(message, /主播配置载入失败/);
  });
} else {
  const css = read('app.css');
  check('快速响应不闪占位', () => {
    assert.match(css, /visibility:hidden/);
    assert.match(css, /content-loading-reveal\s+0s\s+(?:\.15s|150ms)\s+forwards/);
  });
  check('减少动态效果停止流光', () => assert.match(css,
    /@media\s*\(prefers-reduced-motion:reduce\)\s*\{[^}]*\.content-loading[^}]*animation:none/s));
  check('只从设计变量取色', () => {
    const block = css.match(/\.content-loading-bar\{[^}]+\}/)?.[0] || '';
    assert.match(block, /var\(--line\)/); assert.match(block, /var\(--surface\)/);
    assert.doesNotMatch(block, /#[\da-f]{3,8}|rgba?\(|hsla?\(/i);
  });
  check('读屏保留加载文字，条纹不读', () => {
    assert.equal(typeof loadingHtml, 'function');
    const node = el('div'); node.innerHTML = loadingHtml('正在加载…');
    assert.match(node.textContent, /正在加载/);
    assert.equal(node.querySelector('.content-loading').getAttribute('role'), 'status');
    assert.equal(node.querySelector('.content-loading-bars').getAttribute('aria-hidden'), 'true');
  });
}
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
for (const failure of failures) console.log(failure);
process.exitCode = failures.length ? 1 : 0;
