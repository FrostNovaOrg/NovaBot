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
import {$, api, esc, say} from './core.js';
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
 * 建出卡里的内容
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  section.innerHTML = `
    <p class="hint">登录态用于拉取动态与直播间信息。登录态失效时动态推送会静默停止，直播推送不受影响。</p>
    <div id="accounts"></div>
    <div class="health" id="bili-health"></div>`;
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
  box.style.display = rest.length ? '' : 'none';
  box.innerHTML = rest.length ? healthRows(rest, '') : '';
}

function renderAccounts(accounts) {
  const box = $('#accounts');
  if (!box) return;

  box.innerHTML = (accounts || []).map(a => {
    // 登录被配置关掉时（例如匿名模式），二维码永远不会来。卡头已经写明是匿名模式，
    // 这里就不再摆一个「尚未生成二维码，请稍候」——那个「稍候」是没有尽头的
    if (a.disabledReason) return '';

    const qr = a.qrCode
      ? '<img alt="登录二维码" src="data:image/png;base64,' + esc(a.qrCode) + '">'
      : (a.loggedIn ? '' : '<div class="qrwait">二维码生成中，稍候</div>');
    const action = a.loggedIn
      ? '<button type="button" data-logout="' + esc(a.platform) + '">退出登录并重新扫码</button>'
      : '';
    const tip = a.loggedIn
      ? '<span>要换个账号，或者怀疑凭据泄漏时，退出登录会清掉本机存的凭据并立刻出一张新二维码。</span>'
      : '<span>用手机客户端扫这张码即可，扫完本页会自己变成已登录。</span>';

    return '<div class="account"><div class="who">' + tip + '</div>' + qr + action + '</div>';
  }).join('');

  box.querySelectorAll('[data-logout]').forEach(btn => {
    btn.addEventListener('click', () => logout(btn.getAttribute('data-logout')));
  });

  // 等待扫码时轮询刷新，扫完页面自动变为已登录，不必手动刷新。
  // 人停在首页上时也要刷：登录态一变，那一页的链路灯与探针都得跟着变
  const waiting = (accounts || []).some(a => !a.loggedIn && !a.disabledReason);
  clearTimeout(store.accountTimer);
  if (waiting && VISIBLE_TABS.includes(store.tab)) {
    store.accountTimer = setTimeout(() => {
      loadAccounts();
      if (store.tab === 'overview') refreshHome();
    }, 3000);
  }
}

async function loadAccounts() {
  try {
    renderAccounts((await api('/login')).accounts);
  } catch (e) {
    // 登录信息拉取失败不影响其余状态展示，静默重试即可
  }
}

async function logout(platform) {
  if (!await ask({title: '确定退出登录吗？', body: '退出后需要重新扫码，期间动态推送会停。'})) return;
  say('正在退出…');
  try {
    const res = await api('/login/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform })
    });
    say(res.message || (res.success ? '已退出' : '退出失败'), res.success ? 'ok' : 'err');
    if (res.success) setTimeout(loadAccounts, 500);
  } catch (e) {
    say('退出失败：' + e.message, 'err');
  }
}
