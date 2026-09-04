/**
 * 告警那一组：两行 ＋ 三张卡
 *
 * 告警在配置表里是十六个平铺的键，其中九个是「Webhook 的字段名叫什么」「SMTP 端口是多少」
 * 这类只有在配某一路时才有意义的东西。平铺着看，使用者分不出哪几项是一伙的，
 * 也看不出「我到底配通了几路」。于是这里把它们按路收成三张卡，每张卡自己回答两件事：
 * 这一路配好了没有（右上角的药丸），以及它现在到底通不通（底下的「发一条测试」）。
 *
 * 「发一条测试」按的是真的发送路径。地址填错一个字母、授权码过期、机器人被踢出那个群，
 * 三者在配置文件上都长得完全正常，而它们的共同后果是真出事那天没有人收到告警。
 */

import {$, api, el, markDirty} from './core.js';
import {store} from './store.js';
import {mailAlertConfigured} from './alert-model.js';

/**
 * 三张卡各自吃掉哪几个配置键
 *
 * 告警组里剩下的项照常渲染成普通行。这张表是唯一的依据——设置页那边据它把这几项从行里摘走，
 * 卡片这边据它把这几项摆进卡里。两处各写一份的话，摘走了却没人摆的那一项会凭空消失。
 */
export const CARD_FIELDS = new Set([
  'starbot.core.alert.qq-platform', 'starbot.core.alert.qq-type', 'starbot.core.alert.qq-num',
  'starbot.core.alert.webhook-url', 'starbot.core.alert.webhook-method',
  'starbot.core.alert.webhook-title-field', 'starbot.core.alert.webhook-content-field',
  'starbot.core.mail.default-to',
  'spring.mail.host', 'spring.mail.port', 'spring.mail.username', 'spring.mail.password',
]);

/**
 * Webhook 预设：选一个就把提交方式与两个字段名一起填好
 *
 * 各服务的字段名互不相同，而「字段名填错」的表现是接收端收到一条空消息或干脆 400——
 * 都不是使用者能从界面上看出来的东西。预设把这份知识放进程序里，
 * 「自定义」那一档留给自建接口。
 */
const WEBHOOK_PRESETS = {
  'Bark': {method: 'GET', title: 'title', content: 'body'},
  'Server 酱': {method: 'POST', title: 'title', content: 'desp'},
  'PushDeer': {method: 'POST', title: 'text', content: 'desp'},
  '自建接口（JSON）': {method: 'POST', title: 'title', content: 'content'},
};

/**
 * 邮件预设：常见邮箱的 SMTP 服务器与端口
 */
const MAIL_PRESETS = {
  'QQ 邮箱': {host: 'smtp.qq.com', port: '465'},
  '163 邮箱': {host: 'smtp.163.com', port: '465'},
  'Gmail': {host: 'smtp.gmail.com', port: '587'},
};

/** 自定义那一档的名字，两处预设共用 */
const CUSTOM = '自定义';

/**
 * 取一项的当前值：草稿优先，其次已保存的值
 * @param name 配置项名
 * @return {string} 当前值
 */
function valueOf(name) {
  if (store.dirty[name] !== undefined) return store.dirty[name];
  const saved = store.values[name];
  return saved === undefined || saved === null ? '' : String(saved);
}

/**
 * 记一处改动
 *
 * 与设置页普通行走的是同一条记账路径（store.dirty ＋ markDirty），
 * 因此卡片里的改动同样会出现在底部改动条上，保存也是同一个按钮。
 * @param name 配置项名
 * @param value 新值
 */
function setValue(name, value) {
  const saved = store.values[name];
  const original = saved === undefined || saved === null ? '' : String(saved);
  if (String(value) === original) delete store.dirty[name];
  else store.dirty[name] = String(value);
  markDirty();
}

/**
 * 卡片里的一栏
 * @param box 容器
 * @param label 人话名
 * @param name 配置项名
 * @param opt 选项：type 控件类型，opts 下拉选项，ph 占位提示，note 小字，onchange 值变了之后
 * @return {HTMLElement} 控件
 */
