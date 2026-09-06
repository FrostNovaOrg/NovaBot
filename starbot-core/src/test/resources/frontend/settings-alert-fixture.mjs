/**
 * 邮件告警卡：选预设后药丸要跟着变；认预设要把端口算进去
 *
 * 程序给输入框赋值不触发 input，药丸若只听 input 就会停在「未配置」。
 * 只按服务器名认预设时，端口对不上也会把自定义栏藏起来，465 和 587 从界面上看不见。
 *
 * 由 SettingsAlertViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');

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

function constArrow(name) {
  const start = src.indexOf('const ' + name + ' = ');
  if (start < 0) return '';
  const next = src.indexOf('\n\n', start);
  const to = next < 0 ? src.length : next;
  return src.slice(start, to);
}

const apply = constArrow('applyMail');
eq(apply.length > 0, true, '找得到 applyMail');
eq(apply.includes('mailReady('), true, '选预设后刷药丸');

const from = src.indexOf('mailPreset.value =');
const findAt = from < 0 ? -1 : src.indexOf('.find(', from);
const to = findAt < 0 ? -1 : src.indexOf('|| CUSTOM', findAt);
const recognize = from >= 0 && findAt > from && to > findAt
  ? src.slice(from, to + '|| CUSTOM'.length)
  : '';
eq(recognize.length > 0, true, '找得到认预设');
eq(recognize.includes("'spring.mail.port'"), true, '认预设要比端口');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
