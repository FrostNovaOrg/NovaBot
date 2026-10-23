import {notifyRevenueChange} from './session-draft.js';
/**
 * 通道页下半截：「通道规则」（金额可见、静音时段）、「群内命令」与「本群状态」里的
 * @全体成员 与提醒订阅
 *
 * 这几块讲的是「这个群此刻听不听话」，与上方「推送内容」的「往这个群推什么」是同一件事的两面，
 * 因此摆在同一页上——分成两页时，「配好了却不响应」这种情况要在两个入口之间来回对照
 * 才看得出来。金额可见、静音时段、命令开关每条通道各一份，只作用于这位主播在这个群的推送和命令；
 * @全体成员 仍是本群各主播共用，只读显示。
 *
 * 规则、命令、只读状态分三块放：常改的两条规则默认摊开，命令默认收起，只读的另起一块。
 * 混在一段里时，看一眼「这个群静不静音」要先翻过十几个命令开关。
 *
 * 改动与上方推送内容同一条保存纪律：先记进草稿、计入底部改动条，按「保存」才落盘，
 * 按「放弃」全部还原。从前是「拨一下就发请求」，于是同一页上并存两套相反的规矩，
 * 而「放弃」撤不回这几项。
 *
 * 摘要那几行写什么由 push-model.js 算（见 tools/push-model-check.sh 的各档），
 * 本文件只管把算好的摆上去，以及把改动记进草稿。写盘走 push.js 的 save，
 * 与推送配置那份草稿在同一趟里发出。
 */

import {$, el, esc, markDirty} from './core.js';
import {openDrawer} from './push.js';
import {ask} from './confirm.js';
import {
  commandDraft, isClearPending, isUserPendingRemove, quietDraft, revenueDraft, setCommandDraft,
  setCommandsDraft, setQuietDraft, setRevenueDraft, setSubscriptionClearDraft,
  setSubscriptionRemoveDraft,
} from './session-draft.js';

/**
 * 各栏目此刻摊开着的那些
 *
 * 这几行要连着点几下（选档、改起止、拨开关），而每点一下这一段都整段重画；
 * 不记住的话，每改一处它就自己收起来一次。切到别的通道再回来也照旧摊着——
 * 记住的是「这个会话的哪一行摊着」，不是某一位主播下的哪一行：这一段本来就是群共用的。
 */
const foldOpen = new Set();

/** 这个会话的哪一行摊着：会话身份 + 行名。同一群下的几位主播共用一份 */
function foldKey(ctx, name) {
  return ctx.target.platform + ':' + ctx.target.type + ':' + ctx.target.num + ':' + name;
}

/** 折叠项记住摊开与否 */
function keepFold(box, ctx, name) {
  const key = foldKey(ctx, name);
  box.open = foldOpen.has(key);
  box.addEventListener('toggle', () => {
    if (box.open) foldOpen.add(key);
    else foldOpen.delete(key);
  });
}

/** 本页刚画完的那一次重画：草稿变了要连摘要与开关一起对齐，否则屏幕上留着拨过的空壳 */
let repaintSession = () => {};

/**
 * 推送配置里没填完的条目
 *
 * 后端已经说清是哪一条、还差哪个字段，这里照原话显示、不再自己拼一遍：
 * 同一句话两边各写一份，改了一处忘了另一处的时候，界面说的就不是真事了。
 */
export function renderIncomplete(entries) {
  const box = $('#sess-incomplete');

  if (!entries.length) {
    box.innerHTML = '';
    return;
  }

  box.innerHTML = '<div class="notice"><b>推送配置尚未填写完整</b>，以下条目暂未生效，'
    + '因此不会显示在下方的树中：<ul>'
    + entries.map(e => '<li>' + esc(e.message) + '</li>').join('')
    + '</ul></div>';
}

/**
 * 通道规则、命令与只读状态那几块
 *
 * 两条规则（金额可见、静音时段）默认摊开：它们各只占一行，又是这条通道最常单独调的两件事。
 * 命令默认收起：十几个开关摊开要占大半屏，而它们难得一改。
 * 私聊没有 @全体成员，群成员自己订阅「@我」也只在群里有，好友通道不画这两行：
 * 画一行「私聊不支持」，读起来像这里还有一份额度。
 * @param host 往哪儿放；ctx.hosts 分别给了三块的容器时，三块各进各的
 * @param ctx 这个会话的全部事实与算好的摘要，见 push.js 的 channelSettings
 */
