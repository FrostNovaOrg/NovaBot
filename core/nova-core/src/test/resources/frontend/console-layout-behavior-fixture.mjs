/** 用户故障：重复失败淹没首页、已配步骤仍催重填、其他登录方式抢走密码提交、取消退出后焦点藏起。 */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {MiniNode, bracedFrom} from './layout-dom.mjs';
import {healthText, homeModel} from '../../../main/resources/config-ui/home-model.js';
import {loginView, loginControlState} from '../../../main/resources/config-ui/login-model.js';
const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const read = name => readFileSync(new URL(name, ui), 'utf8');
const mode = process.argv[2];
let checks = 0;
const check = fn => { checks++; fn(); };
const el = (tag, cls) => { const n = new MiniNode(tag); n.className = cls || ''; return n; };
function compile(src, names, deps) {
  return new Function(...Object.keys(deps), names.map(n => bracedFrom(src, n).replace(/^export /, '')).join('\n')
    + '\nreturn ' + names.at(-1).match(/function (\w+)/)[1] + ';')(...Object.values(deps));
}
if (mode === 'home') {
  const failure = {type: 'AT_ALL_FAILED', level: 'warn', text: '@全体成员 未发出', channel: '群 A'};
  const events = [900, 300, 700, 500].map(at => ({...failure, at}));
  events.push({...failure, at: 800, channel: '群 B'});
  events.push({at: 200, level: 'info', text: '较早'}, {at: 1000, level: 'info', text: '最新'});
  const rows = homeModel({}, {}, {events, problems: {events: events.filter(e => e.level !== 'info'), matched: 5}}).events;
  check(() => assert.equal(rows.filter(e => e.channel === '群 A').length, 1, '同一种同一目标失败应只占一张卡'));
  check(() => assert.equal(rows.find(e => e.channel === '群 A').count, 4, '合并次数应是四次'));
  check(() => assert.equal(rows.find(e => e.channel === '群 A').at, 900, '合并卡必须显示最近一次的时间'));
  check(() => assert.deepEqual(rows.filter(e => e.level === 'info').map(e => e.at), [1000, 200]));
  check(() => assert.equal(rows.find(e => e.channel === '群 B').count, 1, '不同目标不能合并'));
  const quiet = '412 0 次/7 天，-352 0 次/时，-509 0 次/时，-401 0 次/日，质询 0 次/日，快照缺失 0 次/日，1006 0 次/时（0 次/日）';
  check(() => assert.equal(healthText(quiet), '近 7 天没有被风控'));
  check(() => assert.equal(healthText(quiet.replace('-352 0', '-352 2')), '近 1 小时被限流 2 次'));
  check(() => assert.match(healthText('填 spring.data.redis.host'), /设置 › 采集 › 累计数据 Redis · 地址/));
  for (const interrupted of [false, true]) {
    let captured;
    const source = read('overview.js');
    const refreshHome = compile(source, ['export async function refreshHome()'], {
      api: async path => {
        if (path === '/status') return {};
        if (path.includes('cursor=')) {
          if (interrupted) throw new Error('连接中断');
          return {events:[{...failure, at:300}], nextCursor:null};
        }
        if (path.includes('problems=true')) return {events:[{...failure, at:900}], matched:2, nextCursor:'older'};
        return {events:[]};
      },
      store:{role:'MEMBER'}, today:()=> '2026-01-01', considerSetupRedirect:()=>false,
      renderStatus(){}, renderHome:(status,login,timeline)=>{captured=homeModel(status,login,timeline);},
      refreshPages(){}, say(){},
    });
    await refreshHome();
    check(() => assert.equal(captured.events[0].count, interrupted ? 1 : 2,
      '成功翻页时合计全天次数，翻页失败时只显示实际读到的次数'));
    check(() => assert.equal(captured.events[0].at, 900));
    if (interrupted) check(() => assert.match(captured.events.at(-1).text, /另有 1 条/));
  }
} else if (mode === 'mine') {
  const {bindMineMenu} = await import(new URL('personal-menu.js', ui));
  const {ask} = await import(new URL('confirm.js', ui));
  const toggle = el('button'), panel = el('div'), logout = el('button');
  document.body.appendChild(toggle); document.body.appendChild(panel); panel.appendChild(logout);
  bindMineMenu(toggle, panel); toggle.click(); logout.focus();
  const pending = ask({title: '退出控制台？', body: '未保存的改动将丢失'});
  document.querySelector('[data-act="no"]').click();
  const result = await pending;
  check(() => assert.equal(result, false));
  check(() => assert.ok(panel.classList.contains('open') && document.activeElement === logout
    || !panel.classList.contains('open') && document.activeElement === toggle,
    '取消退出后，焦点必须落在可见的退出按钮或我的按钮'));
} else if (mode === 'setup') {
  const src = read('setup.js');
  let skip;
  const stepAccount = compile(src, ['function accountCard(', 'function stepAccount('], {
    el, heading() {}, titleOf: () => '', note: (kind, text) => {const n=el('p'); n.textContent=text; return n;},
    seen: {login: {accounts: [{displayName:'直播平台', loggedIn:true, accountId:'12345'}]}},
    foot: (host, action) => {skip=action;}, chooseAnonymous() {}, schedulePoll() {},
  });
  const account = el('div'); stepAccount(account);
  check(() => assert.match(account.textContent, /12345/));
  check(() => assert.equal(skip, null, '已有登录不应再催选择免登录'));
  check(() => assert.ok(account.querySelector('a'), '已登录后仍可换账号'));
  const plugin = readFileSync(new URL('../../../../../../plugins/nova-console/src/main/resources/config-ui-pages/setup-streamer.js', import.meta.url), 'utf8');
  const users = [{platform:'site', uid:7, uname:'已有主播'}];
  const store = {platforms:['site']}; const draft = {streamer:null, targets:[]};
  const render = compile(plugin, ['function note(', 'function field(', 'export function render('], {
    el, store, draft, STREAMER_INPUT_HINT:'uid', esc:String, paintFound() {}, loadOptions:async()=>{},
    detailHash: (platform, uid) => '#/streamers/'+platform+'/'+uid,
  });
  const host = el('div'); render(host, {status:{users}});
  check(() => assert.match(host.textContent, /已有主播/));
  check(() => assert.equal(host.querySelector('input'), null, '已配主播时先显示现状'));
  const next = compile(plugin, ['export async function next('], {store, draft});
  const allowed = await next({status:{users}});
  check(() => assert.equal(allowed, true, '已有主播可以直接继续，无需重新添加'));
  const add = host.querySelector('button');
  check(() => assert.match(add?.textContent || '', /再加一个/));
  add.click();
  check(() => assert.ok(host.querySelector('input'), '再加一个仍可打开输入框'));
} else if (mode === 'login') {
  const src = read('login.html');
  document.body.innerHTML = src.slice(src.indexOf('<body>')+6, src.indexOf('<script type="module">'));
  const $ = sel => document.querySelector(sel);
  const paint = compile(src, ['function paint()'], { $, document, loginView, loginControlState,
    hasPasskey:true, totp:false, lockedSeconds:0, oidcEnabled:true, oidcCreateSupported:true, oidcResult:'',
    folded:false, focusedButton:'', userInteracted:true, lastAutomaticFocus:null });
  paint(); $('#pwd-fold').open = true;
  check(() => assert.ok(!$('#submit').classList.contains('ghost') && !$('#submit').classList.contains('secondary'), '密码块的登录应是主按钮'));
  const form = $('#form');
  const buttons = form.querySelectorAll('button');
  const submit = buttons.find(n => n.getAttribute('type') === 'submit' || !n.getAttribute('type'));
  check(() => assert.equal(submit?.id, 'submit', '回车的默认提交按钮仍是密码登录'));
  const calls = [];
  const handlerStart = src.indexOf("$('#form').addEventListener('submit'");
  const handler = src.slice(handlerStart, src.indexOf('\n</script>', handlerStart));
  let entered = false;
  new Function('$','fetch','CONSOLE','markInvalidCredentials','enterConsole','paint','tick', handler)(
    $, async (url, opts) => {calls.push({url, body:JSON.parse(opts.body)}); return {json:async()=>({success:true})};},
    '/config', ()=>{}, ()=>{entered=true;}, paint, ()=>{});
  $('#password').value = 'example-password'; $('#code').value='';
  await form.listeners.submit[0]({preventDefault(){}});
  check(() => assert.deepEqual(calls, [{url:'/config/api/auth/login', body:{password:'example-password',code:''}}]));
  check(() => assert.equal(entered, true));
}
console.log('跑了 ' + checks + ' 格，红 0 格');
