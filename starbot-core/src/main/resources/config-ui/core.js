/**
 * 公共基础：请求、DOM helper、共享状态与底部保存栏
 * 其余模块都依赖它，因此必须最先加载。
 */

const api = (p, o) => fetch('/config/api' + p, o).then(r => r.json());
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
