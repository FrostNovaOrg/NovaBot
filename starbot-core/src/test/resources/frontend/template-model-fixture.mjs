/**
 * 模板编辑器判法的夹具
 *
 * 量的是 config-ui/template-model.js：一整串模板与一张张卡之间的换算、块的增删与排序、
 * 「块不可嵌套」、@ 那一档三选一、以及「谁在用默认、谁自定义了」。
 *
 * 这几件事在真机上一格格点出来的代价极高：@ 那三档要把机器人的管理员身份改三次，
 * 「块拖进块」要在浏览器里拖到某一枚药丸的正中央，「改默认影响到谁」要先配出几个通道。
 * 判法因此被切成纯函数（不碰 DOM、不发请求），本文件喂值逐格对答案。
 *
 * 由 TemplateModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本。
 */

import {
  AT_MODES, addCard, applyEdit, atModeOf, atPlan, blockLabel, blockRefusal, blockSpec, cardHasAttachment,
  dropIndex, editableKeys, insertBlock, isAttachment, isDefault, moveBlock, moveCard,
  normalizeCards, parseTemplate, placeholderBlock, previewBubbles, removeBlock, removeCard,
  restoreDefaults, splitText, templateAdoption, textBlock, toTemplateText, usesBlock,
} from '../../../main/resources/config-ui/template-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 一份与真机同形的处理器自报：五个占位符，其中封面是整行的附件 */
const HANDLER = {
  className: 'x.LiveOn', displayName: '开播通知',
  placeholders: ['{uname}', '{title}', '{cover}', '{url}', '{at}', '{next}', '{at=all}'],
  attachments: ['{cover}'],
  defaultParams: {message: '{uname} 正在直播 {title}\n{url}{cover}', reconnect_message: '不重复通知'},
  options: [{key: 'danmu_top', label: '弹幕榜条数', type: 'INTEGER', defaultValue: 5}],
};

const SPEC = blockSpec(HANDLER);

/** 把一份卡表写成一行好比对的字：文字原样，块写成〔名字〕 */
function sketch(cards) {
  return cards.map(card => card.blocks
    .map(block => block.kind === 'text' ? block.text : '〔' + blockLabel(block.key) + '〕').join('')).join(' ‖ ');
}

// ---------- 一、调色板：有哪些块 ----------
// 块的来源只有处理器自报的那一张表。写死一张的话，第三方插件的占位符会被当成普通文字
eq(SPEC.keys, ['{uname}', '{title}', '{cover}', '{url}'], '调色板上是处理器自报的那几个');
eq(SPEC.keys.includes('{next}'), false, '{next} 不是块——分条由「再加一张卡」表达');
eq(SPEC.keys.includes('{at}'), false, '手写的 {at} 不进调色板，@ 改由 @ 块表达');
eq(SPEC.keys.includes('{at=all}'), false, '手写的 {at=all} 也不进调色板');
eq(SPEC.attachments, ['{cover}'], '哪个是附件由处理器自报');
eq(isAttachment('{cover}', SPEC), true, '封面是整行的附件块');
eq(isAttachment('{title}', SPEC), false, '标题是行内的文字块');
eq(blockLabel('{uname}'), 'uname', '药丸上写的是去掉花括号的占位符名');
// 处理器没声明附件时一个附件也没有：不按名字去猜，第三方插件的同名占位符不会跟着变成图片
eq(blockSpec({placeholders: ['{cover}'], attachments: []}).attachments, [], '没声明就没有附件块');
eq(blockSpec({}).keys, [], '什么都没自报时调色板是空的');

// ---------- 二、一整串 ⇄ 一张张卡 ----------
const parsed = parseTemplate(HANDLER.defaultParams.message, SPEC);
eq(parsed.length, 1, '开播默认模板是一张卡');
eq(sketch(parsed), '〔uname〕 正在直播 〔title〕\n〔url〕〔cover〕', '默认模板拆成块的样子');
eq(toTemplateText(parsed), HANDLER.defaultParams.message, '拼回去与原文一字不差');

