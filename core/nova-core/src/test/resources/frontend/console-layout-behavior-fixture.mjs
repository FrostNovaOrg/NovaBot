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
  // 用户故障：机器人掉线时，同一个群的同一种失败在首页刷成一长串。
  const timeout = {type:'PUSH_FAILED', level:'error', text:'推送失败：连接超时', channel:'群 A', streamer:'主播 A'};
  const failures = [500, 900, 300].map((at, i) => ({...timeout, at,
    detail:{platform:'bot', streamerPlatform:'site', streamerUid:'7', elapsed_ms:String(100 + i)}}));
  const grouped = homeModel({}, {}, {events:failures}).events;
  check(() => assert.equal(grouped.length, 1, '只有耗时不同的同种失败应合成一张卡'));
  check(() => assert.equal(grouped[0].count, 3, '合并次数应包含每次不同耗时的失败'));
  check(() => assert.equal(grouped[0].at, 900, '合并卡取最近时间，不依赖输入顺序'));
  check(() => assert.equal(grouped[0].detail.elapsed_ms, '101', '保留最近一次的详情'));
  const separate = [
    {...failures[0], channel:'群 B'},
    {...failures[0], text:'推送失败：权限不足'},
    {...failures[0], detail:{...failures[0].detail, platform:'another-bot'}},
    {...failures[0], detail:{...failures[0].detail, streamerUid:'8'}},
    {...failures[0], detail:{...failures[0].detail, reason:'另一原因'}},
  ];
  check(() => assert.equal(homeModel({}, {}, {events:[failures[0], ...separate]}).events.length, 6,
    '不同目标、原因或平台的失败仍各自显示'));
  const strip = el('div');
  const renderStrip = compile(read('overview.js'), ['function renderStrip('], {
    $:()=>strip, esc:String, clock:String,
  });
  renderStrip({events:[grouped[0], {...separate[0], count:1, group:'attention'}]});
  check(() => assert.match(strip.textContent, /×3，最近 900/));
  check(() => assert.doesNotMatch(strip.textContent, /×1(?:，|\D|$)/, '单次失败不应标注 ×1'));
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
      $: () => null,
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
  // 用户故障：引导里点「再加一个」后底栏消失，加的主播存不下来。
  const users = [{platform:'site', uid:7, uname:'已有主播'}];
  const {defaultMessages} = await import('../../../../../../plugins/nova-console/src/main/resources/config-ui-pages/push-model.js');
  const deps = {
    el, store:{platforms:['site'], handlerList:[]}, STREAMER_INPUT_HINT:'uid', esc:String,
    detailHash:(platform, uid) => '#/streamers/'+platform+'/'+uid,
    targetOptions:()=>[{key:'g1', sender:'bot', type:1, num:42, text:'群 A'}],
    api:async (path, opts) => {
      if (path.startsWith('/bot/targets')) return [];
      if (path === '/streamer/lookup') return {success:true, uid:8, uname:'新增主播'};
      assert.equal(path, '/datasource/users');
      const {user} = JSON.parse(opts.body);
      check(() => assert.deepEqual(user, {platform:'site', uid:8, enabled:true,
        targets:[{platform:'bot', type:1, num:42, enabled:true, messages:[]}]}));
      users.push({...user, uname:'新增主播'});
      return {success:true, version:'saved'};
    },
    pushEntries:()=>[], defaultMessages, trackSetupVersions(){}, acceptAddedPushUser(){},
    renderStreamers(){}, markDirty(){}, say:assert.fail,
  };
  const functions = ['function note(', 'function field(', 'function invalidateStreamer(',
    'async function loadOptions(', 'export function render(', 'function paintFound(',
    'function targetPicker(', 'export async function next(', 'function stop(', 'async function saveStreamer('];
  const mod = new Function(...Object.keys(deps),
    'let seen = {}, result, options = []; const draft = {streamer:null, targets:[]};\n'
    + functions.map(name=>bracedFrom(plugin, name).replace(/^export /, '')).join('\n')
    + '\nreturn {render, next};')(...Object.values(deps));
  const host = el('div');
  const step = {key:'streamer', plugin:true};
  const ctx = () => ({status:{users}});
  let finished = false;
  const renderPlugin = compile(src, ['function foot(', 'function renderPlugin('], {
    el, pluginMods:{streamer:mod}, pluginCtx:ctx, facts:[false, true], current:1,
    steps:[{}, step, {}], note:deps.el, syncFoot(){}, skipPlugin(){},
    pluginNext:()=>mod.next(ctx()), refreshFacts:async()=>{}, finishStep:()=>{finished=true;},
    goStep(){},
  });
  renderPlugin(host, step);
  check(() => assert.match(host.textContent, /已有主播/));
  check(() => assert.equal(host.querySelector('input'), null, '已配主播时先显示现状'));
  const allowed = await mod.next(ctx());
  check(() => assert.equal(allowed, true, '已有主播可以直接继续，无需重新添加'));
  const add = host.querySelector('button');
  check(() => assert.match(add?.textContent || '', /再加一个/));
  add.click();
  check(() => assert.ok(host.querySelector('input'), '再加一个仍可打开输入框'));
  check(() => assert.ok(host.querySelector('#setup-back'), '再加一个后必须保留上一步'));
  check(() => assert.ok(host.querySelector('#setup-next'), '再加一个后必须保留下一步以保存主播'));
  host.querySelector('#setup-uid').value = '8';
  await host.querySelector('#setup-lookup').listeners.click[0]();
  const deadline = Date.now() + 10000;
  let target;
  while (!(target = host.querySelectorAll('input').find(input=>input.type === 'checkbox'))) {
    assert.ok(Date.now() < deadline, '查询后可以选择推送目标');
    await new Promise(resolve=>setImmediate(resolve));
  }
  target.checked = true;
  target.listeners.change[0]();
  await host.querySelector('#setup-next').listeners.click[0]();
  check(() => assert.equal(finished, true, '新主播保存成功后继续下一步'));
  check(() => assert.deepEqual(users.map(user=>user.uid), [7, 8], '原有主播保留，新主播已保存'));
  host.innerHTML = '';
  renderPlugin(host, step);
  check(() => assert.match(host.textContent, /已完成.*2 位主播/));
  check(() => assert.match(host.textContent, /已有主播.*新增主播/));
  check(() => assert.equal(host.querySelector('input'), null, '返回已完成步骤先显示最新名单'));
  host.querySelector('button').click();
  check(() => assert.equal(host.querySelector('#setup-uid').value, '', '再添加时查询新的主播'));
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
