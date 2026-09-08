/**
 * 推送页那个 N 算得全不全：每一类可改的字段，改一处都得被 changeCount() 数进去
 *
 * 底部改动条现在按「有没有话说」显隐，而 N 是其中一条。<b>N 算漏一类，那一页的保存
 * 这条路就被堵死了</b>：草稿态改了，条不出来，按钮也就无从按起——而屏幕上不会有
 * 任何异常，改动会在下一次载入时安静地没掉。core.js 那处注释防的正是这件事。
 *
 * 因此这把尺按「本页有哪几类写口」逐类喂一处改动，读 changeCount()：
 *   算进去的八类各报 1；只给人看的那三栏（下划线开头）报 0，是阴性对照——
 *   少了它，把 changeCount 改成「恒回 1」这一格照样全绿。
 *
 * 末一格是分母闸：push.js 里 markDirty() 的调用点数一变就红。新开一条改草稿的路时，
 * 它逼着人回来把那一类加进下面这张表——只靠手写名单的话，
 * 「不在表里」与「查过了」在读数上是同一个样子。
 *
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 PushChangeCountCoverageTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');

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

// —— 假页面与假接口 ——

const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

/** 改动条现在还会往 <html> 上挂一个类，假页面得给得出这个落点 */
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

let datasourceBody = '';

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  const answer = method === 'GET' ? {content: datasourceBody} : {success: true, message: '已保存'};
  return Promise.resolve({status: 200, json: () => Promise.resolve(answer)});
};

/** 一位主播、一个通道、一条开着的通知，通知带一份文字模板参数与一份版式参数 */
function datasource() {
  return [{
    platform: 'p', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: 'q', type: 1, num: 12345, enabled: true,
      messages: [{handler: 'x.LiveOn', params: {message: '开播了', width: 800}}],
    }],
  }];
}

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入 push.js／store.js 就抛了：' + err.message);
}

/** 回到「刚打开这一页、一处没改」那个状态 */
async function freshLoad() {
  datasourceBody = JSON.stringify(datasource());
  await push.reload();
  if (push.changeCount() !== 0) {
    throw new Error('刚载入就报出改动，这一趟的读数不作数');
  }
  return push.pushEntries();
}

/**
 * 本页每一类写口各一例。「写口」指改动落进 pushData 的那一手，行号是当前 push.js 的位置
 *
 * 每一类都只改一处，因此应得的读数一律是 1：N 按主播逐条比，同一位主播下改几处也算一处。
 */
const CASES = [
  {
    name: '主播·启用开关（user.enabled）',
    at: 'push.js 的主播卡启用开关',
    want: 1,
    change: users => { users[0].enabled = false; },
  },
  {
    name: '主播·加一位（pushData.push）',
    at: 'push.js 的「就是它，加进来」',
    want: 1,
    change: users => { users.push({uid: 2, platform: 'p', enabled: true, targets: []}); },
  },
  {
    name: '主播·删一位（pushData.splice）',
    at: 'push.js 的「删除主播」',
    want: 1,
    change: users => { users.splice(0, 1); },
  },
  {
    name: '通道·加一个（user.targets.push）',
    at: 'push.js 的 addChannel',
    want: 1,
    change: users => {
      users[0].targets.push({platform: 'q', type: 1, num: 999, enabled: true, messages: []});
    },
  },
  {
    name: '通道·移除（user.targets.splice）',
    at: 'push.js 的「移除这个通道」',
    want: 1,
    change: users => { users[0].targets.splice(0, 1); },
  },
  {
    name: '通知·开一类（target.messages.push）',
    at: 'push.js 的 toggleNotice 勾上那一支',
    want: 1,
    change: users => { users[0].targets[0].messages.push({handler: 'x.LiveOff'}); },
  },
  {
    name: '通知·关一类（target.messages 过滤）',
    at: 'push.js 的 toggleNotice 取消那一支',
    want: 1,
    change: users => {
      const target = users[0].targets[0];
      target.messages = target.messages.filter(item => item.handler !== 'x.LiveOn');
    },
  },
  {
    name: '文字模板·改一处参数（message.params）',
    at: 'push.js 的 sectionTemplate onChange',
    want: 1,
    change: users => {
      const message = users[0].targets[0].messages[0];
      message.params = Object.assign({}, message.params, {message: '改过的开播词'});
    },
  },
  {
    name: '报告版式·改一处参数（message.params 的版式键）',
    at: 'push.js 的 sectionLayout onChange',
    want: 1,
    change: users => {
      const message = users[0].targets[0].messages[0];
      message.params = Object.assign({}, message.params, {width: 1200});
    },
  },
  {
    name: '只给人看的三栏（_uname／_roomId／_face）',
    at: 'push.js 的 decoratePushData（阴性对照，不该算）',
    want: 0,
    change: users => {
      users[0]._uname = '换个昵称';
      users[0]._roomId = 4321;
      users[0]._face = 'https://example.invalid/a.png';
    },
  },
];

