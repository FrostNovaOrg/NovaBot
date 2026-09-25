/**
 * 通道页第 4 段「本群设置」：金额可见、命令、提醒订阅、@全体成员 状态行
 *
 * 这一段讲的是「这个群此刻听不听话」，与前三段的「往这个群推什么」是同一件事的两面，
 * 因此摆在同一页上——分成两页时，「配好了却不响应」这种情况要在两个入口之间来回对照
 * 才看得出来。它对推给这个群的<b>所有</b>主播共用，在哪一位主播下打开都是同一份。
 *
 * 改动与上方三段同一条保存纪律：先记进草稿、计入底部改动条，按「保存」才落盘，
 * 按「放弃」全部还原。从前是「拨一下就发请求」，于是同一页上并存两套相反的规矩，
 * 而「放弃」撤不回这几项。
 *
 * 摘要那四行写什么由 push-model.js 算（见 tools/push-model-check.sh 的各档），
 * 本文件只管把算好的摆上去，以及把改动记进草稿。写盘走 push.js 的 save，
 * 与推送配置那份草稿在同一趟里发出。
 */

import {$, el, esc, markDirty} from './core.js';
import {openDrawer} from './push.js';
import {ask} from './confirm.js';
import {
  commandDraft, isClearPending, isUserPendingRemove, revenueDraft, setCommandDraft,
  setCommandsDraft, setRevenueDraft, setSubscriptionClearDraft, setSubscriptionRemoveDraft,
} from './session-draft.js';

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

  box.innerHTML = '<div class="warn"><b>推送配置尚未填写完整</b>，以下条目暂未生效，'
    + '因此下面的树上看不到它们：<ul>'
    + entries.map(e => '<li>' + esc(e.message) + '</li>').join('')
    + '</ul></div>';
}

/**
 * 「本群设置」四行
 *
 * 前三行默认折起来：它们各自都要占掉大半屏，四行同时摊开的话，
 * 使用者要翻很久才回得到上面那三段——而那三段才是这一页每天要改的东西。
 * @param host 往哪儿放
 * @param ctx 这个会话的全部事实与算好的摘要，见 push.js 的 sectionSession
 */
export function sessionSettings(host, ctx) {
  repaintSession = () => sessionSettings(host, ctx);
  revenueRow(host, ctx);
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
 * @return {HTMLElement} 正文容器
 */
function row(host, title, summary) {
  const box = el('details', 'crow');
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

// ---- 2 命令 ----

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

  // 组开关按格起算：一格关着、另一格还开的半开组不算全开
  const cells = group.cells || [];
  const allOn = cells.length > 0 && cells.every(usage => !effectiveUsageOff(ctx, usage));

  const head = el('div', 'swrow');
  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (allOn ? ' checked' : '') + '>';
  const input = label.querySelector('input');
  input.setAttribute('aria-label', group.category);
  input.disabled = !group.switchable.length;
  input.addEventListener('change', () => batchCommands(ctx, group.switchable, !input.checked));
  head.appendChild(label);

  const text = el('div', 'swtxt');
  text.innerHTML = '<b>' + esc(group.category) + ' <span class="dim">'
    + group.total + ' 条</span></b>';
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
    const lock = el('span', 'cmdlock');
    lock.textContent = command.off ? '不可关闭 · 文件里有残留记录，当前不生效' : '不可关闭';
    line.appendChild(lock);
  }

  const text = el('div', 'swtxt');
  const marks = [];
  if (command.requiresAdmin) marks.push('仅管理员');
  // 整条写「已被群管理员禁用」的条件是每一格都关着：一格关着、另一格还答的话，
  // 这条命令仍然答得出话，写上去就成了谎报
  if (live.length > 0 && live.every(usage => effectiveUsageOff(ctx, usage))) {
    marks.push('已被群管理员禁用');
  }
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

// ---- 3 提醒订阅 ----

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

// ---- 4 @全体成员：状态行，不是设置 ----

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
