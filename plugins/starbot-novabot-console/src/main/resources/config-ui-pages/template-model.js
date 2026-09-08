/**
 * 模板编辑器的判法：一条消息拆成一串块，一串块拼回一条消息
 *
 * 存在配置里的模板是<b>一整串文字</b>（`{uname} 正在直播 {title}\n{url}{cover}`），
 * 而屏幕上它是一张张卡片、一枚枚可拖的药丸。两种形态之间的换算、以及「哪些块能放在哪」
 * 那几条规矩，全在这一份里——它不碰 DOM、不发请求，因此可以喂值直接跑
 * （src/test/resources/frontend/template-model-fixture.mjs）。
 *
 * 🔴 <b>这一份里没有任何一个平台名、也没有任何一条通知的名字，更没有一张写死的占位符表。</b>
 * 有哪些块、哪个块是整行的附件，全部取自 /api/handlers 里各处理器自报的
 * placeholders 与 attachments。写死一张表的话，第三方插件带来的占位符会被当成普通文字，
 * 而屏幕上那一串花括号看起来只是「使用者自己打的字」。
 *
 * <h2>{next} 去哪了</h2>
 * 存储格式一个字没改：卡与卡之间仍然是 {next}。但它<b>不是一个块</b>——它是
 * 「这里分条」这件事本身，而那件事在屏幕上由「另起一张卡」表达。于是编辑器里
 * 再没有 {next} 这个东西可拖、可删、可放错地方，而配置文件仍然照旧。
 *
 * <h2>@ 块为什么不在卡里</h2>
 * 「@ 谁」存的是推送参数 at_mode（见 AtMode），不是模板里的一段文字：程序在发送时
 * 把 @ 拼到正文最前面。因此 @ 块在编辑器里是<b>钉在第一张卡开头</b>的一枚药丸，
 * 不能拖走也不能删——三档是个闭集，里面没有「不 @」这一档
 * （「@订阅的人」那一档在无人订阅时本来就什么也不 @）。
 * 老模板里手写的 {at} / {at=all} 仍会原样显示成普通药丸，另附一句说明：
 * 程序见模板里已经有了就不再补，两处各写一套的话群里会被 @ 两遍。
 */

// 「配置里这一条是不是这一类通知」只有 push-model 那一份判法：处理器搬过包之后，
// 老配置里写的是旧名，这里自己按主名比一遍的话，那几个通道在本页整个消失而不报错
import {messageOf} from './push-model.js';

/**
 * 「@ 谁」的三档，与 AtMode 的取值一一对应
 *
 * 闭集，不是自由字符串：读的那一端（这里、推送处理器、命令的菜单联动）各有各的判断，
 * 取值一多一少只有真发生过那种配置的人才看得见。
 */
export const AT_MODES = [
  {key: 'subscribers', label: '@订阅的人', note: '只 @ 在本群发过「开播@我」这类命令的人。'},
  {key: 'all', label: '@全体成员', note: '机器人不是管理员、或额度用完时会被摘掉，通知照发。'},
  {key: 'all_or_subscribers', label: '@全体成员，不行就 @订阅的人',
    note: '被摘掉的那一次改 @ 订阅名单。'},
];

/**
 * 推送参数里「@ 谁」那一档的键名，与 AtMode.PARAM_KEY 同
 */
export const AT_MODE_KEY = 'at_mode';

/**
 * 旧的布尔键名，只读不写，与 AtMode.LEGACY_PARAM_KEY 同
 */
const AT_ALL_KEY = 'at_all';

/**
 * 分条占位符。它不是块，见文件头
 */
export const NEXT = '{next}';

/**
 * 模板里手写 @ 的两种写法。它们进不了调色板，只在老模板里被认出来
 */
export const AT_LITERALS = [ '{at}', '{at=all}' ];

/**
 * 占位符的取法：从 { 到<b>最近一个</b> }
 *
 * 非贪婪，与服务端 MessagePlaceholders 及各适配器的取法一致。贪婪的话
 * `{uname} 正在直播 {title}` 会被读成一个占位符，屏幕上就成了一枚巨大的药丸。
 */
