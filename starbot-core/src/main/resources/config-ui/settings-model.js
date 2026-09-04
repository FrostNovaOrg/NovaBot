/**
 * 设置页的判定：搜索、只看改过、默认值、危险项围栏
 *
 * 这几件事全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * 混在渲染代码里的话，「搜『静音』搜不搜得到那两项」这种事就只能靠人打开页面手点，
 * 而手点测不出「说明里含关键字但标题不含」这类分支。
 */

/**
 * 遮住的机密值。后端把口令、令牌与密钥换成这一串再下发
 *
 * 界面据此认出「这一项配过、但我看不到它的值」。拿它跟默认值比是没有意义的，
 * 因此下面几处判定都要先把它挑出来单独说。
 */
export const MASK = '********';

/**
 * 这一项改到这个值，要不要先问一句
 *
 * 「哪几项危险、危险在哪、改到哪一档才危险」三件事全由服务端随字段表一起下发
 * （见 ConfigDanger），界面这边<b>没有一张写死的危险项清单</b>。
 * 写死的话，那张清单只能由核心来写，而其中有些项属于插件——核心不该认识它们，
 * 插件没装时那一条还会一直挂在表里。
 *
 * 返回的是那句话本身而不是一个 true：调用方拿到它就直接弹，不必再去别处查文案。
 * @param field 字段表里的一项
 * @param value 改成的值，布尔项传 'true' / 'false'
 * @return {{title: string, body: string}|null} 要问就返回文案，不问返回 null
 */
export function dangerOf(field, value) {
  const rule = field && field.danger;
  if (!rule) return null;
  // 危险的不是这一项本身，是它被改到某一档：监听地址填 127.0.0.1 一点都不危险
  if (String(value ?? '').trim() !== String(rule.value)) return null;
  return {title: rule.title, body: rule.consequence};
}

/**
 * 这一项是不是危险项
 *
 * 与 dangerOf 分开：行左那道警示边框不管当前值是什么都要画出来，
 * 它答的是「这一项危险」；dangerOf 答的是「这一次的改动危险」。
 * @param field 字段表里的一项
 * @return {boolean} 是危险项时为 true
 */
export function isDangerous(field) {
  return !!(field && field.danger);
}

/**
 * 默认值显示成什么样
 *
 * 空与「没有默认值」在界面上得说人话，否则那一行会写成「默认： · 恢复默认」。
 * @param field 字段表里的一项
 * @return {string} 显示文本
 */
export function defaultText(field) {
  const d = field.defaultValue;
  if (field.widget === 'boolean') return String(d) === 'true' ? '开' : '关';
  if (d === null || d === undefined || d === '') return field.widget === 'list' ? '空' : '未设';
  if (Array.isArray(d)) return d.length ? d.join('、') : '空';
  return String(d).replace(/\n/g, '、');
}

/**
 * 默认值在控件里长什么样
 *
 * 「恢复默认」按下去要把这个值填回控件，因此它必须与控件读出来的形态一致——
 * 列表控件是每行一项的文本，布尔是 'true' / 'false'。
 * @param field 字段表里的一项
 * @return {string} 控件值
 */
export function defaultValue(field) {
  const d = field.defaultValue;
  if (field.widget === 'boolean') return String(d) === 'true' ? 'true' : 'false';
  if (d === null || d === undefined) return '';
  if (Array.isArray(d)) return d.join('\n');
  return String(d);
}

/**
 * 这一项的值与默认值不一样吗
 *
 * <b>这与「改过还没保存」不是一回事</b>，两者在界面上也是两套记号：
 * 这里答的是「这台机器在这一项上偏离了出厂设定」，那是使用者巡视配置时想看的；
 * 未保存的草稿由底部改动条统计，那是「点保存之前还剩什么没落盘」。
 * 合成一个的话，一台配置得很仔细的机器一进设置页就满屏都是记号。
 *
 * 机密项按「配过就算改过」处理：它的真值不出后端，拿遮罩串跟默认值比是比不出东西来的。
 * @param field 字段表里的一项
 * @param value 当前值（字符串）
 * @return {boolean} 与默认值不同时为 true
 */
export function isChanged(field, value) {
  if (field.sensitive) return String(value ?? '') !== '';
  return String(value ?? '') !== defaultValue(field);
}

/**
 * 搜索时拿去比对的那一串
 *
 * 三样都进去：人话名（使用者记得的）、说明（他描述得出的现象）、键名（运维手里有的）。
 * 只比人话名的话，「搜 quiet 搜不到静音时段」这种事每次都要有人来报。
 * @param field 字段表里的一项
 * @return {string} 小写串
 */
export function haystack(field) {
  return [field.label, field.name, field.description].filter(Boolean).join(' ').toLowerCase();
}

/**
 * 这一项要不要显示
 *
 * 两个条件是与的关系：搜索命中，且（没开只看改过，或它确实改过）。
 * @param field 字段表里的一项
 * @param value 当前值
 * @param query 搜索词，已 strip
 * @param onlyChanged 是否只看改过的
 * @return {boolean} 显示时为 true
 */
export function isVisible(field, value, query, onlyChanged) {
  const q = String(query || '').trim().toLowerCase();
  if (q && !haystack(field).includes(q)) return false;
  return !onlyChanged || isChanged(field, value);
}

/**
 * 这一项改了什么时候生效
 *
 * 后端对没标过的项回 null，这里跟着回「按需重启」——多提示一次重启的代价是白重启，
 * 而反过来说成已生效却没生效，使用者会以为功能坏了，且没有任何东西会纠正他。
 * @param effect 生效时机，可为 null
 * @return {{immediate: boolean, text: string}} 显示用
 */
export function effectOf(effect) {
  const immediate = effect === 'IMMEDIATE';
  return {immediate, text: immediate ? '立即生效' : '重启生效'};
}
