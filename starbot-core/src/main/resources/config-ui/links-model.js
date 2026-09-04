/**
 * 连接页视图模型
 *
 * 把 /api/status、/api/login、/api/pages 与只读口令清单算成屏幕上那三张卡：
 * 各自的灯色、那句状态说明、以及要人动手时该说的话。
 *
 * 刻意不碰 DOM，也不发请求。连接页要在「登录态 × 机器人态」九种组合下都说得对，
 * 而这九种在真机上凑齐一次的代价极高——掉登录要等凭据过期，机器人掉线要去把 OneBot 实现停掉，
 * 未配置那一档只在刚装好的机器上出现一次。算成纯函数之后，喂十二份回包跑一遍就能逐条对答案
 * （tools/links-model-check.sh），渲染那一层只管把算好的摆上去。
 *
 * 本文件里没有任何一个直播平台的名字：平台卡的标题取自插件登记时报的显示名，
 * 掉登录之后代价是什么取自那个平台自己的探针。写死一个平台名，这一页就替一件可能没装的东西说了话。
 */

import {probesIn, worstLamp} from './home-model.js';

/**
 * 机器人那张卡的固定标识
 *
 * 它同时是页面上那张卡的 id（{@code id="card-napcat"}），两头由 ConfigUiFrontendTest 钉着。
 * 首页链路图上「QQ」那一站的落点<b>不</b>读这个常量，而是从卡片清单里查——理由见 {@link cardAnchor}。
 */
const NAPCAT_KEY = 'napcat';

/**
 * 外部面板那张卡的固定标识
 *
 * 同样是页面上那张卡的 id（{@code id="card-panel"}）。
 */
const PANEL_KEY = 'panel';

/**
 * 平台卡的标识前缀
 *
 * 加前缀是因为它与插件的页签标识同源，而页签标识是插件自己填的——
 * 不加前缀的话，一个把自己叫作 napcat 的插件会顶掉机器人那张卡的锚点。
 */
const PLATFORM_PREFIX = 'p-';

/**
 * 一张平台卡
 *
 * 卡壳由核心画，卡里的内容由插件自己填。分开是因为两侧知道的事不一样：
 * 核心手里有探针与账号状态，答得出「这一段此刻是好是坏」；而扫码、退出登录只有插件做得了。
 *
 * 掉登录时那句「代价是什么」取自平台自己的登录态探针，不在这里写死——
 * 「动态推送停了」这种话只有那个平台说得对，而核心连它叫什么都不该知道。
 * @param card 插件登记的连接卡，{id, displayName}
 * @param status /api/status 回包
 * @param accounts /api/login 回包里的 accounts
 * @return 卡片视图模型
 */
function platformCard(card, status, accounts) {
  const account = accounts.find(item => item.platform === card.id) || null;
  const base = {key: PLATFORM_PREFIX + card.id, kind: 'platform', id: card.id,
    title: card.displayName || card.id, show: true, level: 'off', caption: '', note: '', advice: ''};

  if (!account) {
    // 登记了连接卡却没有登录能力：这一页仍然要出现（插件自有它要显示的东西），
    // 只是核心这一侧没有登录态可说
    return base;
  }

  // ⚠️ 探针不申报自己属于哪个平台，因此同时装两个直播平台时，这里可能取到另一个平台的探针，
  //    那张卡上的状态说明就会张冠李戴。当下只有一个直播平台，尚不会发生；
  //    真装第二个之前，探针得先带上平台标识，否则这一句不可信。
  const probe = probesIn(status, 'PLATFORM').find(item => item.loginState) || {};

  if (account.disabledReason) {
    // 匿名是配置选出来的，不是故障：判熄灯而不是判黄。判黄等于催人去修一件他自己关掉的事
    return Object.assign(base, {
      level: 'off',
      caption: '匿名模式（未登录）',
      note: account.disabledReason,
      action: {text: '去设置页改', href: '#/settings'},
    });
  }

  if (account.loggedIn) {
    return Object.assign(base, {
      level: 'ok',
      caption: accountCaption(account),
      note: expiryText(account),
      advice: '',
    });
  }

  return Object.assign(base, {
    level: 'warn',
    caption: account.qrCode ? '未登录 · 请扫下面的二维码' : '未登录 · 二维码还没生成，稍候',
    note: probe.advice || probe.summary || '登录掉了，重新扫码即可',
  });
}

/**
 * 已登录时卡头那一句
 *
 * 昵称来自登录接口顺手带回的 accountName（平台侧查一次，失败就空）。
 * 有名就名和 uid 都写，没有就只写 uid——两档长得不一样，才看得出「这次没查到」。
 * @param account /api/login 里的一项
 * @return {string}
 */
