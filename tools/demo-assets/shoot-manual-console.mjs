// 拍手册里的控制台截图：manual/images 下 15 张，取景、尺寸与手册现有的图一致。
//
// 用法（在仓库根目录）：
//   NOVA_CHROME=/path/to/chrome-headless-shell node tools/demo-assets/shoot-manual-console.mjs [选项] [图名 ...]
//
//   不给图名：拍全部；给了就只拍这几张，图名不带 .png，例如 01-home 09-dashboard。
//   --publish      写进 manual/images（覆盖同名的图）；不给时写到 target/manual-shots/，manual 下一字不动。
//   --chrome 路径  浏览器，也可由环境变量 NOVA_CHROME 给出；用 chrome-headless-shell，下载见
//                  tools/console-browser-check/README.md。
//   --list         只列出能拍的图名与说明，不起浏览器。
//
// 要什么：node 22 以上、bash、python3、上面说的浏览器。不需要 Java，也不起 NovaBot。
// 拍的是离线演示（tools/demo-assets/console-demo/data 里的录制回包）。界面改了而录包没跟上时，
// 先在仓库根目录跑 bash tools/demo-assets/record-console.sh 重录，再拍。
//
// 固定的条件：窗口 1440×900、设备像素比 1、浅色、默认配色粉蓝；时刻是录包里的录制时刻
// （演示把页面的当前时间钉在那一刻，今天按 Asia/Shanghai）。演示自带的「演示模式」提示条不入画。
// 每张图都先等页面上该有的东西出现；等不到、或画面里有「加载中」「读取失败」「未录制」一类字样，
// 这张就算失败，不写文件。拍出来与已有的图逐像素相同时也不重写，免得只换了压缩字节。
// 退码：0 全拍成；1 有图没拍成；2 跑不起来。
//
// 不占端口、不出网：浏览器以 --remote-debugging-pipe 起，页面开在 http://localhost:8000，
// 那里没有服务，每个请求都由调试协议接住、从导出的演示目录读文件回给浏览器，别处的请求一律拦下。

import {execFileSync, spawn} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {deflateSync, inflateSync} from 'node:zlib';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ORIGIN = 'http://localhost:8000';
const VIEWPORT = {width: 1440, height: 900};
const SETTLE_MS = 500;
const WAIT_LIMIT_MS = 15000;
const COMMAND_LIMIT_MS = 30000;
// macOS 上一次截太大的图会卡住，按块截再拼起来，见 tools/console-browser-check/README.md
const TILE_PIXELS = 480000;
// 画面里出现这些字样就说明没拍到该拍的东西
const BAD_WORDS = ['加载中', '读取中', '读取失败', '未录制', '无法获取', '出错了'];
const STREAMER = '/config/#/streamers/bilibili/990000000102';

class CannotRun extends Error {}

// ---------- 每张图怎么拍 ----------
//
// open：打开的地址；steps：打开后在页面里依次做的事；frame：取景。
// frame 为 'viewport' 时截整个窗口；为 'page' 时截整页（宽 1440，高随内容）；
// 否则是一个页面表达式，算出要截的矩形 {x, y, width, height}（CSS 像素，以页面左上角为原点）。

/** 页面里用的小工具，先注入再执行各步 */
const HELPERS = `(() => {
  if (window.__shot) return;
  const visible = el => !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  const norm = s => (s || '').replace(/\\s+/g, ' ').trim();
  window.__shot = {
    visible,
    /** 文字恰好是 text（去掉首尾空白）的可见元素，取最里层那个 */
    byText(text, selector = '*', root = document) {
      const all = [...root.querySelectorAll(selector)].filter(el => visible(el) && norm(el.textContent) === text);
      return all.find(el => !all.some(other => other !== el && el.contains(other))) || null;
    },
    /** 文字以 text 开头的可见元素，取最里层那个 */
    byPrefix(text, selector = '*', root = document) {
      const all = [...root.querySelectorAll(selector)].filter(el => visible(el) && norm(el.textContent).startsWith(text));
      return all.find(el => !all.some(other => other !== el && el.contains(other))) || null;
    },
    /** 包住 el、带 selector 的最近祖先 */
    up(el, selector) { return el ? el.closest(selector) : null; },
    /** 元素在页面上的矩形，四周各放出 pad 像素 */
    box(el, pad = 0) {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return {x: r.left + scrollX - pad, y: r.top + scrollY - pad, width: r.width + 2 * pad, height: r.height + 2 * pad};
    },
    /** 包住几个元素的矩形 */
    union(els, pad = 0) {
      const boxes = els.map(el => window.__shot.box(el, pad));
      if (boxes.some(b => !b)) return null;
      const x = Math.min(...boxes.map(b => b.x)), y = Math.min(...boxes.map(b => b.y));
      return {x, y, width: Math.max(...boxes.map(b => b.x + b.width)) - x,
        height: Math.max(...boxes.map(b => b.y + b.height)) - y};
    },
    /** 打开着的抽屉 */
    drawer() { return [...document.querySelectorAll('dialog.sc-drawer[open]')].pop() || null; },
  };
})()`;

