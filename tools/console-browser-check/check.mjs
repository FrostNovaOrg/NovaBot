// 在真浏览器里把控制台离线演示各页打开一遍：抓脚本异常、找不到的本地资源、白屏、手机宽横向溢出，
// 以及选了的配色没落到页面上（颜色变量取不到、下拉框箭头不跟配色）。
//
// 不占端口、不建套接字：浏览器以 --remote-debugging-pipe 起，调试协议走两条命名管道（FIFO）；
// 页面开在 http://localhost:8000/config/...，那里没有服务，每个请求都由 Fetch 域接住，
// 从导出目录读文件回给浏览器。发往别处的请求一律拦下，不出网。
//
// 用法：node check.mjs --demo <导出目录> --chrome <浏览器> --out <结果目录> --profile <空的临时目录>
// 退码：0 全过；1 有失败；2 跑不起来。

import {execFileSync, spawn} from 'node:child_process';
import fs from 'node:fs';
import {EventEmitter} from 'node:events';
import {deflateSync, inflateSync} from 'node:zlib';
import path from 'node:path';
import {parseArgs} from 'node:util';

const ORIGIN = 'http://localhost:8000';
const SETTLE_MS = 500;
const SETTLE_LIMIT_MS = 15000;
const COMMAND_LIMIT_MS = 30000;
const SHOT_LIMIT_MS = 15000;
// 极长的页面截到这里为止，免得截图超出浏览器的纹理上限
const SHOT_MAX_HEIGHT = 16000;
// macOS 的截图按实际输出像素分块；布局与设备像素比仍用本轮的原值。
const SHOT_TILE_PIXELS = 480000;

const SCREENS = [
  ['home', '/config/#/home'],
  ['push', '/config/#/push'],
  ['streamers', '/config/#/streamers'],
  ['streamer', '/config/#/streamers/bilibili/990000000102'],
  ['log', '/config/#/log'],
  ['links', '/config/#/links'],
  ['settings', '/config/#/settings'],
  ['setup', '/config/#/setup'],
  ['login', '/config/login/'],
  ['napcat-bootstrap', '/config/napcat-bootstrap/'],
];
const WIDTHS = [
  {name: '1440', width: 1440, height: 900, mobile: false, scale: 1},
  {name: '390', width: 390, height: 844, mobile: true, scale: 2},
];
const COLORS = ['light', 'dark'];
// 配色：默认的粉蓝各页都看；经典、暖阳只看首页与主播详情。配色只换颜色、不改排布，
// 各页三套都看一遍是三倍的轮数，而多出来的那些轮抓的是同一类毛病
const PALETTES = ['pink-blue', 'classic', 'warm'];
const PALETTE_SCREENS = new Set(['home', 'streamer']);

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};

class CannotRun extends Error {}
/** 结果目录里已有同名文件：整轮停下，不在某一轮里当作失败记掉 */
class NameTaken extends CannotRun {}

/** 结果文件一律新建，不覆盖：同名的已经在那里就停下，那个文件不动。 */
function openNew(file) {
  try {
    return fs.openSync(file, 'wx');
  } catch (e) {
    if (e.code === 'EEXIST') throw new NameTaken(`结果目录里已经有同名的文件，没有覆盖它：${path.resolve(file)}`);
    throw e;
  }
}

function writeNew(file, data) {
  const fd = openNew(file);
  try {
    fs.writeFileSync(fd, data);
  } finally {
    fs.closeSync(fd);
  }
}

function main() {
  const {values} = parseArgs({options: {
    demo: {type: 'string'}, chrome: {type: 'string'}, out: {type: 'string'}, profile: {type: 'string'},
  }});
  for (const name of ['demo', 'chrome', 'out', 'profile']) {
    if (!values[name]) throw new CannotRun(`缺少参数 --${name}`);
  }
  const demo = path.resolve(values.demo);
  if (!fs.existsSync(path.join(demo, 'config/index.html'))) {
    throw new CannotRun(`导出目录里没有 config/index.html：${demo}`);
  }
  fs.mkdirSync(values.out, {recursive: true});
  // 结果文件按 --out 原样拼路径写；run.sh 先进入结果目录再传 .，写入就落在它核过的那个目录里。
  // 浏览器按自己被起的路径找随附文件，经符号链接或带 .. 的路径起会找不到，所以用真实路径。
  let chrome;
  try {
    chrome = fs.realpathSync(values.chrome);
  } catch (e) {
    throw new CannotRun(`浏览器不存在：${values.chrome}`);
  }
  return run({demo, chrome, out: values.out, profile: path.resolve(values.profile)});
}