const PLACEHOLDER = /\{.*?}/g;

/**
 * 把处理器自报的那几张表收成一份「这一类通知有哪些块」
 * @param handler /api/handlers 里的一项
 * @return {{keys: string[], attachments: string[], all: string[]}}
 *         调色板上摆哪几个、其中哪几个是整行的附件、认得出的占位符全集
 */
export function blockSpec(handler) {
  const all = ((handler || {}).placeholders) || [];
  const attachments = ((handler || {}).attachments) || [];
  // 调色板上不摆 {next}（分条改由「再加一张卡」表达）与两种手写 @（改由 @ 块表达）。
  // 摆上去的话，拖一个 {at} 进来会让这条通知在群里被 @ 两遍，而屏幕上两处看着都对
  const hidden = [NEXT].concat(AT_LITERALS);
  return {
    keys: all.filter(item => !hidden.includes(item)),
    attachments: attachments.filter(item => all.includes(item)),
    all,
  };
}

/**
 * 这个占位符是不是整行的附件
 * @param key 占位符
 * @param spec 见 blockSpec
 * @return {boolean} 是则为 true
 */
export function isAttachment(key, spec) {
  return ((spec || {}).attachments || []).includes(key);
}

/**
 * 药丸上写什么：占位符去掉花括号
 *
 * 不另建一张「占位符 → 中文名」的表：那张表只能由核心来写，而占位符是各处理器自己的东西，
 * 第三方插件带来的那些在表里一个也没有，届时屏幕上会是一枚没有名字的空药丸。
 * @param key 占位符
 * @return {string} 药丸上的字
 */
export function blockLabel(key) {
  return String(key || '').replace(/^\{/, '').replace(/}$/, '');
}

/**
 * 一整串模板 → 一张张卡
 *
 * 认不出的花括号<b>当普通文字</b>，不当块：当块的话，它会变成一枚药丸，
 * 而使用者以为自己配对了；当文字则原样留在那里，发出去就是那一串花括号——
 * 后者难看，但它是真的，看得见就改得掉。
 * @param text 模板原文，可为 null
 * @param spec 见 blockSpec
 * @return 每张卡一项，形如 {blocks: [...]}
 */
export function parseTemplate(text, spec) {
  const known = ((spec || {}).all) || [];
  return String(text == null ? '' : text).split(NEXT).map(segment => {
    const blocks = [];
    let last = 0;
    PLACEHOLDER.lastIndex = 0;
    let found = PLACEHOLDER.exec(segment);
    while (found) {
      if (known.includes(found[0]) && found[0] !== NEXT) {
        if (found.index > last) blocks.push(textBlock(segment.slice(last, found.index)));
        blocks.push(placeholderBlock(found[0], spec));
        last = found.index + found[0].length;
      }
      found = PLACEHOLDER.exec(segment);
    }
    if (last < segment.length) blocks.push(textBlock(segment.slice(last)));
    return {blocks};
  });
}

/**
 * 一张张卡 → 一整串模板
 *
 * 卡与卡之间落 {next}，卡里一个 {next} 也不会有：块表里根本没有那个东西。
 * @param cards 卡
 * @return {string} 模板原文
 */
export function toTemplateText(cards) {
  return (cards || [])
    .map(card => ((card || {}).blocks || [])
      .map(block => block.kind === 'text' ? block.text : block.key).join(''))
    .join(NEXT);
}

/** 造一个文字块 */
export function textBlock(text) {
  return {kind: 'text', text: String(text == null ? '' : text)};
}

/** 造一个占位符块 */
export function placeholderBlock(key, spec) {
  return {kind: 'ph', key, attachment: isAttachment(key, spec)};
}

