/**
 * 推送规则页：主播卡片、推送目标、消息模板与版式选项
 */

import {$, api, el, esc, markDirty, say} from './core.js';
import {store} from './store.js';

// 插件新增处理器时勾选项自动出现，前端不硬编码任何类名。
const openTemplates = new Set();   // 已展开的模板编辑器，卡片重绘后据此恢复展开状态

export function renderStreamers() {
  const box = $('#streamers');

  if (!store.pushData.length) {
    box.innerHTML = '<div class="empty">尚未配置任何主播。在上方输入 uid 或粘贴个人空间链接来添加。</div>';
    return;
  }

  box.innerHTML = '';
  store.pushData.forEach((user, ui) => box.appendChild(streamerCard(user, ui)));
}

function streamerCard(user, ui) {
  const card = el('div', 'streamer');

  const head = el('div', 'head');
  head.innerHTML =
    (user._face ? '<img referrerpolicy="no-referrer" src="' + esc(user._face) + '" alt="">' : '<img alt="">')
    + '<div><span class="name">' + esc(user._uname || ('uid ' + user.uid)) + '</span>'
    + '<span class="id">uid ' + esc(user.uid) + (user._roomId ? ' · 直播间 ' + esc(user._roomId) : '') + '</span></div>'
    + '<label class="switch" style="margin-left:auto"><input type="checkbox"' + (user.enabled === false ? '' : ' checked')
    + ' data-enable="' + ui + '"><span>启用</span></label>'
    + '<button class="rm" type="button" data-del-user="' + ui + '">删除</button>';
  card.appendChild(head);

  const body = el('div', 'body');
  (user.targets || []).forEach((target, ti) => body.appendChild(targetRow(user, ui, target, ti)));

  const add = el('button', 'add-target');
  add.type = 'button';
  add.textContent = '+ 添加推送目标';
  add.addEventListener('click', () => {
    user.targets = user.targets || [];
    user.targets.push({ platform: store.senderList[0] || '', type: 1, num: null, enabled: true, messages: [] });
    renderStreamers();
    markDirty();
  });
  body.appendChild(add);

  card.appendChild(body);
  return card;
}

function targetRow(user, ui, target, ti) {
  const row = el('div', 'target');

  // 机器人适配器没起来时 senderList 是空的，但配置里的推送平台仍然在。
  // 此时若只给一个空选项，界面就在说「这条规则没有配机器人」——而实际上配了，
  // 保存也不会把它写没（serializePush 用的是 pushData 而不是 DOM）。
  // 与其显示一句与事实不符的话，不如把配置里的值原样列出并标注它当前不可用
  const known = store.senderList.length ? store.senderList : (target.platform ? [target.platform] : []);
  const platformOpts = known.length
    ? known.map(s => '<option value="' + esc(s) + '"' + (s === target.platform ? ' selected' : '') + '>'
        + esc(s) + (store.senderList.length ? '' : '（当前不可用）') + '</option>').join('')
    : '<option value="">（尚无可用的机器人）</option>';

  // 取值须与 PushTargetType 的 code 一致：GROUP(1)、FRIEND(0)
  row.innerHTML = '<select data-t-platform="' + ui + ',' + ti + '">' + platformOpts + '</select>'
    + '<select data-t-type="' + ui + ',' + ti + '">'
    + '<option value="1"' + (Number(target.type) === 1 ? ' selected' : '') + '>群聊</option>'
    + '<option value="0"' + (Number(target.type) === 0 ? ' selected' : '') + '>私聊</option></select>'
    + '<input data-t-num="' + ui + ',' + ti + '" inputmode="numeric" placeholder="群号 / QQ 号" value="'
    + esc(target.num == null ? '' : target.num) + '">'
    + '<button class="rm" type="button" data-del-target="' + ui + ',' + ti + '">移除</button>';

  const events = el('div', 'events');
  events.style.cssText = 'flex-basis:100%;margin:8px 0 0';
  const chosen = new Set((target.messages || []).map(m => m.handler));

  store.handlerList.forEach(h => {
    const label = el('label');
    label.innerHTML = '<input type="checkbox" data-h="' + ui + ',' + ti + ',' + esc(h.className) + '"'
      + (chosen.has(h.className) ? ' checked' : '') + '>'
      + '<span>' + esc(h.displayName) + '</span>'
      + (h.description ? '<small>' + esc(h.description) + '</small>' : '')
      + (chosen.has(h.className)
        ? '<button class="tpl-toggle" type="button" data-tpl="' + ui + ',' + ti + ',' + esc(h.className) + '">编辑模板</button>'
        : '');
    events.appendChild(label);
  });

  if (!store.handlerList.length) {
    events.innerHTML = '<small>尚未加载到任何推送处理器，请确认对应插件已加载</small>';
  }

  row.appendChild(events);

  // 已展开的模板编辑器在重绘后需要保持展开，否则每改一个字就会收起来
  store.handlerList.forEach(h => {
    if (chosen.has(h.className) && openTemplates.has(ui + ',' + ti + ',' + h.className)) {
      row.appendChild(templateEditor(ui, ti, h));
    }
  });

  return row;
}

