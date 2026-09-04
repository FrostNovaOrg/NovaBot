/**
 * 连接页视图模型的十二档对照
 *
 * 连接页要在「登录态 × 机器人态」这九种组合下都说得对，而这九种在真机上凑齐一次的代价极高：
 * 掉登录要等凭据过期或手动退出，机器人掉线要去把 OneBot 实现停掉，未配置那一档更是
 * 只在刚装好的机器上出现一次。三张卡的显隐、灯色与那句关键话因此被切成纯函数
 * （config-ui/links-model.js，不碰 DOM 也不发请求），本文件喂它十二份回包逐档对答案。
 *
 * 九档之外另有三档，各自补一条上面九档量不到的事：
 *   没装平台插件   —— 平台卡该整张不出现。九档里它一直在，「显隐」那一列因此是恒真的
 *   还没签过口令   —— 外部面板卡的空态
 *   口令全撤       —— 有过记录但一把有效的都不剩，与「还没签过」不是同一句话
 *
 * 本文件里没有任何一个直播平台的名字：平台卡的标题取自插件登记时报的显示名，
 * 夹具因此用一个虚构的名字——核心认得的只是「有这么一张卡」。
 *
 * 用 node 直接跑：
 *   node tools/links-model-check.mjs
 * 退码 0 即十二档全对；任一档对不上打印差异并以 1 退出。
 */

import {cardAnchor, linksModel, resolveTarget, targetOptions}
  from '../starbot-core/src/main/resources/config-ui/links-model.js';

/** 探针的原样形态，与 /api/status 里 health 那一项逐字段同形 */
function probe(name, scope, level, summary, advice, loginState) {
  return {name, scope, level, summary, advice: advice || '', loginState: !!loginState};
}

/** 登录态探针，三档各一份。advice 那句正是掉登录时卡上要显示的话 */
const LOGIN_PROBE = {
  'in': probe('平台登录', 'PLATFORM', 'OK', '正常（uid 12345678）', '', true),
  'anon': probe('平台登录', 'PLATFORM', 'DEGRADED', '匿名模式（未登录）',
    '匿名下取不到完整数据，配好账号后重启即可', true),
  'out': probe('平台登录', 'PLATFORM', 'DOWN', '未登录',
    '动态通知已停，直播推送不受影响。请扫描本卡上的二维码完成登录', true),
};

/** 机器人探针，三档各一份 */
const BOT_PROBE = {
  'ok': probe('机器人连接', 'BOT', 'OK', '默认：HTTP 正常 / 账号 在线 / WS 正常'),
  'down': probe('机器人连接', 'BOT', 'DOWN', '默认 的 QQ 账号已掉线，接口仍可调用但消息不会送达',
    '请到 OneBot 实现的界面重新扫码登录'),
  'none': probe('机器人连接', 'BOT', 'DOWN', '未配置任何机器人',
    '请在「连接」页填写地址与端口'),
};

function status(loginState, botState, patch) {
  return Object.assign({
    success: true,
    health: [LOGIN_PROBE[loginState], BOT_PROBE[botState],
      probe('数据存储', 'SYSTEM', 'OK', '本场数据 + 累计数据')],
    // 未配置那一档的判据是「一个推送平台都没注册出来」，与首页同一条
    senders: botState === 'none' ? [] : ['默认'],
    queue: {pending: botState === 'down' ? 7 : 0, dropped: 0},
    users: [], runtime: {}, version: '5.1.0',
  }, patch || {});
}

function login(loginState) {
  const base = {platform: 'demo', displayName: '某直播平台'};
  if (loginState === 'in') {
    return {success: true, accounts: [Object.assign({loggedIn: true, accountId: '12345678',
      credentialNote: '自动续期已开，凭据有效'}, base)]};
  }
  if (loginState === 'anon') {
    return {success: true, accounts: [Object.assign({loggedIn: false, accountId: null,
      disabledReason: '匿名模式：取不到需要登录才能看的数据'}, base)]};
  }
  return {success: true, accounts: [Object.assign({loggedIn: false, accountId: null,
    qrCode: 'iVBORw0KGgo='}, base)]};
}