export function sessionSettings(host, ctx) {
  const hosts = ctx.hosts || {rules: host, commands: host, status: host};
  // 重画是「换掉」，不是「再摆一份」：每块收的都是自己的容器，先清再画。
  // 不清的话，草稿每拨一下这一段就长出一倍，而摘要行的字还是旧的
  repaintSession = () => {
    for (const box of new Set([host, hosts.rules, hosts.commands, hosts.status])) box.innerHTML = '';
    if (ctx.repaint) ctx.repaint(); else sessionSettings(host, ctx);
  };
  revenueRow(hosts.rules, ctx);
  quietRow(hosts.rules, ctx);
  commandRow(hosts.commands, ctx);
  if (Number(ctx.target.type) === 1) {
    atAllRow(hosts.status, ctx);
    subscriptionRow(hosts.status, ctx);
  }
}

/** 草稿变了：先把这一段按「服务端 + 草稿」重画，再让宿主改动条那个 N 自己重算 */
function refresh() {
  repaintSession();
  markDirty();
}

/**
 * 一行可折叠的摘要，标题末尾带一颗问号说明
 * @param helpText 这一行的详细说明，收在问号里；给空就不画那颗问号
 * @return {HTMLElement} 正文容器
 */
function row(host, ctx, title, summary, helpText) {
  const box = el('details', 'crow');
  keepFold(box, ctx, title);
  const head = el('summary');
  head.innerHTML = '<span class="cr-t">' + esc(title) + '</span>'
    + '<span class="cr-sum">' + esc(summary) + '</span>';
  if (helpText) head.appendChild(helpButton(title, helpText));
  box.appendChild(head);
  const body = el('div', 'cr-body');
  box.appendChild(body);
  host.appendChild(box);
  return body;
}

/**
 * 一行摊开的规则或状态：标题、摘要与问号在头一行，控件紧跟在下面
 *
 * 与 row 同一套标题与摘要的写法，只是不折：规则就两条，折起来反倒多一下点击。
 * @param kind 额外的类名，状态行给 cstat
 * @return {HTMLElement} 正文容器
 */
function rule(host, title, summary, helpText, kind) {
  const box = el('div', 'crule' + (kind ? ' ' + kind : ''));
  const head = el('div', 'crule-h');
  head.innerHTML = '<span class="cr-t">' + esc(title) + '</span>'
    + '<span class="cr-sum">' + esc(summary) + '</span>';
  if (helpText) head.appendChild(helpButton(title, helpText));
  box.appendChild(head);
  const body = el('div', 'crule-b');
  box.appendChild(body);
  host.appendChild(box);
  return body;
}

// ---- 问号说明 ----
//
// 详细解释从前摊在段里，占掉小半屏还把开关挤到下面去。挪进问号里之后，
// 想看的人点开看，不想看的人一眼只看见开关。

/** 全页共用的一块说明浮层：同一时刻只开一个，换一行说明就换一段字 */
let helpTip = null;
let helpKeysBound = false;

/** 此刻开着的那块说明怎么收起来；没开着时是空 */
let closeHelp = null;
let activeHelpButton = null;

/** 滚动后仍贴着问号；按钮已移除或滚出视口时收起 */
function placeHelp() {
  if (!activeHelpButton || !helpTip || helpTip.hidden) return;
  const rect = activeHelpButton.getBoundingClientRect();
  if (!activeHelpButton.isConnected || rect.bottom < 0 || rect.top > window.innerHeight
      || rect.right < 0 || rect.left > window.innerWidth) {
    closeHelp();
    return;
  }
  const inset = 8;
  const width = helpTip.offsetWidth || 280;
  const below = rect.bottom + 6;
  const top = below + helpTip.offsetHeight <= window.innerHeight - inset
    ? below : Math.max(inset, rect.top - helpTip.offsetHeight - 6);
  helpTip.style.position = 'fixed';
  helpTip.style.left = Math.max(inset, Math.min(rect.left, window.innerWidth - width - inset)) + 'px';
  helpTip.style.top = top + 'px';
}

