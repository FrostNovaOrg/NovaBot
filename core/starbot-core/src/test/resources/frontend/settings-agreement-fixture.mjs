/**
 * 设置页那张「使用协议」卡：撤回同意必须先问一次，正文只有服务端那一份
 *
 * 撤回把整台机器打回未签态，所有人都要重新同意才进得了控制台。这种动作只要少了确认，
 * 一次误点就够了——而误点之后什么也没坏，只是全体被关在门外，看起来像面板出了故障。
 *
 * 三件事各自量：
 *   ① 点一下只弹确认，答复之前一次请求也不发；确认后恰发一次；取消一次也不发
 *   ② 正文取自 /auth/agreement，源码里不留第二份——留了第二份的话，
 *      改了 agreement.txt 而没改这里，卡片上显示的就不是使用者签的那一份
 *   ③ 三个 agreement 键由这张卡吃掉，不再以普通输入框摊在「登录与安全」组里
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。由 SettingsAgreementCardTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {existsSync, readFileSync, readdirSync, statSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..', '..', '..', '..');
const ui = join(repo, 'core/starbot-core/src/main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/**
 * 一段判定，抛出来的也记成红
 *
 * 分段捕获而不是让第一处抛出去把整个夹具打断：断在第一格的话，后面那些格
 * <b>一格没跑</b>，而屏幕上「红 1 格」与「只有这一格坏」长得一模一样。
 */
async function section(what, run) {
  try {
    await run();
  } catch (e) {
    checks++;
    failures.push(what + ' 这一段抛了：' + e.message);
  }
}

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个空行」。
 */
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

/**
 * 从 marker 起，按方括号配平截出一个数组字面量
 *
 * 字段表是 {@code new Set([...])}，里面没有花括号——拿 bracedFrom 去截会一路截到
 * 下一个函数体，看起来像「取到了」而实际取的是别处。
 */
function bracketFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('[', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '[') depth++;
    else if (c === ']') {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return '';
}

const auth = read('settings-auth.js');

// ---- ① 二次确认：答复之前一次请求也不发 ----

const revokeSrc = bracedFrom(auth, 'async function revokeAgreement');
eq(revokeSrc.length > 0, true, '找得到 revokeAgreement');

/**
 * 把撤回那一段真跑起来
 * @param answer 确认框的答复，可以是一个还没定的 Promise
 */
function runner(answer) {
  const asks = [];
  const calls = [];
  const jumps = [];
  const ask = spec => {
    asks.push(spec);
    return answer;
  };
  const api = (path, options) => {
    calls.push({path, options: options || {}});
    return Promise.resolve({success: true});
  };
  const report = (box, res) => {
    box.said = res;
  };
  const location = {assign: to => jumps.push(to)};
  const fn = new Function('ask', 'api', 'report', 'location',
    revokeSrc + '\nreturn revokeAgreement;')(ask, api, report, location);

  return {fn, asks, calls, jumps};
}

await section('取消不发请求', async () => {
  const cancelled = runner(Promise.resolve(false));
  await cancelled.fn({}, {});
  eq(cancelled.asks.length, 1, '点一下恰弹一次确认');
  eq(cancelled.asks[0].danger, true, '确认框要走危险那一版');
  eq(String(cancelled.asks[0].body || '').length > 0, true, '确认框里要写清后果');
  eq(cancelled.calls.length, 0, '取消之后一次请求也不发');
  eq(cancelled.jumps.length, 0, '取消之后不跳页');
});

await section('确认后恰发一次', async () => {
  const confirmed = runner(Promise.resolve(true));
  await confirmed.fn({}, {});
  eq(confirmed.calls.length, 1, '确认之后恰发一次请求');
  eq(confirmed.calls[0].path, '/auth/agreement/revoke', '发的是撤回口');
  eq(String(confirmed.calls[0].options.method || '').toUpperCase(), 'POST', '撤回是写请求，走 POST');
  eq(confirmed.jumps, ['/config'], '撤回成功后整页回到 /config，落在协议那一屏');
});

// 🔴 「首次点击不发请求」单独量：上面两条只看得到答复之后的样子，
// 而误点要拦住的恰恰是「确认框还立着，请求已经发出去了」这一种
await section('答复之前不发请求', async () => {
  let release = null;
  const pending = new Promise(resolve => {
    release = resolve;
  });
  const waiting = runner(pending);
  const running = waiting.fn({}, {});
  await Promise.resolve();
  await Promise.resolve();
  eq(waiting.asks.length, 1, '确认框已经弹出来了');
  eq(waiting.calls.length, 0, '确认框还没答复时，一次请求也没发出去');
  release(true);
  await running;
  eq(waiting.calls.length, 1, '答复「确认」之后才发，且恰一次');
});