/**
 * 把一份块表捋直：拍平嵌套、丢掉空文字、并掉挨着的文字
 *
 * 🔴 <b>块不可嵌套</b>。屏幕上那一层是可编辑区域，一次落点没算准就会把新块插进
 * 旧块内部，于是屏幕上出现一枚套着一枚的药丸，而拼回模板时它<b>照样拼得出</b>——
 * 发出去的消息一切正常，只有编辑器里那一处越陷越深。这一条在这里收口：
 * 凡是经过本函数的块表都是平的。
 * @param cards 卡
 * @return 捋直之后的卡
 */
export function normalizeCards(cards) {
  return (cards || []).map(card => {
    const out = [];
    flatten((card || {}).blocks, out);

    const merged = [];
    for (const block of out) {
      const last = merged[merged.length - 1];
      if (block.kind === 'text' && last && last.kind === 'text') {
        merged[merged.length - 1] = textBlock(last.text + block.text);
      } else if (block.kind !== 'text' || block.text !== '') {
        merged.push(block);
      }
    }
    return {blocks: merged};
  });
}

/**
 * 拍平：块里套着的块一律提到同一层
 * @param blocks 块表
 * @param out 收口
 */
function flatten(blocks, out) {
  for (const block of blocks || []) {
    if (Array.isArray(block)) {
      flatten(block, out);
      continue;
    }
    if (!block || !block.kind) continue;
    if (block.kind === 'text') {
      out.push(textBlock(block.text));
      continue;
    }
    out.push({kind: 'ph', key: block.key, attachment: !!block.attachment});
    // 历史脏数据：块里挂着块。提到外层而不是丢掉——丢掉的话，
    // 使用者会发现自己写的东西少了一截，而没有任何提示
    flatten(block.blocks, out);
  }
}

// ============ 卡的增删与排序 ============

/**
 * 再加一张卡：群里会多收到一条消息
 * @param cards 卡
 * @return 新的卡表
 */
export function addCard(cards) {
  return (cards || []).concat([{blocks: [textBlock('')]}]);
}

/**
 * 删掉一张卡
 *
 * 最后一张不许删：一条消息都没有的模板发不出任何东西，而它与「配好了」在界面上长得一样。
 * 整类通知不想推是在「推什么」里关掉，不是把模板删空。
 * @param cards 卡
 * @param index 第几张
 * @return {{cards: Array, refused: string}} 新的卡表，以及拒绝的理由（没拒绝时为空串）
 */
export function removeCard(cards, index) {
  const list = cards || [];
  if (list.length <= 1) {
    return {cards: list, refused: '至少留一条消息。整类通知不想推的话，到上面「推什么」里把它关掉。'};
  }
  if (index < 0 || index >= list.length) return {cards: list, refused: ''};
  return {cards: list.slice(0, index).concat(list.slice(index + 1)), refused: ''};
}

/**
 * 挪一张卡
 * @param cards 卡
 * @param from 从第几张
 * @param to 到第几张
 * @return 新的卡表
 */
export function moveCard(cards, from, to) {
  const list = (cards || []).slice();
  if (from < 0 || from >= list.length || to < 0 || to >= list.length || from === to) return list;
  const moved = list.splice(from, 1)[0];
  list.splice(to, 0, moved);
  return list;
}

// ============ 块的增删与排序 ============

/**
 * 落点：拖到某个块身上时，落在它前面还是后面
 *
 * 🔴 这一条正是「块拖进块」那个缺陷的收口：落点压在一枚药丸上时，答案永远是
 * <b>相邻的位置</b>，而不是那枚药丸的内部。左半边落前面、右半边落后面——
 * 与文字编辑里插入光标的直觉一致。
 * @param index 压住的是第几块
 * @param after 压在它的后半边则为 true
 * @return {number} 插入位置
 */
export function dropIndex(index, after) {
  return Math.max(0, index) + (after ? 1 : 0);
}

/**
 * 往某张卡的某个位置插一个块
 * @param cards 卡
 * @param at {{card: number, index: number}} 落点
 * @param block 块
 * @return 新的卡表，已捋直
 */
