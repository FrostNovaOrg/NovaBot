import {api, esc, loadingHtml} from './core.js';
import {refreshLinks} from './links.js';
import {store} from './store.js';

const BASE = '/qqbot/';
let card;

export function render(container) {
  container.innerHTML = `
    <p class="hint">请先在 QQ 开放平台创建机器人，再把 AppID 和 AppSecret 填在这里。开放平台后台的 IP 白名单留空。</p>
    <form class="fbox" id="qqbot-credentials">
      <div class="row">
        <label for="qqbot-app-id">AppID</label>
        <input id="qqbot-app-id" type="text" inputmode="numeric" autocomplete="off" required>
      </div>
      <div class="row">
        <label for="qqbot-secret">AppSecret</label>
        <input id="qqbot-secret" type="password" autocomplete="new-password">
      </div>
      <p class="hint" id="qqbot-unconfigured" hidden>请填写 AppID 和 AppSecret 后保存连接。</p>
      <button type="submit" class="primary">保存并连接</button>
    </form>
    <form class="fbox lc-sub" id="qqbot-interval">
      <div class="row">
        <label for="qqbot-millis">发送间隔（毫秒）</label>
        <input id="qqbot-millis" type="number" min="1000" max="60000" step="1" required>
        <span class="dim">默认每 2 秒一条，即每分钟 30 条</span>
      </div>
      <button type="submit" class="ghost sm">保存间隔</button>
    </form>
    <section class="lc-sub">
      <h3 class="lc-h">已加入的群</h3>
      <p class="hint">把机器人拉进群（个人主体要求你是群主或管理员），请群主打开主动发言。然后就可以在推送页选择这个群。</p>
      <p class="hint">群名留空并保存，可恢复接口群名或默认名。</p>
      <button type="button" class="ghost sm" id="qqbot-refresh">刷新状态和群列表</button>
      <p class="dim" id="qqbot-load" role="status">${loadingHtml('正在加载…', true)}</p>
      <div class="tblwrap" id="qqbot-groups"></div>
    </section>
    <p id="qqbot-result" class="hint" role="status" hidden></p>`;

  const find = id => container.querySelector('#qqbot-' + id);
  const view = {appId: find('app-id'), secret: find('secret'), interval: find('millis'), groups: find('groups'),
    unconfigured: find('unconfigured'), load: find('load'), result: find('result'), request: 0, saving: false};
  card = view;

  find('credentials').addEventListener('submit', event => {
    const appId = view.appId.value.trim();
    return save(view, event, 'credentials', {appId, appSecret: view.secret.value}, '凭据已保存', () => {
      view.secret.value = '';
      setSavedValue(view.appId, appId, true);
      return refreshLinks();
    });
  });
  find('interval').addEventListener('submit', event => {
    return save(view, event, 'interval', {millis: view.interval.valueAsNumber}, '发送间隔已保存', result => {
      setSavedValue(view.interval, String(result.intervalMillis), true);
    });
  });
  find('refresh').addEventListener('click', () => Promise.all([refresh(), refreshLinks()]));
  view.groups.addEventListener('submit', event => {
    const form = event.target;
    const input = form.querySelector('input');
    return save(view, event, 'groups/' + encodeURIComponent(form.dataset.groupNumber) + '/name',
      {name: input.value}, '群名已保存', () => { input.defaultValue = input.value; });
  });
}

export async function refresh() {
  if (!card || store.tab !== 'bot') return;
  const view = card;
  const request = ++view.request;
  try {
    const state = await api(BASE + 'state', {cache: 'no-store'});
    if (request !== view.request) return;
    if (!state.success) throw new Error(state.message || '无法读取机器人状态');
    setSavedValue(view.appId, state.appId || '');
    view.secret.placeholder = state.secretMasked ? '已保存：' + state.secretMasked : '';
    view.unconfigured.hidden = state.configured;
    setSavedValue(view.interval, String(state.intervalMillis));
    renderGroups(view.groups, state.groups || []);
    view.load.textContent = '';
  } catch (error) {
    if (request === view.request) view.load.textContent = '加载失败：' + error.message;
  }
}

async function save(view, event, path, body, message, onSaved) {
  event.preventDefault();
  if (view.saving) return;
  const button = event.submitter || event.target.querySelector('button[type="submit"]');
  const inputs = event.target.querySelectorAll('input');
  view.saving = true;
  button.disabled = true;
  inputs.forEach(input => { input.disabled = true; });
  showResult(view, '正在保存…');
  try {
    const result = await api(BASE + path, {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)});
    if (!result.success) throw new Error(result.message || '保存失败');
    const followUp = onSaved(result);
    showResult(view, result.warning || message, !!result.warning);
    await Promise.all([refresh(), followUp]);
  } catch (error) {
    showResult(view, error.message, true);
  } finally {
    view.saving = false;
    button.disabled = false;
    inputs.forEach(input => { input.disabled = false; });
  }
}

function showResult(view, message, notice = false) {
  view.result.hidden = !message;
  view.result.className = notice ? 'notice' : 'hint';
  view.result.textContent = message;
}

function setSavedValue(input, value, replace = false) {
  const draft = !replace && input.value !== input.defaultValue ? input.value : value;
  input.defaultValue = value;
  input.value = draft;
}

function renderGroups(container, groups) {
  const drafts = new Map();
  for (const form of container.querySelectorAll('[data-group-number]')) {
    const input = form.querySelector('input');
    if (input.value !== input.defaultValue) drafts.set(form.dataset.groupNumber, input.value);
  }
  if (!groups.length) {
    container.innerHTML = '<p class="hint">机器人尚未加入群。</p>';
    return;
  }
  container.innerHTML = `<table aria-label="已加入的群">
    <thead><tr><th>群名</th><th>人数</th><th>机器人是管理员</th><th>主动发言</th><th>最近失败原因</th></tr></thead>
    <tbody>${groups.map(groupRow).join('')}</tbody></table>`;
  for (const form of container.querySelectorAll('[data-group-number]')) {
    const value = drafts.get(form.dataset.groupNumber);
    if (value !== undefined) form.querySelector('input').value = value;
  }
}

function groupRow(group) {
  const admin = group.role == null ? '不知道' : group.role === 'MEMBER' ? '否' : '是';
  const proactive = group.proactive === 'ENABLED' ? '开' : group.proactive === 'DISABLED' ? '未开' : '不知道';
  const help = group.proactive === 'DISABLED'
    ? '<div class="hint">手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开『机器人主动在群聊内发言』</div>'
    : '';
  return `<tr${group.removed ? ' class="dim"' : ''}>
    <td><form class="fbox" data-group-number="${esc(group.num)}">
      <div class="row"><input type="text" aria-label="群名" value="${esc(group.name)}">
        <button type="submit" class="ghost sm">保存群名</button></div>
      ${group.removed ? '<span>已移出</span>' : ''}
    </form></td>
    <td>${esc(group.memberCount ?? '—')}</td><td>${admin}</td>
    <td>${proactive}${help}</td><td>${esc(group.lastFailure || '—')}</td>
  </tr>`;
}