function tipNode() {
  if (!helpTip) {
    helpTip = el('div', 'row-help-tip');
    helpTip.id = 'row-help-tip';
    helpTip.setAttribute('role', 'tooltip');
    helpTip.hidden = true;
    document.body.appendChild(helpTip);
  }
  return helpTip;
}

function bindHelpKeys() {
  if (helpKeysBound) return;
  helpKeysBound = true;
  if (typeof document.addEventListener !== 'function') return;
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && closeHelp) closeHelp();
  });
  document.addEventListener('pointerdown', event => {
    if (activeHelpButton && !activeHelpButton.contains(event.target)
        && !helpTip.contains(event.target)) closeHelp();
  });
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('scroll', placeHelp, {capture: true, passive: true});
    window.addEventListener('resize', placeHelp);
    window.addEventListener('hashchange', () => { if (closeHelp) closeHelp(); });
  }
  if (typeof MutationObserver !== 'undefined') {
    const observer = new MutationObserver(() => {
      if (activeHelpButton && !activeHelpButton.isConnected) closeHelp();
    });
    observer.observe(document.body, {childList: true, subtree: true});
  }
}

/**
 * 一行末尾那颗问号
 *
 * 键盘走到就摊开、走开或按 Esc 收起；手机上点一下钉住、再点一下收起。
 * 按钮长在折叠项的标题里，点它这一下不能顺手把整行也拨了。
 */
function helpButton(title, text) {
  const btn = el('button', 'row-help');
  btn.type = 'button';
  btn.textContent = '?';
  btn.setAttribute('aria-label', title + '：查看说明');
  btn.setAttribute('aria-controls', 'row-help-tip');
  btn.setAttribute('aria-expanded', 'false');

  let pinned = false;

  const hide = () => {
    pinned = false;
    if (closeHelp !== hide) return;
    closeHelp = null;
    activeHelpButton = null;
    btn.setAttribute('aria-expanded', 'false');
    btn.removeAttribute('aria-describedby');
    tipNode().hidden = true;
  };

  const show = () => {
    if (closeHelp && closeHelp !== hide) closeHelp();
    closeHelp = hide;
    activeHelpButton = btn;
    const tip = tipNode();
    btn.setAttribute('aria-expanded', 'true');
    btn.setAttribute('aria-describedby', tip.id);
    tip.textContent = text;
    tip.hidden = false;
    placeHelp();
  };

  btn.addEventListener('focus', () => {
    if (!pinned) show();
  });
  btn.addEventListener('blur', () => {
    if (!pinned) hide();
  });
  btn.addEventListener('click', event => {
    if (typeof event.preventDefault === 'function') event.preventDefault();
    if (typeof event.stopPropagation === 'function') event.stopPropagation();
    if (pinned) hide();
    else {
      pinned = true;
      show();
    }
  });
  bindHelpKeys();
  return btn;
}

// ---- 1 金额可见 ----

function revenueRow(host, ctx) {
  const wanted = ctx.channel ? undefined : revenueDraft(ctx.target);
  const visible = wanted === undefined ? ctx.summary.revenue.visible : wanted;
  const summary = wanted !== undefined && wanted !== ctx.summary.revenue.visible
    ? (visible ? '显示金额' : '隐藏金额') + ' · 未保存' : ctx.summary.revenue.text;
  const body = rule(host, '金额可见', summary,
    '金额榜整榜不显示，而不是只隐去数字：这几张榜的每一行本质上都是「某人花了多少钱」，'
    + '只去掉右侧的数字，仍然是在公开排列消费。');

  const line = el('div', 'swrow rule-switch');
  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (visible ? ' checked' : '') + '>';
  const input = label.querySelector('input');
  input.setAttribute('aria-label', '直播收益等金额');
  input.addEventListener('change', () => {
    // 期望态与服务端那一份比：拨过去又拨回来的那几下自己会消失
    if (ctx.channel) {
      const base = ctx.baseline || {};
      const original = base.revenueVisible ?? (Number(ctx.target.type) === 0);
      if (input.checked === original) restoreChannelField(ctx, 'revenueVisible');
      else ctx.target.revenueVisible = input.checked;
      notifyRevenueChange();
    } else setRevenueDraft(ctx.target, input.checked, ctx.summary.revenue.visible);
    refresh();
  });
  line.appendChild(label);

  const text = el('div', 'swtxt');
  text.innerHTML = '<b>显示金额</b><p>关闭后，下播报告不显示收益，卡片改用人数与条数，'
    + '礼物与醒目留言榜整榜不显示；数据查询命令同样不再返回金额。'
    + (ctx.summary.revenue.explicit ? '' : '（当前为默认值：'
      + (Number(ctx.target.type) === 0 ? '私聊显示' : '群聊隐藏、私聊显示') + '）') + '</p>';
  line.appendChild(text);
  body.appendChild(line);
}

