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

import {$, api, esc, say} from './core.js';
import {store} from './store.js';

/**
 * 进入本页签时调用
 * <p>
 * 每次进入都重建签发表单：顺带把上一次留在屏幕上的口令抹掉，
 * 这正是「离开本页后无法再次查看」要的效果。
 */
export function loadTokens() {
  $('#token-issue').innerHTML = issueFormHtml();
  $('#tk-issue').addEventListener('click', issue);
  $('#tk-label').addEventListener('keydown', e => { if (e.key === 'Enter') $('#tk-pass').focus(); });
  loadTokenList();
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
  return '<p class="hint">签发要<b>再输一次</b>控制台口令'
    + (store.totpRequired ? '与动态验证码' : '')
    + '。这一步是有意留的：用当前这个已登录的会话直接签，就等于「浏览器里有枚 Cookie 就能签出口令」——'
    + '借走一台没锁屏的电脑也签得出来，而签出来的口令长期有效。签发是低频动作，多这一步买断这条路。</p>'

    + '<div class="row"><label for="tk-label">签给谁</label>'
    + '<input id="tk-label" maxlength="40" placeholder="例如：客厅那台电脑" autocomplete="off"></div>'
    + '<div class="row"><label></label><span class="hint" style="margin:0;flex:1;min-width:220px">'
    + '这个名字会原样出现在下面的清单里，日后单独吊销就靠它。'
    + '<b>别填带真名的机器名</b>——「某某的 MacBook」会把一个人名留在这台服务器上。</span></div>'

    + '<div class="row"><label for="tk-pass">控制台口令</label>'
    + '<input type="password" id="tk-pass" autocomplete="off"></div>'

    // 验证器没绑就不显示这一格。上一次事故正出在这里：说明文字写的是「没开两步验证就直接回车」，
    // 而那台机器的二次验证是开着的，照做必然失败，且使用者没有任何办法自己发现说明写错了
    + (store.totpRequired
        ? '<div class="row"><label for="tk-code">动态验证码</label>'
          + '<input id="tk-code" inputmode="numeric" pattern="[0-9]*" maxlength="6"'
          + ' placeholder="验证器里的 6 位数字" autocomplete="one-time-code"></div>'
        : '')

    + '<div class="row"><label></label><button type="button" id="tk-issue">签发</button></div>'
    + '<div class="out" id="tk-msg"></div>'
    + '<div id="token-issued"></div>';
}

async function issue() {
  const label = $('#tk-label').value.trim();
  const password = $('#tk-pass').value;
  const codeInput = $('#tk-code');
  const code = codeInput ? codeInput.value.trim() : '';

  if (!label) return fail('先填「签给谁」。没有它日后只能一次全撤，把别的面板一起踢下线');
  if (!password) return fail('请输入控制台口令');
  if (store.totpRequired && !/^\d{6}$/.test(code)) return fail('动态验证码是 6 位数字，请从验证器应用里读取');

  const body = {label, password};
  // 契约定的是「不传」而非空串——服务端对这两种写法走的是两条路径
  if (code) body.code = code;

  const button = $('#tk-issue');
  button.disabled = true;
  fail('');
  try {
    const response = await fetch('/nova/readonly-token', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
    });
    const data = await response.json().catch(() => ({}));

    if (response.ok && data.token) {
      showIssued(data.token, label);
      $('#tk-pass').value = '';
      if (codeInput) codeInput.value = '';
      loadTokenList();
    } else {
      // 失败时也把口令框清空：留着上一次输错的内容，再点一次还是同样的错
      $('#tk-pass').value = '';
      fail(explain(response.status, data));
    }
  } catch (e) {
    fail('无法连接到服务器');
  } finally {
    button.disabled = false;
  }
}

/**
 * 把契约里的 reason 翻成人话
 *
 * 🔴 locked_out 与 bad_credentials 必须分开说：锁定期内输对的口令也会被拒，
 * 此时说「口令不对」会让人去重置一个根本没问题的密码。
 */
