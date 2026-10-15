import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';

// 用调试管道读取真实布局，不需要监听本地端口或额外的浏览器依赖。
const profile = await mkdtemp(join(process.env.NOVABOT_BROWSER_TMP || tmpdir(), 'streamers-browser-'));
const browser = spawn(process.env.NOVABOT_CHROME_BINARY, [
  '--headless', '--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-pipe', '--remote-allow-origins=*', `--user-data-dir=${profile}`, 'about:blank',
], {stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe']});
const exited = once(browser, 'exit');
let browserErrors = '';
browser.stderr.on('data', data => { browserErrors += data; });
let sequence = 0, buffer = '';
const pending = new Map();
browser.stdio[4].on('data', data => {
  buffer += data;
  let end;
  while ((end = buffer.indexOf('\0')) >= 0) {
    const message = JSON.parse(buffer.slice(0, end)); buffer = buffer.slice(end + 1);
    if (pending.has(message.id)) {
      const {resolve, reject, timer} = pending.get(message.id);
      pending.delete(message.id); clearTimeout(timer);
      if (message.error) reject(new Error(message.error.message)); else resolve(message.result);
    }
  }
});
function command(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(method + ' 超时：' + browserErrors)); }, 20000);
    pending.set(id, {resolve, reject, timer});
    browser.stdio[3].write(JSON.stringify({id, method, params, sessionId}) + '\0');
  });
}