const twoCards = parseTemplate('{uname} 开播了{next}{cover}', SPEC);
eq(twoCards.length, 2, '{next} 把模板切成两张卡');
eq(sketch(twoCards), '〔uname〕 开播了 ‖ 〔cover〕', '两张卡各是什么');
eq(toTemplateText(twoCards), '{uname} 开播了{next}{cover}', '两张卡拼回去还是 {next} 分条');
eq(usesBlock(twoCards, '{next}'), false, '块表里没有 {next} 这个东西');

// 认不出的花括号当普通文字：当块的话它会变成一枚药丸，而使用者以为自己配对了
const typo = parseTemplate('{uname} 直播 {tittle}', SPEC);
eq(sketch(typo), '〔uname〕 直播 {tittle}', '认不出的占位符原样留成文字');
eq(toTemplateText(typo), '{uname} 直播 {tittle}', '原样留着，拼回去一个字不差');

// 空模板与只有花括号的模板都不许把这一层打断
eq(toTemplateText(parseTemplate('', SPEC)), '', '空模板拼回去还是空');
eq(toTemplateText(parseTemplate(null, SPEC)), '', '拿不到模板时当空的办');
eq(sketch(parseTemplate('{uname}{uname}', SPEC)), '〔uname〕〔uname〕', '挨着的两个占位符各是一块');

// ---------- 三、块不可嵌套 ----------
// 这一格奔着那个缺陷去：屏幕上一枚药丸套着一枚，而拼回模板时它照样拼得出，
// 发出去的消息一切正常——只有编辑器里那一处越陷越深
const nested = normalizeCards([{blocks: [
  {kind: 'ph', key: '{uname}', attachment: false, blocks: [placeholderBlock('{title}', SPEC)]},
  textBlock(''), textBlock('甲'), textBlock('乙'),
]}]);
eq(sketch(nested), '〔uname〕〔title〕甲乙', '套在里面的块被提到同一层，一个字也没丢');
eq(nested[0].blocks.length, 3, '捋直之后空文字被丢掉、挨着的文字并成一块');
eq(nested[0].blocks.every(block => !block.blocks), true, '捋直之后没有一个块还挂着块');

eq(dropIndex(2, false), 2, '压在第 3 块的左半边就落它前面');
eq(dropIndex(2, true), 3, '压在第 3 块的右半边就落它后面');
eq(dropIndex(-1, false), 0, '落点算出负数时按最前面办');

const inserted = insertBlock(parsed, {card: 0, index: dropIndex(0, true)}, placeholderBlock('{url}', SPEC));
eq(sketch(inserted), '〔uname〕〔url〕 正在直播 〔title〕\n〔url〕〔cover〕', '插在第 1 块后面');
eq(sketch(insertBlock(parsed, {card: 0, index: 999}, textBlock('尾'))),
  '〔uname〕 正在直播 〔title〕\n〔url〕〔cover〕尾', '落点超出末尾时贴到最后');
eq(sketch(insertBlock(parsed, {card: 9, index: 0}, textBlock('X'))),
  sketch(parsed), '落到一张不存在的卡上时什么也不改');
// 插进来的东西自己套着块时，插完仍然是平的
eq(insertBlock(parsed, {card: 0, index: 0},
  {kind: 'ph', key: '{url}', blocks: [placeholderBlock('{title}', SPEC)]})[0].blocks.every(b => !b.blocks),
  true, '插进来的块自带嵌套时也会被捋直');

// 拖到一句话中间时，落点就在那句话中间——只能整段前后落的话，
// 想往写好的句子里塞一个占位符就得先把句子拆开重打
const cut = splitText(parsed, {card: 0, index: 1, offset: 3});
eq(cut.cards[0].blocks.slice(1, 3).map(block => block.text), [' 正在', '直播 '],
  '在第 3 个字处把那段文字劈成两块');
