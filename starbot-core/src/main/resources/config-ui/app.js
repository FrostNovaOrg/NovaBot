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

// 配置项有数十项，但真正决定系统能否跑起来的只有寥寥几项。
// 默认只显示必填与常用，高级项收起来——问题不在项多，而在没有区分「必须懂」与「可以不管」
function renderGeneral() {
  const box = $('#groups');
  box.innerHTML = '';

  const showAdvanced = $('#show-advanced').checked;
  const visible = f => showAdvanced || f.level !== 'ADVANCED';

  let shown = 0;
  let hidden = 0;

  for (const g of schema) {
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
      const saved = values[f.name] !== undefined ? values[f.name]
        : (f.defaultValue !== null && f.defaultValue !== undefined ? String(f.defaultValue) : '');
      // 切换显示范围会整体重绘，此时未保存的改动要接着显示出来，否则看起来像被悄悄还原了
      const current = dirty[f.name] !== undefined ? dirty[f.name] : saved;

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
      } else {
        input = el('input');
        input.type = (f.widget === 'integer' || f.widget === 'number') ? 'number' : 'text';
        input.value = current;
        cell.appendChild(input);
      }

      const read = () => f.widget === 'boolean' ? String(input.checked) : input.value;
      // 基准是已保存的值，而非当前显示值——后者可能是尚未保存的改动
      const original = String(saved);

      if (dirty[f.name] !== undefined) {
        row.classList.add('changed');
      }

      const onChange = () => {
        const now = read();
        if (now === original) { delete dirty[f.name]; row.classList.remove('changed'); }
        else { dirty[f.name] = now; row.classList.add('changed'); }
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
// 同一文档里不能有两个相同 id，因此按前缀生成 DOM，逻辑仍只写一份。
function botFormHtml(p) {
  return '<div class="row"><label>地址</label><input id="' + p + '-addr" value="127.0.0.1"></div>'
    + '<div class="row"><label>HTTP 端口</label><input id="' + p + '-hport" value="3000" inputmode="numeric"></div>'
    + '<div class="row"><label>HTTP Token</label><input id="' + p + '-htoken" placeholder="与 OneBot 实现中配置的一致"></div>'
    + '<div class="row"><label>WS 端口</label><input id="' + p + '-wport" value="3001" inputmode="numeric"></div>'
    + '<div class="row"><label>WS Token</label><input id="' + p + '-wtoken" placeholder="与 OneBot 实现中配置的一致"></div>'
    + '<div class="row"><button id="' + p + '-test" type="button">测试连接</button>'
    + '<button id="' + p + '-save" type="button" disabled>保存</button></div>'
    + '<div class="out" id="' + p + '-out"></div>';
}

function bindBotForm(p) {
  $('#' + p + '-test').addEventListener('click', () => testBotConnection(p));
  $('#' + p + '-save').addEventListener('click', () => saveBotConnection(p));
}

// 回填已配置的地址与端口，免得改一个字段要把整套重敲一遍。
// 两个 token 有意不回填：回填只省几次输入，却让凭据白白多经过一次浏览器；
// 保存端对空白字段是「保持原值」语义，留空不会把已有 token 抹掉。
const BOT_FORMS = ['s1', 'bot'];

async function fillBotForms() {
  try {
    const current = await api('/setup/bot');
    if (!current.configured) return;
    BOT_FORMS.forEach(p => {
      if (!$('#' + p + '-addr')) return;
      if (current.address) $('#' + p + '-addr').value = current.address;
      if (current.httpPort) $('#' + p + '-hport').value = current.httpPort;
      if (current.websocketPort) $('#' + p + '-wport').value = current.websocketPort;
    });
  } catch (e) {
    // 回填只是便利，失败时保留默认值即可
  }
}

async function testBotConnection(p) {
  const out = $('#' + p + '-out');
  $('#' + p + '-test').disabled = true;
  out.className = 'out';
  out.textContent = '测试中…';

  try {
    const res = await api('/setup/test-bot', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        address: $('#' + p + '-addr').value.trim(),
        httpPort: Number($('#' + p + '-hport').value.trim()),
        httpToken: $('#' + p + '-htoken').value.trim()
      })
    });

    out.className = 'out ' + (res.success ? 'ok' : 'err');
    out.textContent = res.message + (res.advice ? '\n' + res.advice : '');
    // 只有连通了才允许保存：把错误配置写进文件毫无意义
    $('#' + p + '-save').disabled = !res.success;
  } catch (e) {
    out.className = 'out err';
    out.textContent = '测试失败：' + e.message;
  }

  $('#' + p + '-test').disabled = false;
}

async function saveBotConnection(p) {
  const out = $('#' + p + '-out');
  $('#' + p + '-save').disabled = true;

  try {
    const res = await api('/setup/bot', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        address: $('#' + p + '-addr').value.trim(),
        httpPort: $('#' + p + '-hport').value.trim(),
        websocketPort: $('#' + p + '-wport').value.trim(),
        httpToken: $('#' + p + '-htoken').value.trim(),
        websocketToken: $('#' + p + '-wtoken').value.trim()
      })
    });

    out.className = 'out ' + (res.success ? 'ok' : 'err');
    out.textContent = res.message;
  } catch (e) {
    out.className = 'out err';
    out.textContent = '保存失败：' + e.message;
  }

  $('#' + p + '-save').disabled = false;
}

