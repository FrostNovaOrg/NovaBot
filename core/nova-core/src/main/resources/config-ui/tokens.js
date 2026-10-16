/**
 * 只读口令页：给外部面板签发事件流口令，以及吊销
 *
 * 在此之前这个功能只有机器接口，没有人用的入口——签一把口令的实际操作是拿脚本在终端里
 * 输密码和验证码。那样会把签发权收窄到会写脚本的人手上，主播只能让别人代签再发给他，
 * <b>而那条转交路径上的口令是明文的</b>：端点守得再严，保护也做在了错的那一段。
 *
 * 🔴 <b>口令明文只在这一页的内存里存在</b>：不写 localStorage / sessionStorage、不进地址栏、
 *    不打日志。「离开本页后无法再次查看」是界面上的承诺，{@link clearIssuedToken} 是它的实现——
 *    没有那一句，这行文案就只是句话。ConfigUiFrontendTest 会在每次构建时检查前三条。
 */

import {ask} from './confirm.js';
import {$, api, esc, PREFIX, say} from './core.js';
import {refreshLinks} from './links.js';
import {bindPasswordReveal} from './password-reveal.js';
import {store} from './store.js';
import {explain, revokeOutcome} from './tokens-model.js';

/**
 * 进入本页时调用，重建签发表单
 * <p>
 * 每次进入都重建：顺带把上一次留在屏幕上的口令抹掉，
 * 这正是「离开本页后无法再次查看」要的效果。
 * <p>
 * 清单不在这里取——本页那张外部面板卡的状态（有几把有效、有几把撤了）与清单读的是同一份数据，
 * 各取各的话，卡上写着「1 把有效」而清单里一条都没有，两块都出自这一页。
 */
export function loadTokens() {
  $('#token-issue').innerHTML = issueFormHtml();
  $('#tk-issue').addEventListener('click', issue);
  $('#tk-label').addEventListener('keydown', e => { if (e.key === 'Enter') $('#tk-pass').focus(); });
}

/**
 * 把口令清单画出来
 * @param tokens 口令清单，来自 /api/event-tokens
 */
export function renderTokens(tokens) {
  renderTokenList(tokens || []);
}

/**
 * 离开本页签时抹掉屏幕上的口令明文
 *
 * 不只是好看：这一页可能开在正在直播的机器上，切走之后那串东西不该还留在 DOM 里等着被切回来。
 */
export function clearIssuedToken() {
  const box = $('#token-issued');
  if (box) box.innerHTML = '';
}

function issueFormHtml() {
  return '<p class="hint">签发只读访问令牌需<b>再次输入</b>控制台密码'
    + (store.totpRequired ? '与动态验证码' : '')
    + '。若仅凭当前已登录的会话即可签发，借用一台未锁屏的电脑就能签出令牌，'
    + '而签出的令牌长期有效。签发属于低频操作，再次验证可杜绝这种情况。</p>'

    + '<div class="row"><label for="tk-label">令牌名称</label>'
    + '<input id="tk-label" name="label" maxlength="40" placeholder="例如：客厅的电脑" autocomplete="off"></div>'
    + '<div class="row"><label></label><span class="hint" style="margin:0;flex:1;min-width:220px">'
    + '名称将原样显示在下方列表中，日后单独吊销该令牌时以此识别。'
    + '<b>请勿填写含真实姓名的设备名</b>，例如「某某的 MacBook」会将他人姓名保存在本服务器上。</span></div>'

    // 浏览器把紧挨着密码框的文本框当成登录名。「签给谁」里的名字会原样留在清单里，
    // 而这一页正叮嘱别填带真名的机器名。密码框前面放一个隐藏的用户名框，登录名只进这一框。
    // 这一页的样式表没有登录页那条 .hidden，所以再收成 1 像素：display:none 时有的浏览器直接跳过这一框，
    // 登录名仍会填进上面那个看得见的格子。
    + '<input type="text" name="username" value="novabot" autocomplete="username" class="hidden" readonly'
    + ' tabindex="-1" aria-hidden="true"'
    + ' style="position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);border:0">'

    + '<div class="row"><label for="tk-pass">密码</label>'
    + '<input type="password" id="tk-pass" name="password" autocomplete="current-password"></div>'

    // 验证器没绑就不显示这一格。上一次事故正出在这里：说明文字写的是「没开两步验证就直接回车」，
    // 而那台机器的二次验证是开着的，照做必然失败，且使用者没有任何办法自己发现说明写错了
    + (store.totpRequired
        ? '<div class="row"><label for="tk-code">动态验证码</label>'
          + '<input id="tk-code" inputmode="numeric" pattern="[0-9]*" maxlength="6"'
          + ' placeholder="验证器应用中的 6 位数字" autocomplete="one-time-code"></div>'
        : '')

    + '<div class="row"><label></label><button type="button" id="tk-issue">签发令牌</button></div>'
    + '<div class="out" id="tk-msg"></div>'
    + '<div id="token-issued"></div>';
}

async function issue() {
  const label = $('#tk-label').value.trim();
  const password = $('#tk-pass').value;
  const codeInput = $('#tk-code');
  const code = codeInput ? codeInput.value.trim() : '';

  if (!label) return fail('请填写令牌名称。没有名称就无法单独吊销，只能全部吊销，其他外部面板也将无法再连接');
  if (!password) return fail('请输入控制台密码');
  if (store.totpRequired && !/^\d{6}$/.test(code)) return fail('动态验证码为 6 位数字，请从验证器应用中读取');

  const body = {label, password};
  // 契约定的是「不传」而非空串——服务端对这两种写法走的是两条路径
  if (code) body.code = code;

  const button = $('#tk-issue');
  button.disabled = true;
  fail('');
  try {
    const response = await fetch(PREFIX + '/nova/readonly-token', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
    });
    const data = await response.json().catch(() => ({}));

    if (response.ok && data.token) {
      showIssued(data.token, label);
      $('#tk-pass').value = '';
      if (codeInput) codeInput.value = '';
      refreshTokens();
    } else {
      // 失败时也把口令框清空：留着上一次输错的内容，再点一次还是同样的错
      $('#tk-pass').value = '';
      fail(explain(response.status, data, store.vocab));
    }
  } catch (e) {
    fail('无法连接到服务器');
  } finally {
    button.disabled = false;
  }
}