eq(cut.index, 2, '劈完之后插在两半中间');
eq(sketch(insertBlock(cut.cards, {card: 0, index: cut.index}, placeholderBlock('{url}', SPEC))),
  '〔uname〕 正在〔url〕直播 〔title〕\n〔url〕〔cover〕', '块落在句子中间');
eq(splitText(parsed, {card: 0, index: 0, offset: 2}).index, 0, '压在一枚药丸上时不劈，落在它那一格');
eq(sketch(splitText(parsed, {card: 0, index: 1, offset: 999}).cards),
  sketch(parsed), '偏移超出这段文字时劈不出新的东西来');

// ---------- 四、卡的增删与排序 ----------
eq(addCard(twoCards).length, 3, '再加一条消息');
eq(removeCard(twoCards, 0).cards.length, 1, '删掉一张卡');
eq(sketch(removeCard(twoCards, 0).cards), '〔cover〕', '删掉的是点名的那一张');
eq(removeCard(parsed, 0).refused !== '', true, '最后一张卡不许删');
eq(removeCard(parsed, 0).cards.length, 1, '拒绝的那一次一张也没少');
eq(sketch(moveCard(twoCards, 0, 1)), '〔cover〕 ‖ 〔uname〕 开播了', '把第 1 张挪到第 2 张');
eq(sketch(moveCard(twoCards, 0, 9)), sketch(twoCards), '挪到不存在的位置时什么也不改');

// ---------- 五、块的排序 ----------
const line = parseTemplate('甲{uname}乙{url}', SPEC);
eq(sketch(moveBlock(line, {card: 0, index: 1}, {card: 0, index: 3})), '甲乙〔uname〕〔url〕',
  '同一张卡内往后挪：落点先减掉被拿走的那一格，否则永远挪不到最后一位');
eq(sketch(moveBlock(line, {card: 0, index: 3}, {card: 0, index: 0})), '〔url〕甲〔uname〕乙',
  '同一张卡内往前挪');
eq(sketch(moveBlock(twoCards, {card: 1, index: 0}, {card: 0, index: 0})), '〔cover〕〔uname〕 开播了 ‖ ',
  '跨卡挪：走了的那一张会空下来，卡本身还在');
eq(sketch(removeBlock(line, {card: 0, index: 1})), '甲乙〔url〕', '删掉一个块，前后的文字并起来');

// ---------- 六、一张卡最多一个附件 ----------
eq(cardHasAttachment(parsed, 0), true, '默认那张卡上有封面');
eq(blockRefusal(parsed, 0, '{title}', SPEC, -1), '', '文字块随便放');
eq(blockRefusal(parsed, 0, '{cover}', SPEC, -1) !== '', true, '同一份模板里第二个封面拦下来');
eq(blockRefusal(twoCards, 0, '{cover}', SPEC, 1), '', '把已有的封面从第 2 张拖到第 1 张，放得下');
const twoAttachments = insertBlock(twoCards, {card: 0, index: 0}, placeholderBlock('{cover}', SPEC));
eq(blockRefusal(twoAttachments, 0, '{cover}', SPEC, 1) !== '', true, '一张卡上两个附件拦下来');

// ---------- 七、@ 谁 ----------
eq(AT_MODES.map(item => item.key), ['subscribers', 'all', 'all_or_subscribers'], '三档是个闭集');
eq(atModeOf(null), 'subscribers', '没配过时是 @订阅的人');
eq(atModeOf({at_mode: 'all'}), 'all', '选过就按选的来');
eq(atModeOf({at_mode: 'ALL'}), 'all', '取值不分大小写');
eq(atModeOf({at_mode: 'all', at_all: false}), 'all', '选过之后不再看旧键');
eq(atModeOf({at_all: true}), 'all', '没选过才回头看旧的布尔键');
eq(atModeOf({at_mode: 'everyone'}), 'subscribers', '认不出的取值当没配过');
eq(atModeOf({at_mode: 'everyone', at_all: true}), 'all', '认不出时仍回头看旧键');

