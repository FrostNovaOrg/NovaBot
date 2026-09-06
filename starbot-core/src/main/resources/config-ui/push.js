/**
 * QQ 推送页：左边一棵「主播 → 通道」两级树，右边随选中项换成主播级或通道级
 *
 * 本文件只管把 push-model.js 算好的东西摆上屏幕。树上标什么记号、模板算默认还是自定义、
 * 「本群设置」那四行摘要写什么，一律在那边判——判断留在这里的话，那几档就只能靠人
 * 打开页面挨个点着看，而其中好几档（命令被群管理员关掉、累计数据没开、群名取不到）
 * 在真机上凑齐一次的代价极高。
 *
 * 号码一律从机器人自己给的名单里挑，本页没有任何一个手填号码的格子：填错一位数不会有
 * 任何报错，消息只是发去了别处。认目标那一步走 links-model.js 的 resolveTarget，
 * 与「发一条试试」「初始设置第 4 步」是同一份判法。
 */

import {ask} from './confirm.js';
import {$, api, dropDisplayOnly, el, esc, markDirty, say} from './core.js';
import {resolveTarget, targetOptions} from './links-model.js';
import {
  atAllStatus, buildDirectory, channelIndex, channelName, commandGroups, commandSummary,
  layoutState, messageOf, noticeSwitches, previewRequestBody, previewRevenueCaption,
  pushTree, recentPushes, revenueSummary, sessionOf,
  streamerName, strandedSessions, subscriptionSummary, templateState, typeName,
} from './push-model.js';
import {renderIncomplete, sessionSettings} from './sessions.js';
import {store} from './store.js';
import {buildLayoutEditor, buildTemplateEditor} from './template.js';
import {isDefault, restoreDefaults, templateAdoption} from './template-model.js';

/** 这一页最近一次取到的运行状态（/api/state），由 loadPushPage 刷新 */
let runtime = {commands: [], sessions: [], subscriptions: [], incomplete: [], totalDataAvailable: null};

/** 最近的推送记录，按通道筛出各自的 5 条 */
let pushHistory = [];

/** @全体成员 今日用量 */
let quota = null;

/** 机器人自己给的群与好友名单，摊成按键查的一张表 */
let directory = {};

/** 挑选目标时那份原样的可选项，认目标要用它 */
let options = [];

/** 加主播格子上的提示，与空着按下时那句共用：两处各写一份就会各说各的 */
export const STREAMER_INPUT_HINT = 'uid、直播间号，或粘贴空间／直播间链接';

/** 地址栏定的选中项：主播 uid 与通道号，两者皆空即通道一览 */
let picked = {uid: '', num: ''};

/** 树上展开了哪几位主播。收起状态不进地址栏——它是「翻到哪儿了」，不是「在看哪一个」 */
const expanded = new Set();

/**
 * 切到某一个落点
 *
 * 只认地址栏、不认「谁点了哪一行」：刷新、收藏、后退三种进法走的都是这一条路。
 * @param uid 主播 uid，或 default（默认模板），空串为通道一览
 * @param num 通道号，空串为主播级
 */
export function showPush(uid, num) {
  picked = {uid: uid || '', num: num || ''};
  if (picked.uid && picked.uid !== 'default') expanded.add(String(picked.uid));
  renderStreamers();
}

/**
 * 重画整页
 *
 * 名字沿用旧的那一个：设置页与初始设置页在存完推送配置之后调它，
 * 换个名字等于让那两处各自记得本页现在叫什么。
 */
export function renderStreamers() {
  renderIncomplete(runtime.incomplete || []);
  paintTree();
  paintRight();
}

/**
 * 取这一页要用的几份数据
 *
 * 一趟取齐而不是各块各取：命令是新的、名单是旧的这种自相矛盾的画面，
 * 在「为什么这个群不响应」这个问题上尤其误事。
 *
 * 名单与额度取不到不算这一趟失败：OneBot 掉线时它们本来就取不到，而推送配置照样看得见、
 * 也照样改得动——整趟判失败的话，屏幕上会只剩一句「载入失败」，配好的东西一个也看不见。
 */
export async function loadPushPage() {
  try {
    runtime = await api('/state');
  } catch (e) {
    say('载入运行状态失败：' + e.message, 'err');
  }

  try {
    pushHistory = (await api('/push-history')).records || [];
  } catch (e) {
    // 推送记录取不到只影响「这个通道最近推送」那一小块，那一块自己会说明
    pushHistory = [];
  }

  try {
    quota = await api('/at-all/quota');
  } catch (e) {
    quota = null;
  }

  try {
    const [groups, friends] = await Promise.all([
      api('/onebot/targets?type=group'), api('/onebot/targets?type=friend')]);
    options = targetOptions(groups, friends);
    directory = buildDirectory(options);
  } catch (e) {
    options = [];
    directory = {};
  }

  renderStreamers();
}

/**
 * 把运行状态交给本页
 *
 * 首页那一趟整体载入也会取一次 /state，交进来省下一次请求。
 * @param data /api/state 回包
 */
export function setRuntimeState(data) {
  runtime = data || runtime;
}

// ============ 左树 ============

function paintTree() {
  const host = $('#push-tree');
  const nav = $('#push-nav');
  const tree = pushTree(store.pushData, runtime.sessions, store.handlerList, directory);

  host.innerHTML = '';
  nav.innerHTML = '<option value="">通道一览</option><option value="default">默认模板</option>';

  if (!tree.length) {
    host.innerHTML = '<p class="hint">还没有主播。点上面「＋ 添加主播」开始。</p>';
    nav.value = picked.uid === 'default' ? 'default' : '';
    return;
  }

  for (const streamer of tree) {
    const group = el('div', 'tgroup');
    group.appendChild(streamerNode(streamer));

    nav.appendChild(navOption('s:' + streamer.uid, streamer.name));

    if (expanded.has(String(streamer.uid))) {
      for (const channel of streamer.channels) {
        group.appendChild(channelNode(streamer, channel));
      }
      if (!streamer.channels.length) {
        group.appendChild(el('div', 'tempty')).textContent = '还没有通道';
      }
    }
    for (const channel of streamer.channels) {
      nav.appendChild(navOption('c:' + streamer.uid + ':' + channel.num, '　└ ' + channel.name));
    }

    host.appendChild(group);
  }

  nav.value = picked.uid === 'default' ? 'default'
    : picked.uid ? (picked.num ? 'c:' + picked.uid + ':' + picked.num : 's:' + picked.uid) : '';
}

