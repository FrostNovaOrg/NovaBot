/**
 * 通道页第 4 段「本群设置」：金额可见、命令、提醒订阅、@全体成员 状态行
 *
 * 这一段讲的是「这个群此刻听不听话」，与前三段的「往这个群推什么」是同一件事的两面，
 * 因此摆在同一页上——分成两页时，「配好了却不响应」这种情况要在两个入口之间来回对照
 * 才看得出来。它对推给这个群的<b>所有</b>主播共用，在哪一位主播下打开都是同一份。
 *
 * 改动<b>立即生效并当场落盘</b>，因此不受底部「保存」按钮管辖：这几项来自人的一次明确操作，
 * 攒到保存时再写的话，进程此刻被杀掉，使用者会认为「我明明关了」。
 *
 * 摘要那四行写什么由 push-model.js 算（见 tools/push-model-check.sh 的各档），
 * 本文件只管把算好的摆上去，以及把改动发出去。
 */

import {$, api, el, esc, say} from './core.js';
import {loadPushPage, openDrawer, closeDrawer} from './push.js';
import {ask} from './confirm.js';

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
  revenueRow(host, ctx);
  commandRow(host, ctx);
  subscriptionRow(host, ctx);
  atAllRow(host, ctx);
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

  const line = el('div', 'swrow');
  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (ctx.summary.revenue.visible ? ' checked' : '') + '>';
  const input = label.querySelector('input');
  input.setAttribute('aria-label', '直播收益等金额');
  input.addEventListener('change', () => setRevenue(ctx, input));
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

async function setRevenue(ctx, input) {
  input.disabled = true;
  try {
    const res = await api('/state/revenue', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        platform: ctx.target.platform, num: ctx.target.num, visible: input.checked,
      }),
    });
    say(res.message || (res.success ? '已保存 · 立即生效' : '操作失败'), res.success ? 'ok' : 'err');
    // 失败时把开关拨回去：留在新位置会让人以为改成功了
    if (!res.success) input.checked = !input.checked;
    else await loadPushPage();
  } catch (e) {
    input.checked = !input.checked;
    say('操作失败：' + e.message, 'err');
  }
  input.disabled = false;
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

function commandGroup(ctx, group) {
  const box = el('div', 'cgroup');

  const head = el('div', 'swrow');
  const label = el('label', 'switch');
  label.innerHTML = '<input type="checkbox"' + (group.on ? ' checked' : '') + '>';
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

function commandLine(ctx, command) {
  const line = el('div', 'swrow' + (command.listed ? '' : ' dimmed'));

  if (command.disableable) {
    const label = el('label', 'switch');
    label.innerHTML = '<input type="checkbox"' + (command.off ? '' : ' checked') + '>';
    const input = label.querySelector('input');
    input.setAttribute('aria-label', command.name);
    // 群里本来就不列的那几条，开关一并锁住：拨动它不会让它出现在菜单里，
    // 而一个拨得动却不起作用的开关比锁着的更费解
    input.disabled = !command.listed;
    input.addEventListener('change', () => setCommand(ctx, command.name, !input.checked, input));
    line.appendChild(label);
  } else {
    const lock = el('span', 'cmdlock');
    lock.textContent = command.off ? '不可关闭 · 文件里有残留记录，当前不生效' : '不可关闭';
    line.appendChild(lock);
  }

  const text = el('div', 'swtxt');
  const marks = [];
  if (command.requiresAdmin) marks.push('仅管理员');
  if (command.off) marks.push('已被群管理员禁用');
  if (command.hiddenReason) marks.push(command.hiddenReason);
  if (command.note) marks.push(command.note);
  text.innerHTML = '<b>' + esc(command.name) + '</b>'
    + (marks.length ? '<span class="cmdmark">' + esc(marks.join(' · ')) + '</span>' : '')
    + '<p>' + esc(command.description) + '</p>';
  line.appendChild(text);
  return line;
}

async function setCommand(ctx, name, disabled, input) {
  input.disabled = true;
  try {
    const res = await api('/state/command', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        platform: ctx.target.platform, num: ctx.target.num, command: name, disabled,
      }),
    });
    say(res.message || (res.success ? '已保存 · 立即生效' : '操作失败'), res.success ? 'ok' : 'err');
    if (!res.success) input.checked = !input.checked;
    else await loadPushPage();
  } catch (e) {
    input.checked = !input.checked;
    say('操作失败：' + e.message, 'err');
  }
  input.disabled = false;
}

/**
 * 成批开关
 *
 * 走一支批量接口而不是在这里循环调单条：中途失败会留下一半开一半关的局面，
 * 而屏幕上只有最后那一条的报错——使用者不知道刚才究竟改成了什么样。
 */
async function batchCommands(ctx, names, disabled) {
  try {
    const res = await api('/state/commands', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        platform: ctx.target.platform, num: ctx.target.num, commands: names, disabled,
      }),
    });
    say(res.message || (res.success ? '已保存 · 立即生效' : '操作失败'), res.success ? 'ok' : 'err');
    // 成功与否都重取：失败时整批没改，屏幕上那个已经拨过去的开关得拨回来
    await loadPushPage();
  } catch (e) {
    say('操作失败：' + e.message, 'err');
    await loadPushPage();
  }
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
      const box = el('div', 'dwsub');
      const head = el('div', 'dwsub-h');
      head.innerHTML = '<b>' + esc(sub.streamerName) + ' · ' + esc(sub.typeName) + '提醒</b>'
        + '<span>' + sub.users.length + ' 人订阅</span>';

      const clear = el('button', 'ghost danger');
      clear.type = 'button';
      clear.textContent = '清空';
      clear.addEventListener('click', async () => {
        if (!await ask({title: '清空订阅名单？',
          body: '确定清空「' + sub.streamerName + '」在 ' + ctx.target.num + ' 的'
            + sub.typeName + '订阅名单吗？共 ' + sub.users.length + ' 人。'})) return;
        removeSub(ctx, sub, null);
      });
      head.appendChild(clear);
      box.appendChild(head);

      const pills = el('div', 'dwpills');
      for (const uid of sub.users) {
        const one = el('button', 'nv-pill');
        one.type = 'button';
        one.title = '移除';
        one.textContent = uid + ' ×';
        one.addEventListener('click', () => removeSub(ctx, sub, uid));
        pills.appendChild(one);
      }
      box.appendChild(pills);
      body.appendChild(box);
    }
  });
}

async function removeSub(ctx, sub, userUid) {
  try {
    const res = await api('/state/subscription', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        platform: ctx.target.platform, num: ctx.target.num,
        streamerUid: sub.streamerUid, type: sub.type, userUid,
      }),
    });
    say(res.message || (res.success ? '已移除' : '操作失败'), res.success ? 'ok' : 'err');
    if (res.success) {
      closeDrawer();
      await loadPushPage();
    }
  } catch (e) {
    say('操作失败：' + e.message, 'err');
  }
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
