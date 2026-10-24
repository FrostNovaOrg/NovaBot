import {store} from './store.js';
import {api, el, say} from './core.js';

const conversations = new Map();
const settingsReasons = new Set(['disabled', 'no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget', 'key_rejected', 'provider_balance']);
const stateReasons = new Set(['disabled', 'no_key', 'usage_unreadable', 'not_acknowledged', 'daily_limit', 'monthly_budget']);
const adminReasons = {
  disabled: '智能助手还没开启。',
  no_key: '还没填模型密钥。',
  not_acknowledged: '还没确认配置者说明。',
  usage_unreadable: '用量记录读不出来，请检查后再试。',
  monthly_budget: '本月额度已用完。',
  daily_limit: '今天的提问次数用完了，明天再来。',
};
const memberReasons = {
  disabled: '管理员还没开启智能助手。',
  no_key: '智能助手暂时不能用，请联系管理员。',
  not_acknowledged: '智能助手暂时不能用，请联系管理员。',
  usage_unreadable: '智能助手暂时不能用，请联系管理员。',
  monthly_budget: '本月额度已用完，请联系管理员。',
  daily_limit: adminReasons.daily_limit,
};
const STYLE = `
.sa-assistant{display:grid;gap:14px;min-width:0}
.sa-assistant p{margin:6px 0;line-height:1.7;overflow-wrap:anywhere}
.sa-input{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.sa-input textarea{width:100%;box-sizing:border-box;resize:vertical;min-height:90px;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--ground);color:var(--text);font:inherit}
.sa-input textarea:focus-visible{outline:2px solid var(--accent)}
.sa-input .sa-disclaimer{width:100%;margin:0}
.sa-assistant .hint,.sa-label{color:var(--dim);font-size:var(--fs-sm)}
.sa-turn{padding:14px 0;border-bottom:1px solid var(--line);min-width:0}
.sa-question{font-weight:600;white-space:pre-wrap}
.sa-answer,.sa-copy-text{white-space:pre-wrap;overflow-wrap:anywhere}
.sa-turn summary{cursor:pointer;color:var(--accent-text);margin:10px 0}
.sa-turn .nv-btn2{margin-top:8px}
.sa-clear{justify-self:end}
`;

function conversation({platform, uid}) {
  const key = JSON.stringify([platform, String(uid)]);
  if (!conversations.has(key)) conversations.set(key, {
    path: '/streamers/' + encodeURIComponent(platform) + '/' + encodeURIComponent(uid) + '/assistant',
    state: null, stateError: '', stateRequest: null, questionsAfterRead: null,
    turns: [], pending: false, draft: '', listeners: new Set(),
  });
  return conversations.get(key);
}

function notify(context) {
  for (const listener of context.listeners) listener();
}

async function readState(context) {
  if (context.stateRequest) return context.stateRequest;
  context.state = null;
  context.stateError = '';
  context.questionsAfterRead = null;
  context.stateRequest = (async () => {
    try {
      const result = await api(context.path + '/state');
      if (result.success === false) throw new Error(result.message);
      if (context.questionsAfterRead != null) result.questionsLeftToday = context.questionsAfterRead;
      context.state = result;
    } catch (error) {
      context.stateError = error.message;
    } finally {
      context.stateRequest = null;
      notify(context);
    }
  })();
  notify(context);
  return context.stateRequest;
}

function settingLink() {
  const link = el('a', 'lnkbtn');
  link.href = '#/settings?card=assistant';
  link.textContent = '前往设置 → 智能助手';
  return link;
}

function line(host, text, className = '') {
  const node = el('p', className);
  node.textContent = text;
  host.appendChild(node);
  return node;
}

/** 页头订阅状态，成员只在成功读到总开关开启后显示入口。 */
export function mountAssistantEntry(host, identity, href) {
  const context = conversation(identity);
  const link = el('a', 'nv-btn2');
  link.href = href; link.textContent = '智能助手';
  const update = () => {
    link.remove();
    if (store.role !== 'MEMBER' || context.state?.enabled === true) host.appendChild(link);
  };
  context.listeners.add(update);
  update();
  const ready = store.role === 'MEMBER' ? readState(context) : Promise.resolve();
  return {ready, updateHref(value) { link.href = value; }, destroy() { context.listeners.delete(update); link.remove(); }};
}

function money(value) {
  const amount = Number(value);
  return value == null || !Number.isFinite(amount) ? '—' : amount.toFixed(4).replace(/\.?0+$/, '');
}

async function copyAnswer(host, answer) {
  const text = answer + '\n（以上内容由人工智能生成，仅供参考）';
  try {
    await navigator.clipboard.writeText(text);
    say('已复制回答', 'ok');
  } catch (error) {
    let content = host.querySelector('.sa-copy-text');
    if (!content) content = line(host, text, 'sa-copy-text');
    const range = document.createRange();
    range.selectNodeContents(content);
    const selection = window.getSelection();
    selection.removeAllRanges(); selection.addRange(range);
    say('浏览器不允许脚本写入剪贴板，已选中回答，请按 Ctrl/⌘+C 复制', 'err');
  }
}

