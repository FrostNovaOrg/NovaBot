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

import {$, api, clock, el, esc, phrase, say, today, term} from './core.js';
import {
  ENG_LEVELS, emptyText, engAtBottom, engCopyText, engEmptyText, engFollowing, engQuery,
  engSegments, hasFilter, logHash, newerDay, olderDay, parseLogHash, timelineQuery,
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

/**
 * 大类闭集，由接口给出，且只含有类型归属的那些
 *
 * 药丸那一排照它画。界面自己写死一排的话，多出来的那几枚点下去必然是空的，
 * 而使用者会把空结果读成「这台机器没发生过这类事」。
 */
let categories = [];

/** 保留天数，来自同一趟 /api/timeline/days */
let retention = 0;

/** 接着往更旧翻的位置，空表示没有更早的了。它是「翻到哪儿了」，不是筛选，因此不进地址栏 */
let cursor = '';

/** 工程日志：四档级别各自开着没有，与搜索词。这两样只在本页内有效，不进地址栏 */
const engLevels = {error: true, warn: true, info: true, debug: true};

let engSearch = '';

/** 工程日志上一次读回来的原文行，改筛选时不必再读一遍盘 */
let engLines = [];

/**
 * 工程日志读到那份文件的哪个字节了
 *
 * 「跟随最新」从这里接着读，只取新写进去的那几行——每 3 秒把几百行重取一遍，
 * 在一台开着控制台过夜的机器上是几十兆的无谓流量，而画出来的画面一模一样。
 */
let engOffset = -1;

/** 要高亮的是第几行原文（从日志页跳过来时由服务端给），不高亮时为 -1 */
let engHighlight = -1;

/** 跟随最新开着没有。默认开：进这一页多半就是要看最新那几行 */
let engFollow = true;

/** 跟随最新那个定时器。离开本页要停掉，否则它会一直问下去 */
let engTimer = 0;

/** 每隔多久去尾读一次 */
const FOLLOW_MS = 3000;

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
  // 每次进来都先停掉上一趟的跟随：改筛选、翻日子走的都是这一条路，
  // 不停的话，同一页上会有两个定时器各读各的，屏幕上的行随之重复
  stopFollow();
  if (syncLogView()) {
    renderEngTools();
    renderBotLogEntry();
    loadEng();
    return;
  }
  loadTimeline();
}

/**
 * 离开日志页时停掉跟随
 *
 * 由路由在离开时调（与「离开连接页抹掉口令」同一处）。留着不停的话，
 * 使用者在别的页上待一夜，这个定时器还在每 3 秒读一次日志文件。
 */
export function stopFollow() {
  clearInterval(engTimer);
  engTimer = 0;
}

// ============ 时间线 ============

