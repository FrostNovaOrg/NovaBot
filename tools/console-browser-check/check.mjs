// 在真浏览器里把控制台离线演示各页打开一遍：抓脚本异常、找不到的本地资源、白屏与手机宽横向溢出。
//
// 不占端口、不建套接字：浏览器以 --remote-debugging-pipe 起，调试协议走两条命名管道（FIFO）；
// 页面开在 http://localhost:8000/config/...，那里没有服务，每个请求都由 Fetch 域接住，
// 从导出目录读文件回给浏览器。发往别处的请求一律拦下，不出网。
//
// 用法：node check.mjs --demo <导出目录> --chrome <浏览器> --out <结果目录> --profile <空的临时目录>
// 退码：0 全过；1 有失败；2 跑不起来。

import {execFileSync, spawn} from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {parseArgs} from 'node:util';

const ORIGIN = 'http://localhost:8000';
const SETTLE_MS = 500;
const SETTLE_LIMIT_MS = 15000;
const COMMAND_LIMIT_MS = 30000;
const SHOT_LIMIT_MS = 15000;
// 极长的页面截到这里为止，免得截图超出浏览器的纹理上限
const SHOT_MAX_HEIGHT = 16000;

const SCREENS = [
  ['home', '/config/#/home'],
  ['push', '/config/#/push'],
  ['streamers', '/config/#/streamers'],
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
  return run({demo, chrome: values.chrome, out: path.resolve(values.out), profile: path.resolve(values.profile)});
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

/**
 * 起浏览器。调试协议走两条 FIFO：fd 3 浏览器读、fd 4 浏览器写。
 * FIFO 以读写方式打开，另一端还没人时 open 也不会卡住。
 */
async function launch(chrome, profile, logFile) {
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
  const weRead = fs.openSync(fromBrowser, O_RDWR);
  const browserWrites = fs.openSync(fromBrowser, O_WRONLY);
  const log = fs.openSync(logFile, 'a');

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
  for (const fd of [browserReads, browserWrites, log]) fs.closeSync(fd);
  const input = new net.Socket({fd: weRead, readable: true, writable: false});
  const output = new net.Socket({fd: weWrite, readable: false, writable: true});
  const cdp = new Cdp(input, output);
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
    throw new CannotRun(`浏览器没有应答调试协议：${e.message}；日志见 ${logFile}`);
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
  };
})()`;

async function runRound(cdp, demo, out, shots, screen, size, color) {
  const [page, address] = screen;
  const round = {
    page, width: size.name, color,
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
      `try { localStorage.setItem('novabot-theme', ${JSON.stringify(color)}); } catch (e) {}`});

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
    if (facts?.unrecordedNotice) round.notes.unrecorded.add('页面上出现「离线演示未录制」提示');
    if (facts && facts.url !== new URL(ORIGIN + address).pathname + new URL(ORIGIN + address).hash) {
      round.notes.landed.add(`打开后停在 ${facts.url}`);
    }

    if (shots) {
      try {
        const metrics = await s('Page.getLayoutMetrics');
        const content = metrics.cssContentSize || metrics.contentSize;
        const shot = await cdp.send('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true, clip: {
          x: 0, y: 0, width: Math.max(size.width, Math.ceil(content.width)),
          height: Math.min(SHOT_MAX_HEIGHT, Math.max(size.height, Math.ceil(content.height))), scale: 1}},
        sessionId, SHOT_LIMIT_MS);
        fs.writeFileSync(path.join(out, `${page}-${size.name}-${color}.png`), Buffer.from(shot.data, 'base64'));
      } catch (e) {
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
  if (shotProblem) lines.push(`截图：${shotProblem}`);
  lines.push('');
  for (const r of rounds) {
    lines.push(`${r.page} ${r.width} ${r.color} ${r.failures.length ? '失败 ' + r.failures.join('；') : '通过'}`);
  }
  const failed = rounds.filter(r => r.failures.length).length;
  lines.push('', `合计 ${rounds.length} 轮：通过 ${rounds.length - failed}，失败 ${failed}`);

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
  const log = path.join(out, 'browser.log');
  let launches = 0;
  let browser = await launch(chrome, path.join(profile, String(++launches)), log);
  let shotProblem = null;
  const rounds = [];
  try {
    for (const screen of SCREENS) {
      for (const size of WIDTHS) {
        for (const color of COLORS) {
          let round;
          try {
            round = await runRound(browser.cdp, demo, out, !shotProblem, screen, size, color);
            if (round.shotError) {
              shotProblem = `从 ${round.page} ${round.width} ${round.color} 这一轮起截不了图（${round.shotError}），`
                + '换一个浏览器接着查，之后各轮照查但不截图';
              process.stderr.write(`截图：${shotProblem}\n`);
              await browser.stop();
              browser = await launch(chrome, path.join(profile, String(++launches)), log);
            }
          } catch (e) {
            // 浏览器没了就整轮跑不下去；只是这一轮的某条命令出错，记成这一轮失败
            if (browser.cdp.closed) throw browser.cdp.closed;
            round = {page: screen[0], width: size.name, color, failures: [`检查出错：${e.message}`],
              notes: {unrecorded: new Set(), consoleErrors: new Set(), blocked: new Set(), landed: new Set()}};
          }
          rounds.push(round);
          process.stderr.write(`${round.page} ${round.width} ${round.color} ${round.failures.length ? '失败' : '通过'}\n`);
        }
      }
    }
  } finally {
    await browser.stop();
  }
  const {text, failed} = summarize(rounds, browser.version, shotProblem);
  fs.writeFileSync(path.join(out, 'summary.txt'), text);
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
