/**
 * 初始设置第 4 步「去主播页」链接的落点
 *
 * 查到人、还没保存时出这个链接，点进去是空页：详情页读的是已经落盘的名单。
 * 这一格量的是源码树里的 setup.js，不是构建产物里的副本。
 *
 * 由 SetupViewTest 拉起。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'setup.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function functionBody(name) {
  const start = src.indexOf('function ' + name);
  if (start < 0) return '';
  const next = src.indexOf('\nfunction ', start + 1);
  const to = next < 0 ? src.length : next;
  return src.slice(start, to);
}

const paint = functionBody('paintFound');
eq(paint.length > 0, true, '找得到 paintFound');
eq(paint.includes('setup-go-streamer'), false,
  'lookup 成功但未保存时不得出现 #setup-go-streamer');

const done = functionBody('renderDone');
eq(done.length > 0, true, '找得到 renderDone');
eq(done.includes('setup-go-streamer'), true,
  '完成页才出「去主播页看看」');
eq(done.includes('detailHash('), true,
  '完成页去主播页的地址必须问 detailHash');
eq(done.includes('draft.streamer') && done.includes('facts[3]'), true,
  '没加主播时完成页不出这个链接：草稿有人且已落盘才出');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
