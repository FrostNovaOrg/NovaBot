/**
 * 版式项删掉之后：留着死键的老通道不许因此显示「自定义」
 *
 * 处理器的版式项清掉一个（如盲盒盈亏榜并入盲盒榜）之后，两处旧数据里还会躺着那个键：
 * 通道的 datasource.json 参数、template-defaults.json 的覆盖。前端的「默认／自定义」判定
 * 若把<b>处理器已不认识</b>的键也算作差异，老通道升级后立刻显示「自定义」——
 * 而运行那一侧早已静默忽略这个键，界面说的与机器人做的就不是同一件事。
 *
 * 因此本尺喂一份带死键的通道参数，逐格问三个判定：
 * 模板态（templateState）、默认与否（isDefault）、版式态（layoutState——这一支本来就只看
 * 现行版式项，是阴性与阳性的分界）。另带一格真改过的键作阳性对照，防「全放行」式认宽。
 *
 * 类名与键名用短串，与 push-legacy-handler-fixture 同一条理由：这把尺量的是判定法，
 * 不是哪个处理器搬去了哪里。
 *
 * 由 PushRemovedOptionKeyTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import * as model from '../../../main/resources/config-ui-pages/push-model.js';
import * as templateModel from '../../../main/resources/config-ui-pages/template-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 一格自己抛了异常也要记成红：抛出去会把后面几格一并带走，读数只剩前半截 */
function ask(what, expected, fn) {
  let got;
  try {
    got = fn();
  } catch (error) {
    got = 'error:' + error.message;
  }
  eq(got, expected, what);
}

const MAIN = 'x.Report';
const PLATFORM = 'p';

/** 现行版式项：已不含被删掉的 removed_layout_key */
const LAYOUT_OPTIONS = [
  {key: 'cover', label: '直播间封面', type: 'BOOLEAN', defaultValue: true},
  {key: 'gift_ranking', label: '流水排行', type: 'INTEGER', defaultValue: 5},
];

/** /api/handlers 的一项：默认参数同样不含死键——服务端那一侧已把它滤掉 */
const HANDLERS = [{
  className: MAIN,
  displayName: '下播报告',
  description: '下播时推送',
  platform: PLATFORM,
  aliases: [],
  placeholders: ['{report}'],
  options: LAYOUT_OPTIONS,
  defaultParams: {message: '{report}', at_all: false, cover: true, gift_ranking: 5},
}];

/** 老使用者的通道参数：除了那个被删掉的键，与默认一字不差 */
const STALE_ONLY = {removed_layout_key: 5};

/** 真改过的通道参数：死键之外还把礼物榜条数改了 */
const REALLY_CHANGED = {removed_layout_key: 5, gift_ranking: 9};

function targetWith(params) {
  return {platform: 'qq', type: 1, num: 12345, enabled: true,
    messages: [{handler: MAIN, params}]};
}

function userWith(params) {
  return {platform: PLATFORM, uid: 1, _uname: '甲', enabled: true, targets: [targetWith(params)]};
}

// ① 模板态：死键不算差异，这个通道仍是「默认模板」
ask('① 只留死键的通道，模板态是默认', {custom: false, changed: []}, () => {
  const state = model.templateState(targetWith(STALE_ONLY), HANDLERS);
  return {custom: state.custom, changed: state.changed};
});

// ② 默认与否：与 ① 同一把尺在另一页的说法
ask('② 只留死键的通道，isDefault 为真', true, () =>
  templateModel.isDefault(STALE_ONLY, HANDLERS[0]));

// ③ 阳性对照：真改过的键照旧算差异——「放行」若放成了全放行，这一格红
ask('③ 死键之外真改过的通道仍是自定义', false, () =>
  templateModel.isDefault(REALLY_CHANGED, HANDLERS[0]));

// ④ 版式态：本来就只看现行版式项，死键在不在都不影响；真改过的那格要认出来
ask('④ 版式态对死键不亮自定义', {present: true, custom: false, className: MAIN}, () => {
  const state = model.layoutState(targetWith(STALE_ONLY), HANDLERS);
  return {present: state.present, custom: state.custom, className: state.className};
});

// ⑤ 版式态的真改过照旧认得
ask('⑤ 版式态对真改过的键照旧亮自定义', true, () =>
  model.layoutState(targetWith(REALLY_CHANGED), HANDLERS).custom);

// ⑥ 只读那几支跑完，配置逐字不变（与旧全名夹具同一条：读不许顺手改人家的文件）
ask('⑥ 只读那几支跑完，配置逐字不变', true, () => {
  const list = [userWith(STALE_ONLY)];
  const before = JSON.stringify(list);
  model.pushTree(list, [], HANDLERS, {});
  model.noticeSwitches(list[0], list[0].targets[0], HANDLERS);
  model.templateState(list[0].targets[0], HANDLERS);
  model.layoutState(list[0].targets[0], HANDLERS);
  return JSON.stringify(list) === before;
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
