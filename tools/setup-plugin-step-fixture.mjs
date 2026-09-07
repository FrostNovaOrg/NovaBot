/**
 * 向导插件步：源码契约、步骤表插位、事实两态、放行与跳过的转交
 *
 * 九问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 用 node 直接跑：
 *   node tools/setup-plugin-step-fixture.mjs
 * 入口：bash tools/setup-plugin-step-fixture.sh
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {allDone, stepFacts} from '../starbot-core/src/main/resources/config-ui/setup-model.js';
import {withPluginSteps} from '../starbot-core/src/main/resources/config-ui/setup-model.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const ui = join(root, 'starbot-core/src/main/resources/config-ui');

const failures = [];
let checks = 0;

function ask(name, fn) {
  checks++;
  try {
    fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function src(name) {
  return readFileSync(join(ui, name), 'utf8');
}

/** 从声明处截到下一个顶层 function 之前 */
function fnBody(text, marker) {
  const from = text.indexOf(marker);
  if (from < 0) return '';
  let to = text.length;
  const next = text.indexOf('\nfunction ', from + 1);
  const nextAsync = text.indexOf('\nasync function ', from + 1);
  if (next >= 0) to = Math.min(to, next);
  if (nextAsync >= 0) to = Math.min(to, nextAsync);
  return text.slice(from, to);
}

function count(hay, needle) {
  if (!needle) return 0;
  let n = 0;
  let from = 0;
  while (true) {
    const at = hay.indexOf(needle, from);
    if (at < 0) return n;
    n++;
    from = at + needle.length;
  }
}

ask('① setup.js 不含 SETUP_STEPS[ 与 SETUP_STEPS.length', () => {
  const text = src('setup.js');
  const a = count(text, 'SETUP_STEPS[');
  const b = count(text, 'SETUP_STEPS.length');
  if (a !== 0 || b !== 0) {
    throw new Error('SETUP_STEPS[ ' + a + ' 处，SETUP_STEPS.length ' + b + ' 处，应为 0／0');
  }
});

ask('② setup.js 含 /config/assets/ 与 withPluginSteps(', () => {
  const text = src('setup.js');
  if (!text.includes("'/config/assets/'")) {
    throw new Error("缺少 '/config/assets/'");
  }
  if (!text.includes('withPluginSteps(')) {
    throw new Error('缺少 withPluginSteps(');
  }
});

ask("③ home-model.js 不含 from './setup-model.js'", () => {
  const text = src('home-model.js');
  if (text.includes("from './setup-model.js'")) {
    throw new Error("仍含 from './setup-model.js'");
  }
});

ask('④ 假页清单插在登录直播平台之后、试发之前', () => {
  const pages = [{id: 'danmu', displayName: '弹幕', order: 50, slot: 'setup_step'}];
  const table = withPluginSteps(pages);
  const keys = table.map(step => step.key);
  if (table.length !== 5) {
    throw new Error('步数得到 ' + table.length + '，应为 5');
  }
  const accountAt = keys.indexOf('account');
  const danmuAt = keys.indexOf('danmu');
  const testAt = keys.indexOf('test');
  if (!(accountAt >= 0 && danmuAt === accountAt + 1 && testAt === danmuAt + 1)) {
    throw new Error('键序得到 ' + JSON.stringify(keys) + '，danmu 应在 account 后、test 前');
  }
});

ask('⑤ pluginDone 两态下 allDone 相反', () => {
  const pages = [{id: 'danmu', displayName: '弹幕', order: 50, slot: 'setup_step'}];
  const table = withPluginSteps(pages);
  const status = {
    locked: true,
    health: [{scope: 'BOT', level: 'OK', name: '机器人连接'}],
  };
  const login = {accounts: [{loggedIn: true}]};
  const yes = allDone(stepFacts(status, login, true, {danmu: true}, pages), table);
  const no = allDone(stepFacts(status, login, true, {}, pages), table);
  if (yes === no) {
    throw new Error('两态 allDone 同为 ' + yes + '，应为相反');
  }
  if (yes !== true || no !== false) {
    throw new Error('pluginDone 真时应齐、缺省时应不齐，得到 ' + yes + '／' + no);
  }
});

