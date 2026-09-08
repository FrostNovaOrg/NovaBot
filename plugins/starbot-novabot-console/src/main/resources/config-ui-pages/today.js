/**
 * 首页「今日」卡（{@code home_card} 槽）：三个数格与推送总开关
 *
 * 本文件只管把 today-model.js 算好的东西摆上屏幕。哪一格写「—」、第三格摊不摊得开、
 * 明细怎么排——一律在那边判，判断留在这里的话，那几档就只能靠人去改线上配额再打开页面看。
 *
 * 这张卡与它背后那个开关一起搬出宿主：三个数问的都是「推送这件事今天怎么样」，
 * 而 /push/toggle 本来就在本插件里。留在宿主的表现不是报错——它照常画，
 * 只是卸掉本插件之后那个开关按下去 404，而屏幕上只有一句「切换失败」。
 */

import {api, esc, say} from './core.js';
import {refreshHome} from './overview.js';
import {todayAtAllMarkup, todayModel} from './today-model.js';

/** 宿主给的那块容器，卡的正文 */
let box = null;

/**
 * 此刻推送是开着还是暂停，由 paint 按 /api/status 现算的那一位记下
 *
 * 记在这里而不是宿主的 store 上：这个开关连同它背后的 /push/toggle 都在本插件里，
 * 摆进宿主的话，卸掉本插件之后宿主还留着一份没人再更新的状态。
 * 只有本文件读写它——按下开关时要知道「现在是开着的，那就该关」。
 */
let pushOn = true;

/** 最近一份运行状态与额度回包。两份分别到，画的时候要一起用 */
let seen = {status: null, quota: null};

/**
 * 把卡的外壳摆出来
 *
 * 三个数与开关的内容留空：它们要等 /api/status 与 /api/at-all/quota 回来。
 * 先写死一份占位数字的话，接口慢的那几秒屏幕上是一份编出来的数据。
 * @param container 宿主给的容器
 */
export function render(container) {
  box = container;
  box.innerHTML = '<div class="stats" id="today-stats"></div>'
    + '<div class="quickbar">'
    + '<button id="toggle-push" type="button">…</button>'
    + '<span id="push-hint"></span>'
    + '</div>';
  box.querySelector('#toggle-push').addEventListener('click', togglePush);
  paint();
}

/**
 * 宿主把运行状态转过来
 * @param data /api/status 回包
 */
export function status(data) {
  seen.status = data || null;
  paint();
}

/**
 * 额度那一份自己取
 *
 * 单独失败时其余两格照画，第三格写「—」——不让一份取不到的额度把整张卡拖空。
 */
export async function refresh() {
  seen.quota = await api('/at-all/quota').catch(() => null);
  paint();
}

/** 三个数格与开关，按此刻手里这两份回包重画 */
function paint() {
  if (!box) return;
  const model = todayModel(seen.status, seen.quota);
  const cells = [
    {value: model.sent, label: '推送（条）'},
    {value: model.failed, label: '失败（条）'},
    {value: model.atAll.value, label: model.atAll.label, tile: model.atAll},
  ];
  const stats = box.querySelector('#today-stats');
  stats.innerHTML = cells.map(cell => {
    if (!cell.tile) {
      return '<div class="stat"><div class="stat-v">' + esc(cell.value) + '</div>'
        + '<div class="stat-l">' + esc(cell.label) + '</div></div>';
    }
    return todayAtAllMarkup(cell.tile, false);
  }).join('');

  const drop = stats.querySelector('#today-atall');
  if (drop && (model.atAll.details || []).length) {
    drop.addEventListener('click', () => {
      const open = drop.classList.toggle('open');
      drop.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }

  // 开关的字与那句提示由同一位算出来，不各写各的：分叉时按钮写着「暂停全部推送」
  // 而旁边那句写着「已暂停」，两句话说的是相反的事
  pushOn = model.pushOn;
  const toggle = box.querySelector('#toggle-push');
  const hint = box.querySelector('#push-hint');
  toggle.textContent = model.pushOn ? '暂停全部推送' : '恢复推送';
  hint.textContent = model.pushOn ? '当前正常推送' : '已暂停，所有推送都会被丢弃';
  hint.style.color = model.pushOn ? '' : 'var(--err)';
}

/**
 * 暂停／恢复全部推送
 *
 * 走整页重画而不是只把开关拨过去：暂停会同时改顶部横条、链路那一段与「现在」那张卡，
 * 只拨开关的话，屏幕上会出现「已暂停」与一段绿灯并存的画面。
 */
export async function togglePush() {
  const next = !pushOn;
  const toggle = box && box.querySelector('#toggle-push');
  if (toggle) toggle.disabled = true;
  try {
    const res = await api('/push/toggle', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({enabled: next}),
    });
    say(res.message, res.success ? 'ok' : 'err');
    await refreshHome();
  } catch (e) {
    say('切换失败：' + e.message, 'err');
  }
  if (toggle) toggle.disabled = false;
}
