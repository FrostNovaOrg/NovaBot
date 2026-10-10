/**
 * 确认弹层：标题、一句后果、可选输入栏、取消／确认；另有只读长文一形
 *
 * 判定在 confirm-model.js，本文件只负责把它画到屏幕上。问完回一个 Promise，
 * 调用方写成 `if (!await ask({title, body})) return;` 就能替换原生 confirm()；
 * 带 fields 时确认返回各栏的值，取消返回 null。
 *
 * 协议这类要通读的长文走 showDoc()：同一只弹层换成可滚全文＋一颗「关闭」。
 * Esc＝取消、Tab 圈在弹层里、关掉以后把焦点还回刚才那颗按钮：原生 confirm() 由浏览器代劳，
 * 自绘之后这几件事必须自己守，否则键盘和读屏都回不到原点。
 */

import {el} from './core.js';
import {idle, open, settle} from './confirm-model.js';

let state = idle();
let resolve = null;
let root = null;
let listening = false;
// 长文那一形的正文与取正文的状态。挂在模块上而不进 confirm-model：
// 那边是纯函数判定，为一种画法扩它的状态形不值当
let docMode = false;
let docText = '';
let docLoading = false;

function ensure() {
  if (root) return root;
  root = el('div', 'ask');
  root.hidden = true;
  root.innerHTML = '<div class="ask-card" role="dialog" aria-modal="true" aria-labelledby="ask-title">'
    + '<h3 id="ask-title"></h3><p></p><div class="ask-doc" hidden></div>'
    + '<div class="ask-fields" hidden></div><div class="ask-act">'
    + '<button type="button" class="nv-btn2" data-act="no">取消</button>'
    + '<button type="button" class="nv-btn ask-ok" data-act="yes">确认</button>'
    + '</div></div>';
  root.addEventListener('click', ev => {
    const act = ev.target.closest('[data-act]');
    if (act) {
      finish(act.getAttribute('data-act') === 'yes');
      return;
    }
    if (ev.target === root) finish(false);
  });
  if (!listening) {
    document.addEventListener('keydown', onKey);
    listening = true;
  }
  document.body.appendChild(root);
  return root;
}

function onKey(ev) {
  if (state.status !== 'open') return;
  if (ev.key === 'Escape') {
    ev.preventDefault();
    finish(false);
    return;
  }
  if (ev.key === 'Tab') keepTabInside(root.querySelector('.ask-card'), ev);
}

/**
 * box 里按文档序能吃 Tab 的控件：藏起来的（长文那一形的取消钮）与禁用的不算
 */
function tabbablesIn(box) {
  const out = [];
  const walk = node => {
    if (node.hidden || node.disabled) return;
    const tag = String(node.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea'
      || (tag === 'a' && node.getAttribute('href') != null)) out.push(node);
    for (const child of Array.from(node.children || [])) walk(child);
  };
  for (const child of Array.from(box.children || [])) walk(child);
  return out;
}

/**
 * 把这一下 Tab 圈在 box 里：最后一个控件上再按 Tab 回到第一个，第一个上按
 * Shift+Tab 回到最后一个。aria-modal 只拦得住读屏，键盘上的 Tab 浏览器照旧
 * 放行，不圈的话焦点会走到弹层后面的页面上。
 * 独立成可导出的小函数：推送页的抽屉要的是同一套圈法，不该各写一份。
 * @param box 圈的范围，即对话框或抽屉的根
 * @param ev keydown 事件
 */
export function keepTabInside(box, ev) {
  const items = tabbablesIn(box);
  if (items.length === 0) return;
  ev.preventDefault();
  const at = items.indexOf(document.activeElement);
  let next;
  // 焦点在圈外时照浏览器原生的走法：Tab 进到第一个，Shift+Tab 进到最后一个
  if (at < 0) next = ev.shiftKey ? items[items.length - 1] : items[0];
  else if (ev.shiftKey) next = at > 0 ? items[at - 1] : items[items.length - 1];
  else next = at + 1 < items.length ? items[at + 1] : items[0];
  next.focus();
}

