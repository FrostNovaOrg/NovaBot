/**
 * 向导「第一位主播，推到哪」这一步（{@code setup_step} 槽）
 *
 * 这一步做的三件事——查一位主播、挑几个推送目标、把这两样写进 /datasource——全是产品形态，
 * 不是壳。所以它随控制台插件走：卸掉本插件之后向导少这一步，而不是留一步点进去查不到任何东西。
 *
 * <b>放行与落盘都由本文件自己判</b>（见 next）：宿主对插件步一律放行，它不知道
 * 「找到人了没有」「选目标了没有」是什么意思。判定留在宿主的话，这一步会变成
 * 点一下就过，而屏幕上不会有任何异常——落盘那一趟根本没发生，第 5 步却在一台
 * 其实什么都没配的机器上发消息。
 *
 * 本文件里没有任何一个直播平台的名字：平台清单由 store.platforms 在运行期给，
 * 输入提示由推送页那一份 STREAMER_INPUT_HINT 带来。
 */

import {ask} from './confirm.js';
import {api, el, esc, markDirty, say} from './core.js';
import {targetOptions} from './links-model.js';
import {renderStreamers, serializePush, STREAMER_INPUT_HINT} from './push.js';
import {store} from './store.js';
import {detailHash} from './streamers-model.js';

/**
 * 这一步许跳过，按钮上写「先不加主播」
 *
 * 不是「不填也能过」：跳过要先过一段后果确认，见 {@link skip}。
 */
export const skippable = '先不加主播';

/**
 * 这一步此刻手里的东西
 *
 * 与「这台机器上已经这样了」分开：后者由 {@link done} 现问服务端。
 * 换一步再回来时这几格得还在，重画一遍会把刚查到的那位抹掉。
 */
const draft = {streamer: null, targets: []};

/** 可选的推送目标，来自机器人自己给的名单。没有手填号码的格子 */
let options = [];

/** 这一步此刻那块回话，next 拦下来时把理由写在这里 */
let result = null;

/** 宿主交进来的那份上下文，next／skip 用它记推送目标 */
let seen = {};

// ============ 页面零件 ============
// 与向导其余几步同一套 su- 版式，样式在核心的 app.css 里。
// 这几个小函数是宿主 setup.js 里的私有件，插件够不着，因此各留一份

function note(kind, text) {
  const box = el('div', 'su-note ' + (kind || ''));
  box.textContent = text;
  return box;
}

function report(box, res) {
  box.textContent = res.message || (res.success ? '好了' : '没能办成');
  box.className = 'su-r ' + (res.success ? 'ok' : 'err');
}

/**
 * 一格输入。值随打随记进草稿，换一步再回来时那几格还在
 */
function field(host, label, id, value, onInput) {
  const wrap = el('div', 'su-fld');
  const title = el('label');
  title.textContent = label;
  title.setAttribute('for', id);
  wrap.appendChild(title);

  const input = el('input');
  input.type = 'text';
  input.id = id;
  input.value = value == null ? '' : value;
  input.autocomplete = 'off';
  if (onInput) input.addEventListener('input', () => onInput(input.value));
  wrap.appendChild(input);

  host.appendChild(wrap);
  return input;
}

function invalidateStreamer(draft) {
  draft.streamer = null;
  return draft;
}

/**
 * 取一次群与好友名单
 */
async function loadOptions() {
  try {
    const [groups, friends] = await Promise.all([
      api('/onebot/targets?type=group'), api('/onebot/targets?type=friend')]);
    options = targetOptions(groups, friends);
  } catch (e) {
    // 取不到时不退回手填：那等于把「填错一位数不报错」那个失败形态请回来。
    // 界面上另有一句说明，见 targetPicker
    options = [];
  }
}

/**
 * 把这一步画进 host
 * @param host 宿主给的容器
 * @param ctx {status, login, api, pickTargets}
 */
