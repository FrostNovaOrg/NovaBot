/**
 * 外部程序触发规则待调整时，控制台顶上的常驻提醒
 *
 * 清单来自 /exec/notice，是启动检查时定下的那一份：改好配置并重启后为空，这一条随之不再出现。
 * 只给管理员画：规则写在配置文件里，成员改不了。
 */

import {CONSOLE} from './core.js';

/** 自带手册里讲外部程序触发写法的那一章 */
const APPENDIX = `${CONSOLE}/manual/appendix-b-advanced.html`;

/**
 * 按清单画提醒；清单为空、取不到或回包失败时藏起来
 * @param {HTMLElement} box 提醒所在的容器
 * @param {{success?: boolean, rules?: Array<{key: string, event?: string}>}|null} data /exec/notice 的回包
 * @return {boolean} 是否显示
 */
export function renderExecNotice(box, data) {
  const rules = data && data.success && Array.isArray(data.rules) ? data.rules : [];
  box.textContent = '';
  if (!rules.length) {
    box.style.display = 'none';
    return false;
  }
  const names = rules.map(rule => rule.event ? rule.key + '（事件 ' + rule.event + '）' : rule.key).join('、');
  const text = document.createElement('span');
  text.textContent = '外部程序触发有 ' + rules.length + ' 条规则需要调整：' + names
    + '。调整之前，这些规则可能被跳过不执行。请按使用说明附录 B 修改这些规则的 command，改好后重启。';
  const link = document.createElement('a');
  link.href = APPENDIX;
  link.textContent = '打开附录 B →';
  box.append(text, ' ', link);
  box.style.display = '';
  return true;
}
