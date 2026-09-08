/**
 * 设置页四组纯函数的夹具
 *
 * 量的是 config-ui/settings-model.js 里的四件事：搜索、只看改过、默认值、危险项围栏。
 * 它们全不碰 DOM，因此可以在 node 上直接喂值跑——混在渲染代码里的话，
 * 「说明里含关键字但标题不含」这类分支就只能靠人打开页面手点，而手点点不出这种分支。
 *
 * 由 SettingsModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本。
 */

import {
  MASK, dangerOf, isDangerous, defaultText, defaultValue, isChanged, haystack,
  isVisible, effectOf,
} from '../../../main/resources/config-ui/settings-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 造一项字段表条目 */
function field(over) {
  return Object.assign({
    name: 'starbot.core.push.enabled', label: 'enabled', widget: 'boolean',
    description: '全局推送开关', defaultValue: true, effect: 'IMMEDIATE', sensitive: false,
    group: 'push', order: 0,
  }, over);
}

// ---------- 一、默认值 ----------
eq(defaultText(field({widget: 'boolean', defaultValue: true})), '开', '布尔默认值显示成「开」');
eq(defaultText(field({widget: 'boolean', defaultValue: false})), '关', '布尔默认值显示成「关」');
eq(defaultText(field({widget: 'string', defaultValue: null})), '未设', '没有默认值的说「未设」');
eq(defaultText(field({widget: 'list', defaultValue: null})), '空', '列表没有默认值的说「空」');
eq(defaultText(field({widget: 'list', defaultValue: ['127.0.0.1/32', '::1/128']})),
  '127.0.0.1/32、::1/128', '列表默认值用顿号连起来');
eq(defaultText(field({widget: 'integer', defaultValue: 3600})), '3600', '数字默认值原样');

eq(defaultValue(field({widget: 'boolean', defaultValue: true})), 'true', '布尔默认值填回控件是 true');
eq(defaultValue(field({widget: 'boolean', defaultValue: null})), 'false', '布尔没默认值按关处理');
eq(defaultValue(field({widget: 'list', defaultValue: ['a', 'b']})), 'a\nb', '列表默认值填回控件是每行一项');
eq(defaultValue(field({widget: 'string', defaultValue: undefined})), '', '没默认值填回控件是空串');

// ---------- 二、只看改过（值 ≠ 默认值） ----------
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'true'), false, '与默认值相同不算改过');
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'false'), true, '与默认值不同算改过');
eq(isChanged(field({widget: 'integer', defaultValue: 3600}), '3600'), false, '数字同值不算改过（跨类型比）');
eq(isChanged(field({widget: 'integer', defaultValue: 3600}), '600'), true, '数字不同算改过');
eq(isChanged(field({widget: 'string', defaultValue: null}), ''), false, '没默认值且空着不算改过');
eq(isChanged(field({widget: 'string', defaultValue: null}), 'x'), true, '没默认值而填了算改过');
eq(isChanged(field({widget: 'list', defaultValue: ['a', 'b']}), 'a\nb'), false, '列表同值不算改过');
// 机密项的真值不出后端，拿遮罩串跟默认值比是比不出东西来的，因此按「配过就算改过」
eq(isChanged(field({sensitive: true, widget: 'string', defaultValue: null}), MASK), true,
  '机密项配过就算改过');
eq(isChanged(field({sensitive: true, widget: 'string', defaultValue: null}), ''), false,
  '机密项没配不算改过');

// ---------- 三、搜索 ----------
const quiet = field({
  name: 'starbot.core.push.quiet-start', label: 'quiet-start', widget: 'string',
  description: '静音时段开始，期间的推送直接丢弃', defaultValue: null,
});
eq(haystack(quiet).includes('静音'), true, '说明进了搜索范围');
eq(haystack(quiet).includes('quiet-start'), true, '键名进了搜索范围');
eq(isVisible(quiet, '', '静音', false), true, '按说明里的词搜得到');
eq(isVisible(quiet, '', 'quiet', false), true, '按键名搜得到');
eq(isVisible(quiet, '', 'QUIET', false), true, '搜索不分大小写');
eq(isVisible(quiet, '', '  静音  ', false), true, '搜索词两头的空白不算数');
eq(isVisible(quiet, '', '词云', false), false, '搜不相干的词搜不到');
eq(isVisible(quiet, '', '', false), true, '不搜时全都显示');
// 两个条件是与的关系：搜索命中，且（没开只看改过，或它确实改过）
eq(isVisible(quiet, '', '静音', true), false, '只看改过时，没改过的即使搜中也不显示');
eq(isVisible(quiet, '23:00', '静音', true), true, '只看改过时，改过且搜中才显示');
eq(isVisible(quiet, '23:00', '词云', true), false, '只看改过时，改过但没搜中也不显示');

// ---------- 四、危险项围栏 ----------
// 危险的声明跟着配置项自己走（服务端随字段表下发），界面这边没有写死的清单——
// 写死的话，那张清单只能由核心来写，而其中有些项属于插件
const listen = field({
  name: 'server.address', label: 'address', widget: 'string', defaultValue: '127.0.0.1',
  danger: {value: '0.0.0.0', title: '监听地址改成 0.0.0.0？', consequence: '会把接口暴露到网络上。'},
});
const toggle = field({
  danger: {value: 'true', title: '开启外部程序触发？', consequence: '这是能执行任意程序的口子。'},
});
const plain = field();

// 「这一项危险」与「这一次的改动危险」是两个问题：行左那道边框不管当前值都要画
eq(isDangerous(listen), true, '带声明的是危险项');
eq(isDangerous(plain), false, '没带声明的不是危险项');
eq(isDangerous(undefined), false, '拿不到字段时不当危险项');

eq(dangerOf(listen, '127.0.0.1'), null, '改到不危险的那一档不问');
eq(dangerOf(listen, '0.0.0.0') !== null, true, '改到危险的那一档要先问一句');
eq(dangerOf(listen, ' 0.0.0.0 ') !== null, true, '两头带空白的危险值也要问');
eq(dangerOf(listen, '0.0.0.1'), null, '长得像但不等的值不问');
eq(dangerOf(listen, '0.0.0.0').title, '监听地址改成 0.0.0.0？', '问的是声明里那句标题');
eq(dangerOf(listen, '0.0.0.0').body, '会把接口暴露到网络上。', '说的是声明里那句后果');
eq(dangerOf(toggle, 'true') !== null, true, '开关开到 true 要先问一句');
eq(dangerOf(toggle, 'false'), null, '开关关掉不问');
eq(dangerOf(plain, 'true'), null, '没带声明的一律不问');
eq(dangerOf(plain, ''), null, '空值在没带声明时也不问');
// 没带声明的项拿空值去比，不能因为「空等于空」就弹一个空白确认框
eq(dangerOf(field({danger: null}), ''), null, '声明为 null 时不问');

// ---------- 五、生效时机 ----------
eq(effectOf('IMMEDIATE'), {immediate: true, text: '立即生效'}, '标了即时的显示立即生效');
eq(effectOf('RESTART'), {immediate: false, text: '重启生效'}, '标了重启的显示重启生效');
// 没标过的往安全的方向倒：多提示一次重启的代价是白重启，说成已生效却没生效则没人会纠正
eq(effectOf(null), {immediate: false, text: '重启生效'}, '没标过的按需重启显示');
eq(effectOf(undefined), {immediate: false, text: '重启生效'}, '拿不到生效时机时按需重启显示');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
