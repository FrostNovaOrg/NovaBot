/**
 * 危险确认弹层判定的三档对照
 *
 * 打开／取消／确认、Esc＝取消、回调只调一次——原生 confirm() 由浏览器保证，
 * 换成自绘弹层之后必须自己守。这些全是纯函数（config-ui/confirm-model.js，不碰 DOM），
 * 本文件喂它空串、缺字段、大小写按键对答案。
 *
 * 用 node 直接跑：
 *   node tools/confirm-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {idle, open, settle, keydown} from '../core/starbot-core/src/main/resources/config-ui/confirm-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 档一：收起与打开（空串／缺字段）——
eq(idle().status, 'idle', '收起');
eq(idle(), {status: 'idle', title: '', body: '', accepted: null, calls: 0, trigger: null, fields: [], danger: false}, '收起整份');
eq(open(idle(), null), {status: 'open', title: '', body: '', accepted: null, calls: 0, trigger: null, fields: [], danger: true}, '没给说明不编字');
eq(open(idle(), {title: '', body: '  '}), {status: 'open', title: '', body: '  ', accepted: null, calls: 0, trigger: null, fields: [], danger: true}, '标题空串、正文空白原样带上');
eq(open(idle(), {title: '删', body: '不可恢复', trigger: 1}).trigger, 1, '触发钮原样记住');

// —— 档二：结算（只填一半：未打开／已收掉不再调）——
const shut = idle();
let n = 0;
eq(settle(shut, true, () => { n++; }) === shut, true, '还没打开时原样返回');
eq(n, 0, '还没打开不调回调');

const layer = open(idle(), {title: '删', body: '后果'});
n = 0;
const done = settle(layer, true, (ok) => { n++; eq(ok, true, '确认回调参数'); });
eq(n, 1, '确认调一次');
eq(done, {status: 'done', title: '删', body: '后果', accepted: true, calls: 1, trigger: null, fields: [], danger: true}, '确认后整份');

n = 0;
eq(settle(done, false, () => { n++; }) === done, true, '已经收掉再点取消原样返回');
eq(n, 0, '已经收掉不再调回调');

const half = open(idle(), {title: '半'});
n = 0;
const cancelled = settle(half, 0, (ok) => { n++; eq(ok, false, '取消回调参数'); });
eq(n, 1, '取消调一次');
eq(cancelled.accepted, false, '0 视同取消');

// —— 档三：按键（大小写：只有 Escape 算取消）——
const asking = open(idle(), {title: 'Esc'});
n = 0;
const byEsc = keydown(asking, 'Escape', () => { n++; });
eq(n, 1, 'Escape 等于取消并调一次');
eq(byEsc.status, 'done', 'Escape 后收掉');
eq(byEsc.accepted, false, 'Escape 是取消不是确认');

n = 0;
eq(keydown(asking, 'escape', () => { n++; }) === asking, true, '小写 escape 不算');
eq(keydown(asking, 'ESC', () => { n++; }) === asking, true, '全大写 ESC 不算');
eq(keydown(asking, 'Enter', () => { n++; }) === asking, true, 'Enter 不结算');
eq(n, 0, '对不上的键不调回调');

n = 0;
eq(keydown(idle(), 'Escape', () => { n++; }).status, 'idle', '还没打开时 Esc 也不调');
eq(n, 0, '还没打开按 Esc 不调回调');

// —— 档四：输入槽与危险钮（空数组／缺字段／显式关掉红底）——
eq(open(idle(), {fields: [{label: '名', value: '我的设备'}]}).fields[0].value, '我的设备', 'fields 原样带上');
eq(open(idle(), {fields: null}).fields, [], 'fields 缺则空数组');
eq(open(idle(), {danger: false}).danger, false, 'danger 可关');
eq(open(idle(), {}).danger, true, '没写 danger 仍按危险确认');
eq(settle(open(idle(), {fields: [{id: 'n'}], danger: false}), true).fields[0].id, 'n', '结算后 fields 还在');
eq(settle(open(idle(), {danger: false}), true).danger, false, '结算后 danger 还在');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