// ============ 首次配置向导 ============
// 每步都当场验证：若填完只能「保存并重启看看」，配错了不会有任何提示，
// 等于把排障成本全推给了使用者。
// 向导嵌在总览页顶部而非独立页签：新用户打开界面第一眼就该看到它，
// 而不是先猜哪个页签是入口。四步都完成后自动收起。
let wizardTouched = false;   // 使用者手动展开或收起过，此后不再自动折叠

function renderWizard() {
  $('#wizard').innerHTML = `
    <div class="step">
      <h3><span class="no" id="s1-no">1</span>连接机器人</h3>
      <div class="body">` + botFormHtml('s1') + `</div>
    </div>

    <div class="step">
      <h3><span class="no" id="s2-no">2</span>登录哔哩哔哩</h3>
      <div class="body">
        <div class="out" id="s2-out">读取中…</div>
        <div id="s2-qr"></div>
      </div>
    </div>

    <div class="step">
      <h3><span class="no" id="s3-no">3</span>添加第一个主播</h3>
      <div class="body">
        <div class="out" id="s3-out"></div>
        <div class="row"><button id="s3-go" type="button">前往「推送规则」</button></div>
      </div>
    </div>

    <div class="step">
      <h3><span class="no" id="s4-no">4</span>发送测试消息</h3>
      <div class="body">
        <div class="out">在「机器人」页选择目标并发送一条测试消息，群里收到即表示全链路正常。</div>
        <div class="row"><button id="s4-go" type="button">前往「机器人」</button></div>
      </div>
    </div>`;

  bindBotForm('s1');
  $('#s3-go').addEventListener('click', () => switchTab('push'));
  $('#s4-go').addEventListener('click', () => switchTab('bot'));

  refreshWizardState();
}

function stepDone(no, done) {
  const badge = $('#s' + no + '-no');
  badge.classList.toggle('done', !!done);
  badge.textContent = done ? '✓' : String(no);
}

function setWizardCollapsed(collapsed) {
  $('#wizard').style.display = collapsed ? 'none' : 'block';
  $('#wizard-toggle').textContent = collapsed ? '展开' : '收起';
}

async function refreshWizardState() {
  try {
    const [login, st] = await Promise.all([api('/login'), api('/status')]);

    const account = (login.accounts || [])[0];
    const loggedIn = !!(account && account.loggedIn);
    stepDone(2, loggedIn);
    $('#s2-out').textContent = account
      ? (loggedIn ? '已登录，账号 ' + (account.accountId || '未知') : '请使用哔哩哔哩客户端扫描下方二维码')
      : '未找到可登录的平台';
    $('#s2-out').className = 'out' + (loggedIn ? ' ok' : '');
    $('#s2-qr').innerHTML = (account && !loggedIn && account.qrCode)
      ? '<img referrerpolicy="no-referrer" style="width:240px;height:240px;background:#fff;border-radius:6px;padding:8px" src="data:image/png;base64,' + esc(account.qrCode) + '">'
      : '';

    const count = (st.users || []).length;
    stepDone(3, count > 0);
    $('#s3-out').textContent = count ? '已配置 ' + count + ' 位主播' : '尚未配置任何主播';
    $('#s3-out').className = 'out' + (count ? ' ok' : '');

    const bot = (st.health || []).find(h => h.scope === 'BOT');
    const botOk = !!(bot && bot.level === 'OK');
    stepDone(1, botOk);

    // 第四步没有独立的判定依据：能把消息推出去的前提正是前三步都成立
    const ready = botOk && loggedIn && count > 0;
    stepDone(4, ready);

    const remaining = [botOk, loggedIn, count > 0, ready].filter(x => !x).length;
    $('#wizard-title').textContent = remaining
      ? '首次配置 · 还有 ' + remaining + ' 步未完成'
      : '首次配置已完成';
    if (!wizardTouched) setWizardCollapsed(remaining === 0);
  } catch (e) {
    // 向导只是引导，拉取失败不影响其余功能
  }
}

// ============ 推送规则表单 ============
// 处理器的全限定类名属于实现细节，不该要求使用者手抄。此处由 /api/handlers 驱动渲染，
// 插件新增处理器时勾选项自动出现，前端不硬编码任何类名。
let pushData = [];      // datasource.json 解析结果，改动直接作用其上以保留表单未覆盖的字段
let handlerList = [];   // 已注册的推送处理器
let senderList = [];    // 已配置的机器人平台
let advancedMode = false;
const openTemplates = new Set();   // 已展开的模板编辑器，卡片重绘后据此恢复展开状态

