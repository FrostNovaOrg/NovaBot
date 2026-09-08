/**
 * QQ 推送页的视图模型
 *
 * 把 /api/datasource（推送配置）、/api/state（这些会话此刻听不听话）、/api/handlers、
 * /api/push-history、/api/at-all/quota 与机器人自己给的群与好友名单，算成屏幕上那棵树、
 * 通道页四段与「本群设置」那四行摘要。
 *
 * 刻意不碰 DOM，也不发请求。这一页要在好几种情形下都说得对——通道没配过、群名取不到、
 * 累计数据没开、命令被群管理员关掉、状态文件里留着一个已经不推的群——而这些情形在真机上
 * 凑齐一次的代价极高：要么得先去群里发命令把开关关掉，要么得去改线上配置。算成纯函数之后，
 * 喂几份回包跑一遍就能逐条对答案（tools/push-model-check.sh），渲染那一层只管把算好的摆上去。
 *
 * 🔴 <b>本文件里没有任何一个平台名、也没有任何一条通知的名字。</b>四种通知取自
 * /api/handlers 现给的清单，命令分组取自各命令自报的 category，「这个会话里哪几条命令
 * 不列进菜单」取自 /api/state 里后端算好的那一份（见 RuntimeStateController#menuOf）——
 * 那条规则在后端只有一份实现，抄一份到这里的话，改了那一份的那天，控制台仍按旧规矩画。
 */

/**
 * 认一个会话用的键
 *
 * 与后端认会话用的是同一把键（推送平台 + 号，见 RuntimeStateController#sessions）。
 * 两边各用各的键会在同一个号同时是群号与好友号时分叉，而那时屏幕上一切正常。
 * @param platform 推送平台
 * @param num 群号或账号
 * @return {string} 键
 */
export function sessionKey(platform, num) {
  return platform + ':' + num;
}

/**
 * 认一个通道用的键：一位主播 × 一个会话
 * @param user 推送配置里的一位主播
 * @param target 该主播的一个推送目标
 * @return {string} 键
 */
export function channelKey(user, target) {
  return user.platform + '/' + user.uid + '/' + sessionKey(target.platform, target.num);
}

/**
 * 按键取会话
 * @param sessions /api/state 的 sessions
 * @param platform 推送平台
 * @param num 群号或账号
 * @return 会话，没有则为 null
 */
export function sessionOf(sessions, platform, num) {
  const key = sessionKey(platform, num);
  return (sessions || []).find(item => sessionKey(item.platform, item.num) === key) || null;
}

/**
 * 主播的展示名
 *
 * 昵称要等程序去直播平台查回来，查回来之前（刚启动、或平台未登录时）它是空的。
 * 此时退回 uid，而不是显示一个空名字——几位主播会因为空名字相同而在树上并成一行。
 * @param user 推送配置里的一位主播
 * @return {string} 展示名
 */
export function streamerName(user) {
  const name = user._uname;
  return name == null || String(name).trim() === '' ? 'uid ' + user.uid : String(name);
}

/**
 * 通道的展示名
 *
 * 群名与好友昵称只有机器人自己知道，取自它给的名单。名单取不到时（OneBot 掉线、
 * 或这个群机器人已经不在了）退回「群 12345」这种说法——编一个名字出来更糟：
 * 屏幕上会有一个查无此群的名字，而使用者以为自己配对了。
 * @param session 会话，可为 null
 * @param target 推送目标
 * @param directory 名单，键为「平台|类型码|号」，见 buildDirectory
 * @return {string} 展示名
 */
export function channelName(session, target, directory) {
  const known = (directory || {})[directoryKey(target.platform, target.type, target.num)];
  if (known && known.name) return known.name;
  return typeName(session, target) + ' ' + target.num;
}