function renderTurn(host, turn) {
  const box = el('section', 'sa-turn');
  host.appendChild(box);
  line(box, turn.question, 'sa-question');
  if (turn.pending) {
    line(box, '正在查数并回答，最长约一分钟…', 'hint');
    return;
  }
  const result = turn.result;
  if (!result || result.answered !== true) {
    const note = line(box, turn.error || result?.answer || '未能回答，请稍后再试。', 'hint');
    if (settingsReasons.has(result?.reason)) {
      if (store.role === 'MEMBER') note.append('请联系管理员。');
      else { note.append(' '); note.appendChild(settingLink()); }
    }
    return;
  }
  line(box, result.answer, 'sa-answer');
  line(box, '由人工智能生成 · 仅供参考', 'sa-label');
  if (result.unverifiedNumbers?.length) line(box, '这几个数字没能在查到的数据里核对上：'
    + result.unverifiedNumbers.join('、') + '。请以报告为准。', 'hint');
  if (result.steps?.length) {
    const fold = el('details'), summary = el('summary');
    summary.textContent = '查了 ' + result.steps.length + ' 项数据';
    fold.appendChild(summary);
    for (const step of result.steps) line(fold, step.source, 'hint');
    box.appendChild(fold);
  }
  const copy = el('button', 'nv-btn2');
  copy.type = 'button'; copy.textContent = '复制';
  copy.addEventListener('click', () => copyAnswer(box, result.answer));
  box.appendChild(copy);
  if (store.role !== 'MEMBER') line(box, '本问约 ¥' + money(result.costYuan)
    + ' · 本月已用 ¥' + money(result.remaining?.spentYuan) + '，上限 ¥' + money(result.remaining?.monthlyBudgetYuan), 'hint');
}

/** 对话只存在本页面内存；撤掉视图后，进行中的请求仍写回对应主播。 */
export function mountAssistant(host, identity) {
  const context = conversation(identity);
  const style = el('style'); style.textContent = STYLE;
  const root = el('div', 'sa-assistant');
  const status = el('div'); status.setAttribute('role', 'status');
  const turns = el('div'); turns.setAttribute('aria-live', 'polite');
  const clear = el('button', 'nv-btn2 sa-clear'); clear.type = 'button'; clear.textContent = '清空对话';
  clear.addEventListener('click', () => { if (!context.pending) { context.turns = []; notify(context); } });
  const form = el('form'), controls = el('div', 'sa-input');
  const input = el('textarea'); input.rows = 3; input.maxLength = 600;
  input.setAttribute('aria-label', '向智能助手提问，最多 300 字');
  input.value = context.draft;
  const submit = el('button', 'nv-btn'); submit.type = 'submit'; submit.textContent = '提问';
  const remaining = el('span', 'hint');
  controls.appendChild(input);
  line(controls, '回答由人工智能生成，可能出错；数字以报告为准。', 'hint sa-disclaimer');
  controls.append(submit, remaining);
  form.appendChild(controls);
  root.append(status, turns, form); host.replaceChildren(style, root);

  const updateControls = () => {
    input.disabled = context.pending || context.state?.available !== true;
    submit.disabled = input.disabled || !context.draft.trim();
    remaining.textContent = context.state?.available ? '今天还能问 ' + context.state.questionsLeftToday + ' 次' : '';
    clear.disabled = context.pending;
  };
  input.addEventListener('input', () => {
    input.value = [...input.value].slice(0, 300).join('');
    context.draft = input.value;
    updateControls();
  });
  const update = () => {
    status.replaceChildren();
    if (context.stateError) line(status, context.stateError, 'hint');
    else if (!context.state) line(status, '正在读取智能助手状态…', 'hint');
    else if (!context.state.available) {
      const member = store.role === 'MEMBER', reasons = member ? memberReasons : adminReasons;
      const shown = new Set();
      for (const reason of context.state.reasons || []) {
        const text = reasons[reason];
        if (!text || shown.has(text)) continue;
        shown.add(text);
        const note = line(status, text, 'hint');
        if (!member && settingsReasons.has(reason)) { note.append(' '); note.appendChild(settingLink()); }
      }
    }
    turns.replaceChildren();
    for (const turn of context.turns) renderTurn(turns, turn);
    clear.remove();
    if (context.turns.length) root.insertBefore(clear, form);
    updateControls();
  };
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (context.pending || context.state?.available !== true) return;
    const question = [...input.value].slice(0, 300).join('');
    if (!question.trim()) return;
    const history = context.turns.filter(turn => turn.result?.answered === true
      && typeof turn.result.answerForHistory === 'string' && turn.result.answerForHistory.length <= 16000)
      .slice(-3).map(turn => ({question: turn.question, answerForHistory: turn.result.answerForHistory}));
    const turn = {question, pending: true};
    context.turns.push(turn); context.pending = true; context.draft = ''; input.value = '';
    notify(context);
    try {
      const result = await api(context.path + '/ask', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({question, history}),
      });
      if (result.success === false) turn.error = result.message;
      else {
        turn.result = result;
        if (result.remaining?.questionsToday != null) {
          context.questionsAfterRead = result.remaining.questionsToday;
          if (context.state) context.state.questionsLeftToday = result.remaining.questionsToday;
        }
        if (result.answered === false && stateReasons.has(result.reason)) await readState(context);
      }
    } catch (error) {
      turn.error = error.message;
    } finally {
      turn.pending = false; context.pending = false;
      notify(context);
    }
  });
  context.listeners.add(update);
  const ready = readState(context);
  update();
  return {ready, destroy() { context.listeners.delete(update); }};
}
