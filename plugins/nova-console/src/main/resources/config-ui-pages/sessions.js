/**
 * 通道页第 4 段「本群设置」：金额可见、静音时段、命令、提醒订阅、@全体成员 状态行
 *
 * 这一段讲的是「这个群此刻听不听话」，与前三段的「往这个群推什么」是同一件事的两面，
 * 因此摆在同一页上——分成两页时，「配好了却不响应」这种情况要在两个入口之间来回对照
 * 才看得出来。它对推给这个群的<b>所有</b>主播共用，在哪一位主播下打开都是同一份。
 *
 * 改动与上方三段同一条保存纪律：先记进草稿、计入底部改动条，按「保存」才落盘，
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
 * 静音时段那一行摊开着的会话
 *
 * 那一行要连着点几下（选档、改开始、改结束），而每点一下这一段都整段重画；
 * 不记住的话，每改一处它就自己收起来一次。
 */
const quietOpen = new Set();

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
    + '因此下面的树上看不到它们：<ul>'
    + entries.map(e => '<li>' + esc(e.message) + '</li>').join('')
    + '</ul></div>';
}

/**
 * 「本群设置」那几行
 *
 * 前三行默认折起来：它们各自都要占掉大半屏，四行同时摊开的话，
 * 使用者要翻很久才回得到上面那三段——而那三段才是这一页每天要改的东西。
 * @param host 往哪儿放
 * @param ctx 这个会话的全部事实与算好的摘要，见 push.js 的 sectionSession
 */
export function sessionSettings(host, ctx) {
  // 重画是「换掉」，不是「再摆一份」：host 收的是自己的容器（见 push.js 段 4），
  // 先清再画。不清的话，草稿每拨一下这一段就长出一倍，而摘要行的字还是旧的
  repaintSession = () => {
    host.innerHTML = '';
    sessionSettings(host, ctx);
  };
  revenueRow(host, ctx);
  quietRow(host, ctx);
  commandRow(host, ctx);
  subscriptionRow(host, ctx);
  atAllRow(host, ctx);
}

/** 草稿变了：先把这一段按「服务端 + 草稿」重画，再让宿主改动条那个 N 自己重算 */
function refresh() {
  repaintSession();
  markDirty();
}

/**
 * 一行可折叠的摘要
 * @param keep 记住摊开与否的那张表与键，可不给：给了才在重画后照原样摊开
 * @return {HTMLElement} 正文容器
 */
function row(host, title, summary, keep) {
  const box = el('details', 'crow');
  if (keep) {
    box.open = keep.set.has(keep.key);
    box.addEventListener('toggle', () => {
      if (box.open) keep.set.add(keep.key);
      else keep.set.delete(keep.key);
    });
  }
  const head = el('summary');
  head.innerHTML = '<span class="cr-t">' + esc(title) + '</span>'
    + '<span class="cr-sum">' + esc(summary) + '</span>';
  box.appendChild(head);
  const body = el('div', 'cr-body');
  box.appendChild(body);
  host.appendChild(box);
  return body;
}

// ---- 1 金额可见 ----

function revenueRow(host, ctx) {
  const body = row(host, '金额可见', ctx.summary.revenue.text);

  const wanted = revenueDraft(ctx.target);
  const visible = wanted === undefined ? ctx.summary.revenue.visible : wanted;
  const line = el('div', 'swrow');
  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (visible ? ' checked' : '') + '>';
  const input = label.querySelector('input');
  input.setAttribute('aria-label', '直播收益等金额');
  input.addEventListener('change', () => {
    // 期望态与服务端那一份比：拨过去又拨回来的那几下自己会消失
    setRevenueDraft(ctx.target, input.checked, ctx.summary.revenue.visible);
    refresh();
  });
  line.appendChild(label);

  const text = el('div', 'swtxt');
  text.innerHTML = '<b>直播收益等金额</b><p>关掉后，下播报告不显示收益、卡片改用人数与条数、'
    + '礼物与醒目留言榜整榜不出；数据查询命令同样不再回金额。'
    + (ctx.summary.revenue.explicit ? '' : '（当前是默认值：群聊隐藏、私聊显示）') + '</p>';
  line.appendChild(text);
  body.appendChild(line);

  body.appendChild(el('div', 'note')).textContent =
    '金额榜整榜不出而不是抹掉数字：那几张榜的每一行本质都是「某人花了多少钱」，'
    + '只去掉右侧的数字，仍然是在公开排消费。';
}