/**
 * 会话类型的说法
 *
 * 取 /api/state 给的那一份（它来自推送目标类型枚举本身）。取不到时按类型码退回一句
 * 中性的说法，不在这里另建一张码到中文的表——那张表与枚举分叉的那天，
 * 屏幕上会把私聊标成群聊，而两边的代码看起来都对。
 * @param session 会话，可为 null
 * @param target 推送目标
 * @return {string} 说法
 */
export function typeName(session, target) {
  if (session && session.type) return session.type;
  return Number(target.type) === 1 ? '群聊' : '私聊';
}

/**
 * 名单里认一条用的键
 * @param platform 推送平台
 * @param type 类型码，1 群 0 好友
 * @param num 号
 * @return {string} 键
 */
export function directoryKey(platform, type, num) {
  return platform + '|' + Number(type) + '|' + num;
}

/**
 * 把机器人给的名单摊成一张按键查的表
 * @param options 可选项，来自 links-model.js 的 targetOptions
 * @return 键 → {name, memberCount, admin}
 */
export function buildDirectory(options) {
  const map = {};
  for (const item of options || []) {
    map[directoryKey(item.sender, item.type, item.num)] = {
      name: item.name || '', memberCount: item.memberCount == null ? null : item.memberCount,
      admin: !!item.admin,
    };
  }
  return map;
}

/**
 * 这一类通知在配置里可能写成哪几个名字
 *
 * 🔴 处理器搬过包之后，使用者 {@code datasource.json} 里那一条写的仍是旧名。因此屏幕上
 * 每一处「这一条配的是不是这一类通知」都得先<b>归一到主名</b>再比，一处漏了的表现是：
 * 开关显示成「关」而机器人照推、取消勾选删不掉、旧名下的自定义模板读不到而页面报「默认模板」，
 * 三样都不报错。
 *
 * 旧名<b>不在前端写</b>：它随 /api/handlers 一项的 aliases 送来，真源是后端那张别名表
 * （运行期认处理器用的也是它）。在这里另存一份的话，两份清单迟早对不上，而那正是这个毛病的成因。
 * @param handler /api/handlers 的一项，或任何带 className 与 aliases 的东西
 * @return {string[]} 主名在前，其后是旧名
 */
export function handlerNames(handler) {
  const main = (handler || {}).className;
  const names = main == null || main === '' ? [] : [main];
  for (const alias of ((handler || {}).aliases) || []) {
    if (alias != null && alias !== '' && !names.includes(alias)) names.push(alias);
  }
  return names;
}

/**
 * 配置里这一条消息归哪一类通知
 *
 * 反着问 {@link handlerNames}：拿配置里存的那一串（可能是旧名）找回处理器。
 * 按主名严格比的话，旧名那一条会被当成「不认得的通知」而在界面上整条消失。
 * @param message 推送配置里的一条消息
 * @param handlers /api/handlers 的 handlers
 * @return 处理器，认不出则为 null
 */
export function handlerOf(message, handlers) {
  const name = (message || {}).handler;
  if (name == null) return null;
  return (handlers || []).find(handler => handlerNames(handler).includes(name)) || null;
}

/**
 * 这条通知此刻开着没有
 *
 * 推送配置里「没有这条消息」与「有这条消息但 enabled 为假」都算关着。前者是没勾过，
 * 后者是勾过又关掉——对使用者是同一件事：这个通道收不到这类通知。
 * @param target 推送目标
 * @param handler /api/handlers 的一项
 * @return {boolean} 是否开着
 */
export function noticeOn(target, handler) {
  const message = messageOf(target, handler);
  return !!message && message.enabled !== false;
}

/**
 * 取这个通道上某一类通知的那条消息
 *
 * 主名与旧名都算这一类（见 {@link handlerNames}）。参数收的是<b>处理器整项</b>而不是一个类名串：
 * 只给类名的话，调用处就得自己去凑那份旧名清单，而凑漏一处正是这个毛病的形状。
 * @param target 推送目标
 * @param handler /api/handlers 的一项
 * @return 消息，没有则为 null
 */