export function insertBlock(cards, at, block) {
  const list = (cards || []).map(card => ({blocks: ((card || {}).blocks || []).slice()}));
  const card = list[(at || {}).card];
  if (!card || !block) return normalizeCards(list);

  const index = Math.max(0, Math.min(card.blocks.length, (at || {}).index));
  card.blocks.splice(index, 0, block);
  return normalizeCards(list);
}

/**
 * 在一段文字中间劈一刀，好让块插进去
 *
 * 拖到「正在直播」四个字中间时，落点就该在那四个字中间，而不是整段文字的前后——
 * 只能整段前后落的话，写好的一句话再想往里塞一个占位符就得先把句子拆开重打。
 * @param cards 卡
 * @param at {{card: number, index: number, offset: number}} 第几张卡的第几块，从第几个字劈
 * @return {{cards: Array, index: number}} 劈完的卡，以及该往哪一格插
 */
export function splitText(cards, at) {
  const list = (cards || []).map(card => ({blocks: ((card || {}).blocks || []).slice()}));
  const card = list[(at || {}).card];
  const index = (at || {}).index;
  const block = card ? card.blocks[index] : null;
  if (!block || block.kind !== 'text') {
    return {cards: list, index: Math.max(0, index)};
  }

  const offset = Math.max(0, Math.min(block.text.length, (at || {}).offset || 0));
  card.blocks.splice(index, 1, textBlock(block.text.slice(0, offset)), textBlock(block.text.slice(offset)));
  return {cards: list, index: index + 1};
}

/**
 * 删掉一个块
 * @param cards 卡
 * @param at {{card: number, index: number}} 位置
 * @return 新的卡表，已捋直
 */
export function removeBlock(cards, at) {
  const list = (cards || []).map(card => ({blocks: ((card || {}).blocks || []).slice()}));
  const card = list[(at || {}).card];
  if (!card) return normalizeCards(list);
  card.blocks.splice((at || {}).index, 1);
  return normalizeCards(list);
}

/**
 * 把一个块从一处挪到另一处
 *
 * 同一张卡内往后挪时，落点要先减掉被拿走的那一格——不减的话，块会比预期多走一位，
 * 而「拖到最后一位」这件事永远做不成。
 * @param cards 卡
 * @param from {{card: number, index: number}} 从哪
 * @param to {{card: number, index: number}} 到哪
 * @return 新的卡表，已捋直
 */
export function moveBlock(cards, from, to) {
  const list = (cards || []).map(card => ({blocks: ((card || {}).blocks || []).slice()}));
  const source = list[(from || {}).card];
  const target = list[(to || {}).card];
  if (!source || !target) return normalizeCards(list);

  const block = source.blocks[from.index];
  if (!block) return normalizeCards(list);
  source.blocks.splice(from.index, 1);

  let index = to.index;
  if (from.card === to.card && to.index > from.index) index -= 1;
  target.blocks.splice(Math.max(0, Math.min(target.blocks.length, index)), 0, block);
  return normalizeCards(list);
}

/**
 * 这个占位符在这份模板里用过没有
 * @param cards 卡
 * @param key 占位符
 * @return {boolean} 用过则为 true
 */
export function usesBlock(cards, key) {
  return (cards || []).some(card => ((card || {}).blocks || [])
    .some(block => block.kind === 'ph' && block.key === key));
}

/**
 * 这张卡上有没有附件块
 * @param cards 卡
 * @param index 第几张
 * @return {boolean} 有则为 true
 */
export function cardHasAttachment(cards, index) {
  const card = (cards || [])[index];
  return ((card || {}).blocks || []).some(block => block.kind === 'ph' && block.attachment);
}