function navOption(value, text) {
  const item = el('option');
  item.value = value;
  item.textContent = text;
  return item;
}

function streamerNode(streamer) {
  const on = String(streamer.uid) === String(picked.uid) && !picked.num;
  const node = el('button', 'tnode' + (on ? ' on' : ''));
  node.type = 'button';
  if (on) node.setAttribute('aria-current', 'true');

  const caret = el('span', 'caret');
  caret.textContent = expanded.has(String(streamer.uid)) ? '▾' : '▸';
  caret.addEventListener('click', event => {
    // 三角只管展开收起，不换页：点它是「看看它下面有什么」，不是「我要改它」
    event.stopPropagation();
    if (expanded.has(String(streamer.uid))) expanded.delete(String(streamer.uid));
    else expanded.add(String(streamer.uid));
    paintTree();
  });
  node.appendChild(caret);

  const name = el('span', 'tn-nm');
  name.textContent = streamer.name;
  node.appendChild(name);

  const marks = el('span', 'tn-marks');
  if (!streamer.enabled) marks.appendChild(pill('已停用'));
  else if (streamer.collectOnly) marks.appendChild(pill('只采集，不推送'));
  node.appendChild(marks);

  node.addEventListener('click', () => { location.hash = '#/push/' + streamer.uid; });
  return node;
}

function channelNode(streamer, channel) {
  const on = String(streamer.uid) === String(picked.uid) && String(channel.num) === String(picked.num);
  const node = el('button', 'tnode tchan' + (on ? ' on' : ''));
  node.type = 'button';
  if (on) node.setAttribute('aria-current', 'true');

  const icon = el('span', 'tn-ico');
  icon.textContent = channel.type === 1 ? '群' : '友';
  node.appendChild(icon);

  const name = el('span', 'tn-nm');
  name.textContent = channel.name;
  node.appendChild(name);

  const marks = el('span', 'tn-marks');
  if (channel.noticesOff) marks.appendChild(pill(channel.noticesOff + ' 类关着'));
  if (channel.commandsOff) marks.appendChild(pill(channel.commandsOff + ' 条命令被关', 'err'));
  node.appendChild(marks);

  node.addEventListener('click', () => {
    location.hash = '#/push/' + streamer.uid + '/' + channel.num;
  });
  return node;
}

function pill(text, kind) {
  const node = el('span', 'nv-pill' + (kind ? ' ' + kind : ''));
  node.textContent = text;
  return node;
}

// ============ 右区 ============

function paintRight() {
  const host = $('#push-right');
  host.innerHTML = '';

  if (picked.uid === 'default') {
    renderDefaultTemplates(host);
    return;
  }

  const user = (store.pushData || []).find(item => String(item.uid) === String(picked.uid));
  if (!picked.uid || !user) {
    renderIndex(host);
    return;
  }

  if (!picked.num) {
    renderStreamerLevel(host, user);
    return;
  }

  const target = (user.targets || []).find(item => String(item.num) === String(picked.num));
  if (!target) {
    // 通道刚被删掉、或地址是抄来的：退到这位主播那一级，不留一屏空白
    location.replace('#/push/' + user.uid);
    return;
  }
  renderChannelLevel(host, user, target);
}

/** 一张卡的壳 */
function card(host, title, desc) {
  const box = el('div', 'nv-card hcard');
  if (title) {
    const head = el('h3');
    head.textContent = title;
    box.appendChild(head);
  }
  if (desc) {
    const note = el('p', 'hint');
    note.textContent = desc;
    box.appendChild(note);
  }
  host.appendChild(box);
  return box;
}

// ---- 通道一览 ----

function renderIndex(host) {
  const rows = channelIndex(store.pushData, runtime.sessions, store.handlerList, directory);
  const box = card(host, '通道一览',
    '一位主播 × 一个群（或一位好友）＝ 一个通道。同一个群出现在多位主播下是正常的，'
    + '而「本群设置」那一段是这个群自己的事，对推给它的所有主播共用。');

  if (!rows.length) {
    box.appendChild(el('p', 'hint')).textContent =
      '还没有通道。左边加一位主播，再给它挑一个群或好友。';
  } else {
    const table = el('table', 'ptable');
    table.innerHTML = '<thead><tr><th>主播</th><th>通道</th><th>号</th>'
      + '<th>开着的通知</th><th>模板</th><th></th></tr></thead><tbody>'
      + rows.map(row => '<tr><td>' + esc(row.streamer) + '</td>'
        + '<td>' + esc(row.name) + '</td>'
        + '<td class="n">' + esc(row.num) + '</td>'
        + '<td>' + (row.on.length ? esc(row.on.join('、')) : '全关') + '</td>'
        + '<td>' + (row.custom ? '自定义' : '默认') + '</td>'
        + '<td><a href="#/push/' + esc(row.uid) + '/' + esc(row.num) + '">打开</a></td></tr>').join('')
      + '</tbody>';
    box.appendChild(table);
  }

  renderStranded(host);
}

/**
 * 配置里已经没有、状态文件里还留着的会话
 *
 * 它们在树上一个字也看不见，而命令还关着——一旦重新把推送配回来就立刻生效。
 * 这一块正是让那种「配好了却不响应」现出形来的地方。
 */