/** 一步：在页面里求值；值为假就一直等，到时限算失败 */
const until = (label, expression) => ({label, expression});
/** 一步：在页面里执行一次，返回假算失败 */
const act = (label, expression) => ({label, expression, once: true});
/** 一步：点文字恰好是 text 的元素（selector 限定种类，root 是页面表达式） */
const click = (text, selector = 'button,a,summary,label,[role=tab],[role=button]', root = 'document') =>
  act(`点「${text}」`, `(() => { const el = __shot.byText(${JSON.stringify(text)}, ${JSON.stringify(selector)}, ${root});
    if (!el) return false; el.click(); return true; })()`);
/** 一步：等到页面上出现文字恰好是 text 的可见元素 */
const sees = (text, selector = '*') =>
  until(`等「${text}」出现`, `!!__shot.byText(${JSON.stringify(text)}, ${JSON.stringify(selector)})`);
/** 一步：等到页面上有以 text 开头的可见元素 */
const seesPrefix = (text, selector = '*') =>
  until(`等「${text}…」出现`, `!!__shot.byPrefix(${JSON.stringify(text)}, ${JSON.stringify(selector)})`);

/** 抽屉的取景：宽固定为 width，高放到能装下抽屉里全部内容 */
const drawerSteps = width => [
  until('等抽屉打开', '!!__shot.drawer()'),
  act(`抽屉宽设为 ${width}`, `(() => { const d = __shot.drawer(); d.style.width = '${width}px'; return true; })()`),
];

const SETUP_CARD = `__shot.box(__shot.up(__shot.byText('设置引导'), '.nv-card'), 20)`;
const CHANNEL = '/config/#/push/990000000101/990000000301';
// 通道页的卡片四周留 9 像素，图宽 822，与手册里原来的图同宽
const card = title => `__shot.up(__shot.byText(${JSON.stringify(title)}, 'h3'), '.nv-card')`;
const settingsGroup = title => `__shot.up(__shot.byText(${JSON.stringify(title)}, '.grp-head'), '.group')`;
/** 抽屉：高取抽屉头加上抽屉里全部内容 */
const DRAWER_HEIGHT = `(() => { const d = __shot.drawer();
  return d.querySelector('.sc-drawer-head').offsetHeight + d.querySelector('.sc-drawer-body').scrollHeight; })()`;
const DRAWER = `({...__shot.box(__shot.drawer()), height: ${DRAWER_HEIGHT}})`;

