/**
 * 通道详情「消息长什么样」「报告长什么样」两段：默认就能改，「恢复默认」改过才出现
 *
 * 从前这两段锁着：先点「改为自定义」才解锁编辑器，而解锁态只活在当次页面里、不进
 * 配置；改完一项「恢复默认」也不出现，想改回去的人找不到路。现在两段默认可改，
 * 与默认不同时「恢复默认」当场出现、改回相同就收起。出现与收起只动状态行那几个
 * 节点，不整段重画——整段重画会把输入框的焦点弄丢。
 *
 * 本尺真跑：假 DOM 上把整页通道详情画出来，改动从真编辑器的真控件事件里走
 * （@ 那一档的下拉、版式开关），不是只 includes 一段字样。量的是产品码判默认／
 * 自定义的那几支原样给出的答案，尺里不另写一套判法。
 *
 * 由 PushDirectEditTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const here = dirname(fileURLToPath(import.meta.url));
const pages = join(here, '../../../main/resources/config-ui-pages');

const failures = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假 DOM：记下事件监听，改动才能从真控件的真实回调里走 ——

function fakeElement(tag) {
  const node = {
    tagName: String(tag).toLowerCase(),
    className: '', id: '', type: '', textContent: '', innerHTML: '',
    hidden: false, disabled: false, draggable: false, selected: false,
    value: '', contentEditable: '', attrs: {}, dataset: {}, style: {},
    children: [], listeners: {}, found: {}, parentNode: null,
  };
  node.appendChild = child => {
    if (!child) return child;
    node.children.push(child);
    child.parentNode = node;
    return child;
  };
  node.addEventListener = (kind, fn) => {
    (node.listeners[kind] = node.listeners[kind] || []).push(fn);
  };
  node.setAttribute = (key, value) => { node.attrs[key] = String(value); };
  node.getAttribute = key => node.attrs[key];
  // innerHTML 只是摆着的字符串，不解析。产品码往这种标签里 querySelector 的地方
  // （开关那一行的复选框）按选择器记节点：同一个选择器给同一个节点，
  // 挂上去的事件才找得回来
  node.querySelector = sel => node.found[sel] || (node.found[sel] = fakeElement('input'));
  node.classList = {toggle() {}, add() {}, remove() {}, contains() { return false; }};
  return node;
}

const byId = {};
const node = id => byId[id] || (byId[id] = fakeElement('div'));

globalThis.document = {
  querySelector: sel => node(String(sel).replace(/^#/, '')),
  getElementById: id => byId[id] || null,
  createElement: fakeElement,
  createTextNode: text => ({nodeType: 3, nodeValue: String(text)}),
  head: fakeElement('head'),
  body: fakeElement('body'),
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  addEventListener() {},
  get activeElement() { return null; },
};
globalThis.location = {hash: ''};
// 版式预览那张图取不到就取不到：那一支自己会说「预览取不到」，与本尺量的锁态无关
globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({})});

// —— 一位主播推一个群：两类通知都开着，模板与版式都在默认那一份上 ——

const PLATFORM = 'p';
const LIVE = 'x.LiveOn';
const REPORT = 'x.Report';
const HANDLERS = [
  {
    className: LIVE, displayName: '开播通知', description: '开播时推送', platform: PLATFORM,
    aliases: [], placeholders: ['{uname}', '{title}'], options: [],
    defaultParams: {message: '{uname} 开播了'},
  },
  {
    className: REPORT, displayName: '下播报告', description: '下播时推送', platform: PLATFORM,
    aliases: [], placeholders: [], options: [
      {key: 'showRevenue', label: '显示营收', type: 'BOOLEAN', defaultValue: true},
    ],
    // 版式键的默认值也在默认参数里：模板那一段的判法按同一份参数袋比，
    // 真处理器的形状就是如此（见 template-model 的 knownKeys）
    defaultParams: {showRevenue: true},
  },
];

function datasource() {
  return [{
    platform: PLATFORM, uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: 'qq', type: 1, num: 12345, enabled: true,
      messages: [{handler: LIVE}, {handler: REPORT}],
    }],
  }];
}

let push = null;
try {
  // store 也按插件页那套相对名取：上面的钩子把它指到宿主那一份，
  // 与 push.js 里 import 到的是同一个实例——往里交处理器清单，push.js 读的就是它
  const store = await import('./store.js');
  push = await import(join(pages, 'push.js'));
  store.store.handlerList = HANDLERS;
  push.render(fakeElement('section'));
  push.setPushEntries(datasource());
  push.showPush('1', '12345');
} catch (err) {
  failures.push('载入 push.js 或画通道详情就抛了：' + err.message);
}

// —— 在画好的那一页上找东西 ——

function walk(node, fn) {
  if (!node) return;
  fn(node);
  for (const child of node.children || []) walk(child, fn);
}

function all(root, pred) {
  const out = [];
  walk(root, item => { if (pred(item)) out.push(item); });
  return out;
}

/** 通道详情画在 #push-right 里 */
const page = () => node('push-right');

