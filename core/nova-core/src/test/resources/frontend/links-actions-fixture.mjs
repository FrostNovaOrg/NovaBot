import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import './layout-dom.mjs';

const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const encodeModule = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
function loadModule(name, replacements) {
  const source = readFileSync(new URL(name, ui), 'utf8').replace(/from '\.\/([^']+)'/g,
    (_, file) => 'from ' + JSON.stringify(replacements[file] || new URL(file, ui).href));
  return import(encodeModule(source));
}

const ids = ['test-target', 'test-hint', 'test-refresh', 'test-refresh-result', 'test-send', 'test-result',
  'bot-channel', 'bot-channel-enabled', 'bot-channel-label', 'bot-channel-alert', 'bot-channel-result',
  'bot-test', 'bot-save', 'bot-out', 'bot-test-result', 'bot-save-result', 'bot-settings', 'bot-intro', 'bot-toggle-settings'];
const nodes = Object.fromEntries(ids.map(id => {
  const node = document.createElement('div');
  node.id = id;
  document.body.appendChild(node);
  return ['#' + id, node];
}));
const response = data => ({status: 200, json: async () => data});

if (process.argv[2] === 'send') {
  // 后发的官方群先返回失败。早发的成功即使此时选中官方群，也必须显示 NapCat 群名。
  const replies = new Map();
  globalThis.fetch = async (path, opts) => {
    if (path.endsWith('/status')) return response({});
    if (path.endsWith('type=group')) return response({items: [
      {name: '演示 NapCat 群', sender: 'qq', num: '901'},
      {name: '演示官方群', sender: 'qq-official', num: '902'}
    ]});
    if (path.endsWith('type=friend')) return response({items: []});
    const target = JSON.parse(opts.body);
    return new Promise(resolve => replies.set(target.num, data => resolve(response(data))));
  };

  const {loadTargets, sendTestMessage} = await loadModule('links.js', {
    'overview.js': encodeModule('export function renderStatus() {}'),
    'tokens.js': encodeModule('export function renderTokens() {}')
  });
  await loadTargets(false);
  const pick = nodes['#test-target'];
  pick.value = 'qq|1|901';
  pick.onchange?.();
  const first = sendTestMessage();
  pick.value = 'qq-official|1|902';
  pick.onchange?.();
  const canSendToSecond = !nodes['#test-send'].disabled;
  const second = sendTestMessage();
  replies.get('902')({success: false, message: '发送失败：主动发言未开'});
  await second;
  replies.get('901')({success: true, message: '已发送'});
  await first;

  const boxes = nodes['#test-result'].children;
  assert.equal(boxes.length, 2, '两次试发没有各自保留一条结果');
  // 有详细说明时，第一项是完整结果标题，其余子节点显示说明。
  const resultLine = box => box.children[0]?.textContent ?? box.textContent;
  assert.equal(resultLine(boxes[0]), '群 · 演示 NapCat 群（901） · 已发送',
    '先发的成功没有归到 NapCat 群');
  assert.equal(resultLine(boxes[1]), '群 · 演示官方群（902） · 发送失败：主动发言未开',
    '后发的失败没有归到官方群');
  assert.ok(canSendToSecond, '另一笔发送中，无法给不同的群试发');
  console.log('跑了 1 格：NapCat 群和官方群交错返回，结果分别显示');
} else {
  // 关通道必须确认。启用失败时恢复为停用，错误留在开关旁。
  let confirm = false;
  const questions = [];
  const saved = [];
  globalThis.askAboutChannel = async question => { questions.push(question); return confirm; };
  globalThis.fetch = async (path, opts) => {
    assert.equal(path, '/config/api/values');
    const value = JSON.parse(opts.body).enabled;
    saved.push(value);
    return response(value === 'false' ? {success: true} : {success: false, message: '配置无法写入'});
  };
  const {renderBotChannel} = await loadModule('bot.js', {
    'confirm.js': encodeModule('export const ask = globalThis.askAboutChannel;')
  });
  const toggle = nodes['#bot-channel-enabled'];
  renderBotChannel('bot', {enabled: true, name: 'NapCat', key: 'enabled'}, async () => {});
  toggle.checked = false;
  await toggle.onchange();
  assert.deepEqual(saved, [], '取消确认仍停用了 NapCat 通道');
  assert.match(questions.at(-1).body, /群|好友/);
  assert.equal(toggle.checked, true);
  confirm = true;
  toggle.checked = false;
  await toggle.onchange();
  assert.deepEqual(saved, ['false']);
  assert.match(nodes['#bot-channel-result'].textContent, /已保存/);
  toggle.checked = true;
  await toggle.onchange();
  assert.deepEqual(saved, ['false', 'true']);
  assert.equal(toggle.checked, false, '保存失败后 NapCat 开关没有拨回');
  assert.match(nodes['#bot-channel-result'].textContent, /配置无法写入/);
  console.log('跑了 1 格：NapCat 停用确认、拨动保存与失败恢复');
}
