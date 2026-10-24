/** 搜索组名时，管理员仍能看到全部五张智能助手管理卡片。 */
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';

function node(tag, cls = '') {
  const classes = new Set(cls.split(/\s+/).filter(Boolean));
  let content = '';
  return {
    tag, kids: [],
    get textContent() { return content + this.kids.map(child => child.textContent).join(''); },
    set textContent(value) { content = String(value); this.kids = []; },
    classList: {
      contains: name => classes.has(name),
      toggle(name, on) { if (on) classes.add(name); else classes.delete(name); },
    },
    appendChild(child) { this.kids.push(child); return child; },
    replaceChildren(...children) { content = ''; this.kids = children; },
    setAttribute() {},
    addEventListener() {},
    querySelectorAll(selector) {
      return this.kids.flatMap(child => [
        ...(selector === '.alcard' && child.classList.contains('alcard') ? [child] : []),
        ...child.querySelectorAll(selector),
      ]);
    },
  };
}

const source = readFileSync(new URL('../../../main/resources/config-ui/settings-assistant.js', import.meta.url), 'utf8')
  .replace(/^import .*?;\s*$/gm, '').replace(/export /g, '');
let wrap;
const api = async path => path === '/accounts' ? {accounts: []} : {
  available: true, keySet: true, maskedKey: 'sk-****abcd',
  acknowledgementVersion: 'current', acknowledgementText: '智能助手会向模型服务发送提问内容。',
  acknowledgement: {version: 'current', actor: 'admin', at: '2026-10-24 10:00'},
  spentYuan: 1, monthlyBudgetYuan: 30, questionsToday: {},
};
const {assistantCards, filterAssistantCards} = new Function('$', 'api', 'el', 'ask', 'store',
  source + '\nreturn {assistantCards, filterAssistantCards};')(
  selector => selector === '#assistant-cards' ? wrap : null,
  api, node, async () => false, {role: 'ADMIN'});
let rendered;
const ready = new Promise(resolve => { rendered = resolve; });
wrap = assistantCards(rendered);
await ready;
filterAssistantCards('智能助手', false);
const visible = wrap.classList.contains('hide') ? [] : wrap.querySelectorAll('.alcard')
  .filter(card => !card.classList.contains('hide'))
  .map(card => card.kids[0].textContent);
try {
  assert.deepEqual(visible, ['状态', '模型密钥', '测试连接', '配置者说明', '用量']);
  process.stdout.write('跑了 1 格，红 0 格\n');
} catch (error) {
  process.stdout.write('跑了 1 格，红 1 格\n搜索智能助手时五张卡全部可见：' + error.message + '\n');
  process.exitCode = 1;
}