// 占位符要查文档才知道有哪些，模板里写了什么效果也全靠脑补。
// 做成可点击插入的标签加实时预览，把这两件事都摆到眼前
function templateEditor(ui, ti, handler) {
  const message = store.pushData[ui].targets[ti].messages.find(m => m.handler === handler.className);
  const value = (message.params && message.params.message) != null
    ? message.params.message
    : (handler.defaultParams && handler.defaultParams.message) || '';

  const box = el('div', 'tpl');
  box.innerHTML = '<div class="chips">'
    + handler.placeholders.map(p => '<button type="button" data-ins="' + esc(p) + '">' + esc(p) + '</button>').join('')
    + '</div><textarea data-tpl-input="' + ui + ',' + ti + ',' + esc(handler.className) + '">' + esc(value) + '</textarea>'
    + '<div class="preview"></div>';

  const input = box.querySelector('textarea');
  const preview = box.querySelector('.preview');

  const refresh = () => { preview.innerHTML = '预览：<b>' + esc(previewTemplate(input.value)) + '</b>'; };
  refresh();

  input.addEventListener('input', () => {
    message.params = message.params || {};
    message.params.message = input.value;
    refresh();
    markDirty();
  });

  box.querySelectorAll('[data-ins]').forEach(btn => {
    btn.addEventListener('click', () => {
      const token = btn.getAttribute('data-ins');
      const at = input.selectionStart;
      input.value = input.value.slice(0, at) + token + input.value.slice(input.selectionEnd);
      input.selectionStart = input.selectionEnd = at + token.length;
      input.focus();
      input.dispatchEvent(new Event('input'));
    });
  });

  if ((handler.options || []).length) {
    box.appendChild(optionEditor(message, handler.options));
  }

  return box;
}

// 处理器自带的参数。界面不认识任何具体参数名，只按声明的类型渲染，
// 因此第三方插件声明了 options 就同样能在这里配
function optionEditor(message, options) {
  const wrap = el('div', 'opts');
  wrap.innerHTML = '<h5>该内容的可选项</h5>';

  const grid = el('div', 'grid');
  options.forEach(opt => {
    const current = message.params && message.params[opt.key] != null
      ? message.params[opt.key]
      : opt.defaultValue;

    const label = el('label');
    const title = '<span>' + esc(opt.label) + '</span>';

    if (opt.type === 'BOOLEAN') {
      label.innerHTML = '<input type="checkbox"' + (current ? ' checked' : '') + '>' + title;
      label.querySelector('input').addEventListener('change', e => {
        message.params = message.params || {};
        message.params[opt.key] = e.target.checked;
        markDirty();
      });
    } else {
      label.innerHTML = title + '<input type="number" value="' + esc(current)
        + '"' + (opt.min == null ? '' : ' min="' + opt.min + '"')
        + (opt.max == null ? '' : ' max="' + opt.max + '"') + '>';
      label.querySelector('input').addEventListener('change', e => {
        // 就近夹到合法区间：后端也会夹一次，但当场纠正过来才看得懂发生了什么
        let value = Math.round(Number(e.target.value) || 0);
        if (opt.min != null) { value = Math.max(opt.min, value); }
        if (opt.max != null) { value = Math.min(opt.max, value); }
        e.target.value = value;
        message.params = message.params || {};
        message.params[opt.key] = value;
        markDirty();
      });
    }

    if (opt.description) {
      label.title = opt.description;
    }
    grid.appendChild(label);
  });

  wrap.appendChild(grid);
  return wrap;
}

// 用假数据渲染，让人在配置时就看到大致效果，不必等真事件发生
const PREVIEW_VALUES = {
  '{uname}': '示例主播', '{title}': '示例直播间标题', '{action}': '投稿了视频',
  '{url}': 'https://live.bilibili.com/123456', '{time}': '1 小时 23 分钟',
  '{cover}': '［直播间封面］', '{picture}': '［动态图片］',
  '{next}': '\n──────\n', '{at=all}': '@全体成员'
};

function previewTemplate(text) {
  let out = String(text || '');
  Object.keys(PREVIEW_VALUES).forEach(k => { out = out.split(k).join(PREVIEW_VALUES[k]); });
  return out || '（模板为空，将不推送内容）';
}