/** 调试协议客户端：每条消息是一段 JSON，以 \0 结尾 */
class Cdp {
  constructor(input, output) {
    this.output = output;
    this.nextId = 1;
    this.pending = new Map();
    this.sessions = new Map();
    this.closed = null;
    let buffer = Buffer.alloc(0);
    input.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      let end;
      while ((end = buffer.indexOf(0)) >= 0) {
        const text = buffer.subarray(0, end).toString('utf8');
        buffer = buffer.subarray(end + 1);
        this.receive(JSON.parse(text));
      }
    });
  }

  receive(message) {
    if (message.id !== undefined) {
      const waiter = this.pending.get(message.id);
      if (!waiter) return;
      this.pending.delete(message.id);
      clearTimeout(waiter.timer);
      if (message.error) waiter.reject(new Error(`${waiter.method}: ${message.error.message}`));
      else waiter.resolve(message.result);
      return;
    }
    const handler = this.sessions.get(message.sessionId);
    if (handler) handler(message.method, message.params);
  }

  send(method, params = {}, sessionId, limit = COMMAND_LIMIT_MS) {
    if (this.closed) return Promise.reject(this.closed);
    const id = this.nextId++;
    const message = {id, method, params};
    if (sessionId) message.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method}: ${limit / 1000} 秒没有回应`));
      }, limit);
      this.pending.set(id, {method, resolve, reject, timer});
      this.output.write(JSON.stringify(message) + '\0');
    });
  }

  close(reason) {
    this.closed = reason;
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(reason);
    }
    this.pending.clear();
  }
}

/** macOS 上用 net.Socket 读 FIFO 时，大截图响应会卡住；直接非阻塞读取。 */
class FifoReader extends EventEmitter {
  constructor(fd) {
    super();
    this.fd = fd;
    const buffer = Buffer.alloc(65536);
    this.timer = setInterval(() => {
      try {
        // 每次最多读 1 MiB，让协议超时和页面事件也有机会处理。
        for (let total = 0; total < 1024 * 1024;) {
          const n = fs.readSync(fd, buffer, 0, buffer.length, null);
          if (!n) break;
          total += n;
          this.emit('data', Buffer.from(buffer.subarray(0, n)));
        }
      } catch (e) {
        if (e.code !== 'EAGAIN' && e.code !== 'EWOULDBLOCK') this.emit('error', e);
      }
    }, 5);
  }

  destroy() {
    clearInterval(this.timer);
    fs.closeSync(this.fd);
  }
}

/**
 * 起浏览器。调试协议走两条 FIFO：fd 3 浏览器读、fd 4 浏览器写。
 * FIFO 以读写方式打开，另一端还没人时 open 也不会卡住。
 */
async function launch(chrome, profile, log, logFile) {
  fs.mkdirSync(profile);
  const toBrowser = path.join(profile, 'cdp-in');
  const fromBrowser = path.join(profile, 'cdp-out');
  try {
    execFileSync('mkfifo', [toBrowser, fromBrowser]);
  } catch (e) {
    throw new CannotRun(`建不了调试协议用的命名管道（mkfifo）：${e.message}`);
  }
  const {O_RDWR, O_WRONLY} = fs.constants;
  const browserReads = fs.openSync(toBrowser, O_RDWR);
  const weWrite = fs.openSync(toBrowser, O_WRONLY);
  const weRead = fs.openSync(fromBrowser, O_RDWR | fs.constants.O_NONBLOCK);
  const browserWrites = fs.openSync(fromBrowser, O_WRONLY);

  const args = [
    '--headless',
    '--remote-debugging-pipe',
    `--user-data-dir=${path.join(profile, 'user-data')}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-sync',
    '--disable-breakpad',
    '--metrics-recording-only',
    '--password-store=basic',
    '--use-mock-keychain',
    '--mute-audio',
    '--hide-scrollbars',
    '--force-color-profile=srgb',
    '--no-proxy-server',
    // 页面的请求都在 Fetch 域里应答，不需要解析任何域名
    '--host-resolver-rules=MAP * ~NOTFOUND',
  ];
  // macOS 的沙箱不许默认起法派生的子进程
  if (process.platform === 'darwin') args.push('--single-process');
  args.push('about:blank');

  let child;
  try {
    child = spawn(chrome, args, {stdio: ['ignore', log, log, browserReads, browserWrites]});
  } catch (e) {
    throw new CannotRun(`起不了浏览器：${e.message}`);
  }
  for (const fd of [browserReads, browserWrites]) fs.closeSync(fd);
  const input = new FifoReader(weRead);
  const output = fs.createWriteStream(toBrowser, {fd: weWrite});
  const cdp = new Cdp(input, output);
  input.on('error', e => cdp.close(e));
  output.on('error', e => cdp.close(e));
  const exited = new Promise(resolve => {
    child.on('error', error => {
      cdp.close(new CannotRun(`起不了浏览器：${error.message}`));
      resolve();
    });
    child.on('exit', (code, signal) => {
      cdp.close(new CannotRun(`浏览器退出了（退码 ${code}，信号 ${signal}），日志见 ${logFile}`));
      resolve();
    });
  });
  const stop = async () => {
    if (child.exitCode === null && child.signalCode === null) {
      cdp.send('Browser.close').catch(() => {});
      const timer = setTimeout(() => child.kill('SIGKILL'), 5000);
      await exited;
      clearTimeout(timer);
    }
    input.destroy();
    output.destroy();
  };
  try {
    const version = await cdp.send('Browser.getVersion');
    return {cdp, stop, version: version.product};
  } catch (e) {
    await stop();
    // 浏览器退出的说明里已经写了日志在哪，就不再写一遍
    const where = e.message.includes('日志见') ? '' : `；日志见 ${logFile}`;
    throw new CannotRun(`浏览器没有应答调试协议：${e.message}${where}`);
  }
}

/** 把请求地址对到导出目录里的文件；对不上时给 null */
function localFile(demo, pathname) {
  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch (e) {
    return null;
  }
  const file = path.resolve(demo, '.' + decoded);
  if (file !== demo && !file.startsWith(demo + path.sep)) return null;
  let stat;
  try {
    stat = fs.statSync(file);
  } catch (e) {
    return null;
  }
  if (stat.isDirectory()) {
    if (!pathname.endsWith('/')) return {redirect: pathname + '/'};
    return fs.existsSync(path.join(file, 'index.html')) ? {file: path.join(file, 'index.html')} : null;
  }
  return stat.isFile() ? {file} : null;
}

function shortUrl(url) {
  return url.length > 160 ? url.slice(0, 157) + '...' : url;
}

