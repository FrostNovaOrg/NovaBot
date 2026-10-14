/**
 * 初始设置各步的判定：走到哪、放不放行、进度条上画什么、初始值那几行写什么
 *
 * 一个 DOM 也不碰、一个请求也不发——这一页上真正会把人卡住的东西全在这里：
 * 哪一步不许跳过、少了什么不许往下走、「重新跑一遍」之后从第几步接着走。
 * 混在渲染代码里的话，这几件事就只能靠人打开一台没配过的机器手点，
 * 而放行条件写反了<b>不会有任何报错</b>，只是那一步变成点一下就过。
 *
 * 各步各自成不成立由 {@link setupSteps} 现算（在 home-model.js 里），本文件不另判一遍：
 * 首页那条待办与这一页的进度条说的是同一件事，两份实现分叉的表现是
 * 首页写着「完成了 3 步」而这一页停在第 2 步，且两处的代码看起来都对。
 *
 * 本文件里没有任何一个直播平台的名字：第三步按通名说「直播平台」，
 * 平台叫什么由 /api/login 里那个显示名带进来。写死一个，没装它的人会看见一步他走不到的流程。
 */

import {SETUP_STEPS, setupSteps} from './home-model.js';
import * as home from './home-model.js';
import {mailAlertConfigured} from './alert-model.js';

export {SETUP_STEPS};

/** 同义 core.js 的 term：有键用词，无键用中性兜底。模型不读全局。 */
function word(terms, key, fallback) {
  return (terms && terms[key]) || fallback;
}

/** 同义 core.js 的 phrase：词在则套进 withTerm，词缺则整句退成中性 without。 */
function say(terms, key, withTerm, without) {
  const v = terms && terms[key];
  return v ? withTerm(v) : without;
}

export function withPluginSteps(pages, terms) {
  return home.withPluginSteps(pages, terms);
}

/**
 * 各步各自成立了没有
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param sent 第 5 步的事实，来自 /api/setup/state；不知道时传 null
 * @param pluginDone 插件步事实，{@code {key: boolean}}；缺这一位或该键不为 true 都算假
 * @param pages /api/pages 的 pages 清单；缺＝无插件
 * @return {boolean[]} 与步骤表等长的布尔
 */
export function stepFacts(status, login, sent, pluginDone, pages) {
  return setupSteps(status || {}, login || {}, sent, pages, pluginDone);
}

/**
 * 完成了几步
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param sent 第 5 步的事实；不知道时传 null
 * @return {number} 0 到 5
 */
export function setupProgress(status, login, sent, pluginDone, pages) {
  return stepFacts(status, login, sent, pluginDone, pages).filter(Boolean).length;
}

/**
 * 步骤表上每一步都齐了吗
 * @param facts {@link stepFacts} 的结果
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {boolean} 齐了为 true
 */
export function allDone(facts, steps) {
  const table = steps || SETUP_STEPS;
  return (facts || []).length === table.length && facts.every(Boolean);
}

/**
 * 这一步放不放行
 *
 * 拦下来时连理由一起给：只回一个 false 的话，屏幕上的表现是「下一步这个按钮点不动」，
 * 而使用者看不出还差什么。几条理由各写各的——抄成同一句的话，
 * 第 2 步与末步要人做的事完全不同，却写着同一行字。
 *
 * 认不出来的步号一律不放行：这张表将来多一步少一步的时候，宁可卡住也不许默默放过去。
 * 按步骤表上的 key 判，不按写死的下标——插件步插进来之后，试发不再是最后一格。
 *
 * 🔴 <b>插件步先认出来再进 switch</b>：它的 key 由插件申报，可以是任何一个词，
 * 包括恰好与某个曾经的内置键同名（例如主播那一步搬进插件之后仍叫 streamer）。
 * 先进 switch 的话，那一步会被拿一份早已不存在的核心草稿去判，而屏幕上的表现是
 * 「下一步永远点不动」——插件步该不该放行由那一步自己判，见 setup.js 的 pluginNext。
 * @param index 第几步，0 起
 * @param draft 这一页此刻手里的东西
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {{ok: boolean, reason: string}} 放行与否，以及拦住的理由
 */
