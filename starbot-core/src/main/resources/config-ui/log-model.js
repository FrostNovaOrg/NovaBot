/**
 * 日志页的判定：地址栏 ↔ 筛选状态、接口查询串、日期导航、工程日志分行与筛
 *
 * 这几件事全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * 混在渲染代码里的话，「贴出去的地址打开还是不是同一张画面」这种事就只能靠人手点，
 * 而手点点不出「关键词里带 & 号」「站在没有记录的那一天上翻前一天」这类分支。
 */

/** 一次取多少条。翻页靠游标接着往更旧的取，见 timelineQuery */
export const DEFAULT_LIMIT = 200;

/** 工程日志的四档级别与它们的人话。TRACE 并进「调试」，药丸只有这四个 */
export const ENG_LEVELS = [['error', '错误'], ['warn', '警告'], ['info', '信息'], ['debug', '调试']];

/**
 * 日志页上有名字的子路由，闭集
 *
 * 现在只有工程日志这一条。日期走第二段是另一类（形状是 YYYY-MM-DD，不进这张表）。
 * 解析与拼地址都从这张表认——另写一个字符串的话，新开一页只改了链接，
 * 解析仍落在时间线，屏幕上像页面卡住了。
 */
export const LOG_SUBROUTES = ['eng'];

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 点名要看工程日志里的哪一分钟
 *
 * 形状对还不够，值也得是个存在的时刻：25:99 那种地址进来时当没点名，
 * 而不是拿它去问服务端——服务端会答「这一分钟没有记录」，于是屏幕上高亮着一行别的日志。
 */
const MINUTE = /^([01]\d|2[0-3]):[0-5]\d$/;

/**
 * 日志文件里一行的行首：时刻加级别，与 logback.xml 里那个 pattern 对应
 *
 * 认不出来只影响这一行的颜色与它归哪一档，不影响它显示——
 * 换了 pattern 之后整页变成一片没有级别的行，而每一行都还在。
 */
const HEAD = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+(TRACE|DEBUG|INFO|WARN|ERROR)\b/;

/**
 * 什么都不筛时的样子
 * @param today 今天，YYYY-MM-DD
 * @return 筛选状态
 */
export function emptyState(today) {
  return {view: 'timeline', date: today, only: false, cat: '', type: '',
    streamer: '', channel: '', q: '', at: ''};
}

/**
 * 地址栏 → 筛选状态
 *
 * 认不出来的一律退回默认，不留白屏：这一页是出事时来看的，
 * 一个打不开的地址比一张少筛了几项的页面糟得多。
 * @param hash location.hash
 * @param today 今天
 * @return 筛选状态
 */
export function parseLogHash(hash, today) {
  const raw = String(hash || '').replace(/^#\/?/, '');
  const cut = raw.indexOf('?');
  const path = (cut < 0 ? raw : raw.slice(0, cut)).split('/').filter(Boolean);
  const state = emptyState(today);

  const seg = path[1] || '';
  if (LOG_SUBROUTES.includes(seg)) state.view = seg;
  else if (DATE.test(seg)) state.date = seg;

  for (const part of (cut < 0 ? '' : raw.slice(cut + 1)).split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    const key = eq < 0 ? part : part.slice(0, eq);
    const value = eq < 0 ? '' : decode(part.slice(eq + 1));
    // 只看问题认一个值。写成「有这个参数就算开」的话，写错值的地址会静默按开处理
    if (key === 'only') state.only = value === 'problem';
    // 大类与类型两项一起认：药丸那一排换成大类之后，旧地址里带的 type= 仍然筛得动
    else if (key === 'cat') state.cat = value;
    else if (key === 'type') state.type = value;
    else if (key === 'streamer') state.streamer = value;
    else if (key === 'chan') state.channel = value;
    else if (key === 'q') state.q = value;
    // 工程日志那一档路径里已经写着 eng，日期只好走查询串
    else if (key === 'd' && DATE.test(value)) state.date = value;
    else if (key === 'at' && MINUTE.test(value)) state.at = value;
  }
  return state;
}

/** 解不开的百分号原样留着，不让一个坏地址把整页拖垮 */
function decode(value) {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '));
  } catch (e) {
    return value;
  }
}

/**
 * 筛选状态 → 地址栏
 *
 * 与 parseLogHash 严格互逆：只做得出这一半的话，贴给别人的地址打开是一张<b>没筛过</b>的页，
 * 而它与筛过的长得一模一样，没有任何东西会提示对方看到的不是同一份。
 * @param state 筛选状态
 * @param today 今天
 * @return hash
 */
