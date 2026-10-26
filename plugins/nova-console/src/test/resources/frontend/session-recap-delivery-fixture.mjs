import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {installPushDom} from './push-test-dom.mjs';
installPushDom();
const source = readFileSync(new URL('../../../main/resources/config-ui-pages/push.js', import.meta.url), 'utf8');
const start = source.indexOf('function sessionRecapActions(');
assert.ok(start >= 0, '通道订阅旁应能读取小结状态和试发');
assert.match(source, /if \(item.className === SESSION_RECAP\) sessionRecapActions\(text, user, target\)/,
  '小结订阅项应接入操作，关闭订阅后仍能看最近结果');
const code = source.slice(start, source.indexOf('\n}\n', start) + 3);
const nodes = root => [root, ...root.children.flatMap(nodes)];
const text = root => nodes(root).map(node => node.textContent).join(' ');
async function until(condition) {
  const deadline = Date.now() + 10000;
  while (!condition()) {
    assert.ok(Date.now() < deadline, '页面操作应完成');
    await new Promise(resolve => setImmediate(resolve));
  }
}
let calls, prompts, notices, replies, confirmed;
const context = vm.createContext({encodeURIComponent, Number,
  el: (tag, cls) => {const node = document.createElement(tag); node.className = cls || ''; return node;},
  api: async (path, options) => {calls.push({path, options}); return replies.shift();},
  ask: async spec => {prompts.push(spec); return confirmed.shift();},
  say: (message, kind) => notices.push({message, kind}),
});
vm.runInContext(code, context);
function mount(status, extra = {}, target = {}) {
  calls = []; prompts = []; notices = []; replies = [{success: true, status, ...extra}]; confirmed = [];
  const host = document.createElement('div');
  context.sessionRecapActions(host, {platform: 'test', uid: 910000001},
    {platform: 'qq-onebot', type: 0, num: 930000001, streamerSelf: true, ...target});
  return host;
}
let checks = 0;
// 防止状态被隐藏、标点丢失或原因被改写，导致管理员看不出是否发过。
for (const status of ['还没有场次：下一场下播 30 分钟后发', '10/25 晚上场：等发，约 22:30 发',
  '10/25 晚上场：静音中，静音结束后发', '10/25 晚上场：正在发', '10/25 晚上场：已发',
  '10/25 晚上场：没发出去（发送失败），不会自动重发', '10/25 晚上场：不确定发出去没有，不会重发',
  '10/25 晚上场：没发：主播已暂停小结', '10/25 晚上场：作废：主播已经再次开播']) {
  const host = mount(status); await until(() => text(host).includes(status));
  assert.match(calls[0].path, /session-recap\/test\/910000001\/status\?pushPlatform=qq-onebot&type=0&num=930000001/);
  checks++;
}
// 防止坏档案只显示停发，却没有指向主播页档案恢复位置。
const broken = mount('档案读不出，已停发', {profileUnreadable: true});
await until(() => nodes(broken).some(node => node.tagName === 'a' && !node.hidden));
const link = nodes(broken).find(node => node.tagName === 'a');
assert.equal(link.href, '#/streamers/test/910000001?section=profile');
assert.equal(link.textContent, '去主播页点「档案已核对，恢复」'); checks++;
// 防止页面未等暂停和静音确认就发送，或误将其中一次确认用于另一项。
const host = mount('10/25 晚上场：已发'); await until(() => text(host).includes('已发'));
replies.push({success: false, message: '要先确认', confirmation: 'paused'},
  {success: false, message: '要先确认', confirmation: 'quiet'},
  {success: true, message: '最近一场的小结已发送，请到这条通道确认是否收到'});
confirmed.push(true, true);
const button = nodes(host).find(node => node.tagName === 'button');
await button.dispatchEvent({type: 'click', target: button});
assert.deepEqual(prompts.map(prompt => prompt.body), ['主播本人说过先别发了，确定还要发吗？', '现在是这条通道的静音时段，确定现在发吗？']);
const trials = calls.filter(call => call.options?.method === 'POST');
assert.equal(trials.length, 3);
assert.ok(!trials[0].path.includes('confirmPaused=true'));
assert.ok(trials[1].path.includes('confirmPaused=true'));
assert.ok(!trials[1].path.includes('confirmQuiet=true'));
assert.ok(trials[2].path.includes('confirmQuiet=true'));
assert.equal(notices[0].kind, 'ok'); assert.equal(button.disabled, false); checks++;
// 防止取消确认仍发送，或重试时沿用上次确认。
for (const confirmation of ['paused', 'quiet']) {
  const host = mount('已发'); await until(() => text(host).includes('已发'));
  replies.push({success: false, message: '要先确认', confirmation}); confirmed.push(false);
  const button = nodes(host).find(node => node.tagName === 'button');
  await button.dispatchEvent({type: 'click', target: button});
  assert.equal(calls.filter(call => call.options?.method === 'POST').length, 1);
  assert.equal(button.disabled, false); checks++;
}
// 防止服务端拒绝试发后页面静默或谎报成功，导致继续误点。
for (const message of ['档案读不出，已停发，请先去主播页核对恢复', '还没有能试发的场次', '试发失败，请稍后重试']) {
  const host = mount('还没有场次'); await until(() => text(host).includes('还没有场次'));
  replies.push({success: false, message, confirmation: ''});
  const button = nodes(host).find(node => node.tagName === 'button');
  await button.dispatchEvent({type: 'click', target: button});
  assert.equal(notices[0].message, message); assert.equal(notices[0].kind, 'err');
  assert.equal(prompts.length, 0); assert.equal(button.disabled, false); checks++;
}
// 防止群或非本人好友出现可点的试发按钮。
for (const target of [{type: 1}, {streamerSelf: false}]) {
  const host = mount('还没有场次', {}, target);
  assert.equal(nodes(host).find(node => node.tagName === 'button').disabled, true); checks++;
}
console.log('跑了 ' + checks + ' 格，红 0 格');
