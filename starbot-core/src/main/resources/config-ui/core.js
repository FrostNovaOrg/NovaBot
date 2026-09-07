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

/**
 * 插件申报的人话词。没有供这个键时用中性兜底，界面不自带平台名。
 * @param {string} key 词表键
 * @param {string} fallback 中性兜底
 * @return {string}
 */
export function term(key, fallback) {
  return store.vocab[key] || fallback;
}

/**
 * 带名短语：词在则套进 withTerm，词缺则整句退成中性 without。
 * 括注、「打开 … 界面」这类带名短语必须走这里，不能把兜底词塞进括号。
 * 单词位仍用 {@link term}。
 * @param {string} key 词表键
 * @param {function(string): string} withTerm 词在时的带名句
 * @param {string} without 词缺时的中性整句
 * @return {string}
 */
export function phrase(key, withTerm, without) {
  const word = store.vocab[key];
  return word ? withTerm(word) : without;
}

/**
 * 设置页布尔行与登录安全卡共用的开关
 *
 * 两处各写一份的话，样式漂了只会修到看见的那一处。构造必须同一份：
 * {@code label.switch > input[type=checkbox] + span}，旁注「已启用／已关闭」。
 * @param {string} [id] 落在 input 上的 id，没有就空着
 * @param {boolean} checked 初始是否打开
 * @param {string} [ariaLabel] 读屏用的名字
 * @return {{label: HTMLElement, input: HTMLInputElement, text: HTMLElement}}
 */
export function switchControl(id, checked, ariaLabel) {
  const label = el('label', 'switch');
  const input = el('input');
  input.type = 'checkbox';
  input.checked = !!checked;
  if (id) input.id = id;
  if (ariaLabel) input.setAttribute('aria-label', ariaLabel);
  const text = el('span');
  // 旁注由 syncLabel 更新，而不是挂一个 change 监听：
  // 「把控件退回原样」那条路要能在不触发任何 change 的前提下改动它——
  // 靠 dispatchEvent 退回去的话，退回这个动作自己又会走一遍确认流程
  input.syncLabel = () => { text.textContent = input.checked ? '已启用' : '已关闭'; };
  input.syncLabel();
  label.append(input, text);
  return {label, input, text};
}


/**
 * 时:分。事件时刻是毫秒时间戳，按浏览器所在时区显示——看的人和机器往往不在同一个时区
 *
 * 摆在公共这一份里而不是各页各写一个：首页那条短条与日志页那条完整时间线画的是
 * 同一批事件，两处各写一个格式化的话，同一条事件在两页上会显示成两个时刻。
 */
export const clock = at => {
  const time = new Date(at);
  return String(time.getHours()).padStart(2, '0') + ':' + String(time.getMinutes()).padStart(2, '0');
};

/**
 * 今天是哪一天，按浏览器所在时区算
 *
 * 不用 toISOString()：那一串是 UTC 的日期，东八区的深夜与凌晨会各错一天，
 * 而「今天发生了什么」在错的那几个小时里会显示成空的。
 */
export const today = () => {
  const now = new Date();
  return now.getFullYear() + '-'
    + String(now.getMonth() + 1).padStart(2, '0') + '-'
    + String(now.getDate()).padStart(2, '0');
};

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

/**
 * 这一项改了要不要重启才生效
 *
 * 只有明确标着「即时生效」的才算即时。没标的（后端回 null，多见于运行期装进来的插件）
 * 一律按需重启：多提示一次重启的代价是白重启，而反过来说成已生效却没生效，
 * 使用者会以为功能坏了，且没有任何东西会纠正他。
 * @param name 配置项名
 * @return {boolean} 需要重启时为 true
 */
export function needsRestart(name) {
  return store.effects[name] !== 'IMMEDIATE';
}

/**
 * 底部那条改动条上写什么
 *
 * 它答的是「有多少改动还没生效」，因此有两段各自成立的话：
 * 改了还没保存的那 N 处（其中有几处保存了也还要重启），以及已经保存下来、
 * 正等着重启的那些。后者不随保存清零——它要一直挂到重启为止，那正是它的意思。
 * @param n 改过还没保存的项数
 * @return {string} 改动条文案
 */
function changeText(n) {
  const pending = store.restartPending.length;

  if (!n) return pending ? pending + ' 处改动需重启生效' : '';

  // 需重启的项数只在设置页算得出：推送页的改动不是配置项，没有生效时机可言
  const m = saveTarget() === 'values' ? Object.keys(store.dirty).filter(needsRestart).length : 0;
  return n + ' 处改动' + (m ? ' · 其中 ' + m + ' 处需重启生效' : '');
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
  $('#change-count').textContent = changeText(n);
}