export function render(host, ctx) {
  seen = ctx || {};
  result = el('div', 'su-r');

  const head = el('div', 'su-h');
  head.textContent = '第一位主播，推到哪';
  host.appendChild(head);
  const desc = el('div', 'su-d');
  desc.textContent = '填主播的 ' + STREAMER_INPUT_HINT + '。';
  host.appendChild(desc);

  // 装了哪些直播平台是运行期才知道的事，三种情形都要说清楚——
  // 与推送页「添加主播」那个面板同一条规矩（见 push.js 的 addStreamer）
  const known = store.platforms || [];
  if (!known.length) {
    host.appendChild(note('warn', '没有装任何直播平台插件，加不了主播。'));
    return;
  }

  let chosen = (draft.streamer && draft.streamer.platform) || known[0];
  const found = el('div');
  if (known.length > 1) {
    const wrap = el('div', 'su-fld');
    const label = el('label');
    label.textContent = '平台';
    label.setAttribute('for', 'setup-platform');
    wrap.appendChild(label);

    const picker = el('select');
    picker.id = 'setup-platform';
    picker.innerHTML = known.map(one => '<option value="' + esc(one) + '">' + esc(one) + '</option>').join('');
    picker.value = chosen;
    picker.addEventListener('change', () => { chosen = picker.value; invalidateStreamer(draft); paintFound(found); });
    wrap.appendChild(picker);
    host.appendChild(wrap);
  }

  const row = el('div', 'su-row');
  const input = field(row, STREAMER_INPUT_HINT, 'setup-uid',
    draft.streamer ? String(draft.streamer.uid) : '',
    () => { invalidateStreamer(draft); paintFound(found); });
  host.appendChild(row);

  const look = el('button', 'ghost');
  look.type = 'button';
  look.id = 'setup-lookup';
  look.textContent = '找一下';
  look.addEventListener('click', async () => {
    const value = input.value.trim();
    if (!value) {
      result.textContent = '请先输入 ' + STREAMER_INPUT_HINT + '。';
      result.className = 'su-r';
      return;
    }

    look.disabled = true;
    result.textContent = '查询中…';
    result.className = 'su-r';
    draft.streamer = null;

    try {
      const res = await api('/streamer/lookup', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({platform: chosen, uid: value}),
      });
      if (res.success) {
        draft.streamer = {platform: chosen, uid: res.uid, uname: res.uname, roomId: res.roomId,
          face: res.face, fans: res.fans};
        result.textContent = '';
        result.className = 'su-r';
      } else {
        report(result, res);
      }
    } catch (e) {
      report(result, {success: false, message: '查询失败：' + e.message});
    }

    look.disabled = false;
    paintFound(found);
  });

  host.appendChild(look);
  host.appendChild(result);
  host.appendChild(found);
  paintFound(found);

  // 名单可能还没回来：回来之后把「推到哪」补上，而不是让那一块一直写着取不到
  loadOptions().then(() => paintFound(found));
}

/**
 * 找到的那位主播，连同「推到哪」
 *
 * 🔴 这里<b>不出</b>「去主播页看看」那个链接：此刻只是查到了人，还没落盘，
 * 而主播详情页读的是已经落盘的名单——点进去是一张空页。那个链接归 {@link doneLink}。
 */
function paintFound(host) {
  host.innerHTML = '';
  if (!draft.streamer) return;

  const card = el('div', 'su-card');
  const who = el('div', 'su-nm');
  who.textContent = '找到了：' + draft.streamer.uname;
  card.appendChild(who);

  // 粉丝数是这张小卡上最容易发现「认错人」的一项：uid 打错一位仍可能查到一位真实存在的人，
  // 昵称与头像未必看得出不对，而粉丝数常常差着数量级。取不到时不写 0——
  // 那会被读成「这人一个粉丝也没有」
  const meta = el('div', 'su-sub');
  meta.textContent = 'uid ' + draft.streamer.uid
    + (draft.streamer.roomId ? ' · 直播间 ' + draft.streamer.roomId : '')
    + (draft.streamer.fans == null ? '' : ' · 粉丝 ' + draft.streamer.fans);
  card.appendChild(meta);

  host.appendChild(card);
  host.appendChild(targetPicker());
}

/**
 * 「推到哪」：从机器人已经在的群、已经加的好友里挑，可以多选
 *
 * 🔴 <b>没有手填号码的格子</b>，与「发一条试试」同一条理由：填错一位数不会有任何报错，
 * 消息只是发去了别处，而这一步存在的意义正是把那种错拦在配置阶段。
 */
function targetPicker() {
  const box = el('div', 'su-pick');
  const title = el('div', 'su-d');
  title.textContent = '推到哪（可多选）';
  box.appendChild(title);

  if (!options.length) {
    box.appendChild(note('warn', '暂时取不到群与好友名单：先把机器人连上（第 2 步），'
      + '或者把它拉进一个群、加个好友，再回来选。'));
    return box;
  }

  const list = el('div', 'su-targets');
  list.id = 'setup-targets';
  options.forEach(item => {
    const label = el('label');
    const check = el('input');
    check.type = 'checkbox';
    check.checked = draft.targets.includes(item.key);
    check.addEventListener('change', () => {
      draft.targets = check.checked
        ? draft.targets.concat([item.key])
        : draft.targets.filter(key => key !== item.key);
    });
    label.appendChild(check);

    const text = el('span');
    text.textContent = item.text + (item.configured ? ' · 已配推送' : '');
    label.appendChild(text);
    list.appendChild(label);
  });
  box.appendChild(list);

  box.appendChild(note('', '开播、下播、下播报告、动态这几种通知默认全开、用默认模板，'
    + '之后在「推送」页里细调。'));
  return box;
}

