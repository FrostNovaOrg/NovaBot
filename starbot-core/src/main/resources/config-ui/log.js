/**
 * 日志页：一条按天翻的时间线，以及它的子页「工程日志」
 *
 * 本文件只管把东西摆上屏幕。地址栏怎么读怎么写、哪句空态说哪一句、工程日志怎么分段与筛，
 * 一律在 log-model.js 里判——判断留在这里的话，「贴出去的地址打开还是不是同一张画面」
 * 就只能靠人手点，而手点点不出「关键词里带 & 号」这类分支。
 *
 * 时间线的筛与搜都在服务端做（/api/timeline），界面这一侧一条都不筛：
 * 两处各筛一遍的话，翻页那一路会先取回二十条再自己扔掉十九条，
 * 而页脚那句「共 N 条」说的还是服务端那个数——屏幕上的两个数字对不上。
 */

import {$, api, clock, el, esc, say, today} from './core.js';
import {
  ENG_LEVELS, emptyText, engVisible, groupEngLines, hasFilter, logHash, newerDay, olderDay,
  parseLogHash, timelineQuery,
} from './log-model.js';

/** 当前筛选状态，真源是地址栏，见 syncFromHash */
let filters = parseLogHash('', today());

/** 有记录的日期，来自 /api/timeline/days，最近的在前 */
let calendar = [];

/**
 * 各日条数，来自同一趟 /api/timeline/days
 *
 * 空态那句话要靠它分辨「这一天什么都没发生」与「筛得太狠了」，而这个数<b>不能</b>
 * 拿本次查询的命中数来代替——那个数本身就是筛过的，两句话会因此永远只出一句。
 */
let dayCounts = {};

/** 事件类型闭集，由接口给出——界面自己写的那张表漏一项的表现是筛选框里少一个选项 */
let catalog = [];

/** 保留天数，来自同一趟 /api/timeline/days */
let retention = 0;

/** 接着往更旧翻的位置，空表示没有更早的了。它是「翻到哪儿了」，不是筛选，因此不进地址栏 */
let cursor = '';

/** 工程日志：四档级别各自开着没有，与搜索词。这两样只在本页内有效，不进地址栏 */
const engLevels = {error: true, warn: true, info: true, debug: true};

let engQuery = '';

/** 工程日志上一次读回来的原文行，改筛选时不必再读一遍盘 */
let engLines = [];

/**
 * 把地址栏读进筛选状态，并决定显示哪一半
 *
 * 只认地址栏、不认「刚才点了哪个控件」：刷新、收藏、后退三种进法走的都是这一条路。
 *
 * 与取数据分开一个口子，是为了首屏那一次：路由先摆版式、随后才载入数据，
 * 两件事合成一个函数的话，直接打开 #/log/eng 会先闪一下时间线那一半。
 * @return {boolean} 落在工程日志子页时为 true
 */
export function syncLogView() {
  filters = parseLogHash(location.hash, today());
  const eng = filters.view === 'eng';
  $('#log').style.display = eng ? 'none' : '';
  $('#logeng').style.display = eng ? '' : 'none';
  $('#log-back').setAttribute('href', logHash(Object.assign({}, filters, {view: 'timeline'}), today()));
  $('#log-eng-open').setAttribute('href', logHash(Object.assign({}, filters, {view: 'eng'}), today()));
  return eng;
}

/**
 * 把筛选状态写回地址栏
 *
 * 用 replaceState 而不是改 location.hash：后者会触发 hashchange，
 * 于是每敲一个字都整页重来一遍，而输入框会在重绘时丢掉光标。
 * 这也意味着改筛选不进浏览器的后退历史——按后退是「回上一页」，不是「撤销上一次筛选」。
 */
function writeHash() {
  try {
    history.replaceState(null, '', logHash(filters, today()));
  } catch (e) {
    // 某些浏览器在 file:// 下不许改地址栏。筛选照常生效，只是这一次贴不出去
  }
}

/** 进入本页：读日历与第一页，或读日志文件尾部 */
export function loadLog() {
  if (syncLogView()) {
    renderEngTools();
    renderNapCatEntry();
    loadEng();
    return;
  }
  loadTimeline();
}

// ============ 时间线 ============

async function loadTimeline() {
  try {
    const list = await api('/timeline/days');
    calendar = (list.days || []).map(item => item.date);
    dayCounts = {};
    for (const item of list.days || []) dayCounts[item.date] = item.count;
    catalog = list.types || [];
    retention = list.retentionDays || 0;
    renderDayBar();
  } catch (e) {
    say('载入日志日期失败：' + e.message, 'err');
    return;
  }
  cursor = '';
  await loadEvents(false);
}

/**
 * 取一页事件
 * @param append true 表示接着上一页往更旧的取
 */
async function loadEvents(append) {
  let found;
  try {
    found = await api('/timeline' + timelineQuery(filters, 0, append ? cursor : ''));
  } catch (e) {
    say('载入日志失败：' + e.message, 'err');
    return;
  }
  if (!found.success) {
    say(found.message || '载入日志失败', 'err');
    return;
  }

  cursor = found.nextCursor || '';
  renderFilterBar(found);
  renderEvents(found.events || [], append);
  renderFoot(found);
}