function explain(status, data) {
  switch (data.reason) {
    case 'bad_credentials':
      return '控制台口令' + (store.totpRequired ? '或动态验证码' : '') + '不对。'
        + '要填的是登录这个控制台用的那一个——不是机器人（NapCat 等）WebUI 的口令，两者互不相干';
    case 'locked_out':
      return '连续失败太多次，你这个来源已被暂时锁定，' + waitText(data.retryAfterSeconds) + '后再试。'
        + '⚠️ 锁定期内即使输对也会被拒，这不代表口令错了，别急着去改密码';
    case 'busy':
      return '同时在校验的请求太多，等几秒再点一次';
    case 'auth_disabled':
      return '这台机器没有启用控制台登录口令，因此没有可校验的凭据。请先在「设置」里配置登录口令';
    default:
      return '签发失败（HTTP ' + status + '）';
  }
}

function waitText(seconds) {
  const value = Number(seconds) || 0;
  return value >= 60 ? Math.ceil(value / 60) + ' 分钟' : Math.max(1, value) + ' 秒';
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
    + '<p>这是这把口令<b>唯一一次显示</b>，离开本页后无法再次查看：服务器只存指纹不存明文，'
    + '「再看一眼」这件事在这里不存在，忘了就只能吊销重签。</p>'
    + '<div class="secret">'
    + '<input id="tk-value" type="password" readonly>'
    + '<button type="button" id="tk-copy">复制</button>'
    + '<button type="button" id="tk-reveal">显示</button>'
    + '</div>'
    + '<span class="status" id="tk-copy-msg">默认遮住：复制不需要先显示出来，正在直播时也能安全地按</span>'
    + '</div>';

  // 明文直接赋给 value，不拼进 innerHTML：少一次转义与反转义的往返，
  // 也就少一个「口令里恰好含特殊字符」的假设——复制出去的必须与签发的那一把逐字符相同
  const field = $('#tk-value');
  field.value = token;

  $('#tk-reveal').addEventListener('click', () => {
    const hidden = field.type === 'password';
    field.type = hidden ? 'text' : 'password';
    $('#tk-reveal').textContent = hidden ? '隐藏' : '显示';
  });

  $('#tk-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(field.value);
      note('已复制到剪贴板', 'ok');
    } catch (e) {
      // 非 https 访问或浏览器不给权限时剪贴板用不了。这里不能只报一句失败：
      // 手上这把口令没有第二次机会，得把它交到人手里
      field.type = 'text';
      $('#tk-reveal').textContent = '隐藏';
      field.select();
      note('这个浏览器不让脚本写剪贴板，已为你选中，请按 Ctrl / ⌘ + C 复制', 'err');
    }
  });
}

function note(text, kind) {
  const box = $('#tk-copy-msg');
  box.textContent = text;
  box.className = 'status ' + kind;
}

async function loadTokenList() {
  const box = $('#token-list');
  box.innerHTML = '<p class="hint" style="margin:0">载入中…</p>';
  try {
    const result = await api('/event-tokens');
    renderTokenList(result.tokens || []);
  } catch (e) {
    box.innerHTML = '<p class="hint" style="margin:0">载入失败：' + esc(e.message) + '</p>';
  }
}

function renderTokenList(tokens) {
  const box = $('#token-list');
  if (!tokens.length) {
    box.innerHTML = '<p class="hint" style="margin:0">还没有签发过任何口令。'
      + '外部面板连不上时，先看这里是不是空的。</p>';
    return;
  }

  // 新的在上：要吊销的多半是刚签错的那一把
  const sorted = tokens.slice().sort((a, b) => (b.issuedAt || 0) - (a.issuedAt || 0));

  box.innerHTML = '<table><thead><tr><th>签给谁</th><th>指纹</th><th>签发时间</th>'
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
    + '<p class="hint" style="margin:10px 0 0">指纹会出现在鉴权失败的日志里，'
    + '用它可以认出是哪一把口令连不上。吊销<b>不会断开已经建立的连接</b>，撤完请确认对方确实掉线了。</p>';

  box.querySelectorAll('button[data-fp]').forEach(button => {
    const token = sorted.find(t => t.fingerprint === button.dataset.fp);
    button.addEventListener('click', () => revoke(token));
  });
}

async function revoke(token) {
  if (!confirm('确定吊销「' + token.label + '」这把口令吗？\n\n'
      + '⚠️ 已经连上的面板不会因此掉线——吊销只对新连接生效。撤完请确认对方确实断开了。')) return;

  try {
    const result = await api('/event-tokens/' + encodeURIComponent(token.fingerprint) + '/revoke',
        {method: 'POST'});
    say(result.message || (result.success ? '已吊销' : '操作失败'), result.success ? 'ok' : 'err');
    if (result.success) loadTokenList();
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
