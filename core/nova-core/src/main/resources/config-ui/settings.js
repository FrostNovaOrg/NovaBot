/**
 * 设置页：按「要办的事」分的八组、常用与高级、搜索与筛选、危险项围栏、保存与放弃
 *
 * 这一页此前是按配置键的前缀自动分组的，二十余组，组名叫「核心 · 网络线程」这类东西。
 * 那是照程序结构摆的：使用者要办一件事（「让它别在半夜发消息」），得先猜这件事
 * 在代码里归哪个类管。现在组由服务端那张表给（见 ConfigurationGroups），
 * 每一项按服务端标的级别分进常用或高级：高级默认收着，由组目录最底下的「高级选项」显示；
 * 每一项旁边标着改完什么时候生效，行内只留一句短说明，整段说明在问号里。
 */

import {ask} from './confirm.js';
import {$, api, el, esc, markDirty, reloadChangeSources, saveTarget, say, switchControl} from './core.js';
import {load, refreshPages} from './main.js';
import {bindPasswordReveal} from './password-reveal.js';
import {alertCards, cardFields, filterCards, openCards} from './settings-alert.js';
import {authCards, AUTH_CARD_FIELDS, filterAuthCards} from './settings-auth.js';
import {filterMembers, memberGroup} from './settings-members.js';
import {CLEAR, advancedShown, arrangeGroups, canonicalValue, currentGroupId, defaultText, defaultValue, effectOf,
  isChanged, isDangerous, dangerOf, isVisible, secretDraft, timeOfDay, timeOfDayValue}
  from './settings-model.js';
import {store} from './store.js';
import {applyTheme, readTheme, subscribeTheme, THEME_AUTO, THEME_DARK, THEME_LIGHT} from './theme.js';

/** 告警那一组的标识，它要额外接三张卡 */
const ALERT_GROUP = 'alert';

/** 登录与安全那一组的标识，它要额外接四张卡 */
const AUTH_GROUP = 'auth';

/**
 * 高级那一块的开合
 *
 * 默认收起。advancedChosen：使用者点了目录底下的「高级选项」，或从别处点名跳进了高级里的一组；
 * advancedDismissed：使用者在某一次搜索上亲手收起过——记的是那一次搜索本身，换了搜索词就作废，
 * 否则搜到了高级里的项、却因为上一次收起过而一直收着，与没搜到长得一样。
 * 重画（保存、放弃）不动这两样：改到一半的人不该因为一次保存就被收回去。
 */
let advancedChosen = false;
let advancedDismissed = null;

/** 这一次画出来的两半，计数与开合要用 */
let arranged = {common: [], advanced: []};

/**
 * 滚动观察器：标出目录里当前这一组。重绘时拆掉再建，免得旧节点还挂着。
 * 离开设置页也要拆：那一页已经 display:none，观察器留着会空转。
 * 每次判断都重新量各组现在的位置，不用上一次进出视口时记下的坐标。
 * 组展开、变高或窗口改了大小，下一帧就按新位置亮。
 */
let groupWatcher = null;
let groupSizer = null;
let groupMarkFrame = 0;
let groupScroll = null;
let groupResize = null;

/**
 * 拆掉组目录的滚动观察器
 */
export function stopWatchingGroups() {
  if (groupWatcher) {
    groupWatcher.disconnect();
    groupWatcher = null;
  }
  if (groupSizer) {
    groupSizer.disconnect();
    groupSizer = null;
  }
  if (groupScroll) {
    window.removeEventListener('scroll', groupScroll);
    groupScroll = null;
  }
  if (groupResize) {
    window.removeEventListener('resize', groupResize);
    groupResize = null;
  }
  if (groupMarkFrame) {
    cancelAnimationFrame(groupMarkFrame);
    groupMarkFrame = 0;
  }
}

/**
 * 滚到设置页上的某一组
 *
 * 组目录与首页那条「告警有死角」待办走的是同一条路。
 * 点了条目却因为筛选而看不见那一组，比什么都不发生更费解，因此先把 hide 揭掉。
 * 目标若在收起的高级里，先把高级摆出来再量再滚——收着的时候它没有高度，滚到位也看不见。
 * 从别处点名跳来（open 为真）时，那一组里的卡一并展开：首页说「告警有死角，去配」，
 * 落到一排收着的卡上，看起来像点了没反应。组目录点过去只滚，不动卡的开合。
 * 落点让出吸在视口顶上的那几块：窄屏上顶栏与组目录都吸顶，不让出的话滚到位的
 * 组标题正好被它们盖住，看起来像「点了没反应」；宽屏上没有吸顶元素，落点不偏。
 * 让出多少与「哪一组算当前」共用 headerHeight 量出来的那一个数：两处各算各的，
 * 窄屏上点某一组会亮上一组，或滚到位的组标题正好被盖住。
 * 从别的页点名跳来（fresh 为真）时，先把上一回留下的搜索词与「只看改过」清掉、按无筛选重筛一遍：
 * 先搜过 quiet 再回首页点「去配」，旧搜索还挂着的话，目标那一组的行、卡和整块高级照旧被筛掉，
 * 只揭掉组的 hide 落到的是一片空白。保存、放弃后的重画也会按地址栏再走一遍这里，那时不清：
 * 正在搜着的人不该因为点了一下保存就丢了搜索词。
 * @param id 组标识，与 schema 里的 group 一致；高级里拆出来的那半组带 -advanced，整块高级传 adv
 * @param open 从别处点名跳来时为真：展开那一组里的卡
 * @param fresh 从别的页点名跳来（地址栏换了）时为真：先清掉搜索与「只看改过」
 */
