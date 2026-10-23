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
const requests = [];
const presets = ['classic', 'pink-blue', 'warm', 'night'].map((id, i) => ({id,
  name: ['经典', '粉蓝', '暖阳', '夜幕'][i],
  colors: {background: '#123456', card: '#234567', text: '#345678', accent: '#456789'}}));
let defaultId = 'pink-blue';
const HANDLER = 'x.Report';
store.handlerList = [{className: HANDLER, displayName: '下播报告', aliases: [],
  placeholders: ['{report}'], attachments: [], factoryParams: {}, defaultParams: {},
  reportLayout: true, options: [{key: 'cover', label: '封面', type: 'BOOLEAN', defaultValue: true}]}];
store.role = 'ADMIN';
store.platforms = [];
const user = {uid: 1, platform: 'bilibili', _uname: '主播甲', targets: [
  {platform: 'qq', type: 1, num: 11, messages: [{handler: HANDLER}]}]};
let savedUser = structuredClone(user);
globalThis.URL.createObjectURL = () => 'blob:preview';
globalThis.URL.revokeObjectURL = () => {};
globalThis.fetch = async (url, opts = {}) => {
  const body = opts.body ? JSON.parse(opts.body) : null;
  requests.push({url, body, method: opts.method || 'GET'});
  let result = {success: true};
  if (url.endsWith('/report/palettes/default')) {
    defaultId = body.id || 'pink-blue';
    result.message = '已保存，没另选配色的通道将同步更新';
  } else if (url.endsWith('/report/palettes')) result = {success: true, presets, default: defaultId};
  else if (url.endsWith('/datasource/users/bilibili/1')) {
    if (body) savedUser = structuredClone(body.user);
    result = {success: true, user: structuredClone(savedUser), version: 'v1', message: '已保存'};
  } else if (url.endsWith('/datasource/users')) result = {success: true, users: [structuredClone(savedUser)]};
  return {status: 200, ok: true, json: async () => result, blob: async () => new Blob(['png'])};
};
await push.loadPushPage();
push.setPushEntries([user]);
push.render(idNode('page-push'));
let checks = 0;
const failures = [];
async function check(name, run) {
  checks++;
  try { await run(); } catch (error) { failures.push(name + ': ' + error.message); }
}
const select = label => right().find(node => node.tagName === 'select' && node.getAttribute('aria-label') === label);
const waitFor = async condition => {
  const deadline = Date.now() + 8000;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error('等待页面状态超时');
    await new Promise(resolve => setTimeout(resolve, 0));
  }
};
await check('默认模板选暖阳后单独保存，刷新保留并显示服务端提示', async () => {
  await push.showPush('default', '');
  const input = select('默认报告图配色');
  assert.ok(input, '缺少默认配色选择');
  assert.equal(input.value, 'pink-blue');
  input.value = 'warm';
  await input.dispatchEvent({type: 'change', target: input});
  assert.equal(defaultId, 'pink-blue', '未点保存就提交了');
  await byText('保存报告图配色').click();
  assert.equal(requests.findLast(row => row.url.endsWith('/report/palettes/default')).body.id, 'warm');
  assert.ok(all(idNode('status-text')).some(node => node.textContent.includes('没另选配色的通道将同步更新')));
  await push.loadPushPage();
  await push.showPush('default', '');
  assert.equal(select('默认报告图配色').value, 'warm');
  const samples = right().filter(node => node.className === 'report-palette-sample');
  assert.equal(samples.length, 4);
  assert.equal(samples[0].style.backgroundColor, presets[0].colors.background);
});
await check('成员只读当前默认配色', async () => {
  store.role = 'MEMBER';
  await push.showPush('default', '');
  assert.equal(select('默认报告图配色'), undefined);
  assert.ok(byText('当前生效：暖阳'));
  store.role = 'ADMIN';
});
for (const id of ['night', 'warm', null]) {
  await check('通道配色 ' + id + ' 随总保存往返，草稿预览显式携带配色', async () => {
    await push.reload();
    await push.showPush('1', 'qq|1|11');
    const input = select('通道报告图配色');
    assert.ok(input, '缺少通道配色选择');
    requests.length = 0;
    input.value = id || '';
    await input.dispatchEvent({type: 'change', target: input});
    const edit = right().find(node => node.getAttribute('data-notice-edit') === HANDLER);
    if (edit) await edit.click();
    const layoutTab = right().find(node => node.textContent === '图片版式');
    if (layoutTab) await layoutTab.click();
    await waitFor(() => requests.some(row => row.url.endsWith('/report/preview') && row.body.reportPalette === id));
    const preview = requests.findLast(row => row.url.endsWith('/report/preview')).body;
    assert.ok(Object.hasOwn(preview, 'reportPalette'));
    assert.equal(preview.reportPalette, id);
    await push.save();
    assert.equal(savedUser.targets[0].reportPalette, id);
    await push.reload();
    await push.showPush('1', 'qq|1|11');
    assert.equal(select('通道报告图配色').value, id || '');
  });
}
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
failures.forEach(message => console.error(message));
process.exitCode = failures.length ? 1 : 0;