try {
  const {targetId} = await command('Target.createTarget', {url: 'about:blank'});
  const {sessionId} = await command('Target.attachToTarget', {targetId, flatten: true});
  const source = await readFile(new URL('../../../main/resources/config-ui-pages/streamers-components.js', import.meta.url), 'utf8');
  const css = await readFile(new URL('../../../main/resources/config-ui-pages/app.css', import.meta.url), 'utf8');
  const setup = `window.ui = (() => {${source.replace(/^export /gm, '')}; return {COMPONENT_STYLE, createChart, createCalendar, releaseComponents};})();
    document.head.append(Object.assign(document.createElement('style'), {textContent: ${JSON.stringify(css)} + ui.COMPONENT_STYLE}));`;
  await command('Runtime.evaluate', {expression: setup}, sessionId);
  const result = await command('Runtime.evaluate', {awaitPromise: true, returnByValue: true, expression: `(${(async () => {
    let checks = 0, failures = 0;
    const lines = [];
    const expect = (condition, message) => { if (!condition) throw new Error(message); };
    const host = (width = 600) => {
      const node = document.createElement('div'); node.style.width = width + 'px';
      document.body.append(node); return node;
    };
    const check = async (name, run) => {
      checks++;
      try { await run(); lines.push('绿 ' + name); }
      catch (error) { failures++; lines.push('红 ' + name + '：' + error.message); }
      finally { ui.releaseComponents(); document.body.replaceChildren(); }
    };
    const waitFor = async (condition, message) => {
      const deadline = performance.now() + 4000;
      while (!condition()) {
        if (performance.now() >= deadline) throw new Error(message);
        await new Promise(resolve => setTimeout(resolve, 20));
      }
    };
    const start = Date.parse('2026-09-01T06:00:00+08:00'), day = 86400000;
    const daily = Array.from({length: 30}, (_, i) => ({
      start: start + i * day, end: start + (i + 1) * day,
      date: '2026-09-' + String(i + 1).padStart(2, '0'), label: '2026-09-' + String(i + 1).padStart(2, '0'),
      value: i ? 87745.8 : 0, sessions: 1,
    }));
    // 全数与单位换行会挤坏纵轴；栏宽也不能继续固定为 48px。
    await check('纵轴简写且按内容定宽', () => {
      const chart = ui.createChart(host(), {points: daily, format: value => value.toFixed(2) + ' 元'});
      const ticks = [...chart.element.querySelectorAll('.sc-chart-scale span')];
      expect(ticks[0].textContent === '8.8万', '一万以上刻度没有简写成一位小数的万');
      expect(ticks.every(tick => tick.getBoundingClientRect().height < 24), '刻度折成两行');
      const width = chart.element.querySelector('.sc-chart-scale').getBoundingClientRect().width;
      const small = ui.createChart(host(), {points: [{...daily[0], value: 1}]});
      expect(width > small.element.querySelector('.sc-chart-scale').getBoundingClientRect().width, '刻度栏没有随内容变宽');
    });
    // 分别量宽窄容器的相邻标签，不能只核对取了几个刻度。
    for (const width of [600, 270]) await check('横轴标签不重叠 ' + width + 'px', () => {
      for (const period of ['day', 'week', 'month']) {
        const points = period === 'day' ? daily : Array.from({length: 6}, (_, i) => ({
          start: start + i * 7 * day, end: start + (i + 1) * 7 * day,
          label: '2026-09-01 ~ 09-07', value: 100, sessions: 1,
        }));
        const chart = ui.createChart(host(width), {points, period});
        const labels = [...chart.element.querySelectorAll('.sc-chart-labels span')];
        expect(labels.length >= 2, period + ' 缺少两端日期');
        for (let i = 1; i < labels.length; i++) {
          const previous = labels[i - 1].getBoundingClientRect(), current = labels[i].getBoundingClientRect();
          expect(previous.right <= current.left, period + ' 相邻横轴标签重叠');
        }
        expect(labels.every(label => !label.textContent.includes('2026-')), '横轴仍用全写日期');
      }
    });
    // disabled 属性本身不能说明使用者看得到不可用状态。
    await check('未选日期按钮有禁用样式', () => {
      const chart = ui.createChart(host(), {points: daily, onSelect() {}});
      const action = chart.element.querySelector('.sc-chart-action');
      const opacity = getComputedStyle(action).opacity, cursor = getComputedStyle(action).cursor;
      chart.svg.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight'}));
      expect(!action.disabled, '选中有场次的日期后按钮未启用');
      expect(opacity !== getComputedStyle(action).opacity || cursor !== getComputedStyle(action).cursor, '不可用与可用的计算样式相同');
    });
    const calendar = count => ({provableFrom: start + day, items: Array.from({length: count}, (_, i) => {
      const date = new Date(start + i * day);
      return {date: new Date(+date + 8 * 3600000).toISOString().slice(0, 10), start: +date, end: +date + day,
        weekday: (date.getUTCDay() + 6) % 7 + 1, current: i === count - 1,
        status: i >= count - 3 ? 'recorded' : i % 2 ? 'off' : 'unknown',
        effectiveSeconds: i >= count - 3 ? 3600 : 0, gapSeconds: i === 0 ? 40 : 0,
        starts: i >= count - 3 ? [+date + 1000] : []};
    })});
    // 点格子不能立即导航；再点按钮须原样带回当天边界与场次。
    await check('日历先选后看且按钮打开所选日', () => {
      const input = calendar(7), selected = [];
      const node = ui.createCalendar(host(), input, {onSelect: value => selected.push(value)});
      const cells = [...node.querySelectorAll('.sc-calendar-day')];
      cells[4].click();
      expect(selected.length === 0, '点一天直接打开了场次');
      expect(node.querySelector('.sc-calendar-readout')?.textContent.includes('09/05'), '没有所选日期读数');
      const action = node.querySelector('.sc-calendar-action'); action.click();
      expect(JSON.stringify(selected) === JSON.stringify([{start: input.items[4].start, end: input.items[4].end, starts: input.items[4].starts}]), '按钮打开的不是所选日期');
      cells[1].click(); expect(action.disabled, '没场次的日期按钮仍可用');
      action.click(); expect(selected.length === 1, '没场次仍打开了场次');
    });
    // 三种合计须逐项对应实际格子，不用总天数替代。
    await check('日历三态合计与格子一致', () => {
      const node = ui.createCalendar(host(), calendar(30));
      const totals = node.querySelector('.sc-calendar-totals');
      expect(totals?.children.length === 3, '缺少三种状态的天数合计');
      for (const status of ['recorded', 'off', 'unknown']) {
        const count = node.querySelectorAll('.sc-calendar-day.sc-calendar-' + status).length;
        expect(totals.querySelector('[data-status="' + status + '"] strong')?.textContent === count + '天', status + ' 合计与格子数不符');
      }
    });
    // 长范围初次打开应看到最近日期，不能停在最早一端。
    for (const count of [180, 360]) await check('长日历最近日期可见 ' + count + '天', async () => {
      const node = ui.createCalendar(host(300), calendar(count));
      const scroll = node.querySelector('.sc-calendar-scroll'), cell = node.querySelector('.sc-calendar-day[data-date="' + calendar(count).items.at(-1).date + '"]');
      await waitFor(() => {
        const outer = scroll.getBoundingClientRect(), inner = cell.getBoundingClientRect();
        return inner.left >= outer.left && inner.right <= outer.right;
      }, '打开后最近日期不在可视区');
      expect(scroll.scrollLeft > 0, '长日历仍停在最早一端');
    });
    lines.push('跑了 ' + checks + ' 格，失败 ' + failures + ' 格');
    return {lines, failures};
  }).toString()})()`}, sessionId);
  assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
  process.stdout.write(result.result.value.lines.join('\n') + '\n');
  process.exitCode = result.result.value.failures ? 1 : 0;
} finally {
  try { await command('Browser.close'); } catch {
    browser.kill('SIGTERM');
  } finally {
    await exited;
    await rm(profile, {recursive: true, force: true});
  }
}
