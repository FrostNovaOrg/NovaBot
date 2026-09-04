/**
 * 危险确认弹层：标题、一句后果、取消／确认
 *
 * 判定在 confirm-model.js，本文件只负责把它画到屏幕上。问完回一个 Promise，
 * 调用方写成 `if (!await ask({title, body})) return;` 就能替换原生 confirm()。
 */

import {el} from './core.js';
import {idle, open, settle} from './confirm-model.js';

let state = idle();
let resolve = null;
let root = null;

function ensure() {
  if (root) return root;
  root = el('div', 'ask');
  root.hidden = true;
  root.innerHTML = '<div class="ask-card" role="dialog" aria-modal="true" aria-labelledby="ask-title">'
    + '<h3 id="ask-title"></h3><p></p><div class="ask-act">'
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
  document.body.appendChild(root);
  return root;
}

function paint() {
  const box = ensure();
  if (state.status !== 'open') {
    box.hidden = true;
    return;
  }
  box.hidden = false;
  box.querySelector('h3').textContent = state.title;
  box.querySelector('p').textContent = state.body;
  box.querySelector('[data-act="yes"]').focus();
}

function finish(ok) {
  const done = resolve;
  const next = settle(state, ok, accepted => {
    resolve = null;
    if (done) done(accepted);
  });
  if (next === state) return;
  state = next;
  paint();
}

/**
 * 问一句。同一时刻只弹一层：上一层还开着时先按取消收掉。
 * @param spec {title: string, body: string}
 * @return {Promise<boolean>} 确认则为 true
 */
export function ask(spec) {
  return new Promise(r => {
    if (state.status === 'open') finish(false);
    state = open(state, spec || {});
    resolve = r;
    paint();
  });
}
