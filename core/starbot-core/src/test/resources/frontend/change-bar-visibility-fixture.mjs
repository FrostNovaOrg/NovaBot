/**
 * 底部那条只在「有话说」的时候出现
 *
 * 从前它是常驻页脚：状态栏住在里面，因此哪怕一处没改、一句话没说，屏幕最下面
 * 也一直横着一条空条，还占掉一截高度。现在它出现当且仅当三件事之一成立——
 * 本页有未保存的改动、有保存过等重启的项、状态栏正说着一句话。
 *
 * 判定本身是纯函数 {@code barVisible(n, pending, message)}：<b>只有一份</b>。
 * 抄一份进渲染代码之后，这把尺量的还是那份没人调的判法，而屏幕上跑的是新抄的那一份——
 * 「调用点确实问过它」那一格在 ConfigUiFrontendTest 里。
 *
 * 本尺<b>真跑</b> core.js：假 DOM 之外，markDirty／say 用的都是产品码那一份。
 * 每格自带反向那一半（该出的时候真出、该藏的时候真藏）——只量一个方向的话，
 * 把判定改成恒真或恒假各有一半格子照样绿。
 *
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ChangeBarVisibilityTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

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

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假页面 ——

const nodes = {};

function node(selector) {
  const id = String(selector).replace(/^#/, '');
  if (!nodes[id]) {
    nodes[id] = {id, textContent: '', className: '', disabled: false, style: {display: ''}};
  }
  return nodes[id];
}

/** 挂在 <html> 上的那几个类。藏与不藏、以及页底那截留白，都由它一处决定 */
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

globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({})});

/** 此刻条出没出来 */
function barShown() {
  return !htmlClasses.has('nobar');
}

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

/** 这一页此刻报几处改动 */
let reported = 0;

/** 这一页的保存被按了几次 */
let saved = 0;

/** 回到「设置页之外的一个插件页，一处没改、没有待重启项、状态栏空着」 */
function reset() {
  reported = 0;
  saved = 0;
  htmlClasses.clear();
  store.tab = '假页';
  store.dirty = {};
  store.effects = {};
  store.restartPending = [];
  node('#status-text').textContent = '';
}

if (core) {
  core.registerChangeSource('假页', {
    displayName: '假页',
    changeCount: () => reported,
    save: () => {
      saved++;
      reported = 0;
      core.say('已保存', 'ok');
    },
    reload: () => {},
  });
}

await ask('① 一处没改、没有待重启项、状态栏空着：整条不出来', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  core.markDirty();
  const quiet = barShown();

  // 反向那一半：同样这一页，只让它报出一处改动，条必须出得来。
  // 少了这半截，把判定改成恒假这一格照样绿
  reported = 1;
  core.markDirty();

  same({'什么都没有时': quiet, '报出一处改动后': barShown(), '条上的字': node('#change-count').textContent},
    {'什么都没有时': false, '报出一处改动后': true, '条上的字': '1 处改动'}, '空条');
});

await ask('② 有未保存的改动：条出来，保存按得动', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  reported = 1;
  core.markDirty();
  const withChange = {
    '条在': barShown(),
    '条上的字': node('#change-count').textContent,
    '保存按钮': node('#save').style.display,
    '保存按得动': node('#save').disabled === false,
  };

  // 反向那一半：改动回到零，条跟着没
  reported = 0;
  core.markDirty();

  same(Object.assign(withChange, {'改动归零后条还在吗': barShown()}), {
    '条在': true, '条上的字': '1 处改动', '保存按钮': '', '保存按得动': true,
    '改动归零后条还在吗': false,
  }, '有改动');
});

await ask('③ 没有未保存的改动、有两项等重启：条出来，写「2 处改动需重启生效」', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  store.restartPending = ['a', 'b'];
  core.markDirty();
  const pending = {'条在': barShown(), '条上的字': node('#change-count').textContent};

  // 反向那一半：那份名单空掉，条跟着没。
  // 这一条挂到重启为止，因此它必须是「名单空了才没」，而不是被别的什么清掉
  store.restartPending = [];
  core.markDirty();

  same(Object.assign(pending, {'名单空掉后条还在吗': barShown()}),
    {'条在': true, '条上的字': '2 处改动需重启生效', '名单空掉后条还在吗': false}, '等重启');
});

