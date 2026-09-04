/**
 * 设置页：按「要办的事」分的八组、搜索与筛选、危险项围栏、保存与放弃
 *
 * 这一页此前是按配置键的前缀自动分组的，二十余组，组名叫「核心 · 网络线程」这类东西。
 * 那是照程序结构摆的：使用者要办一件事（「让它别在半夜发消息」），得先猜这件事
 * 在代码里归哪个类管。现在组由服务端那张表给（见 ConfigurationGroups），
 * 常用六组在上、工程用的两组折到页底，每一项旁边标着改完什么时候生效。
 */

import {$, api, el, esc, markDirty, saveTarget, say} from './core.js';
import {load} from './main.js';
import {serializePush} from './push.js';
import {alertCards, CARD_FIELDS, filterCards} from './settings-alert.js';
import {authCards, AUTH_CARD_FIELDS, filterAuthCards} from './settings-auth.js';
import {defaultText, defaultValue, effectOf, isChanged, isDangerous, dangerOf, isVisible}
  from './settings-model.js';
import {store} from './store.js';

/** 告警那一组的标识，它要额外接三张卡 */
const ALERT_GROUP = 'alert';

/** 登录与安全那一组的标识，它要额外接四张卡 */
const AUTH_GROUP = 'auth';

/**
 * 一项的当前值：草稿优先，其次已保存的值，最后才是默认值
 *
 * 顺序不能反。切换搜索或筛选会整体重绘，此时未保存的改动要接着显示出来，
 * 否则看起来像被悄悄还原了。
 * @param field 字段表里的一项
 * @return {{saved: string, current: string}} 已保存的值与此刻显示的值
 */
function valuesOf(field) {
  const stored = store.values[field.name];
  const saved = stored !== undefined && stored !== null ? String(stored)
    : (field.defaultValue !== null && field.defaultValue !== undefined ? defaultValue(field) : '');
  const current = store.dirty[field.name] !== undefined ? store.dirty[field.name] : saved;
  return {saved, current};
}

/**
 * 建出一项的控件
 * @param field 字段表里的一项
 * @param value 当前值
 * @param cell 容器
 * @return {HTMLElement|null} 控件，只读展示时为 null
 */
function buildControl(field, value, cell) {
  if (field.widget === 'boolean') {
    const label = el('label', 'switch');
    const input = el('input');
    input.type = 'checkbox';
    input.checked = String(value) === 'true';
    const text = el('span');
    // 开关旁边那两个字由 syncLabel 更新，而不是挂一个 change 监听：
    // 「把控件退回原样」那条路要能在不触发任何 change 的前提下改动它——
    // 靠 dispatchEvent 退回去的话，退回这个动作自己又会走一遍确认流程
    input.syncLabel = () => { text.textContent = input.checked ? '已启用' : '已关闭'; };
    input.syncLabel();
    label.append(input, text);
    cell.appendChild(label);
    return input;
  }

  if (field.widget === 'complex') {
    // 元素为对象的列表与映射无法用简单控件表达。控制台不编辑配置文件，
    // 因此这里只说清「这一项要到文件里改」，路径就在页底
    const note = el('div', 'readonly');
    note.textContent = '这一项结构较复杂，界面上表达不了，请到服务器上改配置文件。';
    cell.appendChild(note);
    return null;
  }

  if (field.widget === 'list') {
    const input = el('textarea');
    input.value = value;
    input.placeholder = '每行一项';
    cell.appendChild(input);
    return input;
  }

  if (field.sensitive) {
    // 口令、令牌与密钥。后端给的是占位值而非真值，这里只负责别把它摆在画面里——
    // 面板可能正开在直播画面上，而二次验证密钥一旦泄漏就永久失效且当事人不会察觉
    const wrap = el('div', 'secret');
    const input = el('input');
    input.type = 'password';
    input.value = value;
    input.autocomplete = 'off';
    const eye = el('button');
    eye.type = 'button';
    eye.textContent = '显示';
    eye.addEventListener('click', () => {
      input.type = input.type === 'password' ? 'text' : 'password';
      eye.textContent = input.type === 'password' ? '显示' : '隐藏';
    });
    wrap.append(input, eye);
    cell.appendChild(wrap);
    return input;
  }

  const input = el('input');
  input.type = (field.widget === 'integer' || field.widget === 'number') ? 'number' : 'text';
  input.value = value;
  cell.appendChild(input);
  return input;
}

