/**
 * 底部改动条的供数方（SPI）：核心只认识「有哪些供数方、各报几处改动、各自怎么保存」
 *
 * 改动条从前直接读 store 上那三项推送状态（pushData／pushSaved／pushEnabled），
 * 开机那一趟也直接去取 /datasource。而推送页随控制台插件走，插件是可卸的——
 * 卸掉之后核心仍会去取那份文件、仍按一份取不到的东西算改动数，而屏幕上
 * <b>不会有任何异常</b>：改动条照样显示 0，出问题的那句话（「datasource.json
 * 不是合法 JSON」）却会在一台根本没有推送页的机器上弹出来。
 *
 * 因此这把尺分两半：
 *   一半查<b>核心界面里还剩不剩产品形状</b>（①②③）。三问一律先各自报一个非零分母
 *   再比——只 diff 的话，「本来就没有」与「没变化」是同一个读数。
 *   另一半<b>真跑</b> core.js（④⑤）：一位供数方也没有时改动条什么都不数、
 *   一位也不问；登记一位之后核心照它报的数写字。⑤ 是 ④ 的阳性对照——
 *   少了它，把整套 SPI 删干净这一格照样绿。
 *
 * 五问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ChangeBarSourceTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
const notes = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function src(name) {
  return readFileSync(join(ui, name), 'utf8');
}

/** 搬出核心的那三项推送状态。它们此后住在控制台插件的推送页里 */
const MOVED_OUT = ['pushData', 'pushSaved', 'pushEnabled'];

await ask('① store.js 上没有推送那三项', () => {
  const fields = [...src('store.js').matchAll(/^ {2}([A-Za-z_$][\w$]*):/gm)].map(item => item[1]);
  // 分母先报。取法哪天不认得 store.js 了，下面那句「一个也没命中」是空扫出来的
  if (fields.length < 5) {
    throw new Error('只数出 ' + fields.length + ' 项共享状态，取法已经不认得 store.js 的写法了');
  }
  notes.push('① store 上共 ' + fields.length + ' 项');
  const stale = MOVED_OUT.filter(name => fields.includes(name));
  if (stale.length) {
    throw new Error('store 上共 ' + fields.length + ' 项，其中推送那几项还在：' + stale.join('、'));
  }
});

