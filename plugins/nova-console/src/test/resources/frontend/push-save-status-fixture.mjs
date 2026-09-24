/**
 * 推送页按「保存」之后，状态栏要说得出这一趟到底存成了什么
 *
 * 从前写死一句「已保存」：服务端那句「重启后生效」「配置将自动重新加载」被丢掉，
 * 使用者不知道要不要重启；两段都存了时也只剩一句。反过来，本群设置一处没改、
 * 推送配置存失败时，提示里却写着「本群设置已存」——那一段根本没发过请求。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，save 用的是产品码那一份。
 * 只 includes 一段字样是不行的——写死「已保存」照样含「已保存」三个字。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 PushSaveStatusTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

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

/** 下一趟 GET /datasource 回什么 */
let datasourceBody = '';

/** 下一趟 POST /datasource 回什么：成败与那句话都由各问自己摆 */
let datasourceReply = {success: true, message: '已保存'};

/** 下一趟 POST /state/… 回什么 */
let stateReply = {success: true, message: '已保存'};

/** 挂在 <html> 上的那几个类。底部那条藏不藏由它一处定，见宿主 core.js 的 paintBar */
const htmlClasses = new Set();

globalThis.document = {
  querySelector: node,
  createElement: tag => {
    const e = {
      tagName: String(tag).toLowerCase(),
      className: '',
      textContent: '',
      style: {},
      children: [],
      appendChild(child) {
        this.children.push(child);
        return child;
      },
      setAttribute() {},
      addEventListener() {},
      classList: {toggle() {}, add() {}, remove() {}, contains() { return false; }},
    };
    return e;
  },
  documentElement: {
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
  body: {appendChild() {}},
  addEventListener() {},
  get activeElement() {
    return null;
  },
};

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  const url = String(path);
  requests.push({path: url, method, body: (opt || {}).body});
  const ok = payload => Promise.resolve({status: 200, json: () => Promise.resolve(payload)});
  if (method === 'GET') {
    if (url.endsWith('/datasource')) return ok({content: datasourceBody});
    if (url.endsWith('/state')) return ok({sessions: []});
    if (url.includes('/push-history')) return ok({records: []});
    if (url.includes('/at-all/quota')) return ok({bots: [], sessions: []});
    if (url.includes('/bot/targets')) return ok({items: []});
    return ok({});
  }
  if (url.includes('/datasource')) return ok(datasourceReply);
  if (url.includes('/state/')) return ok(stateReply);
  return ok({success: true, message: '已保存'});
};

/** 老使用者的推送配置：一位主播推到一个群 */
function datasource() {
  return [{
    platform: 'p', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: 'q', type: 1, num: 12345, enabled: true,
      messages: [{handler: 'x.LiveOn', params: {message: '开播了'}}],
    }],
  }];
}

let push = null;
let draft = null;
try {
  push = await import(join(pages, 'push.js'));
  draft = await import(join(pages, 'session-draft.js'));
} catch (err) {
  failures.push('载入 push.js／session-draft.js 就抛了：' + err.message);
}

const TARGET = {platform: 'q', type: 1, num: 12345};

/** 回到「刚打开这一页」：推送配置干净、本群设置草稿清空、状态栏空着 */
async function fresh() {
  datasourceBody = JSON.stringify(datasource());
  datasourceReply = {success: true, message: '已保存'};
  stateReply = {success: true, message: '已保存'};
  requests.length = 0;
  await push.reload();
  node('#status-text').textContent = '';
}

function statusText() {
  return String(node('#status-text').textContent);
}

function pushPosts() {
  return requests.filter(item => item.method === 'POST' && item.path.includes('/datasource'));
}

function statePosts() {
  return requests.filter(item => item.method === 'POST' && item.path.includes('/state/'));
}

// ---- 存推送配置成功：状态栏是服务端回的那句 ----

await ask('① 存推送配置成功，状态栏写服务端那句（含「重启后生效」）', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  datasourceReply = {success: true, message: '已保存，重启后生效'};
  await push.save();
  const said = statusText();
  same({
    '含服务端那句': said.includes('重启后生效'),
    '不提本群设置': !said.includes('本群设置'),
    '没发本群设置': statePosts().length === 0,
  }, {
    '含服务端那句': true,
    '不提本群设置': true,
    '没发本群设置': true,
  }, '推送成功提示');
});

