/** 智能助手的状态、密钥、连通测试、配置者说明与用量。 */
import {$, api, el} from './core.js';
import {ask} from './confirm.js';
import {store} from './store.js';

let lastTest = '尚未测试';
let refreshCards = null;

async function assistantApi(path, options) {
  const result = await api('/assistant' + path, options);
  if (result.success === false) throw new Error(result.message || '操作失败');
  return result;
}

function acknowledged(status) {
  return !!status.acknowledgementVersion
    && status.acknowledgement?.version === status.acknowledgementVersion;
}

/** 开启前取当前说明；取消时不写确认记录，由设置行退回原值。 */
export async function confirmAssistantEnable() {
  const status = await assistantApi('/status');
  if (acknowledged(status)) return true;
  if (!status.acknowledgementText) throw new Error('无法获取配置者说明');
  if (!await ask({title: '启用智能助手', body: status.acknowledgementText,
    confirmText: '我已了解，开启', danger: false, scrollBody: true})) return false;
  try {
    await assistantApi('/acknowledge', {method: 'POST'});
  } finally {
    if (refreshCards) await refreshCards();
    else await assistantApi('/status');
  }
  return true;
}

function text(parent, tag, value, cls = '') {
  const node = el(tag, cls);
  node.textContent = value;
  parent.appendChild(node);
  return node;
}

function card(parent, title) {
  const box = el('section', 'alcard');
  const head = el('div', 'al-h');
  text(head, 'b', title);
  box.appendChild(head);
  const body = el('div', 'al-b');
  box.appendChild(body);
  parent.appendChild(box);
  return body;
}