const group = {isGroup: true, admin: true};
const notAdmin = {isGroup: true, admin: false};
const unknownAdmin = {isGroup: true, admin: null};
const dm = {isGroup: false, admin: null};

eq(atPlan(parsed, 'subscribers', group).prepend, 'subscribers', '@订阅的人：补在正文前面');
eq(atPlan(parsed, 'all', group).prepend, 'all', '@全体成员：补在正文前面');
eq(atPlan(parsed, 'all', dm).prepend, 'none', '私聊里没有 @全体成员 这回事');
eq(atPlan(parsed, 'all', notAdmin).dropped, true, '不是管理员时这一次会被摘掉');
eq(atPlan(parsed, 'all', unknownAdmin).dropped, false,
  '名单取不到时不画划掉线——画了等于断言会被摘掉，而那时其实什么也不知道');
eq(atPlan(parsed, 'all_or_subscribers', group).fallback, true, '这一档被摘掉时改 @ 订阅名单');
eq(atPlan(parsed, 'all', group).fallback, false, '@全体成员 那一档没有退路');
// 与服务端 withAtBlock 同一条：模板里自己写了就不再补，否则群里被 @ 两遍
eq(atPlan(parseTemplate('{at}{uname} 开播', SPEC), 'subscribers', group).prepend, 'none',
  '模板里写了 {at} 时不再补一份');
eq(atPlan(parseTemplate('{at}{uname}', SPEC), 'subscribers', group).inline, true, '认出模板里手写的 @');
eq(atPlan(parseTemplate('{at=all}{uname}', SPEC), 'all', group).prepend, 'none',
  '模板里写了 {at=all} 时不再补一份');
eq(atPlan(parseTemplate('{at=all}{uname}', SPEC), 'subscribers', group).prepend, 'subscribers',
  '手写的是 {at=all} 而这一档要 @ 订阅的人：两个 @ 都在，与发送那一侧一致');
eq(atPlan(parseTemplate('{at}{uname}', SPEC), 'all_or_subscribers', group).fallback, false,
  '订阅的人已经在正文里被 @ 过了，退路那一份不再顶上来');

// ---------- 八、群里会看到几条 ----------
const bubbles = previewBubbles(parsed, 'all', notAdmin);
eq(bubbles.length, 1, '默认模板在群里是一条消息');
eq(bubbles[0].at, 'all', '第一条前面挂着 @全体成员');
eq(bubbles[0].atDropped, true, '机器人不是管理员时它划掉');
eq(bubbles[0].images, ['cover'], '附件画成图片占位，不混进正文');
eq(bubbles[0].parts.filter(part => part.kind === 'ph').map(part => part.label),
  ['uname', 'title', 'url'], '占位符原样留成药丸，不编一个样例值');

const twoBubbles = previewBubbles(twoCards, 'all', group);
eq(twoBubbles.length, 2, '两张卡就是两条消息');
eq([twoBubbles[0].at, twoBubbles[1].at], ['all', ''],
  '@ 只挂在第一条上——发送那一侧补的是整串内容的开头，分条是之后才切的');
eq(previewBubbles([{blocks: [textBlock('甲')]}, {blocks: [textBlock('')]}], 'subscribers', dm).length, 1,
  '空着的那一张卡不画：画出来会让人以为群里会收到一条空白');
eq(previewBubbles([{blocks: [textBlock('')]}], 'all', group).length, 1,
  '正文空着但要 @全体成员 时，那一条还是会发出去——@ 本身就是内容');

// ---------- 九、默认与自定义 ----------
eq(editableKeys(HANDLER), ['message', 'reconnect_message', 'danmu_top', 'at_mode'],
  '编辑器管的是默认参数、自报的可配置项，加上「@ 谁」——后者不在默认参数里，得单列');