function renderStreamers() {
  const box = $('#streamers');

  if (!pushData.length) {
    box.innerHTML = '<div class="empty">尚未配置任何主播。在上方输入 uid 或粘贴个人空间链接来添加。</div>';
    return;
  }

  box.innerHTML = '';
  pushData.forEach((user, ui) => box.appendChild(streamerCard(user, ui)));
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
    user.targets.push({ platform: senderList[0] || '', type: 1, num: null, enabled: true, messages: [] });
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
  const known = senderList.length ? senderList : (target.platform ? [target.platform] : []);
  const platformOpts = known.length
    ? known.map(s => '<option value="' + esc(s) + '"' + (s === target.platform ? ' selected' : '') + '>'
        + esc(s) + (senderList.length ? '' : '（当前不可用）') + '</option>').join('')
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

  handlerList.forEach(h => {
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

  if (!handlerList.length) {
    events.innerHTML = '<small>尚未加载到任何推送处理器，请确认对应插件已加载</small>';
  }

  row.appendChild(events);

  // 已展开的模板编辑器在重绘后需要保持展开，否则每改一个字就会收起来
  handlerList.forEach(h => {
    if (chosen.has(h.className) && openTemplates.has(ui + ',' + ti + ',' + h.className)) {
      row.appendChild(templateEditor(ui, ti, h));
    }
  });

  return row;
}

// 占位符要查文档才知道有哪些，模板里写了什么效果也全靠脑补。
// 做成可点击插入的标签加实时预览，把这两件事都摆到眼前
function templateEditor(ui, ti, handler) {
  const message = pushData[ui].targets[ti].messages.find(m => m.handler === handler.className);
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
    pushData[+enable].enabled = t.checked;
  } else if (tPlatform) {
    const [ui, ti] = tPlatform.split(',').map(Number);
    pushData[ui].targets[ti].platform = t.value;
  } else if (tType) {
    const [ui, ti] = tType.split(',').map(Number);
    pushData[ui].targets[ti].type = Number(t.value);
  } else if (tNum) {
    const [ui, ti] = tNum.split(',').map(Number);
    pushData[ui].targets[ti].num = t.value.trim() === '' ? null : Number(t.value.trim());
  } else if (h) {
    const parts = h.split(',');
    const target = pushData[+parts[0]].targets[+parts[1]];
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
    const user = pushData[+delUser];
    if (!confirm('确定删除主播 ' + (user._uname || user.uid) + ' 吗？')) return;
    pushData.splice(+delUser, 1);
    renderStreamers();
    markDirty();
  } else if (delTarget) {
    const [ui, ti] = delTarget.split(',').map(Number);
    pushData[ui].targets.splice(ti, 1);
    renderStreamers();
    markDirty();
  }
});

