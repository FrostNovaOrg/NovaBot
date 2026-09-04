/**
 * 首页：链路三段、待办、现在与今日、六项探针、今天发生了什么
 *
 * 本文件只管把 home-model.js 算好的东西摆上屏幕。什么时候该显示哪句话、
 * 哪一段该是什么颜色，一律在那边判——判断留在这里的话，八档对照就只能靠人点开页面看。
 */

import {$, api, clock, el, esc, markDirty, say, today} from './core.js';
import {homeModel} from './home-model.js';
import {pageStatus} from './main.js';
import {store} from './store.js';

/** 链路图上三座站的图标。画在这里而不是插件里：这三座站是产品形态本身，不随装了什么插件变 */
const STATION_ICONS = {
  platform: '<path d="M2 4.4h12v9H2z"/><path d="M5 1.8 6.8 4.2M11 1.8 9.2 4.2"/>',
  self: '<path d="M8 1.9 14 5v5.2L8 14.1 2 10.2V5z"/><circle cx="8" cy="7.6" r="1.9"/>',
  bot: '<rect x="2.6" y="5" width="10.8" height="7.6" rx="2.2"/><path d="M6 8.2v1.2M10 8.2v1.2M8 2.4V5"/>',
};

/**
 * 一座站
 *
 * 站是按钮不是链接：它要做的事是「把人带到连接页上对应的那张卡」，
 * 而地址里带着是哪一站（{@code #/links?card=platform}），刷新与收藏也能回到同一张卡。
 * @param key 站名，与连接页那侧认的一致：platform / self / bot
 */
function station(key, seg, withLamp) {
  return '<button class="lm-st" type="button" data-goto="' + esc(key) + '">'
    + '<span class="lm-ico"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">'
    + STATION_ICONS[key] + '</svg></span>'
    + '<span class="lm-nm">' + esc(seg.station)
    + (withLamp ? '<span class="lamp ' + esc(seg.level) + '"></span>' : '') + '</span>'
    + '<span class="lm-sub">' + esc(seg.sub) + '</span>'
    + '</button>';
}

/** 两站之间的那一段线，灯嵌在中点，线下写这一段此刻怎么样 */
function link(seg) {
  return '<div class="lm-seg">'
    + '<div class="lm-line"><span class="lamp ' + esc(seg.level) + '"></span></div>'
    + '<div class="lm-cap">' + esc(seg.caption || '—') + '</div>'
    + '</div>';
}

/**
 * 链路图
 *
 * 三段对应探针自报的三档范围：直播平台一侧、本机、机器人一侧。
 * 本机那一档的灯挂在中间那座站上——它不是两站之间的一条线，而是这台机器自己的状况。
 */
function renderLinkMap(model) {
  $('#linkmap').innerHTML = '<div class="lm-track">'
    + station('platform', model.chain.platform, false)
    + link(model.chain.platform)
    + station('self', model.chain.self, true)
    + link(model.chain.bot)
    + station('bot', model.chain.bot, false)
    + '</div>'
    // 本机那一段的说明没有线可挂，单独写在轨道下面
    + '<div class="lm-seg" style="min-width:0;padding-top:8px">'
    + '<div class="lm-cap">本机 · ' + esc(model.chain.self.caption || '—') + '</div></div>';

  // 地址里带上是哪一站，由连接页那侧决定滚到哪张卡。写成「跳过去再由这里滚」的话，
  // 从收藏夹直接打开那条地址就滚不了——而那正是使用者第二次来找同一张卡时会走的路
  $('#linkmap').querySelectorAll('[data-goto]').forEach(btn => {
    btn.addEventListener('click', () => { location.hash = '#/links?card=' + btn.dataset.goto; });
  });
}

/** 顶部横条。一次只出一条，出哪一条由模型定 */
function renderBanner(model) {
  const box = $('#home-banner');
  box.innerHTML = '';
  if (!model.banner) return;

  const banner = model.banner;
  const row = el('div', 'banner b-' + (banner.level === 'dim' ? 'dim' : banner.level));
  const text = el('span');
  text.textContent = banner.text;
  row.appendChild(text);

  if (banner.action && banner.action.href) {
    const go = el('a', 'b-sp');
    go.href = banner.action.href;
    go.textContent = banner.action.text;
    row.appendChild(go);
  } else if (banner.action) {
    // 「恢复推送」没有去处，它就在这里当场办
    const go = el('button', 'b-sp');
    go.type = 'button';
    go.textContent = banner.action.text;
    go.addEventListener('click', togglePush);
    row.appendChild(go);
  }

  box.appendChild(row);
}

