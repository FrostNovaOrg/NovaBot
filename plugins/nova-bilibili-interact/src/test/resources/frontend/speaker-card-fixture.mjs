// 发言账号连接卡。
//
// 抓的用户故障：没登录发言账号时，卡却在后台一直轮询、或者自己去要二维码；点「扫码登录」没反应；
// 被拒的理由（扫出来的是监听账号）不出现在卡上；点这张卡上的「退出登录」退掉的是监听账号。
//
// 跑的是插件里真正交付的 bilibili-speaker.js：它 import 的几个控制台模块换成最小替身，接口回包由这里给。
import {readFileSync} from 'node:fs';

const root = new URL('../../../../../../', import.meta.url);
const {MiniNode} = await import(new URL('core/nova-core/src/test/resources/frontend/layout-dom.mjs', root).href);
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
  'links.js': encode('export async function refreshLinks() { globalThis.__links = (globalThis.__links || 0) + 1; }'),
  'store.js': encode('export const store = {tab: "bot"};'),
};
const source = readFileSync(new URL('plugins/nova-bilibili-interact/src/main/resources/config-ui-pages/bilibili-speaker.js', root), 'utf8')
  .replace(/from '\.\/([^']+)'/g, (_, file) => {
    if (!STUBS[file]) throw new Error('bilibili-speaker.js 新引了 ' + file + '，夹具得补一个替身');
    return 'from ' + JSON.stringify(STUBS[file]);
  });

let timers = [];
globalThis.setTimeout = (fn, ms) => { timers.push({fn, ms}); return timers.length; };
globalThis.clearTimeout = () => {};

const MAIN = {platform: 'bilibili', id: 'bilibili', displayName: '哔哩哔哩', loggedIn: true, accountId: '42'};
let speaker = {platform: 'bilibili', id: 'bilibili-speaker', displayName: '发言账号', optional: true, onDemand: true,
  loggedIn: false, accountId: null, notice: null};
const calls = [];
globalThis.__api = async (path, options) => {
  calls.push({path, body: options && options.body ? JSON.parse(options.body) : null});
  if (path === '/login') return {success: true, accounts: [MAIN, speaker]};
  if (path === '/login/start') return {success: true, message: '正在获取二维码，请稍候'};
  if (path === '/login/logout') return {success: true, message: '已退出登录，本机保存的凭据已清除'};
  throw new Error('意外请求：' + path);
};
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };

const card = await import(encode(source));
const section = document.createElement('div');
document.body.appendChild(section);
card.render(section);

const failures = [];
let checks = 0;
function check(name, ok, detail) {
  checks++;
  if (!ok) failures.push(name + (detail ? '：' + detail : ''));
}
const buttons = () => section.querySelectorAll('button');
const button = kind => buttons().find(b => b.getAttribute('data-speaker') === kind);

// —— 从没登录过 ——
card.refresh();
await flush();
check('没登录：有「扫码登录」按钮', !!button('start'));
check('没登录：没有退出按钮', !button('logout'));
check('没登录：不轮询', timers.length === 0, '排了 ' + timers.length + ' 次定时刷新');
check('没登录：除了取一次状态，不发别的请求', calls.every(call => call.path === '/login'), JSON.stringify(calls));
check('卡上有风险提醒', section.textContent.includes('可能被平台限制') && section.textContent.includes('有权管理'));

// —— 点「扫码登录」 ——
speaker = {...speaker, qrCode: 'iVBORw0KGgo='};
button('start').click();
await flush();
const start = calls.find(call => call.path === '/login/start');
check('点了才发起扫码，而且发的是自己的标识', !!start && start.body.id === 'bilibili-speaker', JSON.stringify(start));
check('二维码摆出来了', section.querySelectorAll('img').length === 1);
check('等扫码时轮询', timers.length > 0);

// —— 被拒 ——
timers = [];
speaker = {...speaker, qrCode: null, notice: '扫码登录的是监听直播间用的那个账号。这次登录已作废，请换一个账号扫码'};
card.refresh();
await flush();
check('被拒的理由写在卡上', section.textContent.includes('监听直播间用的那个账号'), section.textContent);
check('被拒之后不再轮询', timers.length === 0, '排了 ' + timers.length + ' 次定时刷新');
timers = [];

// —— 已登录、点退出 ——
speaker = {...speaker, notice: null, loggedIn: true, accountId: '7'};
card.refresh();
await flush();
check('已登录：不轮询', timers.length === 0);
check('已登录：有退出按钮', !!button('logout'));
button('logout').click();
await flush();
const logout = calls.find(call => call.path === '/login/logout');
check('退出登录只发自己的标识', !!logout && logout.body.id === 'bilibili-speaker', JSON.stringify(logout));

// —— 登录过、后来失效：探针在 ——
speaker = {...speaker, loggedIn: false, accountId: null};
card.status({health: [{card: 'bilibili-speaker', scope: 'PLATFORM', level: 'DOWN', loginState: true}]});
card.refresh();
await flush();
check('失效后：可重新扫码，也可退出清掉凭据', !!button('start') && !!button('logout'));

if (failures.length) {
  console.error('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
  failures.forEach(line => console.error('  ' + line));
  process.exit(1);
}
console.log('跑了 ' + checks + ' 格，全绿');
