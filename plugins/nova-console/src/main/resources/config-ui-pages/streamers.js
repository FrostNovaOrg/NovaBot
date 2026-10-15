/**
 * 主播页：列表、详情（概况／场次／趋势／开播时段／观众）、某一场
 *
 * 本文件随控制台插件走，由控制台按注册清单装载，落在顶级导航。
 * 三个导出就是与控制台之间的全部约定：render 建出页容器里的三块、
 * refresh 按地址栏取数、leave 离开本页时停止直播刷新并撤掉报告图的临时地址。
 *
 * 本文件只管把东西摆上屏幕。地址栏怎么读怎么拼、状态标写哪几个字、折线与柱图的几何、
 * 人气峰该显示 0 还是「—」、两种缺口怎么分开说、快照那些裸键换成什么人话——
 * 一律在 streamers-model.js 里判。判断留在这里的话，「几个月前那一场的人气峰」
 * 这种分支就只能靠人手点，而它要等一条几个月前的记录被点开才看得见。
 *
 * 三块子视图共用一个页容器，哪一块显示只由地址栏定（syncStreamersView）——
 * 刷新、收藏、后退三种进法走的都是这一条路，各写一套的话，
 * 从地址栏直接进某一场与点一行进去会渲染出两种结果。
 */

import {$, api, CONSOLE, el, esc, request, say} from './core.js';
import {
  AUDIENCE_COLUMNS, audienceCells, audienceGroups, audienceSort, coverageText,
  PERIODS, TABS, barGeometry, detailHash, fmtDuration, fmtGap, fmtMetric, fmtTime,
  gapCells, insightsComparison, comparisonExplanation, insightsPoint, insightsTime, isStreamersHash, pageBar, parseStreamersHash, peakCell, reportPath,
  reportView, rowSubtitle, seriesValues, sessionHash, sessionTitle, shownMetrics,
  slotCell, slotLabel, slotRangeDays, slotTopText, slotTotal,
  snapshotRows, sparkline, statusChip, summaryTotals, totalDataBanner, uncoveredStatuses,
} from './streamers-model.js';

const PAGE_STYLE = `
#st-list a.rowcard{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px;color:inherit;text-decoration:none}
#st-list a.rowcard:hover{background:var(--soft)}
#st-list a.rowcard:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
#sd-live .live-head{display:flex;align-items:center;justify-content:space-between;gap:12px}
#sd-live .live-head h3{margin:0}
#sd-live .live-times,#sd-live .live-error{font-size:12px;color:var(--dim);margin:10px 0 0}
#sd-live .live-error{color:var(--err,var(--dim))}
/* 仅流水格按内容宽度缩字：每个数字或标点预留 .65em，空格和单位预留 1.5em。 */
#sd-insights .stat-revenue{container-type:inline-size;min-width:0}
#sd-insights .stat-revenue .stat-v{white-space:nowrap;font-size:min(23px,calc(100cqi / var(--revenue-width)))}
.chart{margin-bottom:2px}
.chart .plot{position:relative;padding-top:6px}
.chart svg{display:block;width:100%;height:110px}
/* 柱子的类名带 c- 前缀：核心样式里另有一条 .bar 管页底那条横条，
   共用类名的话，收起横条时柱子会被那条规则一起藏掉 */
.chart .c-bar{fill:var(--accent)}
.chart .c-bar.zero{fill:var(--line)}
.chart .xlab{display:flex;justify-content:space-between;margin-top:6px;font-size:12px;color:var(--dim)}
.spark{width:88px;height:26px;flex:none;color:var(--dim)}
.spark.up{color:var(--accent)}
a.lgpill{text-decoration:none}
a.lgpill[aria-current="page"]{border-color:var(--accent);color:var(--accent);background:var(--soft)}
#sd-tabs{display:block;margin:0 0 14px}
#sd-tabs>.lgpills+.lgpills{margin-top:10px}
#sd-body td.n,#sd-body th.n{text-align:right;font-variant-numeric:tabular-nums}
#sd-body tr.empty-row td{color:var(--dim)}
#sd-body tbody tr:hover{background:var(--soft)}
#sd-body .start-slots{min-width:0;max-width:100%;box-sizing:border-box}
#sd-body .slot-scroll{overflow-x:auto;max-width:100%;margin-top:14px}
#sd-body .slot-grid{border-collapse:collapse;min-width:1200px;width:100%;font-size:12px}
#sd-body .slot-grid th,#sd-body .slot-grid td{padding:4px;text-align:center}
#sd-body .slot-grid button{display:block;width:100%;min-height:64px;border:1px solid var(--line);border-radius:6px;color:inherit;background:transparent;cursor:pointer;font:inherit}
#sd-body .slot-grid button[aria-pressed="true"]{outline:2px solid var(--accent);outline-offset:-2px}
#sd-body .slot-grid button:focus-visible{outline:2px solid var(--accent)}
#sd-body .slot-grid small{display:block;margin-top:6px}
#sd-body .slot-details{overflow-x:auto;max-width:100%}
#sd-body .start-slots .lgpills{flex-wrap:wrap}
#sd-body{min-width:0}
#sx-body img{max-width:100%;height:auto}
`;

/**
 * 把主播页自己的样式注入一次。附属脚本口只收 .js，样式进不了 assets。
 */
function ensureStyle() {
  if (document.getElementById('streamers-page-style')) return;
  const style = document.createElement('style');
  style.id = 'streamers-page-style';
  style.textContent = PAGE_STYLE;
  document.head.appendChild(style);
}

/**
 * 建出列表、详情、某一场三块外壳。控制台只给一个空的 section#page-streamers。
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  ensureStyle();
  section.innerHTML = `
    <div id="sv-list">
      <div class="nv-card hcard">
        <h3>全部主播 <span class="h-note" id="st-window"></span></h3>
        <div class="stats" id="st-all"></div>
      </div>
      <div class="nv-card hcard">
        <h3>每位主播 <span class="h-note">右侧折线为最近几天每天的场次</span></h3>
        <div id="st-list"></div>
      </div>
      <div id="st-unlisted"></div>
    </div>
    <div id="sv-detail" style="display:none">
      <div class="loghead">
        <div>
          <a class="daystep" id="sd-back" href="#/streamers">‹ 返回主播列表</a>
        </div>
      </div>
      <div class="nv-card hcard" id="sd-head"></div>
      <div class="lgpills" id="sd-tabs"></div>
      <div id="sd-body"></div>
    </div>
    <div id="sv-session" style="display:none">
      <div class="loghead">
        <div>
          <a class="daystep" id="sx-back" href="#/streamers">‹ 返回场次列表</a>
        </div>
      </div>
      <h3 class="cat-title" id="sx-title">场次</h3>
      <div id="sx-body"></div>
    </div>`;
}

/**
 * 按地址栏取这一页该看的那一份。顶级页刷新会带上 {sub, tail}，
 * 解析仍走 parseStreamersHash(location.hash)，与拼地址用的是同一份。
 */
export function refresh() {
  return loadStreamers();
}

/** 离开本页时把报告图的临时地址撤掉 */
export function leave() {
  liveActive = false;
  viewGeneration++;
  stopLiveRefresh();
  releaseReport();
}

/** 当前落在哪一块，真源是地址栏 */
let where = parseStreamersHash('');
let liveActive = false;
let liveTimer = null;
let liveGeneration = 0;
let viewGeneration = 0;
let livePending = null;
let liveKey = '';
let live = null;
let liveError = '';

function stopLiveRefresh() {
  if (liveTimer !== null) clearInterval(liveTimer);
  liveTimer = null;
  liveGeneration++;
  livePending = null;
}