/** 应答一个被拦住的请求 */
async function answer(cdp, sessionId, demo, round, params) {
  const {requestId, request} = params;
  const send = (method, extra) => cdp.send(method, {requestId, ...extra}, sessionId).catch(() => {});
  const url = new URL(request.url);
  if (url.origin !== ORIGIN) {
    round.notes.blocked.add(shortUrl(url.origin + url.pathname));
    return send('Fetch.failRequest', {errorReason: 'BlockedByClient'});
  }
  const method = request.method;
  const isApi = url.pathname.startsWith('/config/api/');
  const found = method === 'GET' || method === 'HEAD' ? localFile(demo, url.pathname) : null;
  if (found?.redirect) {
    return send('Fetch.fulfillRequest', {responseCode: 301,
      responseHeaders: [{name: 'Location', value: found.redirect + url.search}], body: ''});
  }
  if (!found) {
    if (isApi || (method !== 'GET' && method !== 'HEAD')) {
      round.notes.unrecorded.add(`${method} ${url.pathname}${url.search}（页面直接请求）`);
    } else {
      round.missing.add(url.pathname);
    }
    const status = method === 'GET' || method === 'HEAD' ? 404 : 501;
    return send('Fetch.fulfillRequest', {responseCode: status,
      responseHeaders: [{name: 'Content-Type', value: 'text/plain; charset=utf-8'}],
      body: Buffer.from('Not Found').toString('base64')});
  }
  const type = TYPES[path.extname(found.file).toLowerCase()] || 'application/octet-stream';
  const body = method === 'HEAD' ? '' : fs.readFileSync(found.file).toString('base64');
  return send('Fetch.fulfillRequest', {responseCode: 200, body, responseHeaders: [
    {name: 'Content-Type', value: type}, {name: 'Cache-Control', value: 'no-store'}]});
}

function remoteText(value) {
  if (!value) return '';
  if (value.value !== undefined) return typeof value.value === 'string' ? value.value : JSON.stringify(value.value);
  return value.description || value.type || '';
}

function exceptionText(details) {
  const first = (details.exception?.description || details.text || '').split('\n')[0];
  const where = details.url ? ` @ ${details.url.replace(ORIGIN, '')}:${details.lineNumber + 1}` : '';
  const prefix = details.text && !first.startsWith(details.text) ? details.text + ' ' : '';
  return prefix + first + where;
}

// 页面稳定后在页面里算的几样读数。演示自带的两个提示框不算页面文字。
const INSPECT = `(() => {
  const demo = [...document.querySelectorAll('[id^="console-demo-"]')];
  const saved = demo.map(el => el.style.display);
  demo.forEach(el => { el.style.display = 'none'; });
  const text = document.body ? document.body.innerText.trim() : '';
  demo.forEach((el, i) => { el.style.display = saved[i]; });
  const root = document.documentElement;
  const viewport = root.clientWidth;
  const content = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
  const culprits = [];
  if (content > viewport && document.body) {
    const clipped = el => {
      for (let a = el.parentElement; a && a !== document.body && a !== root; a = a.parentElement) {
        const style = getComputedStyle(a);
        if (style.overflowX !== 'visible' || style.position === 'fixed') return true;
      }
      return false;
    };
    for (const el of document.body.querySelectorAll('*')) {
      const style = getComputedStyle(el);
      if (style.position === 'fixed' || style.display === 'none') continue;
      const rect = el.getBoundingClientRect();
      const over = Math.round(rect.right + window.scrollX - viewport);
      if (over <= 0 || (!rect.width && !rect.height) || clipped(el)) continue;
      culprits.push({tag: el.tagName.toLowerCase(), id: el.id || '', cls: (el.getAttribute('class') || '').trim(), over});
    }
    culprits.sort((a, b) => b.over - a.over);
  }
  return {
    hasText: text.length > 0,
    unrecordedNotice: text.includes('离线演示未录制'),
    viewport, content, culprits: culprits.slice(0, 3),
    url: location.pathname + location.search + location.hash,
    loginVisible: !!document.querySelector('#login')?.getClientRects().length
      && getComputedStyle(document.querySelector('#login')).visibility !== 'hidden',
  };
})()`;

// 选了的配色有没有落到页面上：根元素的 data-palette、颜色变量取不取得到、下拉框箭头是不是这一套的次要字色。
// 变量取不到时 background-color 落回初值 rgba(0, 0, 0, 0)，页面看起来就是没颜色
const PALETTE_PROBE = `(() => {
  const probe = document.createElement('div');
  probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:1px';
  (document.body || document.documentElement).appendChild(probe);
  const color = name => {
    probe.style.backgroundColor = 'var(' + name + ')';
    return getComputedStyle(probe).backgroundColor;
  };
  const out = {palette: document.documentElement.getAttribute('data-palette'),
    ground: color('--ground'), surface: color('--surface'), text: color('--text'), dim: color('--dim'),
    accent: color('--accent'), err: color('--err')};
  probe.style.backgroundColor = '';
  probe.style.backgroundImage = 'var(--select-chevron)';
  out.chevron = getComputedStyle(probe).backgroundImage;
  probe.remove();
  return out;
})()`;

/** rgb(1, 2, 3) → 010203；带透明度或认不出时返回 null */
function opaqueHex(css) {
  const m = /^rgb\((\d+), (\d+), (\d+)\)$/.exec(css || '');
  return m ? m.slice(1).map(v => Number(v).toString(16).padStart(2, '0')).join('').toUpperCase() : null;
}