/**
 * 这个块此刻能不能放进这张卡
 *
 * 两条规矩，都是发送那一侧的事实，不是界面的口味：
 * 一份模板里同一个附件只有一份内容（同一张封面放两遍是同一张图发两次），
 * 一张卡里放两个附件在 QQ 那头会拆成两条，而卡与消息在这一页上是一对一的。
 * @param cards 卡
 * @param cardIndex 落到第几张卡
 * @param key 占位符
 * @param spec 见 blockSpec
 * @param movingFrom 拖的是已有的块时，它原来在第几张卡；新块传 -1
 * @return {string} 拦下来的理由，放得下时为空串
 */
export function blockRefusal(cards, cardIndex, key, spec, movingFrom) {
  if (!isAttachment(key, spec)) return '';
  if (movingFrom === undefined || movingFrom === null) movingFrom = -1;

  if (movingFrom < 0 && usesBlock(cards, key)) {
    return '这份模板里已经有一个「' + blockLabel(key) + '」了。同一份内容发两遍，群里就是两张一样的图。';
  }
  if (cardHasAttachment(cards, cardIndex) && movingFrom !== cardIndex) {
    return '一张卡最多放一个附件块。想再放一个，就再加一张卡。';
  }
  return '';
}

// ============ @ 谁 ============

/**
 * 这条消息此刻 @ 谁
 *
 * 判定顺序与服务端 AtMode.of 一模一样：<b>选过就按选的来，没选过才回头看旧键</b>。
 * 反过来先看旧键的话，升级之后使用者在新界面上选的那一档会被一个旧布尔顶掉。
 * @param params 推送参数，可为 null
 * @return {string} 三档之一
 */
export function atModeOf(params) {
  const raw = (params || {})[AT_MODE_KEY];
  const known = AT_MODES.find(item => item.key === String(raw || '').trim().toLowerCase());
  if (known) return known.key;
  return (params || {})[AT_ALL_KEY] ? 'all' : 'subscribers';
}

/**
 * 三档里这一档叫什么
 * @param mode 档
 * @return 该档，认不出时回第一档
 */
export function atModeInfo(mode) {
  return AT_MODES.find(item => item.key === mode) || AT_MODES[0];
}

/** 同义 core.js 的 term：有键用词，无键用中性兜底。模型不读全局。 */
function word(terms, key, fallback) {
  return (terms && terms[key]) || fallback;
}

/** 同义 core.js 的 phrase：词在则套进 withTerm，词缺则整句退成中性 without。 */
function say(terms, key, withTerm, without) {
  const v = terms && terms[key];
  return v ? withTerm(v) : without;
}

/**
 * 发出去的时候 @ 会落在哪
 *
 * 🔴 <b>这一段照着服务端 PushHandlerSupport.withAtBlock 的判法写</b>：模板里自己写了
 * @ 占位符时，程序<b>不再补</b>一份。两处各判各的话，预览上少一个 @ 或多一个 @，
 * 而这两种错都要等真发到群里才看得见。
 * @param cards 卡
 * @param mode @ 档
 * @param context {{isGroup: boolean, admin: boolean|null, terms?: Object}} 这个通道是群聊吗、机器人是不是管理员
 * @param terms /api/vocab 七键；缺则看 context.terms，再缺则中性兜底
 * @return {{prepend: string, inline: boolean, dropped: boolean, fallback: boolean, note: string}}
 *         正文前面补什么（none/all/subscribers）、模板里是否自己写了 @、
 *         这一次会不会被摘掉、摘掉后是否改 @ 订阅名单、以及一句说明
 */