function renderStranded(host) {
  const rows = strandedSessions(store.pushData, runtime.sessions);
  if (!rows.length) return;

  const box = card(host, '配置里已经没有、状态里还留着的会话',
    '这些群或好友不在任何一位主播的通道里，因此上面的表里没有它们。'
    + '状态文件里的命令开关仍然留着，一旦重新配上推送就立刻生效。');

  for (const row of rows) {
    const line = el('div', 'strand');
    const text = el('span');
    text.textContent = row.platform + ' · ' + row.num
      + (row.disabled.length ? ' · 关着 ' + row.disabled.length + ' 条命令（'
        + row.disabled.join('、') + '）' : ' · 命令没有被关过')
      + (row.revenueExplicit ? ' · 金额可见性配过' : '');
    line.appendChild(text);

    if (row.disabled.length) {
      const fix = el('button', 'ghost');
      fix.type = 'button';
      fix.textContent = '恢复这几条';
      fix.addEventListener('click', () => restoreCommands(row.platform, row.num, row.disabled));
      line.appendChild(fix);
    }
    box.appendChild(line);
  }
}

// ---- 默认模板 ----

/**
 * 默认模板：同一个编辑器，改的是「所有用默认的通道」那一份
 *
 * 改这里等于一次改一批群，因此页下把影响面逐条列出来——「改一次，所有用默认的通道
 * 一起变」是这一页的立身之本，而它同时意味着一次误改会同时落到一批群上。
 *
 * 存的是<b>相对出厂默认的覆盖</b>（见服务端 PushTemplateDefaults），不是整份参数：
 * 存整份的话，出厂默认此后再动这台机器一个也跟不上，而屏幕上它仍显示「默认」。
 */
function renderDefaultTemplates(host) {
  const box = card(host, '默认模板',
    '用默认模板的通道跟着这里一起变。某个通道想不一样，到那个通道里点「改为自定义」。');

  const handlers = (store.handlerList || []).filter(item => (item.placeholders || []).length);
  if (!handlers.length) {
    box.appendChild(el('p', 'hint')).textContent =
      '还没有加载到任何带文字模板的通知，确认对应插件已加载。';
    return;
  }

  // 这一页的草稿不进底部那条改动条：它存的不是推送配置，走的是自己的保存按钮
  const draft = {};
  const line = el('div', 'tplstate');
  const save = el('button', 'primary');
  save.type = 'button';
  save.textContent = '保存默认模板';
  save.disabled = true;
  line.appendChild(save);

  let currentHandler = handlers[0];

  const reset = el('button', 'ghost');
  reset.type = 'button';
  reset.textContent = '恢复出厂默认';
  reset.addEventListener('click', async () => {
    const name = currentHandler.displayName || currentHandler.className;
    if (!await ask({title: '恢复出厂默认？',
      body: '「' + name + '」的默认模板会改回出厂的样子，用默认模板的通道会一起变回去。'})) return;
    delete draft[currentHandler.className];
    saveDefaults(currentHandler, {}, save);
  });
  line.appendChild(reset);
  box.appendChild(line);

  save.addEventListener('click', () => saveDefaults(currentHandler,
    draft[currentHandler.className] || overridesOf(currentHandler), save));

  buildTemplateEditor(box, {
    handlers,
    editable: true,
    // 默认模板对着<b>出厂默认</b>比，比出来的差集正是要存下来的那一份覆盖
    base: handler => handler.factoryParams || handler.defaultParams,
    paramsOf: handler => draft[handler.className] || overridesOf(handler),
    context: {isGroup: true, admin: null},
    channelLabel: '群里',
    onSelect: handler => {
      currentHandler = handler;
      save.disabled = !draft[handler.className];
    },
    onChange: (handler, params) => {
      currentHandler = handler;
      draft[handler.className] = params;
      save.disabled = false;
      save.textContent = '保存默认模板';
    },
  });

  renderAdoption(host, handlers);
}

/**
 * 这一类通知此刻改过的那几个键
 *
 * 出厂默认与本机默认之差。服务端两份都给（factoryParams 与 defaultParams），
 * 合成一份的话，「改成了与出厂一样的值」与「没改过」就再也分不开。
 */
function overridesOf(handler) {
  const factory = handler.factoryParams || {};
  const now = handler.defaultParams || {};
  const out = {};
  for (const key of Object.keys(now)) {
    if (String(now[key]) !== String(factory[key])) out[key] = now[key];
  }
  return out;
}

/** 把改过的默认模板存下去 */
async function saveDefaults(handler, params, button) {
  button.disabled = true;
  button.textContent = '保存中…';
  try {
    const res = await api('/templates', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({className: handler.className, params}),
    });
    if (!res.success) {
      say((res.message || '保存失败') + (res.issues ? '：' + res.issues.join('；') : ''), 'err');
      button.disabled = false;
      button.textContent = '保存默认模板';
      return;
    }
    say(res.message || '已保存', 'ok');
    // 重新取一遍处理器清单：默认变了，树上「模板：默认／自定义」那一列跟着变
    await reloadHandlers();
    renderStreamers();
  } catch (e) {
    say('保存失败：' + e.message, 'err');
    button.disabled = false;
    button.textContent = '保存默认模板';
  }
}

/** 默认改了之后，各通道是默认还是自定义要重新算 */
async function reloadHandlers() {
  try {
    const res = await api('/handlers');
    store.handlerList = res.handlers || store.handlerList;
  } catch (e) {
    say('重新读取推送处理器失败，屏幕上的「默认／自定义」可能还是旧的：' + e.message, 'err');
  }
}