async function addStreamer() {
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

    if (pushData.some(u => Number(u.uid) === Number(res.uid))) {
      say('主播 ' + res.uname + ' 已在列表中', 'err');
      return;
    }

    if (!confirm('确认添加「' + res.uname + '」（uid ' + res.uid + '）吗？')) return;

    pushData.push({
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
function serializePush() {
  return JSON.stringify(pushData, (key, value) => key.startsWith('_') ? undefined : value, 2);
}

function toggleAdvanced() {
  if (!advancedMode) {
    $('#datasource').value = serializePush();
  } else {
    try {
      pushData = JSON.parse($('#datasource').value);
      renderStreamers();
    } catch (e) {
      say('原始 JSON 格式有误，无法切回表单：' + e.message, 'err');
      return;
    }
  }

  advancedMode = !advancedMode;
  $('#advanced').style.display = advancedMode ? 'block' : 'none';
  $('#streamers').style.display = advancedMode ? 'none' : 'block';
  $('#push-toolbar').querySelectorAll('input,button').forEach(elm => {
    if (elm.id !== 'toggle-advanced') elm.disabled = advancedMode;
  });
  $('#toggle-advanced').textContent = advancedMode ? '返回表单编辑' : '高级：编辑原始 JSON';
}

// 配置文件里只有 uid，昵称需要另行补全才能在界面上显示
async function decoratePushData() {
  await Promise.all(pushData.map(async user => {
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

// 「刚才那条推了吗」「为什么没推」此前只能翻 journalctl
function renderHistory(records) {
  const body = $('#history tbody');
  if (!records || !records.length) {
    body.innerHTML = '<tr><td colspan="4" class="empty">尚无推送记录</td></tr>';
    return;
  }

  body.innerHTML = records.map(r => {
    const result = r.success
      ? '<span class="good">成功</span>'
      : '<span class="bad">失败：' + esc(r.reason || '未知原因') + '</span>';
    return '<tr><td>' + esc(r.at) + '</td><td>' + esc(r.target) + '</td>'
      + '<td title="' + esc(r.summary) + '">' + esc(r.summary) + '</td><td>' + result + '</td></tr>';
  }).join('');
}

async function loadHistory() {
  try {
    renderHistory((await api('/push-history')).records);
  } catch (e) {
    // 推送记录拉取失败不影响其余状态展示
  }
}

// 二维码原本只打印在启动日志里，systemd 部署时得翻 journalctl，
// 且终端字符画在字体或宽度不合适时根本扫不出来
let accountTimer = null;

function renderAccounts(accounts) {
  const box = $('#accounts');
  box.innerHTML = (accounts || []).map(a => {
    const who = a.loggedIn
      ? '<b>' + esc(a.displayName) + '　已登录</b><span>账号 ' + esc(a.accountId || '未知') + '</span>'
      : '<b>' + esc(a.displayName) + '　未登录</b><span>'
        + (a.qrCode ? '请使用手机客户端扫描右侧二维码' : '尚未生成二维码，请稍候') + '</span>';
    const qr = a.qrCode ? '<img alt="登录二维码" src="data:image/png;base64,' + esc(a.qrCode) + '">' : '';
    const action = a.loggedIn
      ? '<button type="button" data-logout="' + esc(a.platform) + '">退出登录</button>'
      : '';
    return '<div class="account"><div class="who">' + who + '</div>' + qr + action + '</div>';
  }).join('');

  box.querySelectorAll('[data-logout]').forEach(btn => {
    btn.addEventListener('click', () => logout(btn.getAttribute('data-logout')));
  });

  // 等待扫码时轮询刷新，扫完页面自动变为已登录，不必手动刷新。
  // 二维码在「哔哩哔哩」页与总览页的向导里各有一处，两处都要跟着刷新
  const waiting = (accounts || []).some(a => !a.loggedIn);
  clearTimeout(accountTimer);
  if (waiting && (tab === 'bilibili' || tab === 'overview')) {
    accountTimer = setTimeout(() => {
      loadAccounts();
      if (tab === 'overview') refreshWizardState();
    }, 3000);
  }
}

async function loadAccounts() {
  try {
    renderAccounts((await api('/login')).accounts);
  } catch (e) {
    // 登录信息拉取失败不影响其余状态展示，静默重试即可
  }
}

async function logout(platform) {
  if (!confirm('确定退出 ' + platform + ' 的登录吗？退出后需重新扫码。')) return;
  say('正在退出…');
  try {
    const res = await api('/login/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform })
    });
    say(res.message || (res.success ? '已退出' : '退出失败'), res.success ? 'ok' : 'err');
    if (res.success) setTimeout(loadAccounts, 500);
  } catch (e) {
    say('退出失败：' + e.message, 'err');
  }
}

// 配错了群号、Token、或机器人不在群里，表现全都是「什么都不发生」。
// 让使用者当场发一条真消息，是最直接的验证手段
function renderTestMessage(senders) {
  const pick = $('#test-platform');
  pick.innerHTML = (senders || []).length
    ? senders.map(s => '<option value="' + esc(s) + '">' + esc(s) + '</option>').join('')
    : '<option value="">（尚无可用的机器人）</option>';
  $('#test-send').disabled = !(senders || []).length;
}

async function sendTestMessage() {
  const box = $('#test-result');
  const platform = $('#test-platform').value;
  const num = $('#test-num').value.trim();

  if (!platform || !num) {
    box.style.display = 'block';
    box.className = 'issues';
    box.innerHTML = '<b>请先选择机器人并填写群号或 QQ 号</b>';
    return;
  }

  $('#test-send').disabled = true;
  box.style.display = 'block';
  box.className = 'issues';
  box.innerHTML = '<b>发送中…</b>';

  try {
    const res = await api('/test-message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform, type: Number($('#test-type').value), num: Number(num) })
    });

    box.className = 'issues' + (res.success ? ' ok' : '');
    let html = '<b>' + esc(res.message) + '</b>';
    if (res.advice) html += '<ul><li>' + esc(res.advice) + '</li></ul>';
    if (res.raw) html += '<ul><li>接口原始响应：' + esc(JSON.stringify(res.raw)) + '</li></ul>';
    box.innerHTML = html;
  } catch (e) {
    box.className = 'issues';
    box.innerHTML = '<b>发送失败：' + esc(e.message) + '</b>';
  }

  $('#test-send').disabled = false;
}

// 「临时静音」要求立即生效，因此后端会同时改内存与配置文件
let pushEnabled = true;

function renderPushSwitch(enabled) {
  pushEnabled = enabled !== false;
  $('#toggle-push').textContent = pushEnabled ? '暂停全部推送' : '恢复推送';
  $('#push-hint').textContent = pushEnabled ? '当前正常推送' : '已暂停，所有推送都会被丢弃';
  $('#push-hint').style.color = pushEnabled ? '' : 'var(--err)';
}

async function togglePush() {
  const next = !pushEnabled;
  $('#toggle-push').disabled = true;
  try {
    const res = await api('/push/toggle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: next })
    });
    renderPushSwitch(next);
    say(res.message, res.success ? 'ok' : 'err');
  } catch (e) {
    say('切换失败：' + e.message, 'err');
  }
  $('#toggle-push').disabled = false;
}

function healthRows(list, emptyText) {
  return list.length
    ? list.map(h =>
        '<div class="row"><span class="dot ' + esc(h.level) + '"></span>' +
        '<span class="who">' + esc(h.name) + '</span>' +
        '<span class="sum">' + esc(h.summary) + '</span>' +
        (h.advice ? '<span class="advice">' + esc(h.advice) + '</span>' : '') +
        '</div>').join('')
    : '<div class="row"><span class="who">健康检查</span><span>' + esc(emptyText) + '</span></div>';
}

function renderStatus(data) {
  renderPushSwitch(data.pushEnabled);
  renderTestMessage(data.senders);

  // 总览展示全部探针；另两个页签只展示与自己相关的，由探针自报 scope 决定归属
  const health = data.health || [];
  $('#health').innerHTML = healthRows(health, '暂无可用探针');
  $('#bot-health').innerHTML = healthRows(health.filter(h => h.scope === 'BOT'),
    '未找到机器人适配器，请确认对应插件已加载');
  $('#bili-health').innerHTML = healthRows(health.filter(h => h.scope === 'PLATFORM'),
    '未找到哔哩哔哩模块的健康探针');

  const r = data.runtime || {};
  $('#runtime').innerHTML = [
    ['堆内存', r.heapUsedMb + ' / ' + r.heapMaxMb + ' MB'],
    ['线程数', r.threads],
    ['CPU 核心', r.processors],
    ['监听主播', (data.users || []).length]
  ].map(([l, n]) => '<div class="card"><div class="n">' + n + '</div><div class="l">' + l + '</div></div>').join('');

  const body = $('#users tbody');
  body.innerHTML = '';

  if (!data.users || !data.users.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">尚未配置任何主播，请在「推送规则」中添加</td></tr>';
    return;
  }

  for (const u of data.users) {
    const tr = el('tr');
    tr.innerHTML = [u.uid, u.uname || '—', u.roomId || '—', u.platform,
      u.targets, u.enabled === false ? '已停用' : '正常']
      .map(v => '<td>' + esc(v) + '</td>').join('');
    body.appendChild(tr);
  }
}

// ---- 群与成员 ----
// 三份数据同属 state.json，用一个接口一次取回：分三次请求会出现
// 「命令是新的、名单是旧的」这种自相矛盾的画面
let stateData = null;

async function loadState() {
  try {
    stateData = await api('/state');
    renderState(stateData);
  } catch (e) {
    say('载入运行状态失败：' + e.message, 'err');
  }
}

function renderState(d) {
  renderSessions(d.sessions || [], d.commands || []);
  renderSubs(d.subscriptions || []);
  renderBinds(d.bindings || []);
}

function renderSessions(sessions, commands) {
  const box = $('#sess-list');
  box.innerHTML = '';

  if (!sessions.length) {
    box.innerHTML = '<table><tbody><tr><td class="empty">尚未配置任何推送目标，'
      + '请先在「推送规则」中添加群或好友</td></tr></tbody></table>';
    return;
  }

  // 命令按名称排序，同类的并不相邻。不先归类就会出现「提醒订阅」隔几行又冒出来一次，
  // 与群里「菜单」的分组也对不上。分类顺序取各自首次出现的次序，与「菜单」一致
  const byCategory = new Map();
  for (const c of commands) {
    if (!byCategory.has(c.category)) byCategory.set(c.category, []);
    byCategory.get(c.category).push(c);
  }

  for (const s of sessions) {
    const card = el('div', 'sess');
    const disabled = s.disabled || [];
    const streamers = s.streamers || [];

    // 从推送配置里删掉、但状态仍残留的会话要单独标出来：命令还关着，
    // 一旦重新配置推送就立刻生效，是最容易百思不解的一种情形
    const tag = s.configured
      ? '<span class="tag">' + esc(streamers.length ? '推送：' + streamers.join('、') : '无主播') + '</span>'
      : '<span class="tag off">未配置推送 · 仅残留状态</span>';

    let html = '<div class="head"><b>' + esc((s.type || '会话') + ' ' + s.num) + '</b>'
      + '<span class="id">' + esc(s.platform) + '</span>' + tag + '</div>';

    // 金额可见性放在命令开关之前：它不是某一条命令的开关，而是这个会话能看到什么，
    // 同时管住下播报告与全部数据查询命令
    html += '<div class="cat">这个会话能看到什么</div>'
      + '<div class="cmd"><span class="nm">直播收益等金额</span>'
      + '<span class="ds">关掉后，下播报告不显示收益、卡片改用人数与条数、礼物与醒目留言榜整榜不出；'
      + '数据查询命令同样不再回金额'
      + (s.revenueExplicit ? '' : '（当前为默认值：私聊显示、群聊隐藏）') + '</span>'
      + '<label class="switch"><input type="checkbox" data-revenue="1" data-num="' + esc(s.num) + '"'
      + ' data-platform="' + esc(s.platform) + '"' + (s.revenueVisible ? ' checked' : '') + '></label>'
      + '</div>';

    for (const [category, list] of byCategory) {
      html += '<div class="cat">' + esc(category) + '</div>';

      for (const c of list) {
        const off = disabled.includes(c.name);
        html += '<div class="cmd"><span class="nm">' + esc(c.name) + '</span>'
          + '<span class="ds">' + esc(c.description) + '</span>'
          + (c.requiresAdmin ? '<span class="adm">仅管理员</span>' : '')
          + (c.disableable
              ? '<label class="switch"><input type="checkbox" data-num="' + esc(s.num) + '"'
                + ' data-platform="' + esc(s.platform) + '" data-command="' + esc(c.name) + '"'
                + (off ? '' : ' checked') + '></label>'
              // 不可关闭的命令仍可能在状态文件里留有禁用记录（手工编辑过，或早先版本允许关闭）。
              // 它此刻不生效，但不说出来就等于让文件里的内容对着界面撒谎
              : '<span class="lock">' + (off ? '不可关闭 · 文件中有残留记录，当前不生效' : '不可关闭') + '</span>')
          + '</div>';
      }
    }

    // 状态文件里禁用了、但当前没有对应命令的记录（命令改过名或已删除）。
    // 不列出来就永远清不掉：它们不属于任何分类，会从上面的循环里整个漏掉
    const known = new Set(commands.map(c => c.name));
    const unknown = disabled.filter(name => !known.has(name));
    if (unknown.length) {
      html += '<div class="cat">状态文件中的残留</div>';
      for (const name of unknown) {
        html += '<div class="cmd"><span class="nm">' + esc(name) + '</span>'
          + '<span class="ds">当前没有这个命令，多为改过名或已删除，记录留着也不会生效</span>'
          + '<label class="switch"><input type="checkbox" data-unknown="1" data-num="' + esc(s.num) + '"'
          + ' data-platform="' + esc(s.platform) + '" data-command="' + esc(name) + '"></label>'
          + '</div>';
      }
    }

    card.innerHTML = html;
    box.appendChild(card);
  }

  box.querySelectorAll('input[data-command]').forEach(input => {
    input.addEventListener('change', () => toggleCommand(input));
  });
  box.querySelectorAll('input[data-revenue]').forEach(input => {
    input.addEventListener('change', () => toggleRevenue(input));
  });
}

async function toggleRevenue(input) {
  const body = {
    platform: input.dataset.platform,
    num: Number(input.dataset.num),
    visible: input.checked
  };

  input.disabled = true;
  try {
    const res = await api('/state/revenue', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    say(res.message || (res.success ? '已保存' : '操作失败'), res.success ? 'ok' : 'err');
    // 失败时把开关拨回去：留在新位置会让人以为改成功了
    if (!res.success) input.checked = !input.checked;
    // 改过之后就不再是默认值了，说明文字要跟着变
    else await loadState();
  } catch (e) {
    input.checked = !input.checked;
    say('操作失败：' + e.message, 'err');
  }
  input.disabled = false;
}

async function toggleCommand(input) {
  const body = {
    platform: input.dataset.platform,
    num: Number(input.dataset.num),
    command: input.dataset.command,
    disabled: !input.checked
  };

  input.disabled = true;
  try {
    const res = await api('/state/command', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    say(res.message || (res.success ? '已保存' : '操作失败'), res.success ? 'ok' : 'err');
    // 失败时把开关拨回去：留在新位置会让人以为改成功了
    if (!res.success) input.checked = !input.checked;
    // 残留记录清掉后那一行就该消失，否则看上去像是没清干净
    else if (input.dataset.unknown) await loadState();
  } catch (e) {
    input.checked = !input.checked;
    say('操作失败：' + e.message, 'err');
  }
  input.disabled = false;
}

function renderSubs(subs) {
  const body = $('#subs tbody');
  body.innerHTML = '';

  if (!subs.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">暂无订阅，群成员发送「开播@我」即可订阅</td></tr>';
    return;
  }

  for (const s of subs) {
    const tr = el('tr');
    tr.innerHTML = '<td>' + esc(s.num) + '</td>'
      + '<td>' + esc(s.streamerName || s.streamerUid) + '</td>'
      + '<td>' + esc(s.typeName) + '</td>'
      + '<td>' + s.users.length + '</td>'
      + '<td><div class="uids">' + s.users.map(u =>
          '<button type="button" data-uid="' + esc(u) + '" title="移除">' + esc(u) + ' ×</button>').join('')
      + '</div></td>'
      + '<td class="act"><button type="button" data-clear="1">清空</button></td>';

    tr.querySelectorAll('[data-uid]').forEach(b =>
      b.addEventListener('click', () => removeSub(s, Number(b.dataset.uid))));
    tr.querySelector('[data-clear]').addEventListener('click', () => {
      if (confirm('确定清空「' + (s.streamerName || s.streamerUid) + '」在 ' + s.num
          + ' 的' + s.typeName + '订阅名单吗？共 ' + s.users.length + ' 人。')) removeSub(s, null);
    });
    body.appendChild(tr);
  }
}

async function removeSub(sub, userUid) {
  try {
    const res = await api('/state/subscription', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        platform: sub.platform, num: sub.num, streamerUid: sub.streamerUid, type: sub.type, userUid
      })
    });
    say(res.message || (res.success ? '已移除' : '操作失败'), res.success ? 'ok' : 'err');
    if (res.success) await loadState();
  } catch (e) {
    say('操作失败：' + e.message, 'err');
  }
}

function renderBinds(binds) {
  const body = $('#binds tbody');
  body.innerHTML = '';

  if (!binds.length) {
    body.innerHTML = '<tr><td colspan="5" class="empty">暂无绑定，群成员发送「绑定 uid」即可绑定</td></tr>';
    return;
  }

  for (const b of binds) {
    const tr = el('tr');
    tr.innerHTML = '<td>' + esc(b.pushPlatform) + '</td><td>' + esc(b.senderUid) + '</td>'
      + '<td>' + esc(b.livePlatform) + '</td><td>' + esc(b.liveUid) + '</td>'
      + '<td class="act"><button type="button">解绑</button></td>';

    tr.querySelector('button').addEventListener('click', async () => {
      if (!confirm('确定解除 ' + b.senderUid + ' 与 ' + b.liveUid + ' 的绑定吗？对方需重新发送「绑定」。')) return;
      try {
        const res = await api('/state/binding', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            pushPlatform: b.pushPlatform, livePlatform: b.livePlatform, senderUid: b.senderUid
          })
        });
        say(res.message || (res.success ? '已解绑' : '操作失败'), res.success ? 'ok' : 'err');
        if (res.success) await loadState();
      } catch (e) {
        say('操作失败：' + e.message, 'err');
      }
    });
    body.appendChild(tr);
  }
}

