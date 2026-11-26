/**
 * 访问令牌页：给自己的账号签发访问令牌，查看与吊销
 *
 * 管理员与成员都进得来。签发只给自己；成员只看得到自己的，管理员看得到全部账号的。
 *
 * 🔴 令牌明文只在这一页的内存里：不写浏览器存储、不进地址栏、不打日志。
 *    离开本页就从 DOM 里抹掉（{@link clearIssuedApiToken}），界面上「离开本页后无法再次查看」那句话靠它兑现。
 */

import {ask} from './confirm.js';
import {$, api, CONSOLE, esc, PREFIX} from './core.js';
import {showLinkResult} from './link-feedback.js';
import {bindPasswordReveal} from './password-reveal.js';
import {DEFAULT_VALIDITY, expiryText, fmtDate, fmtTime, issueBody, lanStatus, localStatus, ownerText, remoteRoutes, revocable,
  scopeSelectable, scopeText, sortTokens, stateText, unavailableText, VALIDITY} from './api-tokens-model.js';

/** 最近一次取到的清单回包 */
let latest = null;

/** 正在吊销的编号，防连点 */
const revoking = new Set();

/**
 * 进入本页时调用：重取清单、重建签发表单，并抹掉上一次留在屏幕上的明文
 */
export async function loadApiTokens() {
  clearIssuedApiToken();
  const status = $('#at-load');
  if (status) showLinkResult(status);
  try {
    const data = await api('/api-tokens');
    if (!data.success) throw new Error(data.message || '读取失败');
    latest = data;
    renderForm(data);
    renderList(data);
    if (data.admin) await loadRemote();
    else hideRemote();
  } catch (e) {
    if (status) showLinkResult(status, '访问令牌读取失败：' + e.message, 'err');
  }
}

/** 正在保存的接口组，防连点 */
const toggling = new Set();

/** 成员那里整块藏起来，里面画过的东西一并清掉 */
function hideRemote() {
  const box = $('#at-remote');
  if (!box) return;
  box.hidden = true;
  $('#at-remote-groups').innerHTML = '';
  $('#at-remote-routes').innerHTML = '';
}

async function loadRemote() {
  const box = $('#at-remote');
  if (!box) return;
  box.hidden = false;
  try {
    const data = await api('/api-tokens/remote-access');
    if (!data.success) throw new Error(data.message || '读取失败');
    renderRemote(data);
  } catch (e) {
    showLinkResult($('#at-remote-msg'), '「从别的机器访问」读取失败：' + e.message, 'err');
  }
}

function renderRemote(state) {
  const groups = state.groups || [];
  const ignored = state.ignored || 0;
  const rows = groups.map(group => '<div class="at-group" data-segment="' + esc(group.segment) + '">'
      + '<div class="at-group-text"><b>' + esc(group.name) + '</b> <code>' + esc(group.segment) + '</code> '
      + (group.open ? '<span class="nv-pill ok">已对任何地址开放</span>' : '<span class="nv-pill">只对名单里的地址开放</span>')
      + (group.description ? '<br><span class="hint">' + esc(group.description) + '</span>' : '')
      + '</div><label class="switch"><input type="checkbox"' + (group.open ? ' checked' : '')
      + (toggling.has(group.segment) ? ' disabled' : '') + '><span>允许从任何地址访问（仍须访问令牌）</span></label></div>')
    .join('');
  $('#at-remote-groups').innerHTML = '<p class="hint">默认只有「允许访问的来源 IP」里的地址（默认只有本机）调得了接口。'
    + '内网别的机器、经反向代理进来的公网访问要调哪一组，就在这里打开哪一组：开放只免去来源名单这一道，'
    + '令牌、范围与无效令牌限速照旧。至少打开一组时，自查接口 <code>/me</code> 也随之对任何地址开放。</p>'
    + (groups.length ? '<div class="at-groups">' + rows + '</div>'
      : '<p class="hint">目前没有插件登记接口组，这里没有可打开的。</p>')
    + (ignored ? '<p class="hint at-stale">清单里另有 ' + ignored + ' 条没登记或写法不合规的组名，不起作用；'
      + '下次在这里打开或关上任何一组时会自动清掉。</p>' : '');

  $('#at-remote-groups').querySelectorAll('[data-segment]').forEach(row => {
    const group = groups.find(g => g.segment === row.dataset.segment);
    const input = row.querySelector('input');
    input.addEventListener('change', () => toggleGroup(state, group, input));
  });
  renderRoutes(state);
}

