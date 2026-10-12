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
 * 显式清除标记，与后端的 SensitiveFields.CLEAR 同一串
 *
 * 界面拿不到机密项的真值，框里不是遮点就是空。于是空有两种含义：照提示留空＝没动，
 * 与清掉。给「清掉」一个显式标记，空一律当没动——照提示留空保存就不会把已存的
 * 授权码删掉。头尾两个 NUL 使键盘打不出这一串，任何真实口令都与它对不上。
 */
export const CLEAR = '\0CLEAR\0';

/**
 * 机密项的草稿态：框里显示什么、改动账里记什么
 *
 * @param draft 改动账里这一项的值，没记过时传 undefined
 * @param saved 已保存的值（遮点或空）
 * @return {{text: string, cleared: boolean, keep: boolean}} text 是框里显示的字；
 *   cleared 是不是「保存后清除」；keep 为 true 表示与没动一样，不该进改动账
 */
export function secretDraft(draft, saved) {
  const base = saved === undefined || saved === null ? '' : String(saved);
  if (draft === undefined || draft === null) return {text: base, cleared: false, keep: true};
  const text = String(draft);
  if (text === CLEAR) return {text: '', cleared: true, keep: false};
  return {text, cleared: false, keep: text === base || text.trim() === ''};
}

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
 * {@code InetAddress.toString()} 的斜杠形态：可选主机名、一条斜杠，后面是 IPv4 点分
 * 或至少含一个冒号的 IPv6。CIDR（127.0.0.1/32、::1/128）对不上这支。
 * 后端读口已按键设门并会把文件改回裸地址；这里仍收一次，免得界面拿到斜杠串时进 dirty。
 */
const ADDRESS_KEYS = new Set(['server.address', 'management.server.address']);
const INET_SLASH = /^[^/]*\/((?:\d{1,3}(?:\.\d{1,3}){3})|(?:[0-9a-fA-F]*:[0-9a-fA-F:]+))$/;
const DOTTED = /^\d{1,3}(?:\.\d{1,3}){3}$/;

/**
 * 这一项拿到界面上、拿去跟已保存的值比时用的串
 *
 * 监听地址在第一次写出配置文件时，可能被写成 {@code /127.0.0.1}
 * （Java {@code InetAddress.toString()}）。那一串与出厂默认 {@code 127.0.0.1} 字面不同，
 * 但指的是同一个回环地址：拿去显示会让人以为机器改过监听地址，拿去记账会让底部改动条
 * 在一个字没改时亮起来。其它项原样。
 * @param field 字段表里的一项
 * @param value 当前值
 * @return {string} 比较与显示用的串
 */