export function messageOf(target, handler) {
  const names = handlerNames(handler);
  if (!names.length) return null;
  return ((target || {}).messages || []).find(item => names.includes(item.handler)) || null;
}

/**
 * 这个通道的四种通知开关
 *
 * 「四种」不是写死的四个名字，而是 /api/handlers 现给的清单里属于这位主播所在平台的那些。
 * 装了第二个平台时，各平台的通知各自出现在各自主播的通道下；插件加一类新通知，
 * 这里自动多一行，界面一个字不用改。
 * @param user 主播
 * @param target 推送目标
 * @param handlers /api/handlers 的 handlers
 * @return 每类通知一行
 */
export function noticeSwitches(user, target, handlers) {
  return (handlers || [])
    .filter(handler => !handler.platform || handler.platform === user.platform)
    .map(handler => ({
      className: handler.className,
      // 旧名一并带上：开关那一行改完要把「这一类通知」整个交给 toggleNotice，
      // 而删掉的得是主名与它的全部旧名。少带这一栏，取消勾选就只删得掉主名那条
      aliases: (handler.aliases) || [],
      displayName: handler.displayName || handler.className,
      description: handler.description || '',
      on: noticeOn(target, handler),
      // 「消息长什么样」只对有文字模板的那几类成立；「报告长什么样」只对自带版式项的那类成立。
      // 两者都由处理器自己声明，界面不认得任何一类通知的名字
      hasTemplate: ((handler.placeholders) || []).length > 0,
      hasLayout: ((handler.options) || []).length > 0,
    }));
}

/**
 * 这个通道的模板是默认的还是自定义的
 *
 * 判法：这个通道开着的每一类通知，逐个参数与处理器自报的默认参数比。有一个键的值与默认不同、
 * 或有一个默认参数里没有的键（@ 谁那一档就是这种，见 AtMode），即为自定义。
 *
 * ℹ️ 「默认」在这里是<b>与当前默认值一字不差</b>，不是「使用者没动过」。历史上发过、
 * 后来被取代的那几版默认值只有服务端认得（supersededDefaults），接口面上没有；
 * 停在旧默认值上的通道因此会显示成「自定义」。宁可这样：反过来把一份真的改过的模板
 * 说成「默认」，使用者按「恢复默认」时会毫无预兆地丢掉自己写的东西。
 * @param target 推送目标
 * @param handlers /api/handlers 的 handlers
 * @return {{custom: boolean, changed: string[]}} 是否自定义，以及哪几类通知与默认不同
 */
export function templateState(target, handlers) {
  const changed = [];
  for (const handler of handlers || []) {
    const message = messageOf(target, handler);
    if (!message || message.enabled === false) continue;
    if (paramsDiffer(message.params, handler.defaultParams)) changed.push(handler.className);
  }
  return {custom: changed.length > 0, changed};
}

/**
 * 这个通道的报告版式是默认的还是自定义的
 *
 * 「哪一类通知有版式」由处理器自报（options 非空），界面不认得「下播报告」这四个字。
 * @param target 推送目标
 * @param handlers /api/handlers 的 handlers
 * @return {{present: boolean, custom: boolean, className: string, handler: object}}
 *         这个通道开着带版式的那类通知没有、版式改过没有、是哪一类、那一类的整项
 */
export function layoutState(target, handlers) {
  for (const handler of handlers || []) {
    if (!((handler.options) || []).length) continue;
    if (!noticeOn(target, handler)) continue;

    const params = (messageOf(target, handler) || {}).params || {};
    const custom = handler.options.some(option => {
      const value = params[option.key];
      return value !== undefined && value !== null && String(value) !== String(option.defaultValue);
    });
    // 连整项一起给回去：拿 className 回头再查一遍的调用处，查法与这里差一点就会漏掉旧名那一条
    return {present: true, custom, className: handler.className, handler};
  }
  return {present: false, custom: false, className: '', handler: null};
}