/**
 * 站外链接要多带的两个属性
 *
 * 待办与新版面板里的「看完整说明」指向发布仓，是本站之外的地址；
 * 控制台是单页应用，原地跳走再退回来时页面状态全部重来。
 * 只认 http 开头：#/xxx 那些内部路由绝不能带 target，带了会在新页签里再开一份控制台。
 */
function extAttrs(href) {
  return String(href || '').indexOf('http') === 0 ? ' target="_blank" rel="noreferrer"' : '';
}

/** 待办。一条都没有时整块不渲染——一张写着「暂无待办」的空卡片只是在占地方 */
function renderTodos(model) {
  const box = $('#home-todo');
  if (!model.todos.length) {
    box.innerHTML = '';
    return;
  }

  box.innerHTML = '<div class="nv-card hcard"><h3>待办</h3>'
    + model.todos.map(item =>
      '<div class="todo">'
      + '<span class="tb' + (item.soft ? ' soft' : '') + '">' + (item.soft ? 'i' : '!') + '</span>'
      + '<span class="tt"><b>' + esc(item.title) + '</b><p>' + esc(item.body) + '</p></span>'
      + '<a href="' + esc(item.href) + '"' + extAttrs(item.href) + '>' + esc(item.action) + '</a>'
      + '</div>').join('')
    + '</div>';
}

/** 已播多久。开播时刻没记上时返回空串，不编一个出来 */
function since(at) {
  if (!at) return '';
  const minutes = Math.floor((Date.now() - at) / 60000);
  if (minutes < 0) return '';
  return minutes < 60 ? minutes + ' 分钟' : Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分钟';
}

/** 现在：谁在播 */
function renderNow(model) {
  const box = $('#now-box');
  const parts = [];

  for (const who of model.now.live) {
    const duration = since(who.since);
    parts.push('<div class="livewho"><div class="lw-m">'
      + '<div class="lw-nm">' + esc(who.uname)
      + '<span class="pill ' + (model.now.note ? 'warn' : 'live') + '">'
      + (model.now.note ? '' : '<span class="dotlive"></span>')
      + (model.now.note ? '直播中 · 采集有缺口' : '直播中' + (duration ? ' ' + duration : ''))
      + '</span></div>'
      + '<dl class="kv">'
      + (who.roomId ? '<dt>直播间</dt><dd>' + esc(who.roomId) + '</dd>' : '')
      + '<dt>推送目标</dt><dd>' + esc(who.targets) + ' 处</dd>'
      + '</dl></div></div>');
  }

  if (model.now.text) parts.push('<div class="empty">' + esc(model.now.text) + '</div>');
  if (model.now.note) parts.push('<div class="note n-warn">' + esc(model.now.note) + '</div>');
  if (model.now.paused) {
    parts.push('<div class="note n-err">推送总开关已关闭：主播照常在播、数据照常采，'
      + '但一条消息都不会发出去。</div>');
  }

  box.innerHTML = parts.join('');
}

/** 今日两个数。第三格「@全体成员 已用」还没有数据源，宁可不摆，也不摆一个编出来的分数 */
function renderToday(model) {
  $('#today-stats').innerHTML = [
    [model.today.sent, '推送（条）'],
    [model.today.failed, '失败（条）'],
  ].map(([n, label]) => '<div class="stat"><div class="stat-v">' + esc(n) + '</div>'
    + '<div class="stat-l">' + esc(label) + '</div></div>').join('');
}

/** 六项探针 */
function renderProbes(model) {
  $('#probe-list').innerHTML = model.probes.length
    ? model.probes.map(item =>
        '<div class="probe"><span class="lamp ' + esc(item.lamp) + ' p-lamp"></span>'
        + '<div class="p-nm">' + esc(item.name) + '</div>'
        + '<div class="p-body"><div class="p-cc">' + esc(item.summary) + '</div>'
        + (item.advice ? '<div class="p-ad">' + esc(item.advice) + '</div>' : '')
        + '</div></div>').join('')
    : '<div class="empty">暂无可用探针</div>';
}

/** 今天发生了什么：失败与告警置顶的那几条 */
function renderStrip(model) {
  const box = $('#home-timeline');
  if (!model.events.length) {
    box.innerHTML = '<div class="empty">' + esc(model.eventsEmpty) + '</div>';
    return;
  }

  box.innerHTML = model.events.map(item => {
    const level = item.level === 'error' ? 'error' : (item.level === 'warn' ? 'warn' : 'info');
    const tone = level === 'error' ? ' r-err' : (level === 'warn' ? ' r-warn' : '');
    const where = item.channel || item.streamer || '';
    return '<div class="tl-row' + tone + '">'
      + '<div class="tl-t">' + esc(clock(item.at)) + '</div>'
      + '<div class="tl-rail"><span class="d ' + level + '"></span><span class="l"></span></div>'
      + '<div class="tl-c"><div class="tl-hd"><span>' + esc(item.text) + '</span>'
      + (where ? '<span class="tl-x">' + esc(where) + '</span>' : '')
      + '</div></div></div>';
  }).join('');
}