export function atPlan(cards, mode, context, terms) {
  const text = toTemplateText(cards);
  const isGroup = !!(context || {}).isGroup;
  const words = terms || (context || {}).terms;
  const admin = (context || {}).admin;
  const hasAt = text.includes(AT_LITERALS[0]);
  const hasAtAll = text.includes(AT_LITERALS[1]);
  const inline = hasAt || hasAtAll;

  if (mode === 'subscribers') {
    return {
      prepend: hasAt ? 'none' : 'subscribers', inline, dropped: false, fallback: false,
      note: hasAt
        ? '模板里自己写了 {at}，@ 就落在那个位置，程序不再往正文前面补一份。'
        : '@ 的是本会话里订阅过这类提醒的人；没有人订阅时这一段不出现。',
    };
  }

  if (!isGroup) {
    return {
      prepend: 'none', inline, dropped: false, fallback: false,
      note: '私聊没有 @全体成员 这回事，这一档在这个通道上不起作用。',
    };
  }

  // admin 为 null＝名单取不到（OneBot 掉线时就是这样）。此时不画划掉线：
  // 画了等于断言「会被摘掉」，而那时我们其实什么也不知道
  const dropped = admin === false;
  const fallback = mode === 'all_or_subscribers' && !hasAt;
  return {
    prepend: hasAtAll ? 'none' : 'all', inline, dropped, fallback,
    note: (hasAtAll ? '模板里自己写了 {at=all}，程序不再往正文前面补一份。' : '')
      + (dropped
        ? '机器人不是本群管理员，@全体成员 会被自动摘掉，正文照发。'
          + (fallback ? '这一次改 @ 订阅名单。' : '')
        : say(words, 'bot.platform',
            v => '@全体成员 不绕过 ' + v + ' 权限，额度用尽或不是管理员时会被摘掉，正文照发。',
            '@全体成员 不绕过机器人权限，额度用尽或不是管理员时会被摘掉，正文照发。')
          + (fallback ? '被摘掉的那一次改 @ 订阅名单。' : '')),
  };
}

// ============ 预览 ============

/**
 * 群里会看到几条、每条长什么样
 *
 * 占位符原样留成一枚灰药丸，不编一个样例值：{uname} 到底是谁要等推送那一刻才知道，
 * 编一个「星野柚子」出来，使用者会以为那就是将来的样子。
 * @param cards 卡
 * @param mode @ 档
 * @param context 见 atPlan
 * @return 每条消息一项：{at, atDropped, parts, images}
 */
export function previewBubbles(cards, mode, context, terms) {
  const plan = atPlan(cards, mode, context, terms);
  const bubbles = [];

  (cards || []).forEach((card, index) => {
    const parts = [];
    const images = [];
    for (const block of ((card || {}).blocks || [])) {
      if (block.kind === 'text') {
        if (block.text !== '') parts.push({kind: 'text', text: block.text});
      } else if (block.attachment) {
        images.push(blockLabel(block.key));
      } else {
        parts.push({kind: 'ph', label: blockLabel(block.key), key: block.key});
      }
    }

    // @ 只拼在第一条上，与发送那一侧一致：withAtBlock 补的是整串内容的开头，
    // 而分条是之后才按 {next} 切的
    const at = index === 0 && plan.prepend !== 'none' ? plan.prepend : '';
    // 整条空着就不画：一条什么都没有的消息发不出去，画出来会让人以为群里会收到一条空白
    if (!at && !parts.length && !images.length) return;
    bubbles.push({at, atDropped: !!at && at === 'all' && plan.dropped, parts, images});
  });

  return bubbles;
}

// ============ 默认与自定义 ============

/**
 * 这一类通知里，哪几个键归模板编辑器管
 *
 * 「@ 谁」刻意不在处理器的默认参数里（见 AtMode），因此得单列一条，
 * 否则「恢复默认」按下去会把它留在原地，而屏幕上写着已经恢复了。
 * @param handler /api/handlers 里的一项
 * @return {string[]} 键
 */
export function editableKeys(handler) {
  const keys = Object.keys(((handler || {}).defaultParams) || {});
  for (const option of ((handler || {}).options) || []) {
    if (!keys.includes(option.key)) keys.push(option.key);
  }
  if (!keys.includes(AT_MODE_KEY)) keys.push(AT_MODE_KEY);
  return keys;
}

/**
 * 这个通道这一类通知，与默认一字不差吗
 *
 * 「默认」是<b>与此刻的默认值一字不差</b>，不是「使用者没动过」——与 push-model 的
 * templateState 同一条口径。反过来把一份真改过的模板说成「默认」的话，
 * 使用者按「恢复默认」时会毫无预兆地丢掉自己写的东西。
 * @param params 这个通道存着的参数
 * @param handler 处理器
 * @return {boolean} 一样则为 true
 */
