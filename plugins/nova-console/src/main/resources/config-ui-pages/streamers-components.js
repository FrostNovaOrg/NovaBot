/**
 * 主播详情的展示组件。时间范围、统计值与比较文字均由调用方提供。
 * 页面插入 COMPONENT_STYLE；离开时调用 releaseComponents，单个图表也可 destroy。
 */
export const COMPONENT_STYLE = `
.sc-card{min-width:0;padding:16px;margin-bottom:16px;background:var(--surface);color:var(--text);border:1px solid var(--line);border-radius:var(--r-card);box-shadow:var(--shadow);font-size:15px;line-height:1.55}
.sc-card h3{font-size:17px;margin:0 0 14px}
.sc-section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.sc-section-head h3{margin-bottom:6px}
.sc-section-hint{margin:0 0 10px;color:var(--dim);font-size:13px}
.sc-kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.sc-kpi{display:flex;flex-direction:column;min-width:0;border:1px solid var(--line);border-radius:var(--r-ctl);padding:14px}
.sc-kpi-label{font-size:13px;color:var(--dim);margin-bottom:7px}
.sc-kpi-value{font-size:23px;font-weight:650;line-height:1.25;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.sc-kpi-delta{font-size:12px;min-height:1.55em;margin-top:6px;color:var(--dim)}
.sc-kpi-delta.sc-up{color:var(--ok)}
.sc-kpi-delta.sc-down{color:var(--err)}
.sc-chart{position:relative;min-width:0;color:var(--text)}
.sc-chart-plot{display:grid;grid-template-columns:48px minmax(0,1fr);column-gap:8px}
.sc-chart-scale{display:flex;flex-direction:column;justify-content:space-between;padding:14px 0 26px;text-align:right;color:var(--dim);font-size:11px;font-variant-numeric:tabular-nums}
.sc-chart svg{display:block;width:100%;height:180px;overflow:visible;touch-action:pan-y}
.sc-chart .sc-grid{stroke:var(--line);stroke-width:1}
.sc-chart .sc-zero{stroke:var(--dim);stroke-width:1}
.sc-chart .sc-line{fill:none;stroke:var(--accent);stroke-width:2.5;vector-effect:non-scaling-stroke}
.sc-chart .sc-point{fill:var(--accent);stroke:var(--surface);stroke-width:1.5}
.sc-chart .sc-bar{fill:var(--accent)}
.sc-chart [role="button"]{cursor:pointer}
.sc-chart [role="button"]:focus-visible{outline:2px solid var(--text);outline-offset:2px}
.sc-chart-labels{position:relative;height:22px;margin-left:56px;color:var(--dim);font-size:12px}
.sc-chart-labels span{position:absolute;top:0;transform:translateX(-50%);white-space:nowrap;font-variant-numeric:tabular-nums}
.sc-chart-labels span:first-child{transform:none}
.sc-chart-labels span:last-child{transform:translateX(-100%)}
.sc-chart-footer{display:flex;align-items:center;gap:10px 16px;flex-wrap:wrap;min-height:36px;margin-top:5px}
.sc-chart-action{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;margin:0;font-size:12px;min-height:36px}.sc-point[aria-current=true]{stroke:var(--text);stroke-width:3}
.sc-chart-readout{min-height:26px;padding:5px 0;color:var(--text);font-size:13px;font-variant-numeric:tabular-nums}
.sc-chart-note,.sc-empty{color:var(--dim);font-size:12px;margin:8px 0}
.sc-calendar{min-width:0;color:var(--text);margin-top:2px}
.sc-calendar-scroll{overflow:auto;max-width:100%;padding:2px 0 7px;overscroll-behavior-x:contain}
.sc-calendar-layout{--sc-cell:20px;--sc-gap:3px;display:grid;grid-template-columns:14px minmax(0,1fr);gap:4px 5px;width:max-content;margin-inline:auto;min-width:calc(19px + var(--sc-cols) * (var(--sc-cell) + var(--sc-gap)) - var(--sc-gap))}
.sc-calendar-months,.sc-calendar-row{display:grid;grid-template-columns:repeat(var(--sc-cols),var(--sc-cell));column-gap:var(--sc-gap)}
.sc-calendar-months{min-height:17px;font-size:11px;color:var(--dim)}
.sc-calendar-months span{white-space:nowrap}
.sc-calendar-weekdays{display:grid;grid-template-rows:repeat(7,var(--sc-cell));row-gap:var(--sc-gap);align-items:center;text-align:center;font-size:10px;color:var(--dim);position:sticky;left:0;background:var(--surface);z-index:2;isolation:isolate}
.sc-calendar-weekdays::before{content:"";position:absolute;inset:-2px -5px -2px -2px;background:var(--surface);z-index:-1;pointer-events:none}
.sc-calendar-days{display:grid;gap:var(--sc-gap)}
.sc-calendar-day,.sc-calendar-blank{height:var(--sc-cell);width:100%;min-width:0;box-sizing:border-box}
.sc-calendar-day{position:relative;border:1px solid var(--line);border-radius:3px;padding:0;background:var(--ground);font:inherit;color:var(--text);font-size:11px}
.sc-calendar-recorded{background:color-mix(in srgb,var(--accent) var(--sc-intensity,68%),var(--ground));border-color:color-mix(in srgb,var(--accent) 65%,var(--line))}
.sc-calendar-off{background:var(--ground);border-color:var(--line)}
.sc-calendar-unknown{background:color-mix(in srgb,var(--dim) 23%,var(--ground));border-color:color-mix(in srgb,var(--dim) 45%,var(--line))}
.sc-calendar-day.sc-calendar-recorded{cursor:pointer}
.sc-calendar-day:focus-visible{outline:2px solid var(--accent);outline-offset:-2px}
.sc-calendar-day[aria-current="date"]{box-shadow:inset 0 0 0 1px var(--text)}
.sc-calendar-today{position:absolute;right:0;bottom:0;width:4px;height:4px;background:var(--text);border-radius:50%;overflow:hidden;font-size:0}
.sc-calendar-legend{display:flex;gap:4px;align-items:center;justify-content:flex-end;flex-wrap:wrap;font-size:10px;color:var(--dim);margin:5px 0 8px}
.sc-calendar-legend-item{display:inline-flex;align-items:center;gap:3px;margin-left:5px}
.sc-calendar-legend i{width:10px;height:10px;border:1px solid var(--line);border-radius:2px}
.sc-calendar-history{font-size:11px;color:var(--dim)}
.sc-calendar-month{--sc-cell:32px;--sc-gap:4px;grid-template-columns:minmax(0,1fr);width:100%;min-width:0}
.sc-calendar-month .sc-calendar-row,.sc-calendar-daynames{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));column-gap:var(--sc-gap)}
.sc-calendar-daynames{position:sticky;top:0;z-index:2;min-height:20px;align-items:center;text-align:center;background:var(--surface);color:var(--dim);font-size:11px}
.sc-calendar-short{grid-template-columns:minmax(0,1fr);width:100%;min-width:0}
.sc-calendar-short .sc-calendar-row{grid-template-columns:repeat(var(--sc-cols),minmax(28px,1fr));gap:4px}
.sc-calendar-short .sc-calendar-day{height:46px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;font-size:11px}
.sc-calendar-short .sc-calendar-day small{font-size:10px}
.sc-audience{display:grid;grid-template-columns:154px minmax(0,1fr);align-items:center;gap:16px;margin:6px 0 16px;color:var(--text)}
.sc-audience-ring{display:grid;width:154px;height:154px}
.sc-audience-ring>svg,.sc-audience-label{grid-area:1/1}
.sc-audience-ring>svg{width:100%;height:100%;display:block}
.sc-audience-ring circle{fill:none;stroke-width:14}
.sc-audience-track{stroke:color-mix(in srgb,var(--dim) 15%,var(--surface))}
.sc-audience-first{stroke:color-mix(in srgb,var(--accent) 84%,var(--dim))}
.sc-audience-returning{stroke:color-mix(in srgb,var(--accent2) 74%,var(--dim))}
.sc-audience-label{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;min-width:0}
.sc-audience-label small{color:var(--dim);font-size:11px}
.sc-audience-label strong{font-size:25px;line-height:1.3;font-variant-numeric:tabular-nums}
.sc-audience-label strong small,.sc-audience-segment strong small{font-size:11px;color:var(--dim);font-weight:400}
.sc-audience-composition{display:grid;gap:9px;min-width:0}
.sc-audience-segment{display:flex;flex-direction:column;gap:7px;padding:10px 11px;border:1px solid var(--line);background:var(--ground);border-radius:var(--r-ctl);min-width:0}
.sc-audience-segment span{display:flex;gap:7px;align-items:center;color:var(--dim);font-size:12px}
.sc-audience-segment i{width:7px;height:7px;border-radius:50%;background:color-mix(in srgb,var(--accent) 84%,var(--dim))}
.sc-audience-segment.sc-returning i{background:color-mix(in srgb,var(--accent2) 74%,var(--dim))}
.sc-audience-segment strong{font-size:19px;line-height:1.2;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.sc-drawer{position:fixed;inset:0 0 0 auto;display:none;margin:0;width:min(820px,92vw);max-width:none;height:100dvh;max-height:none;padding:0;border:0;border-left:1px solid var(--line);background:var(--ground);color:var(--text);box-shadow:var(--shadow);overflow:hidden;font-family:var(--font)}
.sc-drawer[open]{display:flex;flex-direction:column}
.sc-drawer::backdrop{background:var(--overlay)}
.sc-drawer-head{display:flex;flex:0 0 auto;justify-content:space-between;align-items:center;gap:12px;padding:16px 20px;border-bottom:1px solid var(--line);background:var(--surface)}
.sc-drawer-head h2{font-size:18px;margin:0}
.sc-drawer-close{flex-shrink:0;white-space:nowrap}
.sc-drawer-body{padding:20px;overflow:auto;min-height:0;overscroll-behavior:contain}
.sc-drawer-body .sc-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}
@media(max-width:1000px){.sc-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.sc-kpi-value{font-size:21px}}
@media(max-width:1150px) and (min-width:901px),(max-width:420px){.sc-audience{grid-template-columns:132px minmax(0,1fr);gap:12px}.sc-audience-ring{width:132px;height:132px}.sc-audience-label strong{font-size:23px}.sc-audience-segment{padding:9px}.sc-audience-segment strong{font-size:17px}}
@media(max-width:560px){.sc-calendar-layout{--sc-cell:30px;--sc-gap:4px}.sc-calendar-month{--sc-cell:34px}.sc-card{padding:14px;font-size:14px}.sc-card h3{font-size:16px}.sc-kpis{gap:8px}.sc-kpi{padding:11px}.sc-kpi-value{font-size:20px}.sc-section-head{align-items:center}.sc-drawer{width:100vw;border:0}.sc-drawer-head{padding:12px 14px}.sc-drawer-close{min-height:42px}.sc-drawer-body{padding:14px}}
`;

