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
const defaultThanksTemplates = {gift: '感谢 {用户} 赠送的 {礼物}', membership: '感谢 {用户} 开通{等级}',
  superChat: '感谢 {用户} 的醒目留言', follow: '感谢 {用户} 的关注', share: '感谢 {用户} 的分享直播间'};
const defaultWelcomeTemplates = {guardWelcome: '欢迎{等级} {用户} 进入直播间', listedWelcome: '欢迎 {用户} 进入直播间'};
const defaultPkTemplate = '本场PK对手：{对手}，粉丝 {粉丝}，大航海 {舰队}，高能 {高能}';
const blankSettings = {keywordRules: [], pkTemplate: '', welcome: {minimumLevel: 'CAPTAIN', guardTemplate: '', listedTemplate: '', people: []},enabled: false, perMinute: 6, features: {}, plans: [], thanks: {
  giftWindowSeconds: 8, giftIncludeFree: false, giftMinYuan: 0, followWindowSeconds: 30, shareWindowSeconds: 30, templates: {},
}};
const settingsByRoom = {};
const calls = [];
let thanksHistory = false;
let release = null;
let sendReply = {success: true, sent: true, message: '已发出：「你好」'};
let valuesReplies = [];
globalThis.__api = async (path, options) => {
  calls.push({path, method: (options && options.method) || 'GET', body: options && options.body ? JSON.parse(options.body) : null});
  if (path === '/interact/danmaku/state') return state;
  if (path.startsWith('/interact/danmaku/settings?uid=')) {
    const uid = path.split('=').pop();
    return {success: true, defaultThanksTemplates, defaultWelcomeTemplates, defaultPkTemplate, settings: structuredClone(settingsByRoom[uid] || blankSettings)};
  }
  if (path === '/interact/danmaku/settings') {
    const {uid, settings} = JSON.parse(options.body);
    settingsByRoom[uid] = settings;
    state.rooms.find(r => r.uid === uid).autoEnabled = settings.enabled;
    return {success: true, settings};
  }
  if (path.startsWith('/interact/danmaku/history?')) {
    const params = new URLSearchParams(path.split('?')[1]);
    if (thanksHistory) {
      const rows = Object.keys({...defaultThanksTemplates, ...defaultWelcomeTemplates, keywordReply: true, pkOpponent: true}).map(feature => ({
        time: 1795680000000, text: '示例记录', feature, outcome: 'SENT', reason: '已发出',
      }));
      return {success: true, rows, total: rows.length, pageSize: 20};
    }
    const filtered = params.get('outcome') === 'DROPPED';
    const text = filtered ? '迟到的弹幕' : params.get('page') === '2' ? '上一页的试发' : '第一条';
    return {success: true, rows: [{time: 1795680000000, text, feature: filtered ? 'timed' : 'test',
      outcome: filtered ? 'DROPPED' : 'SENT', reason: filtered ? '排队已满' : '已发出'}], total: filtered ? 1 : 21, pageSize: 20};
  }
  if (path.startsWith('/interact/danmaku/limit?uid=')) return {success: true, limit: path.endsWith('502') ? 30 : 20, fromPlatform: true};
  if (path === '/interact/danmaku/test') {
    await new Promise(resolve => { release = resolve; });
    if (sendReply instanceof Error) throw sendReply;
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
check('进页只读状态、上限、所选主播设置与记录',
  calls.every(call => call.method === 'GET' && ['/interact/danmaku/state', '/interact/danmaku/limit?uid=501',
    '/interact/danmaku/settings?uid=501', '/interact/danmaku/history?uid=501&page=1'].includes(call.path)), JSON.stringify(calls));
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

// 试发回包丢失时若显示「没发」，管理员可能点第二次，直播间里收到两条。
sendReply = new Error('连接中断');
byId('li-send').click();
await flush();
release();
await flush();
check('连接中断时引导管理员核实发送结果', byId('li-result').textContent.includes('不确定')
  && byId('li-result').textContent.includes('发送记录'), byId('li-result').textContent);

// 关着总开关还能编辑下方内容，或把甲的定时计划存到了乙的直播间。
check('总开关关闭时显示原因且设置置灰', byId('li-features').disabled && !byId('li-off-note').hidden);
byId('li-enabled').checked = true;
byId('li-enabled').dispatchEvent({type: 'change'});
byId('li-timed').checked = true;
byId('li-add').click();
const planRow = () => byId('li-plans').querySelector('.li-plan');
check('新计划默认十分钟、轮流、安静时跳过', Number(planRow().querySelector('.li-minutes').value) === 10
  && planRow().querySelector('.li-order').value === 'cycle' && planRow().querySelector('.li-quiet').checked);
planRow().querySelector('.li-lines').value = '你好，{主播}\n欢迎';
planRow().querySelector('.li-minutes').value = '3';
byId('li-save').click();
await flush();
check('保存后可见已生效与启用主播的名字', byId('li-settings-result').textContent.includes('已生效')
  && byId('li-summary').textContent.includes('示例主播'));
const saved = settingsByRoom['501'];
check('选中的主播有且只有一条计划', saved?.plans.length === 1 && saved.plans[0].minutes === 3
  && saved.plans[0].texts.length === 2 && saved.features.timed);
planRow().querySelector('.li-plan-on').checked = false;
byId('li-save').click();
await flush();
check('停用计划后写回同一条', settingsByRoom['501'].plans.length === 1 && !settingsByRoom['501'].plans[0].enabled);
planRow().querySelector('.li-remove').click();
byId('li-save').click();
await flush();
check('删除计划后保存了空列表', settingsByRoom['501'].plans.length === 0);
byId('li-settings-room').value = '502';
byId('li-settings-room').dispatchEvent({type: 'change'});
await flush();
check('切换到另一位主播后显示自己的开关', !byId('li-enabled').checked && byId('li-features').disabled);

// 想查之前的试发或被丢掉的弹幕，却只能看第一屏或筛不到原因。
byId('li-next').click();
await flush();
check('可翻到第二页的试发', byId('li-page').textContent.includes('2 / 2') && byId('li-history').textContent.includes('上一页的试发'));
byId('li-outcome').value = 'DROPPED';
byId('li-outcome').dispatchEvent({type: 'change'});
await flush();
check('筛选后回第一页并显示原因', byId('li-page').textContent.includes('1 / 1') && byId('li-history').textContent.includes('排队已满'));

// 切换主播后预览用了试发房间的上限，或改了话术和免费礼物开关却没有保存。
const thanksKeys = Object.keys(defaultThanksTemplates);
check('五种答谢都有独立开关且默认关闭', thanksKeys.every(key => byId('li-' + key) && !byId('li-' + key).checked));
if (byId('li-gift')) {
  byId('li-enabled').checked = true;
  byId('li-enabled').dispatchEvent({type: 'change'});
  byId('li-room').value = '501';
  byId('li-room').dispatchEvent({type: 'change'});
  await flush();
  check('预览显示当前设置房间的上限和默认话术', byId('li-gift-preview').textContent.includes('小花花x3')
    && byId('li-gift-count').textContent.includes('30 字'));
  byId('li-gift').checked = true;
  byId('li-giftIncludeFree').checked = true;
  byId('li-giftWindowSeconds').value = '5';
  byId('li-follow-template').value = '谢谢{礼物}';
  byId('li-follow-template').dispatchEvent({type: 'input'});
  check('无效占位符不会被预览成能发送的话术', byId('li-follow-count').textContent.includes('不能使用')
    && byId('li-follow-count').textContent.includes('{礼物}'));
  byId('li-follow-template').value = '';
  byId('li-follow-template').dispatchEvent({type: 'input'});
  byId('li-gift-template').value = '😀'.repeat(31) + '{用户}';
  byId('li-gift-template').dispatchEvent({type: 'input'});
  check('修改话术立即按字符数显示拆条', byId('li-gift-count').textContent.includes('33 字')
    && byId('li-gift-count').textContent.includes('2 条') && byId('li-gift-preview').textContent.endsWith('小明'));
  byId('li-save').click();
  await flush();
  check('子选项、话术和开关只存到所选主播', settingsByRoom['502']?.features.gift
    && settingsByRoom['502'].thanks.giftIncludeFree && settingsByRoom['502'].thanks.giftWindowSeconds === 5
    && settingsByRoom['502'].thanks.templates.gift.endsWith('{用户}') && !settingsByRoom['501'].features.gift);
}

// 管理员在记录里分不清是哪一种答谢。
thanksHistory = true;
byId('li-outcome').value = '';
byId('li-reload').click();
await flush();
check('五种答谢记录各有中文功能名', ['礼物答谢', '上舰答谢', '醒目留言答谢', '关注答谢', '分享答谢']
  .every(label => byId('li-history').textContent.includes(label)));

// 管理员配好了欢迎却发给另一房间，或长 UID 在浏览器里被舍入。
check('三种新功能各有开关且默认关闭', ['guardWelcome', 'listedWelcome', 'pauseActivities']
  .every(key => byId('li-' + key) && !byId('li-' + key).checked));
check('免登录的识别限制写在欢迎设置里', section.textContent.includes('监听账号') && section.textContent.includes('可能不起作用'));
check('两种欢迎记录各有中文名', ['欢迎大航海进房', '欢迎指定的人进房']
  .every(label => byId('li-history').textContent.includes(label)));
if (byId('li-person-add')) {
  byId('li-person-add').click();
  const person = () => section.querySelector('.li-person');
  person().querySelector('.li-uid').value = '9007199254740993';
  person().querySelector('.li-alias').value = '小红';
  person().querySelector('.li-person-template').value = '欢迎{用户}';
  byId('li-minimumLevel').value = 'COMMANDER';
  byId('li-listedWelcome').checked = true;
  byId('li-pauseActivities').checked = true;
  byId('li-guardWelcome-template').value = '{主播}欢迎{等级}{用户}';
  byId('li-guardWelcome-template').dispatchEvent({type: 'input'});
  check('欢迎预览使用所选房间的名字和字数上限', byId('li-guardWelcome-preview').textContent.includes('另一位欢迎')
    && byId('li-guardWelcome-count').textContent.includes('30 字'));
  byId('li-save').click();
  await flush();
  const room = settingsByRoom['502'];
  check('完整名单和子选项随所选房间保存', room.welcome.people[0]?.uid === '9007199254740993'
    && room.welcome.people[0]?.alias === '小红' && room.welcome.people[0]?.template === '欢迎{用户}'
    && room.welcome.minimumLevel === 'COMMANDER' && room.features.listedWelcome && room.features.pauseActivities
    && !settingsByRoom['501'].features.listedWelcome);
  person().querySelector('.li-person-remove').click();
  byId('li-save').click();
  await flush();
  check('删掉的人不再留在名单里', !settingsByRoom['502'].welcome.people.length);
}

// 管理员无法调整匹配顺序，刷新后规则丢失，或者预览和记录分不清两种弹幕。
check('关键词和 PK 开关默认关闭', ['keywordReply', 'pkOpponent'].every(k => byId('li-' + k) && !byId('li-' + k).checked));
check('记录标明关键词回复和 PK 播报', ['关键词回复', 'PK 对手信息播报'].every(label => byId('li-history').textContent.includes(label)));
if (byId('li-rule-add')) {
  const rows = () => section.querySelectorAll('.li-rule');
  const fill = (row, word, reply) => {
    row.querySelector('.li-keywords').value = word;
    const input = row.querySelector('.li-reply');
    input.value = reply;
    input.dispatchEvent({type: 'input'});
  };
  byId('li-keywordReply').checked = true;
  byId('li-rule-add').click();
  fill(rows()[0], '群号', '{用户}好');
  check('回复旁显示实际文字和字数', rows()[0].querySelector('.li-reply-preview').textContent === '小明好'
    && rows()[0].querySelector('.li-reply-count').textContent.includes('3 字'));
  rows()[0].querySelector('.li-reply-add').click();
  rows()[0].querySelectorAll('.li-reply')[1].value = '另一句';
  byId('li-rule-add').click();
  fill(rows()[1], '群', '{主播}的群');
  rows()[1].querySelector('.li-rule-up').click();
  rows()[0].querySelector('.li-rule-on').checked = false;
  byId('li-pkOpponent').checked = true;
  byId('li-pkOpponent-template').value = '{主播}遇到{对手}，粉丝 {粉丝}';
  byId('li-pkOpponent-template').dispatchEvent({type: 'input'});
  check('PK 预览使用示例数字和当前主播', byId('li-pkOpponent-preview').textContent.includes('另一位遇到')
    && byId('li-pkOpponent-preview').textContent.includes('12.3万'));
  byId('li-save').click();
  await flush();
  const savedRules = settingsByRoom['502'].keywordRules;
  check('规则顺序、启停、多句回复与 PK 话术都保存', savedRules.length === 2 && !savedRules[0].enabled
    && savedRules[0].keywords[0] === '群' && savedRules[1].replies.join('/') === '{用户}好/另一句'
    && settingsByRoom['502'].pkTemplate.includes('{粉丝}') && settingsByRoom['502'].features.pkOpponent);
  rows()[0].querySelector('.li-rule-remove').click();
  byId('li-save').click();
  await flush();
  check('删除只影响当前主播的所选规则', settingsByRoom['502'].keywordRules.length === 1 && !settingsByRoom['501'].keywordRules.length);
}

if (failures.length) {
  console.error('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
  failures.forEach(line => console.error('  ' + line));
  process.exit(1);
}
console.log('跑了 ' + checks + ' 格，全绿');