/**
 * 建出一行设置
 * @param field 字段表里的一项
 * @param groupAllRestart 整组都要重启时，行里不再逐行标
 * @return {HTMLElement} 那一行
 */
function buildRow(field, groupAllRestart) {
  const row = el('div', 'field setitem');
  row.dataset.name = field.name;
  if (isDangerous(field)) row.classList.add('danger');

  const meta = el('div', 'meta');
  const head = el('div', 'si-hd');
  if (isDangerous(field)) {
    const mark = el('span', 'dgm');
    mark.textContent = '⚠';
    mark.title = '这一项改错了后果不小，改到危险的那一档时会先问一句';
    head.appendChild(mark);
  }
  const label = el('b');
  label.textContent = field.label;
  head.appendChild(label);
  const dot = el('span', 'chgdot hide');
  dot.textContent = '已改';
  head.appendChild(dot);
  meta.appendChild(head);

  if (field.description) {
    const desc = el('div', 'desc');
    desc.textContent = field.description;
    meta.appendChild(desc);
  }

  // 键名默认收着，「显示键名」打开时才露出来。使用者认的是人话名，
  // 而运维手里拿到的报错信息里只有键名——两种人都要照顾到
  const key = el('div', 'keyname');
  key.textContent = field.name;
  meta.appendChild(key);

  // 配置键改过名，这一项在文件里写的还是旧位置。值显示的就是它，但得说清是从哪一行来的——
  // 否则使用者在文件里按现行名字找不到这一行，会以为界面显示错了
  if (store.legacy[field.name]) {
    const note = el('div', 'legacy');
    note.textContent = '该项按旧位置 ' + store.legacy[field.name]
      + ' 生效，建议迁到新位置：在此保存一次即可，旧位置的内容不会被改动';
    meta.appendChild(note);
  }

  const line = el('div', 'defline hide');
  const text = el('span');
  text.textContent = '默认：' + defaultText(field) + ' · ';
  line.appendChild(text);
  meta.appendChild(line);
  row.appendChild(meta);

  const cell = el('div');
  const {saved, current} = valuesOf(field);
  const input = buildControl(field, current, cell);
  row.appendChild(cell);

  if (!groupAllRestart) {
    const effect = effectOf(field.effect);
    const badge = el('span', 'badge ' + (effect.immediate ? 'now' : 'restart'));
    badge.textContent = effect.text;
    cell.appendChild(badge);
  }

  if (!input) return row;

  input.setAttribute('aria-label', field.label);
  const read = () => field.widget === 'boolean' ? String(input.checked) : input.value;
  const write = value => {
    if (field.widget === 'boolean') {
      input.checked = String(value) === 'true';
      input.syncLabel();
    } else {
      input.value = value;
    }
  };

  // 「改过没有」有两个互不相同的问法，界面上也是两套记号：
  // 与默认值不同（那个「已改」的点，答的是这台机器偏离了出厂设定），
  // 与已保存的值不同（进 store.dirty，由底部改动条统计，答的是还有什么没落盘）
  const paint = () => {
    const changed = isChanged(field, read());
    dot.classList.toggle('hide', !changed);
    line.classList.toggle('hide', !changed || field.sensitive);
    row.dataset.changed = changed ? '1' : '0';
    row.classList.toggle('changed', store.dirty[field.name] !== undefined);
  };

  // 机密项不给「恢复默认」：它的默认值多半是空，而按下去等于把口令清掉——
  // 那是个该显式做出的决定，不该藏在一个叫「恢复默认」的链接后面
  if (!field.sensitive) {
    const reset = el('button', 'lnkbtn');
    reset.type = 'button';
    reset.textContent = '恢复默认';
    reset.addEventListener('click', () => { write(defaultValue(field)); record(); });
    line.appendChild(reset);
  }

  // 危险项自己记账：改到危险那一档要先问一句，取消就退回原样、不计入改动
  let previous = read();
  const record = () => {
    const now = read();
    if (now === saved) delete store.dirty[field.name];
    else store.dirty[field.name] = now;
    previous = now;
    paint();
    markDirty();
  };

  const onChange = () => {
    const now = read();
    const danger = dangerOf(field, now);
    if (danger && !confirm(danger.title + '\n\n' + danger.body)) {
      write(previous);
      paint();
      return;
    }
    if (field.widget === 'boolean') input.syncLabel();
    record();
  };

  input.addEventListener('change', onChange);
  // 文本类控件要边打边记，否则改动条要等失焦才动
  if (field.widget !== 'boolean') input.addEventListener('input', onChange);
  paint();
  return row;
}

