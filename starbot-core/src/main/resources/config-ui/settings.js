/**
 * 设置页：配置项表单、备份、保存与校验提示
 */

import {$, api, el, esc, markDirty, saveTarget, say} from './core.js';
import {load} from './main.js';
import {serializePush} from './push.js';
import {store} from './store.js';

// 配置项有数十项，但真正决定系统能否跑起来的只有寥寥几项。
// 默认只显示必填与常用，高级项收起来——问题不在项多，而在没有区分「必须懂」与「可以不管」
export function renderGeneral() {
  const box = $('#groups');
  box.innerHTML = '';

  const showAdvanced = $('#show-advanced').checked;
  const visible = f => showAdvanced || f.level !== 'ADVANCED';

  let shown = 0;
  let hidden = 0;

  for (const g of store.schema) {
    const fields = g.fields.filter(visible);
    hidden += g.fields.length - fields.length;
    if (!fields.length) continue;
    shown += fields.length;

    const group = el('div', 'group');
    const title = el('h2');
    title.textContent = g.title;
    group.appendChild(title);

    for (const f of fields) {
      const row = el('div', 'field');
      row.dataset.name = f.name;

      const meta = el('div', 'meta');
      const code = el('code');
      code.textContent = f.name;
      meta.appendChild(code);

      if (f.description) {
        const d = el('div', 'desc');
        d.textContent = f.description;
        meta.appendChild(d);
      }
      if (f.defaultValue !== null && f.defaultValue !== undefined && f.defaultValue !== '') {
        const d = el('div', 'dflt');
        d.textContent = '默认值：' + f.defaultValue;
        meta.appendChild(d);
      }
      // 配置键改过名，这一项在文件里写的还是旧位置。值显示的就是它，但得说清是从哪一行来的——
      // 否则使用者在文件里按现行名字找不到这一行，会以为界面显示错了
      if (store.legacy[f.name]) {
        const l = el('div', 'legacy');
        l.textContent = '该项按旧位置 ' + store.legacy[f.name] + ' 生效，建议迁到新位置：在此保存一次即可，旧位置的内容不会被改动';
        meta.appendChild(l);
      }
      row.appendChild(meta);

      const cell = el('div');
      const saved = store.values[f.name] !== undefined ? store.values[f.name]
        : (f.defaultValue !== null && f.defaultValue !== undefined ? String(f.defaultValue) : '');
      // 切换显示范围会整体重绘，此时未保存的改动要接着显示出来，否则看起来像被悄悄还原了
      const current = store.dirty[f.name] !== undefined ? store.dirty[f.name] : saved;

      let input;
      if (f.widget === 'boolean') {
        const label = el('label', 'switch');
        input = el('input');
        input.type = 'checkbox';
        input.checked = String(current) === 'true';
        const txt = el('span');
        txt.textContent = input.checked ? '已启用' : '已关闭';
        input.addEventListener('change', () => { txt.textContent = input.checked ? '已启用' : '已关闭'; });
        label.append(input, txt);
        cell.appendChild(label);
      } else if (f.widget === 'complex') {
        // 元素为对象的列表无法用简单控件表达，此处只读展示并引导到原始文件编辑器
        const note = el('div', 'readonly');
        note.textContent = '结构较复杂，请展开页面底部的「高级：直接编辑配置文件」修改';
        cell.appendChild(note);
        row.appendChild(cell);
        group.appendChild(row);
        continue;
      } else if (f.widget === 'list') {
        input = el('textarea');
        input.value = current;
        input.placeholder = '每行一项';
        cell.appendChild(input);
      } else if (f.sensitive) {
        // 口令、令牌与密钥。后端给的是占位值而非真值，这里只负责别把它摆在画面里——
        // 面板可能正开在直播画面上，而二次验证密钥一旦泄漏就永久失效且当事人不会察觉
        const wrap = el('div', 'secret');
        input = el('input');
        input.type = 'password';
        input.value = current;
        input.autocomplete = 'off';
        const eye = el('button');
        eye.type = 'button';
        eye.textContent = '显示';
        eye.addEventListener('click', () => {
          input.type = input.type === 'password' ? 'text' : 'password';
          eye.textContent = input.type === 'password' ? '显示' : '隐藏';
        });
        wrap.append(input, eye);
        cell.appendChild(wrap);
      } else {
        input = el('input');
        input.type = (f.widget === 'integer' || f.widget === 'number') ? 'number' : 'text';
        input.value = current;
        cell.appendChild(input);
      }

      const read = () => f.widget === 'boolean' ? String(input.checked) : input.value;
      // 基准是已保存的值，而非当前显示值——后者可能是尚未保存的改动
      const original = String(saved);

      if (store.dirty[f.name] !== undefined) {
        row.classList.add('changed');
      }

      const onChange = () => {
        const now = read();
        if (now === original) { delete store.dirty[f.name]; row.classList.remove('changed'); }
        else { store.dirty[f.name] = now; row.classList.add('changed'); }
        markDirty();
      };
      input.addEventListener('input', onChange);
      input.addEventListener('change', onChange);

      row.appendChild(cell);
      group.appendChild(row);
    }

    box.appendChild(group);
  }

  $('#level-hint').textContent = hidden
    ? '共 ' + shown + ' 项，另有 ' + hidden + ' 项高级选项已折叠'
    : '共 ' + shown + ' 项';
}

