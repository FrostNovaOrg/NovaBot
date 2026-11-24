import {api, esc, loadingHtml} from './core.js';
import {refreshLinks} from './links.js';
import {store} from './store.js';

const BASE = '/qqbot/';
let card;

export const alternateSetupLabel = 'NapCat（用 QQ 号登录）';

export function render(container, {onConnectionChange} = {}) {
  const inSetup = !!onConnectionChange;
  const prefix = inSetup ? 'setup-qqbot-' : 'qqbot-';
  const intro = inSetup
    ? '请先在 QQ 开放平台创建机器人，IP 白名单留空。个人主体的机器人只能加入你担任群主或管理员的群。'
    : '请先在 QQ 开放平台创建机器人，再把 AppID 和 AppSecret 填在这里。开放平台后台的 IP 白名单留空。';
  const loading = `<p class="dim" id="${prefix}load" role="status">${loadingHtml('正在加载…', true)}</p>`;
  container.innerHTML = `
    <p class="hint">${intro}</p>
    <form class="fbox" id="${prefix}credentials">
      <div class="row">
        <label for="${prefix}app-id">AppID</label>
        <input id="${prefix}app-id" type="text" inputmode="numeric" autocomplete="off" required>
      </div>
      <div class="row">
        <label for="${prefix}secret">AppSecret</label>
        <input id="${prefix}secret" type="password" autocomplete="new-password">
      </div>
      <p class="hint" id="${prefix}unconfigured" hidden>请填写 AppID 和 AppSecret 后保存连接。</p>
      <button type="submit" class="primary">保存并连接</button>
    </form>
    ${inSetup ? loading : linkSettingsHtml(loading)}
    <p id="${prefix}result" class="hint" role="status" hidden></p>`;

  const find = id => container.querySelector('#' + prefix + id);
  const view = {appId: find('app-id'), secret: find('secret'), interval: find('millis'), groups: find('groups'),
    welcome: find('welcome-enabled'), welcomeSave: find('welcome-save'), welcomeResult: find('welcome-result'),
    welcomeLoaded: false,
    claimStatus: find('claim-status'), claimResult: find('claim-result'),
    issued: find('issued'), claimCommand: find('claim-command'),
    claimedAdmins: find('claimed-admins'), issuedExpiry: null,
    unconfigured: find('unconfigured'), load: find('load'), result: find('result'), request: 0, saving: false};

  find('credentials').addEventListener('submit', async event => {
    if (view.saving) {
      event.preventDefault();
      return;
    }
    onConnectionChange?.(true);
    const appId = view.appId.value.trim();
    await save(view, event, 'credentials', {appId, appSecret: view.secret.value}, '凭据已保存', () => {
      view.secret.value = '';
      setSavedValue(view.appId, appId, true);
      if (!inSetup) {
        clearIssuedCode(view);
        return refreshLinks();
      }
    });
    await onConnectionChange?.(false);
  });

  if (inSetup) {
    refreshView(view);
    return;
  }
  card = view;
  find('interval').addEventListener('submit', event => {
    return save(view, event, 'interval', {millis: view.interval.valueAsNumber}, '发送间隔已保存', result => {
      setSavedValue(view.interval, String(result.intervalMillis), true);
    });
  });
  find('welcome').addEventListener('submit', event => {
    if (!view.welcomeLoaded) {
      event.preventDefault();
      return;
    }
    return save(view, event, 'welcome', {enabled: view.welcome.checked}, '进群欢迎语已保存', result => {
      setSavedWelcome(view.welcome, result.enabled, true);
    }, view.welcomeResult);
  });
  find('claim-generate').addEventListener('submit', event => {
    if (!view.saving) clearIssuedCode(view);
    return save(view, event, 'admin-claims/code', {}, '认领码已生成', result => {
      view.issuedExpiry = result.expiresAt;
      view.claimCommand.textContent = '认领 ' + result.code;
      view.issued.hidden = false;
      showClaimStatus(view, result.expiresAt);
    }, view.claimResult);
  });
  view.claimedAdmins.addEventListener('submit', event => {
    const member = event.target.dataset.memberNum;
    return save(view, event, 'admin-claims/' + encodeURIComponent(member) + '/revoke', {}, '已撤销超级管理员',
      undefined, view.claimResult);
  });
  const refreshButton = find('refresh');
  refreshButton.addEventListener('click', async () => {
    refreshButton.disabled = true;
    try {
      await refresh(true);
      if (card === view && store.tab === 'bot') await refreshLinks();
    } finally {
      refreshButton.disabled = false;
    }
  });
  view.groups.addEventListener('submit', event => {
    const form = event.target;
    const input = form.querySelector('input');
    return save(view, event, 'groups/' + encodeURIComponent(form.dataset.groupNumber) + '/name',
      {name: input.value}, '群名已保存', () => { input.defaultValue = input.value; });
  });
}