export function accountCaption(account) {
  const uid = (account && account.accountId) || '未知';
  const name = String((account && account.accountName) || '').trim();
  return name ? '已登录 · ' + name + ' · uid ' + uid : '已登录 · 账号 ' + uid;
}

/**
 * 凭据还能用多久
 *
 * 两段各自可能缺席：有的平台答得出到期时刻，有的只答得出「会不会自动续上」，
 * 也有两样都答不出的。缺的那段不写，不编一个日期出来——使用者恰恰照着它决定何时重新扫码。
 * @param account 账号状态
 * @return {string} 说明，无可说时为空串
 */
function expiryText(account) {
  const parts = [];
  if (account.expiresAt) {
    parts.push('凭据 ' + day(account.expiresAt) + ' 到期');
  }
  if (account.credentialNote) {
    parts.push(account.credentialNote);
  }
  return parts.join(' · ');
}

/**
 * 年-月-日，按浏览器所在时区
 *
 * 不用 toISOString()：那一串是 UTC 的日期，东八区的深夜会显示成前一天。
 * @param at 毫秒时间戳
 * @return {string} 日期
 */
function day(at) {
  const time = new Date(at);
  const pad = n => String(n).padStart(2, '0');
  return time.getFullYear() + '-' + pad(time.getMonth() + 1) + '-' + pad(time.getDate());
}

/**
 * 机器人那张卡
 *
 * 「配没配」的判据是一个推送平台都没注册出来，与首页那一档同源：配置文件里写了但 Token 空着的
 * 那种也算没有，因为它同样发不出消息。此处只看推送平台、不看主播——主播配没配是另一页的事，
 * 而这一页答的是「这条链路通没通」。
 * @param status /api/status 回包
 * @return 卡片视图模型
 */
function napcatCard(status) {
  const base = {key: NAPCAT_KEY, kind: 'napcat', title: '机器人（NapCat 等）',
    show: true, level: 'off', caption: '', note: '', advice: ''};

  if (!(status.senders || []).length) {
    return Object.assign(base, {
      caption: '还没连上机器人 · 填好地址与端口，点「测试连接」',
      note: '',
    });
  }

  const probes = probesIn(status, 'BOT');
  const level = worstLamp(probes.map(item => item.lamp));
  const source = probes.find(item => item.lamp === level) || {};

  return Object.assign(base, {
    level,
    caption: source.summary || '',
    advice: source.advice || '',
    note: queueText(status, level),
  });
}

/**
 * 队列里还压着多少条
 *
 * 掉线时消息不会立刻消失，而是在队列里排着——排到队满才开始丢最旧的那条。
 * 光说「连不上」答不了「刚才那条开播通知还在不在」，而这两件事使用者关心的程度完全不同。
 *
 * 累计丢弃数只在真丢过时才说：积压回落到 0 有两种走法——发出去了，和被丢掉了，
 * 只看积压数这两种长得一模一样。
 * @param status /api/status 回包
 * @param level 本卡此刻的灯色
 * @return {string} 说明，无可说时为空串
 */
function queueText(status, level) {
  const queue = status.queue || {};
  const pending = Number(queue.pending) || 0;
  const dropped = Number(queue.dropped) || 0;

  // 一切正常且队列是空的时候不说：那是常态，写出来只会让人以为有个数字要盯
  if (level === 'ok' && !pending && !dropped) {
    return '';
  }
  if (!pending && !dropped) {
    return '';
  }

  return '队列积压 ' + pending + ' 条'
    + (dropped ? '，另有 ' + dropped + ' 条因队列满被丢弃' : '');
}

/**
 * 外部面板那张卡
 *
 * 「一把有效的都没有」与「还没签发过」不是同一句话：前者是撤干净了，后者是从没用过这条路。
 * 两种情形下外部面板都连不上，而该做的事不一样——前者要想清楚是不是撤错了，后者只需签一把。
 * @param tokens 只读口令清单
 * @return 卡片视图模型
 */
function panelCard(tokens) {
  const active = tokens.filter(item => item.active).length;
  const revoked = tokens.length - active;
  const base = {key: PANEL_KEY, kind: 'panel', title: '外部面板',
    show: true, level: 'off', caption: '', note: '', advice: ''};

  if (active) {
    return Object.assign(base, {
      level: 'ok',
      caption: active + ' 把有效' + (revoked ? ' · ' + revoked + ' 把已吊销' : ''),
    });
  }

  return Object.assign(base, {
    caption: revoked ? ('一把有效的都没有 · ' + revoked + ' 把已吊销') : '还没签发过口令',
    note: '外部面板连不上时，先看这里是不是空的',
  });
}

/**
 * 算出连接页此刻该显示什么
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param cards 插件登记的连接卡，[{id, displayName}]
 * @param tokens 只读口令清单，来自 /api/event-tokens
 * @return 连接页视图模型
 */
