/**
 * 主播页：列表、详情（概况／场次／趋势）、某一场
 *
 * 本文件随控制台插件走，由控制台按注册清单装载，落在顶级导航。
 * 三个导出就是与控制台之间的全部约定：render 建出页容器里的三块、
 * refresh 按地址栏取数、leave 离开本页时撤掉报告图的临时地址。
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

import {$, api, el, esc, say} from './core.js';
import {
  PERIODS, TABS, barGeometry, detailHash, fmtDuration, fmtGap, fmtMetric, fmtTime,
  gapCells, pageBar, parseStreamersHash, peakCell, reportPath, reportView, rowSubtitle,
  seriesValues, sessionHash, sessionTitle, shownMetrics, snapshotRows, sparkline,
  statusChip, summaryTotals, totalDataBanner, uncoveredStatuses,
} from './streamers-model.js';

const PAGE_STYLE = `
.chart{margin-bottom:2px}
.chart .plot{position:relative;padding-top:6px}
.chart svg{display:block;width:100%;height:110px}
.chart .bar{fill:var(--accent)}
.chart .bar.zero{fill:var(--line)}
.chart .xlab{display:flex;justify-content:space-between;margin-top:6px;font-size:11px;color:var(--dim)}
.spark{width:88px;height:26px;flex:none;color:var(--dim)}
.spark.up{color:var(--accent)}
a.lgpill{text-decoration:none}
a.lgpill[aria-current="page"]{border-color:var(--accent);color:var(--accent);background:var(--soft)}
#sd-tabs{margin:0 0 14px}
#sd-body td.n,#sd-body th.n{text-align:right;font-variant-numeric:tabular-nums}
#sd-body tr.empty-row td{color:var(--dim)}
#sd-body tbody tr:hover{background:var(--soft)}
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
      <p class="hint">数据来自每场直播下播时的归档。<b>只在这里看得到，不会发到群里。</b>
        点一位主播看他的概况、场次与趋势；点一场看那一场的报告。</p>
      <div class="nv-card hcard">
        <h3>全部主播 <span class="h-note" id="st-window"></span></h3>
        <div class="stats" id="st-all"></div>
      </div>
      <div class="nv-card hcard">
        <h3>每位主播 <span class="h-note">右边那条小折线是最近几天的场次</span></h3>
        <div id="st-list"></div>
      </div>
      <div id="st-unlisted"></div>
    </div>
    <div id="sv-detail" style="display:none">
      <div class="loghead">
        <div>
          <a class="daystep" id="sd-back" href="#/streamers">‹ 回主播列表</a>
        </div>
      </div>
      <div class="nv-card hcard" id="sd-head"></div>
      <div class="lgpills" id="sd-tabs"></div>
      <div id="sd-body"></div>
    </div>
    <div id="sv-session" style="display:none">
      <div class="loghead">
        <div>
          <a class="daystep" id="sx-back" href="#/streamers">‹ 回场次列表</a>
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
  loadStreamers();
}

/** 离开本页时把报告图的临时地址撤掉 */
export function leave() {
  releaseReport();
}

/** 当前落在哪一块，真源是地址栏 */
let where = parseStreamersHash('');

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
  where = parseStreamersHash(location.hash);
  $('#sv-list').style.display = where.view === 'list' ? '' : 'none';
  $('#sv-detail').style.display = where.view === 'detail' ? '' : 'none';
  $('#sv-session').style.display = where.view === 'session' ? '' : 'none';
}

