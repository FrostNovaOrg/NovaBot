/**
 * 模板编辑器：左边一张张消息卡，右边实时画成 QQ 气泡
 *
 * 本文件只管把 template-model.js 算好的东西摆上屏幕，再把屏幕上的编辑动作翻回块表。
 * 换算、「块不可嵌套」、一张卡放几个附件、@ 那一档三选一、以及预览里 @ 落在哪，
 * 一律在那边判——判断留在这里的话，那几档就只能靠人打开页面拖着试，
 * 而其中好几档（机器人不是管理员、模板里手写了 @、块拖进块）手点点不出来。
 *
 * 两处用它：通道页第 2 段「消息长什么样」，与左树顶「默认模板」。同一份编辑器、
 * 同一套规矩，区别只在于改完之后写到哪儿去（见 buildTemplateEditor 的 onChange）。
 */

import {el, esc} from './core.js';
import {
  AT_MODES, addCard, applyEdit, atModeInfo, atModeOf, atPlan, blockLabel, blockRefusal,
  blockSpec, dropIndex, insertBlock, isAttachment, moveBlock, moveCard, normalizeCards,
  parseTemplate, placeholderBlock, previewBubbles, removeCard, splitText, textBlock,
  toTemplateText,
} from './template-model.js';

/**
 * 正在拖的是什么
 *
 * 摆在模块这一层而不是随事件传：HTML5 拖放只捎得动字符串，而我们要捎的是
 * 「哪一张卡的第几块」。把它塞进 dataTransfer 再解析回来，等于给同一件事发明第二种表示。
 */
