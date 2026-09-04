/**
 * 设置页视图模型的三档对照
 *
 * 搜索、只看改过、危险项围栏——这几件事在真机上凑齐一次的代价极高：
 * 「说明里含关键字但标题不含」靠人手点点不出来。视图模型因此被切成纯函数
 * （config-ui/settings-model.js，不碰 DOM），本文件喂它几份字段表条目对答案。
 *
 * 用 node 直接跑：
 *   node tools/settings-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {
  MASK, dangerOf, isDangerous, defaultText, isChanged, isVisible, effectOf,
} from '../starbot-core/src/main/resources/config-ui/settings-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function field(over) {
  return Object.assign({
    name: 'starbot.core.push.enabled', label: 'enabled', widget: 'boolean',
    description: '全局推送开关', defaultValue: true, effect: 'IMMEDIATE', sensitive: false,
  }, over);
}

// —— 档一：默认值与改过 ——
eq(defaultText(field({widget: 'boolean', defaultValue: true})), '开', '布尔默认值显示成开');
eq(defaultText(field({widget: 'string', defaultValue: null})), '未设', '没有默认值的说未设');
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'true'), false, '与默认值相同不算改过');
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'false'), true, '与默认值不同算改过');
eq(isChanged(field({sensitive: true, widget: 'string', defaultValue: null}), MASK), true,
  '机密项配过就算改过');

// —— 档二：搜索与只看改过 ——
const quiet = field({
  name: 'starbot.core.push.quiet-start', label: 'quiet-start', widget: 'string',
  description: '静音时段开始，期间的推送直接丢弃', defaultValue: null,
});
eq(isVisible(quiet, '', '静音', false), true, '按说明里的词搜得到');
eq(isVisible(quiet, '', '词云', false), false, '搜不相干的词搜不到');
eq(isVisible(quiet, '', '静音', true), false, '只看改过时，没改过的即使搜中也不显示');
eq(isVisible(quiet, '23:00', '静音', true), true, '只看改过时，改过且搜中才显示');

// —— 档三：危险项与生效时机 ——
const listen = field({
  name: 'server.address', label: 'address', widget: 'string', defaultValue: '127.0.0.1',
  danger: {value: '0.0.0.0', title: '监听地址改成 0.0.0.0？', consequence: '会把接口暴露到网络上。'},
});
eq(isDangerous(listen), true, '带声明的是危险项');
eq(dangerOf(listen, '127.0.0.1'), null, '改到不危险的那一档不问');
eq(dangerOf(listen, '0.0.0.0').title, '监听地址改成 0.0.0.0？', '改到危险的那一档要先问一句');
eq(effectOf('IMMEDIATE'), {immediate: true, text: '立即生效'}, '标了即时的显示立即生效');
eq(effectOf(null), {immediate: false, text: '重启生效'}, '没标过的按需重启显示');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
