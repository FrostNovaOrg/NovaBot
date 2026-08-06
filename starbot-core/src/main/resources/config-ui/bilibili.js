/**
 * 哔哩哔哩页：账号扫码登录与登出
 */

import {$, api, esc, say} from './core.js';
import {refreshWizardState} from './overview.js';
import {store} from './store.js';

function renderAccounts(accounts) {
  const box = $('#accounts');
  box.innerHTML = (accounts || []).map(a => {
    const who = a.loggedIn
      ? '<b>' + esc(a.displayName) + '　已登录</b><span>账号 ' + esc(a.accountId || '未知') + '</span>'
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
  // 二维码在「哔哩哔哩」页与总览页的向导里各有一处，两处都要跟着刷新
  const waiting = (accounts || []).some(a => !a.loggedIn);
  clearTimeout(store.accountTimer);
  if (waiting && (store.tab === 'bilibili' || store.tab === 'overview')) {
    store.accountTimer = setTimeout(() => {
      loadAccounts();
      if (store.tab === 'overview') refreshWizardState();
    }, 3000);
  }
}

export async function loadAccounts() {
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

// 配错了群号、Token、或机器人不在群里，表现全都是「什么都不发生」。
