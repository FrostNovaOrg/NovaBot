import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {installPushDom} from './push-test-dom.mjs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
const {idNode} = installPushDom();
document.getElementById = idNode;
const create = document.createElement;
document.createElement = tag => {
  const node = create(tag);
  node.dataset = {};
  Object.defineProperty(node, 'childNodes', {get: () => node.children});
  const html = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), 'innerHTML');
  Object.defineProperty(node, 'innerHTML', {get: () => html.get.call(node), set: value => {
    node._text = ''; html.set.call(node, value);
  }});
  const content = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), 'textContent');
  Object.defineProperty(node, 'textContent', {get: () => content.get.call(node), set: value => {
    node.children = []; node._html = ''; content.set.call(node, value);
  }});
  return node;
};
const shell = document.createElement('section');
const fallbackQuery = document.querySelector;
document.querySelector = selector => shell.querySelector(selector) || fallbackQuery(selector);
globalThis.window = {getSelection: () => null};
const copy = value => JSON.parse(JSON.stringify(value));
const keys = ['dynamicBlockWords', 'wordCloudBlockWords', 'wordCloudExcludeUids'];
const initial = () => [{platform: 'bilibili', uid: 1, enabled: true,
  targets: [{platform: 'qq-onebot', type: 0, num: 81001, enabled: true, messages: []},
    {platform: 'qq-onebot', type: 1, num: 82002, enabled: true, messages: []}]}];
let disk = initial(), requests = [], reject = false;
const version = user => JSON.stringify(user);
const summary = user => ({platform: user.platform, uid: user.uid, enabled: user.enabled, targets: user.targets});
const state = () => process.env.PUSH_STATE ? JSON.parse(readFileSync(process.env.PUSH_STATE, 'utf8')) : ({sessions: [{platform: 'qq-onebot', typeCode: 1, num: 81001,
  channels: [{streamerPlatform: 'bilibili', streamerUid: 1, quietActive: true}], disabled: [], menuHidden: []},
  {platform: 'qq-onebot', typeCode: 1, num: 89999, disabled: ['天气']}],
  commands: [{name: '直播报告', category: '数据查询', disableable: true, channelScoped: true}],
  subscriptions: [], incomplete: [], quietGlobal: {start: '23:00', end: '07:00'}});
const response = (payload, status = 200) => ({status, ok: status < 400, json: async () => copy(payload)});
globalThis.fetch = async (path, options = {}) => {
  const url = new URL(String(path), 'http://localhost');
  const route = url.pathname.replace('/config/api', '');
  const body = options.body && JSON.parse(options.body);
  requests.push({url: url.pathname + url.search, method: options.method || 'GET', body});
  if (route === '/datasource/users' && options.method !== 'POST') return response({success: true, users: disk.map(summary)});
  if (route === '/datasource/users' && options.method === 'POST') {
    disk.push(body.user); return response({success: true, version: version(body.user)});
  }
  const match = /^\/datasource\/users\/bilibili\/(\d+)(\/version)?$/.exec(route);
  if (match) {
    const user = disk.find(item => item.uid === Number(match[1]));
    if (options.method === 'POST') {
      if (reject) return response({success: false, message: '会话 82002：机器人不在这个群'}, 400);
      assert.equal(body.version, version(user));
      for (const key of keys) if (!Object.hasOwn(body.user, key) && Object.hasOwn(user, key)) body.user[key] = user[key];
      assert.ok(keys.every(key => body.user[key] === undefined || Array.isArray(body.user[key])), '坏名单仍阻止保存');
      disk[disk.indexOf(user)] = body.user;
      return response({success: true, version: version(body.user), message: '已保存'});
    }
    return response({success: true, user, version: version(user)});
  }
  if (route === '/state') return response(state());
  if (route === '/bot/targets/lookup') {
    const num = url.searchParams.get('num');
    const type = url.searchParams.get('type');
    const found = ['81001', '82002'].includes(num);
    return response({success: true, found, platform: url.searchParams.get('platform'), type, num: Number(num),
      ...(found ? {[type === 'group' ? 'name' : 'nickname']: type === 'group' ? '测试群 ' + num : '测试好友 ' + num}
        : {message: num === '83003' ? '刚查过，请稍后再试' : type === 'group' ? '机器人不在这个群' : '机器人没有这个好友'})});
  }
  if (route === '/bot/targets') return response({success: true, targets: [{sender: 'qq-onebot', num: 82002, name: '管理员群'}]});
  return response({success: true});
};
const push = await import('../../../main/resources/config-ui-pages/push.js');
const {store} = await import('../../../../../../core/nova-core/src/main/resources/config-ui/store.js');
store.platforms = []; store.senderList = ['qq-onebot'];
store.vocab = {'bot.target.group': '群号', 'bot.target.user': 'QQ 号'};
const root = () => document.querySelector('#push-right');
const drawer = () => idNode('push-drawer-body');
const nodes = node => [node, ...(node.children || []).flatMap(nodes)];
const text = node => node.textContent + (node.innerHTML || '').replace(/<[^>]*>/g, '') + (node.children || []).map(text).join('');
const button = (host, label) => nodes(host).find(node => node.tagName === 'button' && node.textContent === label);
const input = label => root().querySelectorAll('input').find(node => node.getAttribute('aria-label') === label);
async function open(uid = 1, tail = '') {
  await push.showPush(String(uid), tail);
  const deadline = Date.now() + 10000;
  while (push.pushEntries().find(user => user.uid === uid)?._summary) {
    assert.ok(Date.now() < deadline, '主播详情应完成加载');
    await new Promise(resolve => setImmediate(resolve));
  }
}
async function reset(role = 'MEMBER') {
  await push.leave(); store.singleStreamer = null; store.role = role; disk = initial(); requests = []; reject = false;
  store.handlerList = [{className: 'org.frostnova.nova.core.handler.SessionRecapPushHandler',
    displayName: '下播小结（私聊）', defaultEnabled: false, description: '下播后发送', platforms: []},
    {className: 'org.frostnova.nova.core.handler.PreLiveReminderPushHandler',
      displayName: '开播前提醒（私聊）', defaultEnabled: false, description: '惯常开播前发送', platforms: []}]; push.render(shell);
  await push.reload(); await push.loadPushPage(); await open();
}