/** 谁在用默认、谁自定义了 */
function renderAdoption(host, handlers) {
  const box = card(host, '谁在用默认模板',
    '在上面改一次，这里「用默认」的通道全都跟着变；已经自定义的那些不受影响。');

  for (const handler of handlers) {
    const rows = templateAdoption(store.pushData, handler);
    const item = el('div', 'tplrow');
    item.appendChild(el('b')).textContent = handler.displayName || handler.className;
    item.appendChild(el('p', 'hint')).textContent =
      '用默认 ' + rows.following.length + ' 个通道 · 自定义 ' + rows.custom.length + ' 个通道';
    if (rows.following.length) {
      item.appendChild(el('p', 'xs dim')).textContent = '跟着变：' + describe(rows.following);
    }
    if (rows.custom.length) {
      item.appendChild(el('p', 'xs dim')).textContent = '不受影响：' + describe(rows.custom);
    }
    box.appendChild(item);
  }
}

/** 把几个通道写成一句话，取的是屏幕上那个名字而不是号 */
function describe(rows) {
  return rows.map(row => {
    const session = sessionOf(runtime.sessions, row.platform, row.num);
    return channelName(session, row, directory);
  }).join('、');
}

// ---- 主播级 ----

function renderStreamerLevel(host, user) {
  const box = card(host, null, null);
  const head = el('div', 'shead');
  head.innerHTML = (user._face
      ? '<img class="sav" referrerpolicy="no-referrer" src="' + esc(user._face) + '" alt="">'
      : '<span class="sav"></span>')
    + '<div class="sh-m"><div class="sh-nm">' + esc(streamerName(user)) + '</div>'
    + '<div class="sh-id">uid ' + esc(user.uid)
    + (user._roomId ? ' · 直播间 ' + esc(user._roomId) : '') + '</div></div>';

  const side = el('div', 'sh-side');
  const enable = el('label', 'switch');
  enable.innerHTML = '<input type="checkbox"' + (user.enabled === false ? '' : ' checked')
    + '><span>启用</span>';
  enable.querySelector('input').addEventListener('change', event => {
    user.enabled = event.target.checked;
    markDirty();
    renderStreamers();
  });
  side.appendChild(enable);

  const remove = el('button', 'ghost danger');
  remove.type = 'button';
  remove.textContent = '删除主播';
  remove.addEventListener('click', async () => {
    if (!await ask({title: '删除这位主播？',
      body: '「' + streamerName(user) + '」的 '
        + (user.targets || []).length + ' 个通道会一起没掉，历史场次数据保留。'})) return;
    store.pushData.splice(store.pushData.indexOf(user), 1);
    expanded.delete(String(user.uid));
    markDirty();
    location.hash = '#/push';
  });
  side.appendChild(remove);
  head.appendChild(side);
  box.appendChild(head);

  if (user.enabled === false) {
    box.appendChild(el('div', 'note')).textContent =
      '这位主播已停用：照常留在配置里，但不采集也不推送。';
  } else if (!(user.targets || []).length) {
    box.appendChild(el('div', 'note')).textContent =
      '这位主播还没有通道：只采集，不推送。加一个通道才会有人收到消息。';
  }

  const list = card(host, '通道', '一个通道 = 一个群或一位好友。点一行进去，定它收到什么、消息长什么样。');
  for (const target of user.targets || []) {
    list.appendChild(channelRow(user, target));
  }

  const add = el('button', 'addrow');
  add.type = 'button';
  add.textContent = '＋ 添加通道';
  add.addEventListener('click', () => addChannel(user));
  list.appendChild(add);

  const data = card(host, '这位主播的数据', '场次、趋势，还有每一场的详细报告，都在主播页。');
  const link = el('a', 'lnkbtn');
  link.href = '#/streamers';
  link.textContent = '打开 ' + streamerName(user) + ' 的数据 →';
  data.appendChild(link);
}

function channelRow(user, target) {
  const session = sessionOf(runtime.sessions, target.platform, target.num);
  const switches = noticeSwitches(user, target, store.handlerList);
  const row = el('div', 'rowcard');

  const main = el('div', 'rc-main');
  const title = el('div', 'rc-nm');
  const link = el('a');
  link.href = '#/push/' + user.uid + '/' + target.num;
  link.textContent = channelName(session, target, directory);
  title.appendChild(link);
  title.appendChild(pill(typeName(session, target)));
  if ((session && (session.disabled || []).length)) {
    title.appendChild(pill('命令有 ' + session.disabled.length + ' 条被关掉', 'err'));
  }
  main.appendChild(title);

  const sub = el('div', 'rc-sub');
  sub.textContent = (Number(target.type) === 1 ? '群号 ' : 'QQ 号 ') + target.num;
  main.appendChild(sub);

  const tags = el('div', 'rc-tg');
  for (const item of switches) {
    tags.appendChild(pill(item.displayName + (item.on ? ' 开' : ' 关'), item.on ? 'ok' : ''));
  }
  main.appendChild(tags);
  row.appendChild(main);

  const side = el('div', 'rc-side');
  side.appendChild(pill(templateState(target, store.handlerList).custom ? '模板：自定义' : '模板：默认'));
  const open = el('a', 'lnkbtn');
  open.href = '#/push/' + user.uid + '/' + target.num;
  open.textContent = '打开';
  side.appendChild(open);
  row.appendChild(side);

  return row;
}

// ---- 通道级：四段 ----

