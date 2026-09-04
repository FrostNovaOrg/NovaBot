/**
 * 主播页的判定：地址栏 ↔ 三块子视图、状态标、折线几何、场次一行怎么读、快照裸键换人话
 *
 * 这几件事全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * 混在渲染代码里的话，「贴出去的地址打开还是不是同一张画面」「几个月前那一场的人气峰
 * 该显示 0 还是「—」」这类分支就只能靠人手点，而手点点不出它们。
 *
 * 服务端那一份（丙5 的 /api/streamers）已经把状态闭集、七天折线的空日补零、
 * 两种缺口分开给这几条办完了。这里判的是它管不着的那一半：地址栏怎么读怎么写、
 * 拿到手的数字在屏幕上摆成什么样、以及取不到的东西该说哪一句。
 */

import {totalDataOff} from './home-model.js';

/**
 * 详情页的三个页签，闭集
 *
 * 第三段落在这三个词上时是页签，落在一串数字上时是某一场——见 parseStreamersHash。
 * 两种取值共用同一段路径，是因为场次是主播底下的东西，地址上摆成
 * #/streamers/<平台>/<uid>/<开播时刻> 才能一眼看出它属于谁。
 */
export const TABS = [['overview', '概况'], ['sessions', '场次'], ['trend', '趋势']];

/**
 * 这一页的三块子视图，闭集
 *
 * 列表、某位主播的详情、某一场。解析与拼地址都从这张表认——另写一个字符串的话，
 * 新开一块只改了地址，解析仍退回列表，屏幕上像页面卡住了。
 */
export const STREAMER_VIEWS = ['list', 'detail', 'session'];

/** 趋势那一栏的两种周期，与服务端认的值一致 */
export const PERIODS = [['week', '按周'], ['month', '按月']];

/**
 * 状态四档在屏幕上写什么
 *
 * 🔴 <b>闭集本身以接口下发的那一份为准</b>（/api/streamers 的 statuses），这里只管措辞：
 * 服务端的 statusText 是「只采集不推送」，而定稿要的是带顿号的「只采集，不推送」。
 * 把整张表搬到界面上来的代价是服务端加一档时这里会漏，因此认不出的档一律退回
 * 接口给的那句话，见 statusChip——<b>漏一档的表现是那一行的标不见了</b>，
 * 而不见了与「这位主播没有状态」在屏幕上长得一样。
 */
const STATUS_TEXT = {
  LIVE: '在播',
  OFFLINE: '未开播',
  COLLECT_ONLY: '只采集，不推送',
  DISABLED: '已停用',
};

/** 折线的画布。摆在这里而不是写在渲染代码里：几何算得对不对要能喂值量 */
const SPARK_WIDTH = 88;

const SPARK_HEIGHT = 26;

/**
 * 快照指标的裸键换人话
 *
 * 服务端把最近一次采样<b>原样</b>交出来、一个字段都不挑（见 StreamerController.overview）：
 * 核心并不知道各平台会采什么，把某个平台的键名写进核心，装第二个平台时那一栏
 * 要么空着要么显示错东西。换人话这件事因此只能落在界面上。
 *
 * 🔴 认不出的键<b>原样显示</b>，不许藏起来：藏起来之后，插件新采了一项指标的那一天，
 * 屏幕上不会有任何变化——而「这台机器采到了一项我不认识的东西」正是该看见的事。
 */
const SNAPSHOT_LABELS = {
  fans: {name: '粉丝', unit: '人'},
  fans_medal: {name: '粉丝团', unit: '人'},
  guard: {name: '大航海', unit: '人'},
};

const DIGITS = /^\d+$/;

/**
 * 地址栏 → 这一页此刻该显示哪一块
 *
 * 认不出来的一律退回列表，不留白屏：主播页是从首页、推送页、初始设置第 4 步三个地方
 * 点进来的，其中任何一处把地址拼错，得到的都该是一张能用的列表，而不是一位不存在的主播。
 *
 * 🔴 uid 必须是一串数字才认。不校验的话，#/streamers/&lt;平台&gt;/undefined 会被当成
 * 一位真主播拿去问接口，屏幕上于是出现一张空的详情页——它与「这位主播还没播过」长得一样。
 * @param hash location.hash
 * @return {{view: string, platform: string, uid: string, pane: string, start: string,
 *           page: number, period: string}} 子视图与它的参数
 *
 * 页签那一栏叫 pane 不叫 tab：{@code tab} 是 store 上一份共享状态的名字（当前页签），
 * 在界面文件里裸着出现即为 ReferenceError，因此有一条判据在盯着它——它当场逮住了这一处。
 */
