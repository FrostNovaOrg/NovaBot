import assert from 'node:assert/strict';
import {register} from 'node:module';
import {readFileSync} from 'node:fs';
import {installPushDom} from './push-test-dom.mjs';

register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
const {idNode} = installPushDom();
const create = document.createElement;
document.createElement = tag => {
  const node = create(tag);
  node.dataset = {};
  Object.defineProperties(node, {
    nodeType: {get: () => tag === '#text' ? 3 : 1},
    nodeValue: {get: () => node._text},
    childNodes: {get: () => node.children.length ? node.children : node._text
      ? [{nodeType: 3, nodeValue: node._text, parentNode: node}] : []},
  });
  node.remove = () => {
    const siblings = node.parentNode?.children;
    if (siblings) siblings.splice(siblings.indexOf(node), 1);
    node.parentNode = null;
  };
  return node;
};
document.getElementById = idNode;
globalThis.window = {getSelection: () => null};
const {store} = await import('./store.js');
const push = await import('../../../main/resources/config-ui-pages/push.js');
const source = readFileSync(new URL('../../../main/resources/config-ui-pages/push.js', import.meta.url), 'utf8');
const all = root => [root, ...root.children.flatMap(all)];
const right = () => all(idNode('push-right'));
const byText = text => right().find(node => node.textContent === text);
const byClass = name => right().find(node => node.className.split(/\s+/).includes(name));
const posts = [];
let failSave = false;
const handler = (className, displayName) => ({className, displayName, aliases: [],
  placeholders: ['{uname}'], attachments: [], factoryParams: {message: 'factory'},
  defaultParams: {message: 'saved'}, options: []});
store.handlerList = [handler('x.LiveOn', '开播通知'), handler('x.LiveOff', '下播通知')];
store.role = 'ADMIN';
store.platforms = [];
const users = [1, 2].map(uid => ({uid, platform: 'bilibili', _uname: '主播' + uid,
  targets: [{platform: 'qq', type: 1, num: 11, messages: [{handler: 'x.LiveOn'}]},
    {platform: 'qq', type: 0, num: 21, messages: []}]}));
globalThis.fetch = async (url, opts = {}) => {
  let result = {success: true};
  if (url.endsWith('/templates')) {
    const body = JSON.parse(opts.body);
    posts.push(body);
    result = {success: !failSave, message: failSave ? '未保存' : '已保存'};
    if (!failSave) {
      const item = store.handlerList.find(item => item.className === body.className);
      item.defaultParams = {...item.factoryParams, ...body.params};
    }
  } else if (url.endsWith('/handlers')) result = {handlers: store.handlerList};
  else if (url.endsWith('/datasource/users')) result = {success: true, users};
  else if (url.includes('/bot/targets?type=group')) result = {items: [
    {sender: 'qq', num: 11, name: '相同群'}, {sender: 'qq', num: 12, name: '新群'}]};
  else if (url.includes('/bot/targets?type=friend')) result = {items: [
    {sender: 'qq', num: 21, nickname: '已有好友'}, {sender: 'qq', num: 22, nickname: '新好友'}]};
  return {status: 200, json: async () => result};
};
await push.loadPushPage();
push.setPushEntries(users);
push.render(idNode('page-push'));
await push.showPush('default', '');

let checks = 0;
const failures = [];
async function check(name, run) {
  checks++;
  try { await run(); } catch (error) { failures.push(name + ': ' + error.message); }
}
const waitFor = async condition => {
  const deadline = Date.now() + 5000;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error('等待页面状态超时');
    await new Promise(resolve => setTimeout(resolve, 0));
  }
};
const edit = () => byClass('tpl-blk').click();

// 故障：未修改就看到保存，换到未修改的通知仍能误存；保存与恢复挤在一起。
await check('保存条只随当前通知的改动出现，恢复操作独立在页底', async () => {
  const save = byText('保存默认模板');
  const bar = save.parentNode;
  assert.equal(bar.hidden, true);
  await edit();
  assert.equal(bar.hidden, false);
  await byClass('tpl-x').dispatchEvent({type: 'click', preventDefault() {}, stopPropagation() {}});
  assert.equal(bar.hidden, true, '改回原样后仍显示保存');
  await edit();
  await byText('下播通知').click();
  assert.equal(bar.hidden, true);
  await byText('开播通知').click();
  assert.equal(bar.hidden, false);
  const reset = right().find(node => node.tagName === 'button' && node.textContent.startsWith('恢复「'));
  assert.equal(reset.textContent, '恢复「开播通知」的出厂默认');
  assert.notEqual(reset.parentNode, bar);
  assert.ok(reset.className.includes('lnkbtn'));
});

// 故障：移到页底后的保存按钮丢失草稿，或失败后无法再存。
await check('保存失败保留草稿，重试只保存当前通知', async () => {
  await push.showPush('default', '');
  await edit();
  failSave = true;
  await byText('保存默认模板').click();
  assert.equal(byText('保存默认模板').disabled, false);
  assert.equal(byText('保存默认模板').parentNode.hidden, false);
  failSave = false;
  await byText('保存默认模板').click();
  await waitFor(() => byText('保存默认模板')?.disabled);
  assert.deepEqual(posts.at(-1), {className: 'x.LiveOn', params: {message: 'saved{uname}'}});
  assert.equal(byText('保存默认模板').parentNode.hidden, true);
});

