/**
 * 选了深色／浅色：当场换肤，并且刷新之后还在
 *
 * 偏好存在当前浏览器里（localStorage），不进服务端配置——外观是「这台浏览器、这双眼睛」
 * 的事，不是机器人配置。这里真跑 theme.js 那一份读写：假 localStorage 与
 * document.documentElement 之外，取值归一、写入、重放都是产品码。
 *
 * 每格自带反向那一半：读出错、读出别的值一律按跟随系统，不许猜一个主题锁住页面。
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ThemeSwitchFrontendTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

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

// —— 假 localStorage：可注入读失败，模拟隐私模式或被策略禁用 ——
const bag = new Map();
let failRead = false;
let failWrite = false;

globalThis.localStorage = {
  getItem(k) {
    if (failRead) throw new Error('storage blocked');
    return bag.has(k) ? bag.get(k) : null;
  },
  setItem(k, v) {
    if (failWrite) throw new Error('storage blocked');
    bag.set(k, String(v));
  },
  removeItem(k) {
    if (failWrite) throw new Error('storage blocked');
    bag.delete(k);
  },
};

// —— 假根元素：只记 data-theme 那一手 ——
const attrs = new Map();
globalThis.document = {
  documentElement: {
    setAttribute(k, v) {
      attrs.set(k, String(v));
    },
    removeAttribute(k) {
      attrs.delete(k);
    },
    getAttribute(k) {
      return attrs.has(k) ? attrs.get(k) : null;
    },
  },
};

const themeUrl = new URL('../../../main/resources/config-ui/theme.js', import.meta.url).href;
let theme = null;
try {
  theme = await import(themeUrl);
} catch (err) {
  failures.push('载入 theme.js 就抛了：' + err.message);
}

function reset() {
  bag.clear();
  attrs.clear();
  failRead = false;
  failWrite = false;
}

/** 模拟刷新：属性树清空，偏好还在本地，照它重放一遍 */
function replay() {
  attrs.clear();
  const v = theme.readTheme();
  theme.applyTheme(v);
  return v;
}

await ask('① 选深色：当场设上 data-theme=dark（系统是浅色也压得住）', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme('dark');
  same(attrs.get('data-theme'), 'dark', '选深色之后的 data-theme');
});

await ask('② 选深色：写进本浏览器，刷新后还是深色', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme('dark');
  same(bag.get(theme.THEME_KEY), 'dark', '本地那一份');
  const v = replay();
  same(v, 'dark', '刷新后读回的偏好');
  same(attrs.get('data-theme'), 'dark', '刷新后重放出的 data-theme');
});

await ask('③ 选浅色：设上 data-theme=light，刷新后还是浅色', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme('light');
  same(attrs.get('data-theme'), 'light', '选浅色之后的 data-theme');
  same(bag.get(theme.THEME_KEY), 'light', '本地那一份');
  const v = replay();
  same(v, 'light', '刷新后读回的偏好');
  same(attrs.get('data-theme'), 'light', '刷新后重放出的 data-theme');
});

await ask('④ 读出错、读出别的值：一律按跟随系统，不锁主题', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  failRead = true;
  same(theme.readTheme(), 'auto', '读出错时');
  failRead = false;
  bag.set(theme.THEME_KEY, 'neither');
  same(theme.readTheme(), 'auto', '读出坏值时');
  theme.applyTheme('neither');
  same(attrs.has('data-theme'), false, '拿坏值去用时不许设上 data-theme');
});

await ask('⑤ 存不下也要当场换肤，只是刷新后回到跟随系统', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  failWrite = true;
  theme.applyTheme('dark');
  same(attrs.get('data-theme'), 'dark', '存不下时当场设上的 data-theme');
  failWrite = false;
  const v = replay();
  same(v, 'auto', '存不下之后刷新读回的偏好');
  same(attrs.has('data-theme'), false, '存不下之后刷新重放不设 data-theme');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) {
  console.log('  红：' + line);
}
process.exit(failures.length ? 1 : 0);