function checkPalette(facts, palette) {
  if (!facts) return ['配色：读不到页面上的颜色变量'];
  const problems = [];
  if (facts.palette !== palette) problems.push(`配色：根元素上是 ${facts.palette ?? '（没有）'}，应为 ${palette}`);
  const missing = ['ground', 'surface', 'text', 'dim', 'accent', 'err'].filter(name => !opaqueHex(facts[name]));
  if (missing.length) problems.push('配色：这些颜色变量取不到：' + missing.map(name => '--' + name).join('、'));
  const dim = opaqueHex(facts.dim);
  if (dim && !(facts.chevron || '').toUpperCase().includes('%23' + dim)) {
    problems.push(`配色：下拉框箭头不是这一套的次要字色 #${dim}`);
  }
  return problems;
}

// 用归档充足的演示主播量实际布局；滚到趋势卡后通过真实控件改变视图。
const STREAMER_INTERACTIONS = `(async () => {
  const wait = async predicate => {
    const deadline = performance.now() + 12000;
    while (!predicate()) {
      if (performance.now() > deadline) throw new Error('等待主播视图超时');
      await new Promise(requestAnimationFrame);
    }
    await new Promise(requestAnimationFrame);
    await new Promise(requestAnimationFrame);
  };
  const rect = selector => document.querySelector(selector).getBoundingClientRect();
  const rows = [...document.querySelectorAll('#sd-recent .sd-recent-row')].filter(row => row.getClientRects().length);
  const last = rows.at(-1).getBoundingClientRect();
  const geometry = {bottomDifference: Math.abs(rect('#sd-calendar-card').bottom - rect('#sd-recent-card').bottom),
    rows: rows.length, bottomSpace: rect('#sd-recent-card').bottom - last.bottom, rowHeight: last.height,
    heading: document.querySelector('#sd-recent h4').textContent};
  document.querySelector('#sd-trend').scrollIntoView({block: 'start'});
  await wait(() => scrollY > 200);
  let position = scrollY;
  const movements = [];
  const step = async (name, action, ready, selector) => {
    window.scrollTo(0, position);
    const beforeTop = rect(selector).top;
    action();
    await wait(ready);
    const afterTop = rect(selector).top;
    movements.push({name, before: position, after: scrollY, difference: Math.abs(scrollY - position),
      controlDifference: Math.abs(afterTop - beforeTop), inView: rect(selector).bottom > 0 && afterTop < innerHeight});
  };
  const checkbox = '#sd-trend input[type=checkbox]';
  const checked = document.querySelector(checkbox).checked;
  await step('隐藏未播与无效日期', () => document.querySelector(checkbox).click(),
    () => document.querySelector(checkbox).checked !== checked && location.hash.includes('hideOff=1'), checkbox);
  document.querySelector('#sd-calendar .sc-calendar-recorded').click();
  const opener = '#sd-calendar .sc-calendar-selection button';
  document.querySelector(opener).scrollIntoView({block: 'center'});
  await wait(() => rect(opener).top < innerHeight);
  position = scrollY;
  await step('打开场次抽屉', () => document.querySelector(opener).click(),
    () => document.querySelector('dialog.sc-drawer[open] .sd-session-filters'), opener);
  await step('关闭场次抽屉', () => document.querySelector('.sc-drawer-close').click(),
    () => !document.querySelector('dialog.sc-drawer[open]') && !location.hash.includes('panel='), opener);
  window.scrollTo(0, 0);
  return {geometry, movements};
})()`;

async function checkStreamerInteractions(s, round) {
  const response = await s('Runtime.evaluate', {expression: STREAMER_INTERACTIONS, returnByValue: true, awaitPromise: true});
  if (response.exceptionDetails) throw new Error(exceptionText(response.exceptionDetails));
  const {geometry, movements} = response.result.value;
  round.interactions = {geometry, movements};
  if (geometry.bottomDifference > 1 || geometry.bottomSpace >= geometry.rowHeight
    || geometry.heading !== '最近 ' + geometry.rows + ' 场归档') {
    round.failures.push('主播双卡布局：' + JSON.stringify(geometry));
  }
  for (const movement of movements) if (!movement.inView || movement.controlDifference > 2) {
    round.failures.push('页内滚动：' + JSON.stringify(movement));
  }
}

// 等重画和临时保高结束，再量真实内容；旧版保高不释放时也留下读数。
const VIEW_HELPERS = `
  const frame = () => new Promise(requestAnimationFrame);
  const wait = async predicate => {
    const deadline = performance.now() + 12000;
    while (!predicate()) {
      if (performance.now() > deadline) throw new Error('等待页面控件超时');
      await frame();
    }
    await frame(); await frame();
  };
  const finish = async () => {
    const deadline = performance.now() + 5000;
    while (document.querySelector('.page.on').style.minHeight && performance.now() < deadline) await frame();
    await frame(); await frame();
  };
  const visible = el => !!el?.getClientRects().length;
  const inView = el => visible(el) && el.getBoundingClientRect().bottom > 0
    && el.getBoundingClientRect().top < innerHeight;
  const bottomSpace = () => {
    const page = document.querySelector('.page.on');
    const bottom = Math.max(...[...page.children].filter(visible).map(el => el.getBoundingClientRect().bottom));
    return Math.max(0, document.documentElement.scrollHeight - (bottom + scrollY));
  };
`;

