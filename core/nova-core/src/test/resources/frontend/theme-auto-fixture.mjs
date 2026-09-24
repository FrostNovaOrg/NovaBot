/**
 * 选回跟随系统：解锁，不再锁在上一次手动选的那一个
 *
 * 「跟随系统」在页面上的表现是<b>不设</b> data-theme——不删掉那一个属性的话，
 * 页面会一直停在上一次手动选的深色（或浅色），系统怎么变都不跟着走。
 * 本地那份偏好也要一起清掉：留着的话，下一次打开又会把人拽回手动那一档。
 *
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

const bag = new Map();
globalThis.localStorage = {
  getItem(k) {
    return bag.has(k) ? bag.get(k) : null;
  },
  setItem(k, v) {
    bag.set(k, String(v));
  },
  removeItem(k) {
    bag.delete(k);
  },
};

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
}

await ask('① 选过深色再选回跟随系统：去掉 data-theme，不再锁在深色', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme('dark');
  theme.applyTheme('auto');
  same(attrs.has('data-theme'), false, '选回跟随系统之后的 data-theme');
});

await ask('② 选回跟随系统：本地那份一起清掉，下次打开不会再拽回手动', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme('dark');
  theme.applyTheme('auto');
  same(bag.has(theme.THEME_KEY), false, '本地那一份');
  same(theme.readTheme(), 'auto', '再读一次的偏好');
});

await ask('③ 从没设过偏好：不设 data-theme，页面跟系统', async () => {
  reset();
  if (!theme) throw new Error('theme.js 没载入，无从量起');
  theme.applyTheme(theme.readTheme());
  same(attrs.has('data-theme'), false, '没设过偏好时的 data-theme');
  same(bag.has(theme.THEME_KEY), false, '没设过偏好时的本地那一份');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) {
  console.log('  红：' + line);
}
process.exit(failures.length ? 1 : 0);