await ask('② 自动重新加载的机器上，写的是「配置将自动重新加载」', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  datasourceReply = {success: true, message: '已保存，配置将自动重新加载'};
  await push.save();
  const said = statusText();
  same({
    '含服务端那句': said.includes('自动重新加载'),
    '不提本群设置': !said.includes('本群设置'),
  }, {
    '含服务端那句': true,
    '不提本群设置': true,
  }, '自动重载提示');
});

// ---- 两句并列 ----

await ask('③ 推送配置与本群设置都存了：两句并列', async () => {
  if (!push || !draft) throw new Error('产品码没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  draft.setRevenueDraft(TARGET, true, false);
  datasourceReply = {success: true, message: '已保存，重启后生效'};
  stateReply = {success: true, message: '「甲」的金额已设为可见'};
  await push.save();
  const said = statusText();
  same({
    '有推送那句': said.includes('重启后生效'),
    '有本群那句': said.includes('金额已设为可见'),
    '两句并列': said.includes(' · '),
  }, {
    '有推送那句': true,
    '有本群那句': true,
    '两句并列': true,
  }, '两句并列');
});

await ask('④ 只改本群设置：状态栏写本群那段服务端那句', async () => {
  if (!push || !draft) throw new Error('产品码没载入，无从量起');
  await fresh();
  draft.setRevenueDraft(TARGET, true, false);
  stateReply = {success: true, message: '「甲」的金额已设为可见'};
  await push.save();
  const said = statusText();
  same({
    '有本群那句': said.includes('金额已设为可见'),
    '没发推送配置': pushPosts().length === 0,
  }, {
    '有本群那句': true,
    '没发推送配置': true,
  }, '只存本群');
});

// ---- 本群设置没有改动时，提示里不提本群设置 ----

await ask('⑤ 本群设置没动、推送配置存失败：提示里不提本群设置', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  datasourceReply = {success: false, message: '推送配置有误，已拒绝保存'};
  await push.save();
  const said = statusText();
  same({
    '不提本群设置': !said.includes('本群设置'),
    '说了推送那段的下场': said.includes('推送') || said.includes('拒绝') || said.includes('失败'),
    '没发本群设置': statePosts().length === 0,
  }, {
    '不提本群设置': true,
    '说了推送那段的下场': true,
    '没发本群设置': true,
  }, '推送失败不提本群');
});

await ask('⑥ 本群设置没动、推送配置存成功：提示里也不提本群设置', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  datasourceReply = {success: true, message: '已保存，重启后生效'};
  await push.save();
  const said = statusText();
  same({
    '不提本群设置': !said.includes('本群设置'),
    '含服务端那句': said.includes('重启后生效'),
    '没发本群设置': statePosts().length === 0,
  }, {
    '不提本群设置': true,
    '含服务端那句': true,
    '没发本群设置': true,
  }, '推送成功也不提本群');
});

// ---- 一段成一段败 ----

await ask('⑦ 推送成、本群败：说出推送那句，也说本群没存上', async () => {
  if (!push || !draft) throw new Error('产品码没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  draft.setRevenueDraft(TARGET, true, false);
  datasourceReply = {success: true, message: '已保存，重启后生效'};
  stateReply = {success: false, message: '这个群没找到'};
  await push.save();
  const said = statusText();
  same({
    '有推送那句': said.includes('重启后生效'),
    '说了本群没存上': said.includes('本群设置'),
  }, {
    '有推送那句': true,
    '说了本群没存上': true,
  }, '本群失败要提');
});

await ask('⑧ 推送败、本群成：与推送失败那句并列写明本群设置已存', async () => {
  if (!push || !draft) throw new Error('产品码没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  draft.setRevenueDraft(TARGET, true, false);
  datasourceReply = {success: false, message: '推送配置有误，已拒绝保存'};
  stateReply = {success: true, message: '「甲」的金额已设为可见'};
  await push.save();
  const said = statusText();
  same({
    '说了推送没存上': said.includes('推送'),
    '写明本群设置已存': said.includes('本群设置已存'),
    '两句并列': said.includes(' · '),
    '也带着服务端那句': said.includes('金额已设为可见'),
  }, {
    '说了推送没存上': true,
    '写明本群设置已存': true,
    '两句并列': true,
    '也带着服务端那句': true,
  }, '一段成一段败时，成的那段不能被括号埋掉');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
