/**
 * 推送页遇到旧全类名的处理器：界面说的与机器人做的必须是同一件事
 *
 * 处理器换过包名之后，使用者 datasource.json 里那一条写的仍是旧名。后端认得出旧名
 * （别名表，见 NovaEventHandlerService），前端按主名严格比就对不上，于是：
 * 开关显示成「关」而机器人照推、取消勾选删不掉那一条、旧名下的自定义模板读不到而
 * 页面报「默认模板 ✓」——三条都<b>不报错</b>，点一遍界面看不出任何异常。
 *
 * 因此本尺喂一份含旧名条目的推送配置，逐格问那几支纯函数与 toggleNotice 的答案。
 *
 * 🔴 <b>旧名清单不在本文件里另写一份。</b>它随 /api/handlers 一项的 aliases 送来，
 * 真源是后端那张别名表；夹具自己编一份的话，量的就是夹具与前端两份手写清单对不对得上，
 * 而真正会分叉的是后端那张表与前端。类名用的是与包路径无关的短串：写上真类名的话，
 * 下一次搬包这把尺会跟着变红，而它量的根本不是哪个类搬去了哪里。
 *
 * toggleNotice 住在 push.js 里、碰 DOM，按花括号配平切出整块后<b>真执行</b>，
 * 不是只 includes 一段字样。它用到的那几支由本尺把 push-model 的真导出注进去，
 * 不另写替身——替身写对了而产品码写错，这一格照样绿。
 *
 * 由 PushLegacyHandlerNameTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import * as model from '../../../main/resources/config-ui-pages/push-model.js';
import * as templateModel from '../../../main/resources/config-ui-pages/template-model.js';

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'push.js'), 'utf8');

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

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
 */