/**
 * 这一步成立了没有：这台机器上已经有主播了
 * @param ctx {status, login, api}
 */
export async function done(ctx) {
  return (((ctx || {}).status || {}).users || []).length > 0;
}

/**
 * 「下一步」按下之后
 *
 * 🔴 拦住时把理由写出来，而不是默默不走：一个点了没反应的按钮，
 * 与一个坏掉的按钮在屏幕上长得一样。两条拦法与落盘都在这里，宿主对插件步一律放行。
 * @return {Promise<boolean>} 放不放行
 */
export async function next(ctx) {
  seen = ctx || seen;
  if (!(store.platforms || []).length) {
    // 一个直播平台插件都没装：这一步没得做，不该拦着人往下走
    return true;
  }
  if (!draft.streamer) {
    stop('先填 ' + STREAMER_INPUT_HINT + ' 并点「找一下」；确实不想现在加的话，点「先不加主播」');
    return false;
  }
  if (!draft.targets.length) {
    stop('至少选一个群或一位好友，否则这位主播的开播通知没有地方可去');
    return false;
  }
  return saveStreamer();
}

/** 把拦住的理由写进这一步那块回话 */
function stop(reason) {
  if (!result) return;
  result.textContent = reason;
  result.className = 'su-r err';
}

/**
 * 「先不加主播」。<b>要过一次确认</b>：不加主播的话这台 NovaBot 起来什么都不做，
 * 而一路点下去的人不会意识到这一点
 * @return {Promise<boolean>} 真才跳过
 */
export async function skip() {
  return ask({title: '确定先不加吗？',
    body: '不加主播的话，这台 NovaBot 起来什么都不做——不采集，也不推送。'
      + '之后可以在「推送」页里加。'});
}

/**
 * 走完那一页上的「去主播页看看」
 *
 * 查到人、还没落盘时不出：详情页读的是已经落盘的名单，点进去是一张空页。
 * 地址问 detailHash，不自己拼——自己拼的那一份与主播页那一份分叉时，
 * 点进去会落到列表而不是这位主播。
 * @param ctx {status, login, api}
 * @return {{text: string, href: string}|null} 没有可去处时为 null
 */
export function doneLink(ctx) {
  const users = (((ctx || {}).status || {}).users || []).length;
  if (!draft.streamer || !users) return null;
  return {text: '去主播页看看', href: detailHash(draft.streamer.platform, draft.streamer.uid)};
}

/**
 * 把这位主播连同推送目标一起落盘
 *
 * 四种通知按「这个平台注册出来的处理器」全开，界面不写死任何一份清单——
 * 处理器由插件带来，写死的话，插件加一种通知这里就少一种，而屏幕上不会有任何异常。
 * @return {Promise<boolean>} 存下去了没有。没存下去就不往下走，
 *         否则第 5 步会在一台其实什么也没配的机器上发消息
 */
async function saveStreamer() {
  const one = draft.streamer;

  const messages = (store.handlerList || [])
    .filter(handler => !handler.platform || handler.platform === one.platform)
    .map(handler => ({handler: handler.className}));

  const targets = draft.targets
    .map(key => options.find(item => item.key === key))
    .filter(Boolean)
    .map(item => ({platform: item.sender, type: item.type, num: item.num, enabled: true, messages}));

  const before = store.pushData;
  // 回上一步改完再走一遍是正当走法，因此同一位主播先去重再加——
  // 不去重的话，配置文件里会出现两条同 uid 的记录，而那台机器每场直播推两遍
  store.pushData = before
    .filter(user => !(Number(user.uid) === Number(one.uid) && user.platform === one.platform))
    .concat([{
      uid: one.uid, platform: one.platform, enabled: true, targets,
      _uname: one.uname, _roomId: one.roomId, _face: one.face,
    }]);

  try {
    const res = await api('/datasource', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({content: serializePush()}),
    });
    if (!res.success) {
      store.pushData = before;
      say(res.message || '没能存下这位主播', 'err');
      return false;
    }
  } catch (e) {
    store.pushData = before;
    say('没能存下这位主播：' + e.message, 'err');
    return false;
  }

  // 快照跟着走，否则推送页那条改动条会显示「还有 1 处没保存」——而它已经存下去了
  store.pushSaved = serializePush();
  renderStreamers();
  markDirty();
  // 末步「发一条试试」只在刚选定的这几个目标里挑：这一步要验的正是那条路通不通，
  // 换个别的目标发通了，说明的是另一回事
  if (typeof (seen || {}).pickTargets === 'function') seen.pickTargets(draft.targets);
  return true;
}
