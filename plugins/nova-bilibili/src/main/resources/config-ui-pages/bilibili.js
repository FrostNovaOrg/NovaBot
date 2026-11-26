/**
 * 哔哩哔哩连接卡：账号扫码登录与登出
 *
 * 本文件随插件走，由控制台在运行时按注册清单装载，落在连接页上成为一张卡。
 * 三个导出就是与控制台之间的全部约定：render 建卡里的内容、refresh 重取本卡数据、
 * status 拿到运行状态后渲染本卡那一块。控制台不认识这里的任何一个接口路径。
 *
 * 卡壳（标题、灯、「已登录 / 匿名 / 未登录」那句话、凭据还能用多久）由控制台按运行状态画：
 * 它手里有探针与 /api/login 的账号状态，答得出「这一段此刻是好是坏」。
 * 这里只做控制台做不了的那两件事——把二维码摆出来，以及退出登录。
 *
 * 监听中的主播那张表已搬去主播页：它的每一列都来自 /api/status，与是哪个平台无关，
 * 留在这里的代价是装第二个平台时会出现两张各列一半的表。
 */

import {ask} from './confirm.js';
import {$, api, el, esc} from './core.js';
import {showLinkResult} from './link-feedback.js';
import {refreshLinks} from './links.js';
import {healthRows, refreshHome} from './overview.js';
import {store} from './store.js';

/**
 * 连接页上那张卡此刻在不在人眼前
 *
 * 控制台把连接页整页的页签仍叫作 bot（页签名是插件读得到的东西，见 store.tab），
 * 因此本卡可见与否要按它判，而不是按本插件自己的标识。
 */
const VISIBLE_TABS = ['bot', 'overview'];

/**
 * 本卡管的那个账号的标识
 *
 * 同一个平台上可以另有账号（例如只用来发言的那个），它们有自己的卡。/api/login 把所有账号都回来，
 * 这里只认自己这一个：照单全画的话，另一个账号会以「正在生成二维码，请稍候」混进本卡，
 * 而它平时不备二维码，这一句永远等不到，本卡还会为它每 3 秒刷一次。
 */
const OWN_ACCOUNT = 'bilibili';
const logoutResults = new Map();
const signingOut = new Set();
let accountRequest = 0;

/**
 * 建出卡里的内容
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  section.innerHTML = `
    <p class="hint lc-intro">登录哔哩哔哩后可获取动态与直播间信息。</p>
    <section class="lc-sub" id="bili-accounts" hidden>
      <h3 class="lc-h">账号登录</h3>
      <p class="hint">登录失效后，动态推送与自动关注将停止，直播推送不受影响。启用告警并配置了告警通道时，会发出告警通知。</p>
      <div id="accounts"></div>
    </section>
    <section class="lc-sub" id="bili-checks" hidden>
      <h3 class="lc-h">连接检查</h3>
      <div class="health" id="bili-health"></div>
    </section>`;
}

/**
 * 重取本卡数据。控制台在整体载入时与切到连接页时各调一次
 */
export function refresh() {
  loadAccounts();
}

/**
 * 渲染运行状态里属于本卡的那一块
 *
 * 只列<b>登录之外</b>的平台探针：登录那一条已经由卡头说了，两处各说一遍的话，
 * 掉登录时同一句话会在同一张卡上出现两次，而读的人会以为是两件事。
 *
 * 控制台把 /status 的原样数据交过来，由本卡自己挑要用的部分——它不知道这张卡上有哪些元素。
 * 反过来写成「控制台往 #bili-health 里塞东西」的话，这个 id 就成了一条谁都看不见的约定，
 * 插件改个 id 就静默地什么都不显示了。
 * @param data 运行状态
 */
export function status(data) {
  const box = $('#bili-health');
  if (!box) return;

  const rest = (data.health || []).filter(h => h.scope === 'PLATFORM' && !h.loginState);
  $('#bili-checks').hidden = !rest.length;
  box.innerHTML = rest.length ? healthRows(rest, '') : '';
}