// ---- 2 静音时段 ----

/** 选「本会话自己的时段」又从没填过时，起止先借全局那一对，全局也没设就给一对常见的 */
const QUIET_FALLBACK = {start: '23:00', end: '08:00'};

const QUIET_MODES = [
  {mode: 'follow', label: '跟全局', hint: '用设置页里那一项全局静音时段。没设过的会话都是这一档。'},
  {mode: 'custom', label: '本会话自己的时段', hint: '这个会话按下面的起止静音，不看全局那一项。可跨零点。'},
  {mode: 'off', label: '本会话不静音', hint: '全局在静音时，推往这个会话的照发。'},
];

function quietRow(host, ctx) {
  const summary = ctx.summary.quiet;
  const key = ctx.target.platform + ':' + ctx.target.type + ':' + ctx.target.num;
  const body = row(host, '静音时段', summary.text, {set: quietOpen, key});

  const saved = {mode: summary.mode, start: summary.start, end: summary.end};
  const wanted = quietDraft(ctx.target) || saved;
  const global = ctx.quietGlobal || {};

  /** 期望态与服务端那一份比：选过去又选回来的那几下自己会消失 */
  const put = to => {
    setQuietDraft(ctx.target, to, saved);
    refresh();
  };

  const name = 'quiet-' + key;
  for (const item of QUIET_MODES) {
    const line = el('label', 'swrow');
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

    const text = el('div', 'swtxt');
    text.innerHTML = '<b>' + esc(item.label) + '</b><p>' + esc(item.hint) + '</p>';
    line.appendChild(text);
    body.appendChild(line);
  }

  const times = el('div', 'swrow');
  for (const [field, label] of [['start', '静音开始'], ['end', '静音结束']]) {
    const input = el('input');
    input.type = 'time';
    input.value = wanted.mode === 'custom' ? (wanted[field] || '') : '';
    input.disabled = wanted.mode !== 'custom';
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

  body.appendChild(el('div', 'note')).textContent =
    '静音期间推往这个会话的开播、下播、报告、动态与命令回复直接丢弃，不攒着补发，时间线上记「静音丢弃」。'
    + '起止不能填成一样，填成一样保存时会被拒；要全天照发选「本会话不静音」。'
    + '设置页的全局推送开关关着时一律不发；告警不受静音影响。';
}

// ---- 3 命令 ----

function commandRow(host, ctx) {
  const group = Number(ctx.target.type) === 1;
  const summary = ctx.summary.command;
  const body = row(host, '命令', summary.text + (group ? ' · 群里 @ 机器人再发' : ' · 私聊直接发'));

  body.appendChild(el('div', 'note')).textContent = group
    ? '群成员要先 @ 机器人再发命令，例如「@机器人 直播报告」。@ 了但不是命令，机器人回菜单；'
      + '命令被本群关掉了，回一句「本群已关闭该命令」。没 @ 的消息一律不理，同一群 3 秒一次。'
    : '私聊直接发命令名，不是命令就回菜单。没配推送的会话一律不理。';

  if (summary.offNames.length) {
    const warn = el('div', 'note n-err');
    warn.appendChild(document.createTextNode(
      '群主或群管理员在群里发「禁用命令」关掉了：' + summary.offNames.join('、')
      + '。关掉后有人发它，机器人会回「本群已关闭该命令」。'));
    const fix = el('button', 'ghost');
    fix.type = 'button';
    fix.textContent = '一键恢复';
    fix.addEventListener('click', async () => {
      if (!await ask({title: '一键恢复？',
        body: '把「' + summary.offNames.join('、') + '」在 ' + ctx.target.num
          + ' 恢复可用。群管理员之后仍可再关掉。'})) return;
      batchCommands(ctx, summary.restorable, false);
    });
    warn.appendChild(fix);
    body.appendChild(warn);
  }

  if (summary.strayNames.length) {
    const stray = el('div', 'note');
    stray.appendChild(document.createTextNode(
      '状态文件里还留着这几条的禁用记录，但现在没有这个命令（多为改过名或已删除），'
      + '留着也不会生效：' + summary.strayNames.join('、') + '。'));
    const clean = el('button', 'ghost');
    clean.type = 'button';
    clean.textContent = '清掉记录';
    clean.addEventListener('click', () => batchCommands(ctx, summary.strayNames, false));
    stray.appendChild(clean);
    body.appendChild(stray);
  }

  if (!summary.menuKnown) {
    body.appendChild(el('div', 'note n-warn')).textContent =
      '这个会话不在推送配置里，菜单口径取不到：下面这些行只按整台机器的能力标，'
      + '不代表群里真会列出哪几条。';
  }

  for (const item of ctx.groups) {
    body.appendChild(commandGroup(ctx, item));
  }
}

/** 这一格此刻「想关掉吗」：草稿优先，没改过才认服务端那一份 */
function effectiveUsageOff(ctx, usage) {
  const wanted = commandDraft(ctx.target, usage.key);
  return wanted === undefined ? usage.off : wanted;
}

function commandGroup(ctx, group) {
  const box = el('div', 'cgroup');
  const commands = group.commands || [];
  // 这一组每一条都不可关闭时，没有开关可画。画一个关着且拨不动的，
  // 看起来像整组被关掉了。与单条命令同一写法：开关位留空位，「不可关闭」写进文字列的标记里。
  const noneClosable = commands.length > 0 && commands.every(command => !command.disableable);

  const head = el('div', 'swrow');
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
  text.innerHTML = '<b>' + esc(group.category) + ' <span class="dim">'
    + group.total + ' 条</span></b>'
    + (marks.length ? '<span class="cmdmark">' + esc(marks.join(' · ')) + '</span>' : '');
  head.appendChild(text);

  const fine = el('button', 'ghost');
  fine.type = 'button';
  fine.textContent = '细调';
  head.appendChild(fine);
  box.appendChild(head);

  const list = el('div', 'cfine');
  list.hidden = true;
  for (const command of group.commands) {
    list.appendChild(commandLine(ctx, command));
  }
  box.appendChild(list);

  fine.addEventListener('click', () => {
    list.hidden = !list.hidden;
    fine.textContent = list.hidden ? '细调' : '收起';
  });
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
  if (command.off) marks.push('文件里有残留记录，当前不生效');
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
    setCommandDraft(ctx.target, usage.key, !input.checked, usage.off);
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
function batchCommands(ctx, names, disabled) {
  setCommandsDraft(ctx.target, names, disabled,
    name => (((ctx.session || {}).disabled) || []).includes(name));
  refresh();
}

// ---- 4 提醒订阅 ----

function subscriptionRow(host, ctx) {
  const summary = ctx.summary.subscription;
  const body = row(host, '提醒订阅', summary.text);

  body.appendChild(el('div', 'note')).textContent =
    '开播或发动态时会 @ 这些人。人退群后订阅仍会留着，@ 一个不在群里的号是无效的，可在此清理。'
    + '改「谁会被 @」不在这一行——那一档配在通道的消息模板里。';

  if (!summary.rows.length) {
    body.appendChild(el('p', 'hint')).textContent =
      '还没有人订阅。群成员发送「@机器人 开播@我」即可订阅。';
    return;
  }

  const manage = el('button', 'ghost');
  manage.type = 'button';
  manage.textContent = '管理名单';
  manage.addEventListener('click', () => subscriptionDrawer(ctx));
  body.appendChild(manage);
}

function subscriptionDrawer(ctx) {
  openDrawer('「@我」订阅名单', '群成员自己订阅的。人退群后订阅仍会留着，可在这里清理。', body => {
    for (const sub of ctx.summary.subscription.rows) {
      const clearPending = isClearPending(ctx.target, sub.streamerUid, sub.type);
      const box = el('div', 'dwsub');
      const head = el('div', 'dwsub-h');
      head.innerHTML = '<b>' + esc(sub.streamerName) + ' · ' + esc(sub.typeName) + '提醒</b>'
        + '<span>' + sub.users.length + ' 人订阅</span>';

      const clear = el('button', 'ghost danger');
      clear.type = 'button';
      clear.textContent = clearPending ? '撤回清空' : '清空';
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
        one.title = pending && !clearPending ? '撤回' : '移除';
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
  const line = el('div', 'statline');
  line.appendChild(el('span', 'st-nm')).textContent = '@全体成员';
  line.appendChild(el('span', 'st-v')).textContent = status.text;
  if (status.warning) {
    line.appendChild(el('span', 'st-w')).textContent = status.warning;
  }
  host.appendChild(line);
}