// 事件委托：卡片会整体重绘，逐个绑定监听器既繁琐又容易漏
$('#streamers').addEventListener('change', e => {
  const t = e.target;
  const enable = t.getAttribute('data-enable');
  const tPlatform = t.getAttribute('data-t-platform');
  const tType = t.getAttribute('data-t-type');
  const tNum = t.getAttribute('data-t-num');
  const h = t.getAttribute('data-h');

  if (enable !== null) {
    store.pushData[+enable].enabled = t.checked;
  } else if (tPlatform) {
    const [ui, ti] = tPlatform.split(',').map(Number);
    store.pushData[ui].targets[ti].platform = t.value;
  } else if (tType) {
    const [ui, ti] = tType.split(',').map(Number);
    store.pushData[ui].targets[ti].type = Number(t.value);
  } else if (tNum) {
    const [ui, ti] = tNum.split(',').map(Number);
    store.pushData[ui].targets[ti].num = t.value.trim() === '' ? null : Number(t.value.trim());
  } else if (h) {
    const parts = h.split(',');
    const target = store.pushData[+parts[0]].targets[+parts[1]];
    const className = parts.slice(2).join(',');
    target.messages = target.messages || [];
    if (t.checked) {
      // 已存在则不重复添加，以免覆盖既有的 params
      if (!target.messages.some(m => m.handler === className)) {
        target.messages.push({ handler: className });
      }
    } else {
      target.messages = target.messages.filter(m => m.handler !== className);
      openTemplates.delete(h);
    }
    // 勾选状态决定是否显示「编辑模板」入口，需重绘
    renderStreamers();
  } else {
    return;
  }

  markDirty();
});

$('#streamers').addEventListener('click', e => {
  const delUser = e.target.getAttribute('data-del-user');
  const delTarget = e.target.getAttribute('data-del-target');
  const tpl = e.target.getAttribute('data-tpl');

  if (tpl) {
    if (openTemplates.has(tpl)) openTemplates.delete(tpl); else openTemplates.add(tpl);
    renderStreamers();
    return;
  }

  if (delUser !== null) {
    const user = store.pushData[+delUser];
    if (!confirm('确定删除主播 ' + (user._uname || user.uid) + ' 吗？')) return;
    store.pushData.splice(+delUser, 1);
    renderStreamers();
    markDirty();
  } else if (delTarget) {
    const [ui, ti] = delTarget.split(',').map(Number);
    store.pushData[ui].targets.splice(ti, 1);
    renderStreamers();
    markDirty();
  }
});

export async function addStreamer() {
  const input = $('#add-uid').value.trim();
  if (!input) {
    say('请先输入 uid 或个人空间链接', 'err');
    return;
  }

  $('#add-streamer').disabled = true;
  say('查询中…');

  try {
    // 先把昵称显示出来让人确认，避免 uid 打错一位却配了个陌生人
    const res = await api('/streamer/lookup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform: 'bilibili', uid: input })
    });

    if (!res.success) {
      say(res.message, 'err');
      return;
    }

    if (store.pushData.some(u => Number(u.uid) === Number(res.uid))) {
      say('主播 ' + res.uname + ' 已在列表中', 'err');
      return;
    }

    if (!confirm('确认添加「' + res.uname + '」（uid ' + res.uid + '）吗？')) return;

    store.pushData.push({
      uid: res.uid, platform: 'bilibili', enabled: true, targets: [],
      _uname: res.uname, _roomId: res.roomId, _face: res.face
    });
    $('#add-uid').value = '';
    renderStreamers();
    markDirty();
    say('已添加 ' + res.uname + '，请为其配置推送目标后保存', 'ok');
  } catch (e) {
    say('查询失败：' + e.message, 'err');
  } finally {
    $('#add-streamer').disabled = false;
  }
}

// 下划线开头的字段仅供界面展示（昵称、头像等），不应写进配置文件
export function serializePush() {
  return JSON.stringify(store.pushData, (key, value) => key.startsWith('_') ? undefined : value, 2);
}

export function toggleAdvanced() {
  if (!store.advancedMode) {
    $('#datasource').value = serializePush();
  } else {
    try {
      store.pushData = JSON.parse($('#datasource').value);
      renderStreamers();
    } catch (e) {
      say('原始 JSON 格式有误，无法切回表单：' + e.message, 'err');
      return;
    }
  }

  store.advancedMode = !store.advancedMode;
  $('#advanced').style.display = store.advancedMode ? 'block' : 'none';
  $('#streamers').style.display = store.advancedMode ? 'none' : 'block';
  $('#push-toolbar').querySelectorAll('input,button').forEach(elm => {
    if (elm.id !== 'toggle-advanced') elm.disabled = store.advancedMode;
  });
  $('#toggle-advanced').textContent = store.advancedMode ? '返回表单编辑' : '高级：编辑原始 JSON';
}

// 配置文件里只有 uid，昵称需要另行补全才能在界面上显示
export async function decoratePushData() {
  await Promise.all(store.pushData.map(async user => {
    if (user._uname || user.platform !== 'bilibili') return;
    try {
      const res = await api('/streamer/lookup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ platform: user.platform, uid: String(user.uid) })
      });
      if (res.success) {
        user._uname = res.uname;
        user._roomId = res.roomId;
        user._face = res.face;
      }
    } catch (e) {
      // 补全失败不影响配置本身，仍以 uid 展示
    }
  }));
  renderStreamers();
}