/**
 * 一份推送参数与默认参数比，有没有不一样的地方
 * @param params 配置里存着的参数，可为 null
 * @param defaults 处理器自报的默认参数，可为 null
 * @return {boolean} 有不同则为 true
 */
function paramsDiffer(params, defaults) {
  const now = params || {};
  const base = defaults || {};
  for (const key of Object.keys(now)) {
    if (String(now[key]) !== String(base[key])) return true;
  }
  return false;
}

/**
 * 左树：主播 → 通道两级
 * @param users 推送配置里的主播
 * @param sessions /api/state 的 sessions
 * @param handlers /api/handlers 的 handlers
 * @param directory 名单，见 buildDirectory
 * @return 每位主播一项
 */
export function pushTree(users, sessions, handlers, directory) {
  return (users || []).map(user => ({
    uid: user.uid,
    platform: user.platform,
    name: streamerName(user),
    enabled: user.enabled !== false,
    // 一个通道都没配的主播只采集不推送。这一条得写在树上：屏幕上它与配好了的主播长得一样，
    // 而「配了半天群里没动静」正是这么来的
    collectOnly: ((user.targets) || []).length === 0,
    channels: ((user.targets) || []).map(target => {
      const session = sessionOf(sessions, target.platform, target.num);
      const switches = noticeSwitches(user, target, handlers);
      return {
        num: target.num,
        platform: target.platform,
        type: Number(target.type),
        typeName: typeName(session, target),
        name: channelName(session, target, directory),
        enabled: target.enabled !== false,
        noticesOff: switches.filter(item => !item.on).length,
        commandsOff: ((session || {}).disabled || []).length,
      };
    }),
  }));
}

/**
 * 通道一览：主播 × 通道拉平成一张表
 * @param users 推送配置里的主播
 * @param sessions /api/state 的 sessions
 * @param handlers /api/handlers 的 handlers
 * @param directory 名单，见 buildDirectory
 * @return 每个通道一行
 */
export function channelIndex(users, sessions, handlers, directory) {
  const rows = [];
  for (const user of users || []) {
    for (const target of (user.targets) || []) {
      const session = sessionOf(sessions, target.platform, target.num);
      const switches = noticeSwitches(user, target, handlers);
      rows.push({
        uid: user.uid,
        streamer: streamerName(user),
        num: target.num,
        platform: target.platform,
        type: Number(target.type),
        typeName: typeName(session, target),
        name: channelName(session, target, directory),
        on: switches.filter(item => item.on).map(item => item.displayName),
        custom: templateState(target, handlers).custom,
      });
    }
  }
  return rows;
}

/**
 * 配置里已经没有、状态文件里却还留着的会话
 *
 * 它们不属于任何通道，因此在树上一个字也看不见——而命令还关着，一旦重新把推送配回来
 * 就立刻生效。不单列一块的话，这些残留只能靠翻状态文件才发现。
 * @param users 推送配置里的主播
 * @param sessions /api/state 的 sessions
 * @return 每个残留会话一行
 */
export function strandedSessions(users, sessions) {
  const used = new Set();
  for (const user of users || []) {
    for (const target of (user.targets) || []) used.add(sessionKey(target.platform, target.num));
  }

  return (sessions || [])
    .filter(item => !used.has(sessionKey(item.platform, item.num)))
    .map(item => ({
      platform: item.platform, num: item.num,
      disabled: item.disabled || [],
      revenueExplicit: !!item.revenueExplicit,
    }));
}

/**
 * 「这个通道最近推送」
 *
 * 🔴 <b>按目标描述整串相等来筛，不是按号码找子串。</b>推送记录里只有一句给人看的目标描述
 * （「群 12345」），号本身没有单独一栏；按子串找的话，群 123 的记录会算进群 12345 里。
 * 描述与这里拼的那一串出自同一个枚举（推送目标类型的 str），因此整串相等是成立的——
 * 但它确实是<b>照着另一处的拼法拼的</b>：那边改了拼法，这里会安静地筛出空表。
 * 记录里带上号与类型才是根治，那要改推送发送那一侧。
 * @param records /api/push-history 的 records
 * @param session 会话，取它的类型说法与号
 * @param limit 最多几条
 * @return 这个通道的推送记录
 */