function linkSettingsHtml(loading) {
  return `
    <form class="fbox lc-sub" id="qqbot-interval">
      <div class="row">
        <label for="qqbot-millis">发送间隔（毫秒）</label>
        <input id="qqbot-millis" type="number" min="1000" max="60000" step="1" required>
        <span class="dim">默认每 2 秒一条，即每分钟 30 条</span>
      </div>
      <button type="submit" class="ghost sm">保存间隔</button>
    </form>
    <form class="lc-sub" id="qqbot-welcome">
      <label class="switch">
        <input id="qqbot-welcome-enabled" type="checkbox" role="switch" aria-describedby="qqbot-welcome-help" disabled>
        <span>机器人进群时发欢迎语</span>
      </label>
      <p class="hint" id="qqbot-welcome-help">关掉后，机器人进群时不会提醒群主打开主动发言，请自行告诉群主。</p>
      <button type="submit" class="ghost sm" id="qqbot-welcome-save" disabled>保存欢迎语设置</button>
      <p class="hint" id="qqbot-welcome-result" role="status" hidden></p>
    </form>
    <section class="lc-sub">
      <h3 class="lc-h">官方机器人超级管理员</h3>
      <form class="fbox" id="qqbot-claim-generate">
        <button type="submit" class="ghost sm">生成超管认领码</button>
      </form>
      <p class="hint" id="qqbot-claim-status" role="status"></p>
      <div class="issued" id="qqbot-issued" role="status" hidden>
        <b>请本人在官方群里 @机器人，发送下面这条命令。</b>
        <p><code id="qqbot-claim-command"></code></p>
        <p>认领码只显示这一次，请勿转发。</p>
      </div>
      <h4 class="lc-h">已认领的超级管理员</h4>
      <div id="qqbot-claimed-admins"></div>
      <p class="hint" id="qqbot-claim-result" role="status" hidden></p>
    </section>
    <section class="lc-sub">
      <h3 class="lc-h">已加入的群</h3>
      <p class="hint">把机器人拉进群（个人主体要求你是群主或管理员），请群主打开主动发言。然后就可以在推送页选择这个群。</p>
      <p class="hint">群名留空并保存，可恢复接口群名或默认名。</p>
      <button type="button" class="ghost sm" id="qqbot-refresh">刷新状态和群列表</button>
      ${loading}
      <div class="tblwrap" id="qqbot-groups"></div>
    </section>`;
}

export async function refresh(refreshGroups = false) {
  if (!card || store.tab !== 'bot') return;
  clearIssuedCode(card);
  showResult(card.claimResult, '');
  await refreshView(card, refreshGroups);
}

async function refreshView(view, refreshGroups = false) {
  const request = ++view.request;
  const welcome = view.welcome ? refreshWelcome(view, request) : undefined;
  const readState = async (query = '') => {
    const state = await api(BASE + 'state' + query, {cache: 'no-store'});
    if (!state.success) throw new Error(state.message || '无法读取机器人状态');
    return state;
  };
  try {
    if (refreshGroups) view.load.textContent = '正在刷新群状态…';
    let state = await readState(refreshGroups ? '?refresh=true' : '');
    while (refreshGroups && state.refreshing && request === view.request && store.tab === 'bot') {
      await new Promise(resolve => setTimeout(resolve, 500));
      if (request !== view.request || store.tab !== 'bot') break;
      state = await readState('?refresh=false');
    }
    if (request !== view.request) return;
    setSavedValue(view.appId, state.appId || '');
    view.secret.placeholder = state.secretMasked ? '已保存：' + state.secretMasked : '';
    view.unconfigured.hidden = state.configured;
    if (view.groups) {
      setSavedValue(view.interval, String(state.intervalMillis));
      const expiresAt = state.claimCodeActive ? state.claimCodeExpiresAt : null;
      if (expiresAt !== view.issuedExpiry) clearIssuedCode(view);
      showClaimStatus(view, expiresAt);
      renderClaimedAdmins(view.claimedAdmins, state.claimedAdmins || []);
      renderGroups(view.groups, state.groups || []);
    }
    view.load.textContent = '';
  } catch (error) {
    if (request === view.request) view.load.textContent = '加载失败：' + error.message;
  } finally {
    await welcome;
  }
}