function renderRoutes(state) {
  const routes = remoteRoutes(state, PREFIX, location);
  const lan = lanStatus(routes.lan);
  const local = localStatus(routes.local);
  const sample = CONSOLE + '/manual/appendix-c-security-and-resources.html';
  const code = text => '<code>' + esc(text) + '</code>';
  $('#at-remote-routes').innerHTML = '<h4 class="at-route-h">该连哪个地址</h4><dl class="at-routes">'
    + '<dt>同一台机器上</dt><dd>' + code(routes.local.url)
    + (local.level ? '<p class="at-lan ' + local.level + '">' + esc(local.local) + '</p>'
      : '<br><span class="hint">' + esc(local.local) + '</span>') + '</dd>'
    + '<dt>内网别的机器</dt><dd>' + routes.lan.urls.map(code).join('<br>')
    + (routes.lan.known ? '' : '<br><span class="hint">没认出这台机器的内网地址，请换成它在内网里的 IP。</span>')
    + '<p class="at-lan ' + lan.level + '">' + esc(lan.text) + '</p>'
    + (lan.steps.length ? '<ol class="at-steps">' + lan.steps.map(step => '<li>' + esc(step) + '</li>').join('') + '</ol>' : '')
    + (routes.lan.restartPending ? '<p class="hint">监听地址已在设置页改过，重启 NovaBot 之后才按新的监听。</p>' : '')
    + '</dd>'
    + '<dt>公网</dt><dd>经反向代理的加密地址，例如 ' + code(routes.internet.url)
    + '<br><span class="hint">反向代理要按<a href="' + esc(sample) + '" target="_blank" rel="noopener noreferrer">使用说明附录 C 的样板</a>'
    + '转发 <code>/nova/api/v1/</code> 并带上转发头。转发了也不等于放通：名单以外的来访者调不调得了，看上面的开关。'
    + '<b>不要没有反向代理就把端口直接放到公网</b>：那样令牌会明文经过网络。</span>'
    + (routes.internet.current ? '<br><span class="hint">你现在打开控制台用的是 ' + code(location.origin)
      + '。只有它前面的反向代理也转发了 <code>/nova/api/v1/</code>，' + code(routes.internet.current)
      + ' 才调得通；只转发了 <code>/config</code> 的，这个地址调不了接口。</span>' : '')
    + '</dd>'
    + '<dt>SSH 隧道</dt><dd>' + (local.level ? '' : '不改任何设置的办法：') + '在来访的机器上运行 '
    + code(routes.tunnel.command) + '，再连 ' + code(routes.tunnel.url) + '。'
    + (local.level ? '<p class="at-lan ' + local.level + '">' + esc(local.tunnel) + '</p>' : esc(local.tunnel)) + '</dd></dl>';
}

/**
 * 打开或关上一组。打开要先问一句；关上不问：收紧不会让谁多拿到什么
 */
async function toggleGroup(state, group, input) {
  const open = input.checked;
  const result = $('#at-remote-msg');
  if (open && !await ask({title: '让「' + group.name + '」接受任何地址的访问？', body: state.consequence,
    danger: true, confirmText: '打开'})) {
    input.checked = false;
    return;
  }
  if (toggling.has(group.segment)) return;
  toggling.add(group.segment);
  input.disabled = true;
  try {
    const data = await api('/api-tokens/remote-access', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({segment: group.segment, open}),
    });
    toggling.delete(group.segment);
    if (!data.success) {
      input.checked = !open;
      showLinkResult(result, data.message || '保存失败', 'err');
      return;
    }
    renderRemote(data);
    showLinkResult(result, data.message || '已保存', 'ok');
  } catch (e) {
    input.checked = !open;
    showLinkResult(result, '保存失败：' + e.message, 'err');
  } finally {
    toggling.delete(group.segment);
    input.disabled = false;
  }
}

/** 离开本页时抹掉屏幕上的令牌明文 */
export function clearIssuedApiToken() {
  const box = $('#at-issued');
  if (box) box.innerHTML = '';
}