/** 插件登记的连接卡，一张 */
const ONE_CARD = [{id: 'demo', displayName: '某直播平台'}];

const TOKENS_ONE = [{fingerprint: 'ab12cd34', label: '客厅那台', issuedAt: 1757000000000, active: true}];

const TOKENS_REVOKED = [{fingerprint: 'ab12cd34', label: '客厅那台', issuedAt: 1757000000000,
  revokedAt: 1757009000000, active: false}];

// ── 十二档 ─────────────────────────────────────────────────────────────
// 每档写明：登录态、机器人态、装没装平台插件、签过几把口令，以及三张卡该长成什么样。
// 期望里的 say 是「这一档必须说出口的那句话」，按子串比——灯色对了而话没说，
// 使用者仍然不知道该做什么，而那正是这一页存在的理由。
const THREE = ['platform', 'napcat', 'panel'];

const CASES = [
  {name: '已登录 × 机器人正常', login: 'in', bot: 'ok',
    expect: {cards: THREE, level: ['ok', 'ok', 'ok'], say: ['已登录', 'HTTP 正常', '1 把有效']}},
  {name: '已登录 × 机器人掉线', login: 'in', bot: 'down',
    expect: {cards: THREE, level: ['ok', 'err', 'ok'], say: ['已登录', '队列积压 7 条', '']}},
  {name: '已登录 × 机器人未配置', login: 'in', bot: 'none',
    expect: {cards: THREE, level: ['ok', 'off', 'ok'], say: ['已登录', '还没连上机器人', '']}},

  {name: '匿名 × 机器人正常', login: 'anon', bot: 'ok',
    expect: {cards: THREE, level: ['off', 'ok', 'ok'], say: ['匿名', 'HTTP 正常', '']}},
  {name: '匿名 × 机器人掉线', login: 'anon', bot: 'down',
    expect: {cards: THREE, level: ['off', 'err', 'ok'], say: ['匿名', '队列积压 7 条', '']}},
  {name: '匿名 × 机器人未配置', login: 'anon', bot: 'none',
    expect: {cards: THREE, level: ['off', 'off', 'ok'], say: ['匿名', '还没连上机器人', '']}},

  {name: '未登录 × 机器人正常', login: 'out', bot: 'ok',
    expect: {cards: THREE, level: ['warn', 'ok', 'ok'], say: ['动态通知已停', 'HTTP 正常', '']}},
  {name: '未登录 × 机器人掉线', login: 'out', bot: 'down',
    expect: {cards: THREE, level: ['warn', 'err', 'ok'], say: ['动态通知已停', '队列积压 7 条', '']}},
  {name: '未登录 × 机器人未配置', login: 'out', bot: 'none',
    expect: {cards: THREE, level: ['warn', 'off', 'ok'], say: ['动态通知已停', '还没连上机器人', '']}},

  // 平台卡该整张不出现。上面九档里它一直在，少了这一档「显隐」那一列就是恒真的
  {name: '没装平台插件', login: 'in', bot: 'ok', cards: [],
    expect: {cards: ['napcat', 'panel'], level: ['ok', 'ok'], say: ['HTTP 正常', '1 把有效'], noAnchor: true}},

  {name: '还没签过口令', login: 'in', bot: 'ok', tokens: [],
    expect: {cards: THREE, level: ['ok', 'ok', 'off'], say: ['已登录', 'HTTP 正常', '还没签发过']}},
  {name: '口令全撤', login: 'in', bot: 'ok', tokens: TOKENS_REVOKED,
    expect: {cards: THREE, level: ['ok', 'ok', 'off'], say: ['已登录', 'HTTP 正常', '1 把已吊销']}},
];

// ── 跑 ─────────────────────────────────────────────────────────────────
const bad = [];
const rows = [];

