/**
 * 入口：整体载入、页签切换与事件绑定
 * 必须最后加载——它在解析时就会调用其余模块里的函数。
 */

import {loadAnalytics} from './analytics.js';
import {loadAccounts} from './bilibili.js';
import {bindBotForm, botFormHtml, fillBotForms, sendTestMessage} from './bot.js';
import {$, api, esc, markDirty, say} from './core.js';
import {loadHistory, loadState, refreshWizardState, renderStatus, renderWizard, runSelfTest, setWizardCollapsed, togglePush} from './overview.js';
import {addStreamer, decoratePushData, renderStreamers, toggleAdvanced} from './push.js';
import {renderBackups, renderGeneral, save, saveRaw} from './settings.js';
import {store} from './store.js';

export async function load() {
  say('载入中…');
  try {
    const [s, v] = await Promise.all([api('/schema'), api('/values')]);
    store.schema = s.groups || [];
    store.values = v.values || {};
    store.dirty = {};
    renderGeneral();

    const [d, y, st, b, h] = await Promise.all([
      api('/datasource'), api('/raw'), api('/status'), api('/backups'), api('/handlers')]);
    $('#datasource').value = d.content || '[]';
    $('#rawyml').value = y.content || '';
    renderStatus(st);

    store.handlerList = h.handlers || [];
    store.senderList = st.senders || [];
    try {
      store.pushData = JSON.parse(d.content || '[]');
    } catch (e) {
      // 文件内容不合法时退回高级模式，让使用者直接修，而不是把错误内容悄悄吞掉
      store.pushData = [];
      if (!store.advancedMode) toggleAdvanced();
      say('推送配置不是合法 JSON，已切换到高级模式供你修正', 'err');
    }
    renderStreamers();
    decoratePushData();
    renderBackups(b.backups);
    loadAccounts();
    loadHistory();
    loadState();
    renderWizard();
    // 向导渲染完成后两份表单才都在 DOM 里，此时统一回填
    fillBotForms();

    const count = store.schema.reduce((n, g) => n + g.fields.length, 0);
    $('#head-sub').textContent = count + ' 个配置项 · ' + store.schema.length + ' 个分组';
    say('');
  } catch (e) {
    say('载入失败：' + e.message, 'err');
  }
  markDirty();
}

export function switchTab(name) {
  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x.dataset.tab === name));
  document.querySelectorAll('section').forEach(x => x.classList.toggle('on', x.id === name));
  store.tab = name;

  // 窄屏上页签栏是横向滚动的，靠右的页签会落在视野外。选中却看不见等于没有选中标记，
  // 因此把当前页签滚进来。block:'nearest' 防止它顺带把整页往下拉
  $('nav button.on')?.scrollIntoView({inline: 'center', block: 'nearest'});

  clearTimeout(store.accountTimer);
  if (store.tab === 'overview') { api('/status').then(renderStatus); loadHistory(); loadAccounts(); refreshWizardState(); }
  else if (store.tab === 'bot') api('/status').then(renderStatus);
  else if (store.tab === 'bilibili') { api('/status').then(renderStatus); loadAccounts(); }
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人
  else if (store.tab === 'sessions') loadState();
  else if (store.tab === 'analytics') loadAnalytics();

  markDirty();
}

document.querySelectorAll('nav button').forEach(b => {
  b.addEventListener('click', () => switchTab(b.dataset.tab));
});

$('#save').addEventListener('click', save);
$('#raw-save').addEventListener('click', saveRaw);
$('#test-send').addEventListener('click', sendTestMessage);
$('#selftest-run').addEventListener('click', runSelfTest);
// 切换显示范围时保留已改动的字段：重绘只影响可见性，不该丢掉未保存的编辑
$('#show-advanced').addEventListener('change', () => { renderGeneral(); markDirty(); });
$('#toggle-push').addEventListener('click', togglePush);
$('#add-streamer').addEventListener('click', addStreamer);
$('#toggle-advanced').addEventListener('click', toggleAdvanced);
$('#add-uid').addEventListener('keydown', e => { if (e.key === 'Enter') addStreamer(); });
$('#wizard-toggle').addEventListener('click', () => {
  store.wizardTouched = true;
  setWizardCollapsed($('#wizard').style.display !== 'none');
});
$('#ana-view').addEventListener('change', loadAnalytics);
$('#ana-period').addEventListener('change', loadAnalytics);
$('#ana-uid').addEventListener('change', loadAnalytics);
$('#reload').addEventListener('click', load);
/**
 * 未绑定验证器时的引导卡片
 *
 * 必须让用户先输一次验证码才算绑定成功——少了这一步，扫码没扫上的人会以为绑好了，
 * 下次登录被自己的二次验证挡在门外，而那时已经没有界面可以撤销了。
 */
async function renderTotpSetup() {
  const box = $('#totp-setup');
  const setup = await api('/auth/totp/setup');
  if (!setup.success) return;

  box.style.display = '';
  box.innerHTML =
    '<h3>建议绑定验证器</h3>'
    + '<p>面板开到公网后，只有口令这一道防线。用任意验证器应用扫码，再输一次它给出的数字即可。</p>'
    + '<div class="totp-body">'
    + (setup.qrCode ? '<img src="data:image/png;base64,' + esc(setup.qrCode) + '" alt="二维码">' : '')
    + '<div class="totp-side">'
    + '<label>不方便扫码时手动输入这串密钥</label>'
    + '<code>' + esc(setup.secret) + '</code>'
    + '<div class="totp-confirm">'
    + '<input id="totp-code" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="6 位数字">'
    + '<button type="button" id="totp-enroll">确认绑定</button>'
    + '<button type="button" id="totp-skip">暂不绑定</button>'
    + '</div><span class="status" id="totp-msg"></span>'
    + '</div></div>';

  $('#totp-enroll').addEventListener('click', async () => {
    const r = await api('/auth/totp/enroll', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({code: $('#totp-code').value})
    });
    if (r.success) {
      box.style.display = 'none';
      say(r.message, 'ok');
      return;
    }
    $('#totp-msg').textContent = r.message;
    $('#totp-msg').className = 'status err';
  });

  $('#totp-skip').addEventListener('click', async () => {
    await api('/auth/totp/skip', {method: 'POST'});
    box.style.display = 'none';
    // 只跳过这一次登录。要永久关掉得去改 starbot.core.config-ui.auth.totp，
    // 那是个该显式做出的决定，不该由一次「等会儿再说」代劳
    say('本次登录不再提示。要永久关闭请改配置项 auth.totp');
  });
}

$('#logout').addEventListener('click', async () => {
  await api('/auth/logout', {method: 'POST'});
  location.reload();
});
$('#logout-all').addEventListener('click', async () => {
  if (!confirm('将注销所有设备上的登录，包括当前这一个。继续？')) return;
  await api('/auth/logout?all=true', {method: 'POST'});
  location.reload();
});
// 先注入 DOM 再绑定，否则 bindBotForm 取到的是 null
$('#bot-form').innerHTML = botFormHtml('bot');
bindBotForm('bot');

// 登录态必须先于正式载入取到：CSRF 令牌从这里来，缺了它所有写请求都会被拒。
// 取不到也照常载入——未启用口令登录时本就没有令牌，读接口不受影响
api('/auth/state')
  .then(state => {
    store.csrfToken = state.csrfToken || '';
    $('#auth-actions').style.display = state.enabled ? '' : 'none';
    if (state.totpSetupNeeded) renderTotpSetup();
  })
  .catch(() => {})
  .finally(load);