/**
 * 日期条：前一天、这一天、后一天，以及这一天有几条
 *
 * 改完筛选也要重画：那两个链接里带着当前筛选，不重画的话，
 * 筛完再点「前一天」会跳到一张<b>没筛过</b>的页，而使用者以为自己只是换了一天。
 */
function renderDayBar() {
  const bar = $('#log-daybar');
  const older = olderDay(calendar, filters.date);
  const newer = newerDay(calendar, filters.date);

  bar.innerHTML = step('‹ 前一天', older) + '<b>' + esc(filters.date)
    + (filters.date === today() ? '（今天）' : '') + '</b>' + step('后一天 ›', newer)
    + '<span class="dim">' + esc(dayCount() ? dayCount() + ' 条' : '这一天没有记录') + '</span>';

  // 保留期是这一页的边界：不写出来的话，「再往前没有了」看起来像是那几天什么都没发生
  $('#log-retention').textContent = retention > 0
    ? '只留最近 ' + retention + ' 天，更早的整日记录会自动删掉。'
    : '按当前配置不自动清理，记录会一直留着。';
}

/** 翻到某一天的按钮。没有那一天时是个按不动的按钮，而不是干脆不画——位置得留着 */
function step(label, date) {
  return date
    ? '<a class="daystep" href="' + esc(logHash(Object.assign({}, filters, {date}), today())) + '">'
      + esc(label) + '</a>'
    : '<button type="button" class="daystep" disabled>' + esc(label) + '</button>';
}

/** 这一天一共有几条（不看筛选），供空态那句话分辨「空日」与「筛没了」 */
function dayCount() {
  return dayCounts[filters.date] || 0;
}

/** 只看问题、三筛、搜索 */
function renderFilterBar(found) {
  $('#log-only').checked = filters.only;
  $('#log-q').value = filters.q;

  fillSelect($('#log-type'), '全部类型',
    catalog.map(item => [item.name, item.text]), filters.type);
  fillSelect($('#log-streamer'), '全部主播',
    (found.streamers || []).map(name => [name, name]), filters.streamer);
  fillSelect($('#log-channel'), '全部通道',
    (found.channels || []).map(name => [name, name]), filters.channel);

  $('#log-clear').style.display = hasFilter(filters) ? '' : 'none';
}

/**
 * 填一个下拉框
 *
 * 选中的那一项不在清单里时<b>补一行进去</b>：贴过来的地址可以带着一位今天没出现过的主播，
 * 此时下拉框若显示「全部主播」，屏幕上就是「明明筛着、看起来却没筛」。
 */
function fillSelect(box, blank, options, chosen) {
  const all = options.slice();
  if (chosen && !all.some(([value]) => value === chosen)) all.push([chosen, chosen + '（这一天没有）']);

  box.innerHTML = '<option value="">' + esc(blank) + '</option>'
    + all.map(([value, label]) => '<option value="' + esc(value) + '"'
      + (value === chosen ? ' selected' : '') + '>' + esc(label) + '</option>').join('');
}

/** 一行一事：时刻、色点、正文、主播与通道，详情折叠 */
function renderEvents(events, append) {
  const box = $('#log-list');
  if (!append) box.innerHTML = '';

  if (!events.length && !append) {
    box.innerHTML = '<div class="empty">' + esc(emptyText(filters, dayCount())) + '</div>';
    return;
  }

  box.insertAdjacentHTML('beforeend', events.map(item => {
    const level = item.level === 'error' ? 'error' : (item.level === 'warn' ? 'warn' : 'info');
    const tone = level === 'error' ? ' r-err' : (level === 'warn' ? ' r-warn' : '');
    const where = [item.streamer, item.channel].filter(Boolean).join(' · ');
    const detail = Object.entries(item.detail || {}).filter(([, value]) => value !== null);

    return '<div class="tl-row' + tone + '">'
      + '<div class="tl-t">' + esc(clock(item.at)) + '</div>'
      + '<div class="tl-rail"><span class="d ' + level + '"></span><span class="l"></span></div>'
      + '<div class="tl-c"><div class="tl-hd"><span>' + esc(item.text || item.typeText) + '</span>'
      + '<span class="tl-x">' + esc(item.typeText) + '</span>'
      + (where ? '<span class="tl-x">' + esc(where) + '</span>' : '')
      + '</div>'
      + (detail.length
        ? '<details class="tl-d"><summary>详情</summary><dl class="kv">'
          + detail.map(([key, value]) => '<dt>' + esc(key) + '</dt><dd>' + esc(value) + '</dd>').join('')
          + '</dl></details>'
        : '')
      + '</div></div>';
  }).join(''));
}

