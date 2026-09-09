/**
 * 连接页：三张卡与「发一条试试」
 *
 * 三张卡答的是同一个问题的三段——直播平台那一头连上了没有、机器人这一头连上了没有、
 * 事件往外送的那把口令签了没有。本文件只管把 links-model.js 算好的东西摆上屏幕：
 * 哪张卡该是什么颜色、该说哪句话一律在那边判，判断留在这里的话，十二档对照就只能靠人点开页面看。
 *
 * 本文件里没有任何一个直播平台的名字：平台卡的标题取自插件登记时报的显示名，
 * 卡里的内容由插件自己填。写死一个平台名，这一页就替一件可能没装的东西立了张卡。
 */

import {$, api, el, esc, phrase, say, term} from './core.js';
import {cardAnchor, linksModel, resolveTarget, targetOptions} from './links-model.js';
import {renderStatus} from './overview.js';
import {renderTokens} from './tokens.js';

/**
 * 插件登记到本页的连接卡，每项是 {id, displayName}
 *
 * 由 main.js 在装载插件页时逐个交进来。本文件不自己去问 /pages：
 * 装载与登记是同一件事的两半，各问一次的话，装不上的那一页会在卡的位置上留一张空壳
 */
const linkCards = [];

/**
 * 上一次算出来的视图模型
 *
 * 留着它是为了让「某一站落在哪张卡上」只有一个读法（见 {@link focusStation}）。
 * 在那里另拼一份卡片清单的话，就是同一条规则的第二份实现——两份分叉的表现是
 * 点了链路图上那一站，页面滚到了另一张卡，而两处的代码看起来都对
 */
let model = {cards: []};

/**
 * 地址栏里点名要看的那一站，画完之后滚过去
 */
let wanted = '';

/**
 * 建出一张插件连接卡的壳，返回给插件填内容的那块地方
 *
 * 卡壳（标题、灯、那句状态说明）由这里画，卡里的内容由插件自己填。分开是因为两侧知道的事不一样：
 * 这一侧手里有探针与账号状态，答得出「这一段此刻是好是坏」；而扫码、退出登录只有插件做得了。
 * @param meta 页面清单里的一项
 * @return {HTMLElement} 插件往里渲染的容器
 */
export function mountLinkCard(meta) {
  linkCards.push({id: meta.id, displayName: meta.displayName});

  const card = el('div', 'nv-card lcard');
  card.id = 'card-p-' + meta.id;
  card.innerHTML = '<div class="lc-hd"><span class="lamp"></span><b></b><span class="lc-cap"></span></div>'
    + '<div class="lc-note"></div><div class="lc-body"></div>';
  card.querySelector('.lc-hd b').textContent = meta.displayName;
  $('#link-cards').appendChild(card);

  return card.querySelector('.lc-body');
}

/**
 * 按模型给一张卡换上此刻的颜色与说明
 * @param card 卡片视图模型
 */
function paint(card) {
  const box = $('#card-' + card.key);
  if (!box) return;

  // 卡的色调跟着灯走，与首页同一条规则：同一个事实在两页上判成两种颜色，
  // 会让人以为是两件事。掉登录判黄、机器人掉线判红——后者首页上也是红的
  box.className = 'nv-card lcard l-' + card.level;
  box.querySelector('.lamp').className = 'lamp ' + card.level;
  box.querySelector('.lc-cap').textContent = card.caption;

  const note = box.querySelector('.lc-note');
  const lines = [card.note, card.advice].filter(Boolean);
  note.innerHTML = lines.map(text => '<div>' + esc(text) + '</div>').join('');
  if (card.action) {
    const go = el('a', 'lc-go');
    go.href = card.action.href;
    go.textContent = card.action.text;
    note.appendChild(go);
  }
  note.style.display = lines.length || card.action ? '' : 'none';
}

/**
 * 重取本页的三份数据再一次性画完
 *
 * 三个请求并发发出、一起等：分三次画的话，卡上已经按新的一份数据变红，
 * 而下面的清单还是上一份算出来的——两块说的是同一件事，屏幕上却互相矛盾。
 */
export async function refreshLinks() {
  try {
    const [status, login, tokens] = await Promise.all([
      api('/status'), api('/login'), api('/event-tokens')]);

    renderStatus(status);
    model = linksModel(status, login, linkCards, tokens.tokens || [],
      phrase('bot.impl', v => '机器人（' + v + ' 等）', '机器人'));
    model.cards.forEach(paint);
    renderTokens(tokens.tokens || []);
    scrollToWanted();
  } catch (e) {
    say('载入连接页失败：' + e.message, 'err');
  }
}

/**
 * 记下地址栏点名要看的那一站
 *
 * 只记不滚：这一刻卡还没画出来，滚过去也没有东西可滚。真正的滚动在数据到齐、
 * 卡画完之后（见 {@link refreshLinks}）——先滚再画的话，画完那一下页面又跳回顶上。
 * @param station 站名，platform / self / bot
 */
export function focusStation(station) {
  wanted = station || '';
}

