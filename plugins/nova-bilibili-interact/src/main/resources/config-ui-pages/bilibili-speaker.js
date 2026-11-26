/**
 * 哔哩哔哩发言账号连接卡：扫码登录与退出登录
 *
 * 本文件随直播间互动插件走，由控制台在运行时按注册清单装载，落在连接页上成为一张卡。
 * 三个导出就是与控制台之间的全部约定：render 建卡里的内容、refresh 重取本卡数据、
 * status 拿到运行状态后渲染本卡那一块。
 *
 * 卡壳（标题、灯、「已登录 / 未登录」那句话、凭据还能用多久）由控制台按账号状态与本卡的探针画；
 * 这里只做控制台做不了的几件事：「扫码登录」按钮、摆出二维码、说明上一次为什么没登上、退出登录。
 *
 * 发言账号平时不备二维码：不点「扫码登录」就不向哔哩哔哩发请求，本卡也不轮询。
 * 只有点过之后、等人扫码的那几分钟里，才每 3 秒问一次登录状态。
 */

import {ask} from './confirm.js';
import {$, api, el, esc} from './core.js';
import {showLinkResult} from './link-feedback.js';
import {refreshLinks} from './links.js';
import {store} from './store.js';

/**
 * 本卡管的那个账号的标识，与插件登记的页标识相同
 */
const ACCOUNT = 'bilibili-speaker';

/**
 * 连接页与首页的页签名（见 store.tab）。人不在这两页上时不轮询
 */
const VISIBLE_TABS = ['bot', 'overview'];

/**
 * 点了「扫码登录」之后，等二维码出现最多等多久，毫秒
 */
const START_WAIT = 30000;

let current = null;
let saved = false;
let waitingSince = 0;
let timer = null;
let request = 0;
let busy = false;
let lastShape = '';

/**
 * 建出卡里的内容
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  section.innerHTML = `
    <p class="hint lc-intro">另一个哔哩哔哩账号，只用来在直播间发弹幕，与监听直播间用的账号分开：它被平台限制时，监听、开播通知和直播报告照常。</p>
    <section class="lc-sub" id="speaker-account">
      <h3 class="lc-h">发言账号</h3>
      <p class="hint">发弹幕走的是非官方途径，账号可能被平台限制，请只在自己有权管理的直播间使用。不登录发言账号时，本插件不向哔哩哔哩发出任何请求。</p>
      <div id="speaker-box"></div>
    </section>`;
}

/**
 * 重取本卡数据。控制台在整体载入时与切到连接页时各调一次
 */
export function refresh() {
  load();
}

/**
 * 运行状态到了：本卡的探针在不在，说明本机还存不存着这个号的凭据（登录过、没退出）
 * @param data 运行状态
 */
export function status(data) {
  const before = saved;
  saved = (data.health || []).some(h => h.card === ACCOUNT);
  if (before !== saved && current) draw(current);
}

async function load() {
  const mine = ++request;
  try {
    const {accounts} = await api('/login');
    if (mine !== request) return;
    current = (accounts || []).find(a => a.id === ACCOUNT) || null;
    draw(current);
  } catch (e) {
    // 登录信息拉取失败不影响其余状态展示，下次刷新再取
  }
}

