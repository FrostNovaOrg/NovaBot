/**
 * 数据分析页：按周月聚合的场次趋势与图表
 */

let anaData = null;

function fmtDuration(seconds) {
  if (!seconds) return '0 分';
  const h = Math.floor(seconds / 3600), m = Math.round((seconds % 3600) / 60);
  return h ? h + ' 时 ' + m + ' 分' : m + ' 分';
}

function fmtMetric(value, metric) {
  if (value === undefined || value === null) return '—';
  return metric.money ? value.toFixed(2) : Math.round(value).toLocaleString('en-US');
}

async function loadAnalytics() {
  const period = $('#ana-period').value;
  const uid = $('#ana-uid').value;
  try {
    anaData = await api('/analytics?period=' + period + (uid ? '&uid=' + encodeURIComponent(uid) : ''));
    renderAnalytics(anaData);
  } catch (e) {
    say('载入数据分析失败：' + e.message, 'err');
  }
}

function renderAnalyticsStreamers(list, keep) {
  const sel = $('#ana-uid');
  sel.innerHTML = '<option value="">全部主播</option>'
    + list.map(s => '<option value="' + esc(s.uid) + '">'
        + esc(s.uname || s.uid) + '（' + s.sessions + ' 场）</option>').join('');
  if (keep) sel.value = keep;
}

function renderAnalytics(d) {
  renderAnalyticsStreamers(d.streamers || [], d.uid ? String(d.uid) : '');

  const buckets = d.buckets || [];
  const metrics = d.metrics || [];
  const periodName = d.period === 'month' ? '月' : '周';

  $('#ana-scope').textContent = '共归档 ' + (d.archive?.count || 0) + ' 场，当前口径 ' + d.sessionCount + ' 场';

  if (!buckets.length) {
    $('#ana-cards').innerHTML = '';
    $('#ana-chart').innerHTML = '';
    $('#ana-table').innerHTML = '<table><tbody><tr><td class="empty">还没有归档数据。'
      + '每场直播下播时会自动追加一条，播过一场之后这里就有内容了</td></tr></tbody></table>';
    $('#ana-note').textContent = '';
    return;
  }

  // 汇总卡片统计的是当前展示的全部周期，与下方表格同一口径
  const total = buckets.reduce((a, b) => ({
    sessions: a.sessions + b.sessions,
    duration: a.duration + b.durationSeconds
  }), {sessions: 0, duration: 0});
  const active = buckets.filter(b => b.sessions > 0).length;

  $('#ana-cards').innerHTML = [
    ['总场次', total.sessions],
    ['总时长', fmtDuration(total.duration)],
    ['场均时长', fmtDuration(total.sessions ? Math.round(total.duration / total.sessions) : 0)],
    ['有播的' + periodName, active + ' / ' + buckets.length]
  ].map(([l, n]) => '<div class="card"><div class="n">' + esc(n) + '</div><div class="l">'
    + esc(l) + '</div></div>').join('');

  $('#ana-chart').innerHTML = chartHtml('每' + periodName + '直播时长', buckets,
    b => b.durationSeconds / 3600, v => v.toFixed(1) + ' 时')
    + (metrics.length
        ? chartHtml('每' + periodName + '弹幕数', buckets,
            b => b.metrics[metrics[0].key] || 0, v => Math.round(v).toLocaleString('en-US'))
        : '');

  // 表格：周期 + 场次 + 时长 + 各指标。指标顺序由插件的指标说明决定
  const head = '<tr><th>' + esc(periodName === '月' ? '月份' : '周') + '</th><th class="n">场次</th>'
    + '<th class="n">时长</th>'
    + metrics.map(m => '<th class="n">' + esc(m.name) + (m.unit ? '<br><small>' + esc(m.unit) + '</small>' : '')
        + '</th>').join('') + '</tr>';

  const rows = buckets.slice().reverse().map(b =>
    '<tr' + (b.sessions ? '' : ' class="empty-row"') + '><td>' + esc(b.label) + '</td>'
    + '<td class="n">' + b.sessions + '</td>'
    + '<td class="n">' + esc(fmtDuration(b.durationSeconds)) + '</td>'
    + metrics.map(m => '<td class="n">' + esc(b.sessions ? fmtMetric(b.metrics[m.key] || 0, m) : '—')
        + '</td>').join('') + '</tr>').join('');

  $('#ana-table').innerHTML = '<table><thead>' + head + '</thead><tbody>' + rows + '</tbody></table>';

  const notes = [];
  if (!d.metricsKnown) {
    notes.push('未找到该平台的指标说明，只能统计场次与时长。');
  }
  if (d.droppedPeriods) {
    notes.push('更早的 ' + d.droppedPeriods + ' 个' + periodName + '未显示。');
  }
  notes.push('时长与各项指标按开播时刻归入周期，跨零点的直播整场算在开播那一天。');
  // 一项指标都没展示时，这句「哪些指标不参与累加」纯属噪音
  if (metrics.length) {
    notes.push('开播时的粉丝数等「快照」指标不参与累加——把十场的粉丝数加起来不是任何一个真实数字。');
  }
  $('#ana-note').textContent = notes.join('');
}

// 柱状图。画之前先算好最大值，全零时不缩放（否则 0/0 出 NaN，整张图消失）。
//
// 柱子用 SVG（preserveAspectRatio="none" 才能横向铺满），但**文字一律放在 SVG 外面**：
// 同一个属性会把文字一起横向拉伸，宽屏上只是略胖，窄屏上会挤成一团认不出来。
function chartHtml(title, buckets, pick, fmt) {
  const values = buckets.map(pick);
  const max = Math.max(...values, 0);
  const h = 100, bw = 1000 / buckets.length;

  const bars = values.map((v, i) => {
    const bh = max > 0 ? h * (v / max) : 0;
    // 零值也留 2px 的痕迹，否则「这周没播」和「这周没有这项数据」在图上长得一样
    return '<rect class="bar' + (v ? '' : ' zero') + '" x="' + (i * bw + bw * 0.15).toFixed(1)
      + '" y="' + (h - Math.max(bh, v ? 1 : 2)).toFixed(1) + '" width="' + (bw * 0.7).toFixed(1)
      + '" height="' + Math.max(bh, v ? 1 : 2).toFixed(1) + '"><title>'
      + esc(buckets[i].label + '：' + fmt(v)) + '</title></rect>';
  }).join('');

  const peak = values.indexOf(max);
  const peakLabel = max > 0
    ? '<span class="peak" style="left:' + ((peak + 0.5) / buckets.length * 100).toFixed(2) + '%">'
      + esc(fmt(max)) + '</span>'
    : '';

  // 横轴只标首尾，柱子一多全标会糊成一片；具体数值靠悬停看 tooltip
  return '<div class="chart"><h4>' + esc(title) + '</h4>'
    + '<div class="plot"><svg viewBox="0 0 1000 ' + h + '" preserveAspectRatio="none">' + bars
    + '</svg>' + peakLabel + '</div>'
    + '<div class="xlab"><span>' + esc(buckets[0].label) + '</span><span>'
    + esc(buckets[buckets.length - 1].label) + '</span></div></div>';
}