function renderForm(data) {
  const box = $('#at-issue');
  const scopes = data.scopes || [];
  // 仅管理员的范围对成员照样列出来，只是勾不了，并说清为什么：免得成员以为插件没装好
  const scopeHtml = scopes.length
    ? '<fieldset class="at-scopes"><legend>范围</legend>' + scopes.map(scope => {
        const selectable = scopeSelectable(scope, data.admin);
        return '<label class="at-scope"><input type="checkbox" name="at-scope" value="' + esc(scope.id) + '"'
          + (selectable ? '' : ' disabled') + '>'
          + '<span><b>' + esc(scope.name) + '</b> <code>' + esc(scope.id) + '</code>'
          + (scope.adminOnly ? ' <span class="nv-pill">仅管理员</span>' : '')
          + (scope.description ? '<br><span class="hint">' + esc(scope.description) + '</span>' : '')
          + (selectable ? '' : '<br><span class="hint">这一项管的是整个实例，只有管理员能勾选。</span>')
          + '</span></label>';
      }).join('') + '</fieldset>'
    : '<p class="hint">目前没有插件登记可勾选的范围。不勾范围的令牌只能调用自查接口 '
      + '<code>/nova/api/v1/me</code>。</p>';

  box.innerHTML = '<div class="lc-fields">'
    + '<div class="lc-field"><label for="at-name">令牌名称</label>'
    + '<input id="at-name" maxlength="40" placeholder="例如：备份脚本" autocomplete="off"></div>'
    + '<div class="lc-field"><label for="at-validity">有效期</label><select id="at-validity">'
    + VALIDITY.map(option => '<option value="' + (option.days === null ? '' : option.days) + '"'
        + (option.days === DEFAULT_VALIDITY ? ' selected' : '') + '>' + option.label + '</option>').join('')
    + '</select></div></div>'
    + scopeHtml
    + '<div class="lc-actions"><button type="button" class="primary" id="at-issue-btn">签发令牌</button>'
    + '<span class="hint" id="at-quota">' + quotaText(data) + '</span>'
    + '<span class="lc-result" id="at-msg" role="status" hidden></span></div>';

  $('#at-issue-btn').addEventListener('click', issue);
  $('#at-name').addEventListener('keydown', e => { if (e.key === 'Enter') issue(); });
}

function quotaText(data) {
  return '你的账号已有 ' + (data.active || 0) + ' / ' + (data.limit || 20) + ' 把有效令牌';
}

async function issue() {
  const name = $('#at-name').value.trim();
  const scopes = Array.from(document.querySelectorAll('input[name="at-scope"]:checked:not(:disabled)')).map(box => box.value);
  const days = $('#at-validity').value;
  const result = $('#at-msg');
  if (!name) return showLinkResult(result, '请给令牌起个名字，日后好认出是哪一把', 'err');

  const button = $('#at-issue-btn');
  button.disabled = true;
  showLinkResult(result, '正在签发…');
  try {
    const data = await api('/api-tokens', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(issueBody(name, scopes, days)),
    });
    if (!data.success || !data.token) {
      showLinkResult(result, data.message || '签发失败', 'err');
      return;
    }
    // 先重画表单与清单，再把明文放上去：重画会把签发区清空
    await loadApiTokens();
    showIssued($('#at-issued'), data.token, name);
  } catch (e) {
    showLinkResult(result, '签发失败：' + e.message, 'err');
  } finally {
    const current = $('#at-issue-btn');
    if (current) current.disabled = false;
  }
}

