/**
 * 模板编辑器：左边一张张消息卡，右边实时画成 QQ 气泡
 *
 * 本文件只管把 template-model.js 算好的东西摆上屏幕，再把屏幕上的编辑动作翻回块表。
 * 换算、屏幕读回块表、「块不可嵌套」、一张卡放几个附件、@ 那一档三选一、以及预览里 @ 落在哪，
 * 一律在那边判——判断留在这里的话，那几档就只能靠人打开页面拖着试，
 * 而其中好几档（机器人不是管理员、模板里手写了 @、块拖进块）手点点不出来。
 *
 * 两处用它：通道页第 2 段「消息长什么样」，与左树顶「默认模板」。同一份编辑器、
 * 同一套规矩，区别只在于改完之后写到哪儿去（见 buildTemplateEditor 的 onChange）。
 */

import {el, esc} from './core.js';
import {
  AT_LITERALS, AT_MODES, addCard, applyEdit, atLiteralOf, atModeInfo, atModeOf,
  atPlan, blockLabel, blockRefusal,
  blockSpec, blocksFromDom, changeAtBlocks, dropIndex, insertBlock, isAttachment, moveBlock, moveCard, placeAtBlock,
  normalizeCards, parseTemplate, placeholderBlock, previewBubbles, removeCard, splitText,
  spotFromDom, textBlock, toTemplateText,
} from './template-model.js';

/**
 * 正在拖的是什么
 *
 * 摆在模块这一层而不是随事件传：HTML5 拖放只捎得动字符串，而我们要捎的是
 * 「哪一张卡的第几块」。把它塞进 dataTransfer 再解析回来，等于给同一件事发明第二种表示。
 * 每处 dragstart 照样调一次 setData（见 startDrag），那只为让 Firefox 起拖，里面不装东西。
 */
let drag = null;

/**
 * 拖起来时往 dataTransfer 里塞的类型
 *
 * 自定义类型而不是 text/plain：页上别的输入框会把 text/plain 当文字收进去，
 * 拖整张卡时消息正文不拦，那串字会被插进正文里。
 */
const DRAG_TYPE = 'application/x-nova-drag';

/**
 * 记下这一下拖的是什么，并让浏览器起拖
 *
 * Firefox 不见 setData 就不起拖，模板里的块与数据卡片的小块在那里就拖不动；点和键盘不受影响。
 * @param event dragstart 事件
 * @param what 记到 drag 上的那一份
 * @param effect effectAllowed
 */
function startDrag(event, what, effect) {
  drag = what;
  event.dataTransfer.effectAllowed = effect;
  event.dataTransfer.setData(DRAG_TYPE, '');
}

/**
 * 模板编辑器自己的那几种拖法：调色板上新拖出来的块、正文里挪的块、整张卡
 *
 * 版式编辑器的卡片小块也记在 drag 上（kind 为 'report-card'）。消息正文只认这几种，
 * 别的一概不收——不然卡片小块拖进正文，会被当成一个占位符插进去。
 */
const TEMPLATE_DRAGS = ['new', 'move', 'card', 'at'];

/** 正在拖的是别的编辑器里的东西 */
const foreignDrag = () => !!drag && !TEMPLATE_DRAGS.includes(drag.kind);

/**
 * 建一个模板编辑器
 * @param host 挂在哪
 * @param options 见下：
 *   handlers      这一段管哪几类通知，每项是 /api/handlers 里的一项
 *   paramsOf      取某一类此刻的参数（可为 null）
 *   editable      改得动吗
 *   context       {isGroup, admin}，预览据此判 @ 落在哪、会不会被摘掉
 *   channelLabel  预览标题上写哪个通道
 *   lockedNote    改不动时那一句话
 *   onChange      改完了往哪写，入参为 (handler, params)
 */