/**
 * 建出一组
 * @param group 一组，来自 /schema
 * @return {HTMLElement} 那一组
 */
function buildGroup(group) {
  const box = el('div', 'group setgrp');
  box.dataset.grp = group.group;

  const title = el('h2');
  const name = el('span');
  name.textContent = group.title;
  title.appendChild(name);
  // 整组都要重启的组，牌子挂在组标题上，行里不再逐行标。混着的组（推送、告警）逐行标保留
  if (group.allRestart) {
    const pill = el('span', 'pill warn');
    pill.textContent = '这一组改了都要重启';
    title.appendChild(pill);
  }
  box.appendChild(title);

  if (group.description) {
    const desc = el('div', 'card-desc');
    desc.textContent = group.description;
    box.appendChild(desc);
  }

  for (const field of group.fields) {
    // 告警那几项由三张卡自己摆，不再在这里出一遍
    if (group.group === ALERT_GROUP && CARD_FIELDS.has(field.name)) continue;
    // 口令、二次验证开关与它的密钥同理：这三项改的时候各有一道门要过
    // （旧口令、现在的验证码、先绑定），摊成普通行的话那三道门就只剩「填格子按保存」
    if (group.group === AUTH_GROUP && AUTH_CARD_FIELDS.has(field.name)) continue;
    box.appendChild(buildRow(field, group.allRestart));
  }

  if (group.group === ALERT_GROUP) box.appendChild(alertCards());
  if (group.group === AUTH_GROUP) box.appendChild(authCards());
  return box;
}

/**
 * 归不了组的那几项
 *
 * 服务端那张分组表一条前缀也匹配不上时，这一项会落在这里。<b>它不该被塞进某个「其他」组</b>：
 * 塞进去之后界面照样显示，于是「这一项还没人给它安排位置」这件事就再也看不见了。
 * 正常情况下这一块不存在——构建期那道判据会先红。
 * @return {HTMLElement|null} 那一块，没有落单项时为 null
 */
function buildUngrouped() {
  if (!store.ungrouped.length) return null;

  const box = el('div', 'group setgrp ungrouped');
  box.dataset.grp = 'ungrouped';
  const title = el('h2');
  title.textContent = '还没归组的 ' + store.ungrouped.length + ' 项';
  box.appendChild(title);

  const desc = el('div', 'card-desc');
  desc.textContent = '这几项在设置页的分组表里没有位置，多半是新加的配置项还没被安排。'
    + '它们照常可以改，只是暂时摆在这里。';
  box.appendChild(desc);

  for (const field of store.ungrouped) box.appendChild(buildRow(field, false));
  return box;
}