function renderChannelLevel(host, user, target) {
  const session = sessionOf(runtime.sessions, target.platform, target.num);
  const name = channelName(session, target, directory);

  const head = card(host, null, null);
  const line = el('div', 'chead');
  const back = el('a', 'lnkbtn');
  back.href = '#/push/' + user.uid;
  back.textContent = '‹ ' + streamerName(user);
  line.appendChild(back);
  const title = el('span', 'ch-nm');
  title.textContent = name;
  line.appendChild(title);
  line.appendChild(pill(typeName(session, target)));
  const num = el('span', 'ch-id');
  num.textContent = (Number(target.type) === 1 ? '群号 ' : 'QQ 号 ') + target.num;
  line.appendChild(num);

  const remove = el('button', 'ghost danger');
  remove.type = 'button';
  remove.textContent = '移除这个通道';
  remove.addEventListener('click', async () => {
    if (!await ask({title: '移除这个通道？',
      body: '不再把「' + streamerName(user) + '」推到 ' + name
        + '。这个群的本群设置会留着，其他主播照旧推。'})) return;
    user.targets.splice(user.targets.indexOf(target), 1);
    markDirty();
    location.hash = '#/push/' + user.uid;
  });
  line.appendChild(remove);
  head.appendChild(line);
  head.appendChild(el('p', 'hint')).textContent =
    '这一页定的是「' + streamerName(user) + ' → ' + name + '」这一条通道。'
    + '同一个群下面还有别的主播时，只有第 4 段「本群设置」是共用的。';

  sectionNotices(host, user, target, session);
  sectionTemplate(host, user, target, session);
  sectionLayout(host, target, session);
  sectionSession(host, user, target, session);
}

/** 段头：一个序号加一句话 */
function sectionHead(host, index, title, desc) {
  const box = el('div', 'nv-card hcard psec');
  const head = el('h3');
  head.innerHTML = '<span class="psec-n">' + index + '</span>' + esc(title);
  box.appendChild(head);
  if (desc) box.appendChild(el('p', 'hint')).textContent = desc;
  host.appendChild(box);
  return box;
}

/** 段 1：推什么 */
function sectionNotices(host, user, target, session) {
  const box = sectionHead(host, 1, '推什么', '这几件事发生时，要不要往这个通道发');
  const switches = noticeSwitches(user, target, store.handlerList);

  if (!switches.length) {
    box.appendChild(el('p', 'hint')).textContent =
      '还没有加载到任何推送处理器，确认对应插件已加载。';
  }

  for (const item of switches) {
    const row = el('div', 'swrow');
    const label = el('label', 'switch');
    label.innerHTML = '<input type="checkbox"' + (item.on ? ' checked' : '') + '>';
    label.querySelector('input').addEventListener('change', event => {
      toggleNotice(target, item.className, event.target.checked);
    });
    row.appendChild(label);

    const text = el('div', 'swtxt');
    text.innerHTML = '<b>' + esc(item.displayName) + '</b>'
      + '<p>' + esc(item.description) + '</p>';
    row.appendChild(text);
    box.appendChild(row);
  }

  const recent = el('h4');
  recent.textContent = '这个通道最近推送';
  box.appendChild(recent);

  const rows = recentPushes(pushHistory, session, 5);
  if (!rows.length) {
    box.appendChild(el('p', 'hint')).textContent = session && session.type
      ? '还没有往这个通道推过东西。'
      : '这个通道还没被加载出来，取不到它的推送记录。';
  } else {
    const table = el('table', 'ptable');
    table.innerHTML = '<thead><tr><th>时间</th><th>内容</th><th>结果</th></tr></thead><tbody>'
      + rows.map(row => '<tr><td>' + esc(row.at) + '</td>'
        + '<td title="' + esc(row.summary) + '">' + esc(row.summary) + '</td>'
        + '<td>' + (row.success ? '<span class="good">成功</span>'
          : '<span class="bad">失败：' + esc(row.reason || '未知原因') + '</span>') + '</td></tr>').join('')
      + '</tbody>';
    box.appendChild(table);
  }

  const more = el('a', 'lnkbtn');
  more.href = '#/log?chan=' + encodeURIComponent(target.num);
  more.textContent = '在日志页看全部 →';
  box.appendChild(more);
}

/**
 * 开关一类通知
 *
 * 勾上＝在这个通道的消息里加一条，取消＝把那条去掉。键形与推送配置里原本的一模一样
 * （只有 handler 一个必填键，其余参数由处理器的默认值补齐），不在这里另造一种写法。
 */
function toggleNotice(target, className, on) {
  target.messages = target.messages || [];
  if (on) {
    // 已经有就不重复添加，以免覆盖掉使用者写过的参数
    if (!messageOf(target, className)) target.messages.push({handler: className});
  } else {
    target.messages = target.messages.filter(item => item.handler !== className);
  }
  markDirty();
  renderStreamers();
}

/**
 * 这个通道此刻解锁了没有
 *
 * 「改为自定义」不写任何东西进配置，它只是<b>把编辑器解锁</b>：真正的分叉发生在
 * 第一次改动落到参数上那一刻。点一下就先写一份与默认一模一样的副本的话，
 * 此后默认再改这个通道会独自停在原地，而使用者只是点了一下「我想改」。
 * 解锁状态因此只活在这一次会话里，不进配置、也不进地址栏。
 */
const unlocked = new Set();

