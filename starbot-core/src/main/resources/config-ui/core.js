/**
 * 公共基础：请求、DOM helper、共享状态与底部保存栏
 * 其余模块都依赖它，因此必须最先加载。
 */

// 登录后由 /auth/state 下发。未启用口令登录时一直是空串，后端也不会校验
let csrfToken = '';

/**
 * 写请求必须带上 CSRF 令牌。它只能由本站脚本读出并放进请求头——
 * 跨站页面能让浏览器自动附上 Cookie，却加不了自定义头，因此这个头就是「请求来自本站」的证明。
 *
 * 会话过期时后端返回 401，此时整页重载会落到登录页，比在每个调用点各自处理要可靠。
 */
const api = (p, o) => {
  const opt = Object.assign({}, o);
  if (opt.method && opt.method.toUpperCase() !== 'GET' && csrfToken) {
    opt.headers = Object.assign({}, opt.headers, {'X-CSRF-Token': csrfToken});
  }
  return fetch('/config/api' + p, opt).then(r => {
    if (r.status === 401) {
      location.reload();
      return new Promise(() => {});
    }
    return r.json();
  });
};
const $ = s => document.querySelector(s);
const el = (t, c) => { const e = document.createElement(t); if (c) e.className = c; return e; };
// 主播昵称等内容来自哔哩哔哩接口，属于外部数据，拼进 innerHTML 前必须转义
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

let schema = [], values = {}, dirty = {}, tab = 'overview';

function say(text, kind) {
  const s = $('#status-text');
  s.textContent = text || '';
  s.className = 'status' + (kind ? ' ' + kind : '');
  if (text && kind === 'ok') setTimeout(() => { if (s.textContent === text) say(''); }, 4000);
}

// 底部的保存按钮只服务于有「草稿态」的两个页签。原始配置文件与机器人连接参数
// 各自带独立的保存入口——它们改的是整份文件或一批关联字段，混进同一个按钮容易误触
function saveTarget() {
  if (tab === 'settings') return 'values';
  if (tab === 'push') return 'push';
  return null;
}

function markDirty() {
  const target = saveTarget();
  const n = Object.keys(dirty).length;
  $('#save').style.display = target ? '' : 'none';
  $('#save').disabled = !target || (target === 'values' && n === 0);
  if (target === 'values') say(n ? n + ' 项待保存' : '');
}