const charts = new Set();
let activeDrawer = null;
let drawerId = 0;
const finite = value => typeof value === 'number' && Number.isFinite(value);
const display = value => value == null ? '—' : String(value);
const chartNumber = value => finite(value) ? new Intl.NumberFormat('zh-CN', {maximumFractionDigits: 1}).format(value) : '—';
function element(tag, className = '', text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text != null) node.textContent = String(text);
  return node;
}
function svgElement(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.classList.add(...value.split(' '));
    else node.setAttribute(key, String(value));
  }
  return node;
}

/** points: {start,end,label,value,status?}[]；period 为 day/week/month，按日先选择再查看场次，周月直接下钻；onSelect 收到 {start,end}。 */
export function createChart(host, {
  points = [], title = '趋势', period = 'day', hideOff = false,
  isOff = point => point.status === 'off', format = chartNumber, onSelect,
  canSelect = point => !Number.isFinite(point.sessions) || point.sessions > 0,
} = {}) {
  const deferred = period === 'day' && typeof onSelect === 'function';
  const shown = period === 'day' && hideOff ? points.filter(point => !isOff(point) && finite(point.value)) : [...points];
  const samples = shown.map(point => point.value).filter(finite);
  const min = Math.min(0, ...samples);
  const max = Math.max(0, ...samples) || (min === 0 ? 1 : 0);
  const span = max - min || 1;
  const wrap = element('div', 'sc-chart');
  if (deferred && !samples.length) {
    wrap.append(element('p', 'sc-empty', '暂无有效图表数据')); host.append(wrap);
    return {element: wrap, svg: null, destroy() {}};
  }
  const plot = element('div', 'sc-chart-plot');
  const scale = element('div', 'sc-chart-scale');
  scale.setAttribute('aria-hidden', 'true');
  const svg = svgElement('svg', {role: 'group', 'aria-label': title + (deferred ? '；用左右键选择日期，再按查看当天场次' : ''), preserveAspectRatio: 'xMidYMid meet'});
  const labels = element('div', 'sc-chart-labels');
  const readout = element('div', 'sc-chart-readout');
  readout.setAttribute('role', 'status');
  const footer = element('div', 'sc-chart-footer'); footer.append(readout);
  plot.append(scale, svg); wrap.append(plot, labels, footer); host.append(wrap);

  if (!samples.length) wrap.append(element('p', 'sc-empty', '暂无有效图表数据'));
  let destroyed = false;
  const select = point => { if (point && canSelect(point)) onSelect?.({start: point.start, end: point.end}); };
  const describe = point => `${point.label ?? ''} · ${format(point.value)}`;
  let selectedIndex = -1;
  const action = deferred ? element('button', 'nv-btn2 sc-chart-action', '查看当天场次 →') : null;
  if (action) {
    action.type = 'button'; action.disabled = true;
    action.setAttribute('aria-label', '查看当天场次：请先选择日期');
    action.addEventListener('click', () => select(shown[selectedIndex])); footer.append(action);
  }
  if (deferred) readout.textContent = '点击图表选择日期';
  const choose = index => {
    if (!shown.length) return;
    selectedIndex = Math.max(0, Math.min(shown.length - 1, index));
    readout.textContent = describe(shown[selectedIndex]);
    if (action) {
      action.disabled = !canSelect(shown[selectedIndex]);
      action.setAttribute('aria-label', '查看当天场次：' + shown[selectedIndex].label);
    }
    for (const mark of svg.children) if (mark.dataset.index != null)
      mark.setAttribute('aria-current', String(Number(mark.dataset.index) === selectedIndex));
  };
  const move = event => {
    if (!deferred) return;
    if (event.key === 'Enter') {
      event.preventDefault(); event.stopPropagation?.();
      if (selectedIndex >= 0 && !action.disabled) action.focus();
      return;
    }
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    event.preventDefault(); event.stopPropagation?.();
    choose(selectedIndex < 0 ? 0 : selectedIndex + (event.key === 'ArrowLeft' ? -1 : 1));
  };
  if (deferred) { svg.setAttribute('tabindex', '0'); svg.addEventListener('keydown', move); }

  const draw = (viewportWidth, viewportHeight) => {
    if (destroyed) return;
    svg.setAttribute('viewBox', `0 0 ${viewportWidth} ${viewportHeight}`);
    svg.replaceChildren(); scale.replaceChildren(); labels.replaceChildren();
    const left = 20, top = 14;
    const width = Math.max(1, viewportWidth - 40), height = Math.max(1, viewportHeight - 40);
    const y = value => top + height - (value - min) / span * height;
    const start = shown[0]?.start ?? 0, end = shown.at(-1)?.start ?? start;
    const x = index => left + (period !== 'day' ? (index + .5) / shown.length
      : shown.length < 2 ? .5 : hideOff || end === start ? index / (shown.length - 1)
        : (shown[index].start - start) / (end - start)) * width;
    if (samples.length) {
      for (let i = 0; i < 4; i++) {
        const value = max - i * span / 3;
        scale.append(element('span', '', format(value)));
        svg.append(svgElement('line', {x1: left, x2: left + width, y1: y(value), y2: y(value), class: 'sc-grid'}));
      }
      svg.append(svgElement('line', {x1: left, x2: left + width, y1: y(0), y2: y(0), class: 'sc-zero'}));
    }
    if (period === 'day') {
      let path = '', connected = false;
      for (let i = 0; i < shown.length; i++) {
        if (!finite(shown[i].value)) { connected = false; continue; }
        path += `${connected ? 'L' : 'M'}${x(i)},${y(shown[i].value)} `;
        connected = true;
      }
      svg.append(svgElement('path', {d: path.trim(), class: 'sc-line'}));
    }
    shown.forEach((point, i) => {
      if (!finite(point.value)) return;
      const step = width / Math.max(1, shown.length);
      const mark = period === 'day'
        ? svgElement('circle', {cx: x(i), cy: y(point.value), r: shown.length < 25 ? 3 : 1.7, class: 'sc-point'})
        : svgElement('rect', {x: left + i * step + Math.min(3, step / 4), y: Math.min(y(point.value), y(0)),
          width: Math.max(.5, step - Math.min(6, step / 2)), height: Math.max(2, Math.abs(y(0) - y(point.value))), class: 'sc-bar'});
      mark.setAttribute('aria-label', describe(point));
      const tip = svgElement('title'); tip.textContent = describe(point); mark.append(tip);
      mark.dataset.index = String(i);
      if (period === 'day') mark.setAttribute('aria-current', String(i === selectedIndex));
      const preview = () => { if (!deferred) readout.textContent = describe(point); };
      mark.addEventListener('pointerenter', preview);
      mark.addEventListener('focus', preview);
      if (onSelect && (period === 'day' || canSelect(point))) {
        mark.setAttribute('role', 'button');
        if (!deferred) mark.setAttribute('tabindex', '0');
        mark.addEventListener('click', () => period === 'day' ? choose(i) : select(point));
        mark.addEventListener('keydown', event => {
          if (deferred && ['ArrowLeft', 'ArrowRight', 'Enter'].includes(event.key)) { move(event); return; }
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation?.(); period === 'day' ? choose(i) : select(point); }
        });
      }
      svg.append(mark);
    });
    const count = Math.min(shown.length, viewportWidth < 420 ? 3 : 5);
    const ticks = new Set(Array.from({length: count}, (_, i) => Math.round(i * (shown.length - 1) / Math.max(1, count - 1))));
    for (const i of ticks) {
      const label = element('span', '', shown[i].label);
      label.style.left = `${x(i) / viewportWidth * 100}%`; labels.append(label);
    }
  };
  const redraw = rect => {
    if (rect.width > 0 && rect.height > 0) draw(rect.width, rect.height);
  };
  const bounds = svg.getBoundingClientRect();
  draw(bounds.width || 720, bounds.height || 180);
  const observer = new ResizeObserver(entries => {
    for (const entry of entries) if (entry.target === svg) redraw(entry.contentRect);
  });
  observer.observe(svg);
  const chart = {element: wrap, svg, destroy() {
    if (destroyed) return;
    destroyed = true;
    observer.disconnect(); charts.delete(chart);
  }};
  charts.add(chart);
  return chart;
}