/** 段 2：消息长什么样 */
function sectionTemplate(host, user, target, session) {
  const box = sectionHead(host, 2, '消息长什么样', '一张卡＝一条消息，花括号是可以拖的块');
  // 只列这个通道<b>开着</b>的那几类：关着的那一类改了模板也不会有人收到，
  // 而屏幕上它与改好了长得一样
  const on = noticeSwitches(user, target, store.handlerList)
    .filter(item => item.hasTemplate && item.on)
    .map(item => item.className);
  const handlers = (store.handlerList || []).filter(item => on.includes(item.className));
  const key = channelKeyOf(user, target);
  const state = templateState(target, store.handlerList);
  const editable = state.custom || unlocked.has(key);

  const line = el('div', 'tplstate');
  const label = el('span');
  label.innerHTML = '本通道用的是：<b>' + (state.custom ? '自定义' : '默认模板') + '</b>'
    + (state.custom ? ' · 与默认不同' : ' ✓');
  line.appendChild(label);

  const act = el('button', state.custom ? 'ghost' : 'primary');
  act.type = 'button';
  act.textContent = state.custom ? '恢复默认' : '改为自定义';
  act.addEventListener('click', async () => {
    if (!state.custom) {
      unlocked.add(key);
      renderStreamers();
      return;
    }
    // 「恢复默认」丢得掉使用者写了很久的东西，因此先问一句，并说清丢的是什么
    if (!await ask({title: '恢复默认？',
      body: '这个通道的消息模板会改回默认，自己写的内容会丢掉。'
        + '此后默认模板再改，这个通道跟着一起变。'})) return;
    for (const message of target.messages || []) {
      const handler = (store.handlerList || []).find(item => item.className === message.handler);
      if (!handler || !(handler.placeholders || []).length) continue;
      message.params = restoreDefaults(message.params, handler);
    }
    unlocked.delete(key);
    markDirty();
    renderStreamers();
  });
  line.appendChild(act);

  const toDefault = el('a', 'lnkbtn');
  toDefault.href = '#/push/default';
  toDefault.textContent = '看默认模板';
  line.appendChild(toDefault);
  box.appendChild(line);

  box.appendChild(el('p', 'hint')).textContent =
    '默认模板改一次，所有用默认的通道一起变。某个通道想不一样，就在这里改成自定义。';

  if (state.custom) {
    const which = (store.handlerList || [])
      .filter(item => state.changed.includes(item.className))
      .map(item => item.displayName || item.className);
    box.appendChild(el('div', 'note')).textContent = '与默认不同的是：' + which.join('、');
  }

  buildTemplateEditor(box, {
    handlers,
    editable,
    emptyNote: '这个通道带文字模板的通知一条都没开着。关着的那一类改了模板也不会有人收到，'
      + '因此这里不列——到上面「推什么」里先开一条。',
    paramsOf: handler => (messageOf(target, handler.className) || {}).params || {},
    context: {
      isGroup: Number(target.type) === 1,
      admin: adminOf(target),
    },
    channelLabel: channelName(session, target, directory),
    lockedNote: '这是默认模板的样子。要单独给这个通道改，先点上面的「改为自定义」。',
    onChange: (handler, params) => {
      const message = messageOf(target, handler.className);
      if (!message) return;
      message.params = params;
      markDirty();
      label.innerHTML = '本通道用的是：<b>'
        + (isDefault(params, handler) ? '默认模板' : '自定义') + '</b>';
    },
  });
}

/** 机器人在这个群里是不是管理员。名单取不到时为 null，预览据此不画划掉线 */
function adminOf(target) {
  const known = directory[target.platform + '|' + Number(target.type) + '|' + target.num];
  return known ? known.admin : null;
}

/** 认一个通道用的键，与 push-model 的 channelKey 同一把 */
function channelKeyOf(user, target) {
  return user.platform + '/' + user.uid + '/' + target.platform + ':' + target.num;
}

/** 段 3：报告长什么样。只有开着带版式的那类通知时才出现 */
function sectionLayout(host, target, session) {
  const state = layoutState(target, store.handlerList);
  if (!state.present) return;

  const handler = (store.handlerList || []).find(item => item.className === state.className) || {};
  const box = sectionHead(host, 3, (handler.displayName || '报告') + '长什么样',
    '这一类通知没有文字模板，只有版式：左边调，右边就是发到群里的那张图');
  const key = 'layout:' + target.platform + ':' + target.num + ':' + state.className;
  const editable = state.custom || unlocked.has(key);

  const line = el('div', 'tplstate');
  const label = el('span');
  label.innerHTML = '本通道用的是：<b>' + (state.custom ? '自定义版式' : '默认版式') + '</b>'
    + (state.custom ? ' · 与默认不同' : ' ✓');
  line.appendChild(label);

  const act = el('button', state.custom ? 'ghost' : 'primary');
  act.type = 'button';
  act.textContent = state.custom ? '恢复默认' : '改为自定义';
  act.addEventListener('click', async () => {
    if (!state.custom) {
      unlocked.add(key);
      renderStreamers();
      return;
    }
    if (!await ask({title: '恢复默认？',
      body: '这个通道的报告版式会改回默认。此后默认版式再改，这个通道跟着一起变。'})) return;
    const message = messageOf(target, state.className);
    if (message) {
      const params = Object.assign({}, message.params || {});
      for (const option of handler.options || []) delete params[option.key];
      message.params = params;
    }
    unlocked.delete(key);
    markDirty();
    renderStreamers();
  });
  line.appendChild(act);
  box.appendChild(line);

  buildLayoutEditor(box, {
    editable,
    lockedNote: '这是默认版式的样子。要单独给这个通道改，先点上面的「改为自定义」。',
    items: handler.options || [],
    paramsOf: () => (messageOf(target, state.className) || {}).params || {},
    caption: previewRevenueCaption(session, target),
    onChange: params => {
      const message = messageOf(target, state.className);
      if (!message) return;
      message.params = params;
      markDirty();
      label.innerHTML = '本通道用的是：<b>'
        + (layoutState(target, store.handlerList).custom ? '自定义版式' : '默认版式') + '</b>';
    },
    render: params => renderLayoutPreview(params, target),
  });
}

/**
 * 按当前这套版式画一张
 *
 * 图由服务端画，与真出报告读的是同一段解析码：各画各的话，预览会在
 * 「越界值怎么夹」「缺项取什么默认」这些地方悄悄给出与实际不同的图。
 * 请求体带上当前通道，服务端按这个群的「金额可见」画。
 * @param params 版式参数
 * @param target 当前通道
 * @return 图片地址，画不出来时为空
 */
let previewUrl = null;
async function renderLayoutPreview(params, target) {
  try {
    const res = await fetch('/config/api/report/preview', {
      method: 'POST',
      headers: Object.assign({'Content-Type': 'application/json'},
        store.csrfToken ? {'X-CSRF-Token': store.csrfToken} : {}),
      body: JSON.stringify(previewRequestBody(params, target)),
    });
    if (!res.ok) return '';
    // 上一张画完就没用了。不撤销的话，来回调开关几十次会把几十张图一直挂在内存里
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = URL.createObjectURL(await res.blob());
    return previewUrl;
  } catch (e) {
    return '';
  }
}

