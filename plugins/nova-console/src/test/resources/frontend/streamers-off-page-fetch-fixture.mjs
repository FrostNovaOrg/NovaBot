/**
 * 主播页只认自己的地址：推送页那类三段地址不得被读成一位主播、拿去问接口
 *
 * 宿主整体载入与首页重画会把所有插件页各刷一遍（refreshPages），落在推送页上时
 * 主播页也被叫到。从前它的解析不看第一段，把 #/push/&lt;uid&gt;/&lt;群号&gt; 的后两段
 * 读成平台与 uid，每开一次推送页白问两趟 /streamers/&lt;uid&gt;/&lt;群号&gt;，两头都是 404。
 *
 * 本尺真跑：假 DOM 与只记账的假 fetch 上把这一页整页拉起来，照宿主那两个口子
 * （整体载入一遍、首页重画一遍）各叫一次 refresh，数这一页发出去的请求。
 * 落在主播页自己地址上的两格是对照：那一趟照常要取，否则「一概不取」也绿。
 *
 * 由 StreamersOffPageFetchTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const here = dirname(fileURLToPath(import.meta.url));
const pages = join(here, '../../../main/resources/config-ui-pages');

const failures = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假 DOM：$() 按选择器给同一个节点，innerHTML 只是摆着的字符串，不解析 ——

function fakeElement(tag) {
  const node = {
    tagName: String(tag).toLowerCase(),
    className: '', id: '', type: '', textContent: '', innerHTML: '',
    hidden: false, disabled: false,
    attrs: {}, dataset: {}, style: {},
    children: [], listeners: {}, found: {}, parentNode: null,
  };
  node.appendChild = child => {
    node.children.push(child);
    child.parentNode = node;
    return child;
  };
  Object.defineProperty(node, 'lastChild',
    {get: () => (node.children.length ? node.children[node.children.length - 1] : null)});
  node.addEventListener = (kind, fn) => {
    (node.listeners[kind] = node.listeners[kind] || []).push(fn);
  };
  node.setAttribute = (key, value) => { node.attrs[key] = String(value); };
  node.getAttribute = key => node.attrs[key];
  node.querySelector = sel => node.found[sel] || (node.found[sel] = fakeElement('input'));
  node.classList = {toggle() {}, add() {}, remove() {}, contains() { return false; }};
  return node;
}

const byId = {};
const node = id => byId[id] || (byId[id] = fakeElement('div'));

globalThis.document = {
  querySelector: sel => node(String(sel).replace(/^#/, '')),
  getElementById: id => byId[id] || null,
  createElement: fakeElement,
  createElementNS: (ns, tag) => fakeElement(tag),
  head: fakeElement('head'),
  body: fakeElement('body'),
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  addEventListener() {},
  get activeElement() { return null; },
};
globalThis.location = {hash: ''};

// fetch 只记账：这一尺量的就是「有没有发出去」。回一份空对象让页面自己走完，
// 不真答数据——答什么都会走到渲染深处，那里的形态另有尺在量
const asked = [];
globalThis.fetch = url => {
  asked.push(String(url));
  return Promise.resolve({status: 200, json: () => Promise.resolve({})});
};

const tick = () => new Promise(done => setTimeout(done, 0));

let page = null;
let model = null;
try {
  model = await import(join(pages, 'streamers-model.js'));
  page = await import(join(pages, 'streamers.js'));
  page.render(fakeElement('section'));
} catch (err) {
  failures.push('载入 streamers 那两份或建页就抛了：' + err.message);
}

/** 主播页没载进来时，量它的几格无从量起，照实报而不让报一句 null */
function needPage() {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
}

await ask('① 解析认第一段：推送页的三段地址退回列表，不读出平台与 uid', () => {
  needPage();
  same(model.parseStreamersHash('#/push/9200000001/910000001'),
    {view: 'list', platform: '', uid: '', pane: 'overview', start: '', page: 1, period: 'week'},
    '推送页的地址');
});

await ask('② 宿主整体载入把各插件页各刷一遍时，主播页在推送页的地址上一趟也不发', async () => {
  needPage();
  asked.length = 0;
  globalThis.location.hash = '#/push/9200000001/910000001';
  page.refresh();
  await tick();
  await tick();
  same(asked, [], '推送页的地址上主播页发出的请求');
});

await ask('③ 首页重画又叫一遍 refreshPages，仍是零', async () => {
  needPage();
  asked.length = 0;
  page.refresh();
  await tick();
  await tick();
  same(asked, [], '同一地址上第二遍发出的请求');
});

await ask('④ 对照：落在主播页自己的地址上照常取那一位', async () => {
  needPage();
  asked.length = 0;
  globalThis.location.hash = '#/streamers/bilibili/3493';
  page.refresh();
  await tick();
  await tick();
  same(asked.includes('/config/api/streamers/bilibili/3493'), true, '详情那一趟');
});

await ask('⑤ 对照：光秃秃的 #/streamers 照常取列表', async () => {
  needPage();
  asked.length = 0;
  globalThis.location.hash = '#/streamers';
  page.refresh();
  await tick();
  await tick();
  same(asked.includes('/config/api/streamers'), true, '列表那一趟');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
