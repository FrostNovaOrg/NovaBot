/**
 * 配了部署前缀时，控制台前端的请求打到带前缀的路径上
 *
 * 服务端吐控制台页时把前缀写进 <meta name="nova-prefix">，core.js 从那里取出前缀常量，
 * api() 与各处地址都经它拼。这里真跑 core.js：假 document 只供那一个 meta，假 fetch 记下请求的地址。
 * 登录页不加载 core.js，它的落点由 login-model.js 的 landing 按页面里写进去的控制台地址算，一并量。
 *
 * 每格自带反向那一半：没有 meta（没配前缀）时，地址与改前逐字相同。
 *
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ConsolePrefixFrontendTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

const sent = [];
globalThis.fetch = (url) => {
  sent.push(url);
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true})});
};
globalThis.location = {reload: () => {}};

const coreUrl = new URL('../../../main/resources/config-ui/core.js', import.meta.url).href;
const modelUrl = new URL('../../../main/resources/config-ui/login-model.js', import.meta.url).href;

/** 页面上写着 content 的那个 meta；content 为 null 时页面上没有它 */
function fakeDocument(content) {
  return {
    querySelector: s => (s === 'meta[name="nova-prefix"]' && content !== null
      ? {content}
      : null),
  };
}

// 同一份 core.js 按不同的查询串各载一份，前缀常量在载入时取定
globalThis.document = fakeDocument('/novabot');
let prefixed = null;
try {
  prefixed = await import(coreUrl + '?prefixed');
} catch (err) {
  failures.push('载入 core.js 就抛了：' + err.message);
}

globalThis.document = fakeDocument(null);
let bare = null;
try {
  bare = await import(coreUrl + '?bare');
} catch (err) {
  failures.push('载入 core.js 就抛了：' + err.message);
}

delete globalThis.document;
let headless = null;
try {
  headless = await import(coreUrl + '?headless');
} catch (err) {
  failures.push('没有 document 时载入 core.js 就抛了：' + err.message);
}

await ask('① 前缀常量设成 /novabot 时，api(\'/x\') 请求的是 /novabot/config/api/x', async () => {
  if (!prefixed) throw new Error('core.js 没载入，无从量起');
  sent.length = 0;
  await prefixed.api('/x');
  same(sent, ['/novabot/config/api/x'], 'api 请求的地址');
});

await ask('② 没配前缀（页面上没有那个 meta）时，api(\'/x\') 与改前相同，请求 /config/api/x', async () => {
  if (!bare) throw new Error('core.js 没载入，无从量起');
  sent.length = 0;
  await bare.api('/x');
  same(sent, ['/config/api/x'], 'api 请求的地址');
});

await ask('③ 没有 document 时（node 里各夹具那样载入）也照旧请求 /config/api/x', async () => {
  if (!headless) throw new Error('core.js 没载入，无从量起');
  sent.length = 0;
  await headless.api('/x');
  same(sent, ['/config/api/x'], 'api 请求的地址');
});

let model = null;
try {
  model = await import(modelUrl);
} catch (err) {
  failures.push('载入 login-model.js 就抛了：' + err.message);
}

await ask('④ 登录成功的落点带前缀：控制台地址是 /novabot/config 时落到 /novabot/config', async () => {
  if (!model) throw new Error('login-model.js 没载入，无从量起');
  same(model.landing('http://h/novabot/config', '', '/novabot/config'), {reload: false, url: '/novabot/config'},
    '无片段时的落点');
  same(model.landing('http://h/novabot/config#/settings', '#/settings', '/novabot/config').reload, true,
    '同址带片段时须整页重载');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) {
  console.log('  红：' + line);
}
process.exit(failures.length ? 1 : 0);