function paint() {
  const box = ensure();
  if (state.status !== 'open') {
    box.hidden = true;
    docMode = false;
    return;
  }
  box.hidden = false;
  box.querySelector('h3').textContent = state.title;
  const note = box.querySelector('p');
  note.textContent = state.body;
  note.hidden = !state.body || docMode;

  const doc = box.querySelector('.ask-doc');
  if (doc) {
    doc.hidden = !docMode;
    doc.textContent = docMode ? (docLoading ? '正在取…' : docText) : '';
  }
  const card = box.querySelector('.ask-card');
  if (card) card.classList.toggle('ask-card-doc', docMode);

  const yes = box.querySelector('[data-act="yes"]');
  yes.classList.toggle('ask-ok', !!state.danger && !docMode);
  yes.textContent = docMode ? '关闭' : '确认';
  const no = box.querySelector('[data-act="no"]');
  if (no) {
    no.hidden = docMode;
    if (!docMode) no.textContent = '取消';
  }

  const host = box.querySelector('.ask-fields');
  host.innerHTML = '';
  const fields = docMode ? [] : (state.fields || []);
  host.hidden = fields.length === 0;
  fields.forEach((spec, i) => {
    const item = spec || {};
    const wrap = el('div', 'ask-fld');
    const id = item.id || ('ask-f-' + i);
    if (item.label) {
      const title = el('label');
      title.textContent = item.label;
      title.setAttribute('for', id);
      wrap.appendChild(title);
    }
    const input = el('input');
    input.type = item.type || 'text';
    input.id = id;
    input.value = item.value == null ? '' : String(item.value);
    if (item.maxlength != null && item.maxlength !== '') input.maxLength = Number(item.maxlength);
    input.autocomplete = input.type === 'password' ? (id === 'pwd-current' ? 'current-password' : 'new-password') : 'off';
    wrap.appendChild(input);
    host.appendChild(wrap);
  });

  const first = host.querySelector('input');
  if (first) first.focus();
  else yes.focus();
}

function readFields() {
  const out = {};
  if (!root) return out;
  root.querySelectorAll('.ask-fields input').forEach(input => {
    out[input.id] = input.value;
  });
  return out;
}

function restoreFocus(trigger) {
  if (trigger && typeof trigger.focus === 'function') {
    trigger.focus();
  }
}

function finish(ok) {
  const hasFields = (state.fields || []).length > 0;
  const payload = !ok ? (hasFields ? null : false) : (hasFields ? readFields() : true);
  const done = resolve;
  const next = settle(state, ok, () => {
    resolve = null;
    if (done) done(payload);
  });
  if (next === state) return;
  const trigger = next.trigger;
  state = next;
  paint();
  restoreFocus(trigger);
}

/**
 * 问一句。同一时刻只弹一层：上一层还开着时先按取消收掉。
 * 带 fields 时确认返回各栏 id→值，取消返回 null；不带则仍是 true／false。
 * @param spec {title: string, body: string, fields?: Array, danger?: boolean}
 * @return {Promise<boolean|object|null>}
 */
export function ask(spec) {
  return new Promise(r => {
    if (state.status === 'open') finish(false);
    docMode = false;
    const wanted = spec || {};
    state = open(state, {
      title: wanted.title,
      body: wanted.body,
      trigger: document.activeElement,
      fields: wanted.fields,
      danger: wanted.danger,
    });
    resolve = r;
    paint();
  });
}

/**
 * 只读长文。同一只弹层换成可滚全文＋一颗「关闭」，Esc／遮罩／关闭都收，
 * 收完焦点回触发那颗按钮。
 *
 * 正文可以现取（load）：取不到就在弹层里说明，不另开一层报错——
 * 长文弹层是来读东西的，读不到也得在同一个地方说清楚。
 * @param spec {title: string, text?: string, load?: () => Promise<{success, text, message?}>, trigger?: Element}
 * @return {Promise<void>}
 */
export function showDoc(spec) {
  return new Promise(r => {
    if (state.status === 'open') finish(false);
    const wanted = spec || {};
    docMode = true;
    docText = wanted.text || '';
    docLoading = typeof wanted.load === 'function' && !wanted.text;
    state = open(state, {
      title: wanted.title,
      body: '',
      trigger: wanted.trigger || document.activeElement,
      fields: [],
      danger: false,
    });
    resolve = r;
    paint();
    if (typeof wanted.load === 'function') {
      Promise.resolve()
        .then(() => wanted.load())
        .then(res => {
          if (!docMode || state.status !== 'open') return;
          if (res && res.text) docText = res.text;
          else if (res && !res.success) docText = res.message || '读不到正文';
          else docText = '';
          docLoading = false;
          paint();
        })
        .catch(e => {
          if (!docMode || state.status !== 'open') return;
          docText = '读不到正文：' + (e && e.message ? e.message : String(e));
          docLoading = false;
          paint();
        });
    }
  });
}