export function focusGroup(id, open, fresh) {
  if (!id) return;
  if (fresh) clearFilters();
  const adv = $('#adv-groups');
  const target = id === 'adv' ? adv : document.querySelector('[data-grp="' + id + '"]');
  if (target) {
    if (adv && adv.contains(target) && adv.classList.contains('hide')) showAdvanced();
    target.classList.remove('hide');
    if (open) openCards(target);
    const above = headerHeight();
    const top = window.scrollY + target.getBoundingClientRect().top - above;
    window.scrollTo(0, Math.max(0, top));
  }
}

/**
 * 清掉搜索词与「只看改过」，按无筛选重筛一遍
 *
 * 只改框里的值不会触发它们的监听，因此这里自己筛：不筛的话框是空的，行却还按旧搜索藏着。
 */
function clearFilters() {
  const search = $('#set-search');
  const onlyChanged = $('#only-changed');
  if (search) search.value = '';
  if (onlyChanged) onlyChanged.checked = false;
  filterSettings();
}

/**
 * 一项的当前值：草稿优先，其次已保存的值，最后才是默认值
 *
 * 顺序不能反。切换搜索或筛选会整体重绘，此时未保存的改动要接着显示出来，
 * 否则看起来像被悄悄还原了。
 * @param field 字段表里的一项
 * @return {{saved: string, current: string, text: string, cleared: boolean}}
 *   已保存的值、筛选「只看改过」用的当前值、框里显示的字，以及是不是挂着清除
 */
function valuesOf(field) {
  const stored = store.values[field.name];
  const saved = canonicalValue(field, stored !== undefined && stored !== null ? String(stored)
    : (field.defaultValue !== null && field.defaultValue !== undefined ? defaultValue(field) : ''));
  const draft = store.dirty[field.name];
  const current = draft !== undefined ? canonicalValue(field, draft) : saved;
  // 挂着清除时，显示与「改没改」是两回事：框里要直接进清除态（空、禁用、保存后清除），
  // 而筛选那一问仍按原始草稿答——清除态算改过，不能因为显示成了空就把它筛掉
  const shown = field.sensitive ? secretDraft(draft, saved) : {text: current, cleared: false};
  return {saved, current, text: shown.text, cleared: shown.cleared};
}

/**
 * 问号浮层：整段说明放在这里，行内只留一句短说明
 *
 * 一页只有一块浮层，挂在 body 上：挂在行里会被组卡片的 overflow:hidden 裁掉一截。
 * 键盘：问号是个真按钮，Tab 停得到，聚焦即打开、Esc 关上；读屏：开着的时候问号的
 * aria-describedby 指向浮层，念完名字接着念说明；触屏：点一下开、再点或点别处关。
 * 鼠标悬停也开，点一下钉住，钉住后移开不关。
 */
let helpPanel = null;
let helpButton = null;
let helpPinned = false;
let helpCloseTimer = 0;

/**
 * 关上问号浮层
 */
function closeHelp() {
  clearTimeout(helpCloseTimer);
  helpCloseTimer = 0;
  if (helpButton) {
    helpButton.setAttribute('aria-expanded', 'false');
    helpButton.removeAttribute('aria-describedby');
  }
  helpButton = null;
  helpPinned = false;
  if (helpPanel) helpPanel.hidden = true;
}

/**
 * 把浮层摆到问号旁边：下面放得下放下面，放不下翻到上面，左右不出视口。
 * 问号滚出视口或已不在页面上（重画过）就关掉
 */
function placeHelp() {
  if (!helpButton || !helpPanel || helpPanel.hidden) return;
  const rect = helpButton.getBoundingClientRect();
  if (!helpButton.isConnected || rect.bottom < 0 || rect.top > window.innerHeight) {
    closeHelp();
    return;
  }
  const inset = 12;
  const left = Math.max(inset, Math.min(rect.left, window.innerWidth - helpPanel.offsetWidth - inset));
  const below = rect.bottom + 8;
  const top = below + helpPanel.offsetHeight <= window.innerHeight - inset
    ? below : Math.max(inset, rect.top - helpPanel.offsetHeight - 8);
  helpPanel.style.left = left + 'px';
  helpPanel.style.top = top + 'px';
}

/**
 * 取那一块浮层，第一次用时建出来并挂上关闭的几条路
 * @return {HTMLElement}
 */
function ensureHelpPanel() {
  if (helpPanel) return helpPanel;
  helpPanel = el('div', 'settings-help-popover');
  helpPanel.id = 'settings-help-popover';
  helpPanel.setAttribute('role', 'tooltip');
  helpPanel.hidden = true;
  document.body.appendChild(helpPanel);
  helpPanel.addEventListener('mouseenter', () => clearTimeout(helpCloseTimer));
  helpPanel.addEventListener('mouseleave', () => {
    if (!helpPinned && document.activeElement !== helpButton) closeHelp();
  });
  document.addEventListener('pointerdown', event => {
    if (helpButton && !helpButton.contains(event.target) && !helpPanel.contains(event.target)) closeHelp();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && helpButton) {
      event.preventDefault();
      closeHelp();
    }
  });
  window.addEventListener('scroll', placeHelp, {capture: true, passive: true});
  window.addEventListener('resize', placeHelp);
  window.addEventListener('hashchange', closeHelp);
  return helpPanel;
}

/**
 * 一项旁边的问号
 * @param label 这一项的名字，读屏念「某某：查看说明」
 * @param text 整段说明
 * @return {HTMLButtonElement}
 */