// ---- 数据分析 ----
// 聚合全在服务端做，这里只负责画。周期归属、时区、空周期补零那几条规则
// 若在前端再实现一遍，两边迟早对不上
let anaData = null;

function fmtDuration(seconds) {
  if (!seconds) return '0 分';
  const h = Math.floor(seconds / 3600), m = Math.round((seconds % 3600) / 60);
  return h ? h + ' 时 ' + m + ' 分' : m + ' 分';
}

function fmtMetric(value, metric) {
  if (value === undefined || value === null) return '—';
  return metric.money ? value.toFixed(2) : Math.round(value).toLocaleString('en-US');
}

async function loadAnalytics() {
  const period = $('#ana-period').value;
  const uid = $('#ana-uid').value;
  try {
    anaData = await api('/analytics?period=' + period + (uid ? '&uid=' + encodeURIComponent(uid) : ''));
    renderAnalytics(anaData);
  } catch (e) {
    say('载入数据分析失败：' + e.message, 'err');
  }
}

function renderAnalyticsStreamers(list, keep) {
  const sel = $('#ana-uid');
  sel.innerHTML = '<option value="">全部主播</option>'
    + list.map(s => '<option value="' + esc(s.uid) + '">'
        + esc(s.uname || s.uid) + '（' + s.sessions + ' 场）</option>').join('');
  if (keep) sel.value = keep;
}