const DRAWER_PAGINATION = `(async () => {
  ${VIEW_HELPERS}
  document.querySelector('#sd-body a[href*="panel=trend"]').click();
  const drawer = () => document.querySelector('dialog.sc-drawer[open]');
  const pager = () => drawer()?.querySelector('.sd-pagination');
  const next = () => [...(pager()?.querySelectorAll('a') || [])].find(link => link.textContent.includes('下一页'));
  await wait(() => next());
  next().scrollIntoView({block: 'center'});
  await frame(); await frame();
  const before = next().getBoundingClientRect().top;
  const scrollBefore = drawer().querySelector('.sc-drawer-body').scrollTop;
  next().click();
  await wait(() => pager()?.textContent.includes('第 2/'));
  await finish();
  const control = next();
  const after = control.getBoundingClientRect().top;
  const body = drawer().querySelector('.sc-drawer-body');
  const bounds = body.getBoundingClientRect(), button = control.getBoundingClientRect();
  const facts = {name: '趋势明细周期翻页', before, after, difference: Math.abs(after - before),
    inView: button.top >= bounds.top && button.bottom <= bounds.bottom && inView(control),
    scrollBefore, scrollAfter: body.scrollTop};
  drawer().querySelector('.sc-drawer-close').click();
  await wait(() => !drawer() && !location.hash.includes('panel='));
  await finish();
  window.scrollTo(0, 0);
  return facts;
})()`;

async function checkDrawerPagination(s, round) {
  const response = await s('Runtime.evaluate', {expression: DRAWER_PAGINATION, returnByValue: true, awaitPromise: true});
  if (response.exceptionDetails) throw new Error(exceptionText(response.exceptionDetails));
  const facts = response.result.value;
  round.drawerPagination = facts;
  if (!facts.inView || facts.difference > 2) round.failures.push('抽屉翻页：' + JSON.stringify(facts));
}

const PUSH_COLLAPSE = `(async () => {
  ${VIEW_HELPERS}
  location.hash = '#/push/990000000101/990000000301';
  const selector = '.crow > summary';
  await wait(() => visible(document.querySelector(selector)));
  document.querySelector(selector).click();
  await wait(() => document.querySelector(selector)?.parentElement.open);
  await finish();
  document.querySelector(selector).scrollIntoView({block: 'start'});
  window.scrollBy(0, -80);
  await frame(); await frame();
  const before = document.querySelector(selector).getBoundingClientRect().top;
  const scrollBefore = scrollY;
  const scrollLimitBefore = document.documentElement.scrollHeight - innerHeight;
  document.querySelector(selector).click();
  await wait(() => !document.querySelector(selector)?.parentElement.open);
  await finish();
  const control = document.querySelector(selector);
  const after = control.getBoundingClientRect().top;
  return {before, after, difference: Math.abs(after - before), inView: inView(control),
    scrollBefore, scrollAfter: scrollY, scrollLimitBefore,
    bottomSpace: bottomSpace(),
    minHeight: document.querySelector('.page.on').style.minHeight,
    overflowAnchor: document.querySelector('.page.on').style.overflowAnchor};
})()`;

const SETTINGS_COLLAPSE = `(async () => {
  ${VIEW_HELPERS}
  const toggle = () => document.querySelector('#show-advanced');
  await wait(() => visible(toggle()));
  toggle().click();
  await wait(() => toggle().getAttribute('aria-expanded') === 'true');
  await finish();
  window.scrollTo(0, document.documentElement.scrollHeight - innerHeight);
  await frame(); await frame();
  const before = toggle().getBoundingClientRect().top;
  const scrollBefore = scrollY;
  toggle().click();
  await wait(() => toggle().getAttribute('aria-expanded') === 'false');
  await finish();
  const after = toggle().getBoundingClientRect().top;
  return {before, after, difference: Math.abs(after - before), inView: inView(toggle()),
    scrollBefore, scrollAfter: scrollY, visibleSettings: [...document.querySelectorAll('.setitem')].filter(inView).length,
    bottomSpace: bottomSpace(), minHeight: document.querySelector('.page.on').style.minHeight,
    overflowAnchor: document.querySelector('.page.on').style.overflowAnchor};
})()`;