/** 进入本页，或在本页里换了地址：按落点取该取的那一份 */
export function loadStreamers() {
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
    say('载入主播列表失败：' + e.message, 'err');
    return;
  }

  const list = data.streamers || [];
  const days = data.days || 0;
  $('#st-window').textContent = days ? '最近 ' + days + ' 天' : '';

  const totals = summaryTotals(list, days);
  $('#st-all').innerHTML = [
    [String(totals.total), '在册主播'],
    [String(totals.living), '正在播'],
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
    say('这台服务端多出了界面还不认识的主播状态：' + missing.join('、')
      + '。它们照服务端给的说法显示', 'err');
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
    host.lastChild.textContent = '还没有配置任何主播。到「推送」页左上角点「＋ 添加主播」。';
    return;
  }

  for (const one of list) {
    const row = el('div', 'rowcard');

    const main = el('div', 'rc-main');
    const name = el('div', 'rc-nm');
    const link = el('a');
    link.href = detailHash(one.platform, one.uid);
    link.textContent = one.uname || String(one.uid);
    name.appendChild(link);
    name.appendChild(chip(one));
    main.appendChild(name);

    const sub = el('div', 'rc-sub');
    // 直播间号与 uid 一并写出：群里的人报的是直播间号，控制台里配的是 uid，
    // 两者对不上时，只显示其中一个的那一行谁也认不出是不是同一位
    sub.textContent = 'uid ' + one.uid + (one.roomId ? ' · 直播间 ' + one.roomId : '')
      + ' · ' + rowSubtitle(one, days);
    main.appendChild(sub);
    row.appendChild(main);

    row.appendChild(spark(one.series));

    const side = el('div', 'rc-side');
    const go = el('a', 'nv-btn2');
    go.href = detailHash(one.platform, one.uid);
    go.textContent = '打开';
    side.appendChild(go);
    row.appendChild(side);

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
  head.textContent = '归档里有、名单里没有 ';
  const note = el('span', 'h-note');
  note.textContent = '从推送配置里删掉了，但他过去的场次还留着';
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
    sub.textContent = 'uid ' + one.uid + ' · 归档里有 ' + one.sessions + ' 场';
    main.appendChild(sub);
    row.appendChild(main);
    card.appendChild(row);
  }

  host.appendChild(card);
}

// ---------------------------------------------------------------- 详情

/** 详情那一趟拿回来的整份，三个页签共用——换页签不再重取 */
let detail = null;

