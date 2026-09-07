/**
 * 向导主播步走完那一页上的「去主播页看看」
 *
 * 查到人、还没落盘时不出这个链接：详情页读的是已经落盘的名单，点进去是一张空页。
 * 切出 doneLink 后<b>真执行</b>三态，不是只 includes——「含 detailHash」证明不了
 * 它在没落盘时会不会照样给出一个地址。
 *
 * 由 SetupStreamerLinkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const pages = join(dirname(fileURLToPath(import.meta.url)),
  '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'setup-streamer.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 从 marker 起按花括号配平截到块尾（含声明本身）。字符串与注释里的花括号不算层 */
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

// 切出 doneLink，把它用到的两样喂进去：这一步自己的草稿，与主播页那份地址拼法。
// detailHash 用替身而不是真的那一份：这一格问的是「什么时候给地址」，
// 「地址怎么拼」由主播页自己的判据量（同一份 detailHash，两处不必各量一遍）
const body = bracedFrom(src, 'export function doneLink');
eq(body.length > 0, true, '① 找得到 doneLink');

function linkWith(draft, users) {
  const make = new Function('draft', 'detailHash',
    body.replace('export function', 'function') + '\nreturn doneLink;');
  const fn = make(draft, (platform, uid) => '#/streamers/' + platform + '/' + uid);
  return fn({status: {users}});
}

const WHO = {platform: 'somewhere', uid: 3493, uname: '某人'};

eq(linkWith({streamer: null}, [{}]), null,
  '② 这一步没查过人：不出链接，哪怕这台机器上本来就有主播');
eq(linkWith({streamer: WHO}, []), null,
  '③ 查到人但还没落盘：不出链接——详情页读的是已落盘的名单，点进去是空页');
eq(linkWith({streamer: WHO}, [{uid: 3493}]),
  {text: '去主播页看看', href: '#/streamers/somewhere/3493'},
  '④ 查到人且已落盘：出链接，地址经 detailHash 拼');

// ⑤ 画「找到了」那张卡的时候不许出这个链接：那一刻还没落盘
const paint = bracedFrom(src, 'function paintFound');
eq(paint.length > 0 && !paint.includes('detailHash'), true,
  '⑤ paintFound 切得出来且不问 detailHash');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