/**
 * 组目录药丸：点一下滚到那一组
 * @param groups 常用组
 * @param hasAdvanced 页底有没有高级区
 */
function buildNav(groups, hasAdvanced) {
  const nav = $('#grp-nav');
  nav.innerHTML = '';

  const jump = id => {
    if (id === 'adv') $('#adv-groups').open = true;
    const target = document.querySelector('[data-grp="' + id + '"]');
    // 点了药丸却因为筛选而看不见那一组，比什么都不发生更费解
    if (target) target.classList.remove('hide');
    if (target && target.scrollIntoView) target.scrollIntoView({behavior: 'smooth', block: 'start'});
  };

  for (const group of groups) {
    const pill = el('button', 'pill');
    pill.type = 'button';
    pill.textContent = group.title;
    pill.addEventListener('click', () => jump(group.group));
    nav.appendChild(pill);
  }

  if (hasAdvanced) {
    const pill = el('button', 'pill');
    pill.type = 'button';
    pill.textContent = '高级';
    pill.addEventListener('click', () => jump('adv'));
    nav.appendChild(pill);
  }
}

/**
 * 画整个设置页
 */
export function renderGeneral() {
  const box = $('#groups');
  box.innerHTML = '';

  const common = store.schema.filter(g => !g.advanced);
  const advanced = store.schema.filter(g => g.advanced);

  const orphans = buildUngrouped();
  if (orphans) box.appendChild(orphans);

  for (const group of common) box.appendChild(buildGroup(group));

  if (advanced.length) {
    const details = el('details', 'adv');
    details.id = 'adv-groups';
    const summary = el('summary');
    const count = advanced.reduce((n, g) => n + g.fields.length, 0);
    summary.textContent = '高级 · 工程用（' + count + ' 项，出问题时才动）';
    details.appendChild(summary);
    const inner = el('div', 'inner');
    for (const group of advanced) inner.appendChild(buildGroup(group));
    details.appendChild(inner);
    box.appendChild(details);
  }

  buildNav(common, advanced.length > 0);
  filterSettings();
}

/**
 * 按搜索词与「只看改过的」筛一遍
 *
 * 折起来的地方有命中就自动展开：搜到了却在一个收起的折页里，与没搜到长得一样。
 */
export function filterSettings() {
  const query = $('#set-search').value;
  const onlyChanged = $('#only-changed').checked;
  const filtering = !!String(query).trim() || onlyChanged;

  // 从 DOM 那一侧遍历，按 data-name 反查字段：配置键里带着点号，
  // 拿它去拼选择器要先转义，而少转义一处的表现是那一行永远筛不掉
  const byName = new Map();
  for (const group of store.schema) {
    for (const field of group.fields) byName.set(field.name, field);
  }
  for (const field of store.ungrouped) byName.set(field.name, field);

  let shown = 0;
  for (const row of document.querySelectorAll('.setitem')) {
    const field = byName.get(row.dataset.name);
    if (!field) continue;
    const hit = isVisible(field, valuesOf(field).current, query, onlyChanged);
    row.classList.toggle('hide', !hit);
    if (hit) shown++;
  }

  shown += filterCards(query, onlyChanged);
  shown += filterAuthCards(query, onlyChanged);

  // 一组里一项都不剩就把整组收起来，否则屏幕上留着一串空标题
  for (const group of document.querySelectorAll('.setgrp')) {
    const any = group.querySelectorAll('.setitem:not(.hide), .alcard:not(.hide)').length;
    group.classList.toggle('hide', any === 0);
  }

  const details = $('#adv-groups');
  if (details) {
    const any = details.querySelectorAll('.setitem:not(.hide), .alcard:not(.hide)').length;
    details.classList.toggle('hide', any === 0);
    if (filtering && any) details.open = true;
  }

  const commonCount = store.schema.filter(g => !g.advanced).reduce((n, g) => n + g.fields.length, 0);
  const advancedCount = store.schema.filter(g => g.advanced).reduce((n, g) => n + g.fields.length, 0);
  $('#set-count').textContent = filtering
    ? '匹配 ' + shown + ' 项'
    : '常用 ' + commonCount + ' 项 · 高级 ' + advancedCount + ' 项';
}