// ---- 2 静音时段 ----

/** 选「本通道自己的时段」又从没填过时，起止先借全局那一对，全局也没设就给一对常见的 */
const QUIET_FALLBACK = {start: '23:00', end: '08:00'};

const QUIET_MODES = [
  {mode: 'follow', label: '使用全局设置', hint: '使用设置页中的全局静音时段。未单独设置过的通道均为此项。'},
  {mode: 'custom', label: '本通道自定义时段', hint: '这条通道按上方的起止时间静音，不受全局静音时段影响。可跨零点。'},
  {mode: 'off', label: '本通道不静音', hint: '全局处于静音时，推往这条通道的消息照常发送。'},
];

function quietRow(host, ctx) {
  const summary = ctx.summary.quiet;
  const body = rule(host, '静音时段', summary.text,
    '静音期间推往这条通道的开播、下播、报告、动态与命令回复将直接丢弃，不会补发，'
    + '时间线上记为「静音丢弃」。运营周报除外：顺延到静音结束后发送。开始与结束时间不能相同，相同时保存会被拒绝；'
    + '如需全天照常发送，请选择「本通道不静音」。设置页的全局推送开关关闭时一律不发送；告警不受静音影响。');

  const saved = {mode: summary.mode, start: summary.start, end: summary.end};
  const wanted = (ctx.channel ? null : quietDraft(ctx.target)) || saved;
  const global = ctx.quietGlobal || {};

  /** 期望态与服务端那一份比：选过去又选回来的那几下自己会消失 */
  const put = to => {
    if (ctx.channel) {
      const base = ctx.baseline?.quietHours || {mode: 'follow'};
      if (to.mode === base.mode && (to.mode !== 'custom' || to.start === base.start && to.end === base.end)) {
        restoreChannelField(ctx, 'quietHours');
      } else if (to.mode === 'follow') delete ctx.target.quietHours;
      else ctx.target.quietHours = to;
    } else setQuietDraft(ctx.target, to, saved);
    refresh();
  };

  // 三档排成一行；选中那一档的说明写在下面一行，不三句一齐摊开
  const modes = el('div', 'quiet-modes');
  modes.setAttribute('role', 'radiogroup');
  modes.setAttribute('aria-label', '静音时段');
  const name = 'quiet-' + foldKey(ctx, '静音时段');
  for (const item of QUIET_MODES) {
    const line = el('label', 'quiet-mode');
    const input = el('input');
    input.type = 'radio';
    input.name = name;
    input.checked = wanted.mode === item.mode;
    input.setAttribute('aria-label', item.label);
    input.addEventListener('change', () => {
      if (!input.checked) return;
      if (item.mode !== 'custom') {
        put({mode: item.mode});
        return;
      }
      const start = wanted.start || saved.start || global.start || QUIET_FALLBACK.start;
      const end = wanted.end || saved.end || global.end || QUIET_FALLBACK.end;
      put({mode: 'custom', start, end});
    });
    line.appendChild(input);
    line.appendChild(el('span')).textContent = item.label;
    modes.appendChild(line);
  }
  body.appendChild(modes);

  // 起止只在选了「自定义」时出现，紧挨在三档下面：没选自定义时摆着两个填不了的框，
  // 读起来像是另外两档也要填时间
  if (wanted.mode === 'custom') {
    const times = el('div', 'session-quiet-times');
    for (const [field, label] of [['start', '静音开始'], ['end', '静音结束']]) {
      const input = el('input');
      input.type = 'time';
      input.value = wanted[field] || '';
      input.setAttribute('aria-label', label);
      input.addEventListener('change', () => {
        // 时间框清空或半截时不进草稿：存下一对缺了一头的起止，只会得到一档看似设了、其实不生效的记录
        if (!/^\d{2}:\d{2}$/.test(input.value)) return;
        put(Object.assign({}, wanted, {mode: 'custom', [field]: input.value}));
      });
      times.appendChild(el('span', 'dim')).textContent = label === '静音开始' ? '从' : '到';
      times.appendChild(input);
    }
    body.appendChild(times);
  }

  const chosen = QUIET_MODES.find(item => item.mode === wanted.mode) || QUIET_MODES[0];
  body.appendChild(el('p', 'rule-note')).textContent = chosen.hint;
}