// 故障：恢复文字链不再确认、恢复错通知，或把尚未保存的草稿一起存下去。
await check('恢复仍先确认，只恢复当前通知，取消不写入', async () => {
  await byText('下播通知').click();
  assert.ok(byText('恢复「下播通知」的出厂默认'));
  await edit();
  const before = posts.length;
  let pending = right().find(node => node.tagName === 'button' && node.textContent.startsWith('恢复「')).click();
  const dialog = document.body.querySelector('.ask');
  await dialog.dispatchEvent({type: 'click', target: dialog.querySelector('[data-act="no"]')});
  await pending;
  assert.equal(posts.length, before);
  pending = right().find(node => node.tagName === 'button' && node.textContent.startsWith('恢复「')).click();
  await dialog.dispatchEvent({type: 'click', target: dialog.querySelector('[data-act="yes"]')});
  await pending;
  await waitFor(() => posts.length === before + 1 && byText('保存默认模板')?.disabled);
  assert.deepEqual(posts.at(-1), {className: 'x.LiveOff', params: {}});
});

// 故障：同名群的使用情况无法区分属于哪位主播，名单也无法展开。
await check('使用情况按通知汇总，展开可辨认主播和通道', async () => {
  const table = byClass('template-adoption');
  assert.ok(table, '缺少使用情况表');
  const details = table.querySelector('details');
  assert.ok(details?.querySelector('summary'), '缺少名单展开入口');
  const content = all(details).map(node => node.textContent).join(' ');
  assert.ok(content.includes('主播1 › 相同群'));
  assert.ok(content.includes('主播2 › 相同群'));
});

// 故障：群和好友混在一起，新增目标没有明确按钮，已有目标混入可添加目标。
await check('群和好友各自分组，已添加在组末；两类添加都选中正确目标', async () => {
  for (const [title, num, type] of [['群 2 个', 12, 1], ['好友 2 个', 22, 0]]) {
    await push.showPush('1', '');
    await byText('＋ 添加通道').click();
    const sections = all(idNode('push-drawer-body')).filter(node => node.tagName === 'section');
    const group = sections.find(node => node.querySelector('h4')?.textContent === title);
    assert.ok(group, '缺少分组 ' + title);
    const buttons = group.querySelectorAll('button');
    assert.equal(buttons[0].textContent, '添加');
    assert.equal(buttons[0].disabled, false);
    assert.equal(buttons.at(-1).disabled, true);
    assert.equal(buttons.at(-1).textContent, '已添加');
    await buttons[0].click();
    assert.ok(users[0].targets.some(target => target.num === num && target.type === type));
    assert.equal(location.hash, '#/push/1/qq|' + type + '|' + num);
    assert.equal(idNode('push-drawer').hidden, true);
  }
});

await check('搜索只命中好友时不保留空群标题，两组都空时显示提示', async () => {
  await push.showPush('1', '');
  await byText('＋ 添加通道').click();
  const body = idNode('push-drawer-body');
  const search = body.querySelector('input');
  search.value = '新好友';
  await search.dispatchEvent({type: 'input', target: search});
  assert.deepEqual(body.querySelectorAll('h4').map(node => node.textContent), ['好友 1 个']);
  search.value = 'missing';
  await search.dispatchEvent({type: 'input', target: search});
  assert.equal(body.querySelectorAll('h4').length, 0);
  assert.ok(all(body).some(node => node.textContent.includes('未找到匹配')));
  push.closeDrawer();
});

// 故障：小窗口左树消失后无法辨认并切换主播和通道。
await check('900px 以上保留左树，手机选择器写明主播且能切换', async () => {
  const breakpoint = /@media\s*\(max-width:(\d+)px\)\s*\{\s*\.pushwrap/.exec(source);
  assert.equal(Number(breakpoint?.[1]), 900);
  const nav = idNode('push-nav');
  const channels = nav.children.filter(node => node.value === 'c:2:qq|1|11');
  assert.equal(channels[0]?.textContent, '主播2 › 相同群');
  nav.value = 'c:2:qq|1|11';
  await nav.dispatchEvent({type: 'change', target: nav});
  assert.equal(location.hash, '#/push/2/qq|1|11');
  nav.value = 's:1';
  await nav.dispatchEvent({type: 'change', target: nav});
  assert.equal(location.hash, '#/push/1');
  await push.showPush('2', '');
  const group = idNode('push-tree').children[1];
  await group.querySelector('.tnode').click();
  assert.equal(location.hash, '#/push/2');
  await group.querySelector('.tchan').click();
  assert.equal(location.hash, '#/push/2/qq|1|11');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
failures.forEach(failure => console.log('红：' + failure));
process.exitCode = failures.length ? 1 : 0;