/**
 * 首页整页重画
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param timeline /api/timeline 回包
 */
function renderHome(status, login, timeline) {
  const model = homeModel(status, login, timeline);
  renderBanner(model);
  renderLinkMap(model);
  renderTodos(model);
  renderNow(model);
  renderToday(model);
  renderProbes(model);
  renderStrip(model);
  renderPushSwitch(model.pushOn);
}

/**
 * 取三份数据再一次性画完
 *
 * 三个请求并发发出、一起等：分三次画的话，链路已经按新的一份数据变红，
 * 而待办还是上一份算出来的——两块说的是同一件事，屏幕上却互相矛盾。
 * @return 这一趟取到的运行状态，取不到时为 null
 */
export async function refreshHome() {
  try {
    const [status, login, timeline] = await Promise.all([
      api('/status'), api('/login'), api('/timeline?date=' + today()),
    ]);
    renderStatus(status);
    renderHome(status, login, timeline);
    return status;
  } catch (e) {
    say('载入首页失败：' + e.message, 'err');
    return null;
  }
}

// 推送记录那张表随推送页改版挪去了通道页的「推什么」那一段：
// 每个通道各看自己的 5 条，见 push.js 的 sectionNotices。整份表不再有摆放的地方——
// 「刚才那条推了吗」问的总是某一个群，而整份表要人自己在里面找。

function renderPushSwitch(enabled) {
  store.pushEnabled = enabled !== false;
  $('#toggle-push').textContent = store.pushEnabled ? '暂停全部推送' : '恢复推送';
  $('#push-hint').textContent = store.pushEnabled ? '当前正常推送' : '已暂停，所有推送都会被丢弃';
  $('#push-hint').style.color = store.pushEnabled ? '' : 'var(--err)';
}

export async function togglePush() {
  const next = !store.pushEnabled;
  $('#toggle-push').disabled = true;
  try {
    const res = await api('/push/toggle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: next })
    });
    say(res.message, res.success ? 'ok' : 'err');
    // 整页重画而不是只把开关拨过去：暂停会同时改横条、链路 QQ 段与「现在」那张卡，
    // 只拨开关的话，屏幕上会出现「已暂停」与一段绿灯并存的画面
    await refreshHome();
  } catch (e) {
    say('切换失败：' + e.message, 'err');
  }
  $('#toggle-push').disabled = false;
}

/**
 * 侧栏底部的版本位
 *
 * 值由运行状态下发。没有这个字段时只显示产品名，不在界面里写死一个版本号——
 * 写死的那个与产物脱节的那一天不会有任何提示，而使用者正是照着它去判断该不该换 jar。
 * @param version 当前版本，没有则不显示
 */
function renderVersion(version) {
  $('#side-version').textContent = version ? 'NovaBot ' + version : 'NovaBot';
}

/**
 * 侧栏那枚「新版」药丸与它点开的小面板
 *
 * 有没有该提示的新版由 /api/status 的 update 块说了算（缺席即没有），这里只管摆出来。
 * 面板每次拿到新的运行状态都整块重画：开着的时候来了一份新的，内容跟着换，
 * 而不是停在上一次那份——说明与版本号都来自检查器，两份混在一起时说的不是同一版。
 * @param update status 里的 update 块，没有则收起药丸与面板
 */
function renderUpdate(update) {
  const pill = $('#side-update');
  const pop = $('#update-pop');

  if (!update) {
    pill.style.display = 'none';
    pop.style.display = 'none';
    return;
  }

  pill.style.display = '';
  pill.textContent = '新版 ' + update.latestVersion;
  // onclick 是赋值不是叠加，重画多少次都只有一份监听；addEventListener 会一份份摞上去
  pill.onclick = toggleUpdatePop;

  pop.innerHTML = '<div class="up-head">新版 ' + esc(update.latestVersion) + '</div>'
    + '<div class="up-notes">'
    + (update.notes || []).map(line => '<p>' + esc(line) + '</p>').join('')
    + '</div>'
    + '<p class="up-note">控制台不做在线更新：到服务器上换 jar 重启就是了。</p>'
    + '<div class="up-actions">'
    + '<a href="' + esc(update.url) + '"' + extAttrs(update.url) + '>看完整更新说明 ↗</a>'
    + '<button type="button" id="update-skip">知道了，这版先不提醒</button>'
    + '</div>';

  $('#update-skip').addEventListener('click', () => skipUpdate(update.latestVersion));
}

function toggleUpdatePop() {
  const pop = $('#update-pop');
  pop.style.display = pop.style.display === 'none' ? '' : 'none';
}