async function refreshWelcome(view, request) {
  try {
    const result = await api(BASE + 'welcome', {cache: 'no-store'});
    if (request !== view.request) return;
    if (!result.success || typeof result.enabled !== 'boolean') throw new Error(result.message || '无法读取设置');
    setSavedWelcome(view.welcome, result.enabled);
    if (!view.welcomeLoaded) {
      showResult(view.welcomeResult, '');
      view.welcomeLoaded = true;
      view.welcome.disabled = false;
      view.welcomeSave.disabled = false;
    }
  } catch (error) {
    if (request === view.request) showResult(view.welcomeResult, '读取欢迎语设置失败：' + error.message, true);
  }
}

async function save(view, event, path, body, message, onSaved, resultBox = view.result) {
  event.preventDefault();
  if (view.saving) return;
  const button = event.submitter || event.target.querySelector('button[type="submit"]');
  const inputs = event.target.querySelectorAll('input');
  view.saving = true;
  button.disabled = true;
  inputs.forEach(input => { input.disabled = true; });
  showResult(resultBox, '正在保存…');
  try {
    const result = await api(BASE + path, {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)});
    if (!result.success) throw new Error(result.message || '保存失败');
    const followUp = onSaved?.(result);
    showResult(resultBox, result.warning || message, !!result.warning);
    await Promise.all([refreshView(view), followUp]);
  } catch (error) {
    showResult(resultBox, error.message, true);
  } finally {
    view.saving = false;
    button.disabled = false;
    inputs.forEach(input => { input.disabled = false; });
  }
}

function clearIssuedCode(view) {
  view.issued.hidden = true;
  view.claimCommand.textContent = '';
  view.issuedExpiry = null;
}

function showClaimStatus(view, expiresAt) {
  view.claimStatus.textContent = expiresAt
    ? '认领码有效，过期时间：' + displayTime(expiresAt)
    : '当前没有有效的认领码。';
}

function displayTime(value) {
  return new Date(value).toLocaleString('zh-CN', {hour12: false});
}

function renderClaimedAdmins(container, admins) {
  if (!admins.length) {
    container.innerHTML = '<p class="hint">还没有人认领超级管理员。</p>';
    return;
  }
  container.innerHTML = `<div role="list" aria-label="已认领的超级管理员">
    ${admins.map(admin => `<div class="todo" role="listitem">
      <div class="tt">
        <b>${esc(admin.nickname)}</b>
        <p>认领群：${esc(admin.groupName)}</p>
        <p>认领时间：<time datetime="${esc(admin.claimedAt)}">${esc(displayTime(admin.claimedAt))}</time></p>
      </div>
      <form data-member-num="${esc(admin.memberNum)}">
        <button type="submit" class="ghost sm danger">撤销</button>
      </form>
    </div>`).join('')}</div>`;
}

function showResult(element, message, notice = false) {
  element.hidden = !message;
  element.className = notice ? 'notice' : 'hint';
  element.textContent = message;
}

function setSavedValue(input, value, replace = false) {
  const draft = !replace && input.value !== input.defaultValue ? input.value : value;
  input.defaultValue = value;
  input.value = draft;
}

function setSavedWelcome(input, checked, replace = false) {
  const draft = !replace && input.checked !== input.defaultChecked ? input.checked : checked;
  input.defaultChecked = checked;
  input.checked = draft;
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