// ---- 3 命令 ----

/**
 * 命令那一块：默认收起，展开先见类别开关，逐条调整要再点开那一类
 *
 * 这个会话里用不上的命令（菜单里本来就不列的）不跟着各类画成一个个淡开关，
 * 统一收进末尾「不适用 n 条」：淡下去的开关看起来仍像点得动。
 */
function commandRow(host, ctx) {
  const group = Number(ctx.target.type) === 1;
  const summary = ctx.summary.command;
  const body = row(host, ctx, group ? '群内命令' : '私聊命令',
    summary.text + (group ? ' · 群聊中需先 @机器人' : ' · 私聊直接发送'),
    group
      ? '群成员需先 @机器人再发送命令，例如「@机器人 直播报告」。@机器人但内容不是命令时，机器人回复菜单；'
        + '点名的主播在本群关了这条命令时，回复「本群已关闭「主播名」的「命令名」」；没点名、且本群各主播都关了这条命令，或群管理员关了插件命令时，'
        + '回复「本群已关闭「命令名」命令」。未 @机器人的消息一律不处理，同一群每 3 秒最多响应一次。'
      : '私聊直接发送命令名，不是命令时回复菜单。未配置推送的会话一律不响应。');

  if (summary.offNames.length) {
    const warn = el('div', 'note n-err');
    warn.appendChild(document.createTextNode(
      '群主或群管理员在群里发送「禁用命令」禁用了：' + summary.offNames.join('、')
      + '。禁用后有人点名这位主播发送这些命令，机器人会回复「本群已关闭「主播名」的「命令名」」；'
      + '没点名、且本群各主播都禁用了时，回复「本群已关闭「命令名」命令」。'));
    const fix = el('button', 'ghost');
    fix.type = 'button';
    fix.textContent = '全部恢复';
    fix.addEventListener('click', async () => {
      if (!await ask({title: '全部恢复？',
        body: '将「' + summary.offNames.join('、') + '」在 ' + ctx.target.num
          + ' 恢复为可用。群管理员之后仍可再次禁用。'})) return;
      batchCommands(ctx, summary.restorable, false);
    });
    warn.appendChild(fix);
    body.appendChild(warn);
  }

  if (summary.strayNames.length) {
    const stray = el('div', 'note');
    stray.appendChild(document.createTextNode(
      '状态文件中仍保留以下命令的禁用记录，但这些命令已不存在（多为已改名或已删除），'
      + '保留也不会生效：' + summary.strayNames.join('、') + '。'));
    const clean = el('button', 'ghost');
    clean.type = 'button';
    clean.textContent = '清除记录';
    clean.addEventListener('click', () => batchCommands(ctx, summary.strayNames, false));
    stray.appendChild(clean);
    body.appendChild(stray);
  }

  if (!summary.menuKnown) {
    body.appendChild(el('div', 'note n-warn')).textContent =
      '此会话不在推送配置中，无法获取菜单状态：以下各行仅按本机整体能力标注，'
      + '不代表群里实际列出的命令。';
  }

  const unusable = [];
  for (const item of ctx.groups) {
    const usable = item.commands.filter(command => command.listed);
    unusable.push(...item.commands.filter(command => !command.listed));
    if (usable.length) body.appendChild(commandGroup(ctx, item, usable));
  }
  if (unusable.length) body.appendChild(unusableGroup(ctx, unusable));
}

