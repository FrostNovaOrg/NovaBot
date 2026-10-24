import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';
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
  targets: [{platform: 'qq-onebot', type: 1, num: 81001, enabled: true, messages: []}]}];
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
const {defaultMessages} = await import('../../../main/resources/config-ui-pages/push-model.js');
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
  store.handlerList = []; push.render(shell);
  await push.reload(); await push.loadPushPage(); await open();
}
async function addDrawer() { await open(); await button(root(), '＋ 添加通道').click(); }
async function lookup(num, type = 'group') {
  const kind = drawer().querySelector('[aria-label="会话类型"]');
  assert.ok(kind, '成员需要选择群或好友');
  kind.value = type; await kind.dispatchEvent({type: 'change', target: kind});
  const number = drawer().querySelector('input');
  number.value = num; await number.dispatchEvent({type: 'input', target: number});
  const go = button(drawer(), '查'); assert.ok(go, '应有精确查号按钮'); await go.click();
}
function noDirectory() {
  assert.deepEqual(requests.filter(req => /^\/config\/api\/bot\/targets(?:\?|$|\/refresh)/.test(req.url)), [], '成员不应请求整张名单或刷新');
}
const cases = {
  // 单主播成员的推送页不该保留只有一个名字的侧栏或手机选择框。
  single: async () => {
    await reset(); store.singleStreamer = {platform: 'bilibili', uid: 1};
    for (const width of [1280, 390]) {
      window.innerWidth = width;
      push.render(shell); await open();
      assert.ok(!shell.querySelector('.ptree'), width + ' 宽不出主播左栏');
      assert.ok(!shell.querySelector('.pnarrow'), width + ' 宽不出下拉框');
      assert.equal(shell.querySelector('#push-nav'), null);
      assert.ok(button(root(), '＋ 添加通道'), '直接打开主播一级设置');
      await open(1, 'qq-onebot|1|81001');
      assert.ok(input('直播收益等金额'), '仍能进入通道设置');
    }
  },
  unchanged: async () => {
    for (const [role, count] of [['ADMIN', 1], ['MEMBER', 2], ['MEMBER', 0]]) {
      await reset(role); disk = count === 0 ? [] : count === 2 ? [...initial(), {...initial()[0], uid: 2}] : initial();
      await push.reload(); await push.showPush('');
      assert.ok(shell.querySelector('.ptree')); assert.ok(shell.querySelector('#push-nav'));
      if (!count) assert.match(text(shell.querySelector('#push-tree')), /暂无分配给你的主播/);
      else assert.match(text(root()), /通道一览/);
    }
  },
  // 成员看见加删入口，操作到保存才被权限拒绝。
  controls: async () => {
    await reset();
    assert.equal(!!shell.querySelector('#push-add-streamer'), false);
    assert.equal(!!shell.querySelector('#push-add-streamer-narrow'), false);
    assert.equal(!!button(root(), '删除主播'), false);
    const toggle = root().querySelector('input'); toggle.checked = false;
    await toggle.dispatchEvent({type: 'change', target: toggle}); await push.save();
    assert.equal(disk[0].enabled, false);
    assert.ok(requests.some(req => req.method === 'POST' && req.url === '/config/api/datasource/users/bilibili/1'));
    for (const role of ['ADMIN', undefined, 'unknown']) {
      await reset(role); store.role = role; push.render(shell); await open(); assert.ok(shell.querySelector('#push-add-streamer'));
      assert.ok(shell.querySelector('#push-add-streamer-narrow')); assert.ok(button(root(), '删除主播'));
    }
  },
  // 成员花时间编辑默认模板，按保存才发现不能保存。
  defaults: async () => {
    await reset();
    store.handlerList = [{className: 'Live', displayName: '开播', placeholders: ['{uname}'],
      defaultParams: {message: '模板正文'}, factoryParams: {message: '模板正文'}}];
    await push.showPush('default');
    assert.ok(text(root()).includes('默认模板由管理员维护，这里只能看。'));
    assert.equal(button(root(), '保存默认模板'), undefined);
    assert.equal(button(root(), '恢复出厂默认'), undefined);
    assert.ok(text(root()).includes('模板正文'));
    const bodies = nodes(root()).filter(node => node.contentEditable !== undefined);
    assert.ok(bodies.length > 0); assert.ok(bodies.every(node => node.contentEditable === 'false'));
    await push.save(); assert.equal(requests.some(req => req.method === 'POST' && req.url === '/config/api/templates'), false);
    store.role = 'ADMIN'; await push.showPush('default'); assert.ok(button(root(), '保存默认模板'));
  },
  // 成员不能借模糊搜索翻看机器人加入的群或其他主播的推送目标。
  exact: async () => {
    await reset(); await addDrawer();
    assert.equal(drawer().querySelectorAll('input').some(node => node.type === 'search'), false);
    assert.equal(button(drawer(), '刷新'), undefined);
    assert.equal(drawer().querySelector('.dwlist'), null);
    assert.equal(text(drawer()).includes('正在推送'), false);
    for (const [num, type, message] of [['84004', 'group', '机器人不在这个群'], ['84004', 'friend', '机器人没有这个好友'], ['83003', 'group', '刚查过，请稍后再试']]) {
      await lookup(num, type); assert.ok(text(drawer()).includes(message)); assert.equal(button(drawer(), '加上'), undefined);
    }
    await lookup('82002', 'friend'); assert.ok(text(drawer()).includes('测试好友 82002'));
    assert.equal(push.pushEntries()[0].targets.length, 1); await button(drawer(), '加上').click();
    assert.ok(push.pushEntries()[0].targets.some(target => target.platform === 'qq-onebot' && target.type === 0 && String(target.num) === '82002'));
    noDirectory();
    store.role = 'ADMIN'; await push.loadPushPage(); await addDrawer();
    assert.ok(drawer().querySelectorAll('input').some(node => node.type === 'search')); assert.ok(button(drawer(), '刷新'));
  },
  // 号码边上的空格不应阻止查询，非数字与前导零则要给出对应原因并拦住请求。
  invalid: async () => {
    await reset(); await addDrawer();
    for (const [value, message] of [['0990000000301', '开头不能是 0'], ['群81001', '只能填数字'], ['0', '开头不能是 0'], ['81001abc', '只能填数字'], ['81 001', '只能填数字']]) {
      const before = requests.length;
      const number = drawer().querySelector('input'); number.value = value;
      await number.dispatchEvent({type: 'input', target: number});
      assert.ok(text(drawer()).includes(message), value + ' 应提示 ' + message);
      await button(drawer(), '查').click();
      assert.equal(requests.length, before);
    }
    await lookup('  82002 \t');
    assert.ok(button(drawer(), '加上'));
    assert.equal(new URL(requests.at(-1).url, 'http://localhost').searchParams.get('num'), '82002');
    await lookup('８２００２');
    assert.ok(button(drawer(), '加上'));
    assert.equal(new URL(requests.at(-1).url, 'http://localhost').searchParams.get('num'), '82002');
    assert.equal(drawer().querySelector('input').value, '８２００２');
    noDirectory();
  },
  // 新分到的主播没有任何通道时，也必须能从成员 status 的平台添加第一个通道。
  first: async () => {
    await reset();
    disk[0].targets = [];
    store.senderList = JSON.parse(readFileSync(process.env.PUSH_STATUS, 'utf8')).senders || [];
    await push.reload(); await push.loadPushPage(); await addDrawer();
    assert.equal(drawer().querySelectorAll('select').length, 1, '单个平台直接使用');
    await lookup('82002');
    assert.ok(text(drawer()).includes('测试群 82002'), text(drawer()));
    await button(drawer(), '加上').click(); await push.save();
    assert.equal(push.changeCount(), 0);
    assert.deepEqual(disk[0].targets.map(({platform, type, num}) => ({platform, type, num})),
      [{platform: 'qq-onebot', type: 1, num: 82002}]);
    noDirectory();
    disk[0].targets = []; store.senderList = []; await push.reload(); await push.loadPushPage(); await addDrawer();
    const before = requests.length;
    await lookup('82002');
    assert.ok(text(drawer()).includes('没有可用的推送平台，请联系管理员。'));
    assert.equal(requests.length, before);
  },
  // 多平台实例的成员必须能选其他平台，查询和最终保存均应使用选中的平台。
  platforms: async () => {
    await reset(); store.senderList = ['qq-onebot', 'qq-secondary']; await addDrawer();
    const platform = drawer().querySelector('[aria-label="推送平台"]');
    assert.ok(platform, '多个平台应有下拉选择');
    assert.equal(platform.querySelectorAll('option').length, 2, '合并来源后平台不重复');
    platform.value = 'qq-secondary'; await platform.dispatchEvent({type: 'change'});
    await lookup('82002');
    assert.equal(new URL(requests.at(-1).url, 'http://localhost').searchParams.get('platform'), 'qq-secondary');
    // 换平台后不能沿用刚才的查询结果添加到另一个平台。
    platform.value = 'qq-onebot'; await platform.dispatchEvent({type: 'change'});
    assert.equal(button(drawer(), '加上'), undefined);
    platform.value = 'qq-secondary'; await platform.dispatchEvent({type: 'change'});
    await lookup('82002'); await button(drawer(), '加上').click(); await push.save();
    assert.ok(disk[0].targets.some(target => target.platform === 'qq-secondary' && target.num === 82002));
    noDirectory();
  },
  // 已经添加的通道不能重复添加，灰色按钮旁应说明原因。
  duplicate: async () => {
    await reset(); await addDrawer(); await lookup('81001');
    assert.equal(button(drawer(), '加上').disabled, true);
    assert.ok(text(drawer()).includes('这个通道已经加过了'));
    assert.equal(push.pushEntries()[0].targets.length, 1);
    noDirectory();
  },
  // 新通道被服务端拒绝时，成员需要看到原因并保留尚未保存的通道。
  rejected: async () => {
    await reset(); await addDrawer(); await lookup('82002'); await button(drawer(), '加上').click();
    reject = true; await push.save();
    assert.ok(idNode('status-text').textContent.includes('会话 82002：机器人不在这个群'));
    assert.ok(push.pushEntries()[0].targets.some(target => String(target.num) === '82002'));
    assert.ok(push.changeCount() > 0); assert.equal(disk[0].targets.length, 1);
  },
  // 成员的现有群名不能依赖没有权限读取的整张名单。
  names: async () => {
    await reset(); assert.ok(text(root()).includes('测试群 81001')); noDirectory();
    await open(1, 'qq-onebot|1|81001');
    assert.equal(text(root()).includes('机器人不是本群管理员'), false, '名称查号不能推断机器人群角色');
    assert.ok(text(root()).includes('机器人角色：无法获取名单'));
    disk[0].targets.push({platform: 'qq-onebot', type: 0, num: 84004, messages: []});
    await push.reload(); await push.loadPushPage(); await open();
    assert.ok(text(root()).includes('84004')); assert.equal(text(root()).includes('机器人没有这个好友'), false);
  },
  // 成员不能点击残留会话的恢复命令按钮后才收到权限错误。
  stranded: async () => {
    await reset(); await push.showPush('');
    assert.equal(text(root()).includes('未配置推送但仍有状态记录的会话'), false);
    assert.equal(button(root(), '恢复这些命令'), undefined);
    store.role = 'ADMIN'; await push.showPush(''); assert.ok(button(root(), '恢复这些命令'));
  },
  // 成员看到的是自己的主播，不能把最近推送误当作全群记录。
  recent: async () => {
    await reset(); await open(1, 'qq-onebot|1|81001');
    assert.ok(text(root()).includes('只列你名下主播的推送。'));
    assert.equal(text(root()).includes('推送到本群的所有主播均计算在内。'), false);
    store.role = 'ADMIN'; await open(1, 'qq-onebot|1|81001');
    assert.ok(text(root()).includes('推送到本群的所有主播均计算在内。'));
  },
  // 未保存的静音档位不能配上已保存档位的「静音中」。
  quiet: async () => {
    for (const role of ['ADMIN', 'MEMBER']) {
      await reset(role); disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
      await push.reload(); await open(1, 'qq-onebot|1|81001');
      assert.ok(text(root()).includes('00:00–23:59 · 静音中'));
      const start = input('静音开始'); start.value = '01:00'; await start.dispatchEvent({type: 'change'});
      assert.equal(text(root()).includes('· 静音中'), false);
      const back = input('静音开始'); back.value = '00:00'; await back.dispatchEvent({type: 'change'});
      assert.ok(text(root()).includes('00:00–23:59 · 静音中'));
      const off = input('本通道不静音'); off.checked = true; await off.dispatchEvent({type: 'change'});
      assert.equal(text(root()).includes('· 静音中'), false);
      await push.reload(); await open(1, 'qq-onebot|1|81001'); assert.ok(text(root()).includes('00:00–23:59 · 静音中'));
    }
  },
  // 向导重选目标后，名单不能显示为空并在下一次编辑时覆盖旧词。
  wizard: async () => {
    await reset('ADMIN'); disk = []; await push.reload();
    const source = readFileSync(new URL('../../../main/resources/config-ui-pages/setup-streamer.js', import.meta.url), 'utf8');
    const draft = {streamer: {platform: 'bilibili', uid: 1, uname: '主播'}, targets: ['g1']};
    const opts = [{key: 'g1', sender: 'qq-onebot', type: 1, num: 81001}, {key: 'g2', sender: 'qq-onebot', type: 1, num: 82002}];
    const names = ['store', 'draft', 'options', 'pushEntries', 'api', 'say', 'renderStreamers', 'defaultMessages', 'markDirty', 'seen', 'acceptAddedPushUser', 'trackSetupVersions'];
    const values = [store, draft, opts, push.pushEntries, async (path, options) => (await fetch('/config/api' + path, options)).json(), () => {}, () => {}, defaultMessages, () => {}, {}, push.acceptAddedPushUser, push.trackSetupVersions];
    const saveWizard = new Function(...names, source.slice(source.indexOf('async function saveStreamer()')) + '\nreturn saveStreamer;')(...values);
    assert.equal(await saveWizard(), true); await open();
    for (const [index, key] of keys.entries()) {
      const field = root().querySelector('[data-block-list="' + key + '"]');
      field.value = index === 2 ? '91001' : '旧词'; await field.dispatchEvent({type: 'input'});
    }
    await push.save(); await push.reload();
    draft.targets = ['g2']; assert.equal(await saveWizard(), true); await open();
    for (const [index, key] of keys.entries()) {
      const field = root().querySelector('[data-block-list="' + key + '"]');
      assert.equal(field.value, index === 2 ? '91001' : '旧词');
      field.value += index === 2 ? '\n92002' : '\n新词'; await field.dispatchEvent({type: 'input'});
    }
    await push.save();
    for (const [index, key] of keys.entries()) assert.deepEqual(disk[0][key], index === 2 ? ['91001', '92002'] : ['旧词', '新词']);
  },
  // 手写成字符串的坏名单在空框有过输入后应能清成数组。
  malformed: async () => {
    await reset(); for (const key of keys) disk[0][key] = 'bad'; await push.reload(); await open();
    for (const key of keys) {
      const field = root().querySelector('[data-block-list="' + key + '"]'); assert.equal(field.value, '');
      field.value = ' '; await field.dispatchEvent({type: 'input'}); field.value = '';
      await field.dispatchEvent({type: 'input'});
    }
    await push.save(); for (const key of keys) assert.deepEqual(disk[0][key], []);
    assert.equal(push.changeCount(), 0);
  },
  // 页面实际请求必须能经过成员权限层；空回包不能掩盖越权调用。
  permissions: async () => {
    await reset(); await addDrawer();
    if (button(drawer(), '查')) { await lookup('82002'); await button(drawer(), '加上').click(); }
    await open(1, 'qq-onebot|1|81001');
    const revenue = input('直播收益等金额'); revenue.checked = true; await revenue.dispatchEvent({type: 'change'});
    const off = input('本通道不静音'); off.checked = true; await off.dispatchEvent({type: 'change'});
    const command = input('直播报告'); command.checked = false; await command.dispatchEvent({type: 'change'});
    await push.save();
    writeFileSync(process.env.PUSH_TRACE, JSON.stringify(requests));
    assert.equal(disk[0].targets[0].revenueVisible, true);
    assert.deepEqual(disk[0].targets[0].quietHours, {mode: 'off'});
    assert.deepEqual(disk[0].targets[0].disabledCommands, ['直播报告']);
  },
};
const selected = process.env.PUSH_CASE;
console.log('检查用户问题：' + selected);
try { await cases[selected](); console.log('跑了 1 格，红 0 格'); process.exit(0); }
catch (error) { console.log(error.stack); console.log('跑了 1 格，红 1 格'); process.exit(1); }
