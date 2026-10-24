/**
 * 首页：待办、连接状态、当前直播、今日数据与事件、健康自检
 *
 * 本文件只管把 home-model.js 算好的东西摆上屏幕。什么时候该显示哪句话、
 * 哪一段该是什么颜色，一律在那边判——判断留在这里的话，八档对照就只能靠人点开页面看。
 */

import {$, api, clearLoading, clock, el, esc, loadingHtml, markDirty, say, today} from './core.js';
import {extAttrs} from './manual-link.js';
import {healthText, homeModel, stationHref} from './home-model.js';
import {considerSetupRedirect, pageStatus, refreshPages} from './main.js';
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
 * 三站都挂灯，灯色照这一段的级别：只有本机挂灯时，平台或机器人那一侧掉了，那一站上看不出来。
 * 灯只是颜色，读屏读不到，因此对读屏藏起来，状态改写进按钮的 aria-label。
 * @param key 站名，与连接页那侧认的一致：platform / self / bot
 */
function station(key, seg) {
  const state = seg.stateText || {ok: '正常', warn: '需要关注', err: '连接异常', off: '未启用'}[seg.level] || '状态未知';
  return '<button class="lm-st" type="button" data-goto="' + esc(key)
    + '" aria-label="' + esc(seg.station + '，' + state + '，' + seg.sub)
    + '" title="' + esc(seg.caption || state) + '">'
    + '<span class="lm-ico"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">'
    + STATION_ICONS[key] + '</svg></span>'
    + '<span class="lm-info"><span class="lm-nm">' + esc(seg.station) + '</span>'
    + '<span class="lm-sub"><span class="lamp ' + esc(seg.level) + '" aria-hidden="true"></span>'
    + esc(state) + '</span></span>'
    + '<span class="lm-arrow" aria-hidden="true">→</span>'
    + '</button>';
}

/**
 * 三个紧凑状态入口
 *
 * 灯色与去处仍取模型。完整探针说明保留在健康自检，避免在首页重复展开同一份状态。
 */