/** calendar 直接使用接口的 {provableFrom,items}；onSelect 收到 {start,end,starts}。 */
export function createCalendar(host, calendar, {onSelect} = {}) {
  const wrap = element('div', 'sc-calendar'); host.append(wrap);
  const items = calendar?.items || [];
  if (!items.length) { wrap.append(element('p', 'sc-empty', '暂无开播日历数据')); return wrap; }
  const short = items.length <= 8, month = !short && items.length <= 35;
  const offset = items[0].weekday - 1;
  const weeks = Math.ceil((offset + items.length) / 7);
  const columns = short ? items.length : month ? 7 : weeks;
  const rows = short ? 1 : month ? weeks : 7;
  const scroll = element('div', 'sc-calendar-scroll');
  scroll.tabIndex = 0; scroll.setAttribute('role', 'region'); scroll.setAttribute('aria-label', '开播日历，可滚动查看日期');
  const layout = element('div', `sc-calendar-layout${short ? ' sc-calendar-short' : month ? ' sc-calendar-month' : ''}`);
  layout.style.setProperty('--sc-cols', String(columns));
  const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  if (!short) {
    const weekdays = element('div', month ? 'sc-calendar-daynames' : 'sc-calendar-weekdays');
    weekdays.setAttribute('aria-hidden', 'true');
    names.forEach((name, index) => weekdays.append(element('span', '', month ? name : [0, 2, 4, 6].includes(index) ? name.slice(1) : '')));
    if (!month) {
      const spacer = element('span'); spacer.setAttribute('aria-hidden', 'true');
      const months = element('div', 'sc-calendar-months'); months.setAttribute('aria-hidden', 'true');
      let previous = '';
      items.forEach((item, i) => {
        const current = item.date.slice(0, 7);
        if (current === previous) return;
        previous = current;
        const name = element('span', '', `${Number(current.slice(5))}月`);
        name.style.gridColumn = String(Math.floor((offset + i) / 7) + 1); months.append(name);
      });
      layout.append(spacer, months);
    }
    layout.append(weekdays);
  }
  const grid = element('div', 'sc-calendar-days');
  grid.setAttribute('role', 'group'); grid.setAttribute('aria-label', '每日开播记录');
  const maxSeconds = Math.max(1, ...items.map(item => finite(item.effectiveSeconds) ? item.effectiveSeconds : 0));
  const buttons = new Map();
  for (let rowIndex = 0; rowIndex < rows; rowIndex++) {
    const row = element('div', 'sc-calendar-row');
    for (let column = 0; column < columns; column++) {
      const index = short ? column : month ? rowIndex * 7 + column - offset : column * 7 + rowIndex - offset;
      const item = items[index];
      if (!item) { const blank = element('span', 'sc-calendar-blank'); blank.setAttribute('aria-hidden', 'true'); row.append(blank); continue; }
      const status = ['recorded', 'off'].includes(item.status) ? item.status : 'unknown';
      const button = element('button', `sc-calendar-day sc-calendar-${status}`);
      button.type = 'button'; button.dataset.date = item.date;
      button.style.setProperty('--sc-intensity', `${30 + Math.max(0, item.effectiveSeconds || 0) / maxSeconds * 55}%`);
      const statusText = {recorded: '有直播', off: '确认没播', unknown: '说不准'}[status];
      const text = `${item.date}，${statusText}${status === 'recorded' ? `，有效时长 ${display(item.effectiveSeconds)} 秒` : ''}${item.current ? '，今天' : ''}`;
      button.setAttribute('aria-label', text); button.title = text;
      if (short || month) button.append(element('span', '', `${Number(item.date.slice(5, 7))}/${Number(item.date.slice(8))}`));
      if (short) button.append(element('small', '', names[item.weekday - 1]));
      if (item.current) { button.setAttribute('aria-current', 'date'); button.append(element('small', 'sc-calendar-today', '今天')); }
      button.addEventListener('click', () => {
        if (status === 'recorded') onSelect?.({start: item.start, end: item.end, starts: [...(item.starts || [])]});
      });
      button.addEventListener('keydown', event => {
        const steps = {ArrowLeft: short || month ? -1 : -7, ArrowRight: short || month ? 1 : 7,
          ArrowUp: month ? -7 : -1, ArrowDown: month ? 7 : 1};
        if (!(event.key in steps)) return;
        event.preventDefault(); buttons.get(Math.max(0, Math.min(items.length - 1, index + steps[event.key])))?.focus();
      });
      buttons.set(index, button); row.append(button);
    }
    grid.append(row);
  }
  layout.append(grid); scroll.append(layout); wrap.append(scroll);
  const legend = element('div', 'sc-calendar-legend');
  for (const [status, label] of [['recorded', '有直播（深浅表示有效时长）'], ['off', '确认没播'], ['unknown', '说不准']]) {
    const item = element('span', 'sc-calendar-legend-item');
    const mark = element('i', `sc-calendar-${status}`); mark.setAttribute('aria-hidden', 'true'); item.append(mark, label); legend.append(item);
  }
  if (calendar.provableFrom == null || items.some(item => item.start < calendar.provableFrom)) {
    legend.append(element('span', 'sc-calendar-history', '此前的记录不全，只能显示说不准'));
  }
  wrap.append(legend);
  return wrap;
}

