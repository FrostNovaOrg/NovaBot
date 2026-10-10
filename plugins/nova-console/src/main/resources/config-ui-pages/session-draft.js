/**
 * 本群设置的改动草稿
 *
 * 金额可见、静音时段、命令开关、@我订阅的移除与清空：改了先记在这里，按「保存」才发出，
 * 按「放弃」随推送配置那份草稿一起丢。与上方三段同一条保存纪律。
 *
 * 记的是「想要的那一份」与「服务端那一份」的差，不是「拨了几下」：
 * 拨过去又拨回来的那几下会自己从表里消失，改动条不会赖着不走。
 *
 * 一个群改了几项算几处——与上方「按主播计入」同一粒度：那是「几个主播变了」，
 * 这是「这个群里几项变了」。两边加起来就是底部改动条那个 N。
 */

const drafts = new Map();

/**
 * 金额草稿变了要跟着重画的那一份
 *
 * 版式区灰不灰按「服务端＋草稿」现算，预览与图下说明也按草稿画——它们住在
 * 通道页的另外几段里，不归这个文件画。草稿是这里管的，重画的通知就从这里发：
 * 单槽位，最后一次注册的算数（同一时刻屏幕上只有一个通道的版式区在听）。
 */
let revenueDraftListener = () => {};

/**
 * 登记金额草稿变了之后的重画；传空即注销
 * @param fn 重画那一支，无参
 */
export function onRevenueDraftChange(fn) {
  revenueDraftListener = typeof fn === 'function' ? fn : () => {};
}

function keyOf(target) {
  return target.platform + ':' + target.type + ':' + target.num;
}

function slotOf(target) {
  const key = keyOf(target);
  if (!drafts.has(key)) {
    drafts.set(key, {
      target: {platform: target.platform, type: target.type, num: target.num},
      revenue: null,
      quiet: null,
      commands: new Map(),
      subs: new Map(),
    });
  }
  return drafts.get(key);
}

/** 差没了就把自己从表里拿掉：空壳占着位置会让「还有几处」数出幽灵 */
function trim(slot) {
  if (!slot.revenue && !slot.quiet && !slot.commands.size && !slot.subs.size) {
    drafts.delete(keyOf(slot.target));
  }
}

/**
 * 本群设置此刻有几处改过还没保存
 * @return {number} 一处改动算一处
 */
export function sessionDraftCount() {
  let n = 0;
  for (const slot of drafts.values()) {
    if (slot.revenue) n++;
    if (slot.quiet) n++;
    for (const d of slot.commands.values()) {
      if (d.to !== d.from) n++;
    }
    for (const sub of slot.subs.values()) {
      if (sub.clear) n++;
      else n += sub.removed.size;
    }
  }
  return n;
}

export function clearSessionDrafts() {
  drafts.clear();
}

export function setRevenueDraft(target, to, from) {
  const s = slotOf(target);
  s.revenue = (to === from) ? null : {from: !!from, to: !!to};
  trim(s);
  revenueDraftListener();
}

/** 想要的金额可见性；没改过则为 undefined */
export function revenueDraft(target) {
  const s = drafts.get(keyOf(target));
  return s && s.revenue ? s.revenue.to : undefined;
}

/** 两份静音设置是不是同一档：不是自己的时段时，起止不算数 */
function sameQuiet(a, b) {
  if (a.mode !== b.mode) return false;
  return a.mode !== 'custom' || (a.start === b.start && a.end === b.end);
}

/**
 * 静音时段的期望设置
 * @param to 想要的 {mode, start, end}
 * @param from 服务端那一份 {mode, start, end}
 */
export function setQuietDraft(target, to, from) {
  const s = slotOf(target);
  const want = {mode: to.mode, start: to.start || '', end: to.end || ''};
  const was = {mode: from.mode, start: from.start || '', end: from.end || ''};
  s.quiet = sameQuiet(want, was) ? null : {from: was, to: want};
  trim(s);
}

/** 想要的静音设置 {mode, start, end}；没改过则为 undefined */
export function quietDraft(target) {
  const s = drafts.get(keyOf(target));
  return s && s.quiet ? s.quiet.to : undefined;
}

export function setCommandDraft(target, name, to, from) {
  const s = slotOf(target);
  if (to === from) s.commands.delete(name);
  else s.commands.set(name, {from: !!from, to: !!to});
  trim(s);
}

/**
 * 成批开关：一次把多条命令的期望状态写进草稿
 * @param serverOff 服务端此刻是否已禁用，按名问
 */
export function setCommandsDraft(target, names, disabled, serverOff) {
  const s = slotOf(target);
  for (const name of names) {
    const from = !!(serverOff && serverOff(name));
    if (disabled === from) s.commands.delete(name);
    else s.commands.set(name, {from, to: !!disabled});
  }
  trim(s);
}

/** 想要的禁用态；没改过则为 undefined */
export function commandDraft(target, name) {
  const s = drafts.get(keyOf(target));
  const d = s && s.commands.get(name);
  return d ? d.to : undefined;
}

function subKey(sub) {
  return sub.streamerUid + '|' + sub.type;
}