export const SHOTS = [
  {
    name: '01-home',
    note: '首页整页，含侧栏',
    open: '/config/#/home',
    steps: [sees('今日事件', 'h3'), sees('健康自检', 'h3'), seesPrefix('静音时段')],
    frame: 'page',
  },
  {
    name: '04-setup-step1',
    note: '设置引导第 1 步「设置控制台密码」',
    open: '/config/#/setup',
    steps: [sees('设置引导'), sees('设置密码', 'button')],
    frame: SETUP_CARD,
  },
  {
    name: '04-setup-step2',
    note: '设置引导第 2 步「连接机器人」',
    open: '/config/#/setup',
    steps: [sees('设置引导'), click('连接机器人', '.su-t'), sees('测试连接', 'button')],
    frame: SETUP_CARD,
  },
  {
    name: '06-template-editor',
    note: '通道页「推送内容」，展开开播通知的模板编辑器',
    open: CHANNEL,
    steps: [sees('推送内容', 'h3'),
      act('展开开播通知', `(() => { const row = __shot.byText('开播通知', '*', ${card('推送内容')});
        let el = row; while (el && !el.querySelector('button')) el = el.parentElement;
        const edit = el && [...el.querySelectorAll('button')].find(b => b.textContent.trim().startsWith('编辑'));
        if (!edit) return false; edit.click(); return true; })()`),
      sees('收起 ▾', 'button'), seesPrefix('消息预览')],
    frame: `__shot.box(${card('推送内容')}, 9)`,
  },
  {
    name: '07-group-settings',
    note: '通道页「通道规则」「群内命令」（展开）「本群状态」三块',
    open: CHANNEL,
    steps: [sees('通道规则', 'h3'),
      act('展开群内命令', `(() => { const s = __shot.byPrefix('群内命令', 'summary'); if (!s) return false;
        s.closest('details').open = true; return true; })()`)],
    frame: `__shot.union([${card('通道规则')}, __shot.byPrefix('群内命令', 'summary').closest('.nv-card'),
      ${card('本群状态')}], 9)`,
  },
  {
    name: '09-dashboard',
    note: '主播页（常青）总览近 30 天',
    open: STREAMER,
    steps: [sees('本期表现'), sees('本期观众'), sees('开播日历'), seesPrefix('当前直播与最近归档')],
    frame: `__shot.box(document.querySelector('section.page.on'), 4)`,
  },
  {
    name: '09-sessions-drawer',
    note: '「直播场次」抽屉',
    open: STREAMER,
    steps: [seesPrefix('当前直播与最近归档'), click('查看 10 场归档', 'a,button'), ...drawerSteps(820),
      until('等场次列表', `/共 \\d+ 条/.test(__shot.drawer().textContent)`)],
    stretch: DRAWER_HEIGHT,
    frame: DRAWER,
  },
  {
    name: '09-trend-drawer',
    note: '「趋势明细」抽屉，按统计日、流水总量',
    open: STREAMER,
    steps: [sees('近期趋势'), click('查看明细', 'a,button'), ...drawerSteps(820),
      until('等周期明细', `/共 \\d+ 条/.test(__shot.drawer().textContent)`)],
    stretch: DRAWER_HEIGHT,
    frame: DRAWER,
  },
  {
    name: '09-rhythm-drawer',
    note: '「开播节奏明细」抽屉，近 90 天、每小时流水',
    open: STREAMER,
    steps: [sees('开播日历'), click('查看历史节奏', 'a,button'), ...drawerSteps(1000),
      until('等直播分区对比', `__shot.drawer().textContent.includes('直播分区对比')`)],
    stretch: DRAWER_HEIGHT,
    frame: DRAWER,
  },
  {
    name: '09-audience-drawer',
    note: '「本期观众名单」抽屉',
    open: STREAMER,
    steps: [sees('本期观众'), click('查看本期名单', 'a,button'), ...drawerSteps(820),
      until('等名单', `/共 \\d+ 条/.test(__shot.drawer().textContent)`)],
    stretch: DRAWER_HEIGHT,
    frame: DRAWER,
  },
  {
    name: '10-quiet-banner',
    note: '首页顶上的静音提示条与待办、连接状态',
    open: '/config/#/home',
    steps: [seesPrefix('静音时段'), sees('连接状态', 'h3')],
    frame: `__shot.union([__shot.up(__shot.byPrefix('静音时段'), 'div,section'), ${card('待办')}, ${card('连接状态')}], 8)`,
  },
  {
    name: '10-quiet-search',
    note: '设置页搜「静音」',
    open: '/config/#/settings',
    steps: [until('等搜索框', `!!document.getElementById('set-search')`),
      act('搜「静音」', `(() => { const input = document.getElementById('set-search'); input.value = '静音';
        input.dispatchEvent(new Event('input', {bubbles: true})); return true; })()`),
      seesPrefix('匹配 3 项')],
    frame: `(() => { const page = __shot.box(document.querySelector('section.page.on'), 8);
      const top = __shot.box(document.getElementById('set-search'), 8).y;
      const groups = [...document.querySelectorAll('#groups .group')].filter(__shot.visible);
      const bottom = Math.max(...groups.map(g => __shot.box(g, 8)).map(b => b.y + b.height));
      return {x: page.x, y: top, width: page.width, height: bottom - top}; })()`,
  },
  {
    name: '10-alert-channels',
    note: '设置页「告警」组，三张卡全部展开',
    open: '/config/#/settings',
    steps: [sees('告警', 'button.pill'), click('告警', 'button.pill'),
      act('展开三张告警卡', `(() => { const cards = [...document.querySelectorAll('details.alcard')].filter(__shot.visible);
        cards.forEach(c => { c.open = true; }); return cards.length === 3; })()`)],
    frame: `(() => { const group = __shot.box(${settingsGroup('告警')}, 8);
      const first = __shot.box([...document.querySelectorAll('details.alcard')].find(__shot.visible));
      const top = first.y - 23;
      return {x: group.x, y: top, width: group.width, height: group.y + group.height - top}; })()`,
  },
  {
    name: '11-log',
    note: '日志页一屏',
    open: '/config/#/log',
    steps: [seesPrefix('共 10 条')],
    frame: 'viewport',
  },
  {
    name: '12-settings-push',
    note: '设置页在分组菜单里点了「推送」',
    open: '/config/#/settings',
    steps: [sees('推送', 'button.pill'), click('推送', 'button.pill'), seesPrefix('全局推送开关')],
    frame: 'viewport',
  },
];