/** 段 4：本群设置 */
function sectionSession(host, user, target, session) {
  const box = sectionHead(host, 4, '本群设置',
    Number(target.type) === 1 ? '这个群自己的事，和推谁无关' : '这位好友自己的事');

  if (Number(target.type) === 1) {
    const streamers = (session || {}).streamers || [];
    box.appendChild(el('div', 'note')).textContent = streamers.length > 1
      ? '本群设置对推给它的所有主播共用。这个群下面有 ' + streamers.length + ' 位主播（'
        + streamers.join('、') + '），在这里改一次是改全部。'
      : '本群设置对推给它的所有主播共用。';
  }

  sessionSettings(box, {
    session, target,
    commands: runtime.commands || [],
    subscriptions: runtime.subscriptions || [],
    totalDataAvailable: runtime.totalDataAvailable,
    summary: {
      revenue: revenueSummary(session),
      command: commandSummary(runtime.commands || [], session, runtime.totalDataAvailable),
      subscription: subscriptionSummary(runtime.subscriptions || [], session),
      atAll: atAllStatus(quota, session, target, directory),
    },
    groups: commandGroups(runtime.commands || [], session, runtime.totalDataAvailable),
  });
}

// ============ 抽屉 ============

/**
 * 摊开一个只问一件事的面板
 * @param title 标题
 * @param lead 一句说明
 * @param build 往面板正文里建内容
 */
export function openDrawer(title, lead, build) {
  $('#push-drawer-title').textContent = title;
  $('#push-drawer-lead').textContent = lead || '';
  const body = $('#push-drawer-body');
  body.innerHTML = '';
  build(body);
  $('#push-drawer').hidden = false;
}

export function closeDrawer() {
  $('#push-drawer').hidden = true;
  $('#push-drawer-body').innerHTML = '';
}

/**
 * 从机器人已经加入的群、已经加的好友里挑
 *
 * 🔴 <b>没有手填号码的格子。</b>填错一位数不会有任何报错，消息只是发去了别处，
 * 而「配好了群里没动静」这几类错的表现完全一样。认出来的那一条走 resolveTarget，
 * 与「发一条试试」是同一份判法——那一份有 node 夹具在量（含四例手填的反面对照）。
 * @param opts 标题、说明、已经是通道的那些（灰掉）、挑中之后做什么
 */
function pickTarget(opts) {
  openDrawer(opts.title, opts.lead, body => {
    const bar = el('div', 'dwbar');
    const search = el('input');
    search.type = 'search';
    search.placeholder = '搜群名、群号、好友昵称';
    search.setAttribute('aria-label', '搜索可选的群与好友');
    bar.appendChild(search);

    const refresh = el('button', 'ghost');
    refresh.type = 'button';
    refresh.textContent = '刷新';
    refresh.addEventListener('click', async () => {
      refresh.disabled = true;
      try {
        await api('/onebot/targets/refresh', {method: 'POST'});
        await loadPushPage();
        pickTarget(opts);
      } catch (e) {
        say('刷新名单失败：' + e.message, 'err');
      }
      refresh.disabled = false;
    });
    bar.appendChild(refresh);
    body.appendChild(bar);

    const list = el('div', 'dwlist');
    body.appendChild(list);

    const paint = () => {
      const keyword = search.value.trim().toLowerCase();
      list.innerHTML = '';

      if (!options.length) {
        list.appendChild(el('p', 'hint')).textContent =
          '取不到机器人的群与好友名单。机器人此刻多半没连上，到「连接」页看一眼。';
        return;
      }

      const rows = options.filter(item => !keyword
        || String(item.num).includes(keyword)
        || (item.name || '').toLowerCase().includes(keyword));
      if (!rows.length) {
        list.appendChild(el('p', 'hint')).textContent = '没有匹配的群或好友。';
        return;
      }

      for (const item of rows) {
        const taken = (opts.taken || []).includes(item.key);
        const row = el('button', 'dwrow' + (taken ? ' taken' : ''));
        row.type = 'button';
        row.disabled = taken;
        row.innerHTML = '<span class="dw-nm">' + esc(item.name || item.num) + '</span>'
          + '<span class="dw-sub">' + (item.type === 1 ? '群 ' : 'QQ ') + esc(item.num)
          + (item.memberCount ? ' · ' + esc(item.memberCount) + ' 人' : '')
          + (item.admin ? ' · 机器人是管理员' : '') + '</span>'
          + (taken ? '<span class="dw-tag">已是通道</span>' : '');
        row.addEventListener('click', () => {
          const hit = resolveTarget(options, item.key);
          if (!hit.ok) {
            say(hit.reason, 'err');
            return;
          }
          closeDrawer();
          opts.onPick(hit);
        });
        list.appendChild(row);
      }
    };

    search.addEventListener('input', paint);
    paint();
  });
}

/** 给某位主播添加一个通道 */
function addChannel(user) {
  const taken = (user.targets || [])
    .map(item => item.platform + '|' + Number(item.type) + '|' + item.num);

  pickTarget({
    title: '给「' + streamerName(user) + '」添加通道',
    lead: '从机器人已经加入的群、已经加的好友里挑。已经是通道的灰着。',
    taken,
    onPick: hit => {
      user.targets = user.targets || [];
      // 新通道默认四种通知全开、全用默认模板：这与初始设置第 4 步给的是同一份起点
      user.targets.push({
        platform: hit.platform, type: hit.type, num: hit.num, enabled: true,
        messages: (store.handlerList || [])
          .filter(item => !item.platform || item.platform === user.platform)
          .map(item => ({handler: item.className})),
      });
      markDirty();
      say('已添加通道，保存后生效', 'ok');
      location.hash = '#/push/' + user.uid + '/' + hit.num;
      renderStreamers();
    },
  });
}