function field(box, label, name, opt) {
  const o = opt || {};
  const wrap = el('div', 'al-fld');
  const head = el('div', 'al-lb');
  const title = el('label');
  title.textContent = label;
  head.appendChild(title);
  wrap.appendChild(head);

  let input;
  if (o.type === 'select') {
    input = el('select');
    for (const value of o.opts) {
      const option = el('option');
      option.value = value;
      option.textContent = value;
      input.appendChild(option);
    }
  } else {
    input = el('input');
    input.type = o.type === 'password' ? 'password' : (o.type === 'number' ? 'number' : 'text');
    if (o.ph) input.placeholder = o.ph;
  }
  input.value = valueOf(name);
  input.setAttribute('aria-label', label);
  wrap.appendChild(input);

  if (o.note) {
    const note = el('div', 'al-note');
    note.textContent = o.note;
    wrap.appendChild(note);
  }
  const key = el('div', 'keyname');
  key.textContent = name;
  wrap.appendChild(key);

  const on = () => { setValue(name, input.value); if (o.onchange) o.onchange(input.value); };
  input.addEventListener('input', on);
  input.addEventListener('change', on);

  box.appendChild(wrap);
  return input;
}

/**
 * 预设下拉
 *
 * 单独一支而不是复用上面那个 field：预设<b>不是配置项</b>，它没有键名，也不该进改动账。
 * 拿 field 传一个空键名去凑的话，store.dirty 上会多出一个键为空串的条目，
 * 保存时它会被原样送去后端。
 * @param box 容器
 * @param options 可选值
 * @return {HTMLElement} 下拉
 */
function presetField(box, options) {
  const wrap = el('div', 'al-fld');
  const label = el('label');
  label.textContent = '预设';
  wrap.appendChild(label);

  const select = el('select');
  select.setAttribute('aria-label', '预设');
  for (const value of options) {
    const option = el('option');
    option.value = value;
    option.textContent = value;
    select.appendChild(option);
  }
  wrap.appendChild(select);
  box.appendChild(wrap);
  return select;
}

/**
 * 一张卡的壳
 * @param id 卡片标识，也是「发一条测试」要传给后端的通道标识
 * @param title 卡片标题
 * @param desc 一句说明
 * @return {{card: HTMLElement, head: HTMLElement, body: HTMLElement, pill: HTMLElement}} 各部位
 */
function shell(id, title, desc) {
  const card = el('div', 'alcard');
  card.dataset.channel = id;

  const head = el('div', 'al-h');
  const name = el('b');
  name.textContent = title;
  const pill = el('span', 'pill');
  pill.textContent = '未配置';
  head.append(name, pill);
  card.appendChild(head);

  const note = el('p', 'al-d');
  note.textContent = desc;
  card.appendChild(note);

  const body = el('div', 'al-b');
  card.appendChild(body);

  const foot = el('div', 'al-f');
  const test = el('button', 'ghost');
  test.type = 'button';
  test.textContent = '发一条测试';
  test.addEventListener('click', () => sendTest(id, test));
  const result = el('span', 'al-r');
  foot.append(test, result);
  card.appendChild(foot);

  return {card, head, body, pill, result};
}

/**
 * 真的发一条出去
 *
 * 结果写在按钮旁边而不是顶部的状态栏：使用者的视线此刻在这张卡上，
 * 而三张卡各有各的结论，顶部只有一行位置放不下第二条。
 * @param channel 通道标识
 * @param button 那个按钮，发送期间禁用
 */
async function sendTest(channel, button) {
  const box = button.parentElement.querySelector('.al-r');
  if (Object.keys(store.dirty).length) {
    // 发送用的是服务端此刻的配置，不是屏幕上这一份。不说清的话，
    // 使用者会以为自己刚填的地址已经被试过了
    box.textContent = '有改动还没保存，这一条测试用的是保存过的那份配置。';
    box.className = 'al-r warn';
  } else {
    box.textContent = '发送中…';
    box.className = 'al-r';
  }

  button.disabled = true;
  try {
    const res = await api('/alert/test?channel=' + encodeURIComponent(channel), {method: 'POST'});
    box.textContent = res.message || (res.success ? '已发出' : '没发出去');
    box.className = 'al-r ' + (res.success ? 'ok' : 'err');
  } catch (e) {
    box.textContent = '发不出去：' + e.message;
    box.className = 'al-r err';
  }
  button.disabled = false;
}

/**
 * 把「已配置／未配置」写到药丸上
 * @param pill 药丸元素
 * @param configured 配好了没有
 */
function pillState(pill, configured) {
  pill.className = 'pill' + (configured ? ' ok' : '');
  pill.textContent = configured ? '已配置' : '未配置';
}

/**
 * QQ 那一路的「发给谁」
 *
 * 从机器人自己知道的群与好友里挑，<b>没有手填号码的格子</b>：填错一位数不会有任何报错，
 * 只是告警发去了别处，或者哪儿也没去——而这件事只有真出事那天才会被发现。
 *
 * 取不到名单时（没装推送适配器，或它此刻连不上）不退回手填，而是把已配好的那一项
 * 原样显示出来并说明为什么挑不了：退回手填等于把上面那个失败形态又请回来。
 * @param box 卡片内容区
 * @param pill 药丸
 */