/**
 * 「知道了，这版先不提醒」
 *
 * 记在服务器上、按版本记，因此点完走一次整页重取而不是本地把药丸藏掉：
 * 首页待办里那条软提醒与这枚药丸由同一份状态驱动，只藏一处，另一处会继续催
 * 一件刚说了先不提醒的事。
 */
async function skipUpdate(version) {
  const btn = $('#update-skip');
  btn.disabled = true;
  try {
    const res = await api('/version/skip', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: version })
    });
    if (res.success) {
      await refreshHome();
    } else {
      say('没记上，稍后再试', 'err');
      btn.disabled = false;
    }
  } catch (e) {
    say('没记上：' + e.message, 'err');
    btn.disabled = false;
  }
}

/**
 * 监听中的主播
 *
 * 每一列都来自 /api/status，与是哪个平台无关，因此归核心画。这张表原先长在平台插件那一页上，
 * 装第二个平台时会变成两张各列一半的表——而使用者要的是「这台机器一共在盯着谁」。
 * @param data /api/status 回包
 */
function renderUsers(data) {
  const body = $('#users tbody');
  if (!body) return;

  const users = data.users || [];
  if (!users.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">还没有配置任何主播，去「QQ 推送」页添加</td></tr>';
    return;
  }

  body.innerHTML = users.map(u => [u.uid, u.uname || '—', u.roomId || '—', u.platform,
    u.targets, u.enabled === false ? '已停用' : '正常']
    .map(v => '<td>' + esc(v) + '</td>').join('')).map(tds => '<tr>' + tds + '</tr>').join('');
}

/**
 * 探针行。插件页要渲染属于自己的那几条，因此这里导出去
 * @param list 探针
 * @param emptyText 一条都没有时说的话
 */
export function healthRows(list, emptyText) {
  return list.length
    ? list.map(h =>
        '<div class="row"><span class="dot ' + esc(h.level) + '"></span>' +
        '<span class="who">' + esc(h.name) + '</span>' +
        '<span class="sum">' + esc(h.summary) + '</span>' +
        (h.advice ? '<span class="advice">' + esc(h.advice) + '</span>' : '') +
        '</div>').join('')
    : '<div class="row"><span class="who">健康检查</span><span>' + esc(emptyText) + '</span></div>';
}

/**
 * 运行状态里与页无关的那几块：版本、侧栏运行三行、机器人页的探针、还欠一次重启的清单
 * @param data /api/status 回包
 */
export function renderStatus(data) {
  renderVersion(data.version);
  renderUpdate(data.update);
  renderUsers(data);

  const runtime = data.runtime || {};
  $('#run-mem').textContent = runtime.heapUsedMb + ' / ' + runtime.heapMaxMb + ' MB';
  $('#run-threads').textContent = runtime.threads;
  // 上限一并显示：只报「监听 10 位」看不出这已经是顶格，再加主播时才发现加不进去
  $('#run-streamers').textContent = (data.users || []).length
    + (data.streamerLimit ? ' / ' + data.streamerLimit : '');

  // 保存过、仍等着重启的那几项。只在这里记一次：底部改动条按它写「M 处改动需重启生效」，
  // 而它的寿命是「到下次重启为止」——每次拿到新的运行状态都该跟着更新，
  // 只在整体载入时记一次的话，首页刷新之后那条提示就停在了上一次的数字上
  store.restartPending = data.restartPending || [];
  markDirty();

  // 平台相关的那几块归各平台页自己渲染：这里既不知道装了哪些页，也不知道页里有哪些元素
  pageStatus(data);
}

// 命令开关、订阅名单与「哪几条推送配置没填完」那三块随推送页改版挪去了通道页的
// 「本群设置」那一段（见 sessions.js）：它们讲的是某一个群此刻听不听话，
// 而这一页答的是「现在好不好」。取那份状态的那一趟也跟着搬走了，见 push.js 的 loadPushPage。

// 「运行自检」把整页重取一遍并给出结论。异常项本就在探针那一栏里逐条列着，
// 这里只回答「现在到底有没有问题」，省得使用者自己数。
// 走整页重取而不是单取一次 /status：自检之后屏幕上的链路、待办、横条都该是这一刻的，
// 只更新一句结论的话，结论说「2 项异常」而上面几块还画着上一次的样子
export async function runSelfTest() {
  $('#selftest-run').disabled = true;
  say('自检中…');

  const st = await refreshHome();
  if (st) {
    const health = st.health || [];
    const bad = health.filter(h => h.level !== 'OK');
    say(bad.length
      ? bad.length + ' 项异常：' + bad.map(h => h.name).join('、')
      : '自检完成，' + health.length + ' 项全部正常', bad.length ? 'err' : 'ok');
  }

  $('#selftest-run').disabled = false;
}