await ask('④ 状态栏说着话的时候条在；话清掉才没；错误那句不自动清，条就跟着留', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  core.say('载入中…');
  const talking = {'说着话时': barShown(), '说的是': node('#status-text').textContent};

  core.say('');
  const hushed = barShown();

  // 错误那句不自动消失（只有 ok 那种才定时清），条因此得一直留着
  core.say('保存失败：写不进去', 'err');
  const complaining = barShown();

  same(Object.assign(talking, {'话清掉后': hushed, '报错时': complaining}),
    {'说着话时': true, '说的是': '载入中…', '话清掉后': false, '报错时': true}, '状态栏');
});

await ask('⑤ 存完之后：那句「已保存」还在时条在，话清掉、改动归零，条才没', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  reported = 2;
  core.markDirty();
  const before = {'存之前条在': barShown(), '存之前的字': node('#change-count').textContent};

  core.currentChangeSource().save();
  core.markDirty();
  const justSaved = {'存完那一刻条在': barShown(), '状态栏说的': node('#status-text').textContent};

  core.say('');
  const settled = {'话清掉后条在': barShown(), '条上的字': node('#change-count').textContent,
    '存了几次': saved};

  same(Object.assign(before, justSaved, settled), {
    '存之前条在': true, '存之前的字': '2 处改动',
    '存完那一刻条在': true, '状态栏说的': '已保存',
    '话清掉后条在': false, '条上的字': '', '存了几次': 1,
  }, '存完');
});

await ask('⑥ 没有供数方的那些页：改动数恒为零，条只在说话或等重启时出现', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  reset();
  store.tab = '一个没登记过供数方的页';
  reported = 7;
  core.markDirty();
  const idle = {'存去哪': core.saveTarget(), '改动数': core.changeCount(), '条在': barShown()};

  core.say('这一页出错了：取不到', 'err');
  const talking = barShown();

  core.say('');
  store.restartPending = ['a'];
  core.markDirty();
  const pending = {'条在': barShown(), '条上的字': node('#change-count').textContent};

  same(Object.assign(idle, {'说话时条在': talking, '等重启时': pending}), {
    '存去哪': null, '改动数': 0, '条在': false,
    '说话时条在': true,
    '等重启时': {'条在': true, '条上的字': '1 处改动需重启生效'},
  }, '无供数方的页');
});

await ask('⑦ 判定是一份纯函数：三条各自成立，都不成立才藏', () => {
  if (!core) throw new Error('core.js 没载入，无从量起');
  if (typeof core.barVisible !== 'function') {
    throw new Error('core.js 没有导出 barVisible，藏不藏的判定没有单独的一份');
  }
  const table = [
    {arg: [0, 0, ''], want: false, why: '三条都不成立'},
    {arg: [1, 0, ''], want: true, why: '只有未保存的改动'},
    {arg: [0, 1, ''], want: true, why: '只有等重启的项'},
    {arg: [0, 0, '已保存'], want: true, why: '只有一句话'},
    {arg: [3, 2, '出错了'], want: true, why: '三条都成立'},
    {arg: [0, 0, null], want: false, why: '状态栏是空的（null）'},
    {arg: [0, 0, undefined], want: false, why: '状态栏是空的（undefined）'},
  ];
  notes.push('⑦ 纯函数逐档共 ' + table.length + ' 档（其中该藏的 '
    + table.filter(item => !item.want).length + ' 档）');
  const bad = [];
  for (const item of table) {
    const got = core.barVisible(...item.arg);
    if (got !== item.want) {
      bad.push(JSON.stringify(item.arg) + '（' + item.why + '）得到 ' + got + '，应为 ' + item.want);
    }
  }
  if (bad.length) {
    throw new Error('这几档对不上：\n    ' + bad.join('\n    '));
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of notes) console.log('  读数：' + line);
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
