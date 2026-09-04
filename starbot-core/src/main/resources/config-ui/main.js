/**
 * 入口：整体载入、六页路由与事件绑定
 * 必须最后加载——它在解析时就会调用其余模块里的函数。
 */

import {loadAnalytics} from './analytics.js';
import {bindBotForm, botFormHtml, fillBotForms, sendTestMessage} from './bot.js';
import {$, api, el, esc, markDirty, say} from './core.js';
import {loadHistory, loadState, refreshWizardState, renderStatus, renderWizard, runSelfTest, setWizardCollapsed, togglePush} from './overview.js';
import {addStreamer, decoratePushData, renderPlatforms, renderStreamers, serializePush} from './push.js';
import {loadPasskeys, registerPasskey} from './passkeys.js';
import {copyConfigPath, discard, renderConfigPath, renderGeneral, save} from './settings.js';
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
 * 按注册清单建出入口与页面容器，并装载各自的脚本
 *
 * 逐个装而不是一次性 Promise.all：入口的先后要与清单一致，
 * 而某一页装不上时也只该影响它自己——其余的页照常可用，那一页上写清为什么空着。
 *
 * 六页导航之后，插件页挂在设置页的「高级」下，地址是 #/settings/<页标识>。
 * 与插件之间的约定一个字未改：仍是清单里的 id/displayName/script，
 * 加上脚本导出的 render/refresh/status ——它们不知道自己被挂在哪里。
 */