function canRefreshLive() {
  const current = parseStreamersHash(location.hash);
  return current.view === 'detail' && current.pane === 'overview'
    && current.platform === where.platform && current.uid === where.uid
    && liveActive && !document.hidden && isStreamersHash(location.hash)
    && where.view === 'detail' && where.pane === 'overview' && detail?.success
    && detail.configured !== false
    && liveKey === where.platform + '/' + where.uid;
}

function startLiveRefresh() {
  if (!canRefreshLive() || liveTimer !== null) return;
  liveTimer = setInterval(loadLive, 15000);
}

// 隐藏时撤表；再看这一页时立即检查一次，再继续每 15 秒刷新。
document.addEventListener('visibilitychange', async () => {
  if (document.hidden) stopLiveRefresh();
  else if (canRefreshLive()) {
    startLiveRefresh();
    await loadLive();
  }
});

/**
 * 这台机器的运行状态，只取「开没开累计数据」那一位
 *
 * 与详情各取各的：它那一趟晚一点回来时，横条会自己补上。合成一趟的话，
 * /api/status 慢一拍就把整张详情页一起拖住，而那一页的主体与这一位无关。
 */
let runtime = {};

/** 图片资源的临时地址。换一场或离开本页时要撤销，否则这一页会一直占着那几张图 */
let reportUrl = '';

/**
 * 把地址栏读进来，并决定显示三块里的哪一块
 *
 * 与取数据分开一个口子，是为了首屏那一次：路由先摆版式、随后才载入数据，
 * 两件事合成一个函数的话，直接打开某一场的地址会先闪一下列表。
 */
export function syncStreamersView() {
  viewGeneration++;
  stopLiveRefresh();
  where = parseStreamersHash(location.hash);
  $('#sv-list').style.display = where.view === 'list' ? '' : 'none';
  $('#sv-detail').style.display = where.view === 'detail' ? '' : 'none';
  $('#sv-session').style.display = where.view === 'session' ? '' : 'none';
}

/** 进入本页，或在本页里换了地址：按落点取该取的那一份 */
export function loadStreamers() {
  // 宿主刷新各插件页（refreshPages）时不分在不在屏幕上，这一页也被叫到。
  // 地址栏第一段不是本页名字的（正落在推送页那类地址上），取了也用不上——
  // 照后两段读出一位主播再去问接口，问回来的是一位不存在的人，白挨 404
  if (!isStreamersHash(location.hash)) {
    liveActive = false;
    viewGeneration++;
    stopLiveRefresh();
    return;
  }
  const next = parseStreamersHash(location.hash);
  if (liveActive && detail?.success && $('#sd-insights')
    && where.view === 'detail' && next.view === 'detail'
    && where.pane === 'overview' && next.pane === 'overview'
    && where.platform === next.platform && where.uid === next.uid && where.days !== next.days) {
    where = next;
    renderTabs();
    return loadInsights();
  }
  liveActive = true;

  syncStreamersView();
  releaseReport();

  // 「开没开累计数据」只有详情页的概况用得上，别的两块不问——每翻一页都顺带问一次运行状态，
  // 在一台配了几十位主播的机器上是白花的一趟
  if (where.view === 'detail') {
    api('/status').then(state => {
      runtime = state || {};
      renderBanner();
    }).catch(() => {});
  }

  if (where.view === 'list') return loadList();
  if (where.view === 'detail') return loadDetail();
  return loadSession();
}

/** 离开本页时把图片的临时地址撤掉 */
export function releaseReport() {
  if (reportUrl) {
    URL.revokeObjectURL(reportUrl);
    reportUrl = '';
  }
}

// ---------------------------------------------------------------- 列表

async function loadList() {
  let data;
  try {
    data = await api('/streamers');
  } catch (e) {
    say('主播列表加载失败：' + e.message, 'err');
    return;
  }

  const list = data.streamers || [];
  const days = data.days || 0;
  $('#st-window').textContent = days ? '最近 ' + days + ' 天' : '';

  const totals = summaryTotals(list, days);
  $('#st-all').innerHTML = [
    [String(totals.total), '在册主播'],
    [String(totals.living), '正在直播'],
    [String(totals.sessions), '最近 ' + totals.days + ' 天场次'],
    [fmtDuration(totals.durationSeconds), '最近 ' + totals.days + ' 天时长'],
  ].map(([value, label]) => '<div class="stat"><div class="stat-v">' + esc(value)
    + '</div><div class="stat-l">' + esc(label) + '</div></div>').join('');

  renderRows(list, days);
  renderUnlisted(data.notConfigured || []);

  // 服务端加了一档状态而界面的措辞表没跟上时说一句。不说的话，那一档会照接口的措辞
  // 静静地显示出来——界面不至于坏，但没有任何东西提起「这里该补一句定稿的说法」
  const missing = uncoveredStatuses(data.statuses);
  if (missing.length) {
    say('服务端返回了界面尚未适配的主播状态：' + missing.join('、')
      + '。这些状态按服务端提供的名称显示', 'err');
  }
}

/**
 * 每位主播一行
 * @param list 接口给的 streamers[]
 * @param days 折线画几天
 */
function renderRows(list, days) {
  const host = $('#st-list');
  host.innerHTML = '';

  if (!list.length) {
    host.appendChild(el('div', 'empty'));
    host.lastChild.textContent = '尚未配置任何主播。请在「推送」页左上角点击「＋ 添加主播」。';
    return;
  }

  for (const one of list) {
    const row = el('a', 'rowcard');
    row.href = detailHash(one.platform, one.uid);
    row.setAttribute('aria-label', one.uname || String(one.uid));

    const main = el('div', 'rc-main');
    const name = el('div', 'rc-nm');
    name.textContent = one.uname || String(one.uid);
    name.appendChild(chip(one));
    main.appendChild(name);

    const sub = el('div', 'rc-sub');
    // 直播间号与 uid 一并写出：群里的人报的是直播间号，控制台里配的是 uid，
    // 两者对不上时，只显示其中一个的那一行谁也认不出是不是同一位
    sub.textContent = 'uid ' + one.uid + (one.roomId ? ' · 直播间 ' + one.roomId : '')
      + ' · ';
    const summary = el('span');
    summary.textContent = rowSubtitle(one, days);
    summary.title = '一天从早上 6 点算起，凌晨 0–6 点开播的场次算在前一天';
    sub.appendChild(summary);
    main.appendChild(sub);
    row.appendChild(main);

    row.appendChild(spark(one.series));

    host.appendChild(row);
  }
}

/**
 * 一行右边那条小折线
 *
 * 画的是场次数不是时长：一行里只摆得下一条线，而「哪几天播了」比「那几天播了多久」
 * 更接近这一列要回答的问题。时长那一路在详情的趋势页上。
 * @param series 接口给的七天
 * @return {HTMLElement} 折线那一块
 */
function spark(series) {
  const box = el('div');
  box.style.cssText = 'display:flex;gap:10px;align-items:center;flex:0 0 auto';

  const points = seriesValues(series, 'sessions');
  const total = points.reduce((sum, one) => sum + one, 0);
  const text = el('div');
  const value = el('div', 'rc-sub');
  value.style.marginTop = '0';
  value.textContent = total + ' 场';
  text.appendChild(value);
  box.appendChild(text);

  const geometry = sparkline(points);
  // 点数不足画不出线。此时整块不画，而不是画一条含 NaN 的路径——后者在屏幕上是一片空白，
  // 与「这几天一场没播」长得一样，而那一档本该是一条贴着底的平线
  if (!geometry) return box;

  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + geometry.width + ' ' + geometry.height);
  svg.setAttribute('class', 'spark' + (geometry.rising ? ' up' : ''));
  svg.setAttribute('aria-hidden', 'true');

  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', geometry.d);
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.6');
  path.setAttribute('vector-effect', 'non-scaling-stroke');
  svg.appendChild(path);

  const dot = document.createElementNS(ns, 'circle');
  dot.setAttribute('cx', String(geometry.cx));
  dot.setAttribute('cy', String(geometry.cy));
  dot.setAttribute('r', '2');
  dot.setAttribute('fill', 'currentColor');
  svg.appendChild(dot);

  box.appendChild(svg);
  return box;
}