export function linksModel(status, login, cards, tokens) {
  const state = status || {};
  const accounts = ((login || {}).accounts) || [];
  const list = (cards || []).map(card => platformCard(card, state, accounts));

  // 平台卡在前：定稿的顺序是「先连上直播平台，再连上机器人，最后才谈把事件送到外面去」，
  // 这也正是使用者第一次配置时的先后
  return {cards: list.concat([napcatCard(state), panelCard(tokens || [])])};
}

/**
 * 首页链路图上某一站，落到本页哪张卡上
 *
 * 一个平台插件都没装时平台那一站没有落点，此时必须回空串而不是回一个页面上不存在的锚——
 * 那种锚点跳过去什么也不会发生，而地址栏确实变了，看起来像是页面卡住了。
 *
 * 「本机」那一站在本页上没有对应的卡：它讲的是这台机器自己的状况，不是它与外面的某一条连接。
 * 点它不跳，好过跳去一张与它无关的卡。
 *
 * 🔴 落点一律<b>从卡片清单里查出来</b>，不回一个写死的标识。回常量的那一版看起来更简单，
 *    可它与真出现在页面上的那张卡之间没有任何东西钉着：改掉卡的标识，常量照旧回旧名，
 *    点那一站就跳向一张不存在的卡——而两处的代码各看各都对，测试也照旧全过。
 * @param model 本页视图模型
 * @param station 站名，platform / self / bot
 * @return {string} 卡片标识，没有落点时为空串
 */
export function cardAnchor(model, station) {
  const kind = STATION_CARD[station];
  if (!kind) {
    return '';
  }

  const hit = (model.cards || []).find(card => card.kind === kind);
  return hit ? hit.key : '';
}

/**
 * 首页链路图上的站，与本页卡片种类的对应
 *
 * 「本机」不在表里：它讲的是这台机器自己的状况，本页上没有对应的卡。
 */
const STATION_CARD = {platform: 'platform', bot: 'napcat'};

/**
 * 把机器人自己知道的群与好友名单转成可选项
 *
 * 取值须与 PushTargetType 的 code 一致：GROUP(1)、FRIEND(0)。
 * @param groups /api/onebot/targets?type=group 回包
 * @param friends /api/onebot/targets?type=friend 回包
 * @return 可选项
 */
export function targetOptions(groups, friends) {
  const options = [];

  for (const row of ((groups || {}).items) || []) {
    options.push({
      key: row.sender + '|1|' + row.num,
      sender: row.sender, type: 1, num: row.num, configured: !!row.configured,
      // 名与人数、是不是管理员单列，不只拼进 text：推送页要按这几项单独排版
      // （群名一行、群号与人数一行、管理员一枚药丸），从 text 里再切回来的话，
      // 切法与拼法迟早分叉，而分叉的表现是群名里多出半截括号
      name: row.name || '', memberCount: row.memberCount == null ? null : row.memberCount,
      admin: !!row.admin,
      text: '群 · ' + (row.name || row.num) + '（' + row.num + '）'
        + (row.memberCount ? ' · ' + row.memberCount + ' 人' : ''),
    });
  }

  for (const row of ((friends || {}).items) || []) {
    options.push({
      key: row.sender + '|0|' + row.num,
      sender: row.sender, type: 0, num: row.num, configured: !!row.configured,
      // 备注优先于昵称：备注是这台机器的主人自己写的，昵称是对方随时会改的
      name: row.remark || row.nickname || '', memberCount: null, admin: false,
      text: '好友 · ' + (row.remark || row.nickname || row.num) + '（' + row.num + '）',
    });
  }

  return options;
}

/**
 * 认一个发送目标
 *
 * 🔴 <b>只认名单里有的那几个</b>。填错一位数不会有任何报错，只是消息发去了别处，或者哪儿也没去——
 * 这四类错（群号填错、Token 不对、机器人不在群里、OneBot 没起）的表现完全一样，
 * 而「发一条试试」这件事存在的全部意义正是把它们分开。放一个手填的号码进来，
 * 第一类错就又混了回去，而且是在使用者最相信这一步的时候。
 *
 * 判成一个函数而不是「界面上没有那个输入框」：界面换几次版这条判据都还在。
 * 靠没有输入框来保证的话，下一个人为了「方便」把框加回来时，没有任何东西会红。
 * @param options 可选项，来自 {@link targetOptions}
 * @param key 选中的那一条
 * @return 认出来的目标，或拒绝与理由
 */
export function resolveTarget(options, key) {
  const hit = (options || []).find(item => item.key === key);
  if (!hit) {
    return {ok: false, reason: '请从名单里挑一个群或好友。这里不接受手填号码——填错一位数不会报错，'
      + '消息只是发去了别处'};
  }
  return {ok: true, platform: hit.sender, type: hit.type, num: hit.num};
}