// ============ 添加主播 ============

/**
 * 添加一位主播
 *
 * 平台由已注册的数据源服务决定：一个都没有就把这件事停掉并写明原因，只有一个就默认它，
 * 有多个才让人挑。写死一个平台名的话，没装那个插件时按钮照样点得动，
 * 点完才在服务端被回一句「没有可用的数据源服务」。
 */
export function addStreamer() {
  const platformNames = store.platforms || [];

  openDrawer('添加主播', '加完之后给它配至少一个通道，才会有人收到消息。', body => {
    if (!platformNames.length) {
      body.appendChild(el('p', 'hint')).textContent =
        '没有加载任何直播平台插件，无法添加主播。';
      return;
    }

    const form = el('div', 'dwform');
    if (platformNames.length > 1) {
      const pick = el('select');
      pick.id = 'add-platform';
      pick.setAttribute('aria-label', '哪个平台的主播');
      pick.innerHTML = platformNames.map(name =>
        '<option value="' + esc(name) + '">' + esc(name) + '</option>').join('');
      form.appendChild(pick);
    }

    const input = el('input');
    input.id = 'add-uid';
    input.placeholder = '输入 ' + STREAMER_INPUT_HINT;
    input.setAttribute('aria-label', '主播 uid、直播间号或链接');
    form.appendChild(input);

    const go = el('button', 'primary');
    go.id = 'add-streamer';
    go.type = 'button';
    go.textContent = '找一下';
    form.appendChild(go);
    body.appendChild(form);

    const out = el('div', 'dwout');
    body.appendChild(out);

    const submit = () => lookupStreamer(platformNames, input, go, out);
    go.addEventListener('click', submit);
    input.addEventListener('keydown', event => { if (event.key === 'Enter') submit(); });
    input.focus();
  });
}

async function lookupStreamer(platformNames, input, go, out) {
  const platform = platformNames.length > 1
    ? ($('#add-platform') || {}).value || platformNames[0]
    : platformNames[0];
  const value = input.value.trim();
  if (!value) {
    out.textContent = '请先输入 ' + STREAMER_INPUT_HINT + '。';
    return;
  }

  go.disabled = true;
  out.textContent = '查询中…';
  try {
    // 先把昵称显示出来让人确认，避免 uid 打错一位却配了个陌生人
    const res = await api('/streamer/lookup', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform, uid: value}),
    });

    if (!res.success) {
      out.textContent = res.message;
      return;
    }
    if ((store.pushData || []).some(item => Number(item.uid) === Number(res.uid))) {
      out.textContent = '主播 ' + res.uname + ' 已经在列表里了。';
      return;
    }

    out.innerHTML = '';
    const found = el('div', 'dwfound');
    found.innerHTML = '<b>' + esc(res.uname) + '</b><span>uid ' + esc(res.uid)
      + (res.roomId ? ' · 直播间 ' + esc(res.roomId) : '') + '</span>';
    const add = el('button', 'primary');
    add.type = 'button';
    add.textContent = '就是它，加进来';
    add.addEventListener('click', () => {
      store.pushData.push({
        uid: res.uid, platform, enabled: true, targets: [],
        _uname: res.uname, _roomId: res.roomId, _face: res.face,
      });
      closeDrawer();
      markDirty();
      say('已添加 ' + res.uname + '，请为它配置通道后保存', 'ok');
      expanded.add(String(res.uid));
      location.hash = '#/push/' + res.uid;
      renderStreamers();
    });
    found.appendChild(add);
    out.appendChild(found);
  } catch (e) {
    out.textContent = '查询失败：' + e.message;
  } finally {
    go.disabled = false;
  }
}

// ============ 存盘与补全 ============

/**
 * 下划线开头的字段仅供界面展示（昵称、头像等），不应写进配置文件。
 *
 * 「哪些字段只是给人看的」这条规则只有 dropDisplayOnly 一份：写两份的话，
 * 改动计数与真正写盘的内容会按两套规则算，于是「有改动却存不出东西」这种事没人查得出来
 */
export function serializePush() {
  return JSON.stringify(store.pushData, dropDisplayOnly, 2);
}

/**
 * 配置文件里只有 uid，昵称要另行补全才显示得出来
 */
export async function decoratePushData() {
  await Promise.all((store.pushData || []).map(async user => {
    // 补昵称要靠对应平台的数据源服务，没注册的平台查不了，照 uid 显示就是
    if (user._uname || !(store.platforms || []).includes(user.platform)) return;
    try {
      const res = await api('/streamer/lookup', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({platform: user.platform, uid: String(user.uid)}),
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

/**
 * 把被群管理员关掉的命令一起改回来
 * @param platform 推送平台
 * @param num 会话号
 * @param names 命令名
 */
export async function restoreCommands(platform, num, names) {
  try {
    const res = await api('/state/commands', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform, num, commands: names, disabled: false}),
    });
    say(res.message || (res.success ? '已恢复' : '操作失败'), res.success ? 'ok' : 'err');
    if (res.success) await loadPushPage();
  } catch (e) {
    say('操作失败：' + e.message, 'err');
  }
}

// ============ 接线 ============

$('#push-nav').addEventListener('change', event => {
  const value = event.target.value;
  if (!value) { location.hash = '#/push'; return; }
  if (value === 'default') { location.hash = '#/push/default'; return; }
  const parts = value.split(':');
  location.hash = parts[0] === 's' ? '#/push/' + parts[1] : '#/push/' + parts[1] + '/' + parts[2];
});
$('#push-add-streamer').addEventListener('click', addStreamer);
$('#push-drawer-close').addEventListener('click', closeDrawer);
// 点面板外面就关：面板盖住整页，没有这一下就只剩右上角那一个出口
$('#push-drawer').addEventListener('click', event => {
  if (event.target === $('#push-drawer')) closeDrawer();
});