/**
 * 状态标
 * @param one 列表项或详情顶层
 * @return {HTMLElement} 一枚标
 */
function chip(one) {
  const state = statusChip(one);
  const pill = el('span', state.cls);
  if (state.dot) pill.appendChild(el('span', 'dotlive'));
  const text = el('span');
  text.textContent = state.text;
  pill.appendChild(text);
  return pill;
}

/**
 * 归档里有、名单里没有的那几位
 * @param list 接口给的 notConfigured[]
 */
function renderUnlisted(list) {
  const host = $('#st-unlisted');
  host.innerHTML = '';
  if (!list.length) return;

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '已归档、但不在推送配置中的主播 ';
  const note = el('span', 'h-note');
  note.textContent = '已从推送配置中删除，过去的场次仍保留在归档中';
  head.appendChild(note);
  card.appendChild(head);

  for (const one of list) {
    const row = el('div', 'rowcard');
    const main = el('div', 'rc-main');
    const name = el('div', 'rc-nm');
    const link = el('a');
    link.href = detailHash(one.platform, one.uid);
    link.textContent = one.uname || String(one.uid);
    name.appendChild(link);
    main.appendChild(name);
    const sub = el('div', 'rc-sub');
    sub.textContent = 'uid ' + one.uid + ' · 已归档 ' + one.sessions + ' 场';
    main.appendChild(sub);
    row.appendChild(main);
    card.appendChild(row);
  }

  host.appendChild(card);
}

// ---------------------------------------------------------------- 详情

/** 详情响应供概况、场次与趋势使用 */
let detail = null;

async function loadDetail() {
  const generation = viewGeneration;
  $('#sd-back').setAttribute('href', '#/streamers');

  const query = where.pane === 'sessions' ? 'page=' + where.page
    : (where.pane === 'trend' ? 'period=' + where.period : '');
  const path = '/streamers/' + encodeURIComponent(where.platform) + '/'
    + encodeURIComponent(where.uid) + (query ? '?' + query : '');

  try {
    const response = await api(path);
    if (generation !== viewGeneration || !liveActive) return;
    detail = response;
  } catch (e) {
    say('主播详情加载失败：' + e.message, 'err');
    return;
  }

  if (!detail.success) {
    $('#sd-head').innerHTML = '';
    $('#sd-tabs').innerHTML = '';
    $('#sd-body').innerHTML = '<p class="empty">' + esc(detail.message || '没有该主播的记录。') + '</p>';
    return;
  }

  renderHead();
  renderTabs();
  const key = where.platform + '/' + where.uid;
  if (key !== liveKey) {
    insightsMetric = 'revenue';
    slotsMetric = 'revenue';
  }
  if (key !== liveKey || !detail.living
    || (live && detail.since != null && live.since !== detail.since)) {
    liveKey = key;
    live = null;
    liveError = '';
  }
  renderPane();
  if (where.pane === 'overview') {
    startLiveRefresh();
    await Promise.all([loadLive(), loadInsights()]);
  } else if (where.pane === 'audience') await loadAudience();
  else if (where.pane === 'start-slots') await loadStartSlots();
}

function renderHead() {
  const host = $('#sd-head');
  host.innerHTML = '';

  const head = el('div', 'shead');
  if (detail.face) {
    const face = el('img', 'sav');
    face.src = detail.face;
    face.alt = '';
    head.appendChild(face);
  }

  const mid = el('div', 'sh-m');
  const name = el('div', 'sh-nm');
  const text = el('span');
  text.textContent = detail.uname || String(detail.uid);
  name.appendChild(text);
  name.appendChild(chip(detail));
  mid.appendChild(name);

  const id = el('div', 'sh-id');
  id.textContent = 'uid ' + detail.uid + (detail.roomId ? ' · 直播间 ' + detail.roomId : '')
    + ' · ' + detail.platform;
  mid.appendChild(id);
  // 不在册的那位要说一句。只按状态显示的话，他会被写成「已停用」——
  // 而停用是配置里的一个决定，压根没在册是另一回事
  if (detail.configured === false) {
    const off = el('div', 'sh-id');
    off.textContent = '该主播不在推送配置中：以下数据来自过去的归档，目前既不采集也不推送。';
    mid.appendChild(off);
  }
  head.appendChild(mid);

  const side = el('div', 'sh-side');
  const push = el('a', 'nv-btn2');
  push.href = '#/push/' + encodeURIComponent(detail.uid);
  push.textContent = '推送设置';
  side.appendChild(push);
  head.appendChild(side);

  host.appendChild(head);
}

const audienceStates = new Map();

function renderTabs() {
  const host = $('#sd-tabs');
  host.innerHTML = '';
  if (where.pane === 'audience') host.appendChild(audienceRanges());
  const stateKey = where.platform + '/' + where.uid;
  if (where.pane === 'audience') audienceStates.set(stateKey, {...where});
  const audienceState = audienceStates.get(stateKey)
    || {...where, group: 'all', sort: 'attendance', page: 1};
  const tabs = el('div', 'lgpills');
  host.appendChild(tabs);
  for (const [key, label] of TABS) {
    const link = el('a', 'nv-pill lgpill');
    link.href = key === 'audience' ? audienceHash(audienceState)
      : detailHash(detail.platform, detail.uid, key, key === 'overview' ? 'days=' + where.days : '');
    link.textContent = label;
    if (key === where.pane) link.setAttribute('aria-current', 'page');
    tabs.appendChild(link);
  }
}

function renderPane() {
  const host = $('#sd-body');
  host.innerHTML = '';
  if (where.pane === 'sessions') renderSessions(host);
  else if (where.pane === 'trend') renderTrend(host);
  else if (where.pane === 'audience') {
    const card = el('div', 'nv-card hcard');
    card.id = 'sd-audience';
    host.appendChild(card);
  } else if (where.pane === 'start-slots') renderStartSlots(host);
  else renderOverview(host);
  renderBanner();
}

function audienceHash(changes = {}) {
  const state = {...where, ...changes};
  return detailHash(state.platform, state.uid, 'audience', 'days=' + state.days
    + '&group=' + state.group + '&sort=' + state.sort + '&page=' + state.page);
}

