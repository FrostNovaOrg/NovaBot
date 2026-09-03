/**
 * 入口：整体载入、页签切换与事件绑定
 * 必须最后加载——它在解析时就会调用其余模块里的函数。
 */

import {loadAnalytics} from './analytics.js';
import {bindBotForm, botFormHtml, fillBotForms, sendTestMessage} from './bot.js';
import {$, api, el, esc, markDirty, say} from './core.js';
import {loadHistory, loadState, refreshWizardState, renderStatus, renderWizard, runSelfTest, setWizardCollapsed, togglePush} from './overview.js';
import {addStreamer, decoratePushData, renderPlatforms, renderStreamers, toggleAdvanced} from './push.js';
import {renderBackups, renderGeneral, save, saveRaw} from './settings.js';
import {store} from './store.js';
import {clearIssuedToken, loadTokens} from './tokens.js';

/**
 * 插件带来的页，装好之后每项是 {meta, module}
 *
 * 平台页此前是编译期定死的一句 import：核心必须先认识那个平台，页面才存在。
 * 现在它是运行期的一张清单——核心只知道「有几页、叫什么、脚本在哪」，页里做什么与它无关。
 */
const pages = [];

/**
 * 按注册清单建出页签与页面容器，并装载各自的脚本
 *
 * 逐个装而不是一次性 Promise.all：页签的先后要与清单一致，
 * 而某一页装不上时也只该影响它自己——其余的页照常可用，那一页上写清为什么空着。
 */
async function mountPages() {
  let list = [];
  try {
    list = (await api('/pages')).pages || [];
  } catch (e) {
    // 取不到清单只是没有平台页，控制台其余部分照常
    return;
  }

  const slot = $('#page-tabs');
  for (const meta of list) {
    const button = el('button');
    button.type = 'button';
    button.dataset.tab = meta.id;
    button.textContent = meta.displayName;
    button.addEventListener('click', () => switchTab(meta.id));
    slot.parentNode.insertBefore(button, slot);

    const section = el('section');
    section.id = meta.id;
    $('#page-sections').appendChild(section);

    try {
      const module = await import('/config/assets/' + meta.script);
      pages.push({meta, module});
      module.render?.(section);
    } catch (e) {
      section.innerHTML = '<p class="hint">' + esc(meta.displayName) + ' 的页面没能载入：' + esc(e.message)
        + '。该插件的其余功能不受影响。</p>';
    }
  }
}

/**
 * 调某一页的钩子，它抛出来只算它自己那一页出事
 *
 * 钩子里跑的是插件的代码。不拦在这里的话，一页抛出去会当场中断整轮遍历——
 * 排在它后面的页就都不再刷新，而调用方那一趟整体载入也跟着失败，
 * 屏幕上只剩一句与出事那一页毫无关系的「载入失败」。
 */
function callPage(page, hook, ...args) {
  try {
    page.module[hook]?.(...args);
  } catch (e) {
    say(page.meta.displayName + ' 页出错：' + e.message, 'err');
  }
}

/**
 * 让各插件页重取自己的数据
 */
export function refreshPages() {
  pages.forEach(page => callPage(page, 'refresh'));
}

/**
 * 把运行状态转给各插件页，由它们自己挑要用的部分
 */
export function pageStatus(data) {
  pages.forEach(page => callPage(page, 'status', data));
}

export async function load() {
  say('载入中…');
  try {
    const [s, v] = await Promise.all([api('/schema'), api('/values')]);
    store.schema = s.groups || [];
    store.values = v.values || {};
    // 哪几项是按旧位置生效的，要跟着值一起进来：值与它的出处分开取，两次之间配置一变就对不上了
    store.legacy = v.legacy || {};
    store.dirty = {};
    renderGeneral();

    const [d, y, st, b, h, p] = await Promise.all([
      api('/datasource'), api('/raw'), api('/status'), api('/backups'), api('/handlers'), api('/platforms')]);
    $('#datasource').value = d.content || '[]';
    $('#rawyml').value = y.content || '';
    renderStatus(st);

    store.handlerList = h.handlers || [];
    store.senderList = st.senders || [];
    // 能添加哪些平台的主播由已注册的数据源服务决定，界面不替任何一个平台作主
    store.platforms = p.platforms || [];
    renderPlatforms();
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
    refreshPages();
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
  // 切走就把刚签发的口令从 DOM 里抹掉。界面上写着「离开本页后无法再次查看」，
  // 这一行就是那句话的实现——留着它，那句话只是句话
  if (store.tab === 'tokens' && name !== 'tokens') clearIssuedToken();

  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x.dataset.tab === name));
  document.querySelectorAll('section').forEach(x => x.classList.toggle('on', x.id === name));
  store.tab = name;

  // 窄屏上页签栏是横向滚动的，靠右的页签会落在视野外。选中却看不见等于没有选中标记，
  // 因此把当前页签滚进来。block:'nearest' 防止它顺带把整页往下拉
  $('nav button.on')?.scrollIntoView({inline: 'center', block: 'nearest'});

  clearTimeout(store.accountTimer);
  // 总览页的向导里也有平台的二维码，因此那一页同样要把各平台页刷一遍
  if (store.tab === 'overview') { api('/status').then(renderStatus); loadHistory(); refreshPages(); refreshWizardState(); }
  else if (store.tab === 'bot') api('/status').then(renderStatus);
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人
  else if (store.tab === 'sessions') loadState();
  else if (store.tab === 'analytics') loadAnalytics();
  // 每次进入都重建：顺带抹掉上一次留在屏幕上的口令明文
  else if (store.tab === 'tokens') loadTokens();
  else {
    // 剩下的都是插件带来的页：核心不知道它们叫什么，按注册清单认
    const page = pages.find(item => item.meta.id === store.tab);
    if (page) { api('/status').then(renderStatus); callPage(page, 'refresh'); }
  }

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

// NapCat 控制台入口。凭据没配好就不显示这一块——
// 显示了点进去才报错，比不显示更难懂
api('/napcat/state')
  .then(state => { if (state.configured) $('#napcat-entry').style.display = ''; })
  .catch(() => {});
// 走引导页而不是直接跳 WebUI：凭据要由同源脚本写进 localStorage，
// 服务端下发再多头也写不进浏览器的存储
$('#napcat-open').addEventListener('click', () => { location.href = '/config/napcat-bootstrap'; });

// 登录态必须先于正式载入取到：CSRF 令牌从这里来，缺了它所有写请求都会被拒。
// 取不到也照常载入——未启用口令登录时本就没有令牌，读接口不受影响
api('/auth/state')
  .then(state => {
    store.csrfToken = state.csrfToken || '';
    // 签发只读口令要重新校验一次凭据，验证码框显示与否照这一位来，不照配置项猜
    store.totpRequired = !!state.totpRequired;
    $('#auth-actions').style.display = state.enabled ? '' : 'none';
    if (state.totpSetupNeeded) renderTotpSetup();
  })
  .catch(() => {})
  // 插件页要先挂上去，随后那一趟整体载入才有东西可刷
  .finally(() => mountPages().finally(load));