eq(isDefault(null, HANDLER), true, '一个参数也没存的通道用的是默认');
eq(isDefault({}, HANDLER), true, '空参数也是默认');
eq(isDefault({message: HANDLER.defaultParams.message}, HANDLER), true, '与默认一字不差就是默认');
eq(isDefault({message: HANDLER.defaultParams.message + ' '}, HANDLER), false, '差一个空格就是自定义');
eq(isDefault({at_mode: 'all'}, HANDLER), false, '选了 @ 谁那一档就算分叉了');

eq(restoreDefaults({message: '改过的', at_mode: 'all', enabled: true}, HANDLER),
  {enabled: true}, '恢复默认删掉编辑器管的那几个键，别人的键一个不动');
eq(restoreDefaults({at_all: true}, HANDLER), {},
  '旧的布尔键一并删掉，否则「恢复默认」之后 @ 那一档会跳回几年前配的那一档');
eq(restoreDefaults(null, HANDLER), {}, '拿不到参数时回一份空的');

// 与默认一字不差的键一律不写回去：写回一份一模一样的副本，此刻看着一样，
// 而下一次改默认时这个通道会独自停在原地，屏幕上却仍显示「默认」
eq(applyEdit({}, HANDLER.defaultParams, parsed, 'subscribers'), {},
  '一个字没改时什么也不写，这个通道继续跟着默认走');
eq(applyEdit({}, HANDLER.defaultParams, twoCards, 'subscribers'),
  {message: '{uname} 开播了{next}{cover}'}, '改过模板才写模板');
eq(applyEdit({}, HANDLER.defaultParams, parsed, 'all'), {at_mode: 'all'},
  '只改了 @ 那一档就只写那一档');
eq(applyEdit({message: '旧的', at_mode: 'all'}, HANDLER.defaultParams, parsed, 'subscribers'), {},
  '两样都改回默认时，两个键都删掉');
eq(applyEdit({at_all: true}, HANDLER.defaultParams, parsed, 'all'), {at_mode: 'all'},
  '旧的布尔键只读不写：留着它会在这一档回到默认之后重新顶上来');
eq(applyEdit({enabled: false}, HANDLER.defaultParams, parsed, 'subscribers'), {enabled: false},
  '不归编辑器管的键一个不动');
// 默认模板那一页拿出厂默认当参照：比出来的差集正是要存下来的那一份覆盖
eq(applyEdit({}, {message: '出厂的'}, parsed, 'subscribers'),
  {message: HANDLER.defaultParams.message}, '对着出厂默认比，改过的那一份就是覆盖');

const USERS = [{
  uid: 3493, platform: 'bilibili', targets: [
    {platform: 'qq', type: 1, num: 1, messages: [{handler: 'x.LiveOn'}]},
    {platform: 'qq', type: 1, num: 2, messages: [{handler: 'x.LiveOn', params: {message: '我自己写的'}}]},
    {platform: 'qq', type: 1, num: 3, messages: [{handler: 'x.LiveOn', enabled: false, params: {message: '关着的'}}]},
    {platform: 'qq', type: 0, num: 4, messages: [{handler: 'x.Other'}]},
  ],
}];
const adoption = templateAdoption(USERS, HANDLER);
eq(adoption.following.map(row => row.num), [1], '改默认会影响到的是这几个通道');
eq(adoption.custom.map(row => row.num), [2], '已经分叉的这几个不受影响');
eq(adoption.following.concat(adoption.custom).map(row => row.num).includes(3), false,
  '这类通知关着的通道不算——改默认对它没有任何现象');
eq(adoption.following.concat(adoption.custom).map(row => row.num).includes(4), false,
  '别的通知不算进来');
eq(templateAdoption(null, HANDLER), {following: [], custom: []}, '一个主播也没配时两边都是空的');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