async function checkCollapse(s, round, expression, name) {
  const response = await s('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
  if (response.exceptionDetails) throw new Error(exceptionText(response.exceptionDetails));
  const facts = response.result.value;
  round.collapse = {name, ...facts};
  // 真正缩短到页底时允许位置随之收回；控件必须仍然可见。
  const clamped = facts.scrollAfter < facts.scrollBefore
    && facts.scrollAfter + 900 >= (await s('Page.getLayoutMetrics')).cssContentSize.height - 2;
  if (!facts.inView || (!clamped && facts.difference > 2) || facts.bottomSpace > 100
    || (facts.scrollLimitBefore !== undefined && facts.scrollBefore < facts.scrollLimitBefore / 2)
    || (facts.visibleSettings !== undefined && facts.visibleSettings < 1)
    || facts.minHeight || facts.overflowAnchor === 'none') {
    round.failures.push(name + '：' + JSON.stringify(facts));
  }
}

// PNG 的校验与压缩只用 node 自带模块。Chromium 输出的 8 位 RGB/RGBA 可直接拼接。
const CRC_TABLE = Array.from({length: 256}, (_, n) => {
  for (let i = 0; i < 8; i++) n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1;
  return n >>> 0;
});

function pngChunk(type, data) {
  const name = Buffer.from(type);
  let crc = 0xffffffff;
  for (const byte of Buffer.concat([name, data])) crc = CRC_TABLE[(crc ^ byte) & 255] ^ (crc >>> 8);
  const result = Buffer.alloc(data.length + 12);
  result.writeUInt32BE(data.length);
  name.copy(result, 4);
  data.copy(result, 8);
  result.writeUInt32BE((crc ^ 0xffffffff) >>> 0, data.length + 8);
  return result;
}

function pngRows(png) {
  const signature = Buffer.from('89504e470d0a1a0a', 'hex');
  if (!png.subarray(0, 8).equals(signature)) throw new Error('截图不是 PNG');
  let header;
  const compressed = [];
  for (let offset = 8; offset + 12 <= png.length;) {
    const length = png.readUInt32BE(offset);
    const type = png.toString('ascii', offset + 4, offset + 8);
    const data = png.subarray(offset + 8, offset + 8 + length);
    if (data.length !== length) throw new Error('PNG 数据不完整');
    if (type === 'IHDR') header = data;
    if (type === 'IDAT') compressed.push(data);
    offset += length + 12;
  }
  if (!header || header.length !== 13 || header[8] !== 8 || ![2, 6].includes(header[9])
    || header[10] || header[11] || header[12]) throw new Error('不能拼接这种 PNG 编码');
  const width = header.readUInt32BE(0), height = header.readUInt32BE(4);
  const channels = header[9] === 6 ? 4 : 3;
  const stride = width * channels;
  const raw = inflateSync(Buffer.concat(compressed));
  if (raw.length !== (stride + 1) * height) throw new Error('PNG 行数不符');
  const rows = Buffer.alloc(raw.length);
  for (let y = 0; y < height; y++) {
    const start = y * (stride + 1);
    const filter = raw[start];
    if (filter > 4) throw new Error('不能识别 PNG 行滤镜');
    // 输出每行都用 None 滤镜，分块边界不再依赖前一块的最后一行。
    for (let x = 1; x <= stride; x++) {
      const a = x > channels ? rows[start + x - channels] : 0;
      const b = y ? rows[start + x - stride - 1] : 0;
      const c = y && x > channels ? rows[start + x - stride - 1 - channels] : 0;
      let predictor = 0;
      if (filter === 1) predictor = a;
      if (filter === 2) predictor = b;
      if (filter === 3) predictor = Math.floor((a + b) / 2);
      if (filter === 4) {
        const value = a + b - c;
        const pa = Math.abs(value - a), pb = Math.abs(value - b), pc = Math.abs(value - c);
        predictor = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      rows[start + x] = (raw[start + x] + predictor) & 255;
    }
  }
  return {header, width, height, rows};
}

function joinPng(files, destination) {
  const pieces = files.map(file => pngRows(fs.readFileSync(file)));
  const first = pieces[0];
  if (pieces.some(p => p.width !== first.width || !p.header.subarray(8).equals(first.header.subarray(8)))) {
    throw new Error('分块宽度或颜色格式不同');
  }
  const header = Buffer.from(first.header);
  header.writeUInt32BE(pieces.reduce((sum, p) => sum + p.height, 0), 4);
  writeNew(destination, Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'), pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(Buffer.concat(pieces.map(p => p.rows)))), pngChunk('IEND', Buffer.alloc(0)),
  ]));
}

async function capture(cdp, sessionId, out, page, size, palette, color) {
  const metrics = await cdp.send('Page.getLayoutMetrics', {}, sessionId);
  const content = metrics.cssContentSize || metrics.contentSize;
  const width = Math.max(size.width, Math.ceil(content.width));
  const height = Math.min(SHOT_MAX_HEIGHT, Math.max(size.height, Math.ceil(content.height)));
  const base = `${page}-${size.name}-${palette}-${color}`;
  const tiled = process.platform === 'darwin';
  const step = tiled ? Math.floor(SHOT_TILE_PIXELS / (width * size.scale ** 2)) : height;
  if (step < 1) throw new Error('页面过宽，一行已超过截图分块上限');
  const files = [];
  for (let y = 0; y < height; y += step) {
    const shot = await cdp.send('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true,
      clip: {x: 0, y, width, height: Math.min(step, height - y), scale: 1}}, sessionId, SHOT_LIMIT_MS);
    const file = path.join(out, `${base}${tiled ? '-' + (files.length + 1) : ''}.png`);
    writeNew(file, Buffer.from(shot.data, 'base64'));
    files.push(file);
  }
  if (tiled) {
    try {
      joinPng(files, path.join(out, `${base}.png`));
    } catch (e) {
      if (e instanceof NameTaken) throw e;
      return {tiles: files.length, joined: false, problem: `${base}：拼图失败（${e.message}），保留分块`};
    }
  }
  return {tiles: tiled ? files.length : 0, joined: true};
}