export function logHash(state, today) {
  const query = [];
  let path = '#/log';

  if (LOG_SUBROUTES.includes(state.view)) {
    path += '/' + state.view;
    if (state.date && state.date !== today) query.push('d=' + state.date);
    // 定位到哪一分钟也写进地址栏：日志页上那一跳贴给别人，对方落在同一刻
    if (state.at) query.push('at=' + encodeURIComponent(state.at));
  } else if (state.date && state.date !== today) {
    path += '/' + state.date;
  }

  if (state.only) query.push('only=problem');
  if (state.cat) query.push('cat=' + encodeURIComponent(state.cat));
  if (state.type) query.push('type=' + encodeURIComponent(state.type));
  if (state.streamer) query.push('streamer=' + encodeURIComponent(state.streamer));
  if (state.channel) query.push('chan=' + encodeURIComponent(state.channel));
  if (state.q) query.push('q=' + encodeURIComponent(state.q));

  return path + (query.length ? '?' + query.join('&') : '');
}

/**
 * 筛选状态 → /api/timeline 的查询串
 *
 * 地址栏那一套名字（only / chan）是这一页对使用者的约定，接口那一套（problems / channel）
 * 是 /api/timeline 的约定。两套只在这一个函数里换一次——散在各调用点换的话，
 * 漏改一处的表现是那一项静默不生效，而屏幕上的下拉框仍然选着它。
 * @param state 筛选状态
 * @param limit 取多少条，0 表示按默认
 * @param cursor 接着往更旧的翻的位置，空表示从最近的开始
 * @return 查询串，以 ? 开头
 */
export function timelineQuery(state, limit, cursor) {
  const query = ['date=' + encodeURIComponent(state.date), 'limit=' + (limit || DEFAULT_LIMIT)];
  if (state.only) query.push('problems=true');
  if (state.cat) query.push('category=' + encodeURIComponent(state.cat));
  if (state.type) query.push('type=' + encodeURIComponent(state.type));
  if (state.streamer) query.push('streamer=' + encodeURIComponent(state.streamer));
  if (state.channel) query.push('channel=' + encodeURIComponent(state.channel));
  if (state.q) query.push('q=' + encodeURIComponent(state.q));
  // 游标是「翻到哪儿了」，不是筛选，因此不写进地址栏：写进去的话，
  // 贴给别人的地址会从第三页开始显示，而对方看不出前面还有
  if (cursor) query.push('cursor=' + encodeURIComponent(cursor));
  return '?' + query.join('&');
}

/**
 * 筛没筛
 *
 * 换一天<b>不算</b>筛：那是翻页。算进来的话，翻到空的那一天会说「清掉筛选试试」，
 * 而清掉筛选并不会让那一天长出记录来。
 * @param state 筛选状态
 * @return 筛了返回 true
 */
export function hasFilter(state) {
  return !!(state.only || state.cat || state.type || state.streamer || state.channel || state.q);
}

/**
 * 一条都没显示出来时说哪句话
 *
 * 空日与无命中是两回事：前者要说的是「这一天什么都没发生」，后者要说的是「筛得太狠了」，
 * 而使用者对这两句话的反应完全不同——把它们合成一句，筛过头的人会以为机器人那天没干活。
 * @param state 筛选状态
 * @param dayCount 这一天一共有几条（不看筛选）
 * @return 那句话
 */
export function emptyText(state, dayCount) {
  if (!dayCount) return '这一天没有记录。';
  return hasFilter(state) ? '没有符合条件的，清掉筛选试试。' : '这一天没有记录。';
}

/**
 * 有记录的那些天里，比这一天更早的头一天
 *
 * 取的是「有记录的前一天」而不是日历上的前一天：留 14 天的机器上，
 * 按日历一天天点过去会连点好几下空页才见到下一条记录。
 * @param days 有记录的日期，YYYY-MM-DD
 * @param date 当前这一天
 * @return 日期，没有更早的返回空串
 */
export function olderDay(days, date) {
  let best = '';
  for (const day of days || []) {
    if (day < date && day > best) best = day;
  }
  return best;
}

/**
 * 有记录的那些天里，比这一天更晚的头一天
 * @param days 有记录的日期
 * @param date 当前这一天
 * @return 日期，没有更晚的返回空串
 */
export function newerDay(days, date) {
  let best = '';
  for (const day of days || []) {
    if (day > date && (!best || day < best)) best = day;
  }
  return best;
}

/**
 * 日志文件里这一行是什么级别
 * @param line 一行原文
 * @return error / warn / info / debug；行首认不出级别时为空串
 */
export function engLevelOf(line) {
  const found = HEAD.exec(String(line || ''));
  if (!found) return '';
  const level = found[1].toLowerCase();
  // TRACE 并进调试那一档：药丸只有四个，单开一档就是开一个没有开关管得着它的口子
  return level === 'trace' ? 'debug' : level;
}

/**
 * 把行合成段：一行日志加上跟在它后面的堆栈
 *
 * 不合的话，筛与搜都会把堆栈从它所属的那一行上撕下来——关掉「错误」那一档，
 * 屏幕上会留下一堆无主的 {@code at ...}；而搜一个类名，搜到的是几行光秃秃的栈帧。
 *
 * 要高亮的那一行由服务端按<b>原文行号</b>给，因此这里顺手把它落到所属的那一段上：
 * 只亮其中一行的话，屏幕上会亮起半个异常，而找的人不知道它属于谁。
 * @param lines 原文行，最旧的在前
 * @param highlight 要高亮的原文行号，不高亮时留空
 * @return 段，每段 {level, text, hl}
 */
