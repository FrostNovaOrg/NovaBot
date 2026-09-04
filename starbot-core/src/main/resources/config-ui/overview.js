/**
 * 首页：链路三段、待办、现在与今日、六项探针、今天发生了什么
 *
 * 本文件只管把 home-model.js 算好的东西摆上屏幕。什么时候该显示哪句话、
 * 哪一段该是什么颜色，一律在那边判——判断留在这里的话，八档对照就只能靠人点开页面看。
 */

import {$, api, clock, el, esc, markDirty, say, today} from './core.js';
import {homeModel, PROBE_ANCHOR, stationHref} from './home-model.js';
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
 * 站是按钮不是链接：平台与机器人要把人带到连接页上对应的那张卡，
 * 本机则锚到本页健康自检那一块。地址里带着是哪一站
 * （{@code #/links?card=platform} 或 {@code #/home?card=probes}），刷新与收藏也能回到同一处。
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

  // 去处由 stationHref 一份判法给出。本机那一站已经在本页，地址若没变就当场滚，
  // 不靠 hashchange——同一条地址再点一次不会触发它
  $('#linkmap').querySelectorAll('[data-goto]').forEach(btn => {
    btn.addEventListener('click', () => {
      const href = stationHref(btn.dataset.goto);
      if (!href) return;
      if (location.hash === href) {
        const box = $('#' + PROBE_ANCHOR);
        if (box) box.scrollIntoView({block: 'start', behavior: 'smooth'});
        return;
      }
      location.hash = href;
    });
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
      + '<a href="' + esc(item.href) + '">' + esc(item.action) + '</a>'
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
