// 哔哩哔哩连接卡只画自己那个账号。
//
// 抓的用户故障：装了发言账号之后，原来那张卡里多出一块「正在生成二维码，请稍候」——那是另一张卡的账号，
// 它平时不备二维码，这一句永远等不到；卡还因此每 3 秒刷一次，人停在连接页上就一直刷下去。
//
// 跑的是插件里真正交付的 bilibili.js：它 import 的几个控制台模块换成最小替身，接口回包由这里给。
import {readFileSync} from 'node:fs';

const root = new URL('../../../../../../', import.meta.url);
const {MiniNode} = await import(new URL('core/nova-core/src/test/resources/frontend/layout-dom.mjs', root).href);
// 卡片脚本按 data-* 认按钮属于哪个账号；替身页面没有 dataset，这里照浏览器的读法补上
Object.defineProperty(MiniNode.prototype, 'dataset', {
  get() {
    const node = this;
    return new Proxy({}, {
      get: (_, key) => node.getAttribute('data-' + String(key).replace(/[A-Z]/g, c => '-' + c.toLowerCase())) ?? undefined,
    });
  },
});

const encode = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
const STUBS = {
  'core.js': encode(`
    export const $ = selector => document.querySelector(selector);
    export async function api(path, options) { return globalThis.__api(path, options); }
    export function el(tag, cls) { const node = document.createElement(tag); if (cls) node.className = cls; return node; }
    export const esc = value => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  `),
  'confirm.js': encode('export async function ask() { return true; }'),
  'link-feedback.js': encode('export function showLinkResult(node, text) { if (node) node.textContent = text || ""; }'),
  'links.js': encode('export async function refreshLinks() {}'),
  'overview.js': encode('export function healthRows() { return ""; } export function refreshHome() {}'),
  'store.js': encode('export const store = {tab: "bot"};'),
};
const source = readFileSync(new URL('plugins/nova-bilibili/src/main/resources/config-ui-pages/bilibili.js', root), 'utf8')
  .replace(/from '\.\/([^']+)'/g, (_, file) => {
    if (!STUBS[file]) throw new Error('bilibili.js 新引了 ' + file + '，夹具得补一个替身');
    return 'from ' + JSON.stringify(STUBS[file]);
  });

const timers = [];
globalThis.setTimeout = (fn, ms) => { timers.push(ms); return timers.length; };
globalThis.clearTimeout = () => {};

const EXTRA = {platform: 'bilibili', id: 'bilibili-speaker', displayName: '另一个账号', optional: true,
  onDemand: true, loggedIn: false, accountId: null};
const MAIN = {platform: 'bilibili', id: 'bilibili', displayName: '哔哩哔哩', loggedIn: true, accountId: '12345678'};
const calls = [];
globalThis.__api = async (path, options) => {
  calls.push({path, options});
  if (path === '/login') return {success: true, accounts: [EXTRA, MAIN]};
  if (path === '/login/logout') return {success: true, message: '已退出登录'};
  throw new Error('意外请求：' + path);
};
const flush = () => new Promise(resolve => setImmediate(resolve));

const card = await import(encode(source));
const section = document.createElement('div');
document.body.appendChild(section);
card.render(section);
card.refresh();
await flush();
await flush();

const failures = [];
let checks = 0;
function check(name, ok, detail) {
  checks++;
  if (!ok) failures.push(name + (detail ? '：' + detail : ''));
}

const blocks = section.querySelectorAll('.account');
check('卡里只有自己那个账号', blocks.length === 1, '画出了 ' + blocks.length + ' 块');
check('不替另一个账号说「正在生成二维码」', !section.textContent.includes('正在生成二维码'), section.textContent);
check('自己的账号已登录时不轮询', timers.length === 0, '排了 ' + timers.length + ' 次定时刷新');

const button = section.querySelectorAll('button')[0];
check('已登录的账号有退出按钮', !!button);
if (button) {
  button.click();
  await flush();
  await flush();
  const logout = calls.find(call => call.path === '/login/logout');
  const body = logout ? JSON.parse(logout.options.body) : {};
  check('退出登录发的是自己的账号', !!logout && (body.id || body.platform) === 'bilibili'
    && body.id !== EXTRA.id, JSON.stringify(body));
}

if (failures.length) {
  console.error('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
  failures.forEach(line => console.error('  ' + line));
  process.exit(1);
}
console.log('跑了 ' + checks + ' 格，全绿');
