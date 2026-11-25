import {ask} from './confirm.js';
import {api, esc, loadingHtml} from './core.js';
import {showLinkResult, trackLinkEdits} from './link-feedback.js';
import {refreshLinks} from './links.js';
import {store} from './store.js';

const BASE = '/qqbot/';
const groupEdits = new WeakMap();
let card;

export const alternateSetupLabel = 'NapCat（用 QQ 号登录）';

export function render(container, {onConnectionChange} = {}) {
  const inSetup = !!onConnectionChange;
  const prefix = inSetup ? 'setup-qqbot-' : 'qqbot-';
  const intro = inSetup
    ? '请先在 QQ 开放平台创建机器人，IP 白名单留空。个人主体的机器人只能加入你担任群主或管理员的群。'
    : '通过 QQ 开放平台连接机器人，向它加入的官方群发送消息。';
  const loading = `<p class="hint" id="${prefix}load" role="status">${loadingHtml('正在加载…', true)}</p>`;
  container.innerHTML = `
    <p class="hint lc-intro">${intro}</p>
    <section class="${inSetup ? '' : 'lc-sub'}">
      ${inSetup ? '' : '<h3 class="lc-h">凭据</h3><p class="hint">在 QQ 开放平台创建机器人后填写 AppID 和 AppSecret，平台后台的 IP 白名单留空。</p>'}
      <form id="${prefix}credentials">
        <div class="lc-fields">
          <div class="lc-field">
            <label for="${prefix}app-id">AppID</label>
            <input id="${prefix}app-id" type="text" inputmode="numeric" autocomplete="off" required>
          </div>
          <div class="lc-field">
            <label for="${prefix}secret">AppSecret</label>
            <input id="${prefix}secret" type="password" autocomplete="new-password">
          </div>
          <p class="hint" id="${prefix}unconfigured" hidden>请填写 AppID 和 AppSecret 后保存连接。</p>
        </div>
        <div class="lc-actions">
          <button type="submit" class="primary">保存并连接</button>
          <span class="lc-unsaved" id="${prefix}credentials-unsaved" hidden>未保存</span>
          <span class="lc-result" id="${prefix}result" role="status" hidden></span>
        </div>
      </form>
      ${inSetup ? loading : ''}
    </section>
    ${inSetup ? '' : linkSettingsHtml(loading)}`;

  const find = id => container.querySelector('#' + prefix + id);
  const view = {appId: find('app-id'), secret: find('secret'), interval: find('millis'), groups: find('groups'),
    welcome: find('welcome-enabled'), welcomeResult: find('welcome-result'), welcomeRevision: 0,
    claimStatus: find('claim-status'), claimResult: find('claim-result'),
    issued: find('issued'), claimCommand: find('claim-command'),
    claimedAdmins: find('claimed-admins'), issuedExpiry: null,
    unconfigured: find('unconfigured'), load: find('load'), result: find('result'), request: 0, saving: false};
  view.updateCredentials = trackLinkEdits([view.appId, view.secret], find('credentials-unsaved'));

  find('credentials').addEventListener('submit', async event => {
    event.preventDefault();
    if (view.saving) {
      showLinkResult(view.result, '请等当前操作完成后重试。', 'warn');
      return;
    }
    onConnectionChange?.(true);
    try {
      const appId = view.appId.value.trim();
      const result = await save(view, event, 'credentials', {appId, appSecret: view.secret.value}, view.result);
      if (!result) return;
      view.secret.value = '';
      setSavedValue(view.appId, appId, true);
      view.updateCredentials();
      if (!inSetup) clearIssuedCode(view);
      await Promise.all([refreshView(view), inSetup ? undefined : refreshLinks(true)]);
    } finally {
      await onConnectionChange?.(false);
    }
  });

  if (inSetup) {
    refreshView(view);
    return;
  }

  card = view;
  const intervalResult = find('interval-result');
  view.updateInterval = trackLinkEdits([view.interval], find('interval-unsaved'));
  find('interval').addEventListener('submit', async event => {
    const result = await save(view, event, 'interval', {millis: view.interval.valueAsNumber}, intervalResult);
    if (!result) return;
    setSavedValue(view.interval, String(result.intervalMillis), true);
    view.updateInterval();
    await refreshView(view);
  });
  view.welcome.addEventListener('change', () => saveWelcome(view));
  find('claim-generate').addEventListener('submit', async event => {
    if (!view.saving) clearIssuedCode(view);
    const result = await save(view, event, 'admin-claims/code', {}, view.claimResult, '认领码已生成');
    if (!result) return;
    view.issuedExpiry = result.expiresAt;
    view.claimCommand.textContent = '认领 ' + result.code;
    view.issued.hidden = false;
    showClaimStatus(view, result.expiresAt);
  });
  view.claimedAdmins.addEventListener('submit', async event => {
    event.preventDefault();
    if (view.saving) return;
    const form = event.target;
    if (!await ask({title: '撤销超级管理员？', body: '撤销后，该成员将失去在官方群中使用超级管理员命令的权限。'})) return;
    const resultBox = form.querySelector('.lc-result');
    const result = await save(view, event, 'admin-claims/' + encodeURIComponent(form.dataset.memberNum) + '/revoke',
      {}, resultBox, '已撤销超级管理员', () => {
        form.closest('[role="listitem"]')?.remove();
        if (!view.claimedAdmins.querySelector('[role="listitem"]')) renderClaimedAdmins(view.claimedAdmins, []);
      });
    if (result) submitButton(event).hidden = true;
  });
  const refreshButton = find('refresh');
  const refreshResult = find('refresh-result');
  refreshButton.addEventListener('click', async () => {
    refreshButton.disabled = true;
    showLinkResult(refreshResult, '正在刷新…');
    try {
      const error = await refresh(true);
      showLinkResult(refreshResult, error || '已刷新', error ? 'err' : 'ok');
      if (card === view && store.tab === 'bot') await refreshLinks();
    } finally {
      refreshButton.disabled = false;
    }
  });
  view.groups.addEventListener('submit', async event => {
    const form = event.target;
    const input = form.querySelector('input');
    const remark = input.value;
    const result = await save(view, event, 'groups/' + encodeURIComponent(form.dataset.groupNumber) + '/remark',
      {remark}, form.querySelector('.lc-result'), '备注已保存');
    if (!result) return;
    input.defaultValue = remark;
    groupEdits.get(form)?.();
    await refreshView(view);
  });
}