async function loadAudience() {
  const generation = viewGeneration;
  renderAudience(null, '正在读取观众名单…');
  try {
    const response = await api('/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/audience?days=' + where.days + '&group=' + where.group
      + '&sort=' + where.sort + '&page=' + where.page);
    if (generation !== viewGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取观众名单');
    renderAudience(response.audience);
  } catch (e) {
    if (generation === viewGeneration && liveActive) renderAudience(null, '读取失败：' + e.message);
  }
}

function audienceRanges() {
  const ranges = el('div', 'lgpills');
  for (const days of [7, 30, 90]) {
    const link = el('a', 'nv-pill lgpill');
    link.href = audienceHash({days, page: 1});
    link.textContent = '近 ' + days + ' 天';
    if (days === where.days) link.setAttribute('aria-current', 'page');
    ranges.appendChild(link);
  }
  return ranges;
}

function renderAudience(data, message) {
  const host = $('#sd-audience');
  if (!host) return;
  host.innerHTML = '';
  if (!data) {
    const hint = el('p', 'hint');
    hint.textContent = message;
    host.appendChild(hint);
    return;
  }
  const groups = el('div', 'lgpills');
  for (const [key, label] of audienceGroups(data.counts)) {
    const link = el('a', 'nv-pill lgpill');
    link.href = audienceHash({group: key, page: 1});
    link.textContent = label;
    if (key === data.group) link.setAttribute('aria-current', 'page');
    groups.appendChild(link);
  }
  host.appendChild(groups);
  const note = el('p', 'hint');
  note.textContent = coverageText(data.coverage);
  host.appendChild(note);
  const explanation = el('p', 'hint');
  explanation.textContent = '首次记录指已有记录中第一次出现，不一定是第一次观看。流水按礼物、醒目留言与大航海合计；到过的场次缺少逐人金额时显示「—」。';
  host.appendChild(explanation);
  const scroll = el('div');
  scroll.style.overflowX = 'auto';
  const table = el('table');
  const head = el('thead');
  const headers = el('tr');
  for (const [key, label] of AUDIENCE_COLUMNS) {
    const th = el('th');
    if (['attendance', 'revenue', 'recent'].includes(key)) {
      th.setAttribute('aria-sort', key === data.sort ? 'descending' : 'none');
      const link = el('a');
      link.href = audienceHash({sort: audienceSort(data.sort, key), page: 1});
      link.textContent = label + (key === data.sort ? ' ↓' : '');
      th.appendChild(link);
    } else th.textContent = label;
    headers.appendChild(th);
  }
  head.appendChild(headers);
  table.appendChild(head);
  const body = el('tbody');
  for (const person of data.items || []) {
    const cells = audienceCells(person);
    const row = el('tr');
    const name = el('td');
    const button = el('button', 'daystep');
    button.type = 'button';
    button.textContent = cells.name;
    button.setAttribute('aria-expanded', 'false');
    name.appendChild(button);
    row.appendChild(name);
    for (const key of ['attendance', 'recent', 'revenue', 'interaction']) {
      const cell = el('td');
      cell.textContent = cells[key];
      if (key === 'recent') {
        const first = el('small', 'hint');
        first.style.display = 'block';
        first.textContent = cells.first;
        cell.appendChild(first);
      }
      row.appendChild(cell);
    }
    body.appendChild(row);
    const expanded = el('tr');
    expanded.hidden = true;
    const visitCell = el('td');
    visitCell.colSpan = 5;
    const visits = el('ul');
    for (const visit of person.visits || []) {
      const item = el('li');
      const link = el('a');
      link.href = sessionHash(where.platform, where.uid, visit.startTime);
      link.textContent = new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai',
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
        hourCycle: 'h23'}).format(new Date(visit.startTime)) + ' · ' + (visit.title || '未记录标题');
      item.appendChild(link);
      visits.appendChild(item);
    }
    visitCell.appendChild(visits);
    expanded.appendChild(visitCell);
    body.appendChild(expanded);
    button.addEventListener('click', () => {
      expanded.hidden = !expanded.hidden;
      button.setAttribute('aria-expanded', String(!expanded.hidden));
    });
  }
  table.appendChild(body);
  scroll.appendChild(table);
  host.appendChild(scroll);
  if (!data.items?.length) {
    const empty = el('p', 'hint');
    empty.textContent = '该范围和分组暂无观众名单。';
    host.appendChild(empty);
  }
  const pager = el('div', 'lgpills');
  for (const [page, label] of [[data.page - 1, '上一页'], [data.page + 1, '下一页']]) {
    if (page < 1 || page > data.pages) continue;
    const link = el('a', 'daystep');
    link.href = audienceHash({page});
    link.textContent = label;
    pager.appendChild(link);
  }
  const text = el('span', 'hint');
  text.textContent = '第 ' + data.page + ' / ' + Math.max(1, data.pages) + ' 页 · 共 ' + data.total + ' 人';
  pager.appendChild(text);
  host.appendChild(pager);
}

let slotsMetric = 'revenue';
let slotsData = null;
let slotsSelected = null;
let slotsMessage = '';