function showIssued(box, token, name) {
  const base = location.origin + PREFIX + '/nova/api/v1';
  box.innerHTML = '<div class="issued">'
    + '<p>「' + esc(name) + '」已签发。令牌明文<b>只显示这一次</b>，离开本页后无法再次查看：'
    + '服务器只保存它的摘要，取不回明文。遗失后只能吊销再重新签发。</p>'
    + '<div class="lc-field"><input id="at-value" type="password" aria-label="刚签发的访问令牌" readonly></div>'
    + '<div class="lc-actions"><button type="button" class="ghost" id="at-reveal">显示</button>'
    + '<button type="button" class="primary" id="at-copy">复制</button>'
    + '<span class="lc-result" id="at-copy-msg" role="status" hidden></span></div>'
    + '<p class="hint">程序在请求头里带上它即可，例如：<br><code>curl -H "Authorization: Bearer &lt;令牌&gt;" '
    + esc(base) + '/me</code></p></div>';

  // 明文直接赋给 value，不拼进 innerHTML：复制出去的必须与签发的那一把逐字符相同
  const field = $('#at-value');
  field.value = token;
  const reveal = bindPasswordReveal(field, $('#at-reveal'), '令牌');
  const copyResult = $('#at-copy-msg');
  $('#at-copy').addEventListener('click', async () => {
    showLinkResult(copyResult);
    try {
      await navigator.clipboard.writeText(field.value);
      showLinkResult(copyResult, '已复制到剪贴板', 'ok');
    } catch (e) {
      // 剪贴板用不了时把明文交到人手里：这把令牌没有第二次机会
      reveal.setRevealed(true);
      field.select();
      showLinkResult(copyResult, '当前浏览器不允许写入剪贴板，令牌已选中，请按 Ctrl / ⌘ + C 手动复制', 'err');
    }
  });
}

function renderList(data) {
  const box = $('#at-list');
  const tokens = sortTokens(data.tokens);
  const admin = !!data.admin;
  $('#at-list-title').textContent = admin ? '全部账号的令牌' : '我的令牌';
  if (!tokens.length) {
    box.innerHTML = '<p class="hint" style="margin:0">还没有签发过访问令牌。</p>';
    return;
  }
  box.innerHTML = '<div class="tblwrap lc-table" role="region" aria-label="访问令牌列表" tabindex="0">'
    + '<table aria-label="访问令牌"><thead><tr><th>名称与范围</th>' + (admin ? '<th>所属账号</th>' : '')
    + '<th>签发</th><th>到期</th><th>最后使用</th><th>状态</th><th></th></tr></thead><tbody>'
    + tokens.map(token => '<tr' + (token.state === 'active' ? '' : ' class="revoked"') + '>'
        + '<td>' + esc(token.name) + (admin && token.mine ? ' <span class="nv-pill">我的</span>' : '')
        + '<br><span class="at-scope-line">' + esc(scopeText(token.scopes, data.scopes)) + '</span></td>'
        + (admin ? '<td>' + esc(ownerText(token)) + '</td>' : '')
        + '<td>' + fmtDate(token.issuedAt) + '</td>'
        + '<td>' + esc(expiryText(token)) + '</td>'
        + '<td>' + fmtTime(token.lastUsedAt) + '</td>'
        + '<td>' + stateText(token) + (token.state === 'revoked' ? ' · ' + fmtDate(token.revokedAt) : '')
        + (unavailableText(token) ? '<span class="hint at-reason">' + esc(unavailableText(token)) + '</span>' : '') + '</td>'
        + '<td class="act"><div class="lc-actions" data-id="' + esc(token.id) + '">'
        + (!revocable(token) ? ''
          : '<button type="button" class="danger sm"' + (revoking.has(token.id) ? ' disabled' : '') + '>吊销</button>')
        + '</div></td></tr>').join('')
    + '</tbody></table></div>';

  box.querySelectorAll('[data-id]').forEach(actions => {
    const token = tokens.find(t => t.id === actions.dataset.id);
    const button = actions.querySelector('button');
    button?.addEventListener('click', () => revoke(token, button));
  });
}

async function revoke(token, button) {
  const whose = latest && latest.admin && !token.mine ? '（' + ownerText(token) + '）' : '';
  if (!await ask({title: '吊销这把令牌？',
    body: '吊销「' + token.name + '」' + whose + '后，用它的程序从下一个请求起就会被拒绝。这一步不能撤回。'})) return;
  if (revoking.has(token.id)) return;
  revoking.add(token.id);
  button.disabled = true;
  const status = $('#at-load');
  try {
    const response = await api('/api-tokens/' + encodeURIComponent(token.id) + '/revoke', {method: 'POST'});
    revoking.delete(token.id);
    await loadApiTokens();
    showLinkResult(status, response.message || (response.success ? '已吊销' : '吊销失败'), response.success ? 'ok' : 'err');
  } catch (e) {
    showLinkResult(status, '吊销失败：' + e.message, 'err');
  } finally {
    revoking.delete(token.id);
    button.disabled = false;
  }
}
