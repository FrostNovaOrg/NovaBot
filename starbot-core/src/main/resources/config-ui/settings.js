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

// ============ 机器人连接表单 ============
// 同一份表单在「首次配置」向导与「机器人」页签下各出现一次。

// 每次保存都会留一份带时间戳的备份，改坏了可以直接滚回去
export function renderBackups(names) {
  const box = $('#backups');
  if (!names || !names.length) {
    box.innerHTML = '<span>暂无历史备份，每次保存会自动生成一份</span>';
    return;
  }

  box.innerHTML = '<span>历史备份</span>'
    + '<select id="backup-pick">'
    + names.map(n => '<option value="' + esc(n) + '">' + esc(n) + '</option>').join('')
    + '</select><button id="backup-restore" type="button">回滚到此版本</button>';

  $('#backup-restore').addEventListener('click', async () => {
    const name = $('#backup-pick').value;
    if (!confirm('确定回滚至 ' + name + ' 吗？当前内容会先备份一份。')) return;
    say('回滚中…');
    try {
      const res = await api('/backups/restore', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      say(res.message || (res.success ? '已回滚' : '回滚失败'), res.success ? 'ok' : 'err');
      if (res.success) await load();
    } catch (e) {
      say('回滚失败：' + e.message, 'err');
    }
  });
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
        body: JSON.stringify({ content: store.advancedMode ? $('#datasource').value : serializePush() })
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

// 整份 YAML 的保存单独成一个按钮：它覆盖的是文件全文，
// 与表单里逐项改动不是一回事，混用同一个按钮迟早会误把旧内容盖回去
export async function saveRaw() {
  $('#raw-save').disabled = true;
  showIssues(null);
  say('保存中…');

  try {
    const res = await api('/raw', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: $('#rawyml').value })
    });

    showIssues(res.issues);
    say(res.message || (res.success ? '已保存' : '保存失败'), res.success ? 'ok' : 'err');
    if (res.success) {
      const open = $('#rawbox').open;
      await load();
      $('#rawbox').open = open;   // 重新载入会重建内容，但展开状态是使用者的选择
    }
  } catch (e) {
    say('保存失败：' + e.message, 'err');
  }

  $('#raw-save').disabled = false;
}