// ---------- 浏览器 ----------

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

/** macOS 上用 net.Socket 读 FIFO 时大响应会卡住，所以非阻塞地直接读 */
class FifoReader {
  constructor(fd) {
    this.fd = fd;
    this.listeners = [];
    const buffer = Buffer.alloc(65536);
    this.timer = setInterval(() => {
      try {
        for (let total = 0; total < 1024 * 1024;) {
          const n = fs.readSync(fd, buffer, 0, buffer.length, null);
          if (!n) break;
          total += n;
          const chunk = Buffer.from(buffer.subarray(0, n));
          for (const listener of this.listeners) listener(chunk);
        }
      } catch (e) {
        if (e.code !== 'EAGAIN' && e.code !== 'EWOULDBLOCK') this.onError?.(e);
      }
    }, 5);
  }

  on(event, listener) {
    if (event === 'data') this.listeners.push(listener);
    if (event === 'error') this.onError = listener;
  }

  destroy() {
    clearInterval(this.timer);
    fs.closeSync(this.fd);
  }
}

/** 起浏览器，调试协议走两条 FIFO：fd 3 浏览器读、fd 4 浏览器写 */
async function launch(chrome, profile, logFd) {
  const toBrowser = path.join(profile, 'cdp-in');
  const fromBrowser = path.join(profile, 'cdp-out');
  try {
    execFileSync('mkfifo', [toBrowser, fromBrowser]);
  } catch (e) {
    throw new CannotRun(`建不了调试协议用的命名管道（mkfifo）：${e.message}`);
  }
  const {O_RDWR, O_WRONLY, O_NONBLOCK} = fs.constants;
  const browserReads = fs.openSync(toBrowser, O_RDWR);
  const weWrite = fs.openSync(toBrowser, O_WRONLY);
  const weRead = fs.openSync(fromBrowser, O_RDWR | O_NONBLOCK);
  const browserWrites = fs.openSync(fromBrowser, O_WRONLY);
  const args = [
    '--headless', '--remote-debugging-pipe', `--user-data-dir=${path.join(profile, 'user-data')}`,
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-default-apps', '--disable-extensions', '--disable-sync',
    '--disable-breakpad', '--metrics-recording-only', '--password-store=basic', '--use-mock-keychain',
    '--mute-audio', '--hide-scrollbars', '--force-color-profile=srgb', '--no-proxy-server',
    '--lang=zh-CN', '--host-resolver-rules=MAP * ~NOTFOUND',
  ];
  // macOS 的沙箱不许默认起法派生的子进程
  if (process.platform === 'darwin') args.push('--single-process');
  args.push('about:blank');
  const child = spawn(chrome, args, {stdio: ['ignore', logFd, logFd, browserReads, browserWrites]});
  for (const fd of [browserReads, browserWrites]) fs.closeSync(fd);
  const input = new FifoReader(weRead);
  const output = fs.createWriteStream(toBrowser, {fd: weWrite});
  const cdp = new Cdp(input, output);
  input.on('error', e => cdp.close(e));
  output.on('error', e => cdp.close(e));
  const exited = new Promise(resolve => {
    child.on('error', e => { cdp.close(new CannotRun(`起不了浏览器：${e.message}`)); resolve(); });
    child.on('exit', (code, signal) => {
      cdp.close(new CannotRun(`浏览器退出了（退码 ${code}，信号 ${signal}）`));
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
    await cdp.send('Browser.getVersion');
  } catch (e) {
    await stop();
    throw new CannotRun(`浏览器没有应答调试协议：${e.message}`);
  }
  return {cdp, stop};
}

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2',
};

/** 把拦住的请求从导出目录应答 */
function answer(cdp, sessionId, demo, shot, {requestId, request}) {
  const send = (method, extra) => cdp.send(method, {requestId, ...extra}, sessionId).catch(() => {});
  const url = new URL(request.url);
  if (url.origin !== ORIGIN) return send('Fetch.failRequest', {errorReason: 'BlockedByClient'});
  let file = null;
  if (request.method === 'GET') {
    const candidate = path.resolve(demo, '.' + decodeURIComponent(url.pathname));
    if (candidate.startsWith(demo + path.sep)) {
      const stat = fs.statSync(candidate, {throwIfNoEntry: false});
      if (stat?.isDirectory() && url.pathname.endsWith('/')) file = path.join(candidate, 'index.html');
      else if (stat?.isDirectory()) {
        return send('Fetch.fulfillRequest', {responseCode: 301, body: '',
          responseHeaders: [{name: 'Location', value: url.pathname + '/' + url.search}]});
      } else if (stat?.isFile()) file = candidate;
    }
  }
  if (!file || !fs.existsSync(file)) {
    shot.problems.add(`请求没有应答：${request.method} ${url.pathname}${url.search}`);
    return send('Fetch.fulfillRequest', {responseCode: 404, body: '',
      responseHeaders: [{name: 'Content-Type', value: 'text/plain'}]});
  }
  return send('Fetch.fulfillRequest', {responseCode: 200, body: fs.readFileSync(file).toString('base64'),
    responseHeaders: [{name: 'Content-Type', value: TYPES[path.extname(file)] || 'application/octet-stream'},
      {name: 'Cache-Control', value: 'no-store'}]});
}

// ---------- PNG 拼接（只用 node 自带模块；浏览器出的是 8 位 RGB/RGBA） ----------

const CRC_TABLE = Array.from({length: 256}, (_, n) => {
  for (let i = 0; i < 8; i++) n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1;
  return n >>> 0;
});

function pngChunk(type, data) {
  const name = Buffer.from(type);
  let crc = 0xffffffff;
  for (const byte of Buffer.concat([name, data])) crc = CRC_TABLE[(crc ^ byte) & 255] ^ (crc >>> 8);
  const out = Buffer.alloc(data.length + 12);
  out.writeUInt32BE(data.length);
  name.copy(out, 4);
  data.copy(out, 8);
  out.writeUInt32BE((crc ^ 0xffffffff) >>> 0, data.length + 8);
  return out;
}

function pngRows(png) {
  let header;
  const compressed = [];
  for (let offset = 8; offset + 12 <= png.length;) {
    const length = png.readUInt32BE(offset);
    const type = png.toString('ascii', offset + 4, offset + 8);
    if (type === 'IHDR') header = png.subarray(offset + 8, offset + 8 + length);
    if (type === 'IDAT') compressed.push(png.subarray(offset + 8, offset + 8 + length));
    offset += length + 12;
  }
  if (!header || header[8] !== 8 || ![2, 6].includes(header[9]) || header[12]) throw new Error('不能拼接这种 PNG 编码');
  const width = header.readUInt32BE(0), height = header.readUInt32BE(4);
  const channels = header[9] === 6 ? 4 : 3;
  const stride = width * channels;
  const raw = inflateSync(Buffer.concat(compressed));
  const rows = Buffer.alloc(raw.length);
  for (let y = 0; y < height; y++) {
    const start = y * (stride + 1);
    const filter = raw[start];
    for (let x = 1; x <= stride; x++) {
      const a = x > channels ? rows[start + x - channels] : 0;
      const b = y ? rows[start + x - stride - 1] : 0;
      const c = y && x > channels ? rows[start + x - stride - 1 - channels] : 0;
      let predictor = 0;
      if (filter === 1) predictor = a;
      if (filter === 2) predictor = b;
      if (filter === 3) predictor = Math.floor((a + b) / 2);
      if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        predictor = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      rows[start + x] = (raw[start + x] + predictor) & 255;
    }
  }
  return {header, width, height, rows};
}

function joinPng(pieces) {
  const parts = pieces.map(pngRows);
  const header = Buffer.from(parts[0].header);
  if (parts.some(p => p.width !== parts[0].width || !p.header.subarray(8).equals(parts[0].header.subarray(8)))) {
    throw new Error('分块宽度或颜色格式不同');
  }
  header.writeUInt32BE(parts.reduce((sum, p) => sum + p.height, 0), 4);
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(Buffer.concat(parts.map(p => p.rows)), {level: 9})), pngChunk('IEND', Buffer.alloc(0))]);
}

