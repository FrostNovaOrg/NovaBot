/**
 * 登录页的失败提示：读屏会念出来，密码被拒会被标成输错
 *
 * 两件只有读屏使用者踩得到的故障：
 *   #msg 没有念出来的属性时，密码被拒、连不上服务器都只发生在「看得见」的层上，
 *   听读屏的人按了登录之后一片安静，不知道刚才那一下成没成；
 *   密码框不带 aria-invalid、错误说明也没挂到框上（aria-describedby）时，
 *   读屏只念「密码 编辑框」，不念它错了、错在哪句话上。
 * 服务端被拒故意只回「密码或验证码不正确」、不说是哪一格（见 ConfigUiAuthService），
 * 界面也不替它分：两格一起标。只有回话说密码或动态码被拒（credentialsRejected）才标：
 * 已经被锁、服务器正忙、通行密钥失败与连不上服务器都不是输错，不标；
 * 再提交或改走通行密钥时，提示清空，标记也一并撤掉，动态码格藏着也撤。
 *
 * 本尺把 login.html 当文本读进来（照 login-reveal-wiring-fixture 的做法），
 * 切出标记函数与提交、通行密钥两支真执行。由 LoginFailureLiveTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '../../../main/resources/config-ui');
const login = readFileSync(join(ui, 'login.html'), 'utf8');

const failures = [];
let checks = 0;

async function section(what, run) {
  try {
    await run();
  } catch (e) {
    checks++;
    failures.push(what + ' 这一段抛了：' + (e && e.message ? e.message : String(e)));
  }
}

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
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

// —— 假节点：够提交与通行密钥两支跑 ——

function fakeNode(id) {
  const node = {
    id, disabled: false, value: '', textContent: '', className: '', attrs: {}, listeners: {},
    setAttribute(name, v) { node.attrs[name] = String(v); },
    removeAttribute(name) { delete node.attrs[name]; },
    getAttribute(name) { return Object.prototype.hasOwnProperty.call(node.attrs, name) ? node.attrs[name] : null; },
    addEventListener(type, fn) { (node.listeners[type] = node.listeners[type] || []).push(fn); },
    focus() {},
    classList: {
      contains: name => String(node.className).split(/\s+/).includes(name),
      add(name) { if (!node.classList.contains(name)) node.className = (node.className + ' ' + name).trim(); },
      remove(name) { node.className = String(node.className).split(/\s+/).filter(Boolean).filter(n => n !== name).join(' '); },
    },
  };
  return node;
}

function pageNodes() {
  const byId = {};
  const $ = sel => {
    const id = String(sel).replace(/^#/, '');
    if (!byId[id]) byId[id] = fakeNode(id);
    return byId[id];
  };
  // 与登录页初画一致：动态码那一格默认不在页上
  $('code-field').className = 'hidden';
  return {$, byId};
}

// 提交那一支按这几档回：被拒／被拒且这一趟锁上／已被锁／正忙／连不上／悬着（只让提交开头那段同步跑完）
const answer = body => () => Promise.resolve({ok: true, json: () => Promise.resolve(body)});
const rejected = answer({success: false, message: '密码或验证码不正确', lockedSeconds: 0, credentialsRejected: true});
const rejectedAndLocked = answer({success: false, message: '密码或验证码不正确', lockedSeconds: 900, credentialsRejected: true});
const lockedOut = answer({success: false, message: '登录失败次数过多，请在 15 分钟后重试', lockedSeconds: 900, credentialsRejected: false});
const busy = answer({success: false, message: '服务器正忙，请稍后重试', lockedSeconds: 0, credentialsRejected: false});
const unreachable = () => Promise.reject(new Error('断网'));
const pending = () => new Promise(() => {});

// 被拒之后落在输入框上的两样标记
function marksOn(node) {
  return node.attrs['aria-invalid'] === 'true' && node.attrs['aria-describedby'] === 'msg';
}

// —— 切出三段：标记函数（改后才在）、提交支、通行密钥支 ——

const markSrc = bracedFrom(login, 'function markInvalidCredentials');
const submitSrc = bracedFrom(login, "$('#form').addEventListener('submit'");
const passkeySrc = bracedFrom(login, 'async function loginWithPasskey');

function buildSubmit(fetchImpl, ctx) {
  if (!submitSrc) throw new Error('切不出提交那一支');
  // 配平截到箭头函数体那只 } 为止，addEventListener( 的另一半括号在这里补上
  new Function('$', 'fetch', 'paint', 'tick', 'lockedSeconds', 'CONSOLE',
    (markSrc ? markSrc + '\n' : '') + submitSrc + ');')(ctx.$, fetchImpl, () => {}, () => {}, 0, '/config');
  const handler = (ctx.byId.form.listeners.submit || [])[0];
  if (!handler) throw new Error('提交支没挂上');
  return handler;
}

function buildPasskey(fetchImpl, ctx) {
  if (!passkeySrc) throw new Error('切不出通行密钥那一支');
  // loginWithPasskey 是声明不挂监听，求值后把函数本身交回来
  return new Function('$', 'fetch', 'paint', 'toBuffer', 'toBase64Url', 'enterConsole', 'CONSOLE',
    (markSrc ? markSrc + '\n' : '') + passkeySrc + '\nreturn loginWithPasskey;')(
    ctx.$, fetchImpl, () => {}, () => {}, () => '', () => {}, '/config');
}

const submitEvent = {preventDefault() {}};

// 一支提交从头到尾是同一只闭包，回包档位要在建好之后换：把 fetch 做成可换挡的
function dispatchFetch() {
  const state = {impl: rejected};
  const fn = (...args) => state.impl(...args);
  fn.set = impl => { state.impl = impl; };
  return fn;
}

// ---- (a) #msg 一出字读屏就会念 ----

await section('#msg 带念出来的属性', async () => {
  const m = login.match(/<div\b[^>]*\bid="msg"[^>]*>/);
  eq(!!m, true, '登录卡里找得到 #msg 那一格');
  if (m) {
    eq(/role="alert"|aria-live=/.test(m[0]), true, '#msg 带 role="alert" 或 aria-live，出一行字读屏就念');
  }
});

// ---- (b) 密码被拒：密码框标错，动态码在页上时一起标 ----

await section('密码被拒标错', async () => {
  const ctx = pageNodes();
  const handler = buildSubmit(rejected, ctx);
  await handler(submitEvent);
  eq(ctx.byId.msg.textContent, '密码或验证码不正确', '被拒的那句话上了屏');
  eq(marksOn(ctx.byId.password), true, '密码框有 aria-invalid 且 aria-describedby 指向 msg');
  eq(marksOn(ctx.byId.code), false, '动态码不在页上，不标它');
});

await section('动态码在页上时一起标', async () => {
  const ctx = pageNodes();
  ctx.$('code-field').className = '';
  const handler = buildSubmit(rejected, ctx);
  await handler(submitEvent);
  eq(marksOn(ctx.byId.password), true, '密码框照旧标错');
  eq(marksOn(ctx.byId.code), true, '动态码在页上，与密码框一起标（不替服务端分是哪一格）');
});

// ---- (c) 再提交一次：提示清空，标记撤掉 ----

await section('再提交撤掉标记', async () => {
  const ctx = pageNodes();
  const fetchCtl = dispatchFetch();
  const handler = buildSubmit(fetchCtl, ctx);
  await handler(submitEvent);
  if (marksOn(ctx.byId.password) !== true) {
    eq(marksOn(ctx.byId.password), true, '前提：上一次被拒已标错');
    return;
  }
  // 第二次提交让请求悬着，只量提交开头那段清空
  fetchCtl.set(pending);
  handler(submitEvent);
  await new Promise(resolve => setTimeout(resolve, 0));
  eq(ctx.byId.msg.textContent, '', '再提交时提示清空');
  eq(marksOn(ctx.byId.password), false, '提示清空时密码框的标记一并撤掉');
});

// ---- (d) 连不上服务器：不是输错，不标 ----

await section('连不上服务器不标', async () => {
  const ctx = pageNodes();
  const handler = buildSubmit(unreachable, ctx);
  await handler(submitEvent);
  eq(ctx.byId.msg.textContent, '无法连接到服务器', '连不上的那句话上了屏');
  eq(ctx.byId.password.attrs['aria-invalid'] === 'true', false, '连不上不是输错，密码框不带 aria-invalid');
});

// ---- (e) 改走通行密钥：提示清空，标记也撤掉 ——

await section('改走通行密钥撤掉标记', async () => {
  const ctx = pageNodes();
  await buildSubmit(rejected, ctx)(submitEvent);
  if (marksOn(ctx.byId.password) !== true) {
    eq(marksOn(ctx.byId.password), true, '前提：密码被拒已标错');
    return;
  }
  // 让取挑战那一步悬着，只量开头那段清空
  buildPasskey(pending, ctx)();
  await new Promise(resolve => setTimeout(resolve, 0));
  eq(ctx.byId.msg.textContent, '', '点通行密钥时提示清空');
  eq(marksOn(ctx.byId.password), false, '提示清空时标记一并撤掉');
});

// ---- (f) 第 N 次输错、这一趟正好锁上：仍是密码被拒，照样标 ----
// 抓的故障：拿 lockedSeconds 分来路，把锁上的那一趟当成「被锁」不标，读屏不念刚才输错了

await section('输错且这一趟锁上仍标', async () => {
  const ctx = pageNodes();
  await buildSubmit(rejectedAndLocked, ctx)(submitEvent);
  eq(marksOn(ctx.byId.password), true, '这一趟核过密码且不对，密码框带 aria-invalid');
});

// ---- (g) 已经被锁、服务器正忙：这一趟没核密码，不标 ----
// 抓的故障：凡失败都标，服务器正忙时读屏也把密码框念成「无效」，人以为自己密码错了

await section('已经被锁不标', async () => {
  const ctx = pageNodes();
  ctx.$('code-field').className = '';
  await buildSubmit(lockedOut, ctx)(submitEvent);
  eq(ctx.byId.msg.textContent, '登录失败次数过多，请在 15 分钟后重试', '被锁的那句话上了屏');
  eq(ctx.byId.password.attrs['aria-invalid'] === 'true', false, '被锁时没核密码，密码框不带 aria-invalid');
  eq(ctx.byId.code.attrs['aria-invalid'] === 'true', false, '被锁时动态码格也不带 aria-invalid');
});

await section('服务器正忙不标', async () => {
  const ctx = pageNodes();
  ctx.$('code-field').className = '';
  await buildSubmit(busy, ctx)(submitEvent);
  eq(ctx.byId.msg.textContent, '服务器正忙，请稍后重试', '正忙的那句话上了屏');
  eq(ctx.byId.password.attrs['aria-invalid'] === 'true', false, '正忙时没核密码，密码框不带 aria-invalid');
  eq(ctx.byId.code.attrs['aria-invalid'] === 'true', false, '正忙时动态码格也不带 aria-invalid');
});

// ---- (h) 动态码格标上之后被藏起来，再撤：两格都撤 ----
// 抓的故障：撤标记只撤看得见的格，动态码格藏起来以后标记留在上面，再显示时一上来就念「无效」

await section('动态码格藏起来以后再撤', async () => {
  const ctx = pageNodes();
  ctx.$('code-field').className = '';
  const fetchCtl = dispatchFetch();
  const handler = buildSubmit(fetchCtl, ctx);
  await handler(submitEvent);
  if (marksOn(ctx.byId.code) !== true) {
    eq(marksOn(ctx.byId.code), true, '前提：动态码在页上时被拒已标错');
    return;
  }
  ctx.$('code-field').className = 'hidden';
  fetchCtl.set(pending);
  handler(submitEvent);
  await new Promise(resolve => setTimeout(resolve, 0));
  eq(marksOn(ctx.byId.password), false, '再提交时密码框的标记撤掉');
  eq(ctx.byId.code.attrs['aria-invalid'] === 'true', false, '动态码格藏着也撤掉 aria-invalid');
  eq(ctx.byId.code.attrs['aria-describedby'] === 'msg', false, '动态码格藏着也撤掉 aria-describedby');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