ask("⑥ main.js 含 SLOT_SETUP_STEP = 'setup_step' 且分派处引用它", () => {
  const text = src('main.js');
  if (!text.includes("SLOT_SETUP_STEP = 'setup_step'")) {
    throw new Error("缺少 SLOT_SETUP_STEP = 'setup_step'");
  }
  const from = text.indexOf('async function mountPages(');
  if (from < 0) {
    throw new Error('找不到 mountPages，分派处无从量起');
  }
  let to = text.length;
  const next = text.indexOf('\nfunction ', from + 1);
  const nextAsync = text.indexOf('\nasync function ', from + 1);
  if (next >= 0) to = Math.min(to, next);
  if (nextAsync >= 0) to = Math.min(to, nextAsync);
  const body = text.slice(from, to);
  if (!body.includes('meta.slot === SLOT_SETUP_STEP')) {
    throw new Error('分派处未引用 SLOT_SETUP_STEP');
  }
});

ask('⑦ skippable 步 foot 出跳过、非 skippable 不出', () => {
  const body = fnBody(src('setup.js'), 'function renderPlugin(');
  if (!body) {
    throw new Error('找不到 renderPlugin');
  }
  if (!body.includes('skippable')) {
    throw new Error('renderPlugin 未读 skippable');
  }
  if (!body.includes('跳过')) {
    throw new Error('skippable 步 foot 未出「跳过」');
  }
  if (body.includes('foot(host, null,')) {
    throw new Error('renderPlugin 的 foot 仍写死 onSkip=null，skippable 步出不了跳过');
  }
});

ask('⑧ 放行与跳过都转交给那一步自己判', () => {
  const text = src('setup.js');
  const body = fnBody(text, 'function renderPlugin(');
  if (!body.includes('pluginNext(') || !body.includes('skipPlugin(')) {
    throw new Error('renderPlugin 的 foot 没转交 next／skip，插件步会变成点一下就过');
  }
  const next = fnBody(text, 'async function pluginNext(');
  if (!next || !next.includes('mod.next(') || !next.includes('return true')) {
    throw new Error('pluginNext 没问过 mod.next，或没有「插件步不报 next 就放行」的兜底');
  }
  const skip = fnBody(text, 'async function skipPlugin(');
  if (!skip || !skip.includes('mod.skip(') || !skip.includes('finishStep')) {
    throw new Error('skipPlugin 没问过 mod.skip，或问完不跳');
  }
});

ask('⑨ 走完那一页的去处问插件要，核心不自己拼地址', () => {
  const body = fnBody(src('setup.js'), 'function renderDone(');
  if (!body) {
    throw new Error('找不到 renderDone');
  }
  if (!body.includes('doneLink')) {
    throw new Error('renderDone 没问过插件步的 doneLink');
  }
  if (!body.includes('link.href')) {
    throw new Error('renderDone 没用插件给的那个地址，等于白问了一趟');
  }
  // 这一段里写得出的地址只许是核心自己那几条路由。插件页的地址由那一页自己拼，
  // 核心抄一份的话，两份分叉的那天点进去会落到别处，而屏幕上看不出任何异常
  const CORE_ROUTES = ['home', 'log', 'links', 'settings', 'setup'];
  const written = [...body.matchAll(/'#\/([A-Za-z0-9_-]*)/g)].map(m => m[1]);
  const stray = written.filter(name => !CORE_ROUTES.includes(name));
  if (stray.length) {
    throw new Error('renderDone 自己拼了非核心路由的地址：' + JSON.stringify(stray));
  }
  if (!written.length) {
    throw new Error('地址扫描 0 命中：「进控制台」那一句应留在这里，写法已变而扫描没跟上');
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