/** 按段头找那一段的卡片 */
function section(mark) {
  const head = all(page(), item => item.tagName === 'h3' && item.innerHTML.includes(mark))[0];
  return head ? head.parentNode : null;
}

const tpl = () => section('消息长什么样');
const lay = () => section('下播报告长什么样');

/** 状态行上「本通道用的是」那一句 */
const stateLabel = box =>
  all(box, item => item.tagName === 'span' && String(item.innerHTML).includes('本通道用的是'))[0];

/** 「恢复默认」出现没有：得有这颗按钮，且没藏着 */
const restoreVisible = box =>
  all(box, item => item.tagName === 'button' && item.textContent === '恢复默认')
    .some(item => !item.hidden);

/** 这一段里（连各层子节点）有没有哪处提到这句话 */
function mentions(box, text) {
  let hit = false;
  walk(box, item => {
    if (String(item.textContent).includes(text) || String(item.innerHTML).includes(text)) hit = true;
  });
  return hit;
}

/** 未改动时状态行旁那句小字（直接改就行…） */
const freeNote = box =>
  all(box, item => String(item.textContent).includes('直接改就行')
    && String(item.textContent).includes('恢复默认'))[0] || null;

/** 当前那一枚 @ 档下拉：重画之后取最新的一枚 */
const atSelect = () =>
  all(tpl(), item => item.tagName === 'select' && item.id === 'tpl-at').at(-1);

/** 当前那一枚版式开关：复选框记在开关那一行的 querySelector 里，取最新的一枚 */
const layoutToggle = () => {
  const row = all(lay(), item => item.tagName === 'label'
    && item.innerHTML.includes('type="checkbox"')).at(-1);
  return row ? row.found['input'] : null;
};

/** 从真控件的真回调里改一下 */
function fire(control, kind, event) {
  const list = ((control || {}).listeners || {})[kind] || [];
  if (!list.length) throw new Error('控件上没有挂 ' + kind + ' 事件');
  for (const fn of list) fn(event);
}

// ---- 未改动：两段都不锁，也没有多余的那一步 ----

await ask('① 段 2 默认就能改：块按得动、@ 档换得动、正文可编辑', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const box = tpl();
  if (!box) throw new Error('没画出来「消息长什么样」那一段');
  const blocks = all(box, item => String(item.className).includes('tpl-blk'));
  const bodies = all(box, item => String(item.className).includes('tpl-c-body'));
  same({
    '块至少列了两枚': blocks.length >= 2,
    '块都没灰': blocks.every(item => !item.disabled),
    '@ 档没灰': !atSelect().disabled,
    '正文可编辑': bodies.length > 0 && bodies.every(item => item.contentEditable === 'true'),
  }, {
    '块至少列了两枚': true, '块都没灰': true, '@ 档没灰': true, '正文可编辑': true,
  }, '段 2 锁态');
});