/** 这条命令此刻还答不答得出话：至少一格本机用得上、又没被关 */
function commandUsable(ctx, command) {
  return (command.usages || []).some(usage => usage.available && !effectiveUsageOff(ctx, usage));
}

/** 「不适用 n 条」：只列名字与为什么，不画开关 */
function unusableGroup(ctx, commands) {
  const box = el('details', 'cgroup cmd-na');
  keepFold(box, ctx, '命令/不适用');
  const head = el('summary', 'swrow');
  head.appendChild(el('span', 'cmdslot'));
  const text = el('div', 'swtxt');
  text.innerHTML = '<b>不适用 <span class="dim">' + commands.length + ' 条</span></b>'
    + '<p>在这个会话里用不上，菜单中也不显示。</p>';
  head.appendChild(text);
  box.appendChild(head);
  const list = el('div', 'cfine');
  for (const command of commands) {
    const line = el('div', 'swrow dimmed');
    line.appendChild(el('span', 'cmdslot'));
    const why = el('div', 'swtxt');
    why.innerHTML = '<b>' + esc(command.name) + '</b>'
      + (command.hiddenReason ? '<span class="cmdmark">' + esc(command.hiddenReason) + '</span>' : '')
      + '<p>' + esc(command.description) + '</p>';
    line.appendChild(why);
    list.appendChild(line);
  }
  box.appendChild(list);
  return box;
}

/** 这一格此刻「想关掉吗」：草稿优先，没改过才认服务端那一份 */
function effectiveUsageOff(ctx, usage) {
  const wanted = ctx.channel ? undefined : commandDraft(ctx.target, usage.key);
  return wanted === undefined ? usage.off : wanted;
}

function commandGroup(ctx, group, commands) {
  // 分类自己就是一层折叠：点标题即开即收，不必先找「细调」才看得见这一类的开关
  const box = el('details', 'cgroup');
  keepFold(box, ctx, '命令/' + group.category);
  // 这一组每一条都不可关闭时，没有开关可画。画一个关着且拨不动的，
  // 看起来像整组被关掉了。与单条命令同一写法：开关位留空位，「不可关闭」写进文字列的标记里。
  const noneClosable = commands.length > 0 && commands.every(command => !command.disableable);

  const head = el('summary', 'swrow');
  if (noneClosable) {
    head.appendChild(el('span', 'cmdslot'));
  } else {
    // 组开关按格起算：一格关着、另一格还开的半开组不算全开
    const cells = group.cells || [];
    const allOn = cells.length > 0 && cells.every(usage => !effectiveUsageOff(ctx, usage));
    const label = el('label', 'switch');
    label.innerHTML = '<input type="checkbox"' + (allOn ? ' checked' : '') + '>';
    const input = label.querySelector('input');
    input.setAttribute('aria-label', group.category);
    input.disabled = !group.switchable.length;
    input.addEventListener('change', () => batchCommands(ctx, group.switchable, !input.checked));
    head.appendChild(label);
  }

  const text = el('div', 'swtxt');
  const marks = [];
  if (noneClosable) marks.push('不可关闭');
  const usable = commands.filter(command => commandUsable(ctx, command)).length;
  text.innerHTML = '<b>' + esc(group.category) + ' <span class="dim">'
    + usable + ' 条可用</span></b>'
    + (marks.length ? '<span class="cmdmark">' + esc(marks.join(' · ')) + '</span>' : '');
  // 这一类对谁生效：拨一下是这个群（或这位好友）自己的事，不是某一位主播下的事
  text.appendChild(el('span', 'cmdscope')).textContent =
    ctx.channel ? '当前主播通道' : (Number(ctx.target.type) === 1 ? '当前群共用' : '当前会话共用');
  head.appendChild(text);
  head.appendChild(el('span', 'cg-more')).textContent = '逐条调整';
  box.appendChild(head);

  const list = el('div', 'cfine');
  for (const command of commands) {
    list.appendChild(commandLine(ctx, command));
  }
  box.appendChild(list);
  return box;
}