export function buildTemplateEditor(host, options) {
  const opts = options || {};
  const handlers = (opts.handlers || []).filter(item => ((item.placeholders) || []).length);
  if (!handlers.length) {
    host.appendChild(el('p', 'hint')).textContent = opts.emptyNote
      || '还没有加载到任何带文字模板的通知，确认对应插件已加载。';
    return;
  }

  // 这一份编辑器此刻在编哪一类通知、编到了什么样子
  const state = {handler: handlers[0], cards: [], atMode: 'subscribers', spec: null, caret: null};

  const tabs = el('div', 'tpl-tabs');
  tabs.id = 'tpl-tabs';
  host.appendChild(tabs);

  if (!opts.editable && opts.lockedNote) {
    host.appendChild(el('p', 'xs dim')).textContent = opts.lockedNote;
  }

  const wrap = el('div', 'tpl-wrap');
  const left = el('div', 'tpl-left');
  const atControls = el('div', 'tpl-at-controls');
  left.appendChild(atControls);
  const palette = el('div', 'tpl-pal');
  palette.id = 'tpl-palette';
  left.appendChild(palette);
  const cards = el('div', 'tpl-cards');
  cards.id = 'tpl-cards';
  left.appendChild(cards);
  const raw = el('details', 'tpl-raw');
  raw.innerHTML = '<summary>高级：查看文本形式</summary>';
  const rawBody = el('pre');
  rawBody.id = 'tpl-raw';
  raw.appendChild(rawBody);
  raw.appendChild(el('p', 'xs dim')).textContent =
    '这是配置文件里存着的写法，只能看。上面的块与它一一对应；分条不再写 {next}——'
    + '一张卡就是一条消息。';
  left.appendChild(raw);
  wrap.appendChild(left);

  const right = el('div', 'tpl-right');
  const head = el('div', 'tpl-pv-h');
  head.appendChild(el('span')).textContent = (opts.channelLabel || '群里') + '会长这样';
  const count = el('span', 'xs dim');
  count.id = 'tpl-count';
  head.appendChild(count);
  right.appendChild(head);
  const preview = el('div', 'tpl-pv');
  preview.id = 'tpl-preview';
  right.appendChild(preview);
  wrap.appendChild(right);
  host.appendChild(wrap);

  /** 换一类通知：把它的模板读成块表 */
  function load(handler) {
    state.handler = handler;
    state.spec = blockSpec(handler);
    const params = (opts.paramsOf ? opts.paramsOf(handler) : null) || {};
    const base = baseOf(handler);
    // 没存过 message 的通道用的就是默认那一份：把默认摆出来给人改，
    // 而不是摆一张空卡——空卡会让人以为这个通道此刻什么都不发
    const text = params.message === undefined || params.message === null
      ? base.message : params.message;
    state.cards = normalizeCards(parseTemplate(text, state.spec));
    // 一张卡都没有的模板在编辑器里没法下手：给一张空卡，它拼回去仍是空串
    if (!state.cards.length) state.cards = [{blocks: [textBlock('')]}];
    // 这个通道自己没选过 @ 那一档时跟着默认走：params 盖在 base 上，正是这条判定
    state.atMode = atModeOf(Object.assign({}, base, params));
    state.caret = null;
    if (opts.onSelect) opts.onSelect(handler);
    paint();
  }

  /**
   * 改完了：拼回一整串，连同 @ 那一档一起交出去
   *
   * 与参照的默认一模一样的键由 applyEdit 删掉，不写回去——写回一份一字不差的副本，
   * 此刻看着一样，而下一次改默认时这个通道会独自停在原地。
   */
  function commit() {
    if (!opts.onChange) return;
    opts.onChange(state.handler, applyEdit(
      opts.paramsOf ? opts.paramsOf(state.handler) : null,
      baseOf(state.handler), state.cards, state.atMode));
  }

  /** 这一份编辑器拿谁当「默认」：通道拿本机此刻的默认，默认模板那一页拿出厂默认 */
  function baseOf(handler) {
    return (opts.base ? opts.base(handler) : handler.defaultParams) || {};
  }

  function paint() {
    paintTabs();
    paintPalette();
    paintCards();
    paintSide();
  }

  function paintTabs() {
    tabs.innerHTML = '';
    if (handlers.length < 2) return;
    for (const handler of handlers) {
      const button = el('button', 'tpl-tab' + (handler === state.handler ? ' on' : ''));
      button.type = 'button';
      button.textContent = handler.displayName || handler.className;
      button.setAttribute('aria-selected', String(handler === state.handler));
      button.addEventListener('click', () => load(handler));
      tabs.appendChild(button);
    }
  }

  function previewContext() {
    return {...(opts.context || {}), supportsSubscribers: state.spec.all.includes('{at}')};
  }

  // ---- 调色板 ----

  function paintPalette() {
    palette.innerHTML = '';
    palette.appendChild(el('span', 'xs dim')).textContent = '可用的块 · 点一下插到光标处，或拖进卡里';

    for (const key of state.spec.keys) {
      const used = blockRefusal(state.cards, 0, key, state.spec, -1);
      const attachment = isAttachment(key, state.spec);
      const button = el('button', 'tpl-blk' + (attachment ? ' att' : ''));
      button.type = 'button';
      button.textContent = (attachment ? '▤ ' : '') + blockLabel(key, state.spec);
      button.disabled = !opts.editable || !!used;
      button.title = opts.editable ? (used || '点一下插到光标处，或拖进卡里') : (opts.lockedNote || '');
      button.draggable = opts.editable && !used;
      button.addEventListener('click', () => insertHere(key));
      button.addEventListener('dragstart', event => startDrag(event, {kind: 'new', key}, 'copy'));
      button.addEventListener('dragend', () => { drag = null; });
      palette.appendChild(button);
    }
    atControls.innerHTML = '';
    atControls.appendChild(atNode());
  }

  /** 点一下调色板：插到最后一次落过光标的地方，没落过就贴到第一张卡末尾 */
  function insertHere(key) {
    const at = state.caret || {card: 0, index: state.cards[0].blocks.length, offset: 0};
    const refusal = blockRefusal(state.cards, at.card, key, state.spec, -1);
    if (refusal) {
      window.alert(refusal);
      return;
    }
    const cut = splitText(state.cards, at);
    state.cards = insertBlock(cut.cards, {card: at.card, index: cut.index},
      placeholderBlock(key, state.spec));
    state.caret = null;
    commit();
    paint();
  }

  // ---- 卡 ----

  function paintCards() {
    cards.innerHTML = '';
    state.cards.forEach((card, index) => cards.appendChild(cardNode(card, index)));

    const add = el('button', 'tpl-add');
    add.type = 'button';
    add.textContent = '⊕ 再加一条消息';
    add.disabled = !opts.editable;
    add.title = opts.editable ? '群里会多收到一条消息' : (opts.lockedNote || '');
    add.addEventListener('click', () => {
      state.cards = addCard(state.cards);
      commit();
      paint();
    });
    cards.appendChild(add);
  }

  function cardNode(card, index) {
    const box = el('div', 'tpl-card');
    const side = el('div', 'tpl-c-side');

    const handle = el('span', 'tpl-c-h');
    handle.textContent = '⋮⋮';
    handle.title = '拖动排序';
    handle.draggable = opts.editable;
    handle.addEventListener('dragstart', event => startDrag(event, {kind: 'card', card: index}, 'move'));
    handle.addEventListener('dragend', () => { drag = null; });
    side.appendChild(handle);

    const remove = el('button', 'tpl-c-x');
    remove.type = 'button';
    remove.textContent = '⊖';
    remove.disabled = !opts.editable;
    remove.setAttribute('aria-label', '删掉第 ' + (index + 1) + ' 条消息');
    remove.title = '删掉这条消息';
    remove.addEventListener('click', () => {
      const result = removeCard(state.cards, index);
      if (result.refused) {
        window.alert(result.refused);
        return;
      }
      state.cards = result.cards;
      commit();
      paint();
    });
    side.appendChild(remove);
    box.appendChild(side);

    const main = el('div', 'tpl-c-main');
    main.appendChild(el('div', 'tpl-c-n')).textContent = '第 ' + (index + 1) + ' 条消息';

    const body = el('div', 'tpl-c-body');
    body.contentEditable = opts.editable ? 'true' : 'false';
    body.setAttribute('role', 'textbox');
    body.setAttribute('aria-label', '第 ' + (index + 1) + ' 条消息的内容');
    paintBody(body, card, index);
    main.appendChild(body);
    box.appendChild(main);

    // 卡与卡之间换位：拖柄拖起来的是整张卡，落到哪张卡上就换到哪儿
    box.addEventListener('dragover', event => {
      if (!drag || drag.kind !== 'card') return;
      event.preventDefault();
      box.classList.add('over');
    });
    box.addEventListener('dragleave', () => box.classList.remove('over'));
    box.addEventListener('drop', event => {
      if (!drag || drag.kind !== 'card') return;
      event.preventDefault();
      box.classList.remove('over');
      state.cards = moveCard(state.cards, drag.card, index);
      drag = null;
      commit();
      paint();
    });
    return box;
  }

  /**
   * 一张卡的正文
   *
   * 文字原样摆着，占位符摆成一枚 contenteditable=false 的药丸——浏览器因此不会让人
   * 把光标伸进药丸里去改半个占位符，而 {uname 这种改了一半的写法发出去就是一串花括号。
   */
  function paintBody(body, card, cardIndex) {
    body.innerHTML = '';
    card.blocks.forEach(block => {
      if (block.kind === 'text') {
        const span = el('span', 'tpl-t');
        span.textContent = block.text;
        body.appendChild(span);
        return;
      }
      body.appendChild(pillNode(block, cardIndex));
    });
    if (!card.blocks.length) body.appendChild(el('span', 'tpl-t'));

    body.addEventListener('input', () => {
      // 打字之后 DOM 才是真的，块表得跟着它走
      state.cards = readBody(body, state.cards, cardIndex);
      rememberCaret(body, cardIndex);
      commit();
      paintPalette();
      paintSide();
    });
    body.addEventListener('focus', () => {
      if (!state.caret || state.caret.card !== cardIndex) {
        state.caret = {card: cardIndex, index: card.blocks.length, offset: 0};
      }
      rememberCaret(body, cardIndex);
    });
    body.addEventListener('keyup', () => rememberCaret(body, cardIndex));
    body.addEventListener('mouseup', () => rememberCaret(body, cardIndex));
    body.addEventListener('dragover', event => {
      if (foreignDrag()) {
        // 可编辑的正文默认收任何拖来的东西：明说不收，光标才显示放不下
        event.preventDefault();
        event.dataTransfer.dropEffect = 'none';
        return;
      }
      if (!drag || drag.kind === 'card' || !opts.editable) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = drag.kind === 'new' ? 'copy' : 'move';
    });
    body.addEventListener('drop', event => onDrop(event, body, cardIndex));
  }

  function pillNode(block, cardIndex) {
    const pill = el('span', block.attachment ? 'tpl-att' : 'tpl-pill');
    pill.contentEditable = 'false';
    pill.dataset.k = block.key;
    pill.title = block.key;
    pill.tabIndex = 0;
    pill.appendChild(document.createTextNode((block.attachment ? '▤ ' : '') + blockLabel(block.key, state.spec)));
    pill.draggable = opts.editable;

    if (opts.editable) {
      const kill = el('button', 'tpl-x');
      kill.type = 'button';
      kill.contentEditable = 'false';
      kill.textContent = '×';
      kill.setAttribute('aria-label', AT_LITERALS.includes(block.key)
        ? '恢复 @ 的默认位置' : '删除块 ' + blockLabel(block.key, state.spec));
      kill.addEventListener('click', event => {
        event.preventDefault();
        event.stopPropagation();
        // 先把这枚药丸从屏幕上摘掉再读回块表，而不是按序号去删：打过字之后
        // 渲染时写下的那个序号已经对不上了，而删错一块在屏幕上只是「少了个别的东西」
        const body = pill.closest('.tpl-c-body');
        pill.remove();
        state.cards = readBody(body, state.cards, cardIndex);
        commit();
        paint();
      });
      pill.appendChild(kill);
    }

    pill.addEventListener('keydown', event => {
      if (!opts.editable || !AT_LITERALS.includes(block.key)) return;
      const body = pill.closest('.tpl-c-body');
      const next = readBody(body, state.cards, cardIndex);
      const index = spotFromDom(body, pill, 0).index;
      const step = {ArrowLeft: -1, ArrowRight: 1}[event.key];
      if (event.key === 'Delete' || event.key === 'Backspace') {
        event.preventDefault();
        pill.querySelector('.tpl-x')?.click();
      } else if (step) {
        event.preventDefault();
        let card = cardIndex;
        let to = index + (step > 0 ? 2 : -1);
        if (to < 0 && card > 0) { card--; to = next[card].blocks.length; }
        if (to > next[card].blocks.length && card < next.length - 1) { card++; to = 0; }
        if (to < 0 || to > next[card].blocks.length) return;
        placeAt(next, {card, index: to});
        [...(cards.querySelectorAll('.tpl-c-body')[card]?.querySelectorAll('[data-k]') || [])]
          .find(node => AT_LITERALS.includes(node.dataset.k))?.focus();
      }
    });

    pill.addEventListener('dragstart', event => {
      // 拖起来的那一刻才算它此刻在第几格：渲染时算好的序号，在打过字之后已经对不上
      const body = pill.closest('.tpl-c-body');
      startDrag(event, {kind: 'move', key: block.key,
        from: {card: cardIndex, index: spotFromDom(body, pill, 0).index}}, 'move');
      event.stopPropagation();
    });
    pill.addEventListener('dragend', () => { drag = null; });
    return pill;
  }

  /** 模式选择与位置独立；按钮可用键盘或触屏定位。 */
  function atNode() {
    const row = el('div', 'tpl-at');
    const subscribers = state.spec.all.includes('{at}');
    const displayMode = !subscribers && state.atMode === 'all_or_subscribers' ? 'all' : state.atMode;
    const label = el('label');
    label.setAttribute('for', 'tpl-at');
    label.textContent = '@ 谁：';
    row.appendChild(label);
    const supported = state.spec.all.includes(atLiteralOf(state.atMode));
    const positionable = !!opts.editable && supported;
    const note = !subscribers && displayMode === 'subscribers' ? '' : atModeInfo(displayMode).note;
    const select = el('select', 'tpl-at-s');
    select.id = 'tpl-at';
    select.disabled = !opts.editable;
    select.setAttribute('aria-label', '选择提醒方式');
    select.title = note;
    const ctx = previewContext();
    const modes = subscribers ? AT_MODES : [
      {...AT_MODES[0], label: '不 @ 人'}, AT_MODES[1],
    ];
    for (const mode of modes) {
      const option = el('option');
      option.value = mode.key;
      option.textContent = mode.label;
      option.disabled = mode.key !== 'subscribers' && (ctx.admin === false || ctx.isGroup === false);
      if (mode.key === displayMode) option.selected = true;
      select.appendChild(option);
    }
    select.value = displayMode;
    select.addEventListener('change', event => {
      state.atMode = event.target.value;
      state.cards = changeAtBlocks(state.cards, state.atMode, state.spec);
      commit();
      paint();
      atControls.querySelector('#tpl-at')?.focus();
    });
    row.appendChild(select);
    if (supported) {
      const block = el('button', 'tpl-blk');
      block.type = 'button';
      block.textContent = blockLabel(atLiteralOf(state.atMode), state.spec);
      block.disabled = !positionable;
      block.draggable = positionable;
      block.title = opts.editable ? '点一下插到光标处，或拖进卡里' : (opts.lockedNote || '');
      block.addEventListener('click', insertAtHere);
      block.addEventListener('dragstart', event => {
        if (positionable) startDrag(event, {kind: 'at'}, 'move');
        else event.preventDefault();
      });
      block.addEventListener('dragend', () => { drag = null; });
      palette.appendChild(block);
    }
    if (note) row.appendChild(el('span', 'tpl-at-note xs dim')).textContent = note;
    if (ctx.admin === false || ctx.isGroup === false) {
      row.appendChild(el('p', 'tpl-at-warning xs')).textContent = ctx.isGroup === false
        ? '私聊没有 @全体成员。'
        : subscribers ? '机器人不是本群管理员，@全体成员 两档选不了。'
          : '机器人不是本群管理员，@全体成员 选不了。';
    }
    return row;
  }

  function placeAt(next, spot) {
    const result = placeAtBlock(next, state.atMode, spot, state.spec);
    if (result.refused) {
      window.alert(result.refused);
      return false;
    }
    state.cards = result.cards;
    const index = state.cards[spot.card].blocks.findIndex(block => AT_LITERALS.includes(block.key));
    state.caret = {card: spot.card, index: index + 1, offset: 0};
    commit();
    paint();
    return true;
  }

  function insertAtHere() {
    if (!opts.editable) return;
    const spot = state.caret || {card: 0, index: state.cards[0].blocks.length, offset: 0};
    const cut = splitText(state.cards, spot);
    if (placeAt(cut.cards, {card: spot.card, index: cut.index})) {
      cards.querySelectorAll('.tpl-c-body')[spot.card]?.focus();
    }
  }

  /** 记住光标落在哪一块的第几个字，点调色板时插到这里 */
  function rememberCaret(body, cardIndex) {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount) return;
    const range = selection.getRangeAt(0);
    if (!body.contains(range.startContainer)) return;
    const spot = spotFromDom(body, range.startContainer, range.startOffset);
    if (spot) state.caret = {card: cardIndex, index: spot.index, offset: spot.offset};
  }

  /**
   * 拖到卡里
   *
   * 🔴 落点压在一枚药丸上时，答案是<b>它前面或后面</b>，绝不是它的内部——那正是
   * 「块拖进块」那个缺陷：插进去之后屏幕上一枚药丸套着一枚，而拼回模板照样拼得出，
   * 发出去的消息一切正常。这一条由 dropIndex 判，本函数只负责问它。
   */
  function onDrop(event, body, cardIndex) {
    if (foreignDrag()) {
      // 拦下浏览器自己往正文里插东西，什么都不收
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (!drag || drag.kind === 'card' || !opts.editable) return;
    event.preventDefault();
    event.stopPropagation();

    const isAt = drag.kind === 'at' || AT_LITERALS.includes(drag.key);
    const key = isAt ? atLiteralOf(state.atMode) : drag.key;
    const from = drag.kind === 'move' ? drag.from : null;
    const refusal = blockRefusal(state.cards, cardIndex, key, state.spec, from ? from.card : -1);
    if (refusal) {
      drag = null;
      window.alert(refusal);
      return;
    }

    // 先把屏幕上此刻的样子读回块表：拖之前可能刚打过字，而那几个字还只在 DOM 里
    let cards = readBody(body, state.cards, cardIndex);
    const spot = spotFromPoint(body, event);
    let index;
    let split = false;
    if (spot && spot.split) {
      const cut = splitText(cards, {card: cardIndex, index: spot.index, offset: spot.offset});
      cards = cut.cards;
      index = cut.index;
      split = true;
    } else if (spot) {
      index = spot.index;
    } else {
      index = (cards[cardIndex] || {blocks: []}).blocks.length;
    }

    if (isAt) {
      drag = null;
      placeAt(cards, {card: cardIndex, index});
      return;
    } else if (from) {
      // 同一张卡里劈过一刀时，被拖的那一块的序号可能已经往后挪了一格
      const shifted = split && from.card === cardIndex && from.index > spot.index
        ? {card: from.card, index: from.index + 1} : from;
      state.cards = moveBlock(cards, shifted, {card: cardIndex, index});
    } else {
      state.cards = insertBlock(cards, {card: cardIndex, index}, placeholderBlock(key, state.spec));
    }

    drag = null;
    state.caret = null;
    commit();
    paint();
  }

  /**
   * 鼠标松开的那一点落在块表的哪一格
   *
   * 压在一枚药丸上时只答「它前面」或「它后面」：左半边落前面、右半边落后面，
   * 与文字编辑里插入光标的直觉一致，也正是这一条让「块拖进块」再也发生不了。
   * 落第几格由 spotFromDom 数——与 readBody 同一条走法，各数各的会把块插到两处。
   * @return {{index: number, offset: number, split: boolean}} 落不到时为 null
   */
  function spotFromPoint(body, event) {
    let node = event.target;
    if (node && node.nodeType === 3) node = node.parentNode;
    const pill = node && node.closest ? node.closest('[data-k]') : null;
    if (pill && body.contains(pill)) {
      const spot = spotFromDom(body, pill, 0);
      const box = pill.getBoundingClientRect();
      return {index: dropIndex(spot.index, event.clientX > box.left + box.width / 2),
        offset: 0, split: false};
    }

    const range = caretRangeFrom(event.clientX, event.clientY);
    if (!range || !body.contains(range.startContainer)) return null;
    const spot = spotFromDom(body, range.startContainer, range.startOffset);
    return spot ? {index: spot.index, offset: spot.offset, split: !spot.onPill} : null;
  }

  /** 一个屏幕坐标对应的文字位置。两种浏览器各有各的取法 */
  function caretRangeFrom(x, y) {
    if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
    if (document.caretPositionFromPoint) {
      const position = document.caretPositionFromPoint(x, y);
      if (!position) return null;
      const range = document.createRange();
      range.setStart(position.offsetNode, position.offset);
      range.collapse(true);
      return range;
    }
    return null;
  }

  /**
   * 把屏幕上那一张卡读回块表
   *
   * 节点怎么翻成块在 template-model.js（blocksFromDom）：那条走法要与 spotFromDom 数位置的
   * 一条路，摆在这里的话两处迟早各走各的。
   */
  function readBody(body, cards, cardIndex) {
    const blocks = blocksFromDom(body, state.spec);
    return normalizeCards((cards || []).map((card, index) =>
      index === cardIndex ? {blocks} : card));
  }

  // ---- 右侧与文本形式 ----

  function paintSide() {
    const bubbles = previewBubbles(state.cards, state.atMode, previewContext(), undefined, state.spec);
    preview.innerHTML = '';
    for (const bubble of bubbles) preview.appendChild(bubbleNode(bubble));
    if (!bubbles.length) {
      preview.appendChild(el('p', 'hint')).textContent = '这样配的话，一条消息也不会发出去。';
    }

    const plan = atPlan(state.cards, state.atMode, previewContext());
    if (bubbles.some(bubble => bubble.atDropped) || plan.dropped) {
      preview.appendChild(el('p', 'tpl-preview-note xs dim')).textContent = plan.fallback
        ? '发送时改为 @订阅的人，正文正常发送。'
        : '发送时省略 @全体成员，正文正常发送。';
    }

    count.textContent = bubbles.length + ' 条消息';
    rawBody.textContent = toTemplateText(state.cards);
  }

  function bubbleNode(bubble) {
    const row = el('div', 'qq-msg');
    row.appendChild(el('span', 'qq-av'));
    const stack = el('div');

    const balloon = el('div', 'qq-bub');
    if (bubble.at === 'all') {
      const at = el('span', 'qq-atall' + (bubble.atDropped ? ' gone' : ''));
      at.textContent = '@全体成员 ';
      balloon.appendChild(at);
    } else if (bubble.at === 'subscribers') {
      balloon.appendChild(el('span', 'qq-at')).textContent = '@订阅的人 ';
    }
    for (const part of bubble.parts) {
      if (part.kind === 'text') {
        balloon.appendChild(document.createTextNode(part.text));
      } else if (part.kind === 'at') {
        balloon.appendChild(el('span', part.at === 'all' ? 'qq-atall' : 'qq-at')).textContent =
          part.at === 'all' ? '@全体成员' : '@订阅的人';
      } else {
        balloon.appendChild(el('span', 'qq-ph')).textContent = part.label;
      }
    }
    stack.appendChild(balloon);

    for (const image of bubble.images) {
      stack.appendChild(el('div', 'qq-img')).textContent = '［' + image + '］';
    }
    row.appendChild(stack);
    return row;
  }

  load(handlers[0]);
}