function renderAccounts(all) {
  const box = $('#accounts');
  if (!box) return;

  // 老回包没有 id 时退回平台名，与控制台配卡的认法一致
  const accounts = (all || []).filter(a => (a.id || a.platform) === OWN_ACCOUNT);
  const html = accounts.map(a => {
    // 登录被配置关掉时（例如匿名模式），二维码永远不会来。卡头已经写明是匿名模式，
    // 这里就不再摆一个「尚未生成二维码，请稍候」——那个「稍候」是没有尽头的
    if (a.disabledReason) return '';

    const qr = a.qrCode
      ? '<img alt="登录二维码" src="data:image/png;base64,' + esc(a.qrCode) + '">'
      : (a.loggedIn ? '' : '<div class="qrwait">正在生成二维码，请稍候</div>');
    const button = a.loggedIn ? '<button type="button" class="danger"'
      + (signingOut.has(a.id || a.platform) ? ' disabled' : '') + '>退出登录并重新扫码</button>' : '';
    const action = '<div class="lc-actions" data-login-platform="' + esc(a.id || a.platform) + '">' + button + '</div>';
    const tip = a.loggedIn
      ? '<span>如需更换账号，或怀疑凭据已泄露，可退出登录：本机保存的凭据将被清除，并立即生成新的登录二维码。</span>'
      : '<span>请使用手机客户端扫描二维码，确认登录后本页将自动显示为已登录。</span>';

    return '<div class="account"><div class="who">' + tip + '</div>' + action + qr + '</div>';
  }).join('');

  box.innerHTML = html;
  $('#bili-accounts').hidden = !html;
  box.querySelectorAll('[data-login-platform]').forEach(actions => {
    const platform = actions.dataset.loginPlatform;
    const result = logoutResults.get(platform);
    if (result) actions.appendChild(result);
    const button = actions.querySelector('button');
    button?.addEventListener('click', () => logout(platform, button, actions));
  });

  // 等待扫码时轮询刷新，扫完页面自动变为已登录，不必手动刷新。
  // 人停在首页上时也要刷：登录态一变，那一页的链路灯与探针都得跟着变
  const waiting = accounts.some(a => !a.loggedIn && !a.disabledReason);
  clearTimeout(store.accountTimer);
  if (waiting && VISIBLE_TABS.includes(store.tab)) {
    store.accountTimer = setTimeout(() => {
      loadAccounts();
      if (store.tab === 'overview') refreshHome();
    }, 3000);
  }
}

async function loadAccounts() {
  const request = ++accountRequest;
  try {
    const {accounts} = await api('/login');
    if (request === accountRequest) renderAccounts(accounts);
  } catch (e) {
    // 登录信息拉取失败不影响其余状态展示，静默重试即可
  }
}

async function logout(platform, button, actions) {
  if (!await ask({title: '退出登录？', body: '退出后须重新扫码登录。重新登录前，动态推送将暂停。'})) return;
  if (signingOut.has(platform)) return;
  let result = logoutResults.get(platform);
  if (!result) {
    result = el('span', 'lc-result');
    result.setAttribute('role', 'status');
    actions.appendChild(result);
    logoutResults.set(platform, result);
  }
  button.disabled = true;
  signingOut.add(platform);
  showLinkResult(result, '正在退出登录…');
  try {
    const res = await api('/login/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform, id: platform })
    });
    const message = res.message || (res.success ? '已退出登录' : '退出登录失败');
    showLinkResult(result, message, res.success ? 'ok' : 'err');
    if (res.success) await Promise.all([loadAccounts(), refreshLinks()]);
  } catch (e) {
    showLinkResult(result, '退出登录失败：' + e.message, 'err');
  } finally {
    signingOut.delete(platform);
    const current = result.parentElement?.querySelector('button');
    if (current) current.disabled = false;
  }
}