/**
 * 设置页底部那行配置文件路径
 *
 * 控制台不再提供配置文件编辑，这一行是「要直接改文件的话去哪儿改」的唯一答案，
 * 因此路径必须来自服务端对配置文件的实际定位，界面不自己拼一个。
 * @param path 配置文件的绝对路径，来自 /status
 */
export function renderConfigPath(path) {
  $('#cfg-path').textContent = path || '（未能确定）';
  $('#cfg-copy').disabled = !path;
}

/**
 * 复制路径
 *
 * 剪贴板接口只在安全上下文（https 或 localhost）里存在。面板常经内网 http 访问，
 * 那里 navigator.clipboard 直接是 undefined——不兜底的话，点下去毫无反应，
 * 而使用者无从知道是没复制上还是复制了。
 */
export async function copyConfigPath() {
  const path = $('#cfg-path').textContent;
  try {
    await navigator.clipboard.writeText(path);
    say('已复制路径', 'ok');
  } catch (e) {
    say('这个浏览器不让脚本写剪贴板，请手动复制：' + path, 'err');
  }
}

// 校验不通过时逐条列出问题：只说「保存失败」而不说哪里错，使用者无从下手
function showIssues(issues) {
  const box = $('#issues');
  if (!issues || !issues.length) {
    box.style.display = 'none';
    box.innerHTML = '';
    return;
  }
  box.style.display = 'block';
  box.innerHTML = '<b>请先修正以下问题：</b><ul>'
    + issues.map(i => '<li>' + esc(i) + '</li>').join('') + '</ul>';
}

export async function save() {
  const target = saveTarget();
  if (!target) return;

  $('#save').disabled = true;
  showIssues(null);
  say('保存中…');

  try {
    let res;
    if (target === 'values') {
      res = await api('/values', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(store.dirty)
      });
      if (res.success) { store.dirty = {}; document.querySelectorAll('.field.changed').forEach(e => e.classList.remove('changed')); }
    } else {
      res = await api('/datasource', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: serializePush() })
      });
    }

    showIssues(res.issues);
    say(res.message || (res.success ? '已保存' : '保存失败'), res.success ? 'ok' : 'err');
    if (res.success && target !== 'values') await load();
  } catch (e) {
    say('保存失败：' + e.message, 'err');
  }

  markDirty();
}

/**
 * 丢掉改过还没保存的内容，回到上一次保存时的样子
 *
 * 走的是整体重载而不是「把改动逐项撤回」：撤回要为每种草稿态各写一套还原逻辑，
 * 而每加一种草稿态就多一处会漏掉的地方；重载则是把服务端的现值重新取一遍，
 * 漏不掉任何一处。代价是一次往返，放弃改动本就不是高频动作。
 */
export async function discard() {
  if (!confirm('放弃这些改动？改过还没保存的内容会全部回到上一次保存时的样子。')) return;
  // 草稿态由 load 自己清。在这里先清一遍的话，重载失败时屏幕上还留着改过的值，
  // 而计数已经归零——看起来像「改动被保存了」
  await load();
  say('已放弃全部改动');
}