/**
 * 顺序项存着的值读成完整的一串 key
 *
 * 与处理器那一侧（HandlerOption.orderOf）同一个读法：不认识的跳过，重复的只算第一次，
 * 缺了的按默认顺序补在后面。默认值就是哪几项能排的唯一出处。
 * @param stored 参数里存着的值，可为空
 * @param defaultValue 顺序项的默认值，逗号连起来的 key
 * @return {string[]} 默认值里每个 key 恰好一次，按存着的先后排
 */
export function normalizedOrder(stored, defaultValue) {
  const split = value => typeof value === 'string'
    ? value.split(',').map(item => item.trim()).filter(Boolean) : [];
  const known = split(defaultValue);
  const out = [];
  for (const key of split(stored).concat(known)) {
    if (known.includes(key) && !out.includes(key)) out.push(key);
  }
  return out;
}

/**
 * 「报告长什么样」：左边一排版式开关，右边照着开关画出来的那张图
 *
 * 版式项清单取自处理器自报的那一张（{@code /api/handlers} 的 options），
 * 前端不写死一份：写死的那一份迟早与真正生效的对不上，而对不上时不会有任何报错，
 * 只会让人以为「配了没用」。
 * <p>
 * 版式项还带着「随金额」一栏（revenueVisibility／revenueNote，处理器自报）：
 * 当前会话的金额设定下不出图的项灰掉、写一句原因并指向「本群设置」的金额开关，
 * 只变样的项不灰、加一句说明——不然使用者把「流水排行」调成前 10 名，
 * 图上永远没有这一榜，还以为是坏了。灰不灰读 opts.revenueVisible 现算的值
 * （会话存的设定＋草稿），草稿里拨了当场跟着变；存着的值不清不改。
 * <p>
 * 带 parentKey 的项（处理器自报「跟着哪一项开关走」）缩进列在那一项下面；那一项关着
 * 或灰着时它们一并灰掉、写一句为什么，存着的值同样不清不改，打开就亮回来。
 * 不带这一栏的项照旧按清单顺序平铺。
 * <p>
 * 处理器还可以报一个顺序项（type ORDER）：它点名的那几个开关不画成一行行，而画成两区小块
 * （见 boardNode）——在报告区＝开着，报告区里的先后＝顺序项的值。没有顺序项的处理器界面不变。
 * <p>
 * ℹ️ 服务端另有一支 {@code /api/report/layout-options} 给同一张表。<b>这里不调它</b>——
 * 同一张表从两个口进来，两个口的字段名还不一样（那边是 {@code default}，
 * 这边是 {@code defaultValue}），而「这一项改过没有」在别处是按后者判的。
 * 一张表两份读法，分叉的那天屏幕上不会有任何异常。
 * <p>
 * 那张图必须由服务端画：自己画的话，预览与真出报告在「越界值怎么夹」「缺项取什么默认」
 * 这些地方会悄悄给出不同的结果，而那正是所见即所得要防的事。
 * @param host 挂在哪
 * @param options paramsOf/editable/onChange 三项与 buildTemplateEditor 同形，另加
 *        items（版式项清单）、render（按当前这套版式画一张的那一支）、
 *        caption（图下那行说明，可为函数：按草稿现说，每次重画时重取）、
 *        revenueVisible（算当前金额可见性的函数，含草稿；不传则不灰任何项）
 * @return {{repaint: Function}} repaint：金额草稿变了之类的外部变化进来时，整段重画一遍
 */
