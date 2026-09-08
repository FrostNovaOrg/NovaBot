/**
 * 推送页是改动条的供数方：这一份状态跟着推送页走，不再摆在宿主的 store 上
 *
 * 底部改动条要答「改了几处」。这份账从前记在宿主的 store 上（pushData／pushSaved），
 * 由宿主自己按主播逐条比——而推送页随本插件走，插件卸掉之后宿主还在按一份取不到的
 * 东西算数。现在这三件事（报几处、怎么存、怎么重取）由本页自己答，宿主只管问。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，reload／changeCount／save 用的都是产品码那一份。
 * 只 includes 一段字样是不行的——报数写错一位、存盘序列化换了把尺，字样照样在。
 * 末一问把本页登记进宿主的改动条，从「改一项」一路量到屏幕上那句「1 处改动」：
 * 少了它，本页自己算得再对，宿主那一条也可能根本没接上。
 *
 * 六问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ChangeBarPushSourceTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');

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

// —— 假页面与假接口 ——

const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

/** 这一跑发出去的每一条请求，按序 */
const requests = [];

/** 下一趟 GET /datasource 回什么。第 ⑥ 问要把它换成一份坏文件 */
let datasourceBody = '';

/** 挂在 <html> 上的那几个类。底部那条藏不藏由它一处定，见宿主 core.js 的 paintBar */
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
  const method = ((opt || {}).method || 'GET').toUpperCase();
  requests.push({path, method, body: (opt || {}).body});
  const answer = method === 'GET'
    ? {content: datasourceBody}
    : {success: true, message: '已保存'};
  return Promise.resolve({status: 200, json: () => Promise.resolve(answer)});
};

/** 老使用者的推送配置：两位主播，第一位推到一个群 */
function datasource() {
  return [
    {
      platform: 'p', uid: 1, _uname: '甲', enabled: true,
      targets: [{
        platform: 'q', type: 1, num: 12345, enabled: true,
        messages: [{handler: 'x.LiveOn', params: {message: '开播了'}}],
      }],
    },
    {platform: 'p', uid: 2, _uname: '乙', enabled: true, targets: []},
  ];
}

let push = null;
let core = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  core = await import('./core.js');
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入 push.js／core.js 就抛了：' + err.message);
}

/** 把一份干净的推送配置载进来，回到「刚打开这一页」那个状态 */
async function freshLoad() {
  datasourceBody = JSON.stringify(datasource());
  requests.length = 0;
  const quiet = await push.reload();
  return quiet;
}

await ask('① 推送页把「报几处、怎么存、怎么重取」三件都导出来了', () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const missing = ['changeCount', 'save', 'reload'].filter(name => typeof push[name] !== 'function');
  if (missing.length) {
    throw new Error('少了这几件：' + missing.join('、') + '，宿主问不着，改动条只好一直显示 0');
  }
});

await ask('② 刚载入时零处改动，且这一份是自己去取的', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const quiet = await freshLoad();
  same(
    {'改动数': push.changeCount(), '取的接口': requests.map(item => item.method + ' ' + item.path),
      '有话要说': quiet === false},
    {'改动数': 0, '取的接口': ['GET /config/api/datasource'], '有话要说': false},
    '刚载入');
});

await ask('③ 按主播逐条比：改一位算 1、再改一位算 2、改回原样算 0', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await freshLoad();
  const users = push.pushEntries();

  users[0].targets[0].enabled = false;
  const one = push.changeCount();

  users[1].enabled = false;
  const two = push.changeCount();

  // 只给人看的那几栏不算改动：昵称是查回来的，改了它文件里一个字节也不会变
  users[0]._uname = '甲甲';
  const stillTwo = push.changeCount();

  users[0].targets[0].enabled = true;
  users[1].enabled = true;
  const back = push.changeCount();

  same({'改一位': one, '再改一位': two, '只动昵称': stillTwo, '改回原样': back},
    {'改一位': 1, '再改一位': 2, '只动昵称': 2, '改回原样': 0}, '逐条比');
});

await ask('④ 按保存：写出去的正是本页序列化的那一份，存完归零', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await freshLoad();
  const users = push.pushEntries();
  users[0].targets[0].enabled = false;

  const wanted = push.serializePush();
  requests.length = 0;
  await push.save();

  const posts = requests.filter(item => item.method === 'POST');
  if (posts.length !== 1) {
    throw new Error('按一次保存发出了 ' + posts.length + ' 条 POST，应恰 1 条');
  }
  const sent = JSON.parse(posts[0].body || '{}').content;
  same({
    '存去哪': posts[0].path,
    '写出去的与序列化的一致': sent === wanted,
    '写出去的还带着昵称': String(sent).includes('_uname'),
    '存完的改动数': push.changeCount(),
  }, {
    '存去哪': '/config/api/datasource',
    '写出去的与序列化的一致': true,
    '写出去的还带着昵称': false,
    '存完的改动数': 0,
  }, '保存');
});

await ask('⑤ 登记进宿主的改动条：改一项，屏幕上写「1 处改动」、保存按钮出得来', async () => {
  if (!push || !core) throw new Error('push.js／core.js 没载入，无从量起');
  core.registerChangeSource('push', {
    displayName: '推送',
    changeCount: () => push.changeCount(),
    save: () => push.save(),
    reload: () => push.reload(),
  });

  await freshLoad();
  store.tab = 'push';
  store.dirty = {};
  store.restartPending = [];

  core.markDirty();
  const clean = node('#change-count').textContent;

  push.pushEntries()[0].targets[0].enabled = false;
  core.markDirty();

  same({
    '存去哪': core.saveTarget(),
    '没改动时': clean,
    '改一项后': node('#change-count').textContent,
    '保存按钮': node('#save').style.display,
    '保存按得动': node('#save').disabled === false,
  }, {
    '存去哪': 'push',
    '没改动时': '',
    '改一项后': '1 处改动',
    '保存按钮': '',
    '保存按得动': true,
  }, '挂上宿主');
});

await ask('⑥ 文件不合法时：本页自己把话说清楚，不抛、不猜数', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  node('#status-text').textContent = '';
  datasourceBody = '{这不是 JSON';
  const quiet = await push.reload();
  const said = String(node('#status-text').textContent);

  same({
    '要不要替它清状态栏': quiet,
    '说了话': said.includes('datasource.json'),
    '改动数': push.changeCount(),
    '手里那份': push.pushEntries().length,
  }, {
    '要不要替它清状态栏': false,
    '说了话': true,
    '改动数': 0,
    '手里那份': 0,
  }, '坏文件');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