export function canAdvance(index, draft, steps) {
  const it = draft || {};
  const step = (steps || SETUP_STEPS)[index];
  if (!step) return stop('无法识别此步骤，不能继续');
  if (step.plugin) return pass();

  switch (step.key) {
    case 'lock':
      return it.locked ? pass()
        : stop('请先设置控制台密码。此步骤不可跳过：未设置密码时，任何能访问本机的人都可以进入控制台');
    case 'bot':
      return it.botOk ? pass()
        : stop('请先点击「测试连接」，测试通过后才能继续。此步骤不可跳过：未连接机器人时，任何消息都无法发送');
    case 'account':
      return it.accountsReady || it.anonymousConfirmed ? pass()
        : stop('请扫码登录，或点击「暂不登录，使用免登录模式」并确认相应影响');
    case 'test':
      return it.sent ? pass() : stop('请先发送一条测试消息，对方能收到才说明整条推送链路畅通');
    default:
      return stop('无法识别此步骤，不能继续');
  }
}

const pass = () => ({ok: true, reason: ''});

const stop = reason => ({ok: false, reason});

/**
 * 进度条上每一步画成什么样
 *
 * 记号有先后：<b>事实成立就是成立，跳过的记号盖不住它</b>——使用者跳过了第 4 步，
 * 回头又把主播加上了，那一格该变成完成而不是一直挂着「跳过了」。
 * 唯一排在事实前面的是免登录：那一步确实「定了」，但它定的是一个有后果的决定，
 * 打勾会让人以为登录成功了。
 * 勾底下那行小字写「已配置」（试发那一步写「有试发记录」）而不写「完成」：
 * 勾读的是盘上已有的配置，不是这一趟刚验过，从设置引导重走时尤其容易被读成后者。
 * 「有试发记录」只在 sent 确为 true 时写：回包里不知道试没试发过时，末步那个勾是照前几步推断的
 * （见 home-model.js 的 setupSteps），那时写「有试发记录」等于断言一份没人记下过的记录。
 * @param facts {@link stepFacts} 的结果
 * @param skips 每一步被跳过的方式，'' / 'skip' / 'anon'
 * @param current 正在做第几步
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @param sent 服务端记着的试发事实；不知道时传 null
 * @return {{state: string, note: string}[]} 每步的记号与那行小字
 */
export function railMarks(facts, skips, current, steps, sent) {
  return (steps || SETUP_STEPS).map((step, i) => {
    if ((skips || [])[i] === 'anon') return {state: 'warn', note: '免登录模式'};
    if ((facts || [])[i]) return {state: 'done', note: step.key === 'test' && sent === true ? '已有测试发送记录' : '已配置'};
    if ((skips || [])[i]) return {state: 'skip', note: '已跳过'};
    if (i === current) return {state: 'current', note: '进行中'};
    return {state: 'idle', note: ''};
  });
}

/**
 * 从第几步接着走
 *
 * 🔴 「重新跑一遍初始设置」按下之后一律回到第一步。不回的话，那个按钮点完停在原地，
 * 与「点了没反应」在屏幕上长得一样。
 * @param facts {@link stepFacts} 的结果
 * @param rerun 有没有人要求重来一遍
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {number} 落在第几步
 */
export function startAt(facts, rerun, steps) {
  if (rerun) return 0;
  const table = steps || SETUP_STEPS;
  const at = (facts || []).findIndex(done => !done);
  return at < 0 ? table.length - 1 : at;
}

/**
 * 「已替你定好的初始值（都可改）」那几行
 *
 * 🔴 每一行都从现值算，不写死一张表：写死的话，一台刚刚被使用者关掉推送的机器上，
 * 这一行照样写着「开」——而这一段的全部意义正是让他<b>看见</b>这台机器现在是什么样。
 *
 * 表里只有核心自己的配置键。「备用直播推送」那一行落在直播平台插件的键上，
 * 核心界面里写不得它的键名（格1 契约），因此不在这里；它归那个插件自己的连接卡。
 * @param configValues /api/values 的 values，键 → 值。<b>参数名不叫 values</b>：
 *        那是 store 上一份共享状态的名字，在界面文件里裸着出现即为 ReferenceError，
 *        因此有一条判据在盯着它——它当场逮住了这一处
 * @param commandCount 运行期认得的命令条数，数不到时传 null
 * @param terms 插件申报的词表
 * @param status /api/status 回包；机器人告警是否已配读 alerts 里除 webhook／mail 外任一插件通道
 * @return {{label: string, text: string, href: string, key: string}[]} 每行
 */