function renderAnalytics(d) {
  renderAnalyticsStreamers(d.streamers || [], d.uid ? String(d.uid) : '');

  const buckets = d.buckets || [];
  const metrics = d.metrics || [];
  const periodName = d.period === 'month' ? '月' : '周';

  $('#ana-scope').textContent = '共归档 ' + (d.archive?.count || 0) + ' 场，当前口径 ' + d.sessionCount + ' 场';

  if (!buckets.length) {
    $('#ana-cards').innerHTML = '';
    $('#ana-chart').innerHTML = '';
    $('#ana-table').innerHTML = '<table><tbody><tr><td class="empty">还没有归档数据。'
      + '每场直播下播时会自动追加一条，播过一场之后这里就有内容了</td></tr></tbody></table>';
    $('#ana-note').textContent = '';
    return;
  }

  // 汇总卡片统计的是当前展示的全部周期，与下方表格同一口径
  const total = buckets.reduce((a, b) => ({
    sessions: a.sessions + b.sessions,
    duration: a.duration + b.durationSeconds
  }), {sessions: 0, duration: 0});
  const active = buckets.filter(b => b.sessions > 0).length;

  $('#ana-cards').innerHTML = [
    ['总场次', total.sessions],
    ['总时长', fmtDuration(total.duration)],
    ['场均时长', fmtDuration(total.sessions ? Math.round(total.duration / total.sessions) : 0)],
    ['有播的' + periodName, active + ' / ' + buckets.length]
  ].map(([l, n]) => '<div class="card"><div class="n">' + esc(n) + '</div><div class="l">'
    + esc(l) + '</div></div>').join('');

  $('#ana-chart').innerHTML = chartHtml('每' + periodName + '直播时长', buckets,
    b => b.durationSeconds / 3600, v => v.toFixed(1) + ' 时')
    + (metrics.length
        ? chartHtml('每' + periodName + '弹幕数', buckets,
            b => b.metrics[metrics[0].key] || 0, v => Math.round(v).toLocaleString('en-US'))
        : '');

  // 表格：周期 + 场次 + 时长 + 各指标。指标顺序由插件的指标说明决定
  const head = '<tr><th>' + esc(periodName === '月' ? '月份' : '周') + '</th><th class="n">场次</th>'
    + '<th class="n">时长</th>'
    + metrics.map(m => '<th class="n">' + esc(m.name) + (m.unit ? '<br><small>' + esc(m.unit) + '</small>' : '')
        + '</th>').join('') + '</tr>';

  const rows = buckets.slice().reverse().map(b =>
    '<tr' + (b.sessions ? '' : ' class="empty-row"') + '><td>' + esc(b.label) + '</td>'
    + '<td class="n">' + b.sessions + '</td>'
    + '<td class="n">' + esc(fmtDuration(b.durationSeconds)) + '</td>'
    + metrics.map(m => '<td class="n">' + esc(b.sessions ? fmtMetric(b.metrics[m.key] || 0, m) : '—')
        + '</td>').join('') + '</tr>').join('');

  $('#ana-table').innerHTML = '<table><thead>' + head + '</thead><tbody>' + rows + '</tbody></table>';

  const notes = [];
  if (!d.metricsKnown) {
    notes.push('未找到该平台的指标说明，只能统计场次与时长。');
  }
  if (d.droppedPeriods) {
    notes.push('更早的 ' + d.droppedPeriods + ' 个' + periodName + '未显示。');
  }
  notes.push('时长与各项指标按开播时刻归入周期，跨零点的直播整场算在开播那一天。');
  // 一项指标都没展示时，这句「哪些指标不参与累加」纯属噪音
  if (metrics.length) {
    notes.push('开播时的粉丝数等「快照」指标不参与累加——把十场的粉丝数加起来不是任何一个真实数字。');
  }
  $('#ana-note').textContent = notes.join('');
}

