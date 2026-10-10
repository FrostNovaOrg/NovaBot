/**
 * 主播详情的场次列表：每一行都有一个指向这一场的链接
 *
 * 用户那头的故障：场次表只有鼠标点得开——整行挂着点击，行里全是文字格，没有链接、
 * 没有 tabindex。只用键盘的人按 Tab 会把全部场次跳过去，哪一场的报告都打不开。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，主播页的建页、地址解析、取详情、画场次页签走的都是产品码。
 * 画出来的每一行，在它的那几格里找 <a href>，地址要等于主播页拼某一场地址的那一份
 * （streamers-model.js 的 sessionHash）算出来的。整行点照旧跳到同一处，是对照格。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。由 StreamersSessionLinkTest 拉起。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');

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

// —— 假 DOM：innerHTML 只是摆着的字符串，链接从字符串里读 ——

const rows = [];

function fakeElement(tag) {
  const node = {
    tagName: String(tag).toLowerCase(),
    className: '', id: '', type: '', textContent: '', innerHTML: '', href: '', title: '',
    hidden: false, disabled: false,
    attrs: {}, dataset: {}, style: {},
    children: [], listeners: {}, parentNode: null,
  };
  node.appendChild = child => {
    node.children.push(child);
    child.parentNode = node;
    return child;
  };
  node.insertBefore = child => node.appendChild(child);
  Object.defineProperty(node, 'firstChild', {get: () => node.children[0] || null});
  node.remove = () => {};
  node.addEventListener = (kind, fn) => {
    (node.listeners[kind] = node.listeners[kind] || []).push(fn);
  };
  node.setAttribute = (key, value) => { node.attrs[key] = String(value); };
  node.getAttribute = key => node.attrs[key];
  node.querySelector = () => null;
  node.classList = {toggle() {}, add() {}, remove() {}, contains() { return false; }};
  if (node.tagName === 'tr') rows.push(node);
  return node;
}

const byId = {};
const node = id => byId[id] || (byId[id] = fakeElement('div'));

globalThis.document = {
  querySelector: sel => {
    const id = String(sel).replace(/^#/, '');
    return id === 'sd-banner' ? null : node(id);
  },
  getElementById: id => byId[id] || null,
  createElement: fakeElement,
  createElementNS: (ns, tag) => fakeElement(tag),
  createTextNode: text => Object.assign(fakeElement('#text'), {textContent: String(text)}),
  head: fakeElement('head'),
  body: fakeElement('body'),
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  addEventListener() {},
  get activeElement() { return null; },
};
globalThis.location = {hash: ''};

const PLATFORM = 'bilibili';
const UID = 3493;
const STARTS = [1760000000000, 1759900000000, 1759800000000];

const sessions = STARTS.map((start, i) => ({
  platform: PLATFORM, uid: UID, startTime: start, endTime: start + 3600000,
  durationSeconds: 3600, title: '第 ' + (i + 1) + ' 场', metrics: {}, interrupted: false,
}));

globalThis.fetch = url => {
  const path = String(url);
  if (path.startsWith('/config/api/streamers/' + PLATFORM + '/' + UID)) {
    return Promise.resolve({status: 200, json: () => Promise.resolve({
      success: true, platform: PLATFORM, uid: UID, uname: '甲',
      sessions: {items: sessions, metrics: [], total: sessions.length, page: 1, pageSize: 20},
    })});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({})});
};

const tick = () => new Promise(done => setTimeout(done, 0));

let page = null;
let model = null;
try {
  model = await import(join(pages, 'streamers-model.js'));
  page = await import(join(pages, 'streamers.js'));
  page.render(fakeElement('section'));
  globalThis.location.hash = model.detailHash(PLATFORM, UID, 'sessions');
  page.refresh();
  for (let i = 0; i < 6; i++) await tick();
} catch (err) {
  failures.push('载入主播页或画场次页签就抛了：' + err.message);
}

/** 一行里写着的那些 <a href="…">，按出现的先后 */
function linksIn(row) {
  const out = [];
  const re = /<a\b[^>]*\bhref\s*=\s*"([^"]*)"/gi;
  let match;
  while ((match = re.exec(String(row.innerHTML)))) out.push(match[1].replace(/&amp;/g, '&'));
  return out;
}

const want = () => STARTS.map(start => model.sessionHash(PLATFORM, UID, start));

// 故障：只用键盘的人 Tab 过场次表一格都停不下，哪一场都打不开
await ask('① 画出来的每一行都有一个链接，地址指向这一场', () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  if (rows.length !== STARTS.length) throw new Error('该画 ' + STARTS.length + ' 行，画了 ' + rows.length + ' 行');
  same(rows.map(linksIn), want().map(hash => [hash]), '每一行里的链接');
  same(want()[0], '#/streamers/bilibili/3493/1760000000000', '第一场的地址');
});

// 对照：整行点照旧能用，跳的是同一处
await ask('② 对照：整行点一下照旧跳到这一场', () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  const reached = rows.map(row => {
    globalThis.location.hash = '';
    for (const fn of row.listeners.click || []) fn({target: row});
    return globalThis.location.hash;
  });
  same(reached, want(), '点整行之后的地址');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