/** 建立卡片；返回节点供调用方追加内容。 */
export function createCard(title, host) {
  const card = element('section', 'sc-card');
  if (title) card.append(element('h3', '', title));
  host?.append(card); return card;
}

/** 分区卡片返回 {box,header}，header 可追加明细入口。 */
export function createSection(title, hint, host) {
  const box = createCard('', host);
  const header = element('div', 'sc-section-head'); header.append(element('h3', '', title)); box.append(header);
  if (hint) box.append(element('p', 'sc-section-hint', hint));
  return {box, header};
}

/** items: {label,value,comparison?,direction?:up|down|neutral}[]，比较内容已经由调用方格式化。 */
export function createKpis(items, host) {
  const grid = element('div', 'sc-kpis');
  for (const item of items || []) {
    const box = element('div', 'sc-kpi');
    const direction = ['up', 'down'].includes(item.direction) ? ` sc-${item.direction}` : '';
    box.append(element('div', 'sc-kpi-label', item.label), element('strong', 'sc-kpi-value', display(item.value)),
      element('div', `sc-kpi-delta${direction}`, item.comparison || ''));
    grid.append(box);
  }
  host?.append(grid); return grid;
}

/** first/returning/total 均为调用方的人数，只计算画弧所需的比例，不推算缺失人数。 */
export function createAudienceRing({first, returning, total, format = display}, host) {
  const wrap = element('div', 'sc-audience');
  const ring = element('div', 'sc-audience-ring');
  const svg = svgElement('svg', {viewBox: '0 0 154 154', 'aria-hidden': 'true'});
  svg.append(svgElement('circle', {cx: 77, cy: 77, r: 62, class: 'sc-audience-track'}));
  const circumference = 2 * Math.PI * 62;
  let offset = 0;
  const composition = element('div', 'sc-audience-composition');
  for (const [key, label, value] of [['first', '首次记录', first], ['returning', '回访', returning]]) {
    if (finite(value) && value >= 0 && finite(total) && total > 0) {
      const length = Math.min(1 - offset, value / total) * circumference;
      svg.append(svgElement('circle', {cx: 77, cy: 77, r: 62, class: `sc-audience-arc sc-audience-${key}`,
        'stroke-dasharray': `${length} ${circumference - length}`, 'stroke-dashoffset': -offset * circumference, transform: 'rotate(-90 77 77)'}));
      offset += length / circumference;
    }
    const segment = element('div', `sc-audience-segment sc-${key}`);
    const name = element('span'); const dot = element('i'); dot.setAttribute('aria-hidden', 'true'); name.append(dot, label);
    const count = element('strong', '', format(value));
    if (finite(value)) count.append(element('small', '', '人'));
    segment.append(name, count); composition.append(segment);
  }
  const caption = element('div', 'sc-audience-label');
  const count = element('strong', '', format(total));
  if (finite(total)) count.append(element('small', '', '人'));
  caption.append(element('small', '', '本期观众'), count);
  ring.append(svg, caption); wrap.append(ring, composition); host?.append(wrap); return wrap;
}