// 柱状图。画之前先算好最大值，全零时不缩放（否则 0/0 出 NaN，整张图消失）。
//
// 柱子用 SVG（preserveAspectRatio="none" 才能横向铺满），但**文字一律放在 SVG 外面**：
// 同一个属性会把文字一起横向拉伸，宽屏上只是略胖，窄屏上会挤成一团认不出来。
function chartHtml(title, buckets, pick, fmt) {
  const values = buckets.map(pick);
  const max = Math.max(...values, 0);
  const h = 100, bw = 1000 / buckets.length;

  const bars = values.map((v, i) => {
    const bh = max > 0 ? h * (v / max) : 0;
    // 零值也留 2px 的痕迹，否则「这周没播」和「这周没有这项数据」在图上长得一样
    return '<rect class="bar' + (v ? '' : ' zero') + '" x="' + (i * bw + bw * 0.15).toFixed(1)
      + '" y="' + (h - Math.max(bh, v ? 1 : 2)).toFixed(1) + '" width="' + (bw * 0.7).toFixed(1)
      + '" height="' + Math.max(bh, v ? 1 : 2).toFixed(1) + '"><title>'
      + esc(buckets[i].label + '：' + fmt(v)) + '</title></rect>';
  }).join('');

  const peak = values.indexOf(max);
  const peakLabel = max > 0
    ? '<span class="peak" style="left:' + ((peak + 0.5) / buckets.length * 100).toFixed(2) + '%">'
      + esc(fmt(max)) + '</span>'
    : '';

  // 横轴只标首尾，柱子一多全标会糊成一片；具体数值靠悬停看 tooltip
  return '<div class="chart"><h4>' + esc(title) + '</h4>'
    + '<div class="plot"><svg viewBox="0 0 1000 ' + h + '" preserveAspectRatio="none">' + bars
    + '</svg>' + peakLabel + '</div>'
    + '<div class="xlab"><span>' + esc(buckets[0].label) + '</span><span>'
    + esc(buckets[buckets.length - 1].label) + '</span></div></div>';
}