export function recentPushes(records, session, limit) {
  if (!session || !session.type) return [];
  const target = session.type + ' ' + session.num;
  return (records || [])
    .filter(item => item.platform === session.platform && item.target === target)
    .slice(0, limit);
}

/**
 * 本会话的命令按组分好
 *
 * 分组取各命令自报的 category，顺序取它首次出现的次序——与群里「菜单」的分法、
 * 分组先后完全一致。界面自己排一套的话，控制台上的组与群里看到的对不上。
 * @param commands /api/state 的 commands
 * @param session 会话，取 disabled 与 menuHidden
 * @param totalDataAvailable 这台机器开没开累计数据
 * @return 每组一项
 */
export function commandGroups(commands, session, totalDataAvailable) {
  const groups = [];
  const index = new Map();
  const off = new Set(((session || {}).disabled) || []);
  const hidden = new Set(menuHiddenOf(session));
  const notes = ((session || {}).menuNotes) || {};

  for (const command of commands || []) {
    const category = command.category || '其它';
    if (!index.has(category)) {
      index.set(category, {category, commands: []});
      groups.push(index.get(category));
    }

    index.get(category).commands.push({
      name: command.name,
      description: command.description || '',
      requiresAdmin: !!command.requiresAdmin,
      disableable: command.disableable !== false,
      off: off.has(command.name),
      listed: !hidden.has(command.name),
      hiddenReason: hiddenReason(command, hidden, totalDataAvailable),
      note: notes[command.name] || '',
    });
  }

  // 组开关：这一组全都开着才算开。半开的组显示成关着更糟——点一下「全开」，
  // 使用者以为自己什么都没改，而那一下真的把没关的几条又写了一遍
  for (const group of groups) {
    const switchable = group.commands.filter(item => item.disableable);
    group.total = group.commands.length;
    group.on = switchable.length > 0 && switchable.every(item => !item.off);
    group.switchable = switchable.map(item => item.name);
  }
  return groups;
}

/**
 * 后端有没有答得上「这个会话的菜单里不列哪几条」
 *
 * 🔴 答不上（推送配置里已经没有、只在状态文件里留着的会话没有会话类型，构不出上下文）时，
 * 那一栏是缺的，而缺的那一栏与一个空表在 `|| []` 底下长得一模一样——读成「一条都不藏」，
 * 屏幕上就是一份看起来完全正常的菜单。因此这里把两者分开。
 * @param session 会话
 * @return {boolean} 答得上则为 true
 */
export function menuKnown(session) {
  return Array.isArray((session || {}).menuHidden);
}

/**
 * 这个会话的菜单里不列哪几条，答不上时为空表
 * @param session 会话
 * @return {string[]} 命令名
 */
function menuHiddenOf(session) {
  return menuKnown(session) ? session.menuHidden : [];
}

/**
 * 这条命令为什么不列进菜单
 *
 * 「列不列」由后端算（见 RuntimeStateController#menuOf），这里只把它翻成一句人话。
 * 整台机器都用不上的（available 为假）与只在这个会话里用不上的，要人做的事不一样：
 * 前者去配一个累计存储，后者是这个群自己的配置使然。
 * @param command 命令
 * @param hidden 本会话不列进菜单的那些
 * @param totalDataAvailable 这台机器开没开累计数据
 * @return {string} 理由，列着时为空串
 */
function hiddenReason(command, hidden, totalDataAvailable) {
  if (!hidden.has(command.name)) return '';
  if (command.available === false) {
    return totalDataAvailable === false ? '累计数据没开，菜单里不显示' : '这台机器暂时用不上';
  }
  return '在这个会话里不起作用，菜单里不显示';
}