await ask('① 逐类喂一处改动，读 changeCount()', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  // 分母先报。表空了的话，下面那句「每一类都对」是在一个空集上成立的
  if (CASES.length < 3) {
    throw new Error('分类表只有 ' + CASES.length + ' 类，分母太小，这一格量不出东西');
  }
  notes.push('① 分类表共 ' + CASES.length + ' 类（其中阴性对照 '
    + CASES.filter(item => item.want === 0).length + ' 类）');

  const bad = [];
  for (const item of CASES) {
    const users = await freshLoad();
    item.change(users);
    const got = push.changeCount();
    notes.push('   ' + item.name + ' → changeCount() = ' + got + '（应为 ' + item.want + '）');
    if (got !== item.want) {
      bad.push(item.name + ' 得到 ' + got + '，应为 ' + item.want + '（写口在 ' + item.at + '）');
    }
  }
  if (bad.length) {
    throw new Error('这几类的读数对不上：\n    ' + bad.join('\n    '));
  }
});

await ask('② 同一位主播下改两处仍算一处：N 是主播数不是字段数', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const users = await freshLoad();
  users[0].enabled = false;
  users[0].targets[0].messages[0].params.message = '两处一起改';
  const got = push.changeCount();
  notes.push('② 同一位主播改两处 → changeCount() = ' + got);
  if (got !== 1) {
    throw new Error('同一位主播下改两处得到 ' + got + '，应为 1');
  }
});

/**
 * push.js 里 markDirty() 的调用点数
 *
 * 它就是「本页有几条改草稿的路」的上界。数一变即红：新开一条路时，
 * 得有人回来判一句「这一类进不进得了 N」，并把它加进上面那张表。
 * 不设这一格的话，手写的分类表会给后来的写口签一张免检——「不在表里」与「查过了」
 * 在读数上是同一个样子。
 */
const MARK_DIRTY_CALLS = 11;

await ask('③ 分母闸：push.js 的 markDirty() 调用点数没变', () => {
  const text = readFileSync(join(pages, 'push.js'), 'utf8');
  const hits = (text.match(/(?<![\w$.])markDirty\(/g) || []).length;
  if (!hits) {
    throw new Error('一处 markDirty( 也没数到，取法已经不认得 push.js 的写法了');
  }
  notes.push('③ push.js 里 markDirty() 调用点共 ' + hits + ' 处（钉着 ' + MARK_DIRTY_CALLS + '）');
  if (hits !== MARK_DIRTY_CALLS) {
    throw new Error('markDirty() 调用点从 ' + MARK_DIRTY_CALLS + ' 处变成了 ' + hits
      + ' 处。本页多了（或少了）一条改草稿的路：先判它进不进得了 N，'
      + '把那一类补进本尺的分类表，再改这个数');
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of notes) console.log('  读数：' + line);
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