export function parseStreamersHash(hash) {
  const raw = String(hash || '').replace(/^#\/?/, '');
  const cut = raw.indexOf('?');
  const path = (cut < 0 ? raw : raw.slice(0, cut)).split('/').filter(Boolean);
  const query = cut < 0 ? '' : raw.slice(cut + 1);

  const [viewList, viewDetail, viewSession] = STREAMER_VIEWS;
  const state = {view: viewList, platform: '', uid: '', pane: 'overview', start: '',
    page: 1, period: 'week'};

  const platform = path[1] || '';
  const uid = path[2] || '';
  // 平台名与 uid 缺一不可：主键是这两样合起来的，只给一半找不出唯一一位主播
  if (!platform || !DIGITS.test(uid)) return state;

  state.view = viewDetail;
  state.platform = decode(platform);
  state.uid = uid;

  const seg = path[3] || '';
  if (DIGITS.test(seg)) {
    // 第三段是一串数字＝某一场的开播时刻。它与三个页签共用这一段，
    // 靠形状分辨：页签是词，开播时刻是毫秒时间戳
    state.view = viewSession;
    state.start = seg;
  } else if (TABS.some(one => one[0] === seg)) {
    state.pane = seg;
  }

  for (const part of query.split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    const key = eq < 0 ? part : part.slice(0, eq);
    const value = eq < 0 ? '' : decode(part.slice(eq + 1));
    // 页码只认正整数。写错值时退回第 1 页而不是拿它去问服务端——
    // 那一头会把越界的页码夹回来，于是地址栏说第 9 页而屏幕上是第 1 页
    if (key === 'page' && DIGITS.test(value) && Number(value) > 0) state.page = Number(value);
    else if (key === 'period' && PERIODS.some(one => one[0] === value)) state.period = value;
  }
  return state;
}

/**
 * 解码地址栏里的一段，坏转义不至于让整页打不开
 * @param text 原文
 * @return {string} 解码后的文本
 */
function decode(text) {
  try {
    return decodeURIComponent(text);
  } catch (e) {
    return text;
  }
}

/**
 * 某一位主播的地址
 *
 * 🔴 拼地址只留这一份。各处自己拼的话，平台名里带斜杠或中文的那一天，
 * 有的地方转义了有的没转义，而两种地址在屏幕上长得一模一样。
 * @param platform 平台
 * @param uid 主播 uid
 * @param pane 页签，概况可省
 * @param query 附加查询串，如 page=2
 * @return {string} hash 地址
 */
export function detailHash(platform, uid, pane, query) {
  const base = '#/streamers/' + encodeURIComponent(platform) + '/' + encodeURIComponent(uid);
  const path = pane && pane !== 'overview' ? base + '/' + pane : base;
  return query ? path + '?' + query : path;
}

/**
 * 某一场的地址
 * @param platform 平台
 * @param uid 主播 uid
 * @param start 开播时刻（毫秒）
 * @return {string} hash 地址
 */
export function sessionHash(platform, uid, start) {
  return '#/streamers/' + encodeURIComponent(platform) + '/' + encodeURIComponent(uid)
    + '/' + encodeURIComponent(start);
}

/**
 * 某一场报告图的地址
 * @param platform 平台
 * @param uid 主播 uid
 * @param start 开播时刻（毫秒）
 * @return {string} 接口路径，相对 /config/api
 */
export function reportPath(platform, uid, start) {
  return '/streamers/' + encodeURIComponent(platform) + '/' + encodeURIComponent(uid)
    + '/sessions/' + encodeURIComponent(start) + '/report';
}

/**
 * 一位主播的状态标
 *
 * 状态是闭集里的一项，一行只显示一个；「在播」另带一个圆点，因为它是唯一
 * 会自己变的那一档——其余三档都是配置事实，改了才变。
 * @param item 列表项或详情顶层
 * @return {{text: string, cls: string, dot: boolean}} 标上写什么、什么颜色、带不带圆点
 */
export function statusChip(item) {
  const one = item || {};
  const known = STATUS_TEXT[one.status];
  // 认不出的档退回接口给的那句话，再退回状态名本身。三级都空着才留空——
  // 而那意味着这一行连状态都没有，此时不画标比画一个空标诚实
  const text = known || one.statusText || one.status || '';
  const live = one.status === 'LIVE';
  return {text, cls: live ? 'pill live' : 'pill', dot: live};
}

/**
 * 接口下发的状态闭集里，措辞表没跟上的那几档
 *
 * 服务端加一档而这边没加时，那一档会退回接口给的措辞照常显示（见 statusChip）——
 * 界面不至于坏，但定稿的措辞就对不上了。把差集点名报出来，收笔时才看得见该补什么。
 * @param statuses 接口给的 [{name, text}]
 * @return {string[]} 措辞表里没有的状态名
 */
export function uncoveredStatuses(statuses) {
  return (statuses || []).map(one => one.name).filter(name => !STATUS_TEXT[name]);
}

/**
 * 七天折线的取值
 *
 * 服务端已经保证恰好七点、空日给 0（见 StreamerController.series）。这里再兜一次底，
 * 是因为界面拿到的可能是一台旧版服务端的回包：那时这一栏要么没有，要么跳过了没播的日子。
 * 少一点画出来的折线比真的连贯，而「哪几天没播」正是这条线要看的东西。
 * @param series 接口给的 [{date, sessions, durationSeconds}]
 * @param key 画哪一项，sessions 或 durationSeconds
 * @return {number[]} 逐日取值
 */
export function seriesValues(series, key) {
  return (series || []).map(point => Number((point || {})[key || 'sessions']) || 0);
}

/**
 * 折线的几何：路径与末端那个点
 *
 * 全零时不缩放（否则 0/0 出 NaN，整条线消失），画成贴着底的一条直线——
 * 「这七天一场没播」本身就该是一条平的线，而不是一片空白。
 * @param points 逐点取值
 * @return {{d: string, cx: number, cy: number, width: number, height: number, rising: boolean}|null}
 *         几何，点数不足时为 null
 */
export function sparkline(points) {
  const list = points || [];
  if (list.length < 2) return null;

  const min = Math.min.apply(null, list);
  const max = Math.max.apply(null, list);
  const range = (max - min) || 1;
  const y = value => SPARK_HEIGHT - 2 - ((value - min) / range) * (SPARK_HEIGHT - 6);

  let d = '';
  for (let i = 0; i < list.length; i++) {
    const x = (i / (list.length - 1)) * SPARK_WIDTH;
    d += (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y(list[i]).toFixed(1) + ' ';
  }

  return {
    d: d.trim(),
    cx: SPARK_WIDTH,
    cy: Number(y(list[list.length - 1]).toFixed(1)),
    width: SPARK_WIDTH,
    height: SPARK_HEIGHT,
    // 末点高于首点才算在涨。相等不算——七天都是 0 的那条线两头一样高，
    // 说它「在涨」是句假话
    rising: list[list.length - 1] > list[0],
  };
}

/**
 * 列表顶上那张汇总卡
 *
 * ⚠️ 只算<b>七天窗口内</b>的场次与时长：那两个数来自接口的 summary，而它本就是按窗口算的。
 * 「在播」与「共几位」不受窗口限制，它们说的是此刻。
 *
 * 停用的主播不计入场次与时长：他这七天不采集，把他上一次停用之前的场次算进来，
 * 会让「本周播了几场」比群里实际发生的多。但他仍计入「共几位」——他还在配置里。
 * @param list 接口给的 streamers[]
 * @param days 折线与汇总的窗口天数，接口给
 * @return {{days: number, total: number, living: number, sessions: number, durationSeconds: number}} 汇总
 */
export function summaryTotals(list, days) {
  let living = 0;
  let sessions = 0;
  let duration = 0;
  for (const one of list || []) {
    if (one.living) living++;
    if (one.status === 'DISABLED') continue;
    const summary = one.summary || {};
    sessions += Number(summary.sessions) || 0;
    duration += Number(summary.durationSeconds) || 0;
  }
  return {days: Number(days) || 0, total: (list || []).length, living, sessions,
    durationSeconds: duration};
}

/**
 * 场次表上「人气峰」那一格
 *
 * 🔴 三态，不是两态：
 * <ul>
 *   <li>{@code hasPeaks} 为假 ＝ 这条记录早于「峰值入归档」，那一场的序列早就没了。
 *   显示「—」——写 0 是句假话，我们不是知道它是 0，是不知道它是多少。</li>
 *   <li>有峰值 ＝ 显示它。</li>
 *   <li>有峰值这件事成立、但这一项没有 ＝ 那条曲线一个点都没有，显示 0。</li>
 * </ul>
 * 只分两态的话，几个月前的场次会整批显示成「人气峰 0」，而那正是这一格要防的误读。
 * @param item 场次
 * @param key 看哪一项指标的峰
 * @return {{known: boolean, value: number}} 知不知道，以及知道时是多少
 */
export function peakCell(item, key) {
  const one = item || {};
  if (one.hasPeaks === false) return {known: false, value: 0};
  const peak = (one.peaks || {})[key];
  return {known: true, value: peak ? Number(peak.value) || 0 : 0};
}

/**
 * 一场的两种采集缺口
 *
 * 🔴 <b>分开列，不相加</b>：程序停机期间这个房间当然也是断的，两段必然重叠，
 * 加起来就是重复计数。服务端把它们分两个字段给出来正是为了这个，
 * 界面上再加回去就等于把那份小心白费了。
 * @param item 场次
 * @return {{maintenance: number, outage: number, any: boolean, title: string}} 两种缺口与提示语
 */
export function gapCells(item) {
  const one = item || {};
  const maintenance = Number(one.maintenanceGapSeconds) || 0;
  const outage = Number(one.roomOutageSeconds) || 0;
  const parts = [];
  if (maintenance) parts.push('其中 ' + fmtGap(maintenance) + '因程序停机未采集');
  if (outage) parts.push('其中 ' + fmtGap(outage) + '因这个直播间断线未采集');
  return {
    maintenance, outage, any: maintenance > 0 || outage > 0,
    title: parts.length ? parts.join('；') + '。本行各项计数只是下界' : '',
  };
}

/**
 * 概况顶上那条小横条
 *
 * 判定与首页那条软待办<b>同一份</b>（见 home-model 的 totalDataOff）：两处各写一遍的话，
 * 同一台机器上首页说没开、这一页说开着，而两边的代码看起来都对。
 * @param status /api/status 回包
 * @return {string} 要说的那句话，没话说时为空串
 */
export function totalDataBanner(status) {
  return totalDataOff(status) ? '这台机器没开累计数据：群里只能查本场。' : '';
}

/**
 * 快照指标摆成一行行
 *
 * 认不出的键原样显示，且标出来它是认不出的那一类——见 SNAPSHOT_LABELS 上那段。
 * @param metrics 接口给的 snapshot.metrics
 * @return {{key: string, name: string, unit: string, value: number, known: boolean}[]} 逐项
 */
export function snapshotRows(metrics) {
  return Object.keys(metrics || {}).map(key => {
    const label = SNAPSHOT_LABELS[key];
    return {
      key,
      name: label ? label.name : key,
      unit: label ? label.unit : '',
      value: Number(metrics[key]) || 0,
      known: !!label,
    };
  });
}

/**
 * 场次表上留哪几列指标
 *
 * 横向的表，指标一多就没法读了。只留至少有一场非零的列——十几列清一色的 0
 * 除了把真正有数的那几列挤出屏幕之外没有任何作用。藏了几列要说，见调用方。
 * @param metrics 指标说明
 * @param items 本页场次
 * @return {{shown: object[], hidden: number}} 留下的列与藏起来的列数
 */
export function shownMetrics(metrics, items) {
  const list = metrics || [];
  const rows = items || [];
  const shown = list.filter(one => rows.some(row => Number((row.metrics || {})[one.key] || 0) !== 0));
  return {shown, hidden: list.length - shown.length};
}

/**
 * 分页条
 *
 * 页码由服务端夹过界（见 StreamerController.sessionPage），因此这里照它回的 page 画，
 * 不照地址栏里那个数——两者不一致时，地址栏说第 9 页而屏幕上是第 1 页。
 * @param page 接口给的 sessions 段
 * @return {{page: number, pages: number, prev: number, next: number, text: string}} 分页状态
 */
export function pageBar(page) {
  const one = page || {};
  const pages = Number(one.pages) || 0;
  const current = Math.max(1, Number(one.page) || 1);
  return {
    page: current,
    pages,
    prev: current > 1 ? current - 1 : 0,
    next: current < pages ? current + 1 : 0,
    text: pages > 1 ? '第 ' + current + ' / ' + pages + ' 页 · 共 ' + (Number(one.total) || 0) + ' 场'
      : '共 ' + (Number(one.total) || 0) + ' 场',
  };
}

/**
 * 报告图三态
 *
 * 取图这件事在屏幕上有三种样子，缺一种就会有一段时间什么也不说：
 * <ul>
 *   <li>正在取——缓存过期的老场次要现画，几百毫秒到几秒。不说的话，
 *   那几秒里屏幕上是一片空白，而空白与「这一场没有报告」长得一样。</li>
 *   <li>拿到了——显示图。</li>
 *   <li>没有——照服务端给的那句人话说。它分得清「没开下播报告」与「早于留明细那个功能」，
 *   而界面自己编一句只会把两种情形说成同一种。</li>
 * </ul>
 * @param phase loading / ok / missing
 * @param message 服务端给的那句话，只在 missing 时有
 * @return {{image: boolean, text: string}} 显不显示图、下面写哪一句
 */
export function reportView(phase, message) {
  if (phase === 'ok') return {image: true, text: ''};
  if (phase === 'loading') {
    return {image: false, text: '正在取这一场的报告图。缓存过期的老场次要现画，请稍候。'};
  }
  return {
    image: false,
    text: message || '这一场没有报告图，也没有留下明细数据，重新绘制不出来。',
  };
}

/**
 * 柱状图的几何
 *
 * 零值也留 2px 的痕迹，否则「这一周没播」和「这一周没有这项数据」在图上长得一样。
 * 全零时不缩放——0/0 出 NaN，整张图会消失。
 * @param points 逐柱取值
 * @param width 画布宽
 * @param height 画布高
 * @return {{x: number, y: number, w: number, h: number, zero: boolean}[]} 逐柱几何
 */
export function barGeometry(points, width, height) {
  const list = points || [];
  if (!list.length) return [];
  const max = Math.max.apply(null, list.concat([0]));
  const step = width / list.length;

  return list.map((value, i) => {
    const full = max > 0 ? height * (value / max) : 0;
    const h = Math.max(full, value ? 1 : 2);
    return {
      x: Number((i * step + step * 0.15).toFixed(1)),
      y: Number((height - h).toFixed(1)),
      w: Number((step * 0.7).toFixed(1)),
      h: Number(h.toFixed(1)),
      zero: !value,
    };
  });
}

/**
 * 时长的人话
 * @param seconds 秒
 * @return {string} 「2 时 04 分」这样的一段
 */
export function fmtDuration(seconds) {
  const total = Number(seconds) || 0;
  if (!total) return '0 分';
  const h = Math.floor(total / 3600);
  const m = Math.round((total % 3600) / 60);
  return h ? h + ' 时 ' + m + ' 分' : m + ' 分';
}

/**
 * 缺口的人话
 *
 * 不能用 fmtDuration：它按分钟四舍五入，一次 44 秒的重启会显示成「1 分」，
 * 而 20 秒的会显示成「0 分」。缺口本来就常在秒的量级，得留住秒。
 * @param seconds 秒
 * @return {string} 「22 分 10 秒」这样的一段
 */
export function fmtGap(seconds) {
  const total = Number(seconds) || 0;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return [h ? h + ' 时' : '', m ? m + ' 分' : '', s ? s + ' 秒' : ''].filter(Boolean).join(' ') || '0 秒';
}

/**
 * 一项指标的人话。钱按两位小数，其余取整加千分位
 * @param value 取值
 * @param metric 指标说明
 * @return {string} 一格里写什么
 */
export function fmtMetric(value, metric) {
  if (value === undefined || value === null) return '—';
  const one = metric || {};
  return one.money ? Number(value).toFixed(2) : Math.round(Number(value) || 0).toLocaleString('en-US');
}

/**
 * 时刻的人话，按浏览器所在时区
 * @param ms 毫秒时间戳
 * @return {string} 「9-04 20:07」
 */
export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(Number(ms));
  const p = n => String(n).padStart(2, '0');
  return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/**
 * 本场标题：取最后一次改成的那一个，并说明改过几次
 * @param item 场次
 * @return {{title: string, changed: string}} 标题与「（改过 N 次）」
 */
export function sessionTitle(item) {
  const titles = (item || {}).titles || [];
  const last = titles.length ? titles[titles.length - 1] : null;
  const count = Number((item || {}).titleChangeCount) || 0;
  return {title: (last && last.title) || '', changed: count ? '（改过 ' + count + ' 次）' : ''};
}

/**
 * 列表上一位主播的副标题
 *
 * 「最近一场」不受七天窗口限制（服务端 summary.lastStart 也不受），因此一位一个月没播的
 * 主播这里写得出他上次播是什么时候——写成「从来没播过」是假话。
 * @param item 列表项
 * @param days 窗口天数
 * @return {string} 副标题
 */
export function rowSubtitle(item, days) {
  const summary = (item || {}).summary || {};
  const window = Number(days) || Number(summary.days) || 0;
  const head = '最近 ' + window + ' 天 ' + (Number(summary.sessions) || 0) + ' 场 · '
    + fmtDuration(summary.durationSeconds);
  return summary.lastStart ? head + ' · 最近一场 ' + fmtTime(summary.lastStart) : head + ' · 还没有归档的场次';
}