function linkSettingsHtml(loading) {
  return `
    <section class="lc-sub">
      <h3 class="lc-h">已加入的群</h3>
      <p class="hint">把机器人拉进群（个人主体要求你是群主或管理员），请群主打开主动发言。然后就可以在推送页选择这个群。</p>
      <p class="hint">群名以 QQ 接口取到的为准，备注只在控制台里显示，方便认出是哪个群。</p>
      <div class="lc-actions">
        <button type="button" class="ghost" id="qqbot-refresh">刷新状态和群列表</button>
        <span class="lc-result" id="qqbot-refresh-result" role="status" hidden></span>
      </div>
      ${loading}
      <div class="tblwrap lc-table" id="qqbot-groups" role="region" aria-label="QQ群列表" tabindex="0"></div>
    </section>
    <section class="lc-sub">
      <h3 class="lc-h">超级管理员</h3>
      <form class="lc-actions" id="qqbot-claim-generate">
        <button type="submit" class="primary">生成超管认领码</button>
        <span class="lc-result" id="qqbot-claim-result" role="status" hidden></span>
      </form>
      <p class="hint" id="qqbot-claim-status" role="status"></p>
      <div class="issued" id="qqbot-issued" hidden>
        <b>请本人在官方群里 @机器人，发送下面这条命令。</b>
        <p><code id="qqbot-claim-command"></code></p>
        <p>认领码只显示这一次，请勿转发。</p>
      </div>
      <div id="qqbot-claimed-admins"></div>
    </section>
    <section class="lc-sub">
      <h3 class="lc-h">其他设置</h3>
      <div class="lc-actions">
        <label class="switch">
          <input id="qqbot-welcome-enabled" type="checkbox" role="switch" aria-describedby="qqbot-welcome-help" disabled>
          <span>机器人进群时发欢迎语</span>
        </label>
        <span class="lc-result" id="qqbot-welcome-result" role="status" hidden></span>
      </div>
      <p class="hint" id="qqbot-welcome-help">关掉后，机器人进群时不会提醒群主打开主动发言，请自行告诉群主。</p>
      <form id="qqbot-interval">
        <div class="lc-fields">
          <div class="lc-field">
            <label for="qqbot-millis">发送间隔（毫秒）</label>
            <input id="qqbot-millis" type="number" min="1000" max="60000" step="1" required>
            <p class="hint">默认每 2 秒一条，即每分钟 30 条</p>
          </div>
        </div>
        <div class="lc-actions">
          <button type="submit" class="primary">保存</button>
          <span class="lc-result" id="qqbot-interval-result" role="status" hidden></span>
          <span class="lc-unsaved" id="qqbot-interval-unsaved" hidden>未保存</span>
        </div>
      </form>
    </section>`;
}

