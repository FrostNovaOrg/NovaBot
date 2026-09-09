/**
 * 确认弹层：标题、一句后果、可选输入栏、取消／确认
 *
 * 判定在 confirm-model.js，本文件只负责把它画到屏幕上。问完回一个 Promise，
 * 调用方写成 `if (!await ask({title, body})) return;` 就能替换原生 confirm()；
 * 带 fields 时确认返回各栏的值，取消返回 null。
 *
 * Esc＝取消、关掉以后把焦点还回刚才那颗按钮：原生 confirm() 由浏览器代劳，
 * 自绘之后这两件事必须自己守，否则键盘和读屏都回不到原点。
 */

import {el} from './core.js';
import {idle, open, settle} from './confirm-model.js';

let state = idle();
let resolve = null;
let root = null;
let listening = false;

function ensure() {
  if (root) return root;
  root = el('div', 'ask');
  root.hidden = true;
  root.innerHTML = '<div class="ask-card" role="dialog" aria-modal="true" aria-labelledby="ask-title">'
    + '<h3 id="ask-title"></h3><p></p><div class="ask-fields" hidden></div><div class="ask-act">'
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
  if (ev.key !== 'Escape') return;
  ev.preventDefault();
  finish(false);
}

function paint() {
  const box = ensure();
  if (state.status !== 'open') {
    box.hidden = true;
    return;
  }
  box.hidden = false;
  box.querySelector('h3').textContent = state.title;
  const note = box.querySelector('p');
  note.textContent = state.body;
  note.hidden = !state.body;

  const yes = box.querySelector('[data-act="yes"]');
  yes.classList.toggle('ask-ok', !!state.danger);

  const host = box.querySelector('.ask-fields');
  host.innerHTML = '';
  const fields = state.fields || [];
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