let drag = null;

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

  const note = el('div', 'tpl-note xs dim');
  note.id = 'tpl-note';
  host.appendChild(note);

  const wrap = el('div', 'tpl-wrap');
  const left = el('div', 'tpl-left');
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
    paintNote();
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

  function paintNote() {
    const plan = atPlan(state.cards, state.atMode, opts.context);
    const lines = [];
    if (!opts.editable && opts.lockedNote) lines.push(opts.lockedNote);
    if (state.cards.length === 1) {
      lines.push('现在是一条消息（文字与附件同一条）。想拆成两条，就「再加一条消息」，'
        + '再把附件块拖过去。');
    }
    if (plan.inline) {
      lines.push('这份模板里自己写了 @ 的占位符：程序见模板里已经有了就不再补一份，'
        + '因此「@ 谁」那一档只管没写的那种情形。');
    }
    note.textContent = lines.join(' ');
    note.hidden = !lines.length;
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
      button.textContent = (attachment ? '▤ ' : '') + blockLabel(key);
      button.disabled = !opts.editable || !!used;
      button.title = opts.editable ? (used || '点一下插到光标处，或拖进卡里') : (opts.lockedNote || '');
      button.draggable = opts.editable && !used;
      button.addEventListener('click', () => insertHere(key));
      button.addEventListener('dragstart', event => {
        drag = {kind: 'new', key};
        event.dataTransfer.effectAllowed = 'copy';
      });
      button.addEventListener('dragend', () => { drag = null; });
      palette.appendChild(button);
    }
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
    handle.addEventListener('dragstart', event => {
      drag = {kind: 'card', card: index};
      event.dataTransfer.effectAllowed = 'move';
    });
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
    if (index === 0) main.appendChild(atNode());
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
      commit();
      paintSide();
    });
    body.addEventListener('keyup', () => rememberCaret(body, cardIndex));
    body.addEventListener('mouseup', () => rememberCaret(body, cardIndex));
    body.addEventListener('dragover', event => {
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
    pill.appendChild(document.createTextNode((block.attachment ? '▤ ' : '') + blockLabel(block.key)));
    pill.draggable = opts.editable;

    if (opts.editable) {
      const kill = el('button', 'tpl-x');
      kill.type = 'button';
      kill.contentEditable = 'false';
      kill.textContent = '×';
      kill.setAttribute('aria-label', '删除块 ' + blockLabel(block.key));
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

    pill.addEventListener('dragstart', event => {
      // 拖起来的那一刻才算它此刻在第几格：渲染时算好的序号，在打过字之后已经对不上
      const body = pill.closest('.tpl-c-body');
      drag = {kind: 'move', key: block.key,
        from: {card: cardIndex, index: spotOf(body, pill, 0).index}};
      event.dataTransfer.effectAllowed = 'move';
      event.stopPropagation();
    });
    pill.addEventListener('dragend', () => { drag = null; });
    return pill;
  }

  /**
   * @ 那一枚药丸
   *
   * 钉在第一张卡的开头，不能拖也不能删：它存的是推送参数 at_mode，程序在发送时
   * 把 @ 拼到正文最前面，屏幕上摆在别处等于画了一个假位置。三档是个闭集，
   * 里面没有「不 @」——「@订阅的人」那一档在无人订阅时本来就什么也不 @。
   */
  function atNode() {
    const row = el('div', 'tpl-at');
    row.appendChild(el('span', 'tpl-at-t')).textContent = '@ 谁';

    const select = el('select', 'tpl-at-s');
    select.id = 'tpl-at';
    select.disabled = !opts.editable;
    for (const mode of AT_MODES) {
      const option = el('option');
      option.value = mode.key;
      option.textContent = mode.label;
      if (mode.key === state.atMode) option.selected = true;
      select.appendChild(option);
    }
    select.addEventListener('change', event => {
      state.atMode = event.target.value;
      commit();
      paint();
    });
    row.appendChild(select);
    row.appendChild(el('span', 'xs dim')).textContent = atModeInfo(state.atMode).note;
    return row;
  }

  /** 记住光标落在哪一块的第几个字，点调色板时插到这里 */
  function rememberCaret(body, cardIndex) {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount) return;
    const range = selection.getRangeAt(0);
    if (!body.contains(range.startContainer)) return;
    const spot = spotOf(body, range.startContainer, range.startOffset);
    if (spot) state.caret = {card: cardIndex, index: spot.index, offset: spot.offset};
  }

  /**
   * 一个 DOM 位置落在块表的第几格
   *
   * 🔴 走的是与 {@link readBody} <b>同一条路</b>：那边怎么数出块表，这边就怎么数出位置。
   * 各走各的话（比如这边认渲染时写下的序号），输入法、粘贴、撤销在块之间造出新的文字节点
   * 之后，同一个位置会算出两个答案——而插错一格在屏幕上看起来只是「拖歪了」。
   * @param body 卡的正文
   * @param container 位置所在的节点
   * @param offset 节点内的偏移
   * @return {{index: number, offset: number, onPill: boolean}} 落不到时为 null
   */
  function spotOf(body, container, offset) {
    let index = 0;
    let chars = 0;
    let pending = false;
    let answer = null;

    const walk = node => {
      for (const child of node.childNodes) {
        if (answer) return;
        if (child.nodeType === 3) {
          if (child === container) {
            answer = {index, offset: chars + offset, onPill: false};
            return;
          }
          chars += child.nodeValue.length;
          if (child.nodeValue.length) pending = true;
          continue;
        }
        if (child.nodeType !== 1) continue;
        if (child.tagName === 'BR') {
          chars += 1;
          pending = true;
          continue;
        }
        if (child.dataset && child.dataset.k) {
          if (pending) {
            index += 1;
            chars = 0;
            pending = false;
          }
          if (child === container) {
            answer = {index, offset: 0, onPill: true};
            return;
          }
          index += 1;
          continue;
        }
        if (child.classList && child.classList.contains('tpl-x')) continue;
        walk(child);
      }
    };

    // 光标停在正文本身（两个节点之间）时，容器是 body、偏移是第几个孩子。
    // 换成那个孩子来找，落点才是「它前面」；没有那个孩子就是「所有块之后」
    const target = container === body ? body.childNodes[offset] : container;
    if (!target) {
      walk(body);
      return {index: pending ? index + 1 : index, offset: 0, onPill: false};
    }

    container = target;
    walk(body);
    // 找不到时（容器是块里某个我们不认得的节点）当作末尾，不猜一个中间位置：
    // 猜错的那一次会把块插到句子中间，而屏幕上看起来只是「拖歪了」
    return answer || {index: pending ? index + 1 : index, offset: 0, onPill: false};
  }

  /**
   * 拖到卡里
   *
   * 🔴 落点压在一枚药丸上时，答案是<b>它前面或后面</b>，绝不是它的内部——那正是
   * 「块拖进块」那个缺陷：插进去之后屏幕上一枚药丸套着一枚，而拼回模板照样拼得出，
   * 发出去的消息一切正常。这一条由 dropIndex 判，本函数只负责问它。
   */
  function onDrop(event, body, cardIndex) {
    if (!drag || drag.kind === 'card' || !opts.editable) return;
    event.preventDefault();
    event.stopPropagation();

    const key = drag.key;
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

    if (from) {
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
   * @return {{index: number, offset: number, split: boolean}} 落不到时为 null
   */
  function spotFromPoint(body, event) {
    let node = event.target;
    if (node && node.nodeType === 3) node = node.parentNode;
    const pill = node && node.closest ? node.closest('[data-k]') : null;
    if (pill && body.contains(pill)) {
      const spot = spotOf(body, pill, 0);
      const box = pill.getBoundingClientRect();
      return {index: dropIndex(spot.index, event.clientX > box.left + box.width / 2),
        offset: 0, split: false};
    }

    const range = caretRangeFrom(event.clientX, event.clientY);
    if (!range || !body.contains(range.startContainer)) return null;
    const spot = spotOf(body, range.startContainer, range.startOffset);
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
   * 走的是 DOM 而不是那份 dataset 序号：输入法、粘贴、撤销都会在块之间造出新的文字节点，
   * 序号那时已经对不上了。药丸认 data-k，其余一律当文字——包括浏览器自己插进来的 <br>。
   */
  function readBody(body, cards, cardIndex) {
    const blocks = [];
    let buffer = '';
    const walk = node => {
      for (const child of node.childNodes) {
        if (child.nodeType === 3) {
          buffer += child.nodeValue;
          continue;
        }
        if (child.nodeType !== 1) continue;
        if (child.tagName === 'BR') {
          buffer += '\n';
          continue;
        }
        if (child.dataset && child.dataset.k) {
          if (buffer) {
            blocks.push(textBlock(buffer));
            buffer = '';
          }
          blocks.push(placeholderBlock(child.dataset.k, state.spec));
          // 块里套着块时把内层提到外层：丢掉的话，使用者写的东西会少一截而没有任何提示
          walk(child);
          continue;
        }
        if (child.classList && child.classList.contains('tpl-x')) continue;
        walk(child);
      }
    };
    walk(body);
    if (buffer) blocks.push(textBlock(buffer));

    return normalizeCards((cards || []).map((card, index) =>
      index === cardIndex ? {blocks} : card));
  }

  // ---- 右侧与文本形式 ----

  function paintSide() {
    const bubbles = previewBubbles(state.cards, state.atMode, opts.context);
    preview.innerHTML = '';
    for (const bubble of bubbles) preview.appendChild(bubbleNode(bubble));
    if (!bubbles.length) {
      preview.appendChild(el('p', 'hint')).textContent = '这样配的话，一条消息也不会发出去。';
    }

    const plan = atPlan(state.cards, state.atMode, opts.context);
    if (plan.note) preview.appendChild(el('p', 'xs dim')).textContent = plan.note;
    preview.appendChild(el('p', 'xs dim')).textContent =
      '花括号里的东西发出去时会换成真的内容，这里按原样显示——编一个样例出来的话，'
      + '看的人会以为那就是将来的样子。';

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
      } else {
        balloon.appendChild(el('span', 'qq-ph')).textContent = part.label;
      }
    }
    stack.appendChild(balloon);

    for (const image of bubble.images) {
      stack.appendChild(el('div', 'qq-img')).textContent = '［' + image + '］';
    }
    if (bubble.atDropped) {
      stack.appendChild(el('div', 'xs dim')).textContent =
        '机器人不是本群管理员，@全体成员 会被自动摘掉，正文照发。';
    }
    row.appendChild(stack);
    return row;
  }

  load(handlers[0]);
}

/**
 * 「报告长什么样」：左边一排版式开关，右边照着开关画出来的那张图
 *
 * 版式项清单取自处理器自报的那一张（{@code /api/handlers} 的 options），
 * 前端不写死一份：写死的那一份迟早与真正生效的对不上，而对不上时不会有任何报错，
 * 只会让人以为「配了没用」。
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
 *        items（版式项清单）与 render（按当前这套版式画一张的那一支）
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
  if (opts.caption) {
    const cap = el('p', 'hint');
    cap.textContent = opts.caption;
    view.appendChild(cap);
  }
  box.appendChild(view);
  host.appendChild(box);

  const items = opts.items || [];

  function valueOf(option) {
    const params = (opts.paramsOf ? opts.paramsOf() : null) || {};
    const stored = params[option.key];
    return stored === undefined || stored === null ? option.defaultValue : stored;
  }

  function change(option, value) {
    const params = Object.assign({}, (opts.paramsOf ? opts.paramsOf() : null) || {});
    params[option.key] = value;
    if (opts.onChange) opts.onChange(params);
    paint();
  }

  function paint() {
    controls.innerHTML = '';
    for (const option of items) {
      const row = el('div', 'rep-row');
      if (String(option.type).toUpperCase() === 'BOOLEAN') {
        const label = el('label', 'switch');
        label.innerHTML = '<input type="checkbox"' + (valueOf(option) ? ' checked' : '')
          + (opts.editable ? '' : ' disabled') + '>';
        label.querySelector('input').addEventListener('change',
          event => change(option, event.target.checked));
        row.appendChild(label);
      } else {
        const input = el('input', 'rep-n');
        input.type = 'number';
        input.value = String(valueOf(option));
        if (option.min !== null && option.min !== undefined) input.min = String(option.min);
        if (option.max !== null && option.max !== undefined) input.max = String(option.max);
        input.disabled = !opts.editable;
        input.addEventListener('change', event => change(option, Number(event.target.value)));
        row.appendChild(input);
      }

      const text = el('div', 'rep-txt');
      text.innerHTML = '<b>' + esc(option.label || option.key) + '</b>'
        + '<p>' + esc(option.description || '') + '</p>';
      row.appendChild(text);
      controls.appendChild(row);
    }
    if (!opts.editable && opts.lockedNote) {
      controls.appendChild(el('p', 'hint')).textContent = opts.lockedNote;
    }
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
}
