/**
 * 群与成员页：会话列表、金额可见性、命令开关、订阅名单与账号绑定
 */

import {$, api, el, esc, say} from './core.js';
import {loadState} from './overview.js';

export function renderSessions(sessions, commands) {
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

export function renderSubs(subs) {
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

export function renderBinds(binds) {
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