export async function refresh(refreshGroups = false) {
  if (!card || store.tab !== 'bot') return;
  clearIssuedCode(card);
  return refreshView(card, refreshGroups);
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
    view.updateCredentials();
    view.secret.placeholder = state.secretMasked ? '已保存：' + state.secretMasked : '';
    view.unconfigured.hidden = state.configured;
    if (view.groups) {
      setSavedValue(view.interval, String(state.intervalMillis));
      view.updateInterval();
      const expiresAt = state.claimCodeActive ? state.claimCodeExpiresAt : null;
      if (expiresAt !== view.issuedExpiry) clearIssuedCode(view);
      showClaimStatus(view, expiresAt);
      renderClaimedAdmins(view.claimedAdmins, state.claimedAdmins || []);
      renderGroups(view.groups, state.groups || []);
    }
    view.load.textContent = '';
  } catch (error) {
    const message = '加载失败：' + error.message;
    if (request === view.request) view.load.textContent = refreshGroups ? '' : message;
    return message;
  } finally {
    await welcome;
  }
}

async function refreshWelcome(view, request) {
  const revision = view.welcomeRevision;
  const current = () => request === view.request && revision === view.welcomeRevision && !view.welcomeSaving;
  try {
    const result = await api(BASE + 'welcome', {cache: 'no-store'});
    if (!current()) return;
    if (!result.success || typeof result.enabled !== 'boolean') throw new Error(result.message || '无法读取设置');
    view.welcome.checked = view.welcome.defaultChecked = result.enabled;
    if (view.welcome.disabled) {
      showLinkResult(view.welcomeResult);
      view.welcome.disabled = false;
    }
  } catch (error) {
    if (current()) showLinkResult(view.welcomeResult, '读取欢迎语设置失败：' + error.message, 'err');
  }
}

async function saveWelcome(view) {
  const toggle = view.welcome;
  if (toggle.disabled) return;
  view.welcomeRevision++;
  view.welcomeSaving = true;
  toggle.disabled = true;
  showLinkResult(view.welcomeResult, '正在保存…');
  try {
    const result = await post('welcome', {enabled: toggle.checked});
    toggle.checked = toggle.defaultChecked = result.enabled;
    showLinkResult(view.welcomeResult, '已保存', 'ok');
  } catch (error) {
    toggle.checked = toggle.defaultChecked;
    showLinkResult(view.welcomeResult, error.message, 'err');
  } finally {
    view.welcomeSaving = false;
    toggle.disabled = false;
  }
}

function submitButton(event) {
  return event.submitter || event.target.querySelector('button[type="submit"]');
}

async function post(path, body) {
  const result = await api(BASE + path, {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)});
  if (!result.success) throw new Error(result.message || '保存失败');
  return result;
}