// 防止管理员看不出小结接收人；成员也应能为自己负责的主播保存和取消标记。
let checks = 0;
for (const role of ['ADMIN', 'MEMBER']) {
  await reset(role);
  await open(1, 'qq-onebot|0|81001');
  const mark = input('这是主播本人');
  assert.ok(mark, '好友通道应有主播本人勾选框');
  assert.equal(mark.checked, false, '旧配置默认没勾');
  assert.equal(input('下播小结（私聊）')?.disabled, true, '未确认身份不能订阅小结');
  assert.match(text(root()), /先勾『这是主播本人』/);
  assert.match(text(root()), /观众昵称和场次数据/);
  assert.equal(input('开播前提醒（私聊）')?.disabled, true, '非本人不能订阅开播前提醒');
  // 防止提醒沿用普通说明，让主播不知道为什么不能开启。
  const reminderRow = root().querySelector('[data-notice="org.frostnova.nova.core.handler.PreLiveReminderPushHandler"]');
  assert.ok(reminderRow, '应显示开播前提醒这一行');
  assert.match(text(reminderRow), /先勾『这是主播本人』/, '提醒这一行应说明如何开启');
  mark.checked = true;
  await mark.dispatchEvent({type: 'change', target: mark});
  assert.equal(input('下播小结（私聊）')?.disabled, false, '确认身份后能开启小结');
  const recap = input('下播小结（私聊）');
  recap.checked = true;
  await recap.dispatchEvent({type: 'change', target: recap});
  const reminder = input('开播前提醒（私聊）');
  assert.equal(reminder?.disabled, false, '确认身份后能开启提醒');
  reminder.checked = true;
  await reminder.dispatchEvent({type: 'change', target: reminder});
  await push.save();
  assert.ok(disk[0].targets[0].messages.some(m => m.handler.endsWith('.PreLiveReminderPushHandler')), '提醒订阅应保存');
  assert.equal(disk[0].targets[0].messages[0].handler, 'org.frostnova.nova.core.handler.SessionRecapPushHandler', '小结订阅应保存');
  assert.equal(disk[0].targets[0].streamerSelf, true, '勾选必须保存');
  await open();
  assert.match(text(root()), /主播本人/, '通道列表应标明接收人');
  await open(1, 'qq-onebot|0|81001');
  const clear = input('这是主播本人');
  assert.equal(clear.checked, true, '再次打开仍勾选');
  clear.checked = false;
  await clear.dispatchEvent({type: 'change', target: clear});
  await push.save();
  assert.equal(disk[0].targets[0].streamerSelf, false, '取消必须保存');
  assert.equal(disk[0].targets[0].messages.length, 0, '取消本人标记应同时取消小结与提醒订阅');
  await open(1, 'qq-onebot|1|82002');
  assert.equal(input('这是主播本人'), undefined, '群通道不能出现勾选框');
  assert.equal(input('下播小结（私聊）')?.disabled, true, '群不能订阅小结');
  assert.equal(input('开播前提醒（私聊）')?.disabled, true, '群不能订阅开播前提醒');
  checks++;
}
console.log('跑了 ' + checks + ' 格，红 0 格');
