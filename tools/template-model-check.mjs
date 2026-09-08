/**
 * 模板编辑器视图模型的三档对照
 *
 * 一整串模板与一张张卡之间的换算、调色板上摆哪些块、块不可嵌套——
 * 这几件事在真机上凑齐一次的代价极高：块拖进块要在浏览器里拖到某一枚药丸正中央。
 * 视图模型因此被切成纯函数（config-ui/template-model.js，不碰 DOM），本文件喂它几份值对答案。
 *
 * 用 node 直接跑：
 *   node tools/template-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {
  AT_MODES, atPlan, blockLabel, blockSpec, isAttachment, normalizeCards,
  parseTemplate, placeholderBlock, textBlock, toTemplateText,
} from '../plugins/starbot-novabot-console/src/main/resources/config-ui-pages/template-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

const HANDLER = {
  placeholders: ['{uname}', '{title}', '{cover}', '{url}', '{at}', '{next}', '{at=all}'],
  attachments: ['{cover}'],
};
const SPEC = blockSpec(HANDLER);

function sketch(cards) {
  return cards.map(card => card.blocks
    .map(block => block.kind === 'text' ? block.text : '〔' + blockLabel(block.key) + '〕')
    .join('')).join(' ‖ ');
}

// —— 档一：调色板 ——
eq(SPEC.keys, ['{uname}', '{title}', '{cover}', '{url}'], '调色板上是处理器自报的那几个');
eq(SPEC.keys.includes('{next}'), false, '{next} 不是块——分条由再加一张卡表达');
eq(SPEC.keys.includes('{at}'), false, '手写的 {at} 不进调色板');
eq(isAttachment('{cover}', SPEC), true, '封面是整行的附件块');
eq(AT_MODES.length, 3, '@ 谁是三档闭集');

// —— 档二：一整串 ⇄ 一张张卡 ——
const parsed = parseTemplate('{uname} 正在直播 {title}\n{url}{cover}', SPEC);
eq(sketch(parsed), '〔uname〕 正在直播 〔title〕\n〔url〕〔cover〕', '默认模板拆成块的样子');
eq(toTemplateText(parsed), '{uname} 正在直播 {title}\n{url}{cover}', '拼回去与原文一字不差');
eq(parseTemplate('{uname} 开播了{next}{cover}', SPEC).length, 2, '{next} 把模板切成两张卡');
eq(sketch(parseTemplate('{uname} 直播 {tittle}', SPEC)), '〔uname〕 直播 {tittle}',
  '认不出的占位符原样留成文字');

// —— 档三：块不可嵌套 ——
const nested = normalizeCards([{blocks: [
  {kind: 'ph', key: '{uname}', attachment: false, blocks: [placeholderBlock('{title}', SPEC)]},
  textBlock(''), textBlock('甲'), textBlock('乙'),
]}]);
eq(sketch(nested), '〔uname〕〔title〕甲乙', '套在里面的块被提到同一层');
eq(nested[0].blocks.every(block => !block.blocks), true, '捋直之后没有一个块还挂着块');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
if (failures.length) process.exit(1);

// ── terms 三档：有词＝适配器七键／无词＝{}／只缺 bot.impl ────────────────
// 三问各自 try/catch，末尾汇总，不得短路。
const ADAPTER_TERMS = {
  'bot.platform': 'QQ',
  'bot.impl': 'NapCat',
  'bot.family': 'OneBot 实现',
  'bot.impl.hint': 'NapCat、Lagrange 等 OneBot 实现',
  'bot.target.group': '群号',
  'bot.target.user': 'QQ 号',
  'bot.targets': '群与好友',
};
const NO_IMPL = Object.assign({}, ADAPTER_TERMS);
delete NO_IMPL['bot.impl'];

const phraseReds = [];
function askPhrase(name, fn) {
  try {
    fn();
  } catch (err) {
    phraseReds.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}
function mustHas(actual, needle, what) {
  if (!String(actual).includes(needle)) {
    throw new Error(what + ' 得到 ' + JSON.stringify(actual) + ' 应含 ' + JSON.stringify(needle));
  }
}
function mustNot(actual, needle, what) {
  if (String(actual).includes(needle)) {
    throw new Error(what + ' 得到 ' + JSON.stringify(actual) + ' 不应含 ' + JSON.stringify(needle));
  }
}
function allNote(terms) {
  const cards = parseTemplate('{uname} 正在直播', SPEC);
  return atPlan(cards, 'all', {isGroup: true, admin: true}, terms).note || '';
}

askPhrase('①有词', () => {
  mustHas(allNote(ADAPTER_TERMS), '不绕过 QQ 权限', '说明');
});

askPhrase('②无词', () => {
  const got = allNote({});
  mustHas(got, '不绕过机器人权限', '说明');
  mustNot(got, 'QQ', '说明');
});

askPhrase('③只缺 bot.impl', () => {
  mustHas(allNote(NO_IMPL), '不绕过 QQ 权限', '说明仍用 platform');
});

console.log('terms 三档\t跑了 3 格\t红 ' + phraseReds.length + ' 格\t' + (phraseReds.length ? '红' : '绿'));
if (phraseReds.length) {
  console.error('\nterms 三档对不上 ' + phraseReds.length + ' 处：');
  phraseReds.forEach(line => console.error('  ' + line));
  process.exit(1);
}