/**
 * 一条命令一行，底下按用法分格
 *
 * 只有一格的命令不另画分格：那一格就是这条命令，多画一个开关只是让人分不清该拨哪个。
 * 整条的开关说的是「这条命令要不要」，拨一下把本机用得上的每一格一起进草稿。
 */
function commandLine(ctx, command) {
  const usages = command.usages || [];
  const live = usages.filter(usage => usage.available);
  const allOn = live.length > 0 && live.every(usage => !effectiveUsageOff(ctx, usage));
  const box = el('div', 'cmdline');
  const line = el('div', 'swrow' + (command.listed ? '' : ' dimmed'));

  if (command.disableable) {
    const label = el('label', 'switch');
    label.innerHTML = '<input type="checkbox"' + (allOn ? ' checked' : '') + '>';
    const input = label.querySelector('input');
    input.setAttribute('aria-label', command.name);
    // 群里本来就不列的那几条，开关一并锁住：拨动它不会让它出现在菜单里，
    // 而一个拨得动却不起作用的开关比锁着的更费解
    input.disabled = !command.listed || !live.length;
    input.addEventListener('change', () => {
      batchCommands(ctx, live.map(usage => usage.key), !input.checked);
    });
    line.appendChild(label);
  } else {
    // 开关位留同宽空位，「不可关闭」写进文字列的标记里
    line.appendChild(el('span', 'cmdslot'));
  }

  const text = el('div', 'swtxt');
  const marks = [];
  if (!command.disableable) marks.push('不可关闭');
  if (command.requiresAdmin) marks.push('仅管理员');
  // 整条写「已被群管理员禁用」的条件是每一格都关着：一格关着、另一格还答的话，
  // 这条命令仍然答得出话，写上去就成了谎报
  if (live.length > 0 && live.every(usage => effectiveUsageOff(ctx, usage))) {
    marks.push('已被群管理员禁用');
  }
  // 残留记录那句摆在文字这一列：挤进左边那个 38px 的开关位会被压成一竖排字，
  // 而这一列本来就是放这些旁注的地方
  if (command.off) marks.push('状态文件中有残留记录，当前不生效');
  if (command.hiddenReason) marks.push(command.hiddenReason);
  if (command.note) marks.push(command.note);
  text.innerHTML = '<b>' + esc(command.name) + '</b>'
    + (marks.length ? '<span class="cmdmark">' + esc(marks.join(' · ')) + '</span>' : '')
    + '<p>' + esc(command.description) + '</p>';
  line.appendChild(text);
  box.appendChild(line);

  if (command.disableable && usages.length > 1) {
    for (const usage of usages) box.appendChild(usageRow(ctx, command, usage));
  }
  return box;
}

/** 一格一行：各自关得掉，锁住的说清为什么 */
function usageRow(ctx, command, usage) {
  const off = effectiveUsageOff(ctx, usage);
  const row = el('div', 'swrow cmdsub' + (command.listed ? '' : ' dimmed'));

  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (!off && usage.available ? ' checked' : '') + '>';
  const input = label.querySelector('input');
  input.setAttribute('aria-label', usage.key);
  // 本机用不上那一格时锁着：拨了也不起作用
  input.disabled = !command.listed || !usage.available;
  input.addEventListener('change', () => {
    if (ctx.channel) setChannelCommands(ctx, [usage.key], !input.checked);
    else setCommandDraft(ctx.target, usage.key, !input.checked, usage.off);
    refresh();
  });
  row.appendChild(label);

  const text = el('div', 'swtxt');
  const marks = [];
  if (usage.reason) marks.push(usage.reason);
  if (off) marks.push('已被群管理员禁用');
  text.innerHTML = '<b>' + esc(usage.key) + '</b>'
    + (marks.length ? '<span class="cmdmark">' + esc(marks.join(' · ')) + '</span>' : '');
  row.appendChild(text);
  return row;
}

/**
 * 成批开关：整批一起进草稿
 *
 * 从服务端那一份起算每一条的「原样」：期望态与原样相同的那几条不占改动条。
 */
function restoreChannelField(ctx, field) {
  const base = ctx.baseline || {};
  if (Object.prototype.hasOwnProperty.call(base, field)) ctx.target[field] = base[field];
  else delete ctx.target[field];
}