/**
 * 「本群设置」第 2 行：命令那一行的摘要
 *
 * 数目按<b>菜单里真列出来的条数</b>算，不按命令总数算：没开累计存储的机器上，
 * 带「总」字的两条在群里本来就不出现，摘要仍写 14 的话，屏幕上的数与群里数出来的对不上。
 * 「列不列」这件事本身由后端答（menuHidden），界面不按命令名去认——
 * 认名字的话，下一条「总」字命令进来就会被漏掉。
 * @param commands /api/state 的 commands
 * @param session 会话
 * @param totalDataAvailable 这台机器开没开累计数据
 * @return 摘要
 */
export function commandSummary(commands, session, totalDataAvailable) {
  const list = commands || [];
  const known = new Set(list.map(item => item.name));
  const hidden = new Set(menuHiddenOf(session));
  const disabled = ((session || {}).disabled) || [];

  const listed = list.filter(item => !hidden.has(item.name));
  // 被群管理员关掉的：只算真有这条命令的。状态文件里那些改过名、已删除的记录另算一摊，
  // 混进来的话「一键恢复」会顺手把它们也清掉，而按钮上写的不是这件事
  const offNames = disabled.filter(name => known.has(name));
  const strayNames = disabled.filter(name => !known.has(name));
  const hiddenByMachine = list.filter(item => item.available === false && hidden.has(item.name));
  const hiddenBySession = list.filter(item => item.available !== false && hidden.has(item.name));

  const parts = [];
  parts.push(offNames.length
    ? (listed.length - offNames.length) + ' 条可用 · ' + offNames.length + ' 条被群管理员关了（'
      + offNames.join('、') + '）'
    : listed.length + ' 条全部可用');
  if (hiddenByMachine.length) {
    parts.push(hiddenByMachine.length + ' 条'
      + (totalDataAvailable === false ? '因累计数据没开' : '因这台机器用不上') + '隐藏');
  }
  if (hiddenBySession.length) {
    parts.push(hiddenBySession.length + ' 条在这个会话里不列');
  }

  if (!menuKnown(session)) {
    parts.push('这个会话不在推送配置里，菜单口径取不到');
  }

  return {
    listed: listed.length, total: list.length,
    menuKnown: menuKnown(session),
    offNames, strayNames,
    // 一键恢复只管上面点名的那几条：残留另有自己的一块与自己的按钮，
    // 一个按钮办两件事的话，按下去清掉了什么就说不清了
    restorable: offNames.slice(),
    hiddenByMachine: hiddenByMachine.map(item => item.name),
    hiddenBySession: hiddenBySession.map(item => item.name),
    text: parts.join(' · '),
  };
}

/**
 * 「本群设置」第 1 行：金额可见
 * @param session 会话
 * @return 摘要
 */
export function revenueSummary(session) {
  const visible = !!(session || {}).revenueVisible;
  const explicit = !!(session || {}).revenueExplicit;
  return {
    visible, explicit,
    text: (visible ? '显示金额' : '隐藏金额') + (explicit ? '' : '（默认：群聊隐藏、私聊显示）'),
  };
}

/**
 * 预览请求体：版式参数加上当前通道
 *
 * 通道字段与改金额可见那一支同一套定位（platform／type／num）。
 * 没通道时只带版式，服务端按金额可见画。
 * type 为 0（私聊）也必须带上：写成 if (target.type) 会把私聊当成没通道。
 * @param params 版式参数
 * @param target 当前通道，可为空
 * @return 请求体
 */
export function previewRequestBody(params, target) {
  const body = Object.assign({}, params || {});
  if (!target || target.platform == null || target.platform === ''
      || target.num == null || target.num === '') {
    return body;
  }
  body.platform = target.platform;
  if (target.type != null && target.type !== '') {
    body.type = Number(target.type);
  }
  body.num = Number(target.num);
  return body;
}