async function qqTarget(box, pill) {
  const wrap = el('div', 'al-fld');
  const label = el('label');
  label.textContent = '发给谁';
  wrap.appendChild(label);

  const select = el('select');
  select.id = 'alert-qq-target';
  select.setAttribute('aria-label', '告警发给谁');
  wrap.appendChild(select);
  const hint = el('div', 'al-note');
  wrap.appendChild(hint);
  const key = el('div', 'keyname');
  key.textContent = 'starbot.core.alert.qq-num';
  wrap.appendChild(key);
  box.appendChild(wrap);

  const platform = valueOf('starbot.core.alert.qq-platform');
  const type = valueOf('starbot.core.alert.qq-type');
  const num = valueOf('starbot.core.alert.qq-num');
  const currentKey = num ? platform + '|' + type + '|' + num : '';

  const options = [];
  // 空档要能选回来：配过之后想撤掉这一路，除了选「不发到 QQ」没有别的路
  options.push({key: '', text: '不发到 QQ'});
  if (currentKey) options.push({key: currentKey, text: '当前：' + (platform || '未知平台') + ' '
    + (String(type) === '1' ? '群' : '好友') + ' ' + num});

  let reachable = true;
  try {
    const [groups, friends] = await Promise.all([
      api('/onebot/targets?type=group'), api('/onebot/targets?type=friend')]);
    for (const row of (groups.items || [])) {
      options.push({key: row.sender + '|1|' + row.num, text: '群 · ' + (row.name || row.num) + '（' + row.num + '）'});
    }
    for (const row of (friends.items || [])) {
      options.push({key: row.sender + '|0|' + row.num,
        text: '好友 · ' + (row.remark || row.nickname || row.num) + '（' + row.num + '）'});
    }
  } catch (e) {
    reachable = false;
  }

  // 「当前」那一条与名单里的同一个目标会重复，按 key 去重后仍保留先出现的那一条
  const seen = new Set();
  for (const item of options) {
    if (seen.has(item.key)) continue;
    seen.add(item.key);
    const option = el('option');
    option.value = item.key;
    option.textContent = item.text;
    select.appendChild(option);
  }
  select.value = currentKey;

  hint.textContent = reachable
    ? (seen.size <= 2 ? '机器人还没有在任何群里，也没有加好友。把它拉进一个群，再回来挑。' : '')
    : '暂时取不到机器人的群与好友名单，只能先显示已配好的那一个。到连接页看看机器人连上了没有。';

  select.addEventListener('change', () => {
    const parts = select.value ? select.value.split('|') : ['', '0', ''];
    setValue('starbot.core.alert.qq-platform', parts[0]);
    setValue('starbot.core.alert.qq-type', parts[1]);
    setValue('starbot.core.alert.qq-num', parts[2]);
    pillState(pill, !!parts[2]);
  });

  pillState(pill, !!num);
}

/**
 * 建出三张卡
 * @return {HTMLElement} 卡片容器
 */
