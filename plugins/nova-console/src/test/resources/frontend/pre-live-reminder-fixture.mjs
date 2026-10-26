import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {installPushDom} from './push-test-dom.mjs';
installPushDom();
const source = readFileSync(new URL('../../../main/resources/config-ui-pages/push.js', import.meta.url), 'utf8');
const start = source.indexOf('function preLiveReminderActions(');
assert.ok(start >= 0, '提醒订阅旁应能读取状态和试发');
assert.match(source, /if \(item.className === PRE_LIVE_REMINDER\) preLiveReminderActions\(text, user, target\)/,
  '未勾订阅也应能看状态，避免误以为开着');
const code = source.slice(start, source.indexOf('\n}\n', start) + 3);
const nodes = root => [root, ...root.children.flatMap(nodes)];
async function until(condition) {
  const deadline = Date.now() + 10000;
  while (!condition()) {
    assert.ok(Date.now() < deadline, '页面操作应完成');
    await new Promise(resolve => setImmediate(resolve));
  }
}
let calls = [], prompts = [], notices = [], replies = [], confirmed = [];
const context = vm.createContext({encodeURIComponent, Number,
  el: (tag, cls) => {const node = document.createElement(tag); node.className = cls || ''; return node;},
  api: async (path, options) => {calls.push({path, options}); const reply = replies.shift(); return typeof reply === 'function' ? reply() : reply;},
  ask: async spec => {prompts.push(spec); return confirmed.shift();},
  say: (message, kind) => notices.push({message, kind}),
});
vm.runInContext(code, context);
async function mount(extra = {}, target = {}) {
  calls = []; prompts = []; notices = []; confirmed = [];
  replies = [{success: true, status: '没勾选，不会发；还没发过', ...extra}];
  const host = document.createElement('div');
  context.preLiveReminderActions(host, {platform: 'test', uid: 910000001},
    {platform: 'qq-onebot', type: 0, num: 930000001, streamerSelf: true, ...target});
  await until(() => nodes(host).some(n => n.textContent.includes('没勾选，不会发')));
  assert.match(calls[0].path, /pre-live-reminder\/test\/910000001\/status\?pushPlatform=qq-onebot&type=[01]&num=930000001/);
  return host;
}
let checks = 0;
// 防止档案损坏却没有恢复入口。
let host = await mount({profileUnreadable: true});
assert.equal(nodes(host).find(n => n.tagName === 'a').hidden, false); checks++;
// 防止绕过暂停、静音和全局关闭确认，或误以为试发占用当天提醒。
host = await mount();
replies.push(...['paused', 'quiet', 'disabled'].map(confirmation => ({success: false, confirmation})),
  {success: true, message: '已试发；不记进发送记录，也不算当天那一条，当天定时的照常发'});
confirmed.push(true, true, true);
let button = nodes(host).find(n => n.tagName === 'button');
await button.dispatchEvent({type: 'click', target: button});
assert.equal(prompts.length, 3);
for (const prompt of prompts) assert.match(prompt.body, /不记进发送记录，也不算当天那一条，当天定时的照常发/);
assert.ok(calls.at(-1).path.includes('confirmDisabled=true'));
assert.match(notices[0].message, /不记进发送记录/); checks++;
// 防止取消后仍发送。
for (const confirmation of ['paused', 'quiet', 'disabled']) {
  host = await mount(); replies.push({success: false, confirmation}); confirmed.push(false);
  button = nodes(host).find(n => n.tagName === 'button');
  await button.dispatchEvent({type: 'click', target: button});
  assert.equal(calls.filter(c => c.options?.method === 'POST').length, 1);
  assert.equal(button.disabled, false); checks++;
}
// 防止请求未完成时连点重复发送。
host = await mount(); let finish;
replies.push(() => new Promise(resolve => {finish = resolve;}));
button = nodes(host).find(n => n.tagName === 'button');
const pending = button.dispatchEvent({type: 'click', target: button});
await until(() => !!finish); assert.equal(button.disabled, true);
await button.dispatchEvent({type: 'click', target: button});
assert.equal(calls.filter(c => c.options?.method === 'POST').length, 1);
finish({success: true, message: '已试发'}); await pending;
assert.equal(button.disabled, false); checks++;
// 防止将拒发显示成成功或向错误通道试发。
for (const message of ['她说过不要开播前提醒，试发也不发', '档案读不出，已停发，请先去主播页核对恢复', '试发失败，请稍后重试']) {
  host = await mount(); replies.push({success: false, message});
  button = nodes(host).find(n => n.tagName === 'button');
  await button.dispatchEvent({type: 'click', target: button});
  assert.equal(notices[0].message, message); assert.equal(notices[0].kind, 'err'); checks++;
}
for (const target of [{type: 1}, {streamerSelf: false}]) {
  host = await mount({}, target);
  assert.equal(nodes(host).find(n => n.tagName === 'button').disabled, true); checks++;
}
console.log('跑了 ' + checks + ' 格，红 0 格');