/**
 * 移除一人；再点一次即撤回
 */
export function setSubscriptionRemoveDraft(target, sub, userUid) {
  const s = slotOf(target);
  const k = subKey(sub);
  const cur = s.subs.get(k) || {
    streamerUid: sub.streamerUid, type: sub.type, removed: new Set(), clear: false, ask: null,
  };
  if (cur.clear) return;
  if (cur.removed.has(userUid)) cur.removed.delete(userUid);
  else cur.removed.add(userUid);
  if (cur.removed.size) s.subs.set(k, cur);
  else s.subs.delete(k);
  trim(s);
}

/**
 * 清空整份名单；再点一次即撤回
 * @param ask 保存时要问的那一句（现有的二次确认），带上它才在保存时弹
 */
export function setSubscriptionClearDraft(target, sub, ask) {
  const s = slotOf(target);
  const k = subKey(sub);
  const cur = s.subs.get(k);
  if (cur && cur.clear) {
    s.subs.delete(k);
  } else {
    s.subs.set(k, {
      streamerUid: sub.streamerUid, type: sub.type, removed: new Set(), clear: true,
      ask: ask || null,
    });
  }
  trim(s);
}

export function subscriptionDraft(target, streamerUid, type) {
  const s = drafts.get(keyOf(target));
  return s ? s.subs.get(streamerUid + '|' + type) : undefined;
}

export function isUserPendingRemove(target, streamerUid, type, userUid) {
  const d = subscriptionDraft(target, streamerUid, type);
  if (!d) return false;
  return d.clear || d.removed.has(userUid);
}

export function isClearPending(target, streamerUid, type) {
  const d = subscriptionDraft(target, streamerUid, type);
  return !!(d && d.clear);
}

/**
 * 保存时要落盘的那几笔
 *
 * 每一笔自带 done()：那一笔真写成功了才清掉自己那一截草稿。
 * 写失败的那一笔留着，下次保存再发；成功过的不会重发。
 *
 * 清的是「发出去的那一份」，不是槽位里此刻这一份：请求挂着的那段时间里又改出来的
 * 新草稿是另一件东西，得留着下次保存再发。判法是草稿对象的身份——每一处 setter
 * 换的都是一份新对象，换过的就不再是发出去的那一个。
 * @return {Array<{path: string, body: object, done: function, ask?: object}>}
 */
export function sessionWrites() {
  const writes = [];
  for (const s of drafts.values()) {
    // 带上类型码：同一个号可以既是群又是好友，只带号的话后端分不出改的是哪一边
    const base = {platform: s.target.platform, type: Number(s.target.type), num: s.target.num};
    if (s.revenue) {
      const sent = s.revenue;
      writes.push({
        path: '/state/revenue',
        body: Object.assign({}, base, {visible: sent.to}),
        done: () => { if (s.revenue === sent) s.revenue = null; trim(s); },
      });
    }
    if (s.quiet) {
      const sent = s.quiet;
      const wanted = sent.to;
      const body = {mode: wanted.mode};
      if (wanted.mode === 'custom') {
        body.start = wanted.start;
        body.end = wanted.end;
      }
      writes.push({
        path: '/state/quiet',
        body: Object.assign({}, base, body),
        done: () => { if (s.quiet === sent) s.quiet = null; trim(s); },
      });
    }
    const disable = [];
    const enable = [];
    for (const [name, d] of s.commands) {
      if (d.to === d.from) continue;
      if (d.to) disable.push(name);
      else enable.push(name);
    }
    const keepOnlyUnchanged = names => {
      const sent = new Map(names.map(name => [name, s.commands.get(name)]));
      return () => {
        for (const name of names) {
          if (s.commands.get(name) === sent.get(name)) s.commands.delete(name);
        }
        trim(s);
      };
    };
    if (disable.length) {
      const names = disable.slice();
      writes.push({
        path: '/state/commands',
        body: Object.assign({}, base, {commands: names, disabled: true}),
        done: keepOnlyUnchanged(names),
      });
    }
    if (enable.length) {
      const names = enable.slice();
      writes.push({
        path: '/state/commands',
        body: Object.assign({}, base, {commands: names, disabled: false}),
        done: keepOnlyUnchanged(names),
      });
    }
    for (const [k, d] of [...s.subs]) {
      if (d.clear) {
        const key = k;
        const sent = d;
        writes.push({
          path: '/state/subscription',
          body: Object.assign({}, base, {streamerUid: sent.streamerUid, type: sent.type, userUid: null}),
          done: () => { if (s.subs.get(key) === sent) s.subs.delete(key); trim(s); },
          ask: sent.ask || undefined,
        });
      } else {
        for (const uid of [...d.removed]) {
          const userUid = uid;
          const key = k;
          writes.push({
            path: '/state/subscription',
            body: Object.assign({}, base, {streamerUid: d.streamerUid, type: d.type, userUid}),
            done: () => {
              const cur = s.subs.get(key);
              if (cur) {
                cur.removed.delete(userUid);
                if (!cur.removed.size && !cur.clear) s.subs.delete(key);
              }
              trim(s);
            },
          });
        }
      }
    }
  }
  return writes;
}