/** 接口只向管理员开放；卡片操作当场生效，普通字段仍走设置页保存。 */
export function assistantCards(onRendered) {
  const wrap = el('div', 'alcards');
  wrap.id = 'assistant-cards';
  if (store.role === 'MEMBER') return wrap;

  const state = card(wrap, '状态');
  text(state, 'p', '正在加载…');
  const key = card(wrap, '模型密钥');
  const keyState = text(key, 'p', '正在加载…');
  const field = el('div', 'al-fld');
  const label = text(field, 'label', '新密钥');
  label.setAttribute('for', 'assistant-key');
  const input = el('input');
  input.id = 'assistant-key';
  input.type = 'password';
  input.name = 'assistant-model-key';
  input.autocomplete = 'new-password';
  input.spellcheck = false;
  input.setAttribute('autocapitalize', 'none');
  field.appendChild(input);
  key.appendChild(field);
  const keyActions = el('div', 'al-h');
  key.appendChild(keyActions);
  const keyResult = text(key, 'p', '', 'al-r');
  keyResult.setAttribute('role', 'status');

  const test = card(wrap, '测试连接');
  const testResult = text(test, 'p', lastTest, 'al-r');
  testResult.setAttribute('role', 'status');
  text(test, 'p', '测试会向模型发一句话，按用量计费', 'al-note');
  const acknowledgement = card(wrap, '配置者说明');
  const paragraphs = el('div');
  acknowledgement.appendChild(paragraphs);
  const accepted = text(acknowledgement, 'p', '正在加载…');
  const ackResult = text(acknowledgement, 'p', '', 'al-r');
  ackResult.setAttribute('role', 'status');
  const usage = card(wrap, '用量');
  text(usage, 'p', '正在加载…');

  let status = null;
  let busy = false;
  const buttons = [];
  const syncButtons = () => {
    for (const button of buttons) button.disabled = busy || !status;
    remove.disabled = busy || !status?.keySet;
    acknowledge.disabled = busy || !status || acknowledged(status);
  };
  const refresh = async () => {
    try {
      const [current, accounts] = await Promise.all([
        assistantApi('/status'), api('/accounts').catch(() => null),
      ]);
      status = current;
      const names = new Map((accounts?.accounts || []).map(account =>
        [account.id, account.displayName || account.email || account.id]));
      state.replaceChildren();
      text(state, 'p', status.available ? '现在可以使用' : '现在无法使用');
      if (!status.available) {
        const missing = el('ul');
        for (const reason of status.missing || []) text(missing, 'li', reason);
        state.appendChild(missing);
      }
      keyState.textContent = status.keySet ? status.maskedKey : '尚未设置';
      paragraphs.replaceChildren();
      for (const paragraph of String(status.acknowledgementText || '').split(/\r?\n/)) {
        text(paragraphs, 'p', paragraph);
      }
      const actor = status.acknowledgement?.actor;
      accepted.textContent = acknowledged(status)
        ? '由 ' + (names.get(actor) || actor) + ' 于 ' + status.acknowledgement.at + ' 确认'
        : status.acknowledgement ? '说明已更新，需要重新确认' : '尚未确认';
      usage.replaceChildren();
      text(usage, 'p', status.spentYuan === null ? '用量文件无法读取'
        : '本月已花 ' + status.spentYuan + ' 元／每月上限 ' + status.monthlyBudgetYuan + ' 元');
      if (status.spentYuan === null) text(usage, 'p', '每月上限 ' + status.monthlyBudgetYuan + ' 元');
      text(usage, 'p', '今天各账号的提问次数');
      const counts = el('ul');
      const entries = Object.entries(status.questionsToday || {});
      for (const [id, count] of entries) text(counts, 'li', (names.get(id) || id) + '：' + count);
      usage.appendChild(counts);
      if (!entries.length) text(usage, 'p', '今天暂无提问记录', 'al-note');
    } catch (error) {
      state.replaceChildren();
      text(state, 'p', error.message, 'al-r err');
    } finally {
      syncButtons();
      onRendered?.();
    }
  };
  refreshCards = refresh;

  const button = (parent, labelText, pendingText, result, action) => {
    const node = text(parent, 'button', labelText, 'nv-btn2 sm');
    node.type = 'button';
    buttons.push(node);
    node.addEventListener('click', async () => {
      if (busy) return;
      busy = true;
      syncButtons();
      node.textContent = pendingText;
      result.textContent = '';
      try {
        const message = await action();
        if (message) result.textContent = message;
      } catch (error) {
        result.textContent = error.message;
      } finally {
        if (result === testResult) lastTest = result.textContent || lastTest;
        await refresh();
        busy = false;
        node.textContent = labelText;
        syncButtons();
      }
    });
    return node;
  };
  button(keyActions, '保存', '正在验证密钥…', keyResult, async () => {
    await assistantApi('/key', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({key: input.value})});
    input.value = '';
    return '密钥已保存';
  });
  const remove = button(keyActions, '删除', '正在删除…', keyResult, async () => {
    if (!await ask({title: '删除模型密钥', body: '删除后智能助手将无法使用，确定删除？'})) return '';
    await assistantApi('/key', {method: 'DELETE'});
    return '密钥已删除';
  });
  button(test, '测试连接', '正在测试…', testResult, async () => {
    await assistantApi('/test', {method: 'POST'});
    return '连接测试成功';
  });
  const acknowledge = button(acknowledgement, '我已了解并确认', '正在确认…', ackResult, async () => {
    await assistantApi('/acknowledge', {method: 'POST'});
    return '已确认';
  });
  syncButtons();
  refresh();
  return wrap;
}

/** 卡片不进设置草稿；搜索按组名、标题和正文匹配，命中也计入组的可见项。 */
export function filterAssistantCards(query, onlyChanged) {
  const box = $('#assistant-cards');
  if (!box) return 0;
  const q = String(query || '').trim().toLowerCase();
  const groupHit = '智能助手'.includes(q);
  let shown = 0;
  for (const item of box.querySelectorAll('.alcard')) {
    const hit = !onlyChanged && (groupHit || (item.textContent || '').toLowerCase().includes(q));
    item.classList.toggle('hide', !hit);
    if (hit) shown++;
  }
  box.classList.toggle('hide', shown === 0);
  return shown;
}