export function alertCards() {
  const wrap = el('div', 'alcards');
  wrap.id = 'alert-cards';

  // ---- QQ ----
  const qq = shell('qq', 'QQ', '走机器人自己的推送链路。QQ 掉线的时候，这一路也一起掉——'
    + '而那正是最需要收到告警的时刻，所以别只配这一路。');
  qqTarget(qq.body, qq.pill);
  wrap.appendChild(qq.card);

  // ---- Webhook ----
  const hook = shell('webhook', 'Webhook', '推到手机上的通知类应用。机器人掉线时只有这一路还活着。');
  const hookCustom = el('div', 'al-cus');
  const preset = presetField(hook.body, Object.keys(WEBHOOK_PRESETS).concat(CUSTOM));
  const url = field(hook.body, '地址', 'starbot.core.alert.webhook-url',
    {ph: 'https://……', onchange: v => pillState(hook.pill, !!String(v).trim())});
  hook.body.appendChild(hookCustom);
  const method = field(hookCustom, '提交方式', 'starbot.core.alert.webhook-method',
    {type: 'select', opts: ['POST', 'GET']});
  const titleField = field(hookCustom, '标题字段名', 'starbot.core.alert.webhook-title-field', {ph: 'title'});
  const contentField = field(hookCustom, '内容字段名', 'starbot.core.alert.webhook-content-field',
    {ph: 'content', note: '各家不一样：Bark 用 body，Server 酱用 desp。'});

  // 预设本身不是配置项，不进 store.dirty；它改的是下面那三栏，改完照常记账
  const applyWebhook = () => {
    const shape = WEBHOOK_PRESETS[preset.value];
    hookCustom.classList.toggle('hide', !!shape);
    if (!shape) return;
    method.value = shape.method;
    titleField.value = shape.title;
    contentField.value = shape.content;
    setValue('starbot.core.alert.webhook-method', shape.method);
    setValue('starbot.core.alert.webhook-title-field', shape.title);
    setValue('starbot.core.alert.webhook-content-field', shape.content);
  };
  preset.addEventListener('change', applyWebhook);
  // 现有配置匹配哪个预设，就显示哪个；对不上就是「自定义」，那几栏摊开
  preset.value = Object.keys(WEBHOOK_PRESETS).find(k => {
    const s = WEBHOOK_PRESETS[k];
    return s.method === valueOf('starbot.core.alert.webhook-method')
      && s.title === valueOf('starbot.core.alert.webhook-title-field')
      && s.content === valueOf('starbot.core.alert.webhook-content-field');
  }) || CUSTOM;
  hookCustom.classList.toggle('hide', !!WEBHOOK_PRESETS[preset.value]);
  pillState(hook.pill, !!url.value.trim());
  wrap.appendChild(hook.card);

  // ---- 邮件 ----
  const mail = shell('mail', '邮件', '慢一点，但不跟 QQ 一起挂掉。发件那栏填的是授权码，不是登录密码。');
  const mailCustom = el('div', 'al-cus');
  const mailPreset = presetField(mail.body, Object.keys(MAIL_PRESETS).concat(CUSTOM));
  const to = field(mail.body, '收件邮箱', 'starbot.core.mail.default-to', {ph: '收告警的邮箱'});
  field(mail.body, '发件账号', 'spring.mail.username', {ph: '发信的那个邮箱'});
  field(mail.body, '发件授权码', 'spring.mail.password',
    {type: 'password', ph: '留空＝保持原值', note: '多数邮箱要的是「授权码」，在邮箱设置里单独生成。'});
  mail.body.appendChild(mailCustom);
  const host = field(mailCustom, '服务器', 'spring.mail.host', {ph: '如 smtp.qq.com'});
  const port = field(mailCustom, '端口', 'spring.mail.port', {type: 'number', ph: '465'});

  const applyMail = () => {
    const shape = MAIL_PRESETS[mailPreset.value];
    mailCustom.classList.toggle('hide', !!shape);
    if (!shape) return;
    host.value = shape.host;
    port.value = shape.port;
    setValue('spring.mail.host', shape.host);
    setValue('spring.mail.port', shape.port);
  };
  mailPreset.addEventListener('change', applyMail);
  mailPreset.value = Object.keys(MAIL_PRESETS).find(k => MAIL_PRESETS[k].host === valueOf('spring.mail.host'))
    || CUSTOM;
  mailCustom.classList.toggle('hide', !!MAIL_PRESETS[mailPreset.value]);

  const mailReady = () => pillState(mail.pill, mailAlertConfigured(to.value, host.value));
  to.addEventListener('input', mailReady);
  host.addEventListener('input', mailReady);
  mailReady();
  wrap.appendChild(mail.card);

  return wrap;
}

/**
 * 搜索与「只看改过的」也要能筛到卡片
 *
 * 卡片里装的同样是配置项。搜「邮箱」时只剩一张邮件卡，比三张卡原样立着有用得多。
 * @param query 搜索词
 * @param onlyChanged 是否只看改过的
 * @return {number} 显示出来的卡片里一共有几项配置
 */
export function filterCards(query, onlyChanged) {
  const box = $('#alert-cards');
  if (!box) return 0;

  const q = String(query || '').trim().toLowerCase();
  let shown = 0;

  for (const card of box.querySelectorAll('.alcard')) {
    const keys = [...card.querySelectorAll('.keyname')].map(k => k.textContent);
    const text = (card.textContent || '').toLowerCase();
    const changed = keys.some(k => store.dirty[k] !== undefined
      || (store.values[k] !== undefined && String(store.values[k]) !== ''));
    const hit = (!q || text.includes(q)) && (!onlyChanged || changed);
    card.classList.toggle('hide', !hit);
    if (hit) shown += keys.length;
  }

  box.classList.toggle('hide', !box.querySelector('.alcard:not(.hide)'));
  return shown;
}
