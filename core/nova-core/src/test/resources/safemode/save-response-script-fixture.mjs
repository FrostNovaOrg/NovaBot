/**
 * 安全模式编辑页里保存脚本的行为：点保存后，服务端回的整页要写进当前页面
 *
 * 页面脚本曾把三步连成 document.open()||document.write(t)||document.close()：
 * document.open() 返回文档对象（真值），|| 链到它就短路，write 永远不执行，
 * 浏览器里看到的是清空后的白页，保存成没成、错在哪都看不到。
 * 这里给脚本一个假的 document 与 fetch，真执行编辑页里那段脚本，量行为不量字面。
 *
 * 用法：node save-response-script-fixture.mjs <编辑页文件> <保存应答文件>
 * 由 SafeModeServerSaveResponseTest 拉起，两份文件都是服务端真出的那两页。
 */

import {readFileSync} from 'node:fs';

const pagePath = process.argv[2];
const responsePath = process.argv[3];
if (!pagePath || !responsePath) {
  console.error('用法: node save-response-script-fixture.mjs <编辑页文件> <保存应答文件>');
  process.exit(2);
}

const page = readFileSync(pagePath, 'utf8');
const response = readFileSync(responsePath, 'utf8');

const failures = [];
let checks = 0;

function ok(condition, what) {
  checks++;
  if (!condition) failures.push(what);
}

const blocks = [...page.matchAll(/<script>([\s\S]*?)<\/script>/g)];
const script = blocks.length ? blocks[blocks.length - 1][1] : '';

// 假 document：open 返回文档对象本身（真值），与真浏览器一致——这正是当初短路的地方
const calls = [];
const written = [];
let submitListener = null;
const form = {
  action: '/save?token=fake-token-for-fixture',
  addEventListener(type, listener) {
    if (type === 'submit') submitListener = listener;
  },
};
const document = {
  querySelector(selector) {
    if (selector === 'form') return form;
    if (selector === 'textarea') return {value: 'fixture-draft: true\n'};
    return null;
  },
  open() {
    calls.push('open');
    return document;
  },
  write(text) {
    calls.push('write');
    written.push(String(text));
  },
  close() {
    calls.push('close');
  },
};
const fetchImpl = () => Promise.resolve({text: () => Promise.resolve(response)});

let scriptError = '';
try {
  if (!script) throw new Error('编辑页里没找到脚本');
  new Function('document', 'fetch', script)(document, fetchImpl);
  if (!submitListener) throw new Error('脚本没在表单上挂 submit 监听');
  submitListener.call(form, {preventDefault() {}});
  await new Promise(resolve => setTimeout(resolve, 20)); // 等 then 链走完
} catch (e) {
  scriptError = e.message;
}

// ① 应答整页写进当前页面
ok(written.length === 1 && written[0] === response,
    '① 应答整页写进 document' + (scriptError ? '（脚本没跑成: ' + scriptError + '）' : '')
    + '：写了 ' + written.length + ' 次'
    + (written.length ? '，内容与应答' + (written[0] === response ? '一致' : '不一致') : ''));

// ② 清页、写入、收尾三步按序走完：少了 open 会往旧页里追加，少了 close 页面不算写完
const order = calls.length ? calls.join(',') : '（一步没走）';
ok(order === 'open,write,close', '② open→write→close 按序走完，实际: ' + order);

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