/** 已有的图与新拍的像素逐个相同时为真：这时不重写文件，免得只换了压缩字节 */
function samePixels(file, png) {
  if (!fs.existsSync(file)) return false;
  try {
    const before = pngRows(fs.readFileSync(file)), after = pngRows(png);
    return before.header.equals(after.header) && before.rows.equals(after.rows);
  } catch (e) {
    return false;
  }
}

// ---------- 拍一张 ----------

async function shoot(cdp, demo, spec) {
  const shot = {name: spec.name, problems: new Set()};
  const {targetId} = await cdp.send('Target.createTarget', {url: 'about:blank'});
  try {
    const {sessionId} = await cdp.send('Target.attachToTarget', {targetId, flatten: true});
    const s = (method, params, limit) => cdp.send(method, params, sessionId, limit);
    const inflight = new Set();
    let lastActivity = Date.now();
    let loaded = false;
    cdp.sessions.set(sessionId, (method, params) => {
      if (method === 'Fetch.requestPaused') answer(cdp, sessionId, demo, shot, params);
      else if (method === 'Network.requestWillBeSent') { inflight.add(params.requestId); lastActivity = Date.now(); }
      else if (method === 'Network.loadingFinished' || method === 'Network.loadingFailed') {
        inflight.delete(params.requestId);
        lastActivity = Date.now();
      } else if (method === 'Page.loadEventFired') { loaded = true; lastActivity = Date.now(); }
      else if (method === 'Runtime.exceptionThrown') {
        shot.problems.add('页面脚本异常：' + (params.exceptionDetails.exception?.description
          || params.exceptionDetails.text || '').split('\n')[0]);
      } else if (method === 'Runtime.consoleAPICalled' && params.type === 'warning') {
        const text = params.args.map(a => a.value ?? '').join(' ');
        if (text.startsWith('Console demo: unrecorded')) shot.problems.add('演示没录：' + text.replace(/^Console demo: /, ''));
      }
    });
    await s('Storage.clearDataForStorageKey', {storageKey: ORIGIN + '/', storageTypes: 'all'});
    await s('Fetch.enable', {patterns: [{urlPattern: '*', requestStage: 'Request'}]});
    await s('Network.enable');
    await s('Network.setCacheDisabled', {cacheDisabled: true});
    await s('Page.enable');
    await s('Runtime.enable');
    await s('Emulation.setDeviceMetricsOverride', {...VIEWPORT, deviceScaleFactor: 1, mobile: false});
    await s('Emulation.setEmulatedMedia', {features: [{name: 'prefers-color-scheme', value: 'light'}]});
    await s('Emulation.setTimezoneOverride', {timezoneId: 'Asia/Shanghai'});
    await s('Emulation.setLocaleOverride', {locale: 'zh-CN'});
    await s('Page.addScriptToEvaluateOnNewDocument', {source:
      "try { localStorage.setItem('novabot-theme', 'light'); localStorage.setItem('novabot-palette', 'pink-blue'); } catch (e) {}"});

    const quiet = async () => {
      const started = Date.now();
      while (Date.now() - started < WAIT_LIMIT_MS) {
        if (loaded && inflight.size === 0 && Date.now() - lastActivity >= SETTLE_MS) return;
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      throw new Error(`${WAIT_LIMIT_MS / 1000} 秒内页面没静下来`);
    };
    const evaluate = async expression => {
      const {result, exceptionDetails} = await s('Runtime.evaluate',
        {expression, returnByValue: true, awaitPromise: true, userGesture: true});
      if (exceptionDetails) throw new Error((exceptionDetails.exception?.description || exceptionDetails.text).split('\n')[0]);
      return result.value;
    };

    const navigation = await s('Page.navigate', {url: ORIGIN + spec.open});
    if (navigation.errorText) throw new Error(`打不开 ${spec.open}：${navigation.errorText}`);
    await quiet();
    for (const step of spec.steps) {
      await evaluate(HELPERS);
      if (step.once) {
        if (!await evaluate(step.expression)) throw new Error(`${step.label}：没做成`);
        lastActivity = Date.now();
        await quiet();
        continue;
      }
      const started = Date.now();
      while (!await evaluate(step.expression)) {
        if (Date.now() - started > WAIT_LIMIT_MS) throw new Error(`${step.label}：${WAIT_LIMIT_MS / 1000} 秒没等到`);
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    }
    await quiet();
    await evaluate(HELPERS);
    // 演示自带的提示条不入画；输入框的光标与焦点也去掉
    await evaluate(`(() => {
      for (const id of ['console-demo-banner', 'console-demo-notice']) {
        const el = document.getElementById(id); if (el) el.style.display = 'none';
      }
      if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
      const style = document.createElement('style');
      // 过渡与动画停在终态，两次拍出来才逐像素一致
      style.textContent = '*,*::before,*::after{caret-color:transparent!important;'
        + 'transition:none!important;animation:none!important}';
      document.head.appendChild(style);
    })()`);

    // 抽屉高 100dvh、内容在抽屉里滚动：把窗口撑到装得下抽屉全部内容的高度，整只抽屉才排得开
    if (spec.stretch) {
      const height = Math.ceil(await evaluate(spec.stretch));
      if (!(height > 0)) throw new Error('撑高窗口：量不出高度');
      if (height > VIEWPORT.height) {
        await s('Emulation.setDeviceMetricsOverride', {width: VIEWPORT.width, height, deviceScaleFactor: 1, mobile: false});
        lastActivity = Date.now();
        await quiet();
      }
    }
    let clip;
    if (spec.frame === 'viewport') {
      clip = {x: 0, y: 0, ...VIEWPORT};
    } else if (spec.frame === 'page') {
      const height = await evaluate('Math.ceil(document.documentElement.scrollHeight)');
      clip = {x: 0, y: 0, width: VIEWPORT.width, height};
    } else {
      clip = await evaluate(spec.frame);
      if (!clip) throw new Error('取景：找不到要截的那块');
    }
    clip = {x: Math.round(clip.x), y: Math.round(clip.y), width: Math.round(clip.width), height: Math.round(clip.height)};
    if (clip.width <= 0 || clip.height <= 0) throw new Error('取景：矩形是空的');

    const text = await evaluate(`(() => {
      const r = ${JSON.stringify(clip)};
      const words = ${JSON.stringify(BAD_WORDS)};
      const hits = new Set();
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const el = n.parentElement;
        if (!el || !__shot.visible(el)) continue;
        const b = el.getBoundingClientRect();
        const x = b.left + scrollX, y = b.top + scrollY;
        if (x + b.width <= r.x || x >= r.x + r.width || y + b.height <= r.y || y >= r.y + r.height) continue;
        for (const w of words) if (n.textContent.includes(w)) hits.add(n.textContent.trim().slice(0, 60));
      }
      return [...hits];
    })()`);
    for (const hit of text) shot.problems.add(`画面里有「${hit}」`);
    if (shot.problems.size) return shot;

    const step = Math.max(1, Math.floor(TILE_PIXELS / clip.width));
    const pieces = [];
    for (let y = 0; y < clip.height; y += step) {
      const piece = await s('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true,
        clip: {x: clip.x, y: clip.y + y, width: clip.width, height: Math.min(step, clip.height - y), scale: 1}});
      pieces.push(Buffer.from(piece.data, 'base64'));
    }
    shot.png = joinPng(pieces);
    shot.size = `${clip.width}×${clip.height}`;
    return shot;
  } catch (e) {
    if (cdp.closed) throw cdp.closed;
    shot.problems.add(e.message);
    return shot;
  } finally {
    await cdp.send('Target.closeTarget', {targetId}, undefined, 5000).catch(() => {});
  }
}

// ---------- 入口 ----------

async function main() {
  const {values, positionals} = parseArgs({allowPositionals: true, options: {
    publish: {type: 'boolean'}, chrome: {type: 'string'}, list: {type: 'boolean'}, help: {type: 'boolean', short: 'h'},
  }});
  if (values.help) {
    const head = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n');
    process.stdout.write(head.slice(0, head.findIndex(line => !line.startsWith('//'))).map(l => l.replace(/^\/\/ ?/, '')).join('\n') + '\n');
    return 0;
  }
  if (values.list) {
    for (const spec of SHOTS) process.stdout.write(`${spec.name}\t${spec.note}\n`);
    return 0;
  }
  const unknown = positionals.map(n => n.replace(/\.png$/, '')).filter(n => !SHOTS.some(s => s.name === n));
  if (unknown.length) throw new CannotRun(`没有这几张图：${unknown.join('、')}（--list 列出能拍的）`);
  const wanted = positionals.length ? SHOTS.filter(s => positionals.map(n => n.replace(/\.png$/, '')).includes(s.name)) : SHOTS;
  const chromeGiven = values.chrome || process.env.NOVA_CHROME;
  if (!chromeGiven) throw new CannotRun('没给浏览器：设环境变量 NOVA_CHROME，或用 --chrome 浏览器路径');
  let chrome;
  try {
    chrome = fs.realpathSync(chromeGiven);
  } catch (e) {
    throw new CannotRun(`浏览器不存在：${chromeGiven}`);
  }
  const out = values.publish ? path.join(ROOT, 'manual/images') : path.join(ROOT, 'target/manual-shots');
  fs.mkdirSync(out, {recursive: true});

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'nova-manual-shots-'));
  try {
    const demo = path.join(work, 'demo');
    try {
      execFileSync('bash', [path.join(ROOT, 'tools/demo-assets/export-console-demo.sh'), demo], {stdio: ['ignore', 'ignore', 'pipe']});
    } catch (e) {
      throw new CannotRun(`导出离线演示失败：${String(e.stderr || e.message).trim()}`);
    }
    const profile = path.join(work, 'profile');
    fs.mkdirSync(profile);
    const logFd = fs.openSync(path.join(work, 'browser.log'), 'w');
    const browser = await launch(chrome, profile, logFd);
    let failed = 0;
    try {
      for (const spec of wanted) {
        const shot = await shoot(browser.cdp, demo, spec);
        if (shot.png && !shot.problems.size) {
          const file = path.join(out, spec.name + '.png');
          const same = samePixels(file, shot.png);
          if (!same) fs.writeFileSync(file, shot.png);
          process.stdout.write(`拍成 ${spec.name}.png ${shot.size}${same ? '（像素与原有的图相同，原文件不动）' : ''}\n`);
        } else {
          failed++;
          process.stdout.write(`没拍成 ${spec.name}：${[...shot.problems].join('；')}\n`);
        }
      }
    } finally {
      await browser.stop();
      fs.closeSync(logFd);
    }
    process.stdout.write(`合计 ${wanted.length} 张：拍成 ${wanted.length - failed}，没拍成 ${failed}；写在 ${path.relative(ROOT, out) || out}\n`);
    return failed ? 1 : 0;
  } finally {
    fs.rmSync(work, {recursive: true, force: true});
  }
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main();
  } catch (e) {
    process.stderr.write(`跑不起来：${e instanceof CannotRun ? e.message : e.stack || e}\n`);
    process.exitCode = 2;
  }
}