/**
 * 预览图旁那一行：按本群金额可见画：隐藏／显示
 *
 * 有会话时跟本群设置走；没有会话时按类型默认（群聊隐藏、私聊显示）。
 * @param session 当前会话，可为空
 * @param target 当前通道，可为空
 * @return 那一行字
 */
export function previewRevenueCaption(session, target) {
  let visible;
  if (session && Object.prototype.hasOwnProperty.call(session, 'revenueVisible')) {
    visible = !!session.revenueVisible;
  } else {
    visible = Number((target || {}).type) !== 1;
  }
  return '按本群金额可见画：' + (visible ? '显示' : '隐藏');
}

/**
 * 「本群设置」第 3 行：提醒订阅
 *
 * 按订阅类型分行报人数。改「谁会被 @」不在这一行——那一档配在通道的模板里，
 * 是这一条通知的事，不是这个群的事。
 * @param subscriptions /api/state 的 subscriptions
 * @param session 会话
 * @return 摘要
 */
export function subscriptionSummary(subscriptions, session) {
  const rows = (subscriptions || [])
    .filter(item => item.platform === (session || {}).platform && item.num === (session || {}).num)
    .map(item => ({
      streamerUid: item.streamerUid,
      streamerName: item.streamerName || String(item.streamerUid),
      type: item.type, typeName: item.typeName || item.type,
      users: item.users || [],
    }));

  const byType = new Map();
  for (const row of rows) {
    byType.set(row.typeName, (byType.get(row.typeName) || 0) + row.users.length);
  }

  const text = byType.size
    ? [...byType].map(([name, n]) => name + ' ' + n + ' 人').join(' · ')
    : '还没有人订阅';
  return {rows, text};
}

/**
 * 「本群设置」第 4 行：@全体成员 的状态行
 *
 * 这一行不是设置，是三个事实：今天这个群用掉几次、这个账号今天一共用掉几次、
 * 机器人在这个群里是不是管理员。前两个来自额度接口，第三个来自机器人自己给的群名单。
 * 私聊没有 @全体成员 这回事，此时只说这一句——报一行 0/20 会让人以为那里也有一份额度。
 * @param quota /api/at-all/quota 回包
 * @param session 会话
 * @param target 推送目标
 * @param directory 名单，见 buildDirectory
 * @return 状态行
 */
export function atAllStatus(quota, session, target, directory) {
  if (Number(target.type) !== 1) {
    return {applicable: false, text: '私聊没有 @全体成员。', admin: null};
  }

  const platform = (session || {}).platform || target.platform;
  const bot = ((quota || {}).bots || []).find(item => item.platform === platform) || null;
  const seat = ((quota || {}).sessions || [])
    .find(item => item.platform === platform && item.num === target.num) || null;
  const known = (directory || {})[directoryKey(target.platform, target.type, target.num)] || null;

  const parts = [];
  // limited 为假＝没有上限，此时不画分母：画一个 0 的分母，看的人会以为一次都不许用
  parts.push(seat ? '今日本群已用 ' + seat.used + (seat.limited ? '/' + seat.limit : '（不限）')
    : '今日本群用量取不到');
  parts.push(bot ? '账号 ' + bot.used + (bot.limited ? '/' + bot.limit : '（不限）')
    : '账号用量取不到');
  parts.push(known ? '机器人角色：' + (known.admin ? '管理员' : '普通成员') : '机器人角色：名单取不到');

  return {
    applicable: true,
    admin: known ? known.admin : null,
    used: seat ? seat.used : null,
    text: parts.join(' · '),
    // @全体成员 不绕过 QQ 权限：不是管理员时它会被整段摘掉，正文照发。
    // 这句话得在这里说，否则「配了却没 @ 到人」这件事没有任何现象
    warning: known && !known.admin
      ? '机器人不是本群管理员，@全体成员 会被自动摘掉，正文照发。' : '',
  };
}