for (const item of CASES) {
  const cards = item.cards === undefined ? ONE_CARD : item.cards;
  const tokens = item.tokens === undefined ? TOKENS_ONE : item.tokens;
  const model = linksModel(status(item.login, item.bot), login(item.login), cards, tokens);

  const shown = model.cards.filter(c => c.show);
  const kinds = shown.map(c => c.kind);
  const level = shown.map(c => c.level);
  // 「说了没说」按整张卡的可见文字比：话落在 caption 还是 note 上是渲染的事，
  // 判据只问使用者读不读得到那句
  const text = shown.map(c => [c.caption, c.note, c.advice].join(' '));

  const want = item.expect;
  const fail = [];
  if (kinds.join(',') !== want.cards.join(',')) fail.push(`显隐 期望 [${want.cards}] 实得 [${kinds}]`);
  if (level.join(',') !== want.level.join(',')) fail.push(`灯色 期望 [${want.level}] 实得 [${level}]`);
  want.say.forEach((s, i) => {
    if (s && !(text[i] || '').includes(s)) fail.push(`第 ${i + 1} 张卡没说「${s}」，实得「${text[i]}」`);
  });

  // 首页链路的站点要落到本页对应的卡上。没装平台插件时那一站没有落点，
  // 此时必须回空串而不是回一个页面上不存在的锚——那种锚点跳过去什么也不会发生
  const platformAnchor = cardAnchor(model, 'platform');
  if (want.noAnchor && platformAnchor !== '') fail.push(`平台站不该有落点，实得「${platformAnchor}」`);
  if (!want.noAnchor && !platformAnchor) fail.push('平台站少了落点');
  if (cardAnchor(model, 'bot') !== 'napcat') fail.push('机器人站的落点不是 napcat 卡');

  rows.push([item.name, kinds.join('/'), level.join('/'), fail.length ? '红' : '绿'].join('\t'));
  if (fail.length) bad.push(item.name + '：' + fail.join('；'));
}

// ── 测试消息的目标只能来自名单 ────────────────────────────────────────
// 「手填拒」不是靠界面上没有那个输入框来保证的：把它做成一个纯函数，
// 界面换几次版这条判据都还在。没有这一格的话，删掉输入框那一刻它就成立，
// 而下一个人为了「方便」把框加回来时，没有任何东西会红
const OPTIONS = targetOptions(
  {items: [{sender: '默认', num: 12345678, name: '开播通知群', memberCount: 42, admin: true, configured: true}]},
  {items: [{sender: '默认', num: 87654321, nickname: '小号', remark: '', configured: false}]});

const pickBad = [];
if (OPTIONS.length !== 2) pickBad.push('名单该有 2 条，实得 ' + OPTIONS.length);

const good = resolveTarget(OPTIONS, OPTIONS[0].key);
if (!good.ok) pickBad.push('名单里的目标该放行，实得拒：' + good.reason);
if (good.ok && (good.platform !== '默认' || good.type !== 1 || good.num !== 12345678)) {
  pickBad.push('放行的那条拆错了：' + JSON.stringify(good));
}

// 反面四例：手打一个名单外的群号必须被拒。这一条正是「不许手填号码」的实质
for (const forged of ['默认|1|999', '|1|12345678', '默认|1|', '']) {
  const verdict = resolveTarget(OPTIONS, forged);
  if (verdict.ok) pickBad.push('名单外的「' + forged + '」被放行了');
}

console.log(['档', '出了哪几张卡', '灯色', '判定'].join('\t'));
rows.forEach(r => console.log(r));
console.log('目标名单\t' + OPTIONS.length + ' 条\t手填 4 例\t' + (pickBad.length ? '红' : '绿'));

if (bad.length || pickBad.length) {
  console.error('\n对不上 ' + (bad.length + pickBad.length) + ' 处：');
  bad.forEach(b => console.error('  ' + b));
  pickBad.forEach(b => console.error('  目标名单：' + b));
  process.exit(1);
}
console.log('\n十二档全对，目标名单四例手填全拒');
