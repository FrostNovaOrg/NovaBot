/**
 * 设置页常用／高级按服务端标的级别分
 *
 * 抓的用户故障：插件新加的设置项落错了地方——该进高级的挤在常用里，
 * 或者常用的整组掉进高级、收起时找不到。分法只认 /api/schema 每一项的 level
 * 与组上的 advanced，界面上没有另一张写着键名的名单。
 *
 * 各格：①组是 advanced 的整组进高级（组里标了常用的也一样）；②其余组里 ADVANCED 进高级、
 * BASIC／COMMON 留在常用，高级那一半另起一组「某组 · 高级」；③一组里全是高级项时，
 * 常用那边不留空组；④由卡片摆的项（告警的 Webhook 地址这类）跟着卡留在常用，
 * 级别标成高级也不搬走——搬走的话行里摘掉了、卡又在常用里，这一项会在高级里凭空多算一次；
 * ⑤旧后端没给 level 时按常用摆，不把整页藏起来；⑥拆出来的两半各自重算「整组都要重启」；
 * ⑦不改动传进来的那份字段表（重画时还要用它）。
 *
 * 由 SettingsLevelSplitTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {arrangeGroups} from '../../../main/resources/config-ui/settings-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function item(name, level, effect) {
  return {name, level, effect: effect || 'RESTART'};
}

const schema = [
  {group: 'push', title: '推送', advanced: false, allRestart: false, fields: [
    item('demo.push.enabled', 'COMMON', 'IMMEDIATE'),
    item('demo.push.burst', 'ADVANCED'),
    item('demo.push.target', 'BASIC'),
  ]},
  {group: 'alert', title: '告警', advanced: false, allRestart: false, fields: [
    item('demo.alert.hook', 'ADVANCED'),
    item('demo.alert.retry', 'ADVANCED'),
    item('demo.alert.enabled', 'COMMON', 'IMMEDIATE'),
  ]},
  {group: 'cmd', title: '命令与权限', advanced: false, allRestart: true, fields: [
    item('demo.cmd.engine', 'ADVANCED'),
  ]},
  {group: 'collect', title: '采集', advanced: false, allRestart: true, fields: [
    {name: 'demo.collect.old', effect: 'RESTART'},
  ]},
  {group: 'svc', title: '服务', advanced: true, allRestart: true, fields: [
    item('demo.svc.port', 'COMMON'),
    item('demo.svc.threads', 'ADVANCED'),
  ]},
];
const before = JSON.stringify(schema);
const carded = (group, name) => group === 'alert' && name === 'demo.alert.hook';

let arranged = {common: [], advanced: []};
try {
  arranged = arrangeGroups(schema, carded);
} catch (err) {
  failures.push('arrangeGroups 抛了：' + err.message);
}

const shape = groups => groups.map(g => ({group: g.group, title: g.title,
  fields: g.fields.map(f => f.name)}));

eq(shape(arranged.common), [
  {group: 'push', title: '推送', fields: ['demo.push.enabled', 'demo.push.target']},
  {group: 'alert', title: '告警', fields: ['demo.alert.hook', 'demo.alert.enabled']},
  {group: 'collect', title: '采集', fields: ['demo.collect.old']},
], '②③④⑤ 常用：BASIC／COMMON 留下，卡片摆的项留下，没给 level 的留下，全是高级的组不留空壳');

eq(shape(arranged.advanced), [
  {group: 'push-advanced', title: '推送 · 高级', fields: ['demo.push.burst']},
  {group: 'alert-advanced', title: '告警 · 高级', fields: ['demo.alert.retry']},
  {group: 'cmd-advanced', title: '命令与权限 · 高级', fields: ['demo.cmd.engine']},
  {group: 'svc', title: '服务', fields: ['demo.svc.port', 'demo.svc.threads']},
], '①② 高级：标了 ADVANCED 的另起「某组 · 高级」，advanced 组整组进来（标常用的也在内），顺序照组表');

eq(arranged.advanced.map(g => g.advanced), [true, true, true, true],
  '高级那一半都标成 advanced，组目录据此排进「高级」那一段');

const restart = groups => Object.fromEntries(groups.map(g => [g.group, g.allRestart]));
eq(restart(arranged.common), {push: false, alert: false, collect: true},
  '⑥ 常用那一半重算「整组都要重启」：推送剩下的里有即时生效的，不挂牌子');
eq(restart(arranged.advanced), {'push-advanced': true, 'alert-advanced': true, 'cmd-advanced': true, svc: true},
  '⑥ 高级那一半重算：拆出来的几项都要重启，挂牌子');

eq(JSON.stringify(schema) === before, true, '⑦ 传进来的字段表原样不动');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
