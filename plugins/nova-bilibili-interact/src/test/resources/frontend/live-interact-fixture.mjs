// 「直播间互动」页。
//
// 抓的用户故障：打开页面就往直播间发了东西；点一次「发送」发出去两条，或者结果回来之前还能再点；
// 页面上没有「直播间里所有人都看得见」的提醒；选「和监听直播间用的同一个账号」时没人提醒它会连累监听，
// 或者没确认就存了；字数上限和已输入字数不显示。
//
// 跑的是插件里真正交付的 live-interact.js：它 import 的几个控制台模块换成最小替身，接口回包由这里给。
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
    export async function api(path, options) { return globalThis.__api(path, options); }
    export const esc = value => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  `),
  'confirm.js': encode('export async function ask(spec) { globalThis.__asked.push(spec); return globalThis.__answer; }'),
  'store.js': encode('export const store = {tab: "live-interact", values: {}};'),
};
const source = readFileSync(new URL('plugins/nova-bilibili-interact/src/main/resources/config-ui-pages/live-interact.js', root), 'utf8')
  .replace(/from '\.\/([^']+)'/g, (_, file) => {
    if (!STUBS[file]) throw new Error('live-interact.js 新引了 ' + file + '，夹具得补一个替身');
    return 'from ' + JSON.stringify(STUBS[file]);
  });

let timers = 0;
globalThis.setTimeout = () => { timers++; return timers; };
globalThis.setInterval = () => { timers++; return timers; };
globalThis.clearTimeout = () => {};
globalThis.__asked = [];
globalThis.__answer = true;

const KEY = 'novabot.bilibili.interact.danmaku.account';
let state = {
  success: true, choice: 'speaker', settingKey: KEY,
  choices: [{value: 'speaker', label: '单独的发言账号'}, {value: 'listener', label: '和监听直播间用的同一个账号'}],
  account: {usable: false, failure: 'NO_ACCOUNT', name: '发言账号', uid: null,
    reason: '选的是单独的发言账号，但还没有登录发言账号，这条没发。请到「连接」页的「哔哩哔哩发言账号」卡上扫码登录'},
  rooms: [{uid: '501', name: '示例主播', roomId: '1001'}, {uid: '502', name: '另一位', roomId: '1002'}],
  minInterval: 3, pauseMinutes: 5, defaultLimit: 20,
};
const calls = [];
let release = null;
let sendReply = {success: true, sent: true, message: '已发出：「你好」'};
let valuesReplies = [];
globalThis.__api = async (path, options) => {
  calls.push({path, method: (options && options.method) || 'GET', body: options && options.body ? JSON.parse(options.body) : null});
  if (path === '/interact/danmaku/state') return state;
  if (path.startsWith('/interact/danmaku/limit?uid=')) return {success: true, limit: path.endsWith('502') ? 30 : 20, fromPlatform: true};
  if (path === '/interact/danmaku/test') {
    await new Promise(resolve => { release = resolve; });
    return sendReply;
  }
  if (path.startsWith('/values')) return valuesReplies.shift() || {success: true};
  throw new Error('意外请求：' + path);
};
const flush = async () => { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); };

const page = await import(encode(source));
const section = document.createElement('section');
section.id = 'page-live-interact';
document.body.appendChild(section);
page.render(section);

const failures = [];
let checks = 0;
function check(name, ok, detail) {
  checks++;
  if (!ok) failures.push(name + (detail ? '：' + detail : ''));
}
const byId = id => section.querySelector('#' + id);
const radios = () => section.querySelectorAll('[data-li-choice]');
const radio = value => radios().find(node => node.getAttribute('data-li-choice') === value);
const sends = () => calls.filter(call => call.path === '/interact/danmaku/test');

// —— 进页 ——
await page.refresh();
await flush();
check('页面上有「所有人都看得见」的提醒', byId('li-reminder').textContent.includes('试发的内容直播间里所有人都看得见'));
check('两个选项都在', radios().length === 2);
check('选中的是当前的选项', radio('speaker').checked === true && radio('listener').checked !== true);
check('选同一个账号旁边写着风险',
  section.textContent.includes('这个号如果因为发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响'));
check('账号不能用时写出原因', byId('li-status').textContent.includes('不能用') && byId('li-status').textContent.includes('连接'),
  byId('li-status').textContent);
check('主播下拉里有已添加的主播', byId('li-room').innerHTML.includes('示例主播') && byId('li-room').innerHTML.includes('房间 1001'));
check('进页不发弹幕', sends().length === 0, JSON.stringify(calls));
check('进页只问状态和选中那位的字数上限',
  calls.every(call => call.path === '/interact/danmaku/state' || call.path === '/interact/danmaku/limit?uid=501'), JSON.stringify(calls));
check('不轮询', timers === 0, '排了 ' + timers + ' 次定时器');

// —— 字数 ——
byId('li-text').value = '你好呀';
byId('li-text').dispatchEvent({type: 'input'});
check('显示已输入字数与上限', byId('li-count').textContent.includes('已输入 3 字') && byId('li-count').textContent.includes('最多 20 字'),
  byId('li-count').textContent);
byId('li-room').value = '502';
byId('li-room').dispatchEvent({type: 'change'});
await flush();
check('换了主播就换这个房间的上限', byId('li-count').textContent.includes('最多 30 字'), byId('li-count').textContent);
check('超出上限时字数标红', (() => {
  byId('li-text').value = '一'.repeat(31);
  byId('li-text').dispatchEvent({type: 'input'});
  return byId('li-count').classList.contains('over');
})());
byId('li-text').value = '你好';
byId('li-text').dispatchEvent({type: 'input'});

// —— 试发：点两下只发一条，结果回来之前按钮是灰的 ——
byId('li-send').click();
byId('li-send').click();
byId('li-text').dispatchEvent({type: 'keydown', key: 'Enter', preventDefault() {}});
await flush();
check('点了才发，点三下只发一条', sends().length === 1, '发了 ' + sends().length + ' 条');
check('发的是选中的主播与写的字', sends()[0] && sends()[0].body.uid === '502' && sends()[0].body.text === '你好',
  JSON.stringify(sends()[0]));
check('结果回来之前不能再点', byId('li-send').disabled === true);
release();
await flush();
check('结果用一句人话显示', byId('li-result').textContent.includes('已发出'), byId('li-result').textContent);
check('结果回来之后又能点', byId('li-send').disabled === false);

sendReply = {success: true, sent: false, uncertain: false, failure: 'TOO_FAST', failureText: '发得太快或重复',
  message: '同一个账号两条弹幕之间至少隔 3 秒，这条来得太快，没发（不排队）。稍等再发'};
byId('li-send').click();
await flush();
release();
await flush();
check('没发出时写清原因', byId('li-result').textContent.includes('没发出') && byId('li-result').textContent.includes('3 秒'),
  byId('li-result').textContent);
check('没发出不自动重发', sends().length === 2, '发了 ' + sends().length + ' 条');

// —— 改成同一个账号：服务端要先确认，取消就不存 ——
const confirmReply = {success: false, message: '未保存：有改动需要先确认',
  confirm: [{name: KEY, title: '发弹幕用哪个账号', body: '这个号如果因为发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响。确定用监听直播间的账号发弹幕吗？'}]};
valuesReplies = [confirmReply];
globalThis.__answer = false;
radio('listener').checked = true;
radio('listener').dispatchEvent({type: 'change'});
await flush();
const valueCalls = () => calls.filter(call => call.path.startsWith('/values'));
check('改选项走设置保存，存的是这一项', valueCalls().length === 1 && valueCalls()[0].body[KEY] === 'listener'
  && valueCalls()[0].method === 'POST', JSON.stringify(valueCalls()));
check('改成监听账号先问一句风险', globalThis.__asked.length === 1
  && String(globalThis.__asked[0].body).includes('会一起受影响'), JSON.stringify(globalThis.__asked));
check('取消就不存', valueCalls().length === 1 && !valueCalls().some(call => call.path.includes('confirmed=true')));
check('取消后仍选着原来的', radio('speaker').checked === true, String(radio('speaker').checked));

valuesReplies = [confirmReply, {success: true, changed: 1, restartRequired: []}];
globalThis.__answer = true;
state = {...state, choice: 'listener', account: {usable: true, name: '监听账号', uid: '42'}};
radio('listener').checked = true;
radio('listener').dispatchEvent({type: 'change'});
await flush();
check('确认后带着 confirmed 再存一次', valueCalls().length === 3 && valueCalls()[2].path === '/values?confirmed=true',
  JSON.stringify(valueCalls()));
check('存好后说明当场生效', byId('li-save-result').textContent.includes('不用重启'), byId('li-save-result').textContent);
check('存好后显示新账号能用', byId('li-status').textContent.includes('监听账号') && byId('li-status').textContent.includes('能用'),
  byId('li-status').textContent);
check('全程没有多发弹幕', sends().length === 2);

if (failures.length) {
  console.error('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
  failures.forEach(line => console.error('  ' + line));
  process.exit(1);
}
console.log('跑了 ' + checks + ' 格，全绿');
