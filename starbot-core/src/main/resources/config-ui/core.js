/**
 * 公共基础：请求、DOM helper、共享状态与底部保存栏
 * 其余模块都依赖它，因此必须最先加载。
 */

import {store} from './store.js';

// 登录后由 /auth/state 下发。未启用口令登录时一直是空串，后端也不会校验

/**
 * 写请求必须带上 CSRF 令牌。它只能由本站脚本读出并放进请求头——
 * 跨站页面能让浏览器自动附上 Cookie，却加不了自定义头，因此这个头就是「请求来自本站」的证明。
 *
 * 会话过期时后端返回 401，此时整页重载会落到登录页，比在每个调用点各自处理要可靠。
 */
export const api = (p, o) => {
  const opt = Object.assign({}, o);
  if (opt.method && opt.method.toUpperCase() !== 'GET' && store.csrfToken) {
    opt.headers = Object.assign({}, opt.headers, {'X-CSRF-Token': store.csrfToken});
  }
  return fetch('/config/api' + p, opt).then(r => {
    if (r.status === 401) {
      location.reload();
      return new Promise(() => {});
    }
    return r.json();
  });
};
export const $ = s => document.querySelector(s);
export const el = (t, c) => { const e = document.createElement(t); if (c) e.className = c; return e; };
// 主播昵称等内容来自各平台的接口，属于外部数据，拼进 innerHTML 前必须转义
export const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));


export function say(text, kind) {
  const s = $('#status-text');
  s.textContent = text || '';
  s.className = 'status' + (kind ? ' ' + kind : '');
  if (text && kind === 'ok') setTimeout(() => { if (s.textContent === text) say(''); }, 4000);
}

// 底部的保存按钮只服务于有「草稿态」的两个页签。机器人连接参数另带独立的保存入口——
// 它改的是一批关联字段，混进同一个按钮容易误触
export function saveTarget() {
  if (store.tab === 'settings') return 'values';
  if (store.tab === 'push') return 'push';
  return null;
}

/**
 * 当前页有几处改过还没保存
 *
 * 两页各有各的草稿形态，因此各算各的，不共用一个计数器：
 * 设置页的草稿是「哪几个字段改了」，逐项记在 store.dirty 上；
 * 推送页的改动直接落在 store.pushData 上，没有逐项的记账，
 * 于是拿它与上一次载入时的快照按主播逐条比——两边都是现算，
 * 谁也没有一个「改动次数」的累加器。累加器的毛病是改回原样它也照加，
 * 屏幕上会显示「1 处改动」而实际什么都没变。
 * @return {number} 改过未保存的项数
 */
export function changeCount() {
  const target = saveTarget();
  if (target === 'values') return Object.keys(store.dirty).length;
  if (target === 'push') return pushChangeCount();
  return 0;
}

/**
 * 推送配置改了几条：按主播逐条比，不是「一整份变了没有」
 *
 * 整份比只能得出 0 或 1，而屏幕上写的是「N 处改动」——那个 1 会被读成「只改了一处」。
 */
function pushChangeCount() {
  const byKey = list => {
    const map = new Map();
    for (const user of list || []) {
      map.set(user.platform + ':' + user.uid, JSON.stringify(user, dropDisplayOnly));
    }
    return map;
  };

  let saved;
  try {
    saved = byKey(JSON.parse(store.pushSaved || '[]'));
  } catch (e) {
    // 快照都解析不了时不猜数：这一页此刻处于「文件内容不合法」的状态，界面上另有说明
    return 0;
  }

  const now = byKey(store.pushData);
  let n = 0;
  now.forEach((value, key) => { if (saved.get(key) !== value) n++; });
  saved.forEach((value, key) => { if (!now.has(key)) n++; });
  return n;
}

// 下划线开头的字段仅供界面展示（昵称、头像等），既不写进配置文件，也不该算作改动
export function dropDisplayOnly(key, value) {
  return key.startsWith('_') ? undefined : value;
}

export function markDirty() {
  const target = saveTarget();
  const n = changeCount();
  $('#save').style.display = target ? '' : 'none';
  $('#discard').style.display = target ? '' : 'none';
  // 推送页的保存按钮一直可按：「添加主播」之外还有一批改动落在 store.pushData 上，
  // 而按 N 禁用意味着算漏一处就等于把保存这条路堵死
  $('#save').disabled = !target || (target === 'values' && n === 0);
  $('#discard').disabled = n === 0;
  $('#change-count').textContent = n ? n + ' 处改动' : '';
}