/**
 * 显示键名
 *
 * 挂在根元素上而不是逐行加类：切换时不必重绘，也就不会丢掉正在编辑的那一格。
 */
export function toggleKeyNames() {
  document.documentElement.classList.toggle('showkeys', $('#show-keys').checked);
}

/**
 * 设置页底部那行配置文件路径
 *
 * 控制台不再提供配置文件编辑，这一行是「要直接改文件的话去哪儿改」的唯一答案，
 * 因此路径必须来自服务端对配置文件的实际定位，界面不自己拼一个。
 * @param path 配置文件的绝对路径，来自 /status
 */
export function renderConfigPath(path) {
  $('#cfg-path').textContent = path || '（未能确定）';
  $('#cfg-copy').disabled = !path;
}

/**
 * 复制路径
 *
 * 剪贴板接口只在安全上下文（https 或 localhost）里存在。面板常经内网 http 访问，
 * 那里 navigator.clipboard 直接是 undefined——不兜底的话，点下去毫无反应，
 * 而使用者无从知道是没复制上还是复制了。
 */
export async function copyConfigPath() {
  const path = $('#cfg-path').textContent;
  try {
    await navigator.clipboard.writeText(path);
    say('已复制路径', 'ok');
  } catch (e) {
    say('这个浏览器不让脚本写剪贴板，请手动复制：' + path, 'err');
  }
}

// 校验不通过时逐条列出问题：只说「保存失败」而不说哪里错，使用者无从下手
function showIssues(issues) {
  const box = $('#issues');
  if (!issues || !issues.length) {
    box.style.display = 'none';
    box.innerHTML = '';
    return;
  }
  box.style.display = 'block';
  box.innerHTML = '<b>请先修正以下问题：</b><ul>'
    + issues.map(i => '<li>' + esc(i) + '</li>').join('') + '</ul>';
}

export async function save() {
  const target = saveTarget();
  if (!target) return;

  $('#save').disabled = true;
  showIssues(null);
  say('保存中…');

  try {
    let res;
    if (target === 'values') {
      res = await api('/values', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(store.dirty)
      });
    } else {
      res = await api('/datasource', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: serializePush() })
      });
    }

    // 保存成功就整体重取一遍，草稿态与「还欠一次重启」都由那一趟自己算。
    // 在这里按 store.dirty 顺手把新值抄进 store.values 是不行的：真正落盘的是哪几项
    // 只有服务端知道（送上来但值没变的项不会写，机密项送的是占位值也不会写），
    // 抄错的那几项此后会一直以为自己是「已保存的值」，而屏幕上看不出任何异常
    if (res.success) await load();

    showIssues(res.issues);
    say(res.message || (res.success ? '已保存' : '保存失败'), res.success ? 'ok' : 'err');
  } catch (e) {
    say('保存失败：' + e.message, 'err');
  }

  markDirty();
}

/**
 * 丢掉改过还没保存的内容，回到上一次保存时的样子
 *
 * 走的是整体重载而不是「把改动逐项撤回」：撤回要为每种草稿态各写一套还原逻辑，
 * 而每加一种草稿态就多一处会漏掉的地方；重载则是把服务端的现值重新取一遍，
 * 漏不掉任何一处。代价是一次往返，放弃改动本就不是高频动作。
 */
export async function discard() {
  if (!confirm('放弃这些改动？改过还没保存的内容会全部回到上一次保存时的样子。')) return;
  // 草稿态由 load 自己清。在这里先清一遍的话，重载失败时屏幕上还留着改过的值，
  // 而计数已经归零——看起来像「改动被保存了」
  await load();
  say('已放弃全部改动');
}