async function mountPages() {
  let list = [];
  try {
    list = (await api('/pages')).pages || [];
  } catch (e) {
    // 取不到清单只是没有平台页，控制台其余部分照常
    return;
  }

  // 一个插件页都没有时整块不显示：一个点开是空的折页，比没有这个折页更费解
  if (list.length) $('#plugin-adv').style.display = '';

  const slot = $('#page-tabs');
  for (const meta of list) {
    const button = el('button');
    button.type = 'button';
    button.dataset.tab = meta.id;
    button.textContent = meta.displayName;
    button.addEventListener('click', () => switchTab(meta.id));
    slot.appendChild(button);

    const section = el('section', 'pgpage');
    section.id = meta.id;
    $('#page-sections').appendChild(section);

    try {
      const module = await import('/config/assets/' + meta.script);
      pages.push({meta, module});
      module.render?.(section);
    } catch (e) {
      // 装不上的页也要留在清单里。清单是路由认页的唯一依据，不留就等于 #/settings/<页标识>
      // 认不出它，于是下面这句「为什么空着」永远显示不出来——而这句话正是给装不上时看的
      if (!pages.some(item => item.meta.id === meta.id)) pages.push({meta, module: {}});
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
    // 生效时机摊平成一张表：底部改动条要按键查，而字段表是按分组套着的
    store.effects = {};
    for (const g of store.schema) {
      for (const f of g.fields) store.effects[f.name] = f.effect;
    }
    store.values = v.values || {};
    // 哪几项是按旧位置生效的，要跟着值一起进来：值与它的出处分开取，两次之间配置一变就对不上了
    store.legacy = v.legacy || {};
    store.dirty = {};
    renderGeneral();

    const [d, st, h, p] = await Promise.all([
      api('/datasource'), api('/status'), api('/handlers'), api('/platforms')]);
    renderStatus(st);
    renderConfigPath(st.configPath);
    // 上一次保存留下的「还欠一次重启」，刷新页面、换台机器打开都该照样看得见
    store.restartPending = st.restartPending || [];

    store.handlerList = h.handlers || [];
    store.senderList = st.senders || [];
    // 能添加哪些平台的主播由已注册的数据源服务决定，界面不替任何一个平台作主
    store.platforms = p.platforms || [];
    renderPlatforms();
    // 推送配置解析不了是这一趟里唯一「载入成功了但有话要说」的情况。
    // 记一位是因为末尾那句 say('') 会把状态栏清空——不记的话，这条提示刚显示就被自己抹掉
    let pushBroken = false;
    try {
      store.pushData = JSON.parse(d.content || '[]');
      // 快照要取序列化之后的形态，与改动计数比的是同一把尺；
      // 直接存 d.content 的话，文件里的缩进与键序都会算成「改动」
      store.pushSaved = serializePush();
    } catch (e) {
      // 内容不合法时不在界面上开一个编辑器让人现场改文件——那条路已经撤了。
      // 这里只把话说清楚：改哪个文件、在哪儿改，路径就显示在设置页底部
      store.pushData = [];
      store.pushSaved = '[]';
      pushBroken = true;
      say('推送配置 datasource.json 不是合法 JSON，界面上暂时看不到已配好的主播。'
        + '请到服务器上修正该文件后重新载入', 'err');
    }
    renderStreamers();
    decoratePushData();
    refreshPages();
    loadHistory();
    loadState();
    renderWizard();
    // 向导渲染完成后两份表单才都在 DOM 里，此时统一回填
    fillBotForms();

    const count = store.schema.reduce((n, g) => n + g.fields.length, 0);
    $('#head-sub').textContent = count + ' 个配置项 · ' + store.schema.length + ' 个分组';
    if (!pushBroken) say('');
  } catch (e) {
    say('载入失败：' + e.message, 'err');
  }
  markDirty();
}

/**
 * 六页导航的路由名 → 该页当家的那个旧页签
 *
 * store.tab 仍取旧页签名，没有跟着改成路由名：它是插件页读得到的东西
 * （带扫码登录的那类插件页据 `store.tab === 'overview'` 决定要不要接着轮询二维码），
 * 换一套取值就是悄悄改了对插件的约定，而表现是那张二维码扫完不自己变。
 *
 * 一页装下两个旧页签时，取有草稿态的那个：底部保存按钮照 store.tab 决定存去哪
 * （见 saveTarget）。「群与成员」与「只读口令」都是改完立刻落盘的，不占这一位。
 */
const PAGE_TAB = {
  home: 'overview', push: 'push', streamers: 'analytics',
  log: 'log', links: 'bot', settings: 'settings', setup: 'setup',
};

/**
 * 旧页签名 → 它现在所在的地址
 *
 * 旧页签在新导航里不再一一对应一页：群与成员并进了「QQ 推送」，只读口令并进了「连接」。
 * 调用方只知道自己要去的那个页签叫什么，这张表因此由这里维护，调用点一处未改。
 */
const TAB_HASH = {
  overview: '#/home', push: '#/push', sessions: '#/push', analytics: '#/streamers',
  log: '#/log', bot: '#/links', tokens: '#/links', settings: '#/settings', setup: '#/setup',
};

/** 上一次落在哪个路由。离开某页时要收的尾巴据此判断 */
let route = '';

/**
 * 解析地址栏。认不出来的路由一律当首页，不留白屏
 * @return {{name: string, sub: string}} 路由名与子路由（插件页标识）
 */
function parseHash() {
  const parts = (location.hash || '').replace(/^#\/?/, '').split('/').filter(Boolean);
  const name = parts[0] || 'home';
  return {name: PAGE_TAB[name] ? name : 'home', sub: parts[1] || ''};
}

/**
 * 按地址栏切页
 *
 * 只认地址栏、不认「谁点了哪个链接」：刷新、收藏、后退三种进法走的都是这一条路，
 * 各写一套的话，从地址栏直接进 #/settings 与点侧栏进去会渲染出两种结果。
 * @param withData 是否顺带重取本页的数据。首屏那一次只摆版式，数据由随后的整体载入取
 */
function applyRoute(withData = true) {
  const {name, sub} = parseHash();
  const plugin = name === 'settings' && sub ? pages.find(item => item.meta.id === sub) : null;

  // 离开「连接」页就把刚签发的口令从 DOM 里抹掉。界面上写着「离开本页后无法再次查看」，
  // 这一行就是那句话的实现——留着它，那句话只是句话
  if (route === 'links' && name !== 'links') clearIssuedToken();
  route = name;

  document.querySelectorAll('#nav a').forEach(a => {
    if (a.dataset.page === name) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  document.querySelectorAll('.page').forEach(x => x.classList.toggle('on', x.id === 'page-' + name));
  document.querySelectorAll('.pgpage').forEach(x => x.classList.toggle('on', !!plugin && x.id === plugin.meta.id));
  document.querySelectorAll('#page-tabs button').forEach(
    x => x.classList.toggle('on', !!plugin && x.dataset.tab === plugin.meta.id));
  // 初始设置页是独立版式：这台机器可能还没上锁，页头上的「退出登录」无从谈起
  document.documentElement.classList.toggle('chromeless', name === 'setup');
  // 插件页是折起来的，点它的入口进来时要替使用者展开，否则地址对了而屏幕上什么都没变
  if (plugin) $('#plugin-adv').open = true;

  store.tab = plugin ? plugin.meta.id : PAGE_TAB[name];

  // 窄屏上导航是横向滚动的，靠右的项会落在视野外。选中却看不见等于没有选中标记
  $('#nav a[aria-current="page"]')?.scrollIntoView({inline: 'center', block: 'nearest'});

  markDirty();
  if (!withData) return;

  clearTimeout(store.accountTimer);
  // 首页的向导里也有平台的二维码，因此这一页同样要把各插件页刷一遍
  if (name === 'home') { api('/status').then(renderStatus); loadHistory(); refreshPages(); refreshWizardState(); }
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人
  else if (name === 'push') loadState();
  else if (name === 'streamers') loadAnalytics();
  // 每次进入都重建只读口令那一块：顺带抹掉上一次留在屏幕上的口令明文
  else if (name === 'links') { api('/status').then(renderStatus); loadTokens(); }
  else if (plugin) { api('/status').then(renderStatus); callPage(plugin, 'refresh'); }

  window.scrollTo(0, 0);
}

/**
 * 跳到某个旧页签现在所在的位置
 * @param name 旧页签名，或插件页标识
 */
export function switchTab(name) {
  const hash = TAB_HASH[name] || (pages.some(item => item.meta.id === name) ? '#/settings/' + name : '#/home');
  // 地址没变就不会有 hashchange，此时直接走一遍——否则点第二次「前往」毫无反应
  if (location.hash === hash) applyRoute();
  else location.hash = hash;
}

// 包一层：事件回调的第一个参数是 HashChangeEvent，直接挂上去会被当成 withData
window.addEventListener('hashchange', () => applyRoute());

// 点已经选中的那一项，地址没变就不会有 hashchange。页签时代点同一个页签是会重取的，
// 这里把那个动作接回来——不接的话，「再点一下看看最新的」这条路会悄悄消失
document.querySelectorAll('#nav a').forEach(a => {
  a.addEventListener('click', () => { if (location.hash === a.getAttribute('href')) applyRoute(); });
});

$('#passkey-add').addEventListener('click', registerPasskey);
$('#save').addEventListener('click', save);
$('#discard').addEventListener('click', discard);
$('#cfg-copy').addEventListener('click', copyConfigPath);
$('#test-send').addEventListener('click', sendTestMessage);
$('#selftest-run').addEventListener('click', runSelfTest);
// 切换显示范围时保留已改动的字段：重绘只影响可见性，不该丢掉未保存的编辑
$('#show-advanced').addEventListener('change', () => { renderGeneral(); markDirty(); });
$('#toggle-push').addEventListener('click', togglePush);
$('#add-streamer').addEventListener('click', addStreamer);
$('#add-uid').addEventListener('keydown', e => { if (e.key === 'Enter') addStreamer(); });
$('#wizard-toggle').addEventListener('click', () => {
  store.wizardTouched = true;
  setWizardCollapsed($('#wizard').style.display !== 'none');
});
$('#ana-view').addEventListener('change', loadAnalytics);
$('#ana-period').addEventListener('change', loadAnalytics);
$('#ana-uid').addEventListener('change', loadAnalytics);
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
    // 通行密钥跟着口令登录的开关走：未配口令时面板走的是「令牌即凭据」那一形态，
    // 它根本不看会话，验过通行密钥签出来的会话也一样进不去
    if (state.enabled) {
      $('#passkey-box').style.display = '';
      loadPasskeys();
    }
    if (state.totpSetupNeeded) renderTotpSetup();
  })
  .catch(() => {})
  // 插件页要先挂上去，随后那一趟整体载入才有东西可刷
  .finally(() => mountPages().finally(() => { applyRoute(); load(); }));

// 先按地址栏摆好版式，不等接口回来
//
// 把这一步一并放进上面那条链里的话，接口慢或者不通时屏幕上一页都不显示——
// 六个 .page 默认都是收起的，而「哪一页该展开」这件事本身不需要任何接口就知道。
// 这一趟只摆版式不取数据：数据由上面那条链走完之后的整体载入去取
applyRoute(false);