async function load() {
  say('载入中…');
  try {
    const [s, v] = await Promise.all([api('/schema'), api('/values')]);
    schema = s.groups || [];
    values = v.values || {};
    dirty = {};
    renderGeneral();

    const [d, y, st, b, h] = await Promise.all([
      api('/datasource'), api('/raw'), api('/status'), api('/backups'), api('/handlers')]);
    $('#datasource').value = d.content || '[]';
    $('#rawyml').value = y.content || '';
    renderStatus(st);

    handlerList = h.handlers || [];
    senderList = st.senders || [];
    try {
      pushData = JSON.parse(d.content || '[]');
    } catch (e) {
      // 文件内容不合法时退回高级模式，让使用者直接修，而不是把错误内容悄悄吞掉
      pushData = [];
      if (!advancedMode) toggleAdvanced();
      say('推送配置不是合法 JSON，已切换到高级模式供你修正', 'err');
    }
    renderStreamers();
    decoratePushData();
    renderBackups(b.backups);
    loadAccounts();
    loadHistory();
    loadState();
    renderWizard();
    // 向导渲染完成后两份表单才都在 DOM 里，此时统一回填
    fillBotForms();

    const count = schema.reduce((n, g) => n + g.fields.length, 0);
    $('#head-sub').textContent = count + ' 个配置项 · ' + schema.length + ' 个分组';
    say('');
  } catch (e) {
    say('载入失败：' + e.message, 'err');
  }
  markDirty();
}

// 每次保存都会留一份带时间戳的备份，改坏了可以直接滚回去
function renderBackups(names) {
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

async function save() {
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
        body: JSON.stringify(dirty)
      });
      if (res.success) { dirty = {}; document.querySelectorAll('.field.changed').forEach(e => e.classList.remove('changed')); }
    } else {
      res = await api('/datasource', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: advancedMode ? $('#datasource').value : serializePush() })
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
async function saveRaw() {
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

function switchTab(name) {
  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x.dataset.tab === name));
  document.querySelectorAll('section').forEach(x => x.classList.toggle('on', x.id === name));
  tab = name;

  // 窄屏上页签栏是横向滚动的，靠右的页签会落在视野外。选中却看不见等于没有选中标记，
  // 因此把当前页签滚进来。block:'nearest' 防止它顺带把整页往下拉
  $('nav button.on')?.scrollIntoView({inline: 'center', block: 'nearest'});

  clearTimeout(accountTimer);
  if (tab === 'overview') { api('/status').then(renderStatus); loadHistory(); loadAccounts(); refreshWizardState(); }
  else if (tab === 'bot') api('/status').then(renderStatus);
  else if (tab === 'bilibili') { api('/status').then(renderStatus); loadAccounts(); }
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人
  else if (tab === 'sessions') loadState();
  else if (tab === 'analytics') loadAnalytics();

  markDirty();
}

// 「运行自检」把探针再跑一遍并给出结论。异常项本就在下方逐条列着，
// 这里只回答「现在到底有没有问题」，省得使用者自己数
async function runSelfTest() {
  $('#selftest-run').disabled = true;
  say('自检中…');
  try {
    const st = await api('/status');
    renderStatus(st);
    const health = st.health || [];
    const bad = health.filter(h => h.level !== 'OK');
    say(bad.length
      ? bad.length + ' 项异常：' + bad.map(h => h.name).join('、')
      : '自检完成，' + health.length + ' 项全部正常', bad.length ? 'err' : 'ok');
  } catch (e) {
    say('自检失败：' + e.message, 'err');
  }
  $('#selftest-run').disabled = false;
}

document.querySelectorAll('nav button').forEach(b => {
  b.addEventListener('click', () => switchTab(b.dataset.tab));
});

$('#save').addEventListener('click', save);
$('#raw-save').addEventListener('click', saveRaw);
$('#test-send').addEventListener('click', sendTestMessage);
$('#selftest-run').addEventListener('click', runSelfTest);
// 切换显示范围时保留已改动的字段：重绘只影响可见性，不该丢掉未保存的编辑
$('#show-advanced').addEventListener('change', () => { renderGeneral(); markDirty(); });
$('#toggle-push').addEventListener('click', togglePush);
$('#add-streamer').addEventListener('click', addStreamer);
$('#toggle-advanced').addEventListener('click', toggleAdvanced);
$('#add-uid').addEventListener('keydown', e => { if (e.key === 'Enter') addStreamer(); });
$('#wizard-toggle').addEventListener('click', () => {
  wizardTouched = true;
  setWizardCollapsed($('#wizard').style.display !== 'none');
});
$('#ana-period').addEventListener('change', loadAnalytics);
$('#ana-uid').addEventListener('change', loadAnalytics);
$('#reload').addEventListener('click', load);
// 先注入 DOM 再绑定，否则 bindBotForm 取到的是 null
$('#bot-form').innerHTML = botFormHtml('bot');
bindBotForm('bot');
load();