export function buildLayoutEditor(host, options) {
  const opts = options || {};
  const box = el('div', 'rep-wrap');
  const controls = el('div', 'rep-opts');
  controls.id = 'rep-options';
  box.appendChild(controls);
  const view = el('div', 'rep-view');
  const image = el('div', 'rep-img');
  image.id = 'rep-preview';
  view.appendChild(image);
  const cap = el('p', 'hint');
  cap.id = 'rep-caption';
  view.appendChild(cap);
  box.appendChild(view);
  host.appendChild(box);

  const items = opts.items || [];
  // 顺序项（type ORDER）不单画一行：它点名的那几个开关画成两区小块（见 boardNode）
  const isOrder = option => String(option.type).toUpperCase() === 'ORDER';
  const orders = items.filter(isOrder);
  const rowItems = items.filter(option => !isOrder(option));

  function valueOf(option) {
    const params = (opts.paramsOf ? opts.paramsOf() : null) || {};
    const stored = params[option.key];
    return stored === undefined || stored === null ? option.defaultValue : stored;
  }

  /**
   * 这一份编辑器打开时存着的那一份参数
   *
   * 小块动完回到这一刻时，键的形状也得回到这一刻（见 setBack）
   */
  const beforeEdit = Object.assign({}, (opts.paramsOf ? opts.paramsOf() : null) || {});

  /** 动它之前那一刻的取值：没存过时与 valueOf 同一条规矩取默认 */
  function beforeValueOf(option) {
    const stored = beforeEdit[option.key];
    return stored === undefined || stored === null ? option.defaultValue : stored;
  }

  /**
   * 这一项在当前的金额设定下怎么走
   *
   * @return {string|null} 'off'＝不出图，灰掉并写原因；'styled'＝照出但变样，加一句说明；
   *   null＝照常，什么都不加
   */
  function revenueState(option, visible) {
    if (typeof opts.revenueVisible !== 'function') return null;
    if (option.revenueVisibility === 'ONLY_WHEN_SHOWN') return visible === false ? 'off' : null;
    if (option.revenueVisibility === 'ONLY_WHEN_HIDDEN') return visible === true ? 'off' : null;
    if (option.revenueVisibility === 'RESTYLED_WHEN_HIDDEN') return 'styled';
    return null;
  }

  /** 灰掉的原因那句：处理器给的为什么，加上去哪儿改这一段 */
  function whyText(option) {
    const note = option.revenueNote || '这一项在当前的金额设定下不出图';
    const how = option.revenueVisibility === 'ONLY_WHEN_HIDDEN'
      ? '在下方「本群设置」的「金额可见」里关掉金额后才会画出'
      : '在下方「本群设置」的「金额可见」里打开金额后才会画出';
    return note + '。' + how + '。';
  }

  /**
   * 画的顺序：顶层项照清单顺序，跟着某一项走的紧跟在那一项后面
   *
   * parentKey 指向清单里没有的键时按顶层项放，不让它从界面上消失。
   * @return {Array<{option: object, parent: object|null}>}
   */
  function ordered() {
    const keys = new Set(rowItems.map(option => option.key));
    const out = [];
    const seen = new Set();
    const visit = (option, parent) => {
      if (seen.has(option)) return;
      seen.add(option);
      out.push({option, parent});
      for (const child of rowItems) {
        if (child.parentKey === option.key) visit(child, option);
      }
    };
    for (const option of rowItems) {
      if (!option.parentKey || !keys.has(option.parentKey)) visit(option, null);
    }
    // 互相指着的那几项走不到：照顶层补上
    for (const option of rowItems) visit(option, null);
    return out;
  }

  /** 顺序项点名的、清单里真有的开关项：它们画成小块，在报告区＝开着 */
  function membersOf(order) {
    const named = normalizedOrder(order.defaultValue, order.defaultValue);
    return rowItems.filter(option => named.includes(option.key)
      && String(option.type).toUpperCase() === 'BOOLEAN');
  }

  /** 这个顺序项眼下的完整顺序，只留画成小块的那几项 */
  function currentOrder(order) {
    const present = new Set(membersOf(order).map(option => option.key));
    return normalizedOrder(valueOf(order), order.defaultValue).filter(key => present.has(key));
  }

  /**
   * 整串顺序上把 key 挪到 target 前面或后面；target 为 null 时挪到末尾
   *
   * 挪的是存着的整串（关着的也在里面），不只是报告区看得见的那几枚：
   * 关着的卡留在它原来的位置上，放回时才另说。
   */
  function placed(sequence, key, target, after) {
    if (target === key) return sequence;
    const out = sequence.filter(item => item !== key);
    const at = target === null || !out.includes(target) ? out.length : out.indexOf(target) + (after ? 1 : 0);
    out.splice(at, 0, key);
    return out;
  }

  /**
   * 这一键动完之后怎么落回参数
   *
   * 值回到「动它之前」那一刻时，键的形状也回到那一刻：那一刻没这个键的删掉、显式存着的
   * 照原样写回。只写值不看形状的话，走默认（没存过这个键）的通道动两下回来会多出一个键，
   * 改动条上平白多出一处；反过来一律删键的话，显式存着等于默认值的那一份又会少一个键，
   * 同样多记一处。改动条按整段 JSON 与快照比，形状对不上就是一处改动。
   *
   * 值没回到那一刻却落在默认值上时删掉键：删掉之后这个通道跟着默认走，与「恢复默认」
   * 按钮同一条规矩（见 push-model.js 的 restoredLayoutParams）。
   * @param params 正在改的那一份参数
   * @param option 这一项（顺序项、小块的开关或平铺的那一行）
   * @param value 这一刻该记的值（顺序项是逗号连起来的整串）
   * @param before 动它之前那一刻的值，同一把尺
   */
  function setBack(params, option, value, before) {
    if (value === before) {
      const stored = beforeEdit[option.key];
      if (stored === undefined || stored === null) delete params[option.key];
      else params[option.key] = stored;
      return;
    }
    if (String(value) === String(option.defaultValue)) {
      delete params[option.key];
      return;
    }
    params[option.key] = value;
  }

  /**
   * 小块上的一次操作：顺序与那张卡的开关一起写回，一次 onChange
   *
   * 写回的是读好的整串：残缺的老值在真改动顺序时就补齐了。值回到动它之前那一刻时，
   * 键的形状也一并回到那一刻（见 setBack）。两样都与此刻一样时（原地拖、
   * 拖回同一格）什么都不写——写一份一模一样的值，状态行照样不变，可草稿会平白记一次改动。
   * 重画之后焦点落在这张卡此刻所在那一区的那一枚上。
   * @param on 开关写成什么；undefined 为不动
   * @param zone 'on' 或 'off'：重画后焦点落在哪一区
   */
  function act(order, key, sequence, on, zone) {
    const option = membersOf(order).find(item => item.key === key);
    if (!option) return;
    const params = Object.assign({}, (opts.paramsOf ? opts.paramsOf() : null) || {});
    let touched = false;
    const joined = sequence.join(',');
    const nowOrder = normalizedOrder(valueOf(order), order.defaultValue).join(',');
    if (joined !== nowOrder) {
      setBack(params, order, joined,
        normalizedOrder(beforeValueOf(order), order.defaultValue).join(','));
      touched = true;
    }
    if (on !== undefined && !!valueOf(option) !== on) {
      setBack(params, option, on, !!beforeValueOf(option));
      touched = true;
    }
    if (!touched) return;
    if (opts.onChange) opts.onChange(params);
    paint();
    const node = chipNodes.get(zone + ':' + key);
    if (node) node.focus();
  }

  /** 这一次重画出来的小块，按「区:key」记下：动完之后要把焦点放回去 */
  const chipNodes = new Map();

  /** 本编辑器的记号：两个编辑器共用模块上的 drag，只收自己这里拖起来的小块 */
  const owner = {};

  /**
   * 顺序项画成两区小块：上面「报告里的卡片」，下面「没放进报告的」
   *
   * 在报告区＝那张卡的开关开着，报告区里的先后＝顺序项存着的先后。上面的拖着换位、× 拿掉
   * （键盘：左右方向键挪一格、Delete／Backspace 拿掉）；下面的点一下放回末尾（键盘：回车／空格），
   * 或拖进报告区落在放下的位置。锁着时（页不可编辑、跟着的那一项关着）两区都拖不动、点不动，
   * 存着的值不动。原来每一行写着的说明放进悬停提示，隐藏金额时换说法的那几枚带一个 ¥。
   * @param order 顺序项
   * @param why 锁着的原因，null 为没锁
   * @param visible 当前的金额可见性，给 revenueState
   */
  function boardNode(order, why, visible) {
    const locked = why !== null;
    const members = membersOf(order);
    const optionOf = key => members.find(option => option.key === key);
    const sequence = normalizedOrder(valueOf(order), order.defaultValue);
    const shown = currentOrder(order);
    const onKeys = shown.filter(key => valueOf(optionOf(key)));
    const offKeys = shown.filter(key => !valueOf(optionOf(key)));
    const nameOf = key => optionOf(key).label || key;

    const board = el('div', 'rep-board rep-sub' + (locked ? ' locked' : ''));
    // 只收本编辑器这一组拖起来的小块：占位符、别处的小块都不带这两样。
    // 锁着时小块拖不起来（见 dragstart），这里不再另判一道
    const accepts = () => !!drag && drag.owner === owner && drag.order === order.key;

    /** 一枚小块的公共部分：卡名、¥ 记号、悬停说明、拖起来 */
    const chipNode = (key, zone) => {
      const option = optionOf(key);
      const state = revenueState(option, visible);
      const node = el('span', 'rep-chip' + (zone === 'off' ? ' rep-chip-off' : '')
        + (state === 'off' ? ' rev-off' : ''));
      node.dataset.card = key;
      node.tabIndex = 0;
      node.appendChild(document.createTextNode(nameOf(key)));
      const tips = [option.description || ''];
      if (state === 'styled') {
        const mark = el('span', 'rep-chip-m');
        mark.textContent = '¥';
        mark.setAttribute('aria-hidden', 'true');
        node.appendChild(mark);
        tips.push(option.revenueNote || '');
      } else if (state === 'off') {
        tips.push(whyText(option));
      }
      if (locked) tips.push(why);
      node.title = tips.filter(Boolean).join('\n');
      node.draggable = !locked;
      node.addEventListener('dragstart', event => {
        if (locked) {
          event.preventDefault();
          return;
        }
        startDrag(event, {kind: 'report-card', owner, order: order.key, key}, 'move');
        event.stopPropagation();
      });
      node.addEventListener('dragend', () => { drag = null; });
      chipNodes.set(zone + ':' + key, node);
      return node;
    };

    /** 一区：标题、小块、空着时那一句；放下时由 onDrop 决定落在哪 */
    const zoneNode = (zone, title, keys, empty, onDrop) => {
      board.appendChild(el('p', 'rep-zone-t xs dim')).textContent = title;
      const area = el('div', 'rep-zone rep-zone-' + zone);
      area.setAttribute('role', 'list');
      area.setAttribute('aria-label', title.split(' · ')[0]);
      area.addEventListener('dragover', event => {
        if (!accepts()) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        area.classList.add('over');
      });
      area.addEventListener('dragleave', () => area.classList.remove('over'));
      area.addEventListener('drop', event => {
        if (!accepts()) return;
        event.preventDefault();
        area.classList.remove('over');
        const moving = drag.key;
        drag = null;
        // 落在一枚上：左半边落它前面、右半边落它后面，与消息正文里拖占位符一个直觉
        const hit = event.target && event.target.closest ? event.target.closest('.rep-chip') : null;
        if (hit && area.contains(hit)) {
          const box = hit.getBoundingClientRect();
          onDrop(moving, hit.dataset.card, event.clientX > box.left + box.width / 2);
        } else {
          onDrop(moving, null, false);
        }
      });
      if (!keys.length) area.appendChild(el('span', 'rep-zone-empty xs dim')).textContent = empty;
      board.appendChild(area);
      return area;
    };

    const onArea = zoneNode('on', '报告里的卡片 · 拖着排先后，× 拿掉', onKeys, '一张都没放，这一块不画',
      (key, target, after) => act(order, key, placed(sequence, key, target, after), true, 'on'));
    onKeys.forEach((key, index) => {
      const node = chipNode(key, 'on');
      node.setAttribute('role', 'listitem');
      node.setAttribute('aria-label', nameOf(key) + '，报告里第 ' + (index + 1) + ' 张，共 ' + onKeys.length + ' 张。'
        + (locked ? why : '左右方向键挪位置，Delete 拿掉，也可以拖'));
      const kill = el('button', 'rep-chip-x');
      kill.type = 'button';
      kill.textContent = '×';
      // 键盘走 Delete，不在 Tab 上多停一站；读屏的浏览模式与手机点按照样够得着
      kill.tabIndex = -1;
      kill.setAttribute('aria-label', '拿掉：' + nameOf(key));
      kill.disabled = locked;
      kill.addEventListener('click', event => {
        event.stopPropagation();
        if (!locked) act(order, key, sequence, false, 'off');
      });
      node.appendChild(kill);
      node.addEventListener('keydown', event => {
        if (locked) return;
        const step = {ArrowLeft: -1, ArrowRight: 1}[event.key];
        if (step) {
          event.preventDefault();
          const next = onKeys[index + step];
          if (next !== undefined) act(order, key, placed(sequence, key, next, step > 0), undefined, 'on');
        } else if (event.key === 'Delete' || event.key === 'Backspace') {
          event.preventDefault();
          act(order, key, sequence, false, 'off');
        }
      });
      onArea.appendChild(node);
    });

    const offArea = offKeys.length ? zoneNode('off', '没放进报告的 · 点一下或拖回上面', offKeys, '都在报告里',
      key => act(order, key, sequence, false, 'off')) : null;
    for (const key of offKeys) {
      const node = chipNode(key, 'off');
      node.setAttribute('role', 'button');
      node.setAttribute('aria-label', '放回报告：' + nameOf(key) + '。' + (locked ? why : '回车或空格放到末尾，也可以拖进上面'));
      if (locked) node.setAttribute('aria-disabled', 'true');
      // 放回排到末尾：整串里挪到最后，报告区里也就是最后一张
      const putBack = () => { if (!locked) act(order, key, placed(sequence, key, null, false), true, 'on'); };
      node.addEventListener('click', putBack);
      node.addEventListener('keydown', event => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        putBack();
      });
      offArea.appendChild(node);
    }

    if (members.some(option => revenueState(option, visible) === 'styled')) {
      board.appendChild(el('p', 'rep-why xs dim')).textContent =
        '带 ¥ 的几张在隐藏金额的会话里换成不带钱的说法；鼠标停在小块上看它画什么。';
    }
    if (locked) board.appendChild(el('p', 'rep-why')).textContent = why;
    return board;
  }

  /** 平铺那一行的开关或数字改了一下：写回与小块同一条规矩（见 setBack） */
  function change(option, value) {
    const params = Object.assign({}, (opts.paramsOf ? opts.paramsOf() : null) || {});
    setBack(params, option, value, beforeValueOf(option));
    if (opts.onChange) opts.onChange(params);
    paint();
  }

  function paint() {
    const visible = typeof opts.revenueVisible === 'function' ? opts.revenueVisible() : undefined;
    controls.innerHTML = '';
    chipNodes.clear();
    // 各项此刻起不起作用，按 key 记下：子项看它跟着的那一项关没关、灰没灰
    const inert = new Map();
    const boards = new Set();
    for (const {option, parent} of ordered()) {
      const state = revenueState(option, visible);
      const off = state === 'off';
      // 跟着的那一项关着或灰着：这一项不起作用，灰掉，存着的值不动
      const parentOff = !!parent && (inert.get(parent.key) || !valueOf(parent));
      inert.set(option.key, off || parentOff);
      // 顺序项点名的开关不画成一行：在头一枚的位置画一次两区小块，锁不锁按头一枚跟着的那一项
      const order = orders.find(item => membersOf(item).includes(option));
      if (order) {
        if (boards.has(order)) continue;
        boards.add(order);
        const why = !opts.editable ? (opts.lockedNote || '这一页改不了。')
          : parentOff ? '「' + (parent.label || parent.key) + '」关着，这几张不起作用；打开后照这里的设定画。'
            : null;
        controls.appendChild(boardNode(order, why, visible));
        continue;
      }
      const row = el('div', 'rep-row' + (parent ? ' rep-sub' : '') + (off ? ' rev-off' : '')
        + (parentOff ? ' parent-off' : ''));
      const locked = !opts.editable || off || parentOff;
      if (String(option.type).toUpperCase() === 'BOOLEAN') {
        const label = el('label', 'switch');
        label.innerHTML = '<input type="checkbox"' + (valueOf(option) ? ' checked' : '')
          + (locked ? ' disabled' : '') + '>';
        const input = label.querySelector('input');
        input.setAttribute('aria-label', option.label || option.key);
        input.addEventListener('change', event => change(option, event.target.checked));
        row.appendChild(label);
      } else {
        const input = el('input', 'rep-n');
        input.type = 'number';
        input.value = String(valueOf(option));
        if (option.min !== null && option.min !== undefined) input.min = String(option.min);
        if (option.max !== null && option.max !== undefined) input.max = String(option.max);
        input.setAttribute('aria-label', option.label || option.key);
        // 灰掉时锁的不只是样式：能拨却不起作用的开关，比锁着的更费解。
        // 存着的值不清不改，亮回来还是原来那个数
        input.disabled = locked;
        input.addEventListener('change', event => change(option, Number(event.target.value)));
        row.appendChild(input);
      }

      const text = el('div', 'rep-txt');
      text.innerHTML = '<b>' + esc(option.label || option.key) + '</b>'
        + '<p>' + esc(option.description || '') + '</p>';
      if (off) {
        // 原因与去哪儿改写在灰掉的那行里：不写的话，「配了不出图」与「坏了」长得一样
        text.appendChild(el('p', 'rep-why')).textContent = whyText(option);
      } else if (parentOff) {
        text.appendChild(el('p', 'rep-why')).textContent =
          '「' + (parent.label || parent.key) + '」关着，这一项不起作用；打开后照这里的设定画。';
      } else if (state === 'styled') {
        // 变样的那三项不灰：它们照出，只是换了不带钱的说法——灰掉反而让人以为配不了
        text.appendChild(el('p', 'rep-why')).textContent = option.revenueNote || '';
      }
      row.appendChild(text);
      controls.appendChild(row);
    }
    if (!opts.editable && opts.lockedNote) {
      controls.appendChild(el('p', 'hint')).textContent = opts.lockedNote;
    }
    cap.textContent = typeof opts.caption === 'function' ? opts.caption() : (opts.caption || '');
    cap.hidden = !cap.textContent;
    redraw();
  }

  /** 照当前这套版式画一张 */
  function redraw() {
    image.innerHTML = '';
    image.appendChild(el('p', 'hint')).textContent = '正在画…';
    opts.render((opts.paramsOf ? opts.paramsOf() : null) || {}).then(url => {
      image.innerHTML = '';
      if (!url) {
        // 画不出来时说明白，不留一块白：一块白与「这套版式什么都不显示」长得一样
        image.appendChild(el('p', 'hint')).textContent =
          '这一类通知的预览取不到，确认对应插件已加载。改动照样存得下来。';
        return;
      }
      const node = el('img', 'rep-shot');
      node.src = url;
      node.alt = '按当前版式画出来的报告';
      image.appendChild(node);
    });
  }

  if (!items.length) {
    controls.appendChild(el('p', 'hint')).textContent =
      '这一类通知没有可调的版式项，或对应插件没加载。';
  }
  paint();
  return {repaint: paint};
}