async function loadDetail() {
  $('#sd-back').setAttribute('href', '#/streamers');

  const query = where.pane === 'sessions' ? 'page=' + where.page
    : (where.pane === 'trend' ? 'period=' + where.period : '');
  const path = '/streamers/' + encodeURIComponent(where.platform) + '/'
    + encodeURIComponent(where.uid) + (query ? '?' + query : '');

  try {
    detail = await api(path);
  } catch (e) {
    say('载入主播详情失败：' + e.message, 'err');
    return;
  }

  if (!detail.success) {
    $('#sd-head').innerHTML = '';
    $('#sd-tabs').innerHTML = '';
    $('#sd-body').innerHTML = '<p class="empty">' + esc(detail.message || '没有这位主播的记录。') + '</p>';
    return;
  }

  renderHead();
  renderTabs();
  renderPane();
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
    off.textContent = '这位主播不在推送配置里：下面的数据来自他过去的归档，此刻既不采集也不推送。';
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

function renderTabs() {
  const host = $('#sd-tabs');
  host.innerHTML = '';
  for (const [key, label] of TABS) {
    const link = el('a', 'nv-pill lgpill');
    link.href = detailHash(detail.platform, detail.uid, key);
    link.textContent = label;
    if (key === where.pane) link.setAttribute('aria-current', 'page');
    host.appendChild(link);
  }
}

function renderPane() {
  const host = $('#sd-body');
  host.innerHTML = '';
  if (where.pane === 'sessions') renderSessions(host);
  else if (where.pane === 'trend') renderTrend(host);
  else renderOverview(host);
  renderBanner();
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
  const overview = detail.overview || {};

  const stats = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '累计 ';
  const note = el('span', 'h-note');
  note.textContent = '归档里这位主播的全部场次';
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

  host.appendChild(snapshotCard(overview.snapshot));
  host.appendChild(recentCard());
}

/**
 * 最近一次采到的基础数据
 * @param snapshot 接口给的 overview.snapshot
 * @return {HTMLElement} 一张卡
 */
function snapshotCard(snapshot) {
  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '基础数据 ';
  const note = el('span', 'h-note');
  note.textContent = snapshot ? '采于 ' + fmtTime(snapshot.at) : '';
  head.appendChild(note);
  card.appendChild(head);

  const rows = snapshotRows(snapshot ? snapshot.metrics : null);
  if (!rows.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    // 「从来没采到过」与「采到了但全是 0」是两回事，说清是哪一种
    empty.textContent = snapshot
      ? '这一次采样一项数据都没有。'
      : '还没有采到过这位主播的基础数据：粉丝数这类数字由平台插件按固定间隔采样留档。';
    card.appendChild(empty);
    return card;
  }

  const list = el('dl', 'kv');
  for (const row of rows) {
    const key = el('dt');
    // 认不出的键原样显示，并标一句：藏起来的话，插件新采了一项的那一天屏幕上不会有任何变化
    key.textContent = row.known ? row.name : row.key;
    if (!row.known) key.title = '这台机器采到了界面还不认识的一项指标，原样显示';
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
  note.textContent = '点一行看这一场的报告';
  head.appendChild(note);
  card.appendChild(head);

  const page = detail.sessions || {};
  const items = (page.items || []).slice(0, 3);
  if (!items.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    empty.textContent = '还没有归档的场次。每场直播下播时会自动记一条。';
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
  note.textContent = '点一行看这一场发生了什么';
  head.appendChild(note);
  card.appendChild(head);

  if (!items.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    empty.textContent = '还没有归档的场次。每场直播下播时会自动记一条。';
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
    '被中断或未闭合的场次已标出：它的时长与营收和正常场次不可比，做趋势判断时应当单独看待。',
    '时长后带 ⚠ 的有采集缺口，那几场的计数只是下界；程序停机与直播间断线两种缺口分开算，不相加。',
    '人气峰显示「—」的场次早于「峰值入归档」：不是峰值为 0，是那一场的曲线已经没有了。',
    hidden ? '这些场次里全为零的 ' + hidden + ' 项指标未列出。' : '',
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

    const row = document.createElement('tr');
    if (item.interrupted) row.className = 'empty-row';
    row.style.cursor = 'pointer';
    row.innerHTML = '<td class="n">' + esc(fmtTime(item.startTime)) + '</td>'
      + '<td class="n"' + (gaps.title ? ' title="' + esc(gaps.title) + '"' : '') + '>'
      + esc(fmtDuration(item.durationSeconds)) + (gaps.any ? ' ⚠' : '') + '</td>'
      + shown.map(one => '<td class="n">'
          + esc(fmtMetric((item.metrics || {})[one.key] || 0, one)) + '</td>').join('')
      + (peak ? '<td class="n"' + (peak.known ? '' : ' title="这一场早于「峰值入归档」，那时的曲线已经没有了"')
          + '>' + esc(peak.known ? Math.round(peak.value).toLocaleString('en-US') : '—') + '</td>' : '')
      + '<td title="' + esc(title) + '">'
      + esc((title.length > 18 ? title.slice(0, 18) + '…' : title) + changed) + '</td>'
      + '<td>' + esc(item.interrupted ? item.endReasonText : '正常') + '</td>';
    row.addEventListener('click',
      () => { location.hash = sessionHash(item.platform, item.uid, item.startTime); });
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
    text.textContent = '还没有归档数据。每场直播下播时会自动追加一条，播过一场之后这里就有内容了。';
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
    '时长与各项指标按开播时刻归入周期，跨零点的直播整场算在开播那一天。',
    metrics.length
      ? '开播时的粉丝数等「快照」指标不参与累加——把十场的粉丝数加起来不是任何一个真实数字。' : '',
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
  const bars = geometry.map((one, i) => '<rect class="bar' + (one.zero ? ' zero' : '')
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
  $('#sx-title').textContent = '这一场的报告';

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
    response = await fetch('/config/api' + reportPath(where.platform, where.uid, where.start));
  } catch (e) {
    paint(body, reportView('missing', '取这一场的报告图失败：' + e.message));
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
    image.alt = '这一场的直播报告';
    image.style.cssText = 'max-width:100%;border-radius:var(--r-card);display:block';
    host.appendChild(image);
    return;
  }
  const text = el('p', 'hint');
  text.style.margin = '0';
  text.textContent = state.text;
  host.appendChild(text);
}