export function groupEngLines(lines, highlight = -1) {
  const out = [];
  let index = 0;
  for (const line of lines || []) {
    const level = engLevelOf(line);
    const text = String(line ?? '');
    const hl = index === highlight;
    // 开头就是续行的情况真会发生：尾巴是从文件中间切进来的
    if (level || !out.length) out.push({level, text, hl});
    else {
      out[out.length - 1].text += '\n' + text;
      if (hl) out[out.length - 1].hl = true;
    }
    index++;
  }
  return out;
}

/**
 * 这一段显示吗
 *
 * 认不出级别的段<b>照样显示</b>：往「看得见」的方向失败——被藏起来的那几行，
 * 不会有任何人发现自己没看见。
 * @param entry 一段
 * @param levelsOn 四档各自开着没有
 * @param q 搜索词
 * @return 显示返回 true
 */
export function engVisible(entry, levelsOn, q) {
  if (entry.level && levelsOn[entry.level] === false) return false;
  const needle = String(q || '').trim().toLowerCase();
  return !needle || entry.text.toLowerCase().includes(needle);
}

/**
 * 筛选状态 → /api/engineering-log 的查询串
 *
 * 与 timelineQuery 同理：地址栏那一套名字（d / at）与接口那一套只在这一个函数里对一次。
 *
 * 定住某一刻时<b>不带 since</b>：跟随会把定住的那一刻一点点推出视野，
 * 而使用者刚刚才点了「看这一刻」。
 * @param state 筛选状态
 * @param limit 读多少行
 * @param since 上一次读到哪个字节，小于 0 表示不接着读
 * @return 查询串，以 ? 开头
 */
export function engQuery(state, limit, since) {
  const query = ['limit=' + (limit || 0)];
  if (state.date) query.push('d=' + encodeURIComponent(state.date));
  if (state.at) query.push('at=' + encodeURIComponent(state.at));
  else if (since >= 0) query.push('since=' + since);
  return '?' + query.join('&');
}

/**
 * 视窗停在底部了没有
 *
 * 留几像素的余量：滚轮与触控板停不到整数上，一个像素的差在屏幕上看不出来，
 * 而按严格相等判的话，「跟随最新」在大多数时候都是关着的——<b>而开关看起来是开着的</b>。
 * @param scrollTop 滚到哪儿了
 * @param clientHeight 视窗高
 * @param scrollHeight 内容高
 * @param slack 余量，像素
 * @return 在底部返回 true
 */
export function engAtBottom(scrollTop, clientHeight, scrollHeight, slack = 24) {
  return scrollHeight - clientHeight - scrollTop <= slack;
}

/**
 * 这一刻该不该去尾读
 *
 * 三种情形一律不跟：开关关着、视窗不在底部、以及正定住某一刻或翻在别的日子上。
 * 中间那一条是这一组的重头——正翻着旧行时被新行推走，人会以为自己点错了，
 * 而屏幕上没有任何东西说明刚才发生了什么。
 * @param on 跟随开关
 * @param atBottom 视窗在不在底部
 * @param state 筛选状态
 * @param today 今天
 * @return 该去尾读返回 true
 */
export function engFollowing(on, atBottom, state, today) {
  if (!on || !atBottom) return false;
  if (state.at) return false;
  // 别的日子那一份不会再长，跟它等于每 3 秒问一次同一个答案
  return !state.date || state.date === today;
}

/**
 * 复制这一段：屏幕上此刻显示的那几段，逐段一行
 *
 * 复制的就是显示的那一份，<b>不另走一条取数路径</b>：口令与 Cookie 是在服务端读盘那一层
 * 就打掉的（见 EngineeringLogService.mask），另开一条「复制时去取原文」的路，
 * 等于把那道打码绕过去——而复制这个动作的下一步往往是贴进聊天窗口。
 * @param entries 此刻显示的段
 * @return 一整段文本
 */
export function engCopyText(entries) {
  return (entries || []).map(entry => entry.text).join('\n');
}

/**
 * 工程日志一行都没显示时说哪句话
 *
 * 三句各答一件事：筛没了、这一天没有文件、今天这一份还没写过。合成一句的话，
 * 翻到一个没开机的日子会看到「日志还没有内容」，而那台机器那天根本没跑。
 * @param state 筛选状态
 * @param today 今天
 * @param total 这一份一共读到几段（不看筛选）
 * @return 那句话
 */
export function engEmptyText(state, today, total) {
  if (total) return '没有符合条件的行。';
  return state.date && state.date !== today ? '这一天没有记录。' : '这一份日志此刻还没有内容。';
}
