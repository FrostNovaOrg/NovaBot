import assert from 'node:assert/strict';
import './layout-dom.mjs';
import {bindBotForm, botFormHtml} from '../../../main/resources/config-ui/bot.js';

const form = document.createElement('div');
form.innerHTML = botFormHtml('bot') + '<input type="checkbox" id="bot-channel-enabled">';
document.body.appendChild(form);

const fieldKeys = ['addr', 'hport', 'htoken', 'wport', 'wtoken'];
const node = key => form.querySelector('#bot-' + key);
for (const key of fieldKeys) node(key).value = node(key).getAttribute('value');
const initialAddress = node('addr').value;

const response = data => ({status: 200, json: async () => data});
const tested = [];
const saved = [];
let pendingReply;
globalThis.fetch = async (path, options) => {
  const {address} = JSON.parse(options.body);
  if (path === '/config/api/setup/test-bot') {
    tested.push(address);
    pendingReply = Promise.withResolvers();
    return response(await pendingReply.promise);
  }
  assert.equal(path, '/config/api/setup/bot');
  saved.push(address);
  return response({success: true, message: '已保存'});
};
bindBotForm('bot');

function edit(key, value) {
  const input = node(key);
  input.value = value;
  input.dispatchEvent({type: 'input'});
}

function click(key) {
  const button = node(key);
  assert.equal(button.disabled, false, key + ' 按钮未启用');
  const [handler] = button.listeners.click || [];
  assert.ok(handler, key + ' 按钮没有绑定点击处理');
  return handler();
}

function beginTest() {
  pendingReply = undefined;
  const done = click('test');
  assert.ok(pendingReply, '点击测试连接没有发送请求');
  return {...pendingReply, done};
}

async function testSuccessfully() {
  const request = beginTest();
  request.resolve({success: true, message: '连接正常'});
  await request.done;
  assert.equal(node('save').disabled, false, '测通后仍不能保存');
}

if (process.argv[2] === 'retest') {
  // 测通后改任一字段，都必须阻止保存没测过的参数。
  for (const key of fieldKeys) {
    await testSuccessfully();
    edit(key, node(key).value + '1');
    assert.equal(node('save').disabled, true, '修改 ' + key + ' 后仍可保存');
  }
  assert.match(node('test-result').textContent, /重新测试连接/);

  await testSuccessfully();
  const failed = beginTest();
  failed.reject(new Error('连接不可用'));
  await failed.done;
  assert.equal(node('save').disabled, true, '重测失败后仍可保存');
  console.log('跑了 3 格：修改任一字段锁定保存、提示重测、测试失败后锁定保存');
} else {
  // 回包前修改地址，先前发出的测试即使通过也不得放行。
  const request = beginTest();
  const changedAddress = '127.0.0.2';
  edit('addr', changedAddress);
  request.resolve({success: true, message: '连接正常'});
  await request.done;
  assert.deepEqual(tested, [initialAddress]);
  assert.equal(node('save').disabled, true, '旧回包放行了没有测试的地址');
  assert.match(node('test-result').textContent, /重新测试连接/);

  // 新地址测通后，保存请求只写入真正测过的地址。
  await testSuccessfully();
  assert.deepEqual(tested, [initialAddress, changedAddress]);
  await click('save');
  assert.deepEqual(saved, [changedAddress]);
  assert.match(node('save-result').textContent, /已保存/);
  console.log('跑了 2 格：回包前改地址仍需重测，回包前未改时测通可保存');
}