function draw(account) {
  const box = $('#speaker-box');
  if (!box) return;
  if (!account) {
    box.innerHTML = '<p class="hint">未取到发言账号的状态。</p>';
    return;
  }

  // 有一句「为什么没登上」又没有码：那一轮扫码已经结束，不再等码、不再轮询
  if (account.notice && !account.qrCode) waitingSince = 0;
  const notice = account.notice ? '<div class="note n-warn">' + esc(account.notice) + '</div>' : '';
  let tip;
  let qr = '';
  let buttons;
  if (account.disabledReason) {
    // 停用了（例如凭据文件与监听账号的是同一个）：扫码与退出都不给，那一句理由已在上面
    waitingSince = 0;
    tip = '发言账号已停用，改好配置并重启后才能使用。';
    buttons = '';
  } else if (account.loggedIn) {
    waitingSince = 0;
    tip = '退出登录会清除本机保存的发言账号凭据，监听账号不受影响。';
    buttons = '<button type="button" class="danger" data-speaker="logout"' + (busy ? ' disabled' : '') + '>退出登录</button>';
  } else if (account.qrCode) {
    tip = '请用打算拿来发言的那个账号（不是监听用的账号）在手机客户端扫码，确认后本页自动显示为已登录。二维码约 3 分钟后过期。';
    qr = '<img alt="发言账号登录二维码" src="data:image/png;base64,' + esc(account.qrCode) + '">';
    buttons = saved ? logoutButton() : '';
  } else if (waiting()) {
    tip = '正在获取二维码，请稍候。';
    buttons = '';
    qr = '<div class="qrwait">正在获取二维码，请稍候</div>';
  } else {
    tip = saved
      ? '发言账号的登录已失效。重新扫码登录，或退出登录清除本机保存的凭据。'
      : '尚未登录发言账号。点「扫码登录」后会生成二维码，不点就不会向哔哩哔哩发出任何请求。';
    buttons = '<button type="button" data-speaker="start"' + (busy ? ' disabled' : '') + '>扫码登录</button>'
      + (saved ? logoutButton() : '');
  }

  // 只在形状变了时重画：轮询每 3 秒来一次，原样重画会把刚显示的操作结果冲掉
  const shape = JSON.stringify([account.loggedIn, account.qrCode, account.notice, account.accountId, saved,
    waiting(), busy]);
  if (shape !== lastShape || !box.querySelector('.account')) {
    lastShape = shape;
    box.innerHTML = '<div class="account"><div class="who"><span>' + esc(tip) + '</span></div>'
      + notice + '<div class="lc-actions" data-speaker-actions="1">' + buttons + '</div>' + qr + '</div>';
    box.querySelectorAll('[data-speaker]').forEach(button => {
      button.addEventListener('click', () => (button.dataset.speaker === 'start' ? start(button) : logout(button)));
    });
  }

  schedule(account);
}

function logoutButton() {
  return '<button type="button" class="danger" data-speaker="logout"' + (busy ? ' disabled' : '') + '>退出登录</button>';
}

function waiting() {
  return waitingSince > 0 && Date.now() - waitingSince < START_WAIT;
}

/**
 * 等扫码时轮询；没在等就不轮询
 */
function schedule(account) {
  clearTimeout(timer);
  timer = null;
  const pending = !account.loggedIn && (account.qrCode || waiting());
  if (pending && VISIBLE_TABS.includes(store.tab)) {
    timer = setTimeout(async () => {
      const before = JSON.stringify([current?.loggedIn, !!current?.qrCode]);
      await load();
      const after = JSON.stringify([current?.loggedIn, !!current?.qrCode]);
      // 登上了、或二维码出现／过期了：卡壳上那句也得跟着变
      if (before !== after) refreshLinks();
    }, 3000);
  }
}

function resultBox() {
  const actions = $('#speaker-box')?.querySelector('[data-speaker-actions]');
  if (!actions) return null;
  let result = actions.querySelector('.lc-result');
  if (!result) {
    result = el('span', 'lc-result');
    result.setAttribute('role', 'status');
    actions.appendChild(result);
  }
  return result;
}

async function start(button) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  const result = resultBox();
  showLinkResult(result, '正在发起扫码登录…');
  try {
    const res = await api('/login/start', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform: 'bilibili', id: ACCOUNT}),
    });
    if (res.success) waitingSince = Date.now();
    showLinkResult(result, res.message || (res.success ? '正在获取二维码' : '发起扫码登录失败'), res.success ? 'ok' : 'err');
  } catch (e) {
    showLinkResult(result, '发起扫码登录失败：' + e.message, 'err');
  } finally {
    busy = false;
    button.disabled = false;
  }
  await load();
}

async function logout(button) {
  if (!await ask({title: '退出发言账号？',
    body: '本机保存的发言账号凭据将被清除，之后要发言须重新扫码登录。监听直播间用的账号不受影响。'})) return;
  if (busy) return;
  busy = true;
  button.disabled = true;
  const result = resultBox();
  showLinkResult(result, '正在退出登录…');
  try {
    const res = await api('/login/logout', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform: 'bilibili', id: ACCOUNT}),
    });
    showLinkResult(result, res.message || (res.success ? '已退出登录' : '退出登录失败'), res.success ? 'ok' : 'err');
    if (res.success) {
      waitingSince = 0;
      saved = false;
      await Promise.all([load(), refreshLinks()]);
    }
  } catch (e) {
    showLinkResult(result, '退出登录失败：' + e.message, 'err');
  } finally {
    busy = false;
    button.disabled = false;
  }
}