async function loadTimeline() {
  try {
    const list = await api('/timeline/days');
    calendar = (list.days || []).map(item => item.date);
    dayCounts = {};
    for (const item of list.days || []) dayCounts[item.date] = item.count;
    catalog = list.types || [];
    categories = list.categories || [];
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

/** 只看问题、两筛、搜索 */
function renderFilterBar(found) {
  $('#log-only').checked = filters.only;
  $('#log-q').value = filters.q;

  renderCatBar();
  fillSelect($('#log-streamer'), '全部主播',
    (found.streamers || []).map(name => [name, name]), filters.streamer);
  fillSelect($('#log-channel'), '全部通道',
    (found.channels || []).map(name => [name, name]), filters.channel);

  $('#log-clear').style.display = hasFilter(filters) ? '' : 'none';
}

/**
 * 大类药丸：全部，加上有事件归属的那几类
 *
 * 旧地址里带着 type= 时在末尾补一枚可摘掉的药丸。不补的话，那一项<b>筛着而屏幕上没有
 * 任何控件显示它</b>——使用者看到的是一张莫名其妙少了很多条的页，
 * 而它与「今天就发生了这么多」长得一模一样。
 */
function renderCatBar() {
  const bar = $('#log-cats');
  bar.innerHTML = '';

  for (const [value, label] of [['', '全部']].concat(categories.map(item => [item.name, item.text]))) {
    const pill = el('button', 'pill lgpill');
    pill.type = 'button';
    pill.textContent = label;
    pill.setAttribute('aria-pressed', filters.cat === value ? 'true' : 'false');
    pill.addEventListener('click', () => changed({cat: value}));
    bar.appendChild(pill);
  }

  if (!filters.type) return;
  const known = catalog.find(item => item.name === filters.type);
  const chip = el('button', 'pill lgpill');
  chip.type = 'button';
  chip.textContent = '类型：' + (known ? known.text : filters.type) + ' ✕';
  chip.setAttribute('aria-pressed', 'true');
  chip.addEventListener('click', () => changed({type: ''}));
  bar.appendChild(chip);
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

    // 「看这一刻」带着这一条的时刻跳去工程日志。时刻取自同一个 clock()，
    // 与这一行左边显示的那个是同一个值——各算各的话，跳过去高亮的是另一分钟
    const jump = logHash(Object.assign({}, filters,
      {view: 'eng', at: clock(item.at)}), today());

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
      + '<a class="tl-go" href="' + esc(jump) + '">在工程日志里看这一刻 →</a>'
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

/** 级别药丸、搜索、翻日子、行数、跟随、复制、重读 */
function renderEngTools() {
  // 这一天那个格子每次进来都要对一遍：翻日子走的是地址栏，而输入框里还留着上一天
  $('#eng-date').value = filters.date;
  $('#eng-follow').checked = engFollow;

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
    found = await api('/engineering-log' + engQuery(filters, limitOf(), -1));
  } catch (e) {
    say('载入工程日志失败：' + e.message, 'err');
    return;
  }

  // 「读不到」与「没有日志」是两回事，后者是个正当状态，前者是这一页此刻什么都没量到
  if (!found.success || !found.available) {
    engLines = [];
    engOffset = -1;
    $('#eng-body').innerHTML = '<div class="empty">' + esc(found.message || '读不了日志文件') + '</div>';
    $('#eng-foot').textContent = '';
    renderEngJump(null);
    return;
  }

  engLines = found.lines || [];
  engHighlight = typeof found.highlight === 'number' ? found.highlight : -1;
  engOffset = typeof found.offset === 'number' ? found.offset : -1;
  $('#eng-foot').dataset.more = found.more ? '1' : '';
  $('#eng-foot').dataset.path = found.path || '';
  renderEngJump(found);
  renderEngList();
  startFollow();
}

/** 读多少行 */
function limitOf() {
  return Number($('#eng-limit').value) || 0;
}

/**
 * 从日志页跳过来时那条横幅
 *
 * 那一分钟没有记录时<b>必须说</b>：屏幕上照样高亮着一行，而它不是使用者点过来要看的那一行。
 */
function renderEngJump(found) {
  const bar = $('#eng-jump');
  bar.innerHTML = '';
  if (!filters.at || !found) {
    bar.style.display = 'none';
    return;
  }

  bar.style.display = '';
  bar.className = found.exact ? 'note' : 'note n-warn';
  const text = el('div');
  text.textContent = found.exact
    ? '已定位到 ' + filters.at + ' · 来自日志页'
    : (found.message || '这一分钟没有记录。') + ' · 来自日志页';
  bar.appendChild(text);

  const back = el('div');
  const toList = el('a', 'daystep');
  toList.textContent = '‹ 回日志';
  toList.setAttribute('href', logHash(Object.assign({}, filters, {view: 'timeline', at: ''}), today()));
  const toLatest = el('a', 'daystep');
  toLatest.textContent = '回到最新';
  toLatest.setAttribute('href', logHash(Object.assign({}, filters, {at: ''}), today()));
  toLatest.style.marginLeft = '8px';
  back.style.marginTop = '8px';
  back.appendChild(toList);
  back.appendChild(toLatest);
  bar.appendChild(back);
}

function renderEngList() {
  const box = $('#eng-body');
  // 与「复制这一段」问的是同一个口子：各筛各的话，剪贴板里会是一份没在屏幕上出现过的日志
  const {all, shown} = engSegments(engLines, engHighlight, engLevels, engSearch);

  box.innerHTML = shown.length
    ? shown.map(entry => '<div class="er' + (entry.level ? ' ' + entry.level : '')
      + (entry.hl ? ' hl' : '') + '">' + esc(entry.text) + '</div>').join('')
    : '<div class="empty">' + esc(engEmptyText(filters, today(), all.length)) + '</div>';

  // 定位过来时停在高亮那一行上，否则停在最下面——排障要看的几乎总是刚发生的那几行
  const target = box.querySelector('.er.hl');
  if (target) box.scrollTop = Math.max(0, target.offsetTop - box.offsetTop - 60);
  else box.scrollTop = box.scrollHeight;

  const foot = $('#eng-foot');
  foot.textContent = '显示 ' + shown.length + ' 段，共读到 ' + all.length + ' 段'
    + (filters.at ? '（这一刻前后那一段）'
      : (foot.dataset.more ? '（更早的没有读进来，只有 NovaBot 自己这一份）' : '（这一份的全部）'))
    + (foot.dataset.path ? ' · ' + foot.dataset.path : '');
}

/**
 * 跟随最新：每 3 秒问一次「刚才之后又写了什么」
 *
 * 只在视窗停在底部时才追加，见 engFollowing。定时器本身照常转——转与不转的差别
 * 只在那一次要不要发请求，而开关与滚动位置随时都会变。
 */
function startFollow() {
  stopFollow();
  engTimer = setInterval(followTick, FOLLOW_MS);
}

async function followTick() {
  const box = $('#eng-body');
  const atBottom = engAtBottom(box.scrollTop, box.clientHeight, box.scrollHeight);
  if (engOffset < 0 || filters.view !== 'eng'
      || !engFollowing(engFollow, atBottom, filters, today())) {
    return;
  }

  let found;
  try {
    found = await api('/engineering-log' + engQuery(filters, limitOf(), engOffset));
  } catch (e) {
    // 停下来并说一声：不说的话，这一页会安静地不再更新，而开关看起来还开着
    engFollow = false;
    $('#eng-follow').checked = false;
    say('跟随最新读不到日志了，已停下：' + e.message, 'err');
    return;
  }
  if (!found.success || !found.available) return;

  // 文件换过了（滚动、清空）就整段重取：接着追加的话，屏幕上会从头长出一整份日志
  if (found.reset) {
    loadEng();
    return;
  }

  engOffset = typeof found.offset === 'number' ? found.offset : engOffset;
  const fresh = found.lines || [];
  if (!fresh.length) return;

  engLines = engLines.concat(fresh);
  // 攒够了就丢最旧的：开着一夜之后，这一页会攒下几十万行，而看的人只要最近这些
  const cap = limitOf() || 300;
  if (engLines.length > cap) engLines = engLines.slice(engLines.length - cap);
  renderEngList();
}

/**
 * 复制这一段：屏幕上此刻显示的那几段
 *
 * 剪贴板接口只在安全上下文（https 或 localhost）里存在，而面板常经内网 http 访问——
 * 那里 navigator.clipboard 直接是 undefined。兜底是替使用者选中，
 * 而不是让按钮点下去毫无反应。
 */
async function copyEng() {
  const {shown} = engSegments(engLines, engHighlight, engLevels, engSearch);
  const text = engCopyText(shown);
  if (!text) {
    say('这里没有可复制的行', 'err');
    return;
  }

  try {
    await navigator.clipboard.writeText(text);
    say('已复制这一段（' + shown.length + ' 段）', 'ok');
  } catch (e) {
    const range = document.createRange();
    range.selectNodeContents($('#eng-body'));
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    say('这个浏览器不让脚本写剪贴板，已替你选中，按 Ctrl/⌘+C 复制', 'err');
  }
}

/**
 * 机器人自己的日志不在这里，只留一个去它自己界面的入口——凭据没配好就只剩那句话
 *
 * 头一次真进到这个子页时才问一趟。摆在模块加载处的话，从没打开过日志页的人
 * 也会在每次载入控制台时多打一个请求，而这一整块他一眼都看不到。
 */
async function renderBotLogEntry() {
  const box = $('#eng-napcat');
  if (box.childElementCount || box.textContent) return;

  box.textContent = phrase('bot.impl',
    v => term('bot.platform', '聊天平台') + ' 那头（' + v + '）的日志不在这里，到它自己的控制台看。',
    '机器人那头的日志不在这里，到它自己的控制台看。');
  try {
    const napcat = await api('/napcat/state');
    if (!napcat.configured) return;
  } catch (e) {
    return;
  }
  box.insertAdjacentHTML('beforeend',
    '<div style="margin-top:8px"><a class="daystep" id="eng-napcat-open" '
    + 'href="/config/napcat-bootstrap" target="_blank" rel="noopener">'
    + phrase('bot.impl', v => '打开 ' + v + ' 界面 ↗', '打开机器人界面 ↗')
    + '</a>'
    + '<span class="dim"> NovaBot 自己知道它在哪，不用另填。</span></div>');
}

// ============ 绑定 ============

$('#log-only').addEventListener('change', event => changed({only: event.target.checked}));
$('#log-streamer').addEventListener('change', event => changed({streamer: event.target.value}));
$('#log-channel').addEventListener('change', event => changed({channel: event.target.value}));
$('#log-q').addEventListener('change', event => changed({q: event.target.value.trim()}));
$('#log-clear').addEventListener('click', () => changed({
  only: false, cat: '', type: '', streamer: '', channel: '', q: '',
}));
$('#eng-q').addEventListener('input', event => { engSearch = event.target.value; renderEngList(); });
$('#eng-limit').addEventListener('change', loadEng);
$('#eng-reload').addEventListener('click', loadEng);
$('#eng-copy').addEventListener('click', copyEng);
$('#eng-follow').addEventListener('change', event => { engFollow = event.target.checked; });
// 翻日子改的是地址栏那一段，与筛选同一条路：贴出去的地址得落在同一天
$('#eng-date').addEventListener('change', event => {
  const day = event.target.value;
  if (!day) return;
  // 换了一天就不再定住上一天那一刻：那一分钟在这一天多半没有记录，
  // 而横幅会照常说「已定位到」
  location.hash = logHash(Object.assign({}, filters, {date: day, at: ''}), today());
});