async function loadStartSlots() {
  const generation = viewGeneration;
  slotsData = null;
  slotsSelected = null;
  slotsMessage = '正在读取归档数据…';
  renderPane();
  try {
    const response = await api('/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/start-slots?days=' + slotRangeDays(where));
    if (generation !== viewGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取归档数据');
    slotsData = response.startSlots;
    slotsMessage = '';
  } catch (error) {
    if (generation !== viewGeneration || !liveActive) return;
    slotsMessage = '读取失败：' + error.message;
  }
  renderPane();
}

function renderStartSlots(host) {
  const card = el('div', 'nv-card hcard start-slots');
  const head = el('h3');
  head.textContent = '开播时段表现';
  card.appendChild(head);
  const ranges = el('div', 'lgpills');
  for (const days of [90, 180]) {
    const link = el('a', 'nv-pill lgpill');
    link.href = detailHash(where.platform, where.uid, 'start-slots', 'slotDays=' + days);
    link.textContent = '近 ' + days + ' 天';
    if (days === slotRangeDays(where)) link.setAttribute('aria-current', 'page');
    ranges.appendChild(link);
  }
  card.appendChild(ranges);
  const metrics = el('div', 'lgpills');
  for (const [metric, text] of [['revenue', '每小时流水（元/时）'], ['messages', '每小时弹幕（条/时）']]) {
    const button = el('button', 'nv-pill lgpill');
    button.type = 'button';
    button.textContent = text;
    button.setAttribute('aria-pressed', String(slotsMetric === metric));
    button.addEventListener('click', () => { slotsMetric = metric; renderPane(); });
    metrics.appendChild(button);
  }
  card.appendChild(metrics);
  if (slotsData) {
    const topHead = el('h4');
    topHead.textContent = '表现较好的开播时段';
    card.appendChild(topHead);
    for (const text of slotTopText(slotsMetric === 'revenue' ? slotsData.topRevenue : slotsData.topMessages, slotsMetric)) {
      const line = el('p', 'hint');
      line.textContent = text;
      card.appendChild(line);
    }
    const scroll = el('div', 'slot-scroll');
    scroll.setAttribute('tabindex', '0');
    scroll.setAttribute('role', 'region');
    scroll.setAttribute('aria-label', '开播时段表现，可左右滚动');
    const table = el('table', 'slot-grid');
    const thead = el('thead');
    const headers = el('tr');
    for (const text of ['星期 / 开播钟点', ...Array.from({length: 12}, (_, i) => (i * 2) + '–' + (i * 2 + 2) + ' 时')]) {
      const th = el('th');
      th.setAttribute('scope', 'col');
      th.textContent = text;
      headers.appendChild(th);
    }
    thead.appendChild(headers);
    table.appendChild(thead);
    const tbody = el('tbody');
    const maximum = Math.max(0, ...slotsData.cells.filter(c => c.sessions >= 3).map(c => c[slotsMetric + 'PerHour'] || 0));
    for (let day = 1; day <= 7; day++) {
      const tr = el('tr');
      const th = el('th');
      th.setAttribute('scope', 'row');
      th.textContent = '周' + '一二三四五六日'[day - 1];
      tr.appendChild(th);
      for (const cell of slotsData.cells.filter(c => c.weekday === day)) {
        const td = el('td');
        const button = el('button');
        button.type = 'button';
        const shown = slotCell(cell, slotsMetric, maximum);
        button.innerHTML = esc(shown.text) + '<small>' + esc(shown.count) + '</small>';
        button.setAttribute('aria-label', slotLabel(cell) + ' · ' + shown.text + ' · ' + shown.count);
        button.setAttribute('aria-pressed', String(slotsSelected === cell));
        if (shown.intensity !== null && shown.intensity > 0) {
          button.style.background = 'color-mix(in srgb, var(--accent) ' + (12 + shown.intensity * 48) + '%, transparent)';
        }
        button.addEventListener('click', () => { slotsSelected = cell; renderPane(); });
        td.appendChild(button);
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    scroll.appendChild(table);
    card.appendChild(scroll);
    if (slotsSelected) {
      const head = el('h4');
      head.textContent = slotLabel(slotsSelected) + ' · ' + slotsSelected.sessions + ' 场';
      card.appendChild(head);
      const list = el('div', 'slot-details');
      if (!slotsSelected.sessionItems.length) list.textContent = '该时段没有场次。';
      else {
        const table = el('table');
        table.innerHTML = '<thead><tr><th>开播时间（东八区）</th><th>时长</th><th>整场'
          + (slotsMetric === 'revenue' ? '流水' : '弹幕') + '</th></tr></thead><tbody>'
          + slotsSelected.sessionItems.map(item => '<tr><td><a href="'
            + esc(sessionHash(where.platform, where.uid, item.startTime)) + '">'
            + esc(insightsTime(item.startTime)) + '</a></td><td>' + esc(fmtDuration(item.durationSeconds))
            + '</td><td>' + esc(slotTotal(item, slotsMetric)) + '</td></tr>').join('') + '</tbody>';
        list.appendChild(table);
      }
      card.appendChild(list);
    }
  } else {
    const hint = el('p', 'hint');
    hint.textContent = slotsMessage || '正在读取归档数据…';
    card.appendChild(hint);
  }
  const note = el('p', 'hint');
  note.textContent = '时段按实际星期与钟表时间，场次按开播时刻归入；数值是这些场次整场总量 ÷ 有效直播时长，不表示该两小时内的产出。少于 3 场为样本不足。';
  card.appendChild(note);
  host.appendChild(card);
}

let insightsGeneration = 0;
let insightsMetric = 'revenue';

async function loadInsights() {
  const generation = ++insightsGeneration;
  const view = viewGeneration;
  renderInsights(null, '正在读取归档数据…');
  try {
    const response = await api('/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/insights?days=' + where.days);
    if (generation !== insightsGeneration || view !== viewGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取归档数据');
    renderInsights(response.insights, '', response.living);
  } catch (e) {
    if (generation === insightsGeneration && view === viewGeneration && liveActive) {
      renderInsights(null, '读取失败：' + e.message);
    }
  }
}

function renderInsights(data, message, living) {
  const host = $('#sd-insights');
  if (!host) return;
  host.innerHTML = '';
  const head = el('h3');
  head.textContent = '近 ' + where.days + ' 天';
  host.appendChild(head);
  const ranges = el('div', 'lgpills');
  for (const days of [7, 30, 90]) {
    const link = el('a', 'nv-pill lgpill');
    link.href = detailHash(where.platform, where.uid, 'overview', 'days=' + days);
    link.textContent = '近 ' + days + ' 天';
    if (days === where.days) link.setAttribute('aria-current', 'page');
    ranges.appendChild(link);
  }
  host.appendChild(ranges);
  if (!data) {
    const hint = el('p', 'hint');
    hint.textContent = message;
    host.appendChild(hint);
    return;
  }
  const range = el('p', 'hint');
  range.textContent = insightsTime(data.from) + ' 至今'
    + (living ? ' · 正在直播的场次下播后才计入' : '');
  range.title = '一天从早上 6 点算起，凌晨 0–6 点开播的场次算在前一天';
  host.appendChild(range);
  const stats = el('div', 'stats');
  function stat(value, label, note, title, key) {
    const cell = el('div', 'stat');
    cell.innerHTML = '<div class="stat-v">' + esc(value) + '</div><div class="stat-l">' + esc(label)
      + '</div>' + (note ? '<small>' + esc(note) + '</small>' : '');
    const comparison = insightsComparison(data.comparisons?.[key]);
    if (comparison) {
      const line = el('small');
      line.textContent = comparison.text;
      line.style.display = 'block';
      line.style.color = comparison.color;
      cell.appendChild(line);
    }
    if (title) cell.title = title;
    stats.appendChild(cell);
    return cell;
  }
  stat(fmtDuration(data.durationSeconds), '直播时长', '共 ' + data.sessions + ' 场', '', 'durationSeconds');
  if (data.metricsKnown) {
    const count = value => value == null ? '暂无数据' : fmtMetric(value) + ' 人';
    const revenue = fmtMetric(data.revenue, {money: true});
    const revenueCell = stat(revenue + ' 元', '流水', '', '', 'revenue');
    revenueCell.classList.add('stat-revenue');
    revenueCell.style.setProperty('--revenue-width', revenue.length * 0.65 + 1.5);
    stat(fmtMetric(data.messages) + ' 条', '弹幕', '', '', 'messages');
    stat(count(data.audience), '观众', '', '', 'audience');
    stat(count(data.interactionAudience), '互动观众', '', '', 'interactionAudience');
    const fans = data.fans;
    const change = fans ? (fans.change > 0 ? '+' : fans.change < 0 ? '−' : '') + fmtMetric(Math.abs(fans.change)) : '暂无数据';
    const tooltip = fans ? (fans.partial ? '从 ' + insightsTime(fans.fromAt) + ' 起算；' : '')
      + insightsTime(fans.fromAt) + ' 的 ' + fmtMetric(fans.fromValue) + ' 人 → '
      + insightsTime(fans.toAt) + ' 的 ' + fmtMetric(fans.toValue) + ' 人' : '可用的粉丝数快照不足两次';
    stat(change, '粉丝净增', '', tooltip);
  }
  host.appendChild(stats);
  const explanation = comparisonExplanation(data);
  if (explanation.count || explanation.note) {
    const details = el('details', 'hint');
    const summary = el('summary');
    summary.textContent = '对比说明 · ' + explanation.count + ' 项未比较';
    details.appendChild(summary);
    for (const group of explanation.groups) {
      const line = el('p');
      line.textContent = group.reason + '：' + group.metrics.join('、');
      details.appendChild(line);
    }
    if (explanation.note) {
      const note = el('p');
      note.textContent = explanation.note;
      details.appendChild(note);
    }
    host.appendChild(details);
  }
  if (data.metricsKnown) {
    const composition = el('p', 'hint');
    composition.textContent = data.composition
      ? '本期首次记录 ' + fmtMetric(data.composition.firstRecorded) + ' 人 · 回访 '
        + fmtMetric(data.composition.returning) + ' 人' : '本期构成：暂无数据';
    composition.title = data.composition
      ? '首次记录指 NovaBot 第一次记录到该观众，不一定是其第一次观看直播'
      : data.firstRecordedAt == null ? '尚未记录到带名单的场次'
      : data.audience == null ? '所选时段内没有带名单的场次，无法区分首次记录与回访'
      : '名单从 ' + insightsTime(data.firstRecordedAt, false)
        + '起才开始记录，此前来过的观众无法区分';
    host.appendChild(composition);
    const text = coverageText(data.coverage);
    if (text) {
      const note = el('p', 'hint');
      note.textContent = text;
      host.appendChild(note);
    }
  }
  const metrics = data.metricsKnown ? [['durationSeconds', '时长'], ['revenue', '流水'],
    ['messages', '弹幕'], ['audience', '观众']] : [['durationSeconds', '时长']];
  if (!metrics.some(([key]) => key === insightsMetric)) insightsMetric = metrics[0][0];
  const switches = el('div', 'lgpills');
  for (const [key, text] of metrics) {
    const button = el('button', 'nv-pill lgpill');
    button.textContent = text;
    button.type = 'button';
    button.setAttribute('aria-pressed', String(key === insightsMetric));
    button.addEventListener('click', () => {
      insightsMetric = key;
      renderInsights(data, '', living);
    });
    switches.appendChild(button);
  }
  host.appendChild(switches);
  const days = data.daily || [];
  if (!days.length) return;
  const points = days.map(day => insightsPoint(day, insightsMetric));
  const geometry = barGeometry(points.map(p => p.value), 1000, 100);
  const bars = geometry.map((bar, i) => {
    const point = points[i];
    if (!point.draw) return '<rect fill="transparent" x="' + (i * 1000 / days.length)
      + '" y="0" width="' + (1000 / days.length) + '" height="100"><title>' + esc(point.title) + '</title></rect>';
    return '<rect class="c-bar' + (bar.zero ? ' zero' : '') + '" x="' + bar.x + '" y="' + bar.y
      + '" width="' + bar.w + '" height="' + bar.h + '"><title>' + esc(point.title) + '</title></rect>';
  }).join('');
  const chart = el('div', 'chart');
  chart.innerHTML = '<div class="plot"><svg viewBox="0 0 1000 100" preserveAspectRatio="none" role="img" aria-label="按统计日的'
    + esc(metrics.find(([key]) => key === insightsMetric)[1]) + '走势">' + bars + '</svg></div>'
    + '<div class="xlab"><span>' + esc(days[0].date) + '</span><span>' + esc(days[days.length - 1].date) + '</span></div>';
  host.appendChild(chart);
}

/**
 * 概况顶上那条小横条
 *
 * 单独一个口子，是因为运行状态与详情各走各的一趟：先回来的那一趟画完之后，
 * 另一趟回来时补上这一条。合在渲染里的话，运行状态晚回来的那几次这条横条就不出现了。
 */
function renderBanner() {
  const host = $('#sd-body');
  const old = $('#sd-banner');
  if (old) old.remove();

  const text = totalDataBanner(runtime);
  if (!text || where.view !== 'detail' || where.pane !== 'overview') return;

  const banner = el('div', 'banner b-dim');
  banner.id = 'sd-banner';
  banner.textContent = text;
  host.insertBefore(banner, host.firstChild);
}

function renderOverview(host) {
  const recent = el('div', 'nv-card hcard');
  recent.id = 'sd-insights';
  host.appendChild(recent);
  const overview = detail.overview || {};

  const stats = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '累计 ';
  const note = el('span', 'h-note');
  note.textContent = '该主播在归档中的全部场次';
  head.appendChild(note);
  stats.appendChild(head);

  const box = el('div', 'stats');
  box.innerHTML = [
    [String(overview.sessions || 0), '总场次'],
    [fmtDuration(overview.durationSeconds), '总时长'],
    // 一场都没有时服务端给 null，这里就写破折号：「场均 0 分」读起来像每场都秒退
    [overview.averageDurationSeconds === null || overview.averageDurationSeconds === undefined
      ? '—' : fmtDuration(overview.averageDurationSeconds), '场均时长'],
    [fmtTime(overview.lastStart), '最近一场'],
  ].map(([value, label]) => '<div class="stat"><div class="stat-v">' + esc(value)
    + '</div><div class="stat-l">' + esc(label) + '</div></div>').join('');
  stats.appendChild(box);
  host.appendChild(stats);

  host.appendChild(snapshotCard(overview.snapshot, overview.snapshotMetrics));
  const current = el('div', 'nv-card hcard');
  current.id = 'sd-live';
  host.appendChild(current);
  renderLive();
  host.appendChild(recentCard());
}

/** 只读当前这一场；失败时保留上一次成功读到的数据。 */
async function loadLive() {
  if (!canRefreshLive() || livePending === liveGeneration) return;
  const generation = liveGeneration;
  livePending = generation;
  try {
    const response = await request(CONSOLE + '/api/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/live?series=online_count');
    if (response.status === 401) {
      location.reload();
      return;
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error('HTTP ' + response.status);
    }
    if (generation !== liveGeneration || !canRefreshLive()) return;
    if (response.status < 200 || response.status >= 300
      || data?.success !== true || typeof data.living !== 'boolean') {
      throw new Error(data?.message || data?.error || 'HTTP ' + response.status);
    }
    live = data;
    liveError = '';
  } catch (error) {
    if (generation !== liveGeneration || !canRefreshLive()) return;
    liveError = error.message || '网络错误';
  } finally {
    if (livePending === generation) livePending = null;
  }
  renderLive();
}

function liveClock(ms, seconds = false) {
  if (ms === null || ms === undefined) return '—';
  const date = new Date(Number(ms));
  const pad = value => String(value).padStart(2, '0');
  return pad(date.getHours()) + ':' + pad(date.getMinutes())
    + (seconds ? ':' + pad(date.getSeconds()) : '');
}

/** 概况中的本场卡片，独立更新，累计与归档卡片不使用这份数。 */
function renderLive() {
  const card = $('#sd-live');
  if (!card) return;
  card.hidden = !(live ? live.living : detail?.living);
  card.innerHTML = '';
  if (card.hidden) return;

  const header = el('div', 'live-head');
  const head = el('h3');
  head.textContent = '正在直播';
  header.appendChild(head);
  const refresh = el('button', 'nv-btn2');
  refresh.type = 'button';
  refresh.textContent = '刷新';
  refresh.addEventListener('click', loadLive);
  header.appendChild(refresh);
  card.appendChild(header);

  const times = el('p', 'live-times');
  const since = live ? live.since : detail.since;
  times.textContent = '开播于 ' + fmtTime(since)
    + (since != null ? ' · 已直播 ' + fmtDuration(Math.max(0, (Date.now() - since) / 1000)) : '')
    + ' · 数据更新至 ' + liveClock(live?.dataAt)
    + ' · 检查于 ' + liveClock(live?.checkedAt, true);
  card.appendChild(times);

  const catalog = detail.liveMetrics || detail.sessions?.metrics || [];
  const labels = new Map(catalog.map(one => [one.key, one]));
  const metrics = {...(live?.metrics || {})};
  // 报告图的在线人数使用 online_count：分钟内取最大，最新分钟的值不是本场峰值。
  const online = live?.series?.online_count || [];
  if (detail.platform === 'bilibili') {
    delete metrics.online_count;
    delete metrics.online_rank_count;
  }
  const rows = snapshotRows(metrics, catalog);
  if (detail.platform === 'bilibili') {
    rows.push({key: 'online_count', name: '在线人数', unit: '人', known: true,
      value: online.length ? online[online.length - 1][1] : null});
  }
  const metricList = el('dl', 'kv');
  for (const row of rows) {
    const label = el('dt');
    label.textContent = row.known ? row.name : row.key;
    if (!row.known) label.title = '本机采集到一项插件尚未命名的指标，按原始键名显示';
    metricList.appendChild(label);
    const value = el('dd');
    value.textContent = fmtMetric(row.value, labels.get(row.key))
      + (row.value != null && row.unit ? ' ' + row.unit : '');
    metricList.appendChild(value);
  }
  card.appendChild(metricList);

  if (liveError) {
    const error = el('p', 'live-error');
    error.setAttribute('role', 'status');
    error.textContent = '刷新失败：' + liveError + (live
      ? '，当前显示的是 ' + liveClock(live.checkedAt, true) + ' 的数据' : '，尚未获取到本场数据');
    card.appendChild(error);
  }
}

/**
 * 最近一次采到的基础数据
 * @param snapshot 接口给的 overview.snapshot
 * @param catalog 接口给的 overview.snapshotMetrics，指标叫什么由平台插件自报
 * @return {HTMLElement} 一张卡
 */
function snapshotCard(snapshot, catalog) {
  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '基础数据 ';
  const note = el('span', 'h-note');
  note.textContent = snapshot ? '采集于 ' + fmtTime(snapshot.at) : '';
  head.appendChild(note);
  card.appendChild(head);

  const rows = snapshotRows(snapshot ? snapshot.metrics : null, catalog);
  if (!rows.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    // 「从来没采到过」与「采到了但全是 0」是两回事，说清是哪一种
    empty.textContent = snapshot
      ? '本次采样没有任何数据。'
      : '尚未采集到该主播的基础数据：粉丝数等数据由平台插件按固定间隔采样保存。';
    card.appendChild(empty);
    return card;
  }

  const list = el('dl', 'kv');
  for (const row of rows) {
    const key = el('dt');
    // 目录里没有的键原样显示，并标一句：藏起来的话，插件新采了一项的那一天屏幕上不会有任何变化
    key.textContent = row.known ? row.name : row.key;
    if (!row.known) key.title = '本机采集到一项插件尚未命名的指标，按原始键名显示';
    list.appendChild(key);
    const value = el('dd');
    value.textContent = Math.round(row.value).toLocaleString('en-US') + (row.unit ? ' ' + row.unit : '');
    list.appendChild(value);
  }
  card.appendChild(list);
  return card;
}

/**
 * 概况里那张「最近几场」
 * @return {HTMLElement} 一张卡
 */
function recentCard() {
  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '最近几场 ';
  const note = el('span', 'h-note');
  note.textContent = '点击一行可查看该场报告';
  head.appendChild(note);
  card.appendChild(head);

  const page = detail.sessions || {};
  const items = (page.items || []).slice(0, 3);
  if (!items.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    empty.textContent = '暂无已归档的场次。每场直播下播时会自动归档一条记录。';
    card.appendChild(empty);
    return card;
  }

  card.appendChild(sessionTable(items, page.metrics || []));
  const more = el('div', 'tl-foot');
  const link = el('a');
  link.href = detailHash(detail.platform, detail.uid, 'sessions');
  link.textContent = '全部 ' + (page.total || 0) + ' 场 →';
  more.appendChild(link);
  card.appendChild(more);
  return card;
}

function renderSessions(host) {
  const page = detail.sessions || {};
  const items = page.items || [];

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '场次 ';
  const note = el('span', 'h-note');
  note.textContent = '点击一行可查看该场详情';
  head.appendChild(note);
  card.appendChild(head);

  if (!items.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    empty.textContent = '暂无已归档的场次。每场直播下播时会自动归档一条记录。';
    card.appendChild(empty);
    host.appendChild(card);
    return;
  }

  card.appendChild(sessionTable(items, page.metrics || []));

  const bar = pageBar(page);
  const foot = el('div', 'tl-foot');
  const text = el('span', 'dim');
  text.style.marginRight = '12px';
  text.textContent = bar.text;
  foot.appendChild(text);
  if (bar.prev) foot.appendChild(pageLink('‹ 上一页', bar.prev));
  if (bar.next) foot.appendChild(pageLink('下一页 ›', bar.next));
  card.appendChild(foot);

  const tips = el('p', 'hint');
  tips.style.margin = '10px 0 0';
  const hidden = shownMetrics(page.metrics || [], items).hidden;
  tips.textContent = [
    '被中断或未闭合的场次已标出：其时长与营收不能与正常场次直接比较，判断趋势时应单独看待。',
    '时长后带 ⚠ 的场次有采集缺口，其计数仅为下界；程序停机与直播间断线两种缺口分别计算，不相加。',
    '人气峰显示「—」的场次早于「峰值入归档」：并非峰值为 0，而是该场的曲线已不存在。',
    hidden ? '本页场次中全部为零的 ' + hidden + ' 项指标未列出。' : '',
  ].filter(Boolean).join('');
  card.appendChild(tips);

  host.appendChild(card);
}

/**
 * 翻页那两个链接
 * @param label 写什么
 * @param page 去第几页
 * @return {HTMLElement} 一个链接
 */
function pageLink(label, page) {
  const link = el('a', 'daystep');
  link.style.marginRight = '8px';
  link.href = detailHash(detail.platform, detail.uid, 'sessions', 'page=' + page);
  link.textContent = label;
  return link;
}

/**
 * 场次表
 *
 * 整行可点：点进去是那一场的报告。用 tr 上挂事件而不是把每一格都做成链接——
 * 一行八格各套一个 a，键盘走一遍要按八次，而它们指的是同一处。
 * 开播时间那一格是一个真链接，指向同一处：只用键盘的人 Tab 一行停一下，回车打开。
 * @param items 本页场次
 * @param metrics 指标说明
 * @return {HTMLElement} 表格那一块
 */
function sessionTable(items, metrics) {
  const {shown} = shownMetrics(metrics, items);
  // 人气峰按第一项指标算：核心并不知道哪一项是弹幕（指标名由各平台自定），
  // 而第一项正是那个平台自己排在最前的那一项，运营分析的每周曲线用的也是它
  const peakOf = shown.length ? shown[0] : null;

  const wrap = el('div');
  wrap.style.overflowX = 'auto';
  const table = document.createElement('table');
  table.style.width = 'max-content';
  table.style.minWidth = '100%';

  table.innerHTML = '<thead><tr><th>开播</th><th class="n">时长</th>'
    + shown.map(one => '<th class="n">' + esc(one.name)
        + (one.unit ? '<br><small>' + esc(one.unit) + '</small>' : '') + '</th>').join('')
    + (peakOf ? '<th class="n">' + esc(peakOf.name) + '峰<br><small>每分钟</small></th>' : '')
    + '<th>标题</th><th>结束原因</th></tr></thead>';

  const body = document.createElement('tbody');
  for (const item of items) {
    const gaps = gapCells(item);
    const peak = peakOf ? peakCell(item, peakOf.key) : null;
    const {title, changed} = sessionTitle(item);

    const hash = sessionHash(item.platform, item.uid, item.startTime);
    const row = document.createElement('tr');
    if (item.interrupted) row.className = 'empty-row';
    row.style.cursor = 'pointer';
    row.innerHTML = '<td class="n"><a href="' + esc(hash) + '">' + esc(fmtTime(item.startTime)) + '</a></td>'
      + '<td class="n"' + (gaps.title ? ' title="' + esc(gaps.title) + '"' : '') + '>'
      + esc(fmtDuration(item.durationSeconds)) + (gaps.any ? ' ⚠' : '') + '</td>'
      + shown.map(one => '<td class="n">'
          + esc(fmtMetric((item.metrics || {})[one.key] || 0, one)) + '</td>').join('')
      + (peak ? '<td class="n"' + (peak.known ? '' : ' title="该场早于「峰值入归档」，当时的曲线已不存在"')
          + '>' + esc(peak.known ? Math.round(peak.value).toLocaleString('en-US') : '—') + '</td>' : '')
      + '<td title="' + esc(title) + '">'
      + esc((title.length > 18 ? title.slice(0, 18) + '…' : title) + changed) + '</td>'
      + '<td>' + esc(item.interrupted ? item.endReasonText : '正常') + '</td>';
    // 点在链接上时由链接自己去：新标签页打开那几种点法不该再把这一页也带走
    row.addEventListener('click', event => {
      if (event.target && event.target.closest && event.target.closest('a')) return;
      location.hash = hash;
    });
    body.appendChild(row);
  }

  table.appendChild(body);
  wrap.appendChild(table);
  return wrap;
}

function renderTrend(host) {
  const trend = detail.trend || {};
  const buckets = trend.buckets || [];
  const name = trend.period === 'month' ? '月' : '周';

  const bar = el('div', 'nv-card hcard');
  const tool = el('div', 'backups');
  const label = el('span');
  label.textContent = '周期';
  tool.appendChild(label);
  for (const [key, text] of PERIODS) {
    const link = el('a', 'nv-pill lgpill');
    link.href = detailHash(detail.platform, detail.uid, 'trend', 'period=' + key);
    link.textContent = text;
    if (key === trend.period) link.setAttribute('aria-current', 'page');
    tool.appendChild(link);
  }
  const scope = el('span');
  scope.style.marginLeft = 'auto';
  scope.textContent = '共归档 ' + ((detail.sessions || {}).total || 0) + ' 场'
    + (trend.droppedPeriods ? ' · 更早的 ' + trend.droppedPeriods + ' 个' + name + '未显示' : '');
  tool.appendChild(scope);
  bar.appendChild(tool);
  host.appendChild(bar);

  if (!buckets.length) {
    const empty = el('div', 'nv-card hcard');
    const text = el('p', 'hint');
    text.style.margin = '0';
    text.textContent = '暂无归档数据。每场直播下播时会自动归档一条记录，直播过一场后此处即有内容。';
    empty.appendChild(text);
    host.appendChild(empty);
    return;
  }

  const metrics = trend.metrics || [];
  host.appendChild(chartCard('每' + name + '直播时长', buckets,
    one => one.durationSeconds / 3600, value => value.toFixed(1) + ' 时'));
  if (metrics.length) {
    // 图名走 textContent，不在这里 esc：再转义一遍的话，带 & 号的指标名会显示成 &amp;
    host.appendChild(chartCard('每' + name + metrics[0].name, buckets,
      one => (one.metrics || {})[metrics[0].key] || 0,
      value => fmtMetric(value, metrics[0])));
  }

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '明细';
  card.appendChild(head);

  const wrap = el('div');
  wrap.style.overflowX = 'auto';
  const table = document.createElement('table');
  table.style.width = 'max-content';
  table.style.minWidth = '100%';
  table.innerHTML = '<thead><tr><th>' + (name === '月' ? '月份' : '周')
    + '</th><th class="n">场次</th><th class="n">时长</th>'
    + metrics.map(one => '<th class="n">' + esc(one.name)
        + (one.unit ? '<br><small>' + esc(one.unit) + '</small>' : '') + '</th>').join('')
    + '</tr></thead>';
  table.innerHTML += '<tbody>' + buckets.slice().reverse().map(one =>
    '<tr' + (one.sessions ? '' : ' class="empty-row"') + '><td>' + esc(one.label) + '</td>'
    + '<td class="n">' + one.sessions + '</td>'
    + '<td class="n">' + esc(fmtDuration(one.durationSeconds)) + '</td>'
    // 没播的周期各项写破折号而不是 0：那一周没有这项数据，与「这项是 0」不是一回事
    + metrics.map(m => '<td class="n">'
        + esc(one.sessions ? fmtMetric((one.metrics || {})[m.key] || 0, m) : '—')
        + '</td>').join('') + '</tr>').join('') + '</tbody>';
  wrap.appendChild(table);
  card.appendChild(wrap);

  const notes = el('p', 'hint');
  notes.style.margin = '10px 0 0';
  notes.textContent = [
    !trend.metricsKnown ? '未找到该平台的指标说明，只能统计场次与时长。' : '',
    '时长与各项指标按开播时刻归入周期，跨过早上 6 点的直播，整场算在开播那一天。',
    '一天从早上 6 点算起，凌晨 0–6 点开播的场次算在前一天。',
    metrics.length
      ? '开播时的粉丝数等「快照」指标不参与累加：十场粉丝数相加的结果不对应任何真实数值。' : '',
    '4.3.0 之前归档的场次没有结束原因、标题记录与缺口统计，一律显示为正常结束、无缺口。',
  ].filter(Boolean).join('');
  card.appendChild(notes);
  host.appendChild(card);
}

/**
 * 一张柱状图
 *
 * 柱子用 SVG（preserveAspectRatio="none" 才能横向铺满），但文字一律放在 SVG 外面：
 * 同一个属性会把文字一起横向拉伸，宽屏上只是略胖，窄屏上会挤成一团认不出来。
 * @param title 图名
 * @param buckets 周期
 * @param pick 取哪一项
 * @param format 一个数写成什么
 * @return {HTMLElement} 一张卡
 */
function chartCard(title, buckets, pick, format) {
  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = title;
  card.appendChild(head);

  const points = buckets.map(pick);
  const geometry = barGeometry(points, 1000, 100);
  const bars = geometry.map((one, i) => '<rect class="c-bar' + (one.zero ? ' zero' : '')
    + '" x="' + one.x + '" y="' + one.y + '" width="' + one.w + '" height="' + one.h
    + '"><title>' + esc(buckets[i].label + '：' + format(points[i])) + '</title></rect>').join('');

  const chart = el('div', 'chart');
  chart.innerHTML = '<div class="plot"><svg viewBox="0 0 1000 100" preserveAspectRatio="none">'
    + bars + '</svg></div>'
    + '<div class="xlab"><span>' + esc(buckets[0].label) + '</span><span>'
    + esc(buckets[buckets.length - 1].label) + '</span></div>';
  card.appendChild(chart);
  return card;
}

// ---------------------------------------------------------------- 某一场

async function loadSession() {
  $('#sx-back').setAttribute('href', detailHash(where.platform, where.uid, 'sessions'));
  $('#sx-title').textContent = '单场报告';

  const host = $('#sx-body');
  host.innerHTML = '';

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '报告 ';
  const note = el('span', 'h-note');
  note.textContent = '开播于 ' + fmtTime(Number(where.start));
  head.appendChild(note);
  card.appendChild(head);

  const body = el('div');
  card.appendChild(body);
  host.appendChild(card);

  paint(body, reportView('loading'));

  let response;
  try {
    response = await request(CONSOLE + '/api' + reportPath(where.platform, where.uid, where.start));
  } catch (e) {
    paint(body, reportView('missing', '报告图获取失败：' + e.message));
    return;
  }

  if (!response.ok) {
    // 那一头回的是一句人话，它分得清「没开下播报告」与「早于留明细那个功能」。
    // 界面自己编一句只会把两种情形说成同一种
    let message = '';
    try {
      message = (await response.json()).message || '';
    } catch (e) {
      message = '';
    }
    paint(body, reportView('missing', message));
    return;
  }

  releaseReport();
  reportUrl = URL.createObjectURL(await response.blob());
  paint(body, reportView('ok'));
}

/**
 * 把三态摆上屏幕
 * @param host 容器
 * @param state 判法给的那一份
 */
function paint(host, state) {
  host.innerHTML = '';
  if (state.image) {
    const image = el('img');
    image.src = reportUrl;
    image.alt = '该场直播报告';
    image.style.cssText = 'max-width:100%;border-radius:var(--r-card);display:block';
    host.appendChild(image);
    return;
  }
  const text = el('p', 'hint');
  text.style.margin = '0';
  text.textContent = state.text;
  host.appendChild(text);
}
