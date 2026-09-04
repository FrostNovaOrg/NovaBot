/**
 * 危险确认弹层纯模型夹具
 *
 * 量的是 config-ui/confirm-model.js：打开／取消／确认三态，以及结算回调只调一次。
 * 原生 confirm() 是同步的，换成自绘弹层之后这两件事都不再由浏览器保证——
 * 连点两下确认、弹着的时候再点取消，都可能把同一件事办两遍。
 *
 * 由 ConfirmModelTest 拉起，退码 0 ＝ 全绿。
 */

import {idle, open, settle} from '../../../main/resources/config-ui/confirm-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function same(actual, expected, what) {
  checks++;
  if (actual !== expected) failures.push(what + '：应当是同一份对象');
}

const SPEC = {title: '恢复默认？', body: '自己写的内容会丢掉。'};

// ---------- 一、三态 ----------
eq(idle().status, 'idle', '起始是收起');
eq(idle().accepted, null, '收起时还没有答案');
eq(idle().calls, 0, '收起时回调次数是 0');

const opened = open(idle(), SPEC);
eq(opened.status, 'open', '打开');
eq(opened.title, SPEC.title, '打开后带标题');
eq(opened.body, SPEC.body, '打开后带后果');
eq(opened.accepted, null, '打开时还没有答案');
eq(opened.calls, 0, '打开时回调还没调');

const cancelled = settle(opened, false);
eq(cancelled.status, 'done', '取消后收掉');
eq(cancelled.accepted, false, '取消的答案是 false');
eq(cancelled.calls, 1, '取消调了一次回调计数');
eq(cancelled.title, SPEC.title, '取消后标题还在，方便核对弹过什么');

const confirmed = settle(opened, true);
eq(confirmed.status, 'done', '确认后收掉');
eq(confirmed.accepted, true, '确认的答案是 true');
eq(confirmed.calls, 1, '确认调了一次回调计数');

// 缺字段时不编字：标题、后果都空，屏幕上就空着，不拿占位符冒充
const blank = open(idle(), {});
eq(blank.title, '', '没给标题就是空串');
eq(blank.body, '', '没给后果就是空串');
eq(open(idle(), null).title, '', 'spec 是 null 也不炸');

// ---------- 二、回调只调一次 ----------
let n = 0;
const first = settle(opened, true, () => { n++; });
eq(n, 1, '第一次确认调了回调');
eq(first.calls, 1, '第一次确认计数为 1');

settle(first, true, () => { n++; });
settle(first, false, () => { n++; });
eq(n, 1, '已经收掉之后再确认或取消，回调不再调');
eq(first.calls, 1, '已经收掉之后计数不再加');

let m = 0;
const still = idle();
same(settle(still, true, () => { m++; }), still, '没打开时确认是空操作，返回原对象');
eq(m, 0, '没打开时确认不调回调');

let k = 0;
const byCancel = settle(opened, false, ok => { k += ok ? 10 : 1; });
eq(k, 1, '取消把 false 交给回调');
settle(byCancel, true, ok => { k += ok ? 10 : 1; });
eq(k, 1, '取消后再确认，回调不再调');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