await ask('② main.js 开机不取 /datasource', () => {
  const calls = [...src('main.js').matchAll(/api\('(\/[^']*)'/g)].map(item => item[1]);
  if (!calls.length) {
    throw new Error("main.js 里一处 api('/…') 也没数到，取法已经不认得它的写法了");
  }
  notes.push('② main.js 里 api(\'/…\') 共 ' + calls.length + ' 处');
  const stale = calls.filter(path => path.startsWith('/datasource'));
  if (stale.length) {
    throw new Error('main.js 共调 ' + calls.length + ' 条接口，其中还取着：' + stale.join('、'));
  }
});

await ask('③ core.js 不按名字认得推送页签', () => {
  const tabs = [...src('core.js').matchAll(/store\.tab\s*===\s*'([^']+)'/g)].map(item => item[1]);
  // 分母先报：一处 store.tab === '…' 也没有的话，改动条已经不按页签认了，
  // 「不含 push」是在一个空集上成立的
  if (!tabs.length) {
    throw new Error("core.js 里一处 store.tab === '…' 也没有，这一格量的那个东西没了");
  }
  notes.push('③ core.js 按名字认的页签共 ' + tabs.length + ' 个：' + tabs.join('、'));
  if (tabs.includes('push')) {
    throw new Error('core.js 仍按名字认得推送页签，认的是：' + tabs.join('、'));
  }
});

// —— 下半截：真跑 core.js ——

/** 记下发出去的每一条请求。一位供数方也没有时这张表必须是空的 */
const requests = [];

/** 页面上那几个落点。改动条真正写字的地方就是 #change-count */
const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

/** 挂在 <html> 上的那几个类。底部那条藏不藏由它一处定，见 core.js 的 paintBar */
const htmlClasses = new Set();

globalThis.document = {
  querySelector: node,
  documentElement: {
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};
globalThis.fetch = (path, opt) => {
  requests.push({path, opt});
  return Promise.resolve({status: 200, json: () => Promise.resolve({})});
};

const coreUrl = new URL('../../../main/resources/config-ui/core.js', import.meta.url).href;
const storeUrl = new URL('../../../main/resources/config-ui/store.js', import.meta.url).href;

let core = null;
let store = null;
try {
  core = await import(coreUrl);
  store = (await import(storeUrl)).store;
} catch (err) {
  failures.push('载入 core.js／store.js 就抛了：' + err.message);
}

await ask('④ 一位供数方也没有时：不出保存按钮、不数改动、一位也不问', async () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  store.tab = 'home';
  store.dirty = {};
  store.restartPending = [];
  requests.length = 0;

  const target = core.saveTarget();
  const count = core.changeCount();
  core.markDirty();
  await core.reloadChangeSources();

  const got = {
    '存去哪': target,
    '改动数': count,
    '改动条': node('#change-count').textContent,
    '保存按钮': node('#save').style.display,
    '发出的请求': requests.length,
  };
  const want = {'存去哪': null, '改动数': 0, '改动条': '', '保存按钮': 'none', '发出的请求': 0};
  for (const key of Object.keys(want)) {
    if (got[key] !== want[key]) {
      throw new Error(key + ' 得到 ' + JSON.stringify(got[key]) + '，应为 ' + JSON.stringify(want[key]));
    }
  }
});

await ask('⑤ 登记一位供数方之后，核心照它报的数写字、按它的法子存与重取', async () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  let saved = 0;
  let reloaded = 0;
  core.registerChangeSource('假页', {
    displayName: '假页',
    changeCount: () => 3,
    save: () => { saved++; },
    reload: () => { reloaded++; },
  });

  store.tab = '假页';
  requests.length = 0;
  core.markDirty();
  const source = core.currentChangeSource();
  if (!source) throw new Error('登记过的那一位，currentChangeSource 认不出来');
  source.save();
  await core.reloadChangeSources();

  const got = {
    '存去哪': core.saveTarget(),
    '改动数': core.changeCount(),
    '改动条': node('#change-count').textContent,
    '保存按钮': node('#save').style.display,
    '存了几次': saved,
    '重取了几次': reloaded,
    '发出的请求': requests.length,
  };
  const want = {
    '存去哪': '假页', '改动数': 3, '改动条': '3 处改动', '保存按钮': '',
    '存了几次': 1, '重取了几次': 1, '发出的请求': 0,
  };
  for (const key of Object.keys(want)) {
    if (got[key] !== want[key]) {
      throw new Error(key + ' 得到 ' + JSON.stringify(got[key]) + '，应为 ' + JSON.stringify(want[key]));
    }
  }
});

await ask('⑥ 接线：报得出改动数的插件页由 mountPages 登记，保存按钮问的是供数方', () => {
  const text = src('main.js');
  const from = text.indexOf('async function mountPages(');
  if (from < 0) {
    throw new Error('找不到 mountPages，登记那一处无从量起');
  }
  let to = text.length;
  for (const marker of ['\nfunction ', '\nasync function ']) {
    const at = text.indexOf(marker, from + 1);
    if (at >= 0) to = Math.min(to, at);
  }
  const body = text.slice(from, to);
  // 登记那一手可以直接写在这里，也可以是一个帮手——但它必须在「装页」那一趟里发生。
  // 挪到别处的话，装上的页与登记在册的页就成了两份名单
  if (!/ChangeSource\(/.test(body)) {
    throw new Error('mountPages 里没有登记那一手，改动条此后永远只认设置页');
  }
  if (!text.includes('registerChangeSource(')) {
    throw new Error('main.js 没有调核心那份登记表，供数方名单有了第二份');
  }
  // 按「这一页报不报得出改动数」挑。一律登记的话，没有草稿态的那些页上
  // 也会冒出保存与放弃两个按钮，而它们按下去无事可做
  if (!text.includes(".changeCount !== 'function'")) {
    throw new Error('main.js 没有按「报不报得出改动数」挑，等于把每一页都登记成供数方');
  }
  // 保存按钮那一路也得走同一份登记表。另走一条 pages.find 的话，
  // 「谁在供数」就有了两份名单，而分叉时的表现是按钮在那一页上点了不管用
  if (!text.includes('currentChangeSource()')) {
    throw new Error('底部保存按钮没问过 currentChangeSource，供数方名单有了第二份');
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of notes) console.log('  分母：' + line);
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