export function isDefault(params, handler) {
  const now = params || {};
  const base = ((handler || {}).defaultParams) || {};
  return !Object.keys(now).some(key => String(now[key]) !== String(base[key]));
}

/**
 * 恢复默认：把编辑器管的那几个键从参数里删掉
 *
 * 删掉而不是写回默认值：删掉之后这个通道<b>跟着默认走</b>，将来默认模板再改它也一起变；
 * 写回一份一模一样的值的话，此刻看起来一样，而下一次改默认时这个通道会独自停在原地。
 * @param params 这个通道存着的参数，可为 null
 * @param handler 处理器
 * @return 新的参数
 */
export function restoreDefaults(params, handler) {
  const out = Object.assign({}, params || {});
  for (const key of editableKeys(handler)) delete out[key];
  // 旧的布尔键一并删掉：留着它的话，删了 at_mode 之后判定会回头看它，
  // 于是「恢复默认」按下去，@ 那一档跳回了几年前配的那一档
  delete out[AT_ALL_KEY];
  return out;
}

/**
 * 编辑器改完之后，该往配置里写什么
 *
 * 🔴 <b>与参照的那一份一模一样的键一律删掉，不写回去。</b>写回一份一字不差的副本
 * 此刻看起来完全一样，而下一次改默认模板时这个通道会<b>独自停在原地</b>——
 * 屏幕上它仍显示「默认」，群里收到的却是旧的那一份。删掉之后它才真的跟着默认走。
 * <p>
 * 这一条对两处都成立：通道对着「这台机器此刻的默认」比，默认模板那一页对着
 * 「出厂默认」比——后者比出来的差集正是要存下来的那一份覆盖。
 * @param params 原有的参数，可为 null
 * @param base 参照的那一份默认参数
 * @param cards 卡
 * @param atMode @ 档
 * @return 该写进配置的参数
 */
export function applyEdit(params, base, cards, atMode) {
  const out = Object.assign({}, params || {});
  const text = toTemplateText(cards);
  if (text === ((base || {}).message)) delete out.message;
  else out.message = text;

  if (atMode === atModeOf(base)) delete out[AT_MODE_KEY];
  else out[AT_MODE_KEY] = atMode;

  // 旧的布尔键只读不写。留着它的话，它会在「这一档回到默认」之后重新顶上来，
  // 表现是使用者选了「@订阅的人」，群里照样 @全体成员
  delete out[AT_ALL_KEY];
  return out;
}

/**
 * 谁在用默认、谁自定义了
 *
 * 默认模板那一页要在改之前说清楚这一改会影响到谁——「改一次，所有用默认的通道一起变」
 * 是这一页的立身之本，而它同时意味着<b>一次误改会同时落到一批群上</b>。
 * @param users 推送配置里的主播
 * @param handler 处理器
 * @param terms /api/vocab；本函数不读词，接线处一并传入以免漏传
 * @return {{following: Array, custom: Array}} 跟着默认走的通道与已经分叉的通道
 *         每项形如 {uid, platform, num, type}
 */
export function templateAdoption(users, handler, terms) {
  const following = [];
  const custom = [];

  for (const user of users || []) {
    for (const target of ((user || {}).targets) || []) {
      // 认这一条用的是 messageOf，与推送页的开关、模板、版式同一把：这一页自己按主名比的话，
      // 老配置里写着旧名的那些通道在这张表上一个也不出现，而「改一次影响到谁」正是这一页的立身之本
      const message = messageOf(target, handler);
      if (!message || message.enabled === false) continue;

      const row = {uid: user.uid, platform: target.platform, num: target.num, type: Number(target.type)};
      (isDefault(message.params, handler) ? following : custom).push(row);
    }
  }
  return {following, custom};
}