async function save(view, event, path, body, resultBox, message = '已保存', onClear) {
  event.preventDefault();
  if (view.saving) {
    showLinkResult(resultBox, '请等当前操作完成后重试。', 'warn');
    return;
  }
  const button = submitButton(event);
  const inputs = event.target.querySelectorAll('input');
  view.saving = true;
  button.disabled = true;
  inputs.forEach(input => { input.disabled = true; });
  showLinkResult(resultBox, '正在保存…');
  try {
    const result = await post(path, body);
    showLinkResult(resultBox, result.warning ? message + ' · ' + result.warning : message,
      result.warning ? 'warn' : 'ok', '', onClear);
    return result;
  } catch (error) {
    showLinkResult(resultBox, error.message, 'err');
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
  const previous = formsByKey(container, 'member-num', 'memberNum');
  container.innerHTML = `<div role="list" aria-label="已认领的超级管理员">
    ${admins.map(admin => `<div class="todo" role="listitem">
      <div class="tt">
        <b>${esc(admin.nickname)}</b>
        <p>认领群：${esc(admin.groupName)}</p>
        <p>认领时间：<time datetime="${esc(admin.claimedAt)}">${esc(displayTime(admin.claimedAt))}</time></p>
      </div>
      <form class="lc-actions" data-member-num="${esc(admin.memberNum)}">
        <button type="submit" class="danger sm">撤销</button>
        <span class="lc-result" role="status" hidden></span>
      </form>
    </div>`).join('')}</div>`;
  for (const form of container.querySelectorAll('[data-member-num]')) {
    const old = previous.get(form.dataset.memberNum);
    if (old) form.replaceWith(old);
  }
}

function formsByKey(container, attribute, key) {
  return new Map(Array.from(container.querySelectorAll('[data-' + attribute + ']'),
    form => [form.dataset[key], form]));
}

function setSavedValue(input, value, replace = false) {
  const draft = !replace && input.value !== input.defaultValue ? input.value : value;
  input.defaultValue = value;
  input.value = draft;
}

function renderGroups(container, groups) {
  const previous = formsByKey(container, 'group-number', 'groupNumber');
  if (!groups.length) {
    container.innerHTML = '<p class="hint">机器人尚未加入群。</p>';
    return;
  }
  container.innerHTML = `<table aria-label="已加入的群">
    <thead><tr><th>群名</th><th>备注</th><th>人数</th><th>机器人是管理员</th>
      <th>主动发言</th><th>最近失败原因</th></tr></thead>
    <tbody>${groups.map(groupRow).join('')}</tbody></table>`;
  for (const form of container.querySelectorAll('[data-group-number]')) {
    const input = form.querySelector('input');
    const old = previous.get(form.dataset.groupNumber);
    if (old) {
      setSavedValue(old.querySelector('input'), input.defaultValue);
      form.replaceWith(old);
      groupEdits.get(old)();
    } else {
      groupEdits.set(form, trackLinkEdits([input], form.querySelector('.lc-unsaved')));
    }
  }
}

function groupRow(group) {
  const admin = group.role == null ? '不知道' : group.role === 'MEMBER' ? '否' : '是';
  const proactive = group.proactive === 'ENABLED' ? '开' : group.proactive === 'DISABLED' ? '未开' : '不知道';
  const help = group.proactive === 'DISABLED'
    ? '<div class="hint">手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开『机器人主动在群聊内发言』</div>'
    : '';
  return `<tr${group.removed ? ' class="dim"' : ''}>
    <td>${esc(group.name)}${group.removed ? '<div>已移出</div>' : ''}</td>
    <td><form class="lc-remark" data-group-number="${esc(group.num)}">
      <div class="lc-field"><input type="text" aria-label="备注" value="${esc(group.remark ?? '')}"></div>
      <div class="lc-actions"><button type="submit" class="ghost sm">保存备注</button>
        <span class="lc-unsaved" hidden>未保存</span><span class="lc-result" role="status" hidden></span></div>
    </form></td>
    <td>${esc(group.memberCount ?? '—')}</td><td>${admin}</td>
    <td>${proactive}${help}</td><td>${esc(group.lastFailure || '—')}</td>
  </tr>`;
}