/**
 * 滚到点名的那张卡
 *
 * 站与卡的对应只有 cardAnchor 一个读法。没有落点时什么也不做——
 * 跳到一个页面上不存在的锚，表现是地址栏变了而屏幕没动，看起来像是页面卡住了。
 */
function scrollToWanted() {
  if (!wanted) return;
  const key = cardAnchor(model, wanted);
  wanted = '';

  const box = key ? $('#card-' + key) : null;
  if (box) box.scrollIntoView({block: 'start', behavior: 'smooth'});
}

// ============ 发一条试试 ============

/**
 * 可选的发送目标，来自机器人自己给的名单
 *
 * 记在模块里而不是每次从下拉框上读回来：下拉框只留得住一个字符串，
 * 而「这个字符串是不是名单里的」正是要判的那件事——读回来再拆一遍，就等于相信了它
 */
let options = [];

/**
 * 取一次名单并填进下拉框
 * @param force 是否让机器人重新去问一遍
 */
export async function loadTargets(force) {
  const pick = $('#test-target');
  pick.innerHTML = '<option value="">载入中…</option>';
  $('#test-send').disabled = true;

  try {
    if (force) await api('/onebot/targets/refresh', {method: 'POST'});
    const [groups, friends] = await Promise.all([
      api('/onebot/targets?type=group'), api('/onebot/targets?type=friend')]);
    options = targetOptions(groups, friends);
  } catch (e) {
    // 取不到名单时<b>不退回手填</b>：退回手填等于把「填错一位数不报错」那个失败形态请回来。
    // 这里只把话说清楚——先去把机器人连上，再回来发这一条
    options = [];
    pick.innerHTML = '<option value="">暂时取不到群与好友名单</option>';
    $('#test-hint').textContent = '机器人还没连上，或它此刻答不上话。先看上面那张卡。';
    return;
  }

  pick.innerHTML = options.length
    ? options.map(item => '<option value="' + esc(item.key) + '">' + esc(item.text)
        + (item.configured ? ' · 已配推送' : '') + '</option>').join('')
    : '<option value="">机器人还没在任何群里，也没有加好友</option>';
  $('#test-hint').textContent = options.length
    ? '名单由机器人自己给出，这里没有手填号码的格子。'
    : '把机器人拉进一个群，或给它加个好友，再回来发这一条。';
  $('#test-send').disabled = !options.length;
}

/**
 * 发一条真消息过去
 *
 * 群号填错、Token 不对、机器人不在群里、OneBot 没起——这四类错的表现完全一样：什么都不发生。
 * 发一条真消息是唯一能当场把它们分开的手段。
 */
export async function sendTestMessage() {
  const box = $('#test-result');
  const pick = $('#test-target');
  const target = resolveTarget(options, pick.value);

  box.style.display = 'block';
  if (!target.ok) {
    box.className = 'issues';
    box.innerHTML = '<b>' + esc(target.reason) + '</b>';
    return;
  }

  $('#test-send').disabled = true;
  box.className = 'issues';
  box.innerHTML = '<b>发送中…</b>';

  try {
    const res = await api('/test-message', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform: target.platform, type: target.type, num: target.num}),
    });
    renderTestResult(res, (options.find(item => item.key === pick.value) || {}).text || '');
  } catch (e) {
    box.className = 'issues';
    box.innerHTML = '<b>发送失败：' + esc(e.message) + '</b>';
  }

  $('#test-send').disabled = false;
}

/**
 * 发出去之后屏幕上显示什么
 *
 * 成了就画一个气泡：使用者要确认的是「那边收到的是不是这句」，而一行「已发送」答不了这个问题。
 * 没成则列三条排查——服务端给的那句话是针对这一次失败的，三条是「还能自己查什么」，两者不重复。
 * @param res /api/test-message 回包
 * @param targetText 发给谁，取自名单上那一条的原文
 */
function renderTestResult(res, targetText) {
  const box = $('#test-result');

  if (res.success) {
    box.className = 'issues ok';
    box.innerHTML = '<b>' + esc(res.message) + '</b>'
      + '<div class="bubble"><div class="bb-to">发给 ' + esc(targetText) + '</div>'
      + '<div class="bb-body">这是一条来自 NovaBot 的测试消息，收到即表示推送链路正常。</div></div>';
    return;
  }

  box.className = 'issues';
  box.innerHTML = '<b>' + esc(res.message) + '</b>'
    + (res.advice ? '<p class="tr-advice">' + esc(res.advice) + '</p>' : '')
    + '<ul>'
    + '<li>机器人此刻在不在线：看本页上面那张卡，账号掉线时接口照样通，消息却没人收得到。</li>'
    + '<li>推送是不是被停着：首页那个「暂停全部推送」按下去之后，这一条也会被丢掉；'
    + '静音时段里同理。</li>'
    + '<li>' + esc(term('bot.family', '机器人程序')) + ' 那侧的日志：填对了地址与 Token 却仍不通时，答案通常只在它自己的日志里。</li>'
    + '</ul>'
    + (res.raw ? '<p class="tr-advice">接口原始响应：' + esc(JSON.stringify(res.raw)) + '</p>' : '');
}