export function canonicalValue(field, value) {
  const text = value === undefined || value === null ? '' : String(value);
  if (field && ADDRESS_KEYS.has(field.name)) {
    const matched = text.match(INET_SLASH);
    if (matched) {
      const slash = text.indexOf('/');
      const prefix = slash > 0 ? text.slice(0, slash) : '';
      if (!DOTTED.test(prefix)) return matched[1];
    }
  }
  return text;
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
  return canonicalValue(field, value) !== canonicalValue(field, defaultValue(field));
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

/**
 * 目录里该亮哪一组。
 *
 * 看得见的起点从吸顶那几块的下沿算，它们盖住的那一截不算。那几块有多高由调用方
 * 从页面量好传进来（窄屏上是顶栏加组目录，宽屏上没有吸顶元素，传 0）。
 * 点左侧菜单时，点中的组会被送到吸顶那几块的下沿，这时亮它，而不是还藏在它们底下的上一组。
 * 页面还能往下滚、并且已经滚到最底时，最后一组往往比视口矮，它的顶到不了吸顶那几块的下沿，这时亮最后一组。
 * 一屏就放得下，或者还没滚到底，仍亮看得见的最上面那一组。
 * @param {Array<{id: string, top: number, bottom: number}>} groups 文档坐标
 * @param {number} viewportTop 视口上沿的文档坐标，含被吸顶那几块盖住的那一截
 * @param {number} viewportHeight 视口高度
 * @param {number} [headerHeight] 吸在视口顶上的那几块有多高；不传或不是正数时按没有吸顶元素算
 * @param {number} [pageHeight] 文档高度；不传时按最后一组的底边算
 * @return {string} 组 id；一组都看不见时为空串
 */
export function currentGroupId(groups, viewportTop, viewportHeight, headerHeight, pageHeight) {
  if (!Array.isArray(groups) || !(viewportHeight > 0)) return '';
  const header = typeof headerHeight === 'number' && headerHeight > 0 ? headerHeight : 0;
  const visibleTop = viewportTop + header;
  const viewBottom = viewportTop + viewportHeight;
  let chosen = '';
  let chosenTop = Infinity;
  let last = null;
  for (const group of groups) {
    if (!group || typeof group.top !== 'number' || typeof group.bottom !== 'number') continue;
    if (!(group.bottom > group.top)) continue;
    if (!last || group.bottom >= last.bottom) last = group;
    if (group.bottom <= visibleTop || group.top >= viewBottom) continue;
    if (group.top < chosenTop) {
      chosenTop = group.top;
      chosen = group.id || '';
    }
  }
  const page = typeof pageHeight === 'number' && pageHeight > 0 ? pageHeight : (last ? last.bottom : 0);
  const canScroll = page > viewportHeight;
  const atBottom = viewportTop + viewportHeight >= page - 1;
  if (canScroll && atBottom && last && last.bottom > visibleTop && last.top < viewBottom) {
    return last.id || '';
  }
  return chosen;
}

/**
 * 把服务端那张组表分成常用与高级两半
 *
 * 分法只认服务端：组标了 advanced 的整组进高级；其余组里 level 为 ADVANCED 的项进高级，
 * 另起一组「某组 · 高级」，BASIC／COMMON 留在常用。界面上不另记一张写着键名的名单——
 * 那张名单只能由核心来写，插件新加的键会落在名单外，而落错了不会有任何提示。
 *
 * 由卡片摆的项（告警的三张卡、登录与安全的几张卡）跟着卡走：卡挂在常用那一组上，
 * 这几项要是按级别算进高级，行里摘掉了、数目却记在高级那边，两边对不上。
 * 旧后端没给 level 的项按常用摆：把它们藏起来，比多露几项更难察觉。
 * 「整组都要重启」那枚牌子按拆出来的两半各自重算，服务端算的是整组。
 * @param groups 组表，来自 /api/schema 的 groups；不改动它
 * @param carded (组标识, 配置键) => 这一项是否由卡片摆，可不传
 * @return {{common: Array, advanced: Array}} 两半，各按组表原来的顺序
 */
export function arrangeGroups(groups, carded) {
  const common = [];
  const advanced = [];
  const held = typeof carded === 'function' ? carded : () => false;
  const part = (group, fields, extra) => Object.assign({}, group, {
    fields,
    allRestart: fields.length > 0 && fields.every(field => field.effect !== 'IMMEDIATE'),
  }, extra);
  for (const group of groups || []) {
    const fields = group.fields || [];
    if (group.advanced) {
      advanced.push(part(group, fields.slice(), {}));
      continue;
    }
    const basic = [];
    const extra = [];
    for (const field of fields) {
      const high = field.level === 'ADVANCED' && !held(group.group, field.name);
      (high ? extra : basic).push(field);
    }
    if (basic.length) common.push(part(group, basic, {}));
    if (extra.length) {
      advanced.push(part(group, extra, {
        group: group.group + '-advanced', title: group.title + ' · 高级', advanced: true, base: group.group,
      }));
    }
  }
  return {common, advanced};
}

/**
 * 高级那一块此刻该不该摆出来
 *
 * 收起是默认。三种情况要摆出来：使用者点了「显示高级选项」或从别处点名跳进高级里的某一组
 * （chosen）；搜索／只看改过命中了高级里的项（搜到了却收着，与没搜到长得一样）——
 * 除非使用者就在这一次筛选上亲手收起过它（dismissed）。高级里一项都不剩时不摆。
 * @param state.matches 高级里此刻没被筛掉的项数
 * @param state.filtering 是否正在搜索或只看改过
 * @param state.chosen 是否选了显示
 * @param state.dismissed 是否在这一次筛选上亲手收起过
 * @return {boolean}
 */
export function advancedShown(state) {
  const s = state || {};
  if (!(s.matches > 0)) return false;
  if (s.chosen) return true;
  return !!s.filtering && !s.dismissed;
}

/** 时刻：两位小时、两位分钟，可带秒。与服务端 LocalTime.parse 收的写法同一路 */
const TIME_OF_DAY = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d+)?)?$/;

/**
 * 时刻项的框里显示什么
 *
 * 时间框只认补零的 HH:mm。配置文件里存着它认不出的值（不补零的 8:00、写错的字）时，
 * 框会显示成空——可那一项并不是空的：服务端读到它也认不出，整段静音按没设处理。
 * 因此这里把「认不出」单独报出来，让界面说一句，而不是让人以为本来就没填。
 * @param value 已保存的值或草稿
 * @return {{text: string, unreadable: boolean}} text 是交给时间框的 HH:mm（或空串）
 */
export function timeOfDay(value) {
  const raw = String(value ?? '').trim();
  if (!raw) return {text: '', unreadable: false};
  if (!TIME_OF_DAY.test(raw)) return {text: '', unreadable: true};
  return {text: raw.slice(0, 5), unreadable: false};
}

/**
 * 时间框里的值交出去之前收成 HH:mm
 *
 * 浏览器给的可能带秒（step 不是 60 时），服务端要的是 HH:mm 或空串。认不出的一律当空：
 * 空串的意思是「停用静音」，与服务端遇到坏值时的做法一致，不会把坏值写进配置文件。
 * @param value 时间框的 value
 * @return {string} HH:mm 或空串
 */
export function timeOfDayValue(value) {
  return timeOfDay(value).text;
}