function fail(text) {
  const box = $('#tk-msg');
  box.textContent = text || '';
  box.className = 'out' + (text ? ' err' : '');
}

function showIssued(token, label) {
  const box = $('#token-issued');
  box.innerHTML = '<div class="issued">'
    + '<b>已签发：' + esc(label) + '</b>'
    + '<p>令牌明文<b>仅显示这一次</b>，离开本页后无法再次查看：服务器只保存指纹，不保存明文，'
    + '之后无法再次取回。遗失后只能吊销并重新签发。</p>'
    + '<div class="secret">'
    + '<input id="tk-value" type="password" readonly>'
    + '<button type="button" id="tk-copy">复制</button>'
    + '<button type="button" id="tk-reveal">显示</button>'
    + '</div>'
    + '<span class="status" id="tk-copy-msg">令牌默认隐藏，无需显示即可复制，直播过程中也可安全操作</span>'
    + '</div>';

  // 明文直接赋给 value，不拼进 innerHTML：少一次转义与反转义的往返，
  // 也就少一个「口令里恰好含特殊字符」的假设——复制出去的必须与签发的那一把逐字符相同
  const field = $('#tk-value');
  field.value = token;

  const reveal = bindPasswordReveal(field, $('#tk-reveal'), '令牌');

  $('#tk-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(field.value);
      note('已复制到剪贴板', 'ok');
    } catch (e) {
      // 非 https 访问或浏览器不给权限时剪贴板用不了。这里不能只报一句失败：
      // 手上这把口令没有第二次机会，得把它交到人手里
      reveal.setRevealed(true);
      field.select();
      note('当前浏览器不允许写入剪贴板，令牌已选中，请按 Ctrl / ⌘ + C 手动复制', 'err');
    }
  });
}

function note(text, kind) {
  const box = $('#tk-copy-msg');
  box.textContent = text;
  box.className = 'status ' + kind;
}

/**
 * 签发或吊销之后重取本页
 *
 * 走整页重取而不是只重画清单：那张外部面板卡上写着「几把有效」，只更新清单的话，
 * 刚签出来的这一把在清单里出现了，而卡上的数字还停在上一刻——两块说的是同一件事。
 */
function refreshTokens() {
  refreshLinks();
}

function renderTokenList(tokens) {
  const box = $('#token-list');
  if (!tokens.length) {
    box.innerHTML = '<p class="hint" style="margin:0">尚未签发只读访问令牌。'
      + '外部面板无法连接时，请先确认此处是否为空。</p>';
    return;
  }

  // 新的在上：要吊销的多半是刚签错的那一把
  const sorted = tokens.slice().sort((a, b) => (b.issuedAt || 0) - (a.issuedAt || 0));

  box.innerHTML = '<table><thead><tr><th>令牌名称</th><th>指纹</th><th>签发时间</th>'
    + '<th>状态</th><th></th></tr></thead><tbody>'
    + sorted.map(token =>
        '<tr' + (token.active ? '' : ' class="revoked"') + '>'
        + '<td>' + esc(token.label) + '</td>'
        + '<td class="fp">' + esc(token.fingerprint) + '</td>'
        + '<td>' + fmtTime(token.issuedAt) + '</td>'
        + '<td>' + (token.active ? '有效' : '已吊销 · ' + fmtTime(token.revokedAt)) + '</td>'
        + '<td class="act">' + (token.active
            ? '<button type="button" data-fp="' + esc(token.fingerprint) + '">吊销</button>' : '')
        + '</td></tr>').join('')
    + '</tbody></table>'
    // 指纹不是秘密（是口令的哈希前缀），列出来是为了对账：鉴权失败的日志里印的就是它，
    // 否则「有个面板连不上」只能靠猜是哪一把
    + '<p class="hint" style="margin:10px 0 0">鉴权失败的日志中会记录指纹，'
    + '可据此确定是哪个令牌无法连接。吊销会<b>立即断开用该令牌认证的所有已有连接</b>，其他连接不受影响。</p>';

  box.querySelectorAll('button[data-fp]').forEach(button => {
    const token = sorted.find(t => t.fingerprint === button.dataset.fp);
    button.addEventListener('click', () => revoke(token));
  });
}

async function revoke(token) {
  if (!await ask({title: '吊销此令牌？',
    body: '吊销「' + token.label + '」后，该令牌无法再建立新连接，用它认证的所有已有连接也会立即断开。其他连接不受影响。'})) return;

  try {
    const result = await api('/event-tokens/' + encodeURIComponent(token.fingerprint) + '/revoke',
        {method: 'POST'});
    // 显哪句、什么颜色、要不要重取，都在 revokeOutcome 里判——这里只管接线
    const outcome = revokeOutcome(result);
    say(outcome.text, outcome.kind);
    if (outcome.refresh) refreshTokens();
  } catch (e) {
    say('吊销失败：' + e.message, 'err');
  }
}

function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  // 口令能活很久，只印月日会让「去年那把」和「今天这把」看起来一样新
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}