function renderLinkMap(model) {
  $('#linkmap').innerHTML = '<div class="lm-track">'
    + station('platform', model.chain.platform)
    + station('self', model.chain.self)
    + station('bot', model.chain.bot)
    + '</div>';

  // 去处由 stationHref 一份判法给出。地址变了走 hashchange，由 focusCard 滚到那一块。
  $('#linkmap').querySelectorAll('[data-goto]').forEach(btn => {
    btn.addEventListener('click', () => {
      const href = stationHref(btn.dataset.goto);
      if (!href) return;
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

  // 只画有去处的那一条。没有去处的横条只说事，不办事——「已暂停」那一条要办的事
  // 已经随「今日」卡去了控制台插件，画一颗这里点不动的按钮比不画更费解
  if (banner.action && banner.action.href) {
    const go = el('a', 'b-sp nv-btn2 sm');
    go.href = banner.action.href;
    go.textContent = banner.action.text;
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
      + (item.href ? '<a class="nv-btn2 sm" href="' + esc(item.href) + '"' + extAttrs(item.href) + '>' + esc(item.action) + '</a>' : '')
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
  $('#home-now').classList.toggle('is-empty', !model.now.live.length);
  const parts = [];

  for (const who of model.now.live) {
    const duration = since(who.since);
    parts.push('<div class="livewho"><div class="lw-m">'
      + '<span class="lw-nm">' + esc(who.uname) + '</span>'
      + '<span class="pill ' + (model.now.note ? 'warn' : 'live') + '">'
      + (model.now.note ? '' : '<span class="dotlive"></span>')
      + (model.now.note ? '直播中 · 采集有缺口' : '直播中' + (duration ? ' ' + duration : ''))
      + '</span></div>'
      + '<div class="lw-meta">'
      + (who.roomId ? '<span>直播间 ' + esc(who.roomId) + '</span>' : '')
      + '<span>推送目标 ' + esc(who.targets) + ' 个</span>'
      + '</div></div>');
  }

  if (model.now.text) parts.push('<div class="empty">' + esc(model.now.text) + '</div>');
  if (model.now.note) parts.push('<div class="note n-warn">' + esc(model.now.note) + '</div>');
  if (model.now.paused) {
    parts.push('<div class="note n-err">推送已暂停：主播照常直播、数据照常采集，'
      + '但推送、群命令回复与机器人告警均不会发出。</div>');
  }

  box.innerHTML = parts.join('');
}

/** 最近已展示的严重异常；同一异常刷新时保留使用者手动收起的选择。 */
let shownProbeErrors = new Set();

/** 检查明细保持原顺序，摘要只展示模型已有的状态与名称。 */
function renderProbes(model) {
  const errors = model.probes.filter(item => item.lamp === 'err');
  const attention = model.probes.filter(item => item.lamp === 'warn');
  const unknown = model.probes.filter(item => item.lamp === 'off');
  const summary = [];
  if (errors.length) summary.push('异常：' + errors.map(item => item.name).join('、'));
  if (attention.length) summary.push('需关注：' + attention.map(item => item.name).join('、'));
  if (unknown.length) summary.push('状态未知：' + unknown.map(item => item.name).join('、'));
  const card = $('#home-probes');
  card.classList.toggle('has-errors', errors.length > 0);
  card.classList.toggle('has-attention', attention.length > 0 || unknown.length > 0);
  $('#home-health-summary').textContent = summary.length ? summary.join('；')
    : (model.probes.length ? '当前 ' + model.probes.length + ' 项检查均正常' : '暂无可用检查结果');
  $('#home-probe-count').textContent = '（' + model.probes.length + ' 项）';
  const detail = $('#home-probe-details');
  if (errors.some(item => !shownProbeErrors.has(item.name))) detail.open = true;
  shownProbeErrors = new Set(errors.map(item => item.name));

  $('#probe-list').innerHTML = model.probes.length
    ? model.probes.map(item =>
        '<div class="probe"><span class="lamp ' + esc(item.lamp) + ' p-lamp"></span>'
        + '<div class="p-nm">' + esc(item.name) + '</div>'
        + '<div class="p-body"><div class="p-cc" title="' + esc(item.summary) + '">' + esc(healthText(item.summary)) + '</div>'
        + (item.advice ? '<div class="p-ad">' + esc(healthText(item.advice)) + '</div>' : '')
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

  let group = '';
  box.innerHTML = model.events.map(item => {
    const label = item.group === 'attention' ? '需要处理' : '其他事件';
    const heading = item.group !== group ? '<h4 class="event-group">' + label + '</h4>' : '';
    group = item.group;
    if (item.href) {
      return heading + '<a class="tl-more" href="' + esc(item.href) + '">' + esc(item.text) + '</a>';
    }
    const level = item.level === 'error' ? 'error' : (item.level === 'warn' ? 'warn' : 'info');
    const tone = level === 'error' ? ' r-err' : (level === 'warn' ? ' r-warn' : '');
    const where = item.channel || item.streamer || '';
    return heading + '<div class="tl-row' + tone + '">'
      + '<div class="tl-t">' + esc(clock(item.at)) + '</div>'
      + '<div class="tl-rail"><span class="d ' + level + '"></span><span class="l"></span></div>'
      + '<div class="tl-c"><div class="tl-hd"><span>' + esc(item.text) + '</span>'
      + (item.group === 'attention' && item.count > 1 ? '<span class="tl-x">×' + item.count + '，最近 ' + esc(clock(item.at)) + '</span>' : '')
      + (where ? '<span class="tl-x">' + esc(where) + '</span>' : '')
      + '</div></div></div>';
  }).join('');
}

/**
 * 向导插件步的页清单与 done 事实。清单取 /api/pages，模块装法与入口相同。
 */
async function pluginFacts(status, login) {
  let pages = [];
  try {
    pages = ((await api('/pages')).pages || []).filter(meta => meta && meta.slot === 'setup_step');
  } catch (e) {
    pages = [];
  }
  const pluginDone = {};
  const ctx = {status: status || {}, login: login || {}, api};
  for (const meta of pages) {
    try {
      const mod = await import(new URL(meta.script, import.meta.url).href);
      pluginDone[meta.id] = !!(typeof mod.done === 'function' && await mod.done(ctx));
    } catch (e) {
      pluginDone[meta.id] = false;
    }
  }
  return {pages, pluginDone};
}

/**
 * 首页整页重画
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param timeline /api/timeline 回包
 * @param pages /api/pages 里 slot=setup_step 的清单
 * @param pluginDone 插件步事实
 */
function renderHome(status, login, timeline, pages, pluginDone) {
  if (store.role === 'MEMBER') {
    const model = homeModel({users: status.users, live: status.live}, null, timeline);
    model.now.paused = false;
    model.events = model.events.filter(event => !event.href);
    model.eventsEmpty = '当天没有记录。';
    renderNow(model);
    renderStrip(model);
    return;
  }
  const model = homeModel(status, login, timeline, pages, pluginDone, store.vocab);
  renderBanner(model);
  renderLinkMap(model);
  renderTodos(model);
  renderNow(model);
  renderProbes(model);
  renderStrip(model);
}

/**
 * 取数据再一次性画完
 *
 * 请求并发发出、一起等：分几次画的话，链路已经按新的一份数据变红，
 * 而待办还是上一份算出来的——两块说的是同一件事，屏幕上却互相矛盾。
 * 失败与告警另取一份：时间线最近一页装不下时，更早的失败不在这一页里，
 * 只从这一页里数就会像没出过事。这一趟单独接住错误：取不到时按没有这份数据，
 * 短条退回从这一页里数，首页其余照常画。原来三趟出错仍让整页载入失败。
 * 插件带来的首页卡各自取自己那一份，由随后的 refreshPages 通知它们。
 * @return 这一趟取到的运行状态，取不到时为 null
 */
export async function refreshHome() {
  // 已有内容留在原位；失败后重试时，空下来的内容区重新给出加载反馈。
  for (const [id, text, inline] of [
    ['linkmap', '连接状态加载中…'], ['now-box', '当前直播加载中…'],
    ['home-health-summary', '检查结果加载中…', true], ['probe-list', '检查结果加载中…'],
    ['home-timeline', '今日事件加载中…'],
  ]) {
    const box = $('#' + id);
    if (box && !box.textContent.trim() && !box.innerHTML) box.innerHTML = loadingHtml(text, inline);
  }
  try {
    const [status, login, timeline, problems] = await Promise.all([
      api('/status'), store.role === 'MEMBER' ? Promise.resolve(null) : api('/login'), api('/timeline?date=' + today()),
      api('/timeline?date=' + today() + '&problems=true').catch(() => null),
    ]);
    if (problems) {
      try {
        let cursor = problems.nextCursor;
        const visited = new Set();
        while (cursor && !visited.has(cursor)) {
          visited.add(cursor);
          const page = await api('/timeline?date=' + today() + '&problems=true&cursor=' + encodeURIComponent(cursor));
          problems.events = (problems.events || []).concat(page.events || []);
          cursor = page.nextCursor;
        }
      } catch (e) {
        // 未能取全时仍保留已读记录，其余条数由日志入口说明。
      }
    }
    const extra = store.role === 'MEMBER' ? {pages: [], pluginDone: {}} : await pluginFacts(status, login);
    if (considerSetupRedirect(status, login)) return status;
    renderStatus(status);
    renderHome(status, login, Object.assign({}, timeline, {problems}), extra.pages, extra.pluginDone);
    refreshPages();
    return status;
  } catch (e) {
    clearLoading($('#page-home'));
    say('首页加载失败：' + e.message, 'err');
    return null;
  }
}

// 推送记录那张表随推送页改版挪去了通道页的「推什么」那一段：每个通道各看自己的 5 条。
// 整份表不再有摆放的地方——「刚才那条推了吗」问的总是某一个群，而整份表要人自己在里面找。
//
// 「今日」那三个数与推送总开关随控制台插件走：它们问的是推送这件事今天怎么样，
// 而恢复推送那一趟本来就在那个插件里。

/**
 * 侧栏底部的版本位
 *
 * 值由运行状态下发。没有这个字段时只显示产品名，不在界面里写死一个版本号——
 * 写死的那个与产物脱节的那一天不会有任何提示，而使用者正是照着它去判断该不该换 jar。
 * 版本文字连同 GitHub 官方标是一枚去源码仓库的按钮，地址取自 {@link SOURCE_URL}
 * 那一份（AGPL-3.0 下这是使用者取得源码的入口），不在这里另抄。
 * @param version 当前版本，没有则不显示
 */
function renderVersion(version) {
  const source = $('#side-source');
  if (source) source.href = SOURCE_URL;
  $('#side-version').textContent = version ? 'NovaBot ' + version : 'NovaBot';
  renderAbout(version);
}

/** 源码地址。AGPL-3.0 下这是使用者取得源码的入口，因此写在界面上而不只写在仓库里 */
const SOURCE_URL = 'https://github.com/FrostNovaOrg/NovaBot';

/**
 * 设置页页底那一行：产品名与版本、许可证、源码地址
 *
 * 版本与侧栏版本位取自同一个字段，因此这一行也随每次运行状态更新——
 * 在这里另写一个版本号的话，两处会各说各的，而屏幕上不会有任何异常。
 * 没有版本时只报产品名，与侧栏同一种退法：源码地址与许可证跟版本无关，任何时候都该在。
 * @param version 当前版本，没有则不显示
 */
function renderAbout(version) {
  const about = esc(version ? 'NovaBot v' + version : 'NovaBot')
    + ' · AGPL-3.0 · 源码 <a href="' + SOURCE_URL + '"' + extAttrs(SOURCE_URL) + '>'
    + esc(SOURCE_URL) + '</a>';
  $('#about-line').innerHTML = about;
  $('#personal-about').innerHTML = about;
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
        '<span class="sum" title="' + esc(h.summary) + '">' + esc(healthText(h.summary)) + '</span>' +
        (h.advice ? '<span class="advice">' + esc(healthText(h.advice)) + '</span>' : '') +
        '</div>').join('')
    : '<div class="row"><span class="who">健康检查</span><span>' + esc(emptyText) + '</span></div>';
}

/**
 * 运行状态里与页无关的那几块：版本、侧栏运行三行、机器人页的探针、还欠一次重启的清单
 * @param data /api/status 回包
 */
export function renderStatus(data) {
  renderVersion(data.version);
  renderUpdate(store.role === 'MEMBER' ? null : data.update);
  if (store.role === 'MEMBER') {
    pageStatus(data);
    return;
  }

  const runtime = data.runtime || {};
  $('#run-mem').textContent = runtime.heapUsedMb + ' / ' + runtime.heapMaxMb + ' MB';
  $('#run-threads').textContent = runtime.threads;
  // 上限一并显示：只报「监听 10 位」看不出这已经是顶格，再加主播时才发现加不进去
  $('#run-streamers').textContent = (data.users || []).length
    + (data.streamerLimit ? ' / ' + data.streamerLimit : '');
  // 磁盘剩余：量不到时整块缺席，那一格是「—」而不是 0
  renderDiskRow($('#run-disk'), runtime);

  // 保存过、仍等着重启的那几项。只在这里记一次：底部改动条按它写「M 处改动需重启生效」，
  // 而它的寿命是「到下次重启为止」——每次拿到新的运行状态都该跟着更新，
  // 只在整体载入时记一次的话，首页刷新之后那条提示就停在了上一次的数字上
  store.restartPending = data.restartPending || [];
  markDirty();

  // 平台相关的那几块归各平台页自己渲染：这里既不知道装了哪些页，也不知道页里有哪些元素
  pageStatus(data);
}

/**
 * 运行栏「磁盘剩余」那一行
 *
 * 数值与档位都由 runtime 块给（diskFreeMb、diskLevel，量不到时整块缺席）：
 * 阈值在服务端判，浏览器不另养一份——两处各判各的迟早说两种话。
 * 档位不是 ok 时那一格要变色并带悬停说明：磁盘快满这件事没有别的征兆，
 * 写不进去的那一刻推送记录与报告才开始丢，那时已经晚了。
 * @param el 那一格的值位
 * @param runtime /api/status 回包里的 runtime 块
 */
function renderDiskRow(el, runtime) {
  const mb = runtime.diskFreeMb;
  el.textContent = mb == null ? '—' : (mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : mb + ' MB');
  const level = runtime.diskLevel;
  el.className = level === 'err' ? 'err' : level === 'warn' ? 'warn' : '';
  el.title = level === 'err' || level === 'warn'
    ? '磁盘空间即将用尽：数据与日志无法写入时，推送记录与报告将会丢失' : '';
}

// 命令开关、订阅名单与「哪几条推送配置没填完」那三块随推送页改版挪去了通道页的
// 「通道设置」那一段（见 sessions.js）：它们讲的是某一个群此刻听不听话，
// 而这一页答的是「现在好不好」。取那份状态的那一趟也跟着搬走了，见 push.js 的 loadPushPage。

// 「运行自检」把整页重取一遍并给出结论。异常项本就在探针那一栏里逐条列着，
// 这里只回答「现在到底有没有问题」，省得使用者自己数。
// 走整页重取而不是单取一次 /status：自检之后屏幕上的链路、待办、横条都该是这一刻的，
// 只更新一句结论的话，结论说「2 项异常」而上面几块还画着上一次的样子
export async function runSelfTest() {
  if (store.role === 'MEMBER') return;
  $('#home-probe-details').open = true;
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
  pill.textContent = '新版本 ' + update.latestVersion;
  // onclick 是赋值不是叠加，重画多少次都只有一份监听；addEventListener 会一份份摞上去
  pill.onclick = toggleUpdatePop;

  // 说明地址来自新版来源的回包：不是 http(s) 的不画成链接，免得一段 javascript: 在控制台里被点开
  const notesLink = extAttrs(update.url);
  pop.innerHTML = '<div class="up-head">新版本 ' + esc(update.latestVersion) + '</div>'
    + '<div class="up-notes">'
    + (update.notes || []).map(line => '<p>' + esc(line) + '</p>').join('')
    + '</div>'
    + '<p class="up-note">控制台不提供在线更新：请在服务器上替换 jar 文件并重启。</p>'
    + '<div class="up-actions">'
    + (notesLink ? '<a class="lnkbtn" href="' + esc(update.url) + '"' + notesLink + '>查看完整更新说明 ↗</a>' : '')
    + '<button type="button" class="ghost sm" id="update-skip">此版本不再提醒</button>'
    + '</div>';

  $('#update-skip').addEventListener('click', () => skipUpdate(update.latestVersion));
}

function toggleUpdatePop() {
  const pop = $('#update-pop');
  pop.style.display = pop.style.display === 'none' ? '' : 'none';
}

/**
 * 「此版本不再提醒」
 *
 * 记在服务器上、按版本记，因此点完走一次整页重取而不是本地把药丸藏掉：
 * 首页待办里那条软提醒与这枚药丸由同一份状态驱动，只藏一处，另一处会继续催
 * 一件刚说了先不提醒的事。
 */
async function skipUpdate(version) {
  if (store.role === 'MEMBER') return;
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
      say('未能保存提醒设置，请稍后重试', 'err');
      btn.disabled = false;
    }
  } catch (e) {
    say('提醒设置保存失败：' + e.message, 'err');
    btn.disabled = false;
  }
}