export function initialRows(configValues, commandCount, terms, status) {
  const at = configValues || {};
  const read = key => String(at[key] ?? '').trim();
  const alerts = (status || {}).alerts || {};

  const quietStart = read('novabot.core.push.quiet-start');
  const quietEnd = read('novabot.core.push.quiet-end');
  const retention = Number(read('novabot.core.timeline.retention-days'));
  const pluginAlert = Object.keys(alerts).some(key => key !== 'webhook' && key !== 'mail' && alerts[key]);
  const alerted = !!read('novabot.core.alert.webhook-url') || pluginAlert
    || mailAlertConfigured(read('novabot.core.mail.default-to'), read('spring.mail.host'));

  return [
    row('推送总开关', read('novabot.core.push.enabled') === 'false' ? '关闭' : '开启',
      '#/settings', 'novabot.core.push.enabled'),
    row('静音时段', quietStart && quietEnd ? quietStart + ' – ' + quietEnd : '未设置',
      '#/settings', 'novabot.core.push.quiet-start'),
    // 金额可见没有一个总的配置键：它按会话来，默认群聊隐藏、私聊显示，改在推送页上
    row('金额可见', '群聊隐藏、私聊显示', '#/push', ''),
    row('命令', (commandCount ? commandCount + ' 条' : '') + '全部开启，仅响应 @ 机器人的消息',
      '#/settings', 'novabot.core.command.admins'),
    row('告警', alerted ? '已配置' : '未配置。建议配置一条 Webhook，'
      + say(terms, 'bot.platform', v => v + ' 掉线时只能靠它收到通知', '机器人掉线时只能靠它收到通知'),
      '#/settings', 'novabot.core.alert.webhook-url'),
    // 0 与负数是「不自动清理」，不是「留 0 天」——后者读起来像日志当天就没了
    row('日志保留', Number.isFinite(retention) && retention > 0 ? retention + ' 天' : '不自动清理',
      '#/settings', 'novabot.core.timeline.retention-days'),
  ];
}

const row = (label, text, href, key) => ({label, text, href, key});

/**
 * 走完之后那三行小结
 *
 * 三行分别答：机器人连上没有、直播平台这一头定成了什么、推给谁。
 * <b>跳过的那几步如实写出后果</b>——「还没加主播」这台 NovaBot 起来什么都不做，
 * 而一句「初始设置完成」会让人以为它已经在干活了。
 * <p>
 * 第三行只看这台机器上此刻有几位主播（{@code counts.streamers}），不问某一步做了没：
 * 加主播那一步已经是插件带来的，按步骤键去问等于把插件的键名写进核心，
 * 而两处答的本来就是同一份 {@code /api/status} 的 users。
 * @param facts {@link stepFacts} 的结果
 * @param skips 每一步被跳过的方式
 * @param counts {accounts, streamers, targets}
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {string[]} 三行
 */
export function summaryLines(facts, skips, counts, steps, terms) {
  const it = counts || {};
  const accounts = it.accounts || [];
  const table = steps || SETUP_STEPS;
  const fact = key => {
    const i = table.findIndex(step => step.key === key);
    return i >= 0 && !!(facts || [])[i];
  };

  const account = accounts.length
    ? accounts.map(one => (one.displayName || '直播平台') + ' '
      + (one.loggedIn ? '已登录' : (one.disabledReason ? '免登录模式（动态推送与自动关注不可用）' : '未登录')))
      .join(' · ')
    : '未安装任何直播平台插件，NovaBot 无法采集直播事件';

  const streamer = it.streamers
    ? it.streamers + ' 位主播 → ' + (it.targets || 0) + ' 个推送目标'
    : '尚未添加主播，NovaBot 暂时不会采集或推送任何内容';

  return [
    fact('bot')
      ? say(terms, 'bot.platform', v => v + ' 机器人 已连接', '机器人 已连接')
      : say(terms, 'bot.platform', v => v + ' 机器人 未连接，推送无法发出',
        '机器人 未连接，推送无法发出'),
    account,
    streamer,
  ];
}