await ask('② 段 3 默认就能改：版式开关不带 disabled', async () => {
  const box = lay();
  if (!box) throw new Error('没画出来「报告长什么样」那一段');
  const rows = all(box, item => item.tagName === 'label'
    && item.innerHTML.includes('type="checkbox"'));
  same(rows.length >= 1 && rows.every(item => !item.innerHTML.includes('disabled')),
    true, '版式开关锁态');
});

await ask('③ 两段都没有「改为自定义」这一步', async () => {
  same({'段 2 没有': !mentions(tpl(), '改为自定义'), '段 3 没有': !mentions(lay(), '改为自定义')},
    {'段 2 没有': true, '段 3 没有': true}, '改为自定义');
});

await ask('④ 未改动时两段都没有「恢复默认」', async () => {
  same({'段 2 没有': !restoreVisible(tpl()), '段 3 没有': !restoreVisible(lay())},
    {'段 2 没有': true, '段 3 没有': true}, '恢复默认');
});

await ask('⑤ 未改动时状态行旁有小字：直接改就行，改过随时可恢复', async () => {
  const note2 = freeNote(tpl());
  const note3 = freeNote(lay());
  same({
    '段 2 有': !!note2 && !note2.hidden,
    '段 3 有': !!note3 && !note3.hidden,
    '段 3 说的是版式': !!note3 && note3.textContent.includes('默认版式'),
  }, {'段 2 有': true, '段 3 有': true, '段 3 说的是版式': true}, '未改动的小字');
});

// ---- 改一项：「恢复默认」当场出现 ----

await ask('⑥ 段 2 改一项（@ 档换一档）→「恢复默认」出现、状态行说自定义', async () => {
  fire(atSelect(), 'change', {target: {value: 'all'}});
  const label = stateLabel(tpl());
  const note = freeNote(tpl());
  same({
    '恢复默认出现': restoreVisible(tpl()),
    '状态行说自定义': !!label && label.innerHTML.includes('自定义'),
    '小字收起': note ? note.hidden : false,
  }, {'恢复默认出现': true, '状态行说自定义': true, '小字收起': true}, '段 2 改后');
});

await ask('⑦ 段 3 改一项（版式开关拨一下）→「恢复默认」出现、状态行说自定义版式', async () => {
  fire(layoutToggle(), 'change', {target: {checked: false}});
  const label = stateLabel(lay());
  const note = freeNote(lay());
  same({
    '恢复默认出现': restoreVisible(lay()),
    '状态行说自定义版式': !!label && label.innerHTML.includes('自定义版式'),
    '小字收起': note ? note.hidden : false,
  }, {'恢复默认出现': true, '状态行说自定义版式': true, '小字收起': true}, '段 3 改后');
});

// ---- 改回默认值：收起 ----

await ask('⑧ 改回默认值：两段「恢复默认」都收起、状态行回默认、小字回来', async () => {
  // 两段共用一只参数袋，各段的状态行跟自己那一段的改动走：先收版式、后收模板，
  // 让最后一下同步到的是两段都回默认的终态
  fire(layoutToggle(), 'change', {target: {checked: true}});
  fire(atSelect(), 'change', {target: {value: 'subscribers'}});
  const label2 = stateLabel(tpl());
  const label3 = stateLabel(lay());
  const note2 = freeNote(tpl());
  const note3 = freeNote(lay());
  same({
    '段 2 收起': !restoreVisible(tpl()),
    '段 3 收起': !restoreVisible(lay()),
    '段 2 回默认': !!label2 && label2.innerHTML.includes('默认模板'),
    '段 3 回默认': !!label3 && label3.innerHTML.includes('默认版式'),
    '两段小字回来': !!note2 && !note2.hidden && !!note3 && !note3.hidden,
  }, {
    '段 2 收起': true, '段 3 收起': true, '段 2 回默认': true,
    '段 3 回默认': true, '两段小字回来': true,
  }, '改回默认后');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