async function runRound(cdp, demo, out, shots, screen, size, palette, color) {
  const [page, address] = screen;
  const round = {
    page, width: size.name, palette, color,
    failures: [],
    missing: new Set(),
    exceptions: [],
    notes: {unrecorded: new Set(), consoleErrors: new Set(), blocked: new Set(), landed: new Set()},
  };
  // macOS 上只能单进程起浏览器，这时在新建的浏览器上下文里开标签页会让它崩掉；
  // 所以都开在默认上下文里，每轮换一个新标签页，并在打开页面前清空这个来源的存储。
  const {targetId} = await cdp.send('Target.createTarget', {url: 'about:blank'});
  try {
    const {sessionId} = await cdp.send('Target.attachToTarget', {targetId, flatten: true});
    const s = (method, params) => cdp.send(method, params, sessionId);
    await s('Storage.clearDataForStorageKey', {storageKey: ORIGIN + '/', storageTypes: 'all'});
    const inflight = new Map();
    let loaded = false;
    let lastActivity = Date.now();
    cdp.sessions.set(sessionId, (method, params) => {
      switch (method) {
        case 'Fetch.requestPaused':
          answer(cdp, sessionId, demo, round, params);
          break;
        case 'Network.requestWillBeSent':
          inflight.set(params.requestId, params.request.url);
          lastActivity = Date.now();
          break;
        case 'Network.loadingFinished':
        case 'Network.loadingFailed':
          inflight.delete(params.requestId);
          lastActivity = Date.now();
          break;
        case 'Page.frameStartedLoading':
          if (params.frameId === targetId) loaded = false;
          break;
        case 'Page.loadEventFired':
          loaded = true;
          lastActivity = Date.now();
          break;
        case 'Runtime.exceptionThrown':
          round.exceptions.push(exceptionText(params.exceptionDetails));
          break;
        case 'Runtime.consoleAPICalled': {
          const text = params.args.map(remoteText).join(' ');
          if (params.type === 'error' || params.type === 'assert') round.notes.consoleErrors.add(shortUrl(text));
          else if (params.type === 'warning' && text.startsWith('Console demo: unrecorded')) {
            round.notes.unrecorded.add(text.replace(/^Console demo: unrecorded (request|navigation) /, ''));
          }
          break;
        }
        case 'Log.entryAdded':
          // 网络类的报错已由上面的资源核对与拦截清单覆盖
          if (params.entry.level === 'error' && params.entry.source !== 'network') {
            round.notes.consoleErrors.add(shortUrl(params.entry.text));
          }
          break;
        default:
      }
    });
    await s('Fetch.enable', {patterns: [{urlPattern: '*', requestStage: 'Request'}]});
    await s('Network.enable');
    await s('Network.setCacheDisabled', {cacheDisabled: true});
    await s('Network.clearBrowserCookies');
    await s('Page.enable');
    await s('Runtime.enable');
    await s('Log.enable');
    await s('Emulation.setDeviceMetricsOverride', {width: size.width, height: size.height,
      deviceScaleFactor: size.scale, mobile: size.mobile});
    if (size.mobile) await s('Emulation.setTouchEmulationEnabled', {enabled: true, maxTouchPoints: 5});
    await s('Emulation.setEmulatedMedia', {features: [{name: 'prefers-color-scheme', value: color}]});
    await s('Page.addScriptToEvaluateOnNewDocument', {source:
      `try { localStorage.setItem('novabot-theme', ${JSON.stringify(color)});`
      + ` localStorage.setItem('novabot-palette', ${JSON.stringify(palette)}); } catch (e) {}\n`
      + (page === 'login' ? 'window.NOVA_CONSOLE_DEMO_SIGNED_OUT = true;' : '')});

    loaded = false;
    const started = Date.now();
    const navigation = await s('Page.navigate', {url: ORIGIN + address});
    if (navigation.errorText) round.failures.push(`打不开页面：${navigation.errorText}`);
    let settled = false;
    while (Date.now() - started < SETTLE_LIMIT_MS) {
      if (loaded && inflight.size === 0 && Date.now() - lastActivity >= SETTLE_MS) {
        settled = true;
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    if (!settled) {
      const waiting = [...inflight.values()].slice(0, 3).map(url => url.replace(ORIGIN, '')).join('、');
      round.failures.push(`${page} 页 ${SETTLE_LIMIT_MS / 1000} 秒内没稳定`
        + (loaded ? '' : '（load 事件未到）') + (waiting ? `（未完成的请求：${waiting}）` : ''));
    }

    const {result} = await s('Runtime.evaluate', {expression: INSPECT, returnByValue: true});
    const facts = result.value;
    if (round.exceptions.length) round.failures.push('页面脚本异常：' + [...new Set(round.exceptions)].join('；'));
    if (round.missing.size) round.failures.push('导出目录里找不到：' + [...round.missing].join('、'));
    if (facts && !facts.hasText) round.failures.push('白屏：页面主体没有可见文字');
    if (facts && size.mobile && facts.content > facts.viewport) {
      const who = facts.culprits.map(c => `${c.tag}${c.id ? '#' + c.id : ''}${c.cls ? '.' + c.cls.split(/\s+/).join('.') : ''}`
        + ` 伸出 ${c.over}px`).join('、');
      round.failures.push(`横向溢出：页面宽 ${facts.content}px，视口 ${facts.viewport}px` + (who ? `；${who}` : ''));
    }
    if (page === 'login' && (!facts?.loginVisible || facts.url !== address)) {
      round.failures.push('登录页没有显示未登录表单');
    }
    if (facts?.unrecordedNotice) round.notes.unrecorded.add('页面上出现「离线演示未录制」提示');
    const probe = (await s('Runtime.evaluate', {expression: PALETTE_PROBE, returnByValue: true})).result.value;
    round.failures.push(...checkPalette(probe, palette));
    round.colors = probe;
    if (page === 'streamer' && size.width === 1440 && palette === PALETTES[0] && color === 'light') {
      await checkStreamerInteractions(s, round);
      await checkDrawerPagination(s, round);
    }
    if (size.width === 1440 && palette === PALETTES[0] && color === 'light') {
      if (page === 'push') await checkCollapse(s, round, PUSH_COLLAPSE, '收起群内命令');
      if (page === 'settings') await checkCollapse(s, round, SETTINGS_COLLAPSE, '收起高级选项');
    }
    if (facts && facts.url !== new URL(ORIGIN + address).pathname + new URL(ORIGIN + address).hash) {
      round.notes.landed.add(`打开后停在 ${facts.url}`);
    }

    if (shots) {
      try {
        round.shots = await capture(cdp, sessionId, out, page, size, palette, color);
      } catch (e) {
        if (e instanceof NameTaken) throw e;
        // 有的环境里截图一直不回，浏览器也跟着不再应答（见 README）。这一轮的检查结果照留。
        round.shotError = e.message;
      }
    }
    cdp.sessions.delete(sessionId);
  } finally {
    await cdp.send('Target.closeTarget', {targetId}, undefined, 5000).catch(() => {});
  }
  return round;
}

function summarize(rounds, version, shotProblem) {
  const lines = [`浏览器：${version}`];
  const completed = rounds.filter(r => r.shots?.joined).length;
  const pieces = rounds.reduce((sum, r) => sum + (r.shots?.tiles || 0), 0);
  const mode = process.platform === 'darwin' ? `每块最多 ${SHOT_TILE_PIXELS} 像素，保留 ${pieces} 张分块，` : '';
  lines.push(`截图：${mode}${completed} 轮已保存整页图${shotProblem ? '；' + shotProblem : ''}`);
  for (const r of rounds) if (r.shots?.problem) lines.push(`  ${r.shots.problem}`);
  lines.push('');
  for (const r of rounds) {
    lines.push(`${r.page} ${r.width} ${r.palette} ${r.color} ${r.failures.length ? '失败 ' + r.failures.join('；') : '通过'}`);
  }
  const failed = rounds.filter(r => r.failures.length).length;
  lines.push('', `合计 ${rounds.length} 轮：通过 ${rounds.length - failed}，失败 ${failed}`);
  for (const r of rounds) if (r.interactions) {
    lines.push('主播双卡布局：' + JSON.stringify(r.interactions.geometry));
    for (const movement of r.interactions.movements) lines.push('页内滚动：' + JSON.stringify(movement));
  }
  for (const r of rounds) if (r.collapse) lines.push('页面收起：' + JSON.stringify(r.collapse));
  for (const r of rounds) if (r.drawerPagination) lines.push('抽屉翻页：' + JSON.stringify(r.drawerPagination));

  // 各套配色在首页上取到的几样颜色，只列出：三套该各是各的，一眼看得出有没有哪套没生效
  lines.push('', '配色读数（首页 1440 宽，页底／强调色／次要字）：');
  for (const r of rounds) {
    if (r.page !== 'home' || r.width !== '1440' || !r.colors) continue;
    lines.push(`  ${r.palette} ${r.color}：#${opaqueHex(r.colors.ground) ?? '?'}／#${opaqueHex(r.colors.accent) ?? '?'}`
      + `／#${opaqueHex(r.colors.dim) ?? '?'}`);
  }

  const kinds = [
    ['unrecorded', '演示没录的接口'],
    ['consoleErrors', 'console.error'],
    ['blocked', '拦下的外部请求'],
    ['landed', '打开后的地址'],
  ];
  lines.push('', '只列出、不判失败（按页归并，括号里是出现的轮数）：');
  let any = false;
  for (const [page] of SCREENS) {
    const mine = rounds.filter(r => r.page === page);
    const section = [];
    for (const [kind, label] of kinds) {
      const counts = new Map();
      for (const r of mine) for (const item of r.notes[kind]) counts.set(item, (counts.get(item) || 0) + 1);
      if (!counts.size) continue;
      section.push(`  ${label}：`);
      for (const [item, count] of [...counts].sort()) section.push(`    ${item}（${count}）`);
    }
    if (section.length) {
      any = true;
      lines.push(`[${page}]`, ...section);
    }
  }
  if (!any) lines.push('  （无）');
  return {text: lines.join('\n') + '\n', failed};
}

async function run({demo, chrome, out, profile}) {
  // 日志只打开这一次，换浏览器时接着写同一个文件，不再按名字重新打开
  const logFile = path.resolve(out, 'browser.log');
  const log = openNew(path.join(out, 'browser.log'));
  try {
    return await runAll({demo, chrome, out, profile, log, logFile});
  } finally {
    fs.closeSync(log);
  }
}

async function runAll({demo, chrome, out, profile, log, logFile}) {
  let launches = 0;
  let browser = await launch(chrome, path.join(profile, String(++launches)), log, logFile);
  let shotProblem = null;
  const rounds = [];
  try {
    const plan = [];
    for (const screen of SCREENS) {
      for (const size of WIDTHS) {
        for (const palette of PALETTES) {
          if (palette !== PALETTES[0] && !PALETTE_SCREENS.has(screen[0])) continue;
          for (const color of COLORS) plan.push([screen, size, palette, color]);
        }
      }
    }
    for (const [screen, size, palette, color] of plan) {
      let round;
      try {
        round = await runRound(browser.cdp, demo, out, !shotProblem, screen, size, palette, color);
        if (round.shotError) {
          shotProblem = `从 ${round.page} ${round.width} ${round.palette} ${round.color} 这一轮起截不了图（${round.shotError}），`
            + '换一个浏览器接着查，之后各轮照查但不截图';
          process.stderr.write(`截图：${shotProblem}\n`);
          await browser.stop();
          browser = await launch(chrome, path.join(profile, String(++launches)), log, logFile);
        }
      } catch (e) {
        // 浏览器没了就整轮跑不下去；只是这一轮的某条命令出错，记成这一轮失败
        if (e instanceof NameTaken) throw e;
        if (browser.cdp.closed) throw browser.cdp.closed;
        round = {page: screen[0], width: size.name, palette, color, failures: [`检查出错：${e.message}`],
          notes: {unrecorded: new Set(), consoleErrors: new Set(), blocked: new Set(), landed: new Set()}};
      }
      rounds.push(round);
      process.stderr.write(`${round.page} ${round.width} ${round.palette} ${round.color} ${round.failures.length ? '失败' : '通过'}\n`);
    }
  } finally {
    await browser.stop();
  }
  const {text, failed} = summarize(rounds, browser.version, shotProblem);
  writeNew(path.join(out, 'summary.txt'), text);
  process.stdout.write(text);
  return failed ? 1 : 0;
}

try {
  process.exitCode = await main();
} catch (e) {
  if (e instanceof CannotRun) {
    process.stderr.write(`跑不起来：${e.message}\n`);
  } else {
    process.stderr.write(`跑不起来：${e.stack || e}\n`);
  }
  process.exitCode = 2;
}