/** 原生模态抽屉，返回 {dialog,body,close}；content 为可选 DOM 节点。 */
export function openDrawer({title, content, opener = document.activeElement} = {}) {
  if (activeDrawer?.dialog.contains(opener)) opener = activeDrawer.opener;
  activeDrawer?.close();
  const dialog = element('dialog', 'sc-drawer');
  const heading = element('h2', '', title); heading.id = `sc-drawer-title-${++drawerId}`;
  dialog.setAttribute('aria-labelledby', heading.id);
  const header = element('div', 'sc-drawer-head');
  const button = element('button', 'nv-btn2 sc-drawer-close', '关闭'); button.type = 'button';
  button.setAttribute('aria-label', `关闭${title || '明细'}`);
  header.append(heading, button);
  const body = element('div', 'sc-drawer-body');
  if (content) body.append(content);
  dialog.append(header, body); document.body.append(dialog);
  const oldOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
  let released = false;
  const cleanup = () => {
    if (released) return;
    released = true;
    for (const chart of charts) if (dialog.contains(chart.element)) chart.destroy();
    dialog.remove();
    document.body.style.overflow = oldOverflow;
    if (activeDrawer?.dialog === dialog) activeDrawer = null;
    if (opener?.isConnected) opener.focus({preventScroll: true});
  };
  const close = () => { if (dialog.open) dialog.close(); cleanup(); };
  button.addEventListener('click', close);
  dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
  dialog.addEventListener('close', cleanup);
  activeDrawer = {dialog, body, opener, close};
  dialog.showModal(); button.focus({preventScroll: true});
  return {dialog, body, close};
}

/** 页面 leave 调用；反复调用安全。 */
export function releaseComponents() {
  activeDrawer?.close();
  for (const chart of charts) chart.destroy();
}