function setChannelCommands(ctx, names, disabled) {
  const keys = new Set(ctx.target.disabledCommands || []);
  for (const name of names) { if (disabled) keys.add(name); else keys.delete(name); }
  const base = ctx.baseline?.disabledCommands || [];
  if (keys.size === base.length && base.every(key => keys.has(key))) restoreChannelField(ctx, 'disabledCommands');
  else if (keys.size) ctx.target.disabledCommands = [...keys];
  else delete ctx.target.disabledCommands;
}

function batchCommands(ctx, names, disabled) {
  if (ctx.channel) setChannelCommands(ctx, names, disabled);
  else setCommandsDraft(ctx.target, names, disabled,
    name => (((ctx.session || {}).disabled) || []).includes(name));
  refresh();
}

// ---- 4 提醒订阅 ----

function subscriptionRow(host, ctx) {
  const summary = ctx.summary.subscription;
  const body = rule(host, '提醒订阅', summary.text,
    '开播或发布动态时会 @ 这些人。成员退群后订阅仍会保留，@ 不在群里的账号无效，可在此清理。'
    + '「@ 谁」不在此处设置，而是在各通知的文字模板中设置。', 'cstat');

  if (!summary.rows.length) {
    body.appendChild(el('p', 'rule-note')).textContent = '群成员发送「@机器人 开播@我」即可订阅。';
    return;
  }

  const manage = el('button', 'ghost sm');
  manage.type = 'button';
  manage.textContent = '管理名单';
  manage.addEventListener('click', () => subscriptionDrawer(ctx));
  body.appendChild(manage);
}

function subscriptionDrawer(ctx) {
  openDrawer('「@我」订阅名单', '由群成员自行订阅。成员退群后订阅仍会保留，可在此清理。', body => {
    for (const sub of ctx.summary.subscription.rows) {
      const clearPending = isClearPending(ctx.target, sub.streamerUid, sub.type);
      const box = el('div', 'dwsub');
      const head = el('div', 'dwsub-h');
      head.innerHTML = '<b>' + esc(sub.streamerName) + ' · ' + esc(sub.typeName) + '提醒</b>'
        + '<span>' + sub.users.length + ' 人订阅</span>';

      const clear = el('button', 'ghost danger sm');
      clear.type = 'button';
      clear.textContent = clearPending ? '撤销清空' : '清空';
      clear.addEventListener('click', () => {
        // 确认挪到保存那一步：这里只记「想要清空」，保存前再点一次即撤回
        setSubscriptionClearDraft(ctx.target, {
          streamerUid: sub.streamerUid, type: sub.type,
        }, {
          title: '清空订阅名单？',
          body: '确定清空「' + sub.streamerName + '」在 ' + ctx.target.num + ' 的'
            + sub.typeName + '订阅名单吗？共 ' + sub.users.length + ' 人。',
        });
        markDirty();
        subscriptionDrawer(ctx);
      });
      head.appendChild(clear);
      box.appendChild(head);

      const pills = el('div', 'dwpills');
      for (const uid of sub.users) {
        const pending = isUserPendingRemove(ctx.target, sub.streamerUid, sub.type, uid);
        const one = el('button', 'nv-pill');
        one.type = 'button';
        one.title = pending && !clearPending ? '撤销移除' : '移除';
        one.textContent = uid + ' ×';
        // 清空已进草稿时整份都要走，单人「移除」这会儿点了也跟着走
        one.disabled = clearPending;
        one.addEventListener('click', () => {
          setSubscriptionRemoveDraft(ctx.target, sub, uid);
          markDirty();
          subscriptionDrawer(ctx);
        });
        pills.appendChild(one);
      }
      box.appendChild(pills);
      body.appendChild(box);
    }
  });
}

// ---- 5 @全体成员：状态行，不是设置 ----

function atAllRow(host, ctx) {
  const status = ctx.summary.atAll;
  const body = rule(host, '@全体成员', status.text, null, 'cstat');
  body.appendChild(el('p', 'rule-note')).textContent = '额度由本群各主播共用，这里只能看。';
  if (status.warning) {
    body.appendChild(el('p', 'st-w')).textContent = '⚠ ' + status.warning;
  }
}