function buildHelp(label, text) {
  const panel = ensureHelpPanel();
  const button = el('button', 'settings-help');
  button.type = 'button';
  button.textContent = '?';
  button.setAttribute('aria-label', label + '：查看说明');
  button.setAttribute('aria-expanded', 'false');
  button.setAttribute('aria-controls', panel.id);
  const open = pinned => {
    clearTimeout(helpCloseTimer);
    if (helpButton !== button) closeHelp();
    helpButton = button;
    helpPinned = pinned || helpPinned;
    panel.textContent = text;
    panel.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    button.setAttribute('aria-describedby', panel.id);
    placeHelp();
  };
  button.addEventListener('mouseenter', () => {
    if (!helpPinned || helpButton === button) open(false);
  });
  button.addEventListener('mouseleave', () => {
    if (!helpPinned && document.activeElement !== button) helpCloseTimer = setTimeout(closeHelp, 160);
  });
  button.addEventListener('focus', () => open(false));
  button.addEventListener('blur', () => {
    if (!helpPinned && helpButton === button) closeHelp();
  });
  button.addEventListener('click', () => {
    if (helpButton === button && helpPinned) closeHelp();
    else open(true);
  });
  return button;
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
    const sw = switchControl('', String(value) === 'true');
    cell.appendChild(sw.label);
    return sw.input;
  }

  if (field.widget === 'complex') {
    // 元素为对象的列表与映射无法用简单控件表达。控制台不编辑配置文件，
    // 因此这里只说清「这一项要到文件里改」，路径就在页底
    const note = el('div', 'readonly');
    note.textContent = '此配置项结构复杂，暂不支持在控制台编辑，请在服务器上修改配置文件，修改后需重启生效。';
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
    input.autocomplete = 'new-password';
    const eye = el('button');
    eye.type = 'button';
    wrap.append(input, eye);
    bindPasswordReveal(input, eye, field.label);
    cell.appendChild(wrap);
    return input;
  }

  if (field.format === 'TIME_OF_DAY') {
    // 一天里的时刻：给时间框，不让人手敲「HH:mm」。框只认补零的写法，
    // 配置文件里认不出的旧值在这里显示成空，由 buildRow 那一句说清楚
    const input = el('input');
    input.type = 'time';
    input.step = '60';
    input.value = timeOfDay(value).text;
    cell.appendChild(input);
    return input;
  }

  const input = el('input');
  input.type = (field.widget === 'integer' || field.widget === 'number') ? 'number' : 'text';
  // 文件位置用等宽字：l 与 1、O 与 0 认得出来。认法看服务端给的 format，机密与否不在这里判
  if (field.format === 'PATH') input.classList.add('setting-path');
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
    mark.title = '此配置项设置不当可能造成严重后果；改为风险取值时，将先请求确认';
    head.appendChild(mark);
  }
  const label = el('b');
  label.textContent = field.label;
  head.appendChild(label);
  // 服务端给了短说明的项：行内只摆那一句（改之前就得知道的后果或填写约束），
  // 整段说明进问号。没给的（插件没标、旧后端）照旧在行内摆整段，一个字也不少
  const summary = typeof field.summary === 'string' ? field.summary : null;
  if (summary !== null && field.help) head.appendChild(buildHelp(field.label, field.help));
  const dot = el('span', 'chgdot hide');
  dot.textContent = '已修改';
  head.appendChild(dot);
  meta.appendChild(head);

  const inline = summary === null ? field.description : summary;
  if (inline) {
    const desc = el('div', 'desc');
    desc.textContent = inline;
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
    note.textContent = '此项当前按旧配置键 ' + store.legacy[field.name]
      + ' 生效。建议在此保存一次，以迁移至新配置键；旧配置键中的内容不会被修改。';
    meta.appendChild(note);
  }

  // 配置文件里手写成界面改不了的写法（带换行的块标量、引到名单的别名……）：只读摆出程序读到的值，
  // 说清为什么、去哪儿改。照常摆个框的话，一改一存就把那种写法压扁了
  const locked = store.locked && store.locked[field.name];
  if (locked) {
    const note = el('div', 'dflt');
    note.textContent = locked;
    meta.appendChild(note);
  }

  const line = el('div', 'defline hide');
  const text = el('span');
  text.textContent = '默认：' + defaultText(field) + ' · ';
  line.appendChild(text);
  meta.appendChild(line);
  row.appendChild(meta);

  const cell = el('div', field.widget === 'boolean' ? 'boolcell' : '');
  // 显示值不叫 text：上面「默认：…」那个 span 已经占了这个名字，同作用域撞车
  const {saved, text: shown, cleared} = valuesOf(field);
  let input = null;
  if (locked) {
    const box = el('div', 'readonly lockedval');
    box.textContent = shown;
    cell.appendChild(box);
  } else {
    input = buildControl(field, shown, cell);
  }
  if (field.unit) {
    const unit = el('span', 'unit');
    unit.textContent = field.unit;
    cell.appendChild(unit);
    // 数字／文本框把单位摆在右边；textarea 仍单独占一行，不改成横排
    if (input && input.tagName === 'INPUT') {
      cell.classList.add('cell', 'has-unit');
    }
  }
  row.appendChild(cell);

  if (!groupAllRestart) {
    const effect = effectOf(field.effect);
    const badge = el('span', 'badge ' + (effect.immediate ? 'now' : 'restart'));
    badge.textContent = effect.text;
    cell.appendChild(badge);
  }

  // 成图字体表：老实例的文件里存着旧版写下的默认表，启动时已按未设处理，而框里原样显示它。
  // 不说一声，使用者改一项再存就成了自己的表，表情空白又回来。两句都跟框里的草稿走：
  // 点了「恢复默认」还没保存，框已经空了，还说「这是旧表」就对不上眼前的框
  const fonts = store.fontTables && store.fontTables[field.name];
  let fontNotes = () => {};
  if (fonts) {
    const pastNote = el('div', 'dflt fontnote');
    const defaultsNote = el('div', 'dflt fontnote');
    cell.append(pastNote, defaultsNote);
    fontNotes = value => {
      const draft = canonicalValue(field, value);
      const past = fonts.pastDefault && draft === saved;
      const empty = draft.trim() === '';
      pastNote.textContent = past ? '此为旧版本自动写入配置的默认字体表，启动时已视为未设置。'
        + '点击「恢复默认」可清除此表，此后将使用程序内置的默认字体表。' : '';
      pastNote.classList.toggle('hide', !past);
      defaultsNote.textContent = past || empty
        ? '当前使用本系统的默认字体表：' + (fonts.defaults || []).join('、') : '';
      defaultsNote.classList.toggle('hide', !(past || empty));
    };
    fontNotes(shown);
  }

  if (!input) return row;

  input.setAttribute('aria-label', field.label);
  const timed = field.format === 'TIME_OF_DAY';
  // 时间框交出去之前收成 HH:mm：服务端只认这一种写法（或空串＝停用）
  const read = () => field.widget === 'boolean' ? String(input.checked)
    : (timed ? timeOfDayValue(input.value) : input.value);
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
  // 时刻项：配置文件里存着时间框认不出的值（不补零的 8:00 这类）时，框是空的，
  // 可那一项并不是空的——服务端读到它也认不出，整段静音按没设处理。说一句，
  // 免得人以为本来就没填。动过这一格（改了或清空）之后，文件里那一份就要被换掉了，不再说
  let timeNote = () => {};
  if (timed) {
    const note = el('div', 'dflt timenote hide');
    cell.appendChild(note);
    timeNote = () => {
      const odd = store.dirty[field.name] === undefined && timeOfDay(saved).unreadable;
      note.textContent = odd ? '配置文件中的值「' + saved + '」格式无效（应为 08:00 这样的写法），当前不生效。' : '';
      note.classList.toggle('hide', !odd);
    };
  }

  const paint = () => {
    const changed = isChanged(field, read());
    dot.classList.toggle('hide', !changed);
    line.classList.toggle('hide', !changed || field.sensitive);
    row.dataset.changed = changed ? '1' : '0';
    row.classList.toggle('changed', store.dirty[field.name] !== undefined);
    fontNotes(read());
    timeNote();
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

  // 机密项另给一个明确的「清除」：留空＝没动（照提示留空保存不删已存的值），
  // 真要清掉得按这个钮说一声，保存时才删键。只有已存了值（框里是遮点）才有得清。
  // 清除态自己记账：输入框已经禁用，record 里的字面比较分不出「空＝没动」与「清掉」。
  // 初次渲染就得认出「这一项挂着清除」：重画只把清除标记当普通值塞回框里的话，
  // 界面看着没动、一保存已存的值照样被删，要撤回还得连点两下
  let clearing = cleared;
  // 控制台访问令牌不给清除钮：留空即每次启动换一个随机令牌、只打进启动日志
  // （ConfigUiRegistrar 解析令牌那一处），清掉之后远程访问的人当场进不来，
  // 要有人到机器上翻日志才拿得到新地址。别的机密项照常给清除钮
  const noClear = field.name === 'novabot.core.config-ui.token';
  if (field.sensitive && saved && !noClear) {
    const clear = el('button', 'lnkbtn');
    clear.type = 'button';
    clear.textContent = clearing ? '取消清除' : '清除';
    clear.title = '保存后将删除此项；如需再次使用，须重新填写';
    // 同一句话挂着清除时摆在框下：悬停提示在触屏上看不到，点了清除却不知道保存后会删
    const clearNote = el('div', 'dflt clearnote');
    const showClearNote = () => {
      clearNote.textContent = clearing ? clear.title : '';
      clearNote.classList.toggle('hide', !clearing);
    };
    showClearNote();
    // 挂着清除的重画直接进清除态；没挂的照旧显示草稿，不在这儿动框里的值
    if (clearing) {
      input.value = '';
      input.disabled = true;
      input.placeholder = '保存后清除';
    }
    clear.addEventListener('click', () => {
      clearing = !clearing;
      showClearNote();
      if (clearing) {
        input.value = '';
        input.disabled = true;
        input.placeholder = '保存后清除';
        clear.textContent = '取消清除';
        store.dirty[field.name] = CLEAR;
      } else {
        input.disabled = false;
        input.value = saved;
        input.placeholder = '';
        clear.textContent = '清除';
        delete store.dirty[field.name];
      }
      paint();
      markDirty();
    });
    cell.append(clear, clearNote);
  }

  // 危险项自己记账：改到危险那一档要先问一句，取消就退回原样、不计入改动
  let previous = read();
  const record = () => {
    if (clearing) return;
    const now = canonicalValue(field, read());
    if (field.sensitive) {
      // 机密项的空框是「留空＝没动」，不是「改成空」——照提示留空保存不再误删
      const draft = secretDraft(now, saved);
      if (draft.keep) delete store.dirty[field.name];
      else store.dirty[field.name] = now;
    } else if (now === saved) {
      delete store.dirty[field.name];
    } else {
      store.dirty[field.name] = now;
    }
    previous = now;
    paint();
    markDirty();
  };
  const onChange = async () => {
    const now = read();
    const danger = dangerOf(field, now);
    if (danger && !await ask({title: danger.title, body: danger.body})) {
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
  // 时间框自己清不干净（各家浏览器的清除钮不一样，有的没有），旁边给一个。
  // 清空交的是空串：任一端空就停用静音，与服务端的规则一致
  if (timed) {
    const clear = el('button', 'lnkbtn time-clear');
    clear.type = 'button';
    clear.textContent = '清空';
    clear.setAttribute('aria-label', '清空' + field.label);
    clear.addEventListener('click', () => { write(''); record(); });
    cell.appendChild(clear);
  }
  paint();
  return row;
}

/**
 * 界面主题那一组：深色／浅色／跟随系统
 *
 * 纯前端的偏好，只影响这一个浏览器，不进服务端配置、不进改动条。
 * 摆在组目录最上方：外观是进来第一眼的事，不该埋在工程用的组里。
 * @return {HTMLElement} 那一组
 */
function buildThemeGroup() {
  const box = el('div', 'group setgrp');
  box.dataset.grp = 'ui';
  // 这一组不是配置项：底下没有 .setitem，搜索／只看改过那两道筛也量不到它。
  // 不标 data-ui 的话，「一组里一项不剩就收起」会把它连标题一起藏掉。
  box.dataset.ui = '1';

  const title = el('h2');
  const name = el('span');
  name.textContent = '界面';
  title.appendChild(name);
  box.appendChild(title);

  const desc = el('div', 'card-desc');
  desc.textContent = '可选择深色、浅色或跟随系统。此设置仅保存在当前浏览器中，不写入服务器配置。';
  box.appendChild(desc);

  const row = el('div', 'themechoice');
  const current = readTheme();
  for (const [value, label] of [
    [THEME_AUTO, '跟随系统'],
    [THEME_LIGHT, '浅色'],
    [THEME_DARK, '深色'],
  ]) {
    const btn = el('button', 'pill' + (value === current ? ' cur' : ''));
    btn.type = 'button';
    btn.setAttribute('data-theme-choice', value);
    btn.setAttribute('aria-pressed', String(value === current));
    btn.textContent = label;
    // 钮的亮暗由 markThemeChoice 改：本页点的与另一个标签换的走同一条路
    btn.addEventListener('click', () => applyTheme(value));
    row.appendChild(btn);
  }
  box.appendChild(row);
  return box;
}

/**
 * 主题换了：只改那三颗钮的亮暗
 *
 * 本页点钮与另一个标签换主题都走这里（见 theme.js 的 subscribeTheme）。
 * <b>不重画设置页</b>：另一个标签里点一下，这边正填着、还没保存的那一格和焦点都得原样留着。
 * @param value 新的主题
 */
function markThemeChoice(value) {
  for (const btn of document.querySelectorAll('[data-theme-choice]')) {
    const on = btn.getAttribute('data-theme-choice') === value;
    btn.classList.toggle('cur', on);
    btn.setAttribute('aria-pressed', String(on));
  }
}

subscribeTheme(markThemeChoice);

/**
 * 建出一组
 * @param group 一组，来自 /schema
 * @return {HTMLElement} 那一组
 */
function buildGroup(group) {
  const box = el('div', 'group setgrp');
  box.dataset.grp = group.group;
  // 从常用组里拆出来的「某组 · 高级」：哪几项由卡片摆，按原来那一组认
  const base = group.base || group.group;

  const title = el('h2');
  const name = el('span');
  name.textContent = group.title;
  title.appendChild(name);
  // 整组都要重启的组，牌子挂在组标题上，行里不再逐行标。混着的组（推送、告警）逐行标保留
  if (group.allRestart) {
    const pill = el('span', 'pill warn');
    pill.textContent = '本组设置修改后均需重启生效';
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
    if (base === ALERT_GROUP && cardFields().has(field.name)) continue;
    // 口令、二次验证开关与它的密钥同理：这三项改的时候各有一道门要过
    // （旧口令、现在的验证码、先绑定），摊成普通行的话那三道门就只剩「填格子按保存」
    if (base === AUTH_GROUP && AUTH_CARD_FIELDS.has(field.name)) continue;
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
  title.textContent = '未分组的设置项（' + store.ungrouped.length + ' 项）';
  box.appendChild(title);

  const desc = el('div', 'card-desc');
  desc.textContent = '以下设置项尚未纳入设置页的分组，通常是新增的配置项。'
    + '可以照常修改，暂时显示在此处。';
  box.appendChild(desc);

  for (const field of store.ungrouped) box.appendChild(buildRow(field, false));
  return box;
}

/**
 * 组目录一条：常用或高级组都走这里，点一下滚到那一组
 * @param group 一组，来自 /schema
 * @return {HTMLButtonElement}
 */
function buildNavLink(group) {
  const pill = el('button', 'pill');
  pill.type = 'button';
  pill.setAttribute('data-grp-link', group.group);
  pill.textContent = group.title;
  pill.addEventListener('click', () => focusGroup(group.group));
  return pill;
}

/**
 * 「高级选项」那颗钮上的眼睛：收着时带一道斜杠，摆出来时斜杠由样式藏掉（见 app.css）。
 * 固定的几笔，不含任何服务端给的字
 */
const ADVANCED_EYE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"'
  + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
  + '<path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/>'
  + '<path class="eye-slash" d="M2.5 2.5l11 11"/></svg>';

/**
 * 组目录：常用一段，有高级则加「高级」分隔再列高级各组，最后一项是「高级选项」开关
 *
 * 高级那一段（分隔与各组）装在 #adv-nav 里，跟着高级区一起收起、一起出现（见 filterSettings）；
 * 搜到高级里的项、从别处点名跳进高级时，两处照样一起出来。常用组里拆出来的「某组 · 高级」
 * 也列在这一段，按组表的先后与整组高级的那几组排在一起。
 * 开关钉在目录最后，开合状态的字与图由 filterSettings 同步。全页只有它一个 #show-advanced：
 * 别处再有一个同 id 的，按 id 取到的是排在前面的那一个，同步的就不是人点的这颗。
 * @param groups 常用组
 * @param advanced 高级组
 */
function buildNav(groups, advanced) {
  const nav = $('#grp-nav');
  nav.innerHTML = '';

  // 界面组排最上：纯前端偏好，不是这台机器的配置
  nav.appendChild(buildNavLink({group: 'ui', title: '界面'}));
  for (const group of groups) nav.appendChild(buildNavLink(group));
  if (document.querySelector('[data-grp="members"]')) {
    nav.appendChild(buildNavLink({group: 'members', title: '成员'}));
  }

  if (advanced.length) {
    const part = el('div', 'grpnav-advanced hide');
    part.id = 'adv-nav';
    const sep = el('div', 'sep');
    sep.textContent = '高级';
    part.appendChild(sep);
    for (const group of advanced) part.appendChild(buildNavLink(group));
    nav.appendChild(part);

    const toggle = el('button', 'settings-advanced-toggle');
    toggle.type = 'button';
    toggle.id = 'show-advanced';
    toggle.setAttribute('aria-controls', 'adv-groups adv-nav');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.innerHTML = '<span>高级选项</span>' + ADVANCED_EYE;
    toggle.addEventListener('click', toggleAdvanced);
    nav.appendChild(toggle);
  }
}

/**
 * 画整个设置页
 */
export function renderGeneral() {
  closeHelp();
  stopWatchingGroups();
  const box = $('#groups');
  box.innerHTML = '';

  // 常用／高级按服务端给每一项标的级别分，界面上不另记名单（见 arrangeGroups）。
  // 由卡片摆的项跟着卡留在常用那一组上
  arranged = arrangeGroups(store.schema, (group, name) =>
    (group === ALERT_GROUP && cardFields().has(name)) || (group === AUTH_GROUP && AUTH_CARD_FIELDS.has(name)));
  const {common, advanced} = arranged;

  // 界面组排最上：纯前端偏好，不是这台机器的配置
  box.appendChild(buildThemeGroup());

  const orphans = buildUngrouped();
  if (orphans) box.appendChild(orphans);

  for (const group of common) box.appendChild(buildGroup(group));
  const members = memberGroup();
  if (members) box.appendChild(members);

  // 高级默认收着，由组目录最底下的「高级选项」摆出来。收着就是整块不显示，不是一道折页：
  // 折页的标题横在页底，人会以为那就是全部
  if (advanced.length) {
    const region = el('section', 'settings-advanced hide');
    region.id = 'adv-groups';
    region.setAttribute('aria-label', '高级选项');
    for (const group of advanced) region.appendChild(buildGroup(group));
    box.appendChild(region);
  }

  buildNav(common, advanced);
  filterSettings();
}

/**
 * 这一次搜索是哪一次：搜索词加「只看改过」。亲手收起高级时记下它，换了就作废
 * @return {string}
 */
function filterKey() {
  return JSON.stringify([String($('#set-search').value).trim(), $('#only-changed').checked]);
}

/**
 * 把高级摆出来：从别处点名跳进高级里的一组时走这里
 */
function showAdvanced() {
  advancedChosen = true;
  advancedDismissed = null;
  filterSettings();
}

/**
 * 组目录最底下那颗「高级选项」：字不变，眼睛与读屏念的名字跟着开合换（见 filterSettings）
 *
 * 搜索把高级自动摆出来的时候，点它是收起：记下这一次搜索，同一次搜索上不再自动摆出来，
 * 改了搜索词再重新算。
 */
function toggleAdvanced() {
  const region = $('#adv-groups');
  const visible = !!region && !region.classList.contains('hide');
  advancedChosen = !visible;
  advancedDismissed = visible ? filterKey() : null;
  filterSettings();
}

/**
 * 吸在视口顶上的那几块有多高：窄屏上顶栏与组目录都吸顶，宽屏上没有吸顶元素
 *
 * 只有这一处量。点目录的落点（focusGroup）与「哪一组算当前」（watchCurrentGroup
 * 交给 currentGroupId 的那一位）都取它：两处各算各的话，窄屏上点任何一组都差一组——
 * 落点让了顶栏加目录，判「看得见」却让了 0，上一组的尾巴还压在底下就被当成看得见。
 * 顶栏里的内容会折行，高度不是定数，写死一个数换个设备、改一下字号就不准。
 * 959px 与 app.css 的窄屏断点同一处，两边各写一个数的话，改了一处没改另一处，
 * 宽屏上会白让一段，窄屏上照旧盖住。
 * @return {number} 像素
 */
function headerHeight() {
  if (!window.matchMedia('(max-width:959px)').matches) return 0;
  const side = $('#side');
  const nav = $('#grp-nav');
  return (side ? side.offsetHeight : 0) + (nav ? nav.offsetHeight : 0);
}

/**
 * 量一次窄屏顶栏的高度，交给样式那边的 --side-h
 *
 * 顶栏里的内容会折行（退出块单独一行），高度不是定数，写死一个数的话，
 * 换台设备、改一下字号，目录又会被盖回去。宽屏上量了也没人用（.grpnav 的
 * top 只在窄屏那一档里引用它），因此不用分档。跟着 watchCurrentGroup 走：
 * 设置页每次重画、窗口每次改大小都会重挂一遍，顶栏高度变了自然跟上
 */
function syncSideHeight() {
  const side = $('#side');
  if (!side) return;
  document.documentElement.style.setProperty('--side-h', side.offsetHeight + 'px');
}

/**
 * 文档此刻有多高。用来判断页面还能不能往下滚、有没有真滚到最底。
 * @return {number} 像素
 */
function pageHeight() {
  const root = document.documentElement;
  const body = document.body;
  const rootHeight = root ? root.scrollHeight : 0;
  const bodyHeight = body ? body.scrollHeight : 0;
  return rootHeight > bodyHeight ? rootHeight : bodyHeight;
}

/**
 * 观察各组在视口里的位置，把目录里对应那条标成当前
 *
 * 哪一组算当前交给 currentGroupId。每次判断都重新量各组的盒子，
 * 不沿用组进出视口时记下的旧坐标：折页展开、某一组变高、窗口改了大小，位置都会变。
 * 搜索／筛选会改哪些组可见，所以每次先拆掉再挂新的。
 */
function watchCurrentGroup() {
  stopWatchingGroups();
  const sections = [];
  for (const node of document.querySelectorAll('.setgrp[data-grp]')) {
    if (!node.classList.contains('hide')) sections.push(node);
  }
  if (!sections.length) return;

  const mark = () => {
    groupMarkFrame = 0;
    const scroll = window.scrollY;
    const groups = [];
    for (const section of sections) {
      if (!section.isConnected || section.classList.contains('hide')) continue;
      const rect = section.getBoundingClientRect();
      if (!(rect.height > 0)) continue;
      const top = scroll + rect.top;
      groups.push({id: section.dataset.grp, top: top, bottom: top + rect.height});
    }
    if (!groups.length) return;
    const current = currentGroupId(groups, scroll, window.innerHeight, headerHeight(), pageHeight());
    for (const link of document.querySelectorAll('#grp-nav [data-grp-link]')) {
      link.classList.toggle('cur', link.getAttribute('data-grp-link') === current);
    }
  };
  const schedule = () => {
    if (!groupMarkFrame) groupMarkFrame = requestAnimationFrame(mark);
  };

  groupWatcher = new IntersectionObserver(() => {
    schedule();
  }, {root: null, rootMargin: '0px', threshold: 0});
  for (const section of sections) groupWatcher.observe(section);

  const host = document.getElementById('groups');
  if (host && typeof ResizeObserver === 'function') {
    groupSizer = new ResizeObserver(() => {
      // 顶栏自己长高变矮（更新按钮、退出块落地，或折行）时先把 --side-h 量准再判高亮：
      // 那种时候窗口没改大小，原来那条路（resize）不走，窄屏目录的吸顶位置就停在旧高度上
      syncSideHeight();
      schedule();
    });
    groupSizer.observe(host);
    // #groups 以外的内容（页面上方的提示条、页底那块）变高变矮时整页高度跟着变，而
    // 「滚到最底就亮最后一组」读的正是整页高度——不看着它就不重判。看 body 这一层：
    // 页面内容都长在它里面，它的盒子高度跟着内容变；整页高度的读数仍由 pageHeight() 取。
    groupSizer.observe(document.body);
    // 窄屏顶栏也是自长的一层：内容落地、折行都会改它的高度，变了就得重量 --side-h
    const side = document.getElementById('side');
    if (side) groupSizer.observe(side);
  }

  groupScroll = schedule;
  window.addEventListener('scroll', groupScroll, {passive: true});
  // 窗口改了大小顶栏可能折行变高，先量一次再判高亮
  groupResize = () => {
    syncSideHeight();
    schedule();
  };
  window.addEventListener('resize', groupResize);
  syncSideHeight();
  schedule();
}

/**
 * 按搜索词与「只看改过的」筛一遍
 *
 * 收起的地方有命中就自动摆出来：搜到了却收着，与没搜到长得一样。
 */
export function filterSettings() {
  closeHelp();
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
  filterMembers(query, onlyChanged);

  // 一组里一项都不剩就把整组收起来，否则屏幕上留着一串空标题。
  // 界面组与自行筛选的组（data-ui）不在这里按配置行数收起。
  for (const group of document.querySelectorAll('.setgrp:not([data-ui])')) {
    const any = group.querySelectorAll('.setitem:not(.hide), .alcard:not(.hide)').length;
    group.classList.toggle('hide', any === 0);
  }

  const region = $('#adv-groups');
  const matches = region ? region.querySelectorAll('.setitem:not(.hide), .alcard:not(.hide)').length : 0;
  if (!filtering) advancedDismissed = null;
  const advancedVisible = advancedShown({matches, filtering, chosen: advancedChosen,
    dismissed: filtering && advancedDismissed === filterKey()});
  if (region) region.classList.toggle('hide', !advancedVisible);
  // 目录里高级那一段与高级区同开同合：搜到高级里的项、点名跳进高级时一起出来
  const advancedNav = $('#adv-nav');
  if (advancedNav) advancedNav.classList.toggle('hide', !advancedVisible);
  const toggle = $('#show-advanced');
  if (toggle) {
    toggle.setAttribute('aria-expanded', String(advancedVisible));
    toggle.setAttribute('aria-label', advancedVisible ? '高级选项，已显示；点击隐藏' : '高级选项，已隐藏；点击显示');
    toggle.title = advancedVisible ? '隐藏高级选项' : '显示高级选项';
    toggle.classList.toggle('hide', !arranged.advanced.length);
  }

  // 目录跟着组走：一组被筛没了，左边还留着那条会点进去什么也不发生。
  // 高级那一段整段的开合在上面跟着高级区走，这里只管段内哪几条被筛没了
  let advancedLinks = 0;
  for (const link of document.querySelectorAll('#grp-nav [data-grp-link]')) {
    const id = link.getAttribute('data-grp-link');
    const group = document.querySelector('.setgrp[data-grp="' + id + '"]');
    const gone = !group || group.classList.contains('hide');
    link.classList.toggle('hide', gone);
    if (!gone && region && region.contains(group)) advancedLinks++;
  }
  const sep = document.querySelector('#grp-nav .sep');
  if (sep) sep.classList.toggle('hide', advancedLinks === 0);
  watchCurrentGroup();

  const count = groups => groups.reduce((n, g) => n + g.fields.length, 0);
  $('#set-count').textContent = filtering
    ? '匹配 ' + shown + ' 项' + (matches && !advancedVisible ? '（含已隐藏的高级选项 ' + matches + ' 项）' : '')
    : '常用 ' + count(arranged.common) + ' 项 · 高级 ' + count(arranged.advanced) + ' 项';
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
  const node = $('#cfg-path');
  node.textContent = path || '（未获取到路径）';
  // 以 /status 传来的 path 为准，不从显示文本反推。空串＝/status 没带路径（服务端总会给，没带只会是没取到）。
  node.dataset.path = path || '';
}

/**
 * 复制路径
 *
 * 剪贴板接口只在安全上下文（https 或 localhost）里存在。面板常经内网 http 访问，
 * 那里 navigator.clipboard 直接是 undefined——不兜底的话，点下去毫无反应，
 * 而使用者无从知道是没复制上还是复制了。
 */
export async function copyConfigPath() {
  const path = $('#cfg-path').dataset.path;
  if (!path) {
    say('未获取到配置文件路径，无法复制', 'err');
    return;
  }
  try {
    await navigator.clipboard.writeText(path);
    say('已复制配置文件路径', 'ok');
  } catch (e) {
    say('当前浏览器不允许脚本写入剪贴板，请手动复制：' + path, 'err');
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
  if (target !== 'values') return;

  $('#save').disabled = true;
  showIssues(null);
  say('正在保存…');

  try {
    // 发出去的是此刻这一份。回包之前使用者可能又改了几项：那几项没随这一趟发出去，
    // 不能被回包后的重取当成已存——快照交给 load 逐项对账，见 load 里的 sentDrafts
    const sent = Object.assign({}, store.dirty);
    const res = await api('/values', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(sent)
    });

    // 保存成功就整体重取一遍，草稿态与「还欠一次重启」都由那一趟自己算。
    // 在这里按 store.dirty 顺手把新值抄进 store.values 是不行的：真正落盘的是哪几项
    // 只有服务端知道（送上来但值没变的项不会写，机密项送的是占位值也不会写），
    // 抄错的那几项此后会一直以为自己是「已保存的值」，而屏幕上看不出任何异常。
    // keepDrafts：这趟只清设置页自己的草稿，别页此刻有没存改动的不重取、草稿留着。
    // sentDrafts：真发出去的那一份——load 拿它与回包前又被改的草稿逐项对账，
    // 对不上的照旧算没保存的改动（与推送页 markPushSaved 同一条规矩）。
    // 对账有话要交代时（机密项在保存途中改回了留空，旧值还原不出来）由它带回来在这里说：
    // 那句话比「已保存」更要紧，说早了会被下面这句盖掉
    const note = res.success ? await load({keepDrafts: true, sentDrafts: sent}) : '';

    showIssues(res.issues);
    say(note || res.message || (res.success ? '已保存' : '保存失败'),
      note ? 'err' : (res.success ? 'ok' : 'err'));
  } catch (e) {
    say('保存失败：' + e.message, 'err');
  }

  markDirty();
}

/**
 * 丢掉当前这一页改过还没保存的内容，回到上一次保存时的样子
 *
 * 只动这一页：别的页此刻有没存改动的，草稿原样留着——保存与放弃都是对一页做的，
 * 连带把别页没存的改动一起丢掉时，屏幕上不会有任何提示，而那几处再也找不回来。
 *
 * 设置页走的是整体重载而不是「把改动逐项撤回」：撤回要为每种草稿态各写一套还原逻辑，
 * 而每加一种草稿态就多一处会漏掉的地方；重载则是把服务端的现值重新取一遍，
 * 漏不掉任何一处。代价是一次往返，放弃改动本就不是高频动作。
 * 插件页只让那一页自己重取、再按新取回的数据重画一次：它的草稿长什么样、怎么丢只有它
 * 自己知道，而 store.dirty 是设置页的草稿，那条路不能走整体载入（那一趟会把它一起清零）。
 * 只换数据不重画的话，屏上的控件还连着画屏那一刻的旧对象，接着在这屏上改的都是旧的。
 */
export async function discard() {
  const target = saveTarget();
  if (!await ask({title: '放弃本页未保存的修改？',
    body: '本页尚未保存的内容将恢复为上次保存时的状态。'})) return;
  if (target && target !== 'values') {
    // 它的 reload 回 false 时，状态栏上那句话是它自己写上去的，这里不能再盖一句上去
    const quiet = await reloadChangeSources({only: target});
    // 重取完让这一页自己重画一次，回 false 也画（那时取数出了事，屏上仍该是新数据）。
    // 只画这一页，而它重画不写状态栏，上面那句话照旧留着
    const repainted = refreshPages(target);
    markDirty();
    // 重画自己也出了事时，状态栏上已是那句「页出错」，也不该被下面这句盖掉
    if (quiet && repainted) say('已放弃本页未保存的修改', 'ok');
    return;
  }
  // 草稿态由 load 自己清。在这里先清一遍的话，重载失败时屏幕上还留着改过的值，
  // 而计数已经归零——看起来像「改动被保存了」
  await load({keepDrafts: true});
  say('已放弃本页未保存的修改', 'ok');
}