function bracedFrom(text, marker) {
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const open = text.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const prev = i > 0 ? text[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && text[i + 1] === '/') {
      const nl = text.indexOf('\n', i);
      i = nl < 0 ? text.length : nl;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return '';
}

/**
 * 切出 toggleNotice 并注入它用到的那几支
 *
 * 注进去的一律是 push-model 的真导出。这一笔之前 push-model 里还没有按别名认的那几支，
 * 此时它们是 undefined——而那时的 toggleNotice 也用不到它们，正是本尺要量的那个状态。
 */
function loadToggleNotice(store) {
  const body = bracedFrom(src, 'function toggleNotice');
  if (!body) throw new Error('切不出 toggleNotice 整块');
  return new Function('messageOf', 'handlerNames', 'handlerOf', 'markDirty', 'renderStreamers',
    'store', body + '\nreturn toggleNotice;')(
    model.messageOf, model.handlerNames, model.handlerOf, () => {}, () => {}, store);
}

// —— 喂进去的那一份 ——
// 类名短串与真包路径无关，见文件头
const MAIN = 'x.Report';
const LEGACY = 'y.Report';
const OTHER = 'x.LiveOn';
const PLATFORM = 'p';

const LAYOUT_OPTIONS = [
  {key: 'showRevenue', label: '显示营收', type: 'BOOLEAN', defaultValue: true},
];

/** /api/handlers 的一项：主名带 aliases，旧名不混进主表 */
const HANDLERS = [
  {
    className: MAIN, displayName: '下播报告', description: '下播时推送', platform: PLATFORM,
    aliases: [LEGACY], placeholders: ['{uname}'], options: LAYOUT_OPTIONS,
    defaultParams: {message: '出厂的下播报告', showRevenue: true},
  },
  {
    className: OTHER, displayName: '开播通知', description: '开播时推送', platform: PLATFORM,
    aliases: [], placeholders: ['{uname}'], options: [],
    defaultParams: {message: '出厂的开播通知'},
  },
];

const USER = {platform: PLATFORM, uid: 1, _uname: '甲', enabled: true};

/**
 * 老使用者的推送配置：下播报告那一条写的是旧名，而且模板与版式都改过
 * @param messages 这个通道的消息
 */
function datasource(messages) {
  return [Object.assign({}, USER, {
    targets: [{platform: 'qq', type: 1, num: 12345, enabled: true, messages}],
  })];
}

const LEGACY_CUSTOM = {handler: LEGACY, params: {message: '自己写了很久的报告模板', showRevenue: false}};

const users = () => datasource([JSON.parse(JSON.stringify(LEGACY_CUSTOM))]);

/** 这个通道上某一类通知的开关读成开还是关 */
function switchOf(user, target, className) {
  const row = model.noticeSwitches(user, target, HANDLERS).find(item => item.className === className);
  return row ? row.on : 'no-row';
}

// ① 开关读成关：旧名那一条对不上主名，界面画「关」而机器人照推
ask('① 旧名条目在界面上读成开', true, () => {
  const list = users();
  return switchOf(list[0], list[0].targets[0], MAIN);
});

// ② 取消勾选：旧名那一条也得删掉，只删主名的话它留在文件里继续生效
ask('② 取消勾选后这一类通知在配置里恰 0 条', 0, () => {
  const list = users();
  const target = list[0].targets[0];
  loadToggleNotice({handlerList: HANDLERS})(target, MAIN, false);
  return target.messages.filter(item => item.handler === MAIN || item.handler === LEGACY).length;
});

// ③ 旧名下的自定义模板：读不到就报「默认模板 ✓」，而发出去的是自定义那版
ask('③ 旧名下的自定义模板读得到', {custom: true, changed: [MAIN]}, () => {
  const list = users();
  const state = model.templateState(list[0].targets[0], HANDLERS);
  return {custom: state.custom, changed: state.changed};
});

// ④ 勾上：已有旧名条目时把它扶正成主名，不新增第二条；同一类通知两条会推两遍
ask('④ 已有旧名条目时勾上 → 恰 1 条主名且参数还在',
  {'条数': 1, '名字': MAIN, '模板': '自己写了很久的报告模板'}, () => {
    const list = users();
    const target = list[0].targets[0];
    loadToggleNotice({handlerList: HANDLERS})(target, MAIN, true);
    const mine = target.messages.filter(item => item.handler === MAIN || item.handler === LEGACY);
    return {
      '条数': mine.length,
      '名字': mine.length ? mine[0].handler : '没有',
      '模板': mine.length ? (mine[0].params || {}).message : '没有',
    };
  });

// ⑤ 只打开页面不动任何开关：读的那几支一个字节也不许改配置
// 归一若写成「读到就把 handler 改写成主名」，这一格红——那种迁移是后台偷偷改用户的文件
ask('⑤ 只读那几支跑完，配置逐字不变', true, () => {
  const list = users();
  const before = JSON.stringify(list);
  const target = list[0].targets[0];
  model.pushTree(list, [], HANDLERS, {});
  model.channelIndex(list, [], HANDLERS, {});
  model.noticeSwitches(list[0], target, HANDLERS);
  model.templateState(target, HANDLERS);
  model.layoutState(target, HANDLERS);
  templateModel.templateAdoption(list, HANDLERS[0], {});
  return JSON.stringify(list) === before;
});

// ⑥ 报告版式：旧名下改过的版式同样要认出来，否则「恢复默认」按钮压根不出现
ask('⑥ 旧名下改过的版式读得到', {present: true, custom: true, className: MAIN}, () => {
  const list = users();
  const state = model.layoutState(list[0].targets[0], HANDLERS);
  return {present: state.present, custom: state.custom, className: state.className};
});

// ⑦ 默认模板页「谁在用」：旧名那一条得算进「自己改过」的一栏，否则那一页少数一个通道
ask('⑦ 默认模板页把旧名条目算进自定义那一栏', {following: 0, custom: 1}, () => {
  const list = users();
  const rows = templateModel.templateAdoption(list, HANDLERS[0], {});
  return {following: rows.following.length, custom: rows.custom.length};
});

/** 一个通道上两条：下播报告写的是旧名，开播通知写的是主名 */
function mixed() {
  return datasource([
    JSON.parse(JSON.stringify(LEGACY_CUSTOM)),
    {handler: OTHER, params: {message: '出厂的开播通知'}},
  ]);
}

// ⑧ 阴性对照：这两格在改动前后都该绿，改动把它们弄红即为认宽了
// 没声明旧名的处理器照旧读得出；删一类通知不许把别人的条目一并删掉
ask('⑧ 无旧名的那一类照旧读得出，删一类不碰别类',
  {'别类的开关': true, '删掉本类后别类还在': 1}, () => {
    const list = mixed();
    const target = list[0].targets[0];
    const otherSwitch = switchOf(list[0], target, OTHER);
    loadToggleNotice({handlerList: HANDLERS})(target, MAIN, false);
    return {
      '别类的开关': otherSwitch,
      '删掉本类后别类还在': target.messages.filter(item => item.handler === OTHER).length,
    };
  });

// ⑨ 别名不许张开到别的处理器：拿 OTHER 去问，答的必须是 OTHER 那一条，不是旧名那一条
ask('⑨ 拿无旧名的处理器去问，取回的是它自己那一条', OTHER, () => {
  const target = mixed()[0].targets[0];
  const message = model.messageOf(target, HANDLERS[1]);
  return message ? message.handler : '取不到';
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
