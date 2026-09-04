/**
 * 哔哩哔哩页：账号扫码登录与登出、监听中的主播一览
 *
 * 本文件随插件走，由控制台在运行时按注册清单装载。三个导出就是与控制台之间的全部约定：
 * render 建页面、refresh 重取本页数据、status 拿到运行状态后渲染本页那几块。
 * 控制台不认识这里的任何一个接口路径。
 */

import {$, api, el, esc, say} from './core.js';
import {healthRows, refreshHome} from './overview.js';
import {store} from './store.js';

const PAGE_ID = 'bilibili';

/**
 * 建出页面内容
 * @param section 控制台按页签标识建好的空容器
 */
export function render(section) {
  section.innerHTML = `
    <p class="hint">哔哩哔哩登录态用于拉取动态与直播间信息。登录态失效时推送会静默停止，本页可随时查看与重新扫码。</p>
    <div class="health" id="bili-health"></div>
    <div id="accounts"></div>
    <p class="hint" style="margin:20px 0 10px">监听中的主播与直播间</p>
    <table id="users"><thead><tr><th>UID</th><th>昵称</th><th>直播间</th><th>平台</th><th>推送目标</th><th>状态</th></tr></thead><tbody></tbody></table>`;
}

/**
 * 重取本页数据。控制台在整体载入时与切到本页时各调一次
 */
export function refresh() {
  loadAccounts();
}

/**
 * 渲染运行状态里属于本页的那两块
 *
 * 控制台把 /status 的原样数据交过来，由本页自己挑要用的部分——它不知道这一页上有哪些元素。
 * 反过来写成「控制台往 #bili-health 里塞东西」的话，这个 id 就成了一条谁都看不见的约定，
 * 插件改个 id 就静默地什么都不显示了。
 * @param data 运行状态
 */
export function status(data) {
  const health = data.health || [];
  $('#bili-health').innerHTML = healthRows(health.filter(h => h.scope === 'PLATFORM'),
    '未找到哔哩哔哩模块的健康探针');

  const body = $('#users tbody');
  body.innerHTML = '';

  if (!data.users || !data.users.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">尚未配置任何主播，请在「推送规则」中添加</td></tr>';
    return;
  }

  for (const u of data.users) {
    const tr = el('tr');
    tr.innerHTML = [u.uid, u.uname || '—', u.roomId || '—', u.platform,
      u.targets, u.enabled === false ? '已停用' : '正常']
      .map(v => '<td>' + esc(v) + '</td>').join('');
    body.appendChild(tr);
  }
}

function renderAccounts(accounts) {
  const box = $('#accounts');
  box.innerHTML = (accounts || []).map(a => {
    // 登录被配置关掉时（例如匿名模式），二维码永远不会来。这里要说明白，
    // 否则界面上只剩一句「尚未生成二维码，请稍候」，而那个「稍候」是没有尽头的
    const who = a.loggedIn
      ? '<b>' + esc(a.displayName) + '　已登录</b><span>账号 ' + esc(a.accountId || '未知') + '</span>'
      : a.disabledReason
        ? '<b>' + esc(a.displayName) + '　未登录（已按配置停用登录）</b><span>' + esc(a.disabledReason) + '</span>'
        : '<b>' + esc(a.displayName) + '　未登录</b><span>'
          + (a.qrCode ? '请使用手机客户端扫描右侧二维码' : '尚未生成二维码，请稍候') + '</span>';
    const qr = a.qrCode ? '<img alt="登录二维码" src="data:image/png;base64,' + esc(a.qrCode) + '">' : '';
    const action = a.loggedIn
      ? '<button type="button" data-logout="' + esc(a.platform) + '">退出登录</button>'
      : '';
    return '<div class="account"><div class="who">' + who + '</div>' + qr + action + '</div>';
  }).join('');

  box.querySelectorAll('[data-logout]').forEach(btn => {
    btn.addEventListener('click', () => logout(btn.getAttribute('data-logout')));
  });

  // 等待扫码时轮询刷新，扫完页面自动变为已登录，不必手动刷新。
  // 人停在首页上时也要刷：登录态一变，那一页的链路灯与探针都得跟着变
  const waiting = (accounts || []).some(a => !a.loggedIn && !a.disabledReason);
  clearTimeout(store.accountTimer);
  if (waiting && (store.tab === PAGE_ID || store.tab === 'overview')) {
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
  if (!confirm('确定退出 ' + platform + ' 的登录吗？退出后需重新扫码。')) return;
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