// ---- ② 正文只有服务端那一份 ----

const carriers = [];

await section('正文只此一份', async () => {
  const cardSrc = bracedFrom(auth, 'function agreementCard');
  eq(cardSrc.length > 0, true, '找得到 agreementCard');
  eq(cardSrc.includes('/auth/agreement'), true, '卡片正文取自 /auth/agreement');

  const agreement = readFileSync(join(ui, 'agreement.txt'), 'utf8');
  const probe = agreement.split('\n').find(line => line.startsWith('NovaBot 是 Nova 系列'));
  eq(typeof probe === 'string' && probe.length > 20, true, '取得到协议正文首句');

  // 扫的是仓，不是某一个目录：第二份正文最可能被抄进的正是卡片这一侧的源码，
  // 而只盯着 config-ui/ 的话，抄进 java 里的那一份看不见
  const skip = new Set(['.git', 'target', 'scratch', 'node_modules', 'dist', '.idea', '.m2-lane']);
  const sweep = dir => {
    for (const name of readdirSync(dir)) {
      if (skip.has(name)) continue;
      const path = join(dir, name);
      let info;
      try {
        info = statSync(path);
      } catch {
        continue;
      }
      if (info.isDirectory()) {
        sweep(path);
        continue;
      }
      if (!info.isFile() || info.size > 4 * 1024 * 1024) continue;
      let body = '';
      try {
        body = readFileSync(path, 'utf8');
      } catch {
        continue;
      }
      if (body.includes(probe)) carriers.push(path.slice(repo.length + 1));
    }
  };

  eq(existsSync(join(repo, 'build.sh')) && existsSync(join(repo, 'pom.xml')), true,
    '仓根定位对了，否则下面那一扫扫的是别处');
  sweep(repo);
  eq(carriers, ['core/starbot-core/src/main/resources/config-ui/agreement.txt'],
    '协议正文全仓只此一份，卡片与登录页都向它要');
});

// ---- ③ 三个 agreement 键归这张卡，不再是普通输入框 ----

await section('协议三键归卡片', async () => {
  const fieldsSrc = bracketFrom(auth, 'export const AUTH_CARD_FIELDS = new Set(');
  eq(fieldsSrc.length > 0, true, '找得到 AUTH_CARD_FIELDS');
  const cardFields = new Function('return ' + fieldsSrc + ';')();
  const wanted = [
    'starbot.core.config-ui.agreement.accepted-version',
    'starbot.core.config-ui.agreement.accepted-at',
    'starbot.core.config-ui.agreement.accepted-by',
  ];
  for (const key of wanted) {
    eq(cardFields.includes(key), true, key + ' 已被这张卡吃掉');
  }

  const settings = read('settings.js');
  eq(settings.includes('AUTH_CARD_FIELDS.has(field.name)) continue'), true,
    '设置页仍按 AUTH_CARD_FIELDS 把这几项从普通行里摘走');

  // 分母现算：这三个键确实在配置面上（否则「摘走」摘的是空气），
  // 而「登录与安全」组的普通行数应恰好因此少三行
  const keys = readFileSync(
    join(repo, 'core/starbot-core/src/test/resources/configuration-baseline/config-keys.txt'), 'utf8')
    .split('\n')
    .map(line => line.split('|')[0])
    .filter(name => name.startsWith('starbot.core.config-ui.'));
  eq(keys.length > 0, true, '「登录与安全」组的配置键取得到，分母不为零');
  for (const key of wanted) {
    eq(keys.includes(key), true, key + ' 本来就是配置面上的一项');
  }

  // 差值拿两张真名单相减，不写死组里有几项：写死的话，这一组日后新加一个键就会红，
  // 而红的理由与协议卡毫无关系
  const before = ['starbot.core.config-ui.auth.password',
    'starbot.core.config-ui.auth.totp',
    'starbot.core.config-ui.auth.totp-secret'];
  const ordinaryBefore = keys.filter(key => !before.includes(key)).length;
  const ordinaryAfter = keys.filter(key => !cardFields.includes(key)).length;
  eq(ordinaryAfter > 0, true, '分母非零：这一组仍有普通行');
  eq(ordinaryBefore - ordinaryAfter, 3, '「登录与安全」组的普通输入框比本笔之前正好少三行');

  console.log('「登录与安全」组配置键 ' + keys.length + ' 个，普通行 '
    + ordinaryBefore + ' → ' + ordinaryAfter + '；协议正文载体 ' + carriers.length + ' 处');
});
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