/** 页脚：命中多少、还有没有更早的 */
function renderFoot(found) {
  const foot = $('#log-more');
  const shown = $('#log-list').querySelectorAll('.tl-row').length;

  foot.innerHTML = '';
  if (!found.matched) return;

  const count = el('span', 'dim');
  count.textContent = '共 ' + found.matched + ' 条，已显示 ' + shown + ' 条';
  foot.appendChild(count);
  if (!found.truncated) return;

  const more = el('button', 'daystep');
  more.type = 'button';
  more.textContent = '看更早的';
  more.addEventListener('click', () => loadEvents(true));
  foot.appendChild(more);
}

/** 改了某一项筛选：写回地址栏，重画日期条上那两个带筛选的链接，回到第一页重取 */
function changed(patch) {
  Object.assign(filters, patch);
  cursor = '';
  writeHash();
  renderDayBar();
  loadEvents(false);
}

// ============ 工程日志 ============

/** 级别药丸、搜索、行数、重读 */
function renderEngTools() {
  const pills = $('#eng-levels');
  if (pills.childElementCount) return;

  for (const [level, label] of ENG_LEVELS) {
    const pill = el('button', 'pill lgpill');
    pill.type = 'button';
    pill.textContent = label;
    pill.setAttribute('aria-pressed', 'true');
    pill.addEventListener('click', () => {
      engLevels[level] = !engLevels[level];
      pill.setAttribute('aria-pressed', engLevels[level] ? 'true' : 'false');
      renderEngList();
    });
    pills.appendChild(pill);
  }
}

async function loadEng() {
  let found;
  try {
    found = await api('/engineering-log?limit=' + encodeURIComponent($('#eng-limit').value));
  } catch (e) {
    say('载入工程日志失败：' + e.message, 'err');
    return;
  }

  // 「读不到」与「没有日志」是两回事，后者是个正当状态，前者是这一页此刻什么都没量到
  if (!found.success || !found.available) {
    engLines = [];
    $('#eng-body').innerHTML = '<div class="empty">' + esc(found.message || '读不了日志文件') + '</div>';
    $('#eng-foot').textContent = '';
    return;
  }

  engLines = found.lines || [];
  $('#eng-foot').dataset.more = found.more ? '1' : '';
  $('#eng-foot').dataset.path = found.path || '';
  renderEngList();
}

function renderEngList() {
  const box = $('#eng-body');
  const all = groupEngLines(engLines);
  const shown = all.filter(entry => engVisible(entry, engLevels, engQuery));

  box.innerHTML = shown.length
    ? shown.map(entry => '<div class="er' + (entry.level ? ' ' + entry.level : '') + '">'
      + esc(entry.text) + '</div>').join('')
    : '<div class="empty">' + esc(all.length ? '没有符合条件的行。' : '这份日志此刻还没有内容。') + '</div>';

  // 最新的在最下面，进来就该看到它——排障要看的几乎总是刚发生的那几行
  box.scrollTop = box.scrollHeight;

  const foot = $('#eng-foot');
  foot.textContent = '显示 ' + shown.length + ' 段，共读到 ' + all.length + ' 段'
    + (foot.dataset.more ? '（更早的没有读进来，只有 NovaBot 自己这一份）' : '（这一份的全部）')
    + (foot.dataset.path ? ' · ' + foot.dataset.path : '');
}

/**
 * NapCat 的日志不在这里，只留一个去它自己界面的入口——凭据没配好就只剩那句话
 *
 * 头一次真进到这个子页时才问一趟。摆在模块加载处的话，从没打开过日志页的人
 * 也会在每次载入控制台时多打一个请求，而这一整块他一眼都看不到。
 */
async function renderNapCatEntry() {
  const box = $('#eng-napcat');
  if (box.childElementCount || box.textContent) return;

  box.textContent = 'QQ 那头（NapCat）的日志不在这里，到它自己的控制台看。';
  try {
    const napcat = await api('/napcat/state');
    if (!napcat.configured) return;
  } catch (e) {
    return;
  }
  box.insertAdjacentHTML('beforeend',
    '<div style="margin-top:8px"><a class="daystep" id="eng-napcat-open" '
    + 'href="/config/napcat-bootstrap" target="_blank" rel="noopener">打开 NapCat 界面 ↗</a>'
    + '<span class="dim"> NovaBot 自己知道它在哪，不用另填。</span></div>');
}

// ============ 绑定 ============

$('#log-only').addEventListener('change', event => changed({only: event.target.checked}));
$('#log-type').addEventListener('change', event => changed({type: event.target.value}));
$('#log-streamer').addEventListener('change', event => changed({streamer: event.target.value}));
$('#log-channel').addEventListener('change', event => changed({channel: event.target.value}));
$('#log-q').addEventListener('change', event => changed({q: event.target.value.trim()}));
$('#log-clear').addEventListener('click', () => changed({
  only: false, type: '', streamer: '', channel: '', q: '',
}));
$('#eng-q').addEventListener('input', event => { engQuery = event.target.value; renderEngList(); });
$('#eng-limit').addEventListener('change', loadEng);
$('#eng-reload').addEventListener('click', loadEng);
