import assert from 'node:assert/strict';
import {register} from 'node:module';
import {installPushDom} from './push-test-dom.mjs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
const {idNode} = installPushDom();
const copy = value => JSON.parse(JSON.stringify(value));
const initial = () => [1, 2].map(uid => ({platform: 'p', uid, enabled: true,
  targets: [{platform: 'q', type: 1, num: 100, enabled: true, messages: []}]}));
let disk = initial();
const version = user => JSON.stringify(user);
const requests = [];
let sessionDisabled = [];
let assistantAvailable = false;
const quietByUid = {1: true, 2: false};
let quietGlobal;
const response = payload => ({status: 200, json: async () => copy(payload)});
globalThis.fetch = async (path, options = {}) => {
  const url = String(path).replace('/config/api', '');
  requests.push({url, method: options.method || 'GET'});
  if (url === '/datasource/users') return response({success: true, users: disk});
  const match = /^\/datasource\/users\/p\/(\d+)(\/version)?$/.exec(url);
  if (match) {
    const user = disk.find(user => user.uid === Number(match[1]));
    if (options.method === 'POST') {
      const body = JSON.parse(options.body);
      if (body.version !== version(user)) return response({success: false, conflict: true, message: '配置已改过，请重新载入'});
      disk[disk.indexOf(user)] = body.user;
      return response({success: true, version: version(body.user)});
    }
    return response({success: true, user, version: version(user)});
  }
  if (url === '/state') return response({sessions: [{platform: 'q', typeCode: 1, num: 100,
    revenueVisible: true, disabled: sessionDisabled, menuHidden: [], quietActive: true,
    channels: [{streamerPlatform: 'p', streamerUid: 1, quietActive: quietByUid[1]},
      {streamerPlatform: 'p', streamerUid: 2, quietActive: quietByUid[2]}]}], commands: [
    {name: '直播报告', category: '数据查询', disableable: true, channelScoped: true},
    {name: '问', aliases: ['问数'], category: '数据查询', available: assistantAvailable},
    {name: '天气', category: '天气查询', disableable: true, channelScoped: false},
    {name: '菜单', category: '命令管理', disableable: false},
    {name: '启用命令', category: '命令管理', disableable: false}, {name: '禁用命令', category: '命令管理', disableable: false}],
    subscriptions: [1, 2].map(uid => ({platform: 'q', num: 100, streamerUid: uid, users: [uid], type: 'live'})), incomplete: [],
    quietGlobal});
  return response({success: true});
};
const push = await import('../../../main/resources/config-ui-pages/push.js');
const {store} = await import('../../../../../../core/nova-core/src/main/resources/config-ui/store.js');
async function open(uid) {
  await push.showPush(String(uid), 'q|1|100');
  const deadline = Date.now() + 10000;
  while (push.pushEntries().find(user => user.uid === uid)?._summary) {
    assert.ok(Date.now() < deadline, '通道详情应完成加载');
    await new Promise(resolve => setImmediate(resolve));
  }
}
function input(label) {
  return idNode('push-right').querySelectorAll('input').find(node => node.getAttribute('aria-label') === label);
}
function text(node) {
  return node.textContent + (node.innerHTML || '').replace(/<[^>]*>/g, '') + (node.children || []).map(text).join('');
}
const failures = [];
async function test(problem, run) {
  console.log('检查用户问题：' + problem);
  try { await run(); console.log('PASS ' + problem); }
  catch (error) { failures.push(problem); console.log('FAIL ' + problem + ': ' + error.stack); }
}
await test('甲通道金额保存在甲的配置，乙仍是默认，页面不调用旧状态写口', async () => {
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(1);
  assert.ok(input('直播报告'), '载入运行状态后应列出主播命令');
  const revenue = input('直播收益等金额');
  assert.ok(revenue, '金额开关应显示');
  assert.equal(revenue.checked, false, '旧群金额设置不应作为通道的值');
  revenue.checked = true; await revenue.dispatchEvent({type: 'change'});
  assert.equal(disk[0].targets[0].revenueVisible, undefined);
  await push.save();
  assert.equal(disk[0].targets[0].revenueVisible, true);
  assert.equal(disk[1].targets[0].revenueVisible, undefined);
  assert.equal(requests.some(request => request.method === 'POST' && request.url.startsWith('/state/')), false);
  assert.equal(input('菜单'), undefined);
  assert.equal(input('启用命令'), undefined);
  assert.equal(input('禁用命令'), undefined);
  assert.equal(input('天气'), undefined);
  assert.equal(idNode('push-right').textContent.includes('命令管理'), false);
});
await test('金额拨回默认后草稿消失，避免无意写入设置', async () => {
  await push.reload(); await open(2);
  const revenue = input('直播收益等金额');
  revenue.checked = true; await revenue.dispatchEvent({type: 'change'});
  assert.ok(push.changeCount() > 0);
  const back = input('直播收益等金额');
  back.checked = false; await back.dispatchEvent({type: 'change'});
  assert.equal(push.changeCount(), 0);
  await open(1);
});
await test('QQ 先关过甲命令时，页面保存保留草稿并提示重新载入', async () => {
  await open(1);
  const revenue = input('直播收益等金额');
  revenue.checked = false; await revenue.dispatchEvent({type: 'change'});
  disk[0].targets[0].disabledCommands = ['直播报告'];
  await push.save();
  assert.equal(disk[0].targets[0].revenueVisible, true);
  assert.deepEqual(disk[0].targets[0].disabledCommands, ['直播报告']);
  assert.ok(push.changeCount() > 0);
  assert.match(idNode('push-conflict').textContent, /重新载入/);
});
await test('同群甲通道正在静音时显示静音中，乙通道不静音时不显示', async () => {
  disk = initial();
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  disk[1].targets[0].quietHours = {mode: 'off'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(1);
  assert.ok(text(idNode('push-right')).includes('00:00–23:59 · 静音中'), '甲应显示服务端给出的通道静音状态');
  await open(2);
  assert.equal(text(idNode('push-right')).includes('· 静音中'), false, '乙不能使用群或甲的静音状态');
});
await test('群里关了插件命令时通道行显示计数，再关本通道的直播报告后计数增加', async () => {
  disk = initial();
  disk[1].targets[0].disabledCommands = ['直播报告'];
  sessionDisabled = ['天气'];
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(text(idNode('push-right')).includes('已禁用 1 条命令'), '插件命令应计入通道行');
  await push.showPush('2');
  assert.ok(text(idNode('push-right')).includes('已禁用 2 条命令'), '通道和群插件命令应合计');
  await open(2);
  assert.equal(input('天气'), undefined, '通道命令开关仍只列主播命令');
  assert.equal(text(idNode('push-right')).includes('但这些命令已不存在'), false, '群插件命令不能当作失效的通道开关');
});
await test('报告插件未加载时，推送页仍能清除直播报告的禁用记录', async () => {
  const retired = ['直播间数据', '直播间总数据', '数据排行榜', '总数据排行榜'];
  disk = initial();
  disk[0].targets[0].disabledCommands = [...retired, '直播报告'];
  await push.reload();
  const state = await (await fetch('/config/api/state')).json();
  state.commands = state.commands.filter(command => command.name !== '直播报告');
  push.setRuntimeState(state);
  await open(1);
  const clean = idNode('push-right').querySelectorAll('button').find(node => node.textContent === '清除记录');
  assert.ok(clean, '报告插件未加载时仍应提供清除记录按钮');
  const note = text(clean.parentNode);
  assert.match(note, /保留也不会生效.*直播报告/);
  for (const key of retired) assert.ok(!note.includes(key), key);
  await clean.click();
  assert.deepEqual(disk[0].targets[0].disabledCommands, [...retired, '直播报告']);
  await push.save();
  assert.deepEqual(disk[0].targets[0].disabledCommands, retired);
});
await test('通道明明在静音，主播页的通道行一眼看不出，以为推送坏了', async () => {
  const rowQuiet = () => {
    const rows = idNode('push-right').querySelectorAll('.rowcard');
    assert.equal(rows.length, 1, '主播页应列出这位主播的那一条通道');
    return rows.some(row => text(row).includes('静音中'));
  };
  disk = initial(); sessionDisabled = [];
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  disk[1].targets[0].quietHours = {mode: 'off'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(rowQuiet(), '服务端判甲通道正在静音，通道行应出「静音中」');
  await push.showPush('2');
  assert.equal(rowQuiet(), false, '乙通道不在静音，通道行不应出「静音中」');
  // 草稿改了静音档位：服务端那份判定说的是已存的档位，与通道页摘要同样不再采用
  push.pushEntries().find(user => user.uid === 1).targets[0].quietHours = {mode: 'off'};
  await push.showPush('1');
  assert.equal(rowQuiet(), false, '未保存的静音档位不能配上已存档位的「静音中」');
  // 没设静音时段：服务端判不在静音
  disk = initial(); quietByUid[1] = false;
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.equal(rowQuiet(), false, '没设静音时段时通道行不应出「静音中」');
  quietByUid[1] = true;
});
function channelRowQuiet() {
  const rows = idNode('push-right').querySelectorAll('.rowcard');
  assert.equal(rows.length, 1, '主播页应列出这位主播的那一条通道');
  return rows.some(row => text(row).includes('静音中'));
}
await test('通道设了自己的时段但此刻不在时段里，通道行却挂着静音中', async () => {
  disk = initial(); sessionDisabled = []; quietByUid[1] = false;
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.equal(channelRowQuiet(), false, '服务端判甲通道此刻不在静音，设了时段也不应出「静音中」');
  quietByUid[1] = true;
});
await test('通道跟随全局、全局正在静音，通道行看不出在静音', async () => {
  disk = initial(); sessionDisabled = []; quietByUid[1] = true;
  quietGlobal = {start: '00:00', end: '23:59'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(channelRowQuiet(), '服务端判跟随全局的甲通道正在静音，通道行应出「静音中」');
  quietGlobal = undefined;
});
await test('管理员照问号说明与禁用提示，以为点名问一位关了命令的主播会收到不带名字的回话，跟群里实际回话对不上', async () => {
  disk = initial(); sessionDisabled = [];
  disk[0].targets[0].disabledCommands = ['直播报告'];
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(1);
  const help = idNode('push-right').querySelectorAll('button').find(node => node.getAttribute('aria-label') === '群内命令：查看说明');
  assert.ok(help, '群内命令那一块应有问号说明');
  const hadWindow = 'window' in globalThis;
  if (!hadWindow) globalThis.window = {innerWidth: 1280, innerHeight: 800};
  help.isConnected = true;
  help.getBoundingClientRect = () => ({top: 100, bottom: 120, left: 100, right: 120});
  try {
    await help.click();
    const tip = idNode('body').querySelectorAll('[role="tooltip"]').find(node => !node.hidden);
    assert.ok(tip, '点问号应展开说明');
    const warn = idNode('push-right').querySelectorAll('.n-err').find(node => text(node).includes('直播报告'));
    assert.ok(warn, '甲关了直播报告，命令那一行应出禁用提示');
    for (const [where, said] of [['问号说明', tip.textContent], ['禁用提示', text(warn)]]) {
      // 点名时回话带主播名（BilibiliStreamerCommand 点名分支），没点名且各位都关了才不带名字
      assert.match(said, /点名[^；。]*本群已关闭「主播名」的「命令名」/, where + '应写出点名时回话带主播名');
      assert.match(said, /没点名[^；。]*本群已关闭「命令名」命令/, where + '应写明只有没点名时回话才不带名字');
    }
  } finally {
    if (!hadWindow) delete globalThis.window;
  }
});
await test('老通道没有问数字段时开关误显示为开', async () => {
  disk = initial();
  disk[1].targets[0].assistantEnabled = 'true';
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  for (const uid of [1, 2]) {
    await open(uid);
    assert.equal(input('问数')?.checked, false, '只有布尔 true 才应显示为开启');
  }
  assert.match(text(idNode('push-right')), /智能助手未开启或未设置密钥/);
  assistantAvailable = true;
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(2);
  assert.doesNotMatch(text(idNode('push-right')), /智能助手未开启或未设置密钥/);
});
await test('群通道未经运营群确认就启用了问数', async () => {
  disk = initial();
  await push.reload();
  await open(1);
  const beginEnable = () => {
    const toggle = input('问数');
    assert.ok(toggle, '通道规则应有问数开关');
    toggle.checked = true;
    return toggle.dispatchEvent({type: 'change'});
  };
  const pending = beginEnable();
  const dialog = idNode('body').querySelector('.ask');
  assert.equal(dialog?.hidden, false, '拨到开必须先显示确认框');
  const cancel = dialog.querySelector('[data-act="no"]');
  const confirm = dialog.querySelector('[data-act="yes"]');
  assert.equal(cancel.textContent, '取消');
  assert.equal(confirm.textContent, '这是运营群，开启');
  for (const phrase of ['问 〈问题〉', '人工智能生成', '模型服务', '金额可见', '每天、每周都有金额上限']) {
    assert.ok(dialog.querySelector('p').textContent.includes(phrase), phrase);
  }
  assert.notEqual(push.pushEntries()[0].targets[0].assistantEnabled, true, '确认之前不得进草稿');
  assert.equal(push.changeCount(), 0);
  await dialog.dispatchEvent({type: 'click', target: cancel});
  await pending;
  assert.equal(input('问数').checked, false, '取消后开关应退回关');
  assert.equal(push.changeCount(), 0);

  const accepted = beginEnable();
  await dialog.dispatchEvent({type: 'click', target: confirm});
  await accepted;
  assert.equal(input('问数').checked, true);
  await push.save();
  assert.equal(disk[0].targets[0].assistantEnabled, true);
  assert.equal(disk[1].targets[0].assistantEnabled, undefined, '同群的另一位主播仍关闭');

  const toggle = input('问数');
  toggle.checked = false;
  await toggle.dispatchEvent({type: 'change'});
  assert.equal(dialog.hidden, true, '关闭无需确认');
  await push.save();
  assert.equal(disk[0].targets[0].assistantEnabled, false);
});
await test('保存同一通道的其他设置后问数开关丢失', async () => {
  disk = initial();
  disk[0].targets[0].assistantEnabled = true;
  await push.reload();
  await open(1);
  const revenue = input('直播收益等金额');
  revenue.checked = true;
  await revenue.dispatchEvent({type: 'change'});
  await push.save();
  assert.equal(disk[0].targets[0].revenueVisible, true, '金额确实保存了');
  assert.equal(disk[0].targets[0].assistantEnabled, true, '已开启的问数仍应按布尔值保存');
  await push.reload();
  await open(1);
  assert.equal(input('问数')?.checked, true, '重新载入仍显示开启');
});
await test('成员页面能把关着的问数开关拨开', async () => {
  store.role = 'MEMBER';
  disk = initial();
  await push.reload();
  await open(1);
  const toggle = input('问数');
  assert.equal(toggle.checked, false, '成员应看见关闭状态');
  assert.equal(toggle.disabled, true, '成员不能拨开关着的问数开关');
  assert.match(text(idNode('push-right')), /由管理员开启/);
});
await test('成员关掉问数时出现确认框，或关闭状态没有保存', async () => {
  store.role = 'MEMBER';
  disk = initial();
  disk[0].targets[0].assistantEnabled = true;
  await push.reload();
  await open(1);
  const toggle = input('问数');
  assert.equal(toggle.checked, true);
  assert.equal(toggle.disabled, false, '成员可以关闭已开启的问数');
  toggle.checked = false;
  const change = toggle.dispatchEvent({type: 'change'});
  assert.ok(idNode('body').querySelector('.ask')?.hidden !== false, '关闭无需确认');
  await change;
  assert.equal(input('问数').checked, false);
  await push.save();
  assert.equal(disk[0].targets[0].assistantEnabled, false);
  assert.equal(push.changeCount(), 0, '保存成功后应清除草稿');
  await push.reload();
  await open(1);
  assert.equal(input('问数').checked, false, '重新载入仍显示关闭');
});
await push.leave();
console.log('跑了 15 格，红 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
