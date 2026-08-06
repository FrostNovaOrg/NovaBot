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
  const view = $('#ana-view').value;
  const uid = $('#ana-uid').value;
  const query = uid ? '&uid=' + encodeURIComponent(uid) : '';

  // 周期选择器只对汇总视图有意义。逐场流水下藏起来，而不是留着让人以为选了没生效
  const isSessions = view === 'sessions';
  $('#ana-period').style.display = isSessions ? 'none' : '';
  $('#ana-period-label').style.display = isSessions ? 'none' : '';

  try {
    if (isSessions) {
      anaData = await api('/analytics/sessions?limit=0' + query);
      renderSessionList(anaData);
    } else {
      anaData = await api('/analytics?period=' + $('#ana-period').value + query);
      renderAnalytics(anaData);
    }
  } catch (e) {
    say('载入数据分析失败：' + e.message, 'err');
  }
}

function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

// 逐场流水。汇总视图回答「这周比上周如何」，这里回答「上周六那场到底怎么了」——
// 被切流、只播了十分钟这类事情在周汇总里全被平均掉了
function renderSessionList(d) {
  renderAnalyticsStreamers(d.streamers || [], d.uid ? String(d.uid) : '');

  const sessions = d.sessions || [];
  const metrics = d.metrics || [];
  $('#ana-scope').textContent = '共 ' + d.total + ' 场';
  $('#ana-chart').innerHTML = '';

  if (!sessions.length) {
    $('#ana-cards').innerHTML = '';
    $('#ana-table').innerHTML = '<p class="hint">还没有归档任何场次。每场直播结束时会自动记一条。</p>';
    $('#ana-note').textContent = '';
    return;
  }

  const totalSeconds = sessions.reduce((a, s) => a + s.durationSeconds, 0);
  const interrupted = sessions.filter(s => s.interrupted).length;
  $('#ana-cards').innerHTML = [
    ['场次', sessions.length],
    ['总时长', fmtDuration(totalSeconds)],
    ['场均时长', fmtDuration(Math.round(totalSeconds / sessions.length))],
    ['被中断', interrupted + ' 场']
  ].map(([l, n]) => '<div class="card"><div class="n">' + esc(n) + '</div><div class="l">'
    + esc(l) + '</div></div>').join('');

  const multi = new Set(sessions.map(s => s.uid)).size > 1;

  // 逐场表是横向的，指标一多就没法读了。只留至少有一场非零的列——
  // 十几列清一色的 0 除了把真正有数的那几列挤出屏幕之外没有任何作用
  const shown = metrics.filter(m => sessions.some(s => (s.metrics[m.key] || 0) !== 0));
  const hidden = metrics.length - shown.length;

  const head = '<tr><th>开播</th>' + (multi ? '<th>主播</th>' : '') + '<th class="n">时长</th>'
    + shown.map(m => '<th class="n">' + esc(m.name)
        + (m.unit ? '<br><small>' + esc(m.unit) + '</small>' : '') + '</th>').join('')
    + '<th>标题</th></tr>';

  const rows = sessions.map(s => {
    // 结束原因只在非正常时标出：每行都缀一个「主动下播」纯属噪音，
    // 真出事的那几场反而会淹在里面
    const flag = s.interrupted ? ' <b>· ' + esc(s.endReasonText) + '</b>' : '';
    const titles = s.titles || [];
    const title = titles.length ? (titles[titles.length - 1].title || '') : '';
    const changed = s.titleChangeCount ? '（改过 ' + s.titleChangeCount + ' 次）' : '';

    return '<tr' + (s.interrupted ? ' class="empty-row"' : '') + '>'
      + '<td>' + esc(fmtTime(s.startTime)) + flag + '</td>'
      + (multi ? '<td>' + esc(s.uname || s.uid) + '</td>' : '')
      + '<td class="n">' + esc(fmtDuration(s.durationSeconds)) + '</td>'
      + shown.map(m => '<td class="n">' + esc(fmtMetric(s.metrics[m.key] || 0, m)) + '</td>').join('')
      + '<td title="' + esc(title) + '">'
      + esc((title.length > 18 ? title.slice(0, 18) + '…' : title) + changed) + '</td></tr>';
  }).join('');

  $('#ana-table').innerHTML = '<table><thead>' + head + '</thead><tbody>' + rows + '</tbody></table>';

  const notes = [];
  if (d.droppedSessions) {
    notes.push('更早的 ' + d.droppedSessions + ' 场未显示。');
  }
  if (hidden) {
    notes.push('这些场次里全为零的 ' + hidden + ' 项指标未列出。');
  }
  if (interrupted) {
    notes.push('被平台中断的场次已标出：它的时长与营收和正常场次不可比，做趋势判断时应当单独看待。');
  }
  notes.push('4.3.0 之前归档的场次没有结束原因与标题记录，一律显示为正常结束。');
  $('#ana-note').textContent = notes.join('');
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
