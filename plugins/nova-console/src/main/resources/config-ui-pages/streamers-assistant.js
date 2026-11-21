import {store} from './store.js';
import {api, el, say} from './core.js';

const conversations = new Map();
const settingsReasons = new Set(['disabled', 'no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget', 'key_rejected', 'provider_balance']);
const stateReasons = new Set(['disabled', 'no_key', 'usage_unreadable', 'not_acknowledged', 'daily_limit', 'monthly_budget']);
const questionExamples = ['这个月心动盲盒收了多少个', '最近 30 天各钟点平均在线', '上周送礼榜前十'];
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
.sa-guidance{display:flex;gap:6px;flex-wrap:wrap}
.sa-guidance[hidden]{display:none}
.sa-guidance .nv-btn2{min-height:36px;max-width:100%;margin:0;white-space:normal;text-align:left;overflow-wrap:anywhere}
.sa-examples{width:100%}
.sa-follow-ups{margin-top:10px}
.sa-clear{justify-self:end}
.sa-charts{display:grid;gap:10px;margin-top:10px}
.sa-chart{display:block;width:min(100%,480px);padding:0;overflow:hidden;border:1px solid var(--line);border-radius:10px;background:white;cursor:zoom-in}
.sa-chart:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.sa-chart img,.sa-chart-view img{display:block;width:100%;height:auto}
.sa-chart-view{box-sizing:border-box;width:min(820px,calc(100% - 20px));max-height:calc(100% - 20px);padding:12px;border:1px solid var(--line);border-radius:12px;background:var(--ground);color:var(--text);overflow:auto}
.sa-chart-view::backdrop{background:#0009}
.sa-chart-toolbar{display:flex;justify-content:flex-end;gap:8px;margin-bottom:10px}
.sa-chart-scroll{max-height:calc(100dvh - 100px);overflow:auto}
.sa-chart-scroll[data-zoom="true"] img{width:auto;max-width:none}
@media (min-width:840px){.sa-chart-size{display:none}}
@media (max-width:520px){.sa-chart-view{width:100%;max-width:100%;padding:10px 0}.sa-chart-toolbar{padding:0 10px}}
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

function renderTurn(host, turn, openChart, askFollowUp) {
  const box = el('section', 'sa-turn');
  host.appendChild(box);
  line(box, turn.label ?? turn.question, 'sa-question');
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
  if (Array.isArray(result.images)) {
    const charts = el('div', 'sa-charts');
    for (const png of result.images.slice(0, 2)) {
      if (typeof png !== 'string' || !/^iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(png)) continue;
      const src = 'data:image/png;base64,' + png;
      const preview = el('button', 'sa-chart'); preview.type = 'button';
      const image = el('img'); image.src = src; image.alt = '查看图表大图'; image.loading = 'lazy';
      preview.appendChild(image);
      preview.addEventListener('click', () => openChart(src));
      charts.appendChild(preview);
    }
    if (charts.firstChild) box.appendChild(charts);
  }
  if (result.unverifiedNumbers?.length) line(box, '这几个数字没能在查到的数据里核对上：'
    + result.unverifiedNumbers.join('、') + '。请以报告为准。', 'hint');
  if (result.steps?.length) {
    const fold = el('details'), summary = el('summary');
    summary.textContent = '查了 ' + result.steps.length + ' 项数据';
    fold.appendChild(summary);
    for (const step of result.steps) line(fold, step.source, 'hint');
    box.appendChild(fold);
  }
  const options = Array.isArray(result.followUps) ? result.followUps.slice(0, 2) : [];
  const copy = el('button', 'nv-btn2');
  copy.type = 'button'; copy.textContent = '复制';
  copy.addEventListener('click', () => copyAnswer(box, [result.answer,
    ...options.map((option, index) => `${index + 1}. ${option.label}`)].join('\n')));
  box.appendChild(copy);
  if (store.role !== 'MEMBER') line(box, '本问约 ¥' + money(result.costYuan)
    + ' · 本月已用 ¥' + money(result.remaining?.spentYuan) + '，上限 ¥' + money(result.remaining?.monthlyBudgetYuan), 'hint');
  if (options.length) {
    const guidance = el('div', askFollowUp ? 'sa-guidance sa-follow-ups' : 'sa-follow-ups');
    guidance.setAttribute('role', 'group'); guidance.setAttribute('aria-label', '下一问');
    options.forEach((option, index) => {
      const label = `${index + 1}. ${option.label}`;
      if (!askFollowUp) { line(guidance, label, 'hint'); return; }
      const button = el('button', 'nv-btn2'); button.type = 'button'; button.textContent = label;
      button.addEventListener('click', () => askFollowUp(option.question, option.label));
      guidance.appendChild(button);
    });
    box.appendChild(guidance);
  }
}

/** 对话只存在本页面内存；撤掉视图后，进行中的请求仍写回对应主播。 */
export function mountAssistant(host, identity) {
  const context = conversation(identity);
  const style = el('style'); style.textContent = STYLE;
  const root = el('div', 'sa-assistant');
  const chartView = el('dialog', 'sa-chart-view'); chartView.setAttribute('aria-label', '智能助手图表');
  const toolbar = el('div', 'sa-chart-toolbar');
  const chartScroll = el('div', 'sa-chart-scroll');
  const plot = el('img'); plot.alt = '智能助手图表 · 由人工智能生成';
  const sizeChart = el('button', 'nv-btn2 sa-chart-size'); sizeChart.type = 'button';
  const zoomChart = zoom => {
    chartScroll.dataset.zoom = String(zoom);
    sizeChart.textContent = zoom ? '适应屏幕' : '查看原图尺寸';
  };
  sizeChart.addEventListener('click', () => zoomChart(chartScroll.dataset.zoom !== 'true'));
  const closeChart = el('button', 'nv-btn2'); closeChart.type = 'button'; closeChart.textContent = '关闭大图';
  closeChart.addEventListener('click', () => chartView.close());
  chartView.addEventListener('close', () => plot.removeAttribute('src'));
  toolbar.append(sizeChart, closeChart); chartScroll.appendChild(plot);
  chartView.append(toolbar, chartScroll);
  const openChart = src => {
    zoomChart(false);
    plot.src = src; chartView.showModal();
    chartScroll.scrollTop = chartScroll.scrollLeft = 0;
  };
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
  const examples = el('div', 'sa-guidance sa-examples');
  examples.setAttribute('role', 'group'); examples.setAttribute('aria-label', '提问示例');
  for (const question of questionExamples) {
    const example = el('button', 'nv-btn2'); example.type = 'button'; example.textContent = question;
    example.addEventListener('click', () => {
      input.value = question;
      updateDraft();
      input.focus();
    });
    examples.appendChild(example);
  }
  controls.append(input, examples);
  line(controls, '回答由人工智能生成，可能出错；数字以报告为准。', 'hint sa-disclaimer');
  controls.append(submit, remaining);
  form.appendChild(controls);
  root.append(status, turns, form, chartView); host.replaceChildren(style, root);

  const canAsk = () => !context.pending && context.state?.available === true;
  const updateControls = () => {
    input.disabled = !canAsk();
    submit.disabled = input.disabled || !context.draft.trim();
    examples.hidden = input.disabled || Boolean(context.draft.trim());
    remaining.textContent = context.state?.available ? '今天还能问 ' + context.state.questionsLeftToday + ' 次' : '';
    clear.disabled = context.pending;
  };
  const updateDraft = () => {
    input.value = [...input.value].slice(0, 300).join('');
    context.draft = input.value;
    updateControls();
  };
  input.addEventListener('input', updateDraft);
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
    const latest = canAsk() ? context.turns.at(-1) : null;
    for (const turn of context.turns) renderTurn(turns, turn, openChart, turn === latest ? askQuestion : null);
    clear.remove();
    if (context.turns.length) root.insertBefore(clear, form);
    updateControls();
  };
  const askQuestion = async (text, label) => {
    if (!canAsk()) return;
    const question = [...text].slice(0, 300).join('');
    if (!question.trim()) return;
    const history = context.turns.filter(turn => turn.result?.answered === true
      && typeof turn.result.answerForHistory === 'string' && turn.result.answerForHistory.length <= 16000)
      .slice(-3).map(turn => ({question: turn.question, answerForHistory: turn.result.answerForHistory}));
    const turn = {question, label, pending: true};
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
  };
  form.addEventListener('submit', event => {
    event.preventDefault();
    return askQuestion(input.value);
  });
  context.listeners.add(update);
  const ready = readState(context);
  update();
  return {ready, destroy() { if (chartView.open) chartView.close(); context.listeners.delete(update); }};
}
