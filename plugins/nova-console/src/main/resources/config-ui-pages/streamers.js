/**
 * 主播页：列表、详情总览与明细抽屉、某一场
 *
 * 本文件随控制台插件走，由控制台按注册清单装载，落在顶级导航。
 * 三个导出就是与控制台之间的全部约定：render 建出页容器里的三块、
 * refresh 按地址栏取数、leave 离开本页时停止直播刷新并撤掉报告图的临时地址。
 *
 * 本文件只管把东西摆上屏幕。地址栏怎么读怎么拼、状态标写哪几个字、折线与柱图的几何、
 * 两种缺口怎么分开说、快照那些裸键换成什么人话——
 * 一律在 streamers-model.js 里判。
 *
 * 三块子视图共用一个页容器，哪一块显示只由地址栏定（syncStreamersView）——
 * 刷新、收藏、后退三种进法走的都是这一条路，各写一套的话，
 * 从地址栏直接进某一场与点一行进去会渲染出两种结果。
 */

import {store} from './store.js';
import {$, api, CONSOLE, el, esc, loadingHtml, request, say} from './core.js';
import {COMPONENT_STYLE, createChart, createCalendar, createSection, createKpis, createAudienceRing, openDrawer, releaseComponents} from './streamers-components.js';
import {
  AUDIENCE_COLUMNS, audienceCells, audienceGroups, audienceSort, coverageText, durationCoverage, sessionCoverageText, metricRecorded,
  monitoringDayText, monitoringPeriodText,
  dailyFanChanges, trendCoverageBadge, detailHash, fmtDuration, fmtWholeGap, fmtMetric,
  gapCells, insightsComparison, comparisonExplanation, insightsTime, trendValue, isStreamersHash, pageBar, parseStreamersHash, reportPath,
  reportView, rowSubtitle, seriesValues, sessionHash, sessionTitle,
  slotCell, slotLabel, slotRangeDays, slotTopText,
  snapshotRows, sparkline, statusChip, summaryTotals, totalDataBanner, uncoveredStatuses,
} from './streamers-model.js';

export {detailHash};

const PAGE_STYLE = `
#st-list a.rowcard{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px;color:inherit;text-decoration:none}
#st-list a.rowcard:hover{background:var(--soft);border-color:var(--accent)}
.st-identity{color:var(--faint)}
.st-row-arrow{font-size:var(--fs-xl);color:var(--accent-text)}
.st-spark-scope{font-size:var(--fs-xs);color:var(--dim)}
#st-list a.rowcard:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.st-list-scope{margin:12px 0 0;color:var(--dim);font-size:var(--fs-xs);line-height:1.6}
.st-group-heading{margin:18px 0 4px;padding-top:16px;border-top:1px solid var(--line);font-size:var(--fs-md)}
.st-group-heading:first-child{margin-top:0;padding-top:0;border-top:0}
.st-group-heading small{display:block;margin-top:5px;color:var(--dim);font-size:var(--fs-xs);font-weight:400;line-height:1.6}
#sd-live .live-head{display:flex;align-items:center;justify-content:space-between;gap:12px}
#sd-live .live-head h3{margin:0}
#sd-live .live-times,#sd-live .live-error{font-size:var(--fs-xs);color:var(--dim);margin:10px 0 0}
#sd-live .live-error{color:var(--dim)}
.spark{width:88px;height:26px;flex:none;color:var(--dim)}
.spark.up{color:var(--accent-text)}
a.lgpill{text-decoration:none}
a.lgpill[aria-current="page"]{border-color:var(--accent);color:var(--accent-text);background:var(--soft)}
:is(#sd-body,.sc-drawer) .start-slots>.lgpills+.lgpills,#sd-audience>.lgpills+.lgpills{margin-top:10px}
:is(#sd-body,.sc-drawer) th.n{text-align:left}
:is(#sd-body,.sc-drawer) td.n{text-align:right;font-variant-numeric:tabular-nums}
:is(#sd-body,.sc-drawer) tbody tr:hover{background:var(--soft)}
:is(#sd-body,.sc-drawer) .start-slots{min-width:0;max-width:100%;box-sizing:border-box}
:is(#sd-body,.sc-drawer) .slot-scroll{overflow-x:auto;max-width:100%;margin-top:14px}
:is(#sd-body,.sc-drawer) .slot-grid{border-collapse:collapse;table-layout:fixed;min-width:920px;width:100%;font-size:var(--fs-xs)}
:is(#sd-body,.sc-drawer) .slot-grid th,:is(#sd-body,.sc-drawer) .slot-grid td{padding:3px;text-align:center;font-size:var(--fs-xs)}
:is(#sd-body,.sc-drawer) .slot-grid th:first-child{width:52px;box-sizing:border-box}
:is(#sd-body,.sc-drawer) .slot-grid button{display:block;box-sizing:border-box;width:100%;min-height:64px;padding:8px 4px;border:1px solid var(--line);border-radius:6px;color:inherit;background:transparent;cursor:pointer;font:inherit;white-space:nowrap}
:is(#sd-body,.sc-drawer) .slot-grid button:focus-visible{outline:2px solid var(--accent)}
:is(#sd-body,.sc-drawer) .slot-grid small{display:block;margin-top:6px;font-size:var(--fs-xs);color:var(--dim)}
:is(#sd-body,.sc-drawer) .slot-unit{white-space:nowrap;margin:14px 0 0}
:is(#sd-body,.sc-drawer) .start-slots .lgpills{flex-wrap:wrap}
#sd-audience{min-width:0;max-width:100%;box-sizing:border-box}
#sd-audience .audience-scroll{overflow-x:auto;max-width:100%}
#sd-audience table{min-width:700px}
#sd-audience th,#sd-audience td{white-space:nowrap;height:auto}
#sd-audience th,#sd-audience th a,#sd-audience th a:visited{color:var(--dim);font-size:var(--fs-xs);font-weight:500;text-decoration:none}
#sd-audience td a,#sd-audience td a:visited{color:var(--accent-text)}
.sd-area-table td a,.sd-area-table td a:visited{color:var(--accent-text);font-weight:400;text-decoration:none}
:is(#sd-body,.sc-drawer){min-width:0}
#sx-body img{max-width:100%;height:auto}

.sd-toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:16px 0}
.sd-toolbar .hint{margin:0}
.sd-cumulative{border:1px solid var(--line);border-radius:var(--r-ctl,10px);padding:12px 16px;margin:14px 0;background:var(--surface)}
.sd-scope summary,.sd-cumulative summary,#sd-more summary{cursor:pointer;color:var(--dim)}
details.sd-scope{margin:0;padding:0;border:0}.sd-scope p{margin:10px 0 0;font-size:var(--fs-sm);color:var(--dim)}
.sd-columns{display:grid;grid-template-columns:minmax(0,1.22fr) minmax(0,1fr);gap:16px;margin-top:16px}
.sd-columns>.sc-card{min-width:0;margin:0;display:flex;flex-direction:column}
#sd-trend{scroll-margin-top:82px}
#sd-trend-content{display:flex;flex:1;flex-direction:column}
#sd-trend .sc-chart{margin-top:auto}
#sd-trend .sc-chart svg{height:180px}
.sd-slot-top{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:0;list-style:none}
.sd-slot-top a{display:flex;flex-direction:column;gap:8px;border:1px solid var(--line);border-radius:8px;padding:12px;color:inherit;text-decoration:none}
.sd-slot-top a:hover{border-color:var(--accent)}
.sd-slot-top strong{font-size:var(--fs-xl)}
.sd-slot-top small{color:var(--dim)}
#sd-insights .sc-kpis{grid-template-columns:repeat(3,minmax(0,1fr))}
#sd-insights .sc-kpi[role=button]{cursor:pointer}
#sd-insights .sc-kpi[role=button]:hover{background:var(--soft)}
#sd-insights .sc-kpi:focus-visible{outline:2px solid var(--accent)}
#sd-insights #sd-more .sc-kpi{background:var(--surface)}
#sd-more{margin-top:14px}#sd-more .sc-kpis{margin-top:12px;grid-template-columns:repeat(4,minmax(0,1fr))}
.sd-trend-controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:8px 0 12px}
.sd-trend-controls .lgpills{display:flex;gap:4px;flex-wrap:wrap}
.sd-trend-controls label{display:flex;align-items:center;gap:6px;font-size:var(--fs-xs)}
.sd-trend-controls button[aria-pressed=true]{color:var(--accent-text);border-color:var(--accent);background:var(--soft)}
.sd-trend-controls button:disabled{opacity:.45;cursor:default}
.sd-trend-daily{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:10px 0}.sd-trend-daily strong{font-size:var(--fs-sm)}.sd-trend-note{font-size:var(--fs-xs);color:var(--dim);line-height:1.7}
.sd-trend-hide{font-size:var(--fs-xs);color:var(--dim);display:flex;align-items:center;gap:6px}
.sd-scope,.sd-recording{border:1px solid var(--line);border-radius:var(--r-ctl,8px);padding:10px 13px;margin:0 0 16px;color:var(--dim);font-size:var(--fs-sm)}
.sd-recording summary{cursor:pointer;color:var(--text)}.sd-recording ul{margin:10px 0 0;padding-left:20px}
.sd-cell-note{display:block;font-size:var(--fs-xs);color:var(--dim)}
.sd-kpi-recorded{font-size:var(--fs-xs);font-weight:400;color:var(--dim);white-space:nowrap;margin-left:5px}
.sd-session-quality{text-align:right}.sd-session-quality .sd-cell-note{margin-top:3px;white-space:nowrap}
.sd-trend-coverage{white-space:nowrap;text-align:left}
.sd-trend-table{overflow-x:auto;min-width:0;max-width:100%}.sd-trend-table table{min-width:480px}
.sd-period-link,.sd-period-link:visited{color:var(--accent-text);text-decoration:none}
.sd-trend-table tbody tr[role=link]{cursor:pointer}.sd-trend-table tr:focus-visible{outline:2px solid var(--accent)}
.sd-pagination{display:flex;align-items:center;flex-wrap:wrap;justify-content:flex-end;gap:12px;padding-top:14px;font-size:var(--fs-sm)}
.sd-pagination>span{margin:0 auto 0 0;color:var(--dim)}
.sd-pagination a,.sd-pagination a:visited{color:var(--accent-text);text-decoration:none}
.sd-interaction-bar{height:6px;border-radius:4px;background:var(--line);overflow:hidden;margin:10px 0}
.sd-interaction-bar span{display:block;height:100%;background:var(--accent)}
.sd-current-live{padding:12px;border:1px solid var(--accent);border-radius:10px;background:var(--soft);margin:4px 0 10px}
.sd-current-live[hidden]{display:none}
/* 「正在直播」是「当前直播与最近归档」那一块里嵌的小卡，块标题由外面那一块给，这里只是小卡里的小标题，所以比卡片标题小一档 */
#sd-live .live-head{flex-wrap:wrap;gap:8px}#sd-live .live-head h3{font-size:var(--fs-sm)}
#sd-live .live-head button{margin-left:auto}
.sd-live-badge{font-size:var(--fs-xs);color:var(--accent-text);border:1px solid var(--line);border-radius:6px;padding:2px 6px}
#sd-live .live-times{margin:7px 0 10px;font-size:var(--fs-xs);line-height:1.6}
.sd-live-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px;margin:0}
.sd-live-metrics div{min-width:0}.sd-live-metrics dt{font-size:var(--fs-xs);color:var(--dim);margin-bottom:3px}
.sd-live-metrics dd{margin:0;font-size:var(--fs-md);font-weight:650;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.sd-live-details{margin-top:10px;font-size:var(--fs-xs)}.sd-live-details summary{cursor:pointer;color:var(--accent-text)}
#sd-live .live-status{min-height:4.8em;margin:10px 0 0;font-size:var(--fs-xs);line-height:1.6}
#sd-audience-summary [role=link]{cursor:pointer}#sd-audience-summary [role=link]:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
#sd-live .kv{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:12px 0 0}
#sd-live .kv dt{color:var(--dim)}#sd-live .kv dd{margin:0;text-align:right}
.sd-recent-row{display:grid;gap:5px;padding:9px 0;color:inherit;text-decoration:none}
.sd-recent-row+.sd-recent-row{border-top:1px solid var(--line)}
.sd-recent-row span,.sd-recent-row small{color:var(--dim);font-size:var(--fs-xs)}.sd-recent-row:hover{color:var(--accent-text)}
.sc-drawer table{width:100%;border-collapse:collapse}.sc-drawer th,.sc-drawer td{padding:10px;border-bottom:1px solid var(--line);text-align:left}
.sc-drawer .nv-card{background:var(--surface);padding:16px;border-radius:10px}
.sc-drawer .nv-card h3{font-size:var(--fs-lg)}
.sc-drawer thead th{font-size:var(--fs-xs);font-weight:500;color:var(--dim)}
.sc-drawer .sd-area-compare{border-radius:14px;padding:14px 16px}
.sc-drawer .sd-entry-rhythm{min-width:0;max-width:100%;box-sizing:border-box;border-radius:14px;padding:14px 16px}
.sd-entry-rhythm h4{font-size:var(--fs-sm);font-weight:500;margin:14px 0 8px;color:var(--dim)}
.sd-entry-rhythm .hint{font-size:var(--fs-xs);line-height:1.65}
.sd-entry-details{min-width:0;margin-top:14px;border-top:1px solid var(--line);padding-top:12px}
.sd-entry-details summary{cursor:pointer;font-size:var(--fs-sm);color:var(--dim);overflow-wrap:anywhere}
.sd-entry-scroll{max-width:100%;min-width:0;overflow-x:auto;margin-top:10px;overscroll-behavior-x:contain}
.sd-entry-table{width:100%;min-width:560px;border-collapse:collapse;font-size:var(--fs-xs)}
.sd-entry-table th,.sd-entry-table td{padding:10px 8px;border-bottom:1px solid var(--line);text-align:right;font-variant-numeric:tabular-nums}
.sd-entry-table th{font-weight:500;color:var(--dim)}
.sd-entry-table th:first-child,.sd-entry-table td:first-child{text-align:left}
.sd-entry-table small{display:block;margin-top:3px;font-size:var(--fs-xs);color:var(--dim)}
:is(.sd-area-table td a,.sd-period-link,.sd-pagination a,.sd-clear-filter,.sd-person-link):hover{text-decoration:underline;text-underline-offset:3px}
:is(#sd-body,.sc-drawer) a:focus-visible{outline:2px solid var(--accent);outline-offset:3px;border-radius:3px}
#sd-head .sh-nm{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
#sd-head .sh-nm>.pill{flex-shrink:0}
#sd-banner{padding:9px 13px;border:0;border-left:3px solid var(--accent);background:var(--soft);border-radius:var(--r-ctl);font-size:var(--fs-sm);color:var(--dim);margin:0 0 16px}
#sd-trend .sc-chart-readout{font-size:var(--fs-xs)}
.sd-detail-link{padding:4px 0}
.sc-section-head>.sd-detail-link{flex-shrink:0;text-align:right}
.sd-toolbar .nv-pill,.sd-trend-controls .nv-pill,.sd-slot-controls .nv-pill{padding:5px 12px;font-size:var(--fs-sm);min-height:32px;background:var(--ground);box-sizing:border-box}
.sd-toolbar strong{font-size:var(--fs-sm)}
.sd-meta{display:flex;align-items:baseline;gap:8px;flex-basis:100%;font-size:var(--fs-xs);color:var(--dim)}
.sd-meta .hint{font-size:var(--fs-xs)}
.sd-scope{position:relative;flex-shrink:0}
.sd-scope summary{font-size:var(--fs-xs);color:var(--accent-text);list-style:none;border:1px solid var(--line);border-radius:50%;width:20px;text-align:center}
.sd-scope summary::-webkit-details-marker{display:none}
.sd-scope-content{position:absolute;right:0;top:28px;z-index:5;width:min(600px,calc(100vw - 60px));max-height:420px;overflow:auto;box-sizing:border-box;padding:14px;background:var(--surface);border:1px solid var(--line);border-radius:var(--r-ctl);box-shadow:var(--shadow)}
#sd-more summary{font-size:var(--fs-sm);color:var(--text)}
#sd-insights #sd-more .sc-kpi{padding:10px;min-height:73px;box-sizing:border-box}
#sd-insights #sd-more .sc-kpi-value{font-size:var(--fs-xl)}
#sd-more .sc-kpi-delta:empty{display:none}
.sd-toolbar a[aria-current=page]{border-color:color-mix(in srgb,var(--accent) 25%,var(--surface))}
#sd-insights .sc-kpis{gap:8px}
#sd-insights .sc-kpi{padding:11px 12px;background:var(--ground)}
#sd-insights .sc-kpi-value{font-size:var(--fs-xl);letter-spacing:-.6px;overflow-wrap:normal;word-break:keep-all;white-space:nowrap}
.sd-trend-controls>.hint{margin:0}
.sd-trend-controls select,.sd-slot-controls select,.sd-session-filters input{width:auto;max-width:100%;border:1px solid var(--line);border-radius:var(--r-ctl,8px);background:var(--ground);color:var(--text);padding:7px 10px;font-size:var(--fs-md);min-height:32px;box-sizing:border-box}
.sd-trend-controls select,.sd-slot-controls select{appearance:none;padding-right:30px;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'%3E%3Cpath d='m1 1 5 5 5-5' fill='none' stroke='%23838391' stroke-width='1.5'/%3E%3C/svg%3E");background-position:right 10px center;background-repeat:no-repeat}
.sd-trend-hide input{width:16px;height:16px}
.sd-trend-daily strong{font-weight:400;color:var(--dim)}
.sd-comparison{position:relative;margin-left:auto;color:var(--dim);font-size:var(--fs-xs)}
.sd-comparison summary{cursor:pointer;padding:4px 0}
.sd-comparison-body{position:absolute;z-index:5;right:0;top:100%;width:min(420px,calc(100vw - 60px));max-height:340px;overflow:auto;box-sizing:border-box;background:var(--surface);color:var(--text);border:1px solid var(--line);border-radius:var(--r-ctl,8px);padding:14px;box-shadow:var(--shadow)}
.sd-comparison-body p{margin:0 0 10px}
.sd-comparison-body p:last-child{margin-bottom:0}
.sd-audience-value{display:flex;justify-content:space-between;align-items:baseline;gap:6px}.sd-audience-share{align-self:flex-end;color:var(--dim);font-size:var(--fs-xs)}
.sd-interaction{display:grid;grid-template-columns:minmax(0,1fr) auto;column-gap:10px;padding:12px;border:1px solid var(--line);border-radius:var(--r-ctl,8px);background:var(--ground);text-decoration:none;color:var(--text)}
.sd-interaction-head{grid-column:1 / -1;display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:var(--fs-sm)}
.sd-interaction-head small{display:block;font-size:var(--fs-xs);color:var(--dim);margin-top:4px}
.sd-interaction-head strong{font-size:var(--fs-xl);white-space:nowrap;font-variant-numeric:tabular-nums}
.sd-interaction>small{align-self:center;white-space:nowrap;font-size:var(--fs-xs);color:var(--dim)}
.sd-audience-note{font-size:var(--fs-xs);margin:10px 0 0;line-height:1.7}
.sd-recent{display:flex;flex-direction:column;flex:1;min-width:0}
.sd-recent-row{flex:1}
@media(min-width:901px){#sd-recent{contain:size}.sd-recent-row{flex:none}}
.sd-recent h4{font-size:var(--fs-xs);font-weight:400;color:var(--dim);margin:10px 0 2px}
.sd-recent-row{grid-template-columns:76px minmax(0,1fr) auto;align-items:center;gap:8px}
.sd-recent-time,.sd-recent-main{display:flex;flex-direction:column;gap:5px;min-width:0}
.sd-recent-main>span{font-size:var(--fs-sm);color:var(--text);overflow-wrap:anywhere}
.sd-recent-row small{font-size:var(--fs-xs)}
.sd-gap-badge{display:inline-block;white-space:nowrap;background:var(--soft);color:var(--accent-text)!important;border:0;border-radius:999px;padding:2px 7px;font-size:var(--fs-xs)!important}
.sd-drawer-heading{min-width:0}.sd-drawer-context{font-size:var(--fs-xs);color:var(--dim);line-height:1.6;margin:6px 0 0}
.sd-session-filters,.sd-slot-controls{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px}
.sd-session-filters label,.sd-slot-controls label{display:flex;gap:6px;align-items:center;color:var(--dim);font-size:var(--fs-sm)}
.sd-session-list>h3{font-size:var(--fs-lg)}
.sd-session-row{display:block;padding:13px 0;color:var(--text);text-decoration:none}
.sd-session-row+.sd-session-row{border-top:1px solid var(--line)}
.sd-session-row:hover{background:var(--soft)}
.sd-session-row:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.sd-session-row-head{display:grid;grid-template-columns:155px minmax(0,1fr) 14px;gap:12px;align-items:baseline}
.sd-session-date{font-size:var(--fs-xs);color:var(--dim);font-variant-numeric:tabular-nums}
.sd-session-row-head strong{font-size:var(--fs-md);font-weight:600;overflow-wrap:anywhere}
.sd-session-arrow{font-size:var(--fs-xl);line-height:1;text-align:right;color:var(--accent-text)}
.sd-session-row-body{display:grid;grid-template-columns:minmax(0,1fr) 158px;align-items:center;gap:10px;margin-top:9px}
.sd-session-row-body>.sd-gap-badge{justify-self:end}
.sd-session-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;flex:1;margin:0}
.sd-session-metrics dt{font-size:var(--fs-xs);color:var(--dim)}.sd-session-metrics dd{font-size:var(--fs-sm);margin:2px 0 0;font-variant-numeric:tabular-nums}
.sd-audience-groups{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:12px 0}
.sd-audience-group{display:flex;align-items:center;justify-content:space-between;gap:8px;border:1px solid var(--line);border-radius:var(--r-ctl,8px);padding:9px 10px;background:var(--ground);color:var(--text);text-decoration:none;font-size:var(--fs-xs)}
.sd-audience-group strong{font-size:var(--fs-md);color:var(--text)}
.sd-audience-group[aria-current=page]{border-color:var(--accent);background:var(--soft)}
#sd-audience-summary .sd-ring-link{font-size:var(--fs-xs);color:var(--accent-text)}
#sd-audience .sd-person-link,#sd-audience .sd-person-link:visited{color:var(--accent-text);text-decoration:none;font-size:var(--fs-md)}
#sd-audience .sd-person-link:hover{text-decoration:underline;text-underline-offset:3px}
.sd-scroll-hint{font-size:var(--fs-xs);color:var(--dim);margin:7px 0 0}
.sd-session-note{font-size:var(--fs-xs);color:var(--dim);margin:8px 0 0}
.sd-slot-top a{background:var(--ground);text-align:left}
.sd-slot-title{display:flex;align-items:center;gap:7px;font-size:var(--fs-xs)}
.sd-slot-title>small{display:inline-flex;align-items:center;justify-content:center;width:21px;height:21px;border-radius:50%;background:var(--soft);color:var(--accent-text);flex-shrink:0}
.sd-slot-top strong{font-size:var(--fs-xxl);font-variant-numeric:tabular-nums}
.sd-slot-top strong small{font-size:var(--fs-xs);font-weight:400}
.sd-slot-sample{display:flex;justify-content:space-between;gap:5px;flex-wrap:wrap;color:var(--dim);font-size:var(--fs-xs)}
.sd-slot-sample>span{color:var(--accent-text)}
:is(#sd-body,.sc-drawer) .slot-grid button{background:var(--ground);font-size:var(--fs-xs);min-height:56px}
:is(#sd-body,.sc-drawer) .slot-grid button[data-low=true]{color:var(--dim);font-size:var(--fs-xs)}
.sd-slot-heading{font-size:var(--fs-md);margin:14px 0 4px}
.sd-slot-method{font-size:var(--fs-xs);line-height:1.6;margin:4px 0}
.sd-slot-scope{font-size:var(--fs-xs);margin:6px 0 12px}
@media(max-width:560px){.sd-session-quality{text-align:left}.sd-session-row-head{grid-template-columns:minmax(0,1fr) 14px;gap:5px 12px}.sd-session-date,.sd-session-row-head strong{grid-column:1}.sd-session-arrow{grid-column:2;grid-row:1 / span 2}.sd-session-row-body{grid-template-columns:minmax(0,1fr);gap:8px}.sd-session-metrics{width:100%;grid-template-columns:repeat(2,minmax(0,1fr))}.sd-audience-groups{grid-template-columns:repeat(2,minmax(0,1fr))}#sd-insights .sc-kpi-value{font-size:var(--fs-xl);white-space:nowrap}.sd-recent-row{grid-template-columns:76px minmax(0,1fr);gap:8px}.sd-recent-row>.sd-gap-badge{grid-column:2;justify-self:start;margin-top:-7px}.sd-comparison{flex-shrink:0}}
@media(min-width:1280px){#sd-insights-content>.sc-kpis{grid-template-columns:repeat(6,minmax(0,1fr))}}
@media(max-width:900px){.sd-columns{grid-template-columns:minmax(0,1fr)}}
@media(max-width:560px){.sd-slot-top{grid-template-columns:1fr}#sd-insights .sc-kpis,#sd-more .sc-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}#sd-trend .sc-chart svg{height:180px}.sd-toolbar{gap:4px}.sd-toolbar .nv-pill{padding:5px 9px;white-space:nowrap}.sd-toolbar .hint{width:100%;margin:4px 0 0}}
`;

/**
 * 把主播页自己的样式注入一次。附属脚本口只收 .js，样式进不了 assets。
 */
function ensureStyle() {
  if (document.getElementById('streamers-page-style')) return;
  const style = document.createElement('style');
  style.id = 'streamers-page-style';
  style.textContent = COMPONENT_STYLE + PAGE_STYLE;
  document.head.appendChild(style);
}

/**
 * 建出列表、详情、某一场三块外壳。控制台只给一个空的 section#page-streamers。
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  ensureStyle();
  section.innerHTML = `
    <div id="sv-list">
      <div class="nv-card hcard">
        <h3>全部主播 <span class="h-note" id="st-window"></span></h3>
        <div class="stats" id="st-all"></div>
        <p class="st-list-scope">在册人数包含停用主播；场次与时长仅汇总启用主播。统计日为 06:00—次日 06:00。</p>
      </div>
      <div class="nv-card hcard">
        <h3>主播列表 <span class="h-note">右侧折线为最近几个统计日的场次</span></h3>
        <div id="st-list"></div>
      </div>
      <div id="st-unlisted"></div>
    </div>
    <div id="sv-detail" style="display:none">
      ${store.singleStreamer ? '<div id="sd-member-home">'
        + (store.singleStreamer.status === 'DISABLED' ? '<p class="note" id="sd-disabled">这位主播已停用，请联系管理员</p>' : '')
        + '</div>' : `<div class="loghead">
        <div>
          <a class="lnkbtn" id="sd-back" href="#/streamers">‹ 返回主播列表</a>
        </div>
      </div>`}
      <div class="nv-card hcard" id="sd-head"></div>
      <div id="sd-body"></div>
    </div>
    <div id="sv-session" style="display:none">
      <div class="loghead">
        <div>
          <a class="lnkbtn" id="sx-back" href="#/streamers">‹ 返回场次列表</a>
        </div>
      </div>
      <h3 class="cat-title" id="sx-title">场次</h3>
      <div id="sx-body"></div>
    </div>`;
}

/**
 * 按地址栏取这一页该看的那一份。顶级页刷新会带上 {sub, tail}，
 * 解析仍走 parseStreamersHash(location.hash)，与拼地址用的是同一份。
 */
export function refresh() {
  return loadStreamers();
}

/** 离开本页时把报告图的临时地址撤掉 */
export function leave() {
  closeDetailDrawer();
  releaseComponents();
  kpiResizeObserver?.disconnect(); kpiResizeObserver = null;
  recentResizeObserver?.disconnect(); recentResizeObserver = null;
  liveActive = false;
  viewGeneration++;
  stopLiveRefresh();
  releaseReport();
}

/** 当前落在哪一块，真源是地址栏 */
let where = parseStreamersHash('');
let liveActive = false;
let liveTimer = null;
let liveGeneration = 0;
let viewGeneration = 0;
let livePending = null;
let liveKey = '';
let live = null;
let liveError = '';

/**
 * 后台刷新失败时屏幕上那一句。数据照留，不拼浏览器或服务器的原话、不标红。
 * 原因不往浏览器控制台打（前端一律不打印，见 ConfigUiFrontendTest 的口令守卫）：
 * 服务器回的错误服务端自己记在工程日志里，连不上的那种请求根本没到服务器
 */
const REFRESH_FAILED = '刷新失败，稍后自动重试';

function stopLiveRefresh() {
  if (liveTimer !== null) clearInterval(liveTimer);
  liveTimer = null;
  liveGeneration++;
  livePending = null;
}

function canRefreshLive() {
  const current = parseStreamersHash(location.hash);
  return current.view === 'detail'
    && current.platform === where.platform && current.uid === where.uid
    && liveActive && !document.hidden && isStreamersHash(location.hash)
    && where.view === 'detail' && detail?.success
    && detail.configured !== false
    && liveKey === where.platform + '/' + where.uid;
}

function startLiveRefresh() {
  if (!canRefreshLive() || liveTimer !== null) return;
  liveTimer = setInterval(loadLive, 15000);
}

// 隐藏时撤表；再看这一页时立即检查一次，再继续每 15 秒刷新。
document.addEventListener('visibilitychange', async () => {
  if (document.hidden) stopLiveRefresh();
  else if (canRefreshLive()) {
    startLiveRefresh();
    await loadLive();
  }
});

/**
 * 这台机器的运行状态，只取「开没开累计数据」那一位
 *
 * 与详情各取各的：它那一趟晚一点回来时，横条会自己补上。合成一趟的话，
 * /api/status 慢一拍就把整张详情页一起拖住，而那一页的主体与这一位无关。
 */
let runtime = {};

/** 图片资源的临时地址。换一场或离开本页时要撤销，否则这一页会一直占着那几张图 */
let reportUrl = '';

/**
 * 把地址栏读进来，并决定显示三块里的哪一块
 *
 * 与取数据分开一个口子，是为了首屏那一次：路由先摆版式、随后才载入数据，
 * 两件事合成一个函数的话，直接打开某一场的地址会先闪一下列表。
 */
export function syncStreamersView() {
  closeDetailDrawer();
  releaseComponents();
  kpiResizeObserver?.disconnect(); kpiResizeObserver = null;
  recentResizeObserver?.disconnect(); recentResizeObserver = null;
  viewGeneration++;
  stopLiveRefresh();
  const next = parseStreamersHash(location.hash);
  const changingStreamer = next.view === 'detail'
    && (where.view !== 'detail' || where.platform !== next.platform || where.uid !== next.uid);
  where = next;
  if (changingStreamer) {
    // 换主播时立即撤掉旧身份的链接，避免加载期间点回上一位主播。
    detail = null; insightsData = null; periodAudience = null;
    $('#sv-detail').setAttribute('aria-busy', 'true');
    $('#sd-head').innerHTML = loadingHtml('正在加载主播资料…');
    $('#sd-body').innerHTML = loadingHtml('正在读取该主播的运营数据…');
  }
  const home = $('#sd-member-home');
  if (home && store.singleStreamer) home.style.display = where.view === 'detail'
    && where.platform === store.singleStreamer.platform && String(where.uid) === String(store.singleStreamer.uid) ? '' : 'none';
  $('#sv-list').style.display = where.view === 'list' ? '' : 'none';
  $('#sv-detail').style.display = where.view === 'detail' ? '' : 'none';
  $('#sv-session').style.display = where.view === 'session' ? '' : 'none';
}

/** 进入本页，或在本页里换了地址：按落点取该取的那一份 */
export function loadStreamers() {
  // 宿主刷新各插件页（refreshPages）时不分在不在屏幕上，这一页也被叫到。
  // 地址栏第一段不是本页名字的（正落在推送页那类地址上），取了也用不上——
  // 照后两段读出一位主播再去问接口，问回来的是一位不存在的人，白挨 404
  if (!isStreamersHash(location.hash)) {
    liveActive = false;
    viewGeneration++;
    stopLiveRefresh();
    return;
  }
  const next = parseStreamersHash(location.hash);
  if (liveActive && detail?.success && $('#sd-insights')
    && where.view === 'detail' && next.view === 'detail'
    && where.platform === next.platform && where.uid === next.uid
    && JSON.stringify(where) !== JSON.stringify(next)) {
    const rangeChanged = where.days !== next.days;
    const hideChanged = where.hideOff !== next.hideOff;
    const viewport = drawerViewport(next);
    where = next;
    insightsHideOff = where.hideOff === '1';
    if (hideChanged) renderDailyTrend();
    if (hideChanged) updateHideOffLinks();
    if (rangeChanged) insightsData = null;
    const jobs = [openDetailDrawer(viewport)];
    if (rangeChanged) jobs.push(loadDashboard());
    return Promise.all(jobs);
  }
  liveActive = true;

  syncStreamersView();
  releaseReport();

  // 「开没开累计数据」只有详情页的概况用得上，别的两块不问——每翻一页都顺带问一次运行状态，
  // 在一台配了几十位主播的机器上是白花的一趟
  if (where.view === 'detail') {
    api('/status').then(state => {
      runtime = state || {};
      renderBanner();
    }).catch(() => {});
  }

  if (where.view === 'list') return loadList();
  if (where.view === 'detail') return loadDetail();
  return loadSession();
}

/** 离开本页时把图片的临时地址撤掉 */
export function releaseReport() {
  if (reportUrl) {
    URL.revokeObjectURL(reportUrl);
    reportUrl = '';
  }
}

// ---------------------------------------------------------------- 列表

async function loadList() {
  let data;
  try {
    data = await api('/streamers');
  } catch (e) {
    say('主播列表加载失败：' + e.message, 'err');
    return;
  }

  const list = data.streamers || [];
  const days = data.days || 0;
  $('#st-window').textContent = days ? '最近 ' + days + ' 个统计日' : '';

  const totals = summaryTotals(list, days);
  $('#st-all').innerHTML = [
    [String(totals.total), '在册主播（含停用）'],
    [String(totals.living), '正在直播'],
    [String(totals.sessions), '启用主播 · 近 ' + totals.days + ' 天场次'],
    [fmtDuration(totals.durationSeconds), '启用主播 · 近 ' + totals.days + ' 天时长'],
  ].map(([value, label]) => '<div class="stat"><div class="stat-v">' + esc(value)
    + '</div><div class="stat-l">' + esc(label) + '</div></div>').join('');

  renderRows(list, days);
  renderUnlisted(data.notConfigured || []);

  // 服务端加了一档状态而界面的措辞表没跟上时说一句。不说的话，那一档会照接口的措辞
  // 静静地显示出来——界面不至于坏，但没有任何东西提起「这里该补一句定稿的说法」
  const missing = uncoveredStatuses(data.statuses);
  if (missing.length) {
    say('服务端返回了界面尚未适配的主播状态：' + missing.join('、')
      + '。这些状态按服务端提供的名称显示', 'err');
  }
}

/**
 * 每位主播一行
 * @param list 接口给的 streamers[]
 * @param days 折线画几天
 */
function renderRows(list, days) {
  const host = $('#st-list');
  host.innerHTML = '';

  if (!list.length) {
    host.appendChild(el('div', 'empty'));
    host.lastChild.textContent = '尚未配置任何主播。请在「推送」页左上角点击「＋ 添加主播」。';
    return;
  }

  // 仅按接口已有状态分组，汇总仍由 summaryTotals 保持原统计规则。
  const enabled = list.filter(one => one.status !== 'DISABLED');
  const disabled = list.filter(one => one.status === 'DISABLED');
  for (const group of [{title: '启用主播', rows: enabled}, {title: '停用主播', rows: disabled}]) {
    if (!group.rows.length) continue;
    const heading = el('h4', 'st-group-heading');
    heading.textContent = group.title;
    if (group.rows === disabled) {
      const note = el('small');
      note.textContent = '历史场次仍可查看；以下场次与时长不计入上方汇总。';
      heading.appendChild(note);
    }
    host.appendChild(heading);
    for (const one of group.rows) {
      const row = el('a', 'rowcard');
      row.href = detailHash(one.platform, one.uid);
      row.setAttribute('aria-label', one.uname || String(one.uid));

      const main = el('div', 'rc-main');
      const name = el('div', 'rc-nm');
      name.textContent = one.uname || String(one.uid);
      name.appendChild(chip(one));
      main.appendChild(name);

      const sub = el('div', 'rc-sub');
      // 直播间号与 uid 一并写出：群里的人报的是直播间号，控制台里配的是 uid，
      // 两者对不上时，只显示其中一个的那一行谁也认不出是不是同一位
      const summary = el('span');
      summary.textContent = rowSubtitle(one, days);
      summary.title = '一天从早上 6 点算起，凌晨 0–6 点开播的场次算在前一天';
      sub.appendChild(summary);
      const identity = el('span', 'st-identity');
      identity.textContent = ' · uid ' + one.uid + (one.roomId ? ' · 直播间 ' + one.roomId : '');
      sub.appendChild(identity);
      main.appendChild(sub);
      row.appendChild(main);

      row.appendChild(spark(one.series));
      const arrow = el('span', 'st-row-arrow'); arrow.textContent = '›'; arrow.setAttribute('aria-hidden', 'true');
      row.appendChild(arrow);

      host.appendChild(row);
    }
  }
}

/**
 * 一行右边那条小折线
 *
 * 画的是场次数不是时长：一行里只摆得下一条线，而「哪几天播了」比「那几天播了多久」
 * 更接近这一列要回答的问题。时长那一路在详情的趋势页上。
 * @param series 接口给的七天
 * @return {HTMLElement} 折线那一块
 */
function spark(series) {
  const box = el('div');
  box.style.cssText = 'display:flex;gap:10px;align-items:center;flex:0 0 auto';

  const points = seriesValues(series, 'sessions', statisticalDate(Date.now()));
  box.title = '折线截至昨天；场次合计含今天已归档的场次';
  const total = seriesValues(series, 'sessions').reduce((sum, one) => sum + one, 0);
  const text = el('div');
  const value = el('div', 'rc-sub');
  value.style.marginTop = '0';
  value.textContent = total + ' 场';
  text.appendChild(value);
  const scope = el('small', 'st-spark-scope'); scope.textContent = '折线至昨天'; text.appendChild(scope);
  box.appendChild(text);

  const geometry = sparkline(points);
  // 点数不足画不出线。此时整块不画，而不是画一条含 NaN 的路径——后者在屏幕上是一片空白，
  // 与「这几天一场没播」长得一样，而那一档本该是一条贴着底的平线
  if (!geometry) return box;

  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + geometry.width + ' ' + geometry.height);
  svg.setAttribute('class', 'spark' + (geometry.rising ? ' up' : ''));
  svg.setAttribute('aria-hidden', 'true');

  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', geometry.d);
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.6');
  path.setAttribute('vector-effect', 'non-scaling-stroke');
  svg.appendChild(path);

  const dot = document.createElementNS(ns, 'circle');
  dot.setAttribute('cx', String(geometry.cx));
  dot.setAttribute('cy', String(geometry.cy));
  dot.setAttribute('r', '2');
  dot.setAttribute('fill', 'currentColor');
  svg.appendChild(dot);

  box.appendChild(svg);
  return box;
}

/**
 * 状态标
 * @param one 列表项或详情顶层
 * @return {HTMLElement} 一枚标
 */
function chip(one) {
  const state = statusChip(one);
  const pill = el('span', state.cls);
  if (state.dot) pill.appendChild(el('span', 'dotlive'));
  const text = el('span');
  text.textContent = state.text;
  pill.appendChild(text);
  return pill;
}

/**
 * 归档里有、名单里没有的那几位
 * @param list 接口给的 notConfigured[]
 */
function renderUnlisted(list) {
  const host = $('#st-unlisted');
  host.innerHTML = '';
  if (!list.length) return;

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '已归档、但不在推送配置中的主播 ';
  const note = el('span', 'h-note');
  note.textContent = '已从推送配置中删除，过去的场次仍保留在归档中';
  head.appendChild(note);
  card.appendChild(head);

  for (const one of list) {
    const row = el('div', 'rowcard');
    const main = el('div', 'rc-main');
    const name = el('div', 'rc-nm');
    const link = el('a');
    link.href = detailHash(one.platform, one.uid);
    link.textContent = one.uname || String(one.uid);
    name.appendChild(link);
    main.appendChild(name);
    const sub = el('div', 'rc-sub');
    sub.textContent = 'uid ' + one.uid + ' · 已归档 ' + one.sessions + ' 场';
    main.appendChild(sub);
    row.appendChild(main);
    card.appendChild(row);
  }

  host.appendChild(card);
}

// ---------------------------------------------------------------- 详情

/** 主播资料、累计数据与最近归档。 */
let detail = null;

async function loadDetail() {
  const generation = viewGeneration;
  $('#sd-back')?.setAttribute('href', '#/streamers');

  const query = '';
  const path = '/streamers/' + encodeURIComponent(where.platform) + '/'
    + encodeURIComponent(where.uid) + (query ? '?' + query : '');

  // 页面上已经画着这一位：后台重取失败（连不上，或服务器回了错误包）不抹数据，也不拼原话标红
  const showing = () => detail?.success && detail.platform === where.platform
    && String(detail.uid) === String(where.uid);
  let response;
  try {
    response = await api(path);
  } catch (e) {
    if (generation !== viewGeneration || !liveActive) return;
    $('#sv-detail').removeAttribute('aria-busy');
    if (showing()) {
      say(REFRESH_FAILED);
      startLiveRefresh();
      return;
    }
    $('#sd-head').innerHTML = '';
    $('#sd-body').innerHTML = '<p class="empty">' + esc('主播详情加载失败：' + e.message) + '</p>';
    say('主播详情加载失败：' + e.message, 'err');
    return;
  }
  if (generation !== viewGeneration || !liveActive) return;

  $('#sv-detail').removeAttribute('aria-busy');
  if (!response?.success) {
    if (showing()) {
      say(REFRESH_FAILED);
      startLiveRefresh();
      return;
    }
    detail = response;
    $('#sd-head').innerHTML = '';
    $('#sd-body').innerHTML = '<p class="empty">' + esc(response?.message || '没有该主播的记录。') + '</p>';
    return;
  }
  detail = response;
  if ($('#status-text')?.textContent === REFRESH_FAILED) say('');

  renderHead();
  const key = where.platform + '/' + where.uid;
  if (key !== liveKey) {
    insightsMetric = 'revenue'; insightsMode = 'total';
    slotsMetric = 'revenue';
  }
  if (key !== liveKey || !detail.living
    || (live && detail.since != null && live.since !== detail.since)) {
    liveKey = key;
    live = null;
    liveError = '';
  }
  insightsHideOff = where.hideOff === '1';
  renderDashboard();
  startLiveRefresh();
  await Promise.all([loadLive(), loadDashboard(), openDetailDrawer()]);
}

function renderHead() {
  const host = $('#sd-head');
  host.innerHTML = '';

  const head = el('div', 'shead');
  if (detail.face) {
    const face = el('img', 'sav');
    face.src = detail.face;
    face.alt = '';
    head.appendChild(face);
  }

  const mid = el('div', 'sh-m');
  const name = el('div', 'sh-nm');
  const text = el('span');
  text.textContent = detail.uname || String(detail.uid);
  name.appendChild(text);
  name.appendChild(chip(detail));
  mid.appendChild(name);

  const id = el('div', 'sh-id');
  id.textContent = 'uid ' + detail.uid + (detail.roomId ? ' · 直播间 ' + detail.roomId : '')
    + ' · ' + detail.platform;
  mid.appendChild(id);
  // 不在册的那位要说一句。只按状态显示的话，他会被写成「已停用」——
  // 而停用是配置里的一个决定，压根没在册是另一回事
  if (detail.configured === false) {
    const off = el('div', 'sh-id');
    off.textContent = '该主播不在推送配置中：以下数据来自过去的归档，目前既不采集也不推送。';
    mid.appendChild(off);
  }
  head.appendChild(mid);

  const side = el('div', 'sh-side');
  const push = el('a', 'nv-btn2');
  push.href = '#/push/' + encodeURIComponent(detail.uid);
  push.textContent = '推送设置';
  side.appendChild(push);
  head.appendChild(side);

  host.appendChild(head);
}

function audienceHash(changes = {}) {
  const state = {...where, ...changes};
  return dashboardHash({panel: 'audience', days: state.days, group: state.group || 'all', sort: state.sort || 'attendance', page: state.page});
}

async function loadAudience() {
  const generation = drawerGeneration;
  renderAudience(null, '正在读取观众名单…');
  try {
    const response = await api('/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/audience?days=' + where.days + '&group=' + where.group
      + '&sort=' + where.sort + '&page=' + where.page);
    if (generation !== drawerGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取观众名单');
    renderAudience(response.audience);
  } catch (e) {
    if (generation === drawerGeneration && liveActive) renderAudience(null, '读取失败：' + e.message);
  }
}

function renderAudience(data, message) {
  const host = $('#sd-audience');
  if (!host) return;
  host.innerHTML = '';
  if (!data) {
    const hint = el('p', 'hint');
    hint.textContent = message;
    host.appendChild(hint);
    return;
  }
  const card = el('div', 'nv-card hcard sd-audience-people'); host.appendChild(card);
  const title = el('h3'); title.textContent = '本期有效观众名单'; card.appendChild(title);
  const groupNames = {all: '本期全部观众', first: '本期首次记录', returning: '历史回访', interaction: '本期互动观众'};
  const intro = el('p', 'hint'); intro.textContent = '当前：' + (groupNames[data.group] || groupNames.all) + ' · 仅包含本期已有名单中记录到的身份；首次记录参照已有名单历史。'; card.appendChild(intro);
  const groups = el('div', 'sd-audience-groups');
  for (const [key] of audienceGroups(data.counts)) {
    const link = el('a', 'sd-audience-group');
    link.href = audienceHash({group: key, page: 1});
    const name = el('span'); name.textContent = groupNames[key];
    const count = el('strong'); count.textContent = data.counts?.[key] == null ? '—' : fmtMetric(data.counts[key]);
    link.append(name, count);
    if (key === data.group) link.setAttribute('aria-current', 'page');
    groups.appendChild(link);
  }
  card.appendChild(groups);
  const explanation = el('p', 'sd-audience-note hint');
  explanation.textContent = '首次记录指已有记录中第一次出现，不一定是第一次观看。流水按礼物、醒目留言与大航海合计；到过的场次缺少逐人金额时显示「—」。';
  const scroll = el('div', 'audience-scroll');
  const table = el('table');
  const head = el('thead');
  const headers = el('tr');
  for (const [key, label] of AUDIENCE_COLUMNS) {
    const th = el('th', ['attendance', 'revenue'].includes(key) ? 'n' : '');
    if (['attendance', 'revenue', 'recent'].includes(key)) {
      th.setAttribute('aria-sort', key === data.sort ? 'descending' : 'none');
      const link = el('a');
      link.href = audienceHash({sort: audienceSort(data.sort, key), page: 1});
      link.textContent = label + (key === data.sort ? ' ↓' : '');
      th.appendChild(link);
    } else th.textContent = label;
    headers.appendChild(th);
  }
  head.appendChild(headers);
  table.appendChild(head);
  const body = el('tbody');
  for (const person of data.items || []) {
    const cells = audienceCells(person, data);
    const row = el('tr');
    const name = el('td');
    const button = el('a', 'sd-person-link');
    button.href = '#';
    button.textContent = cells.name;
    button.setAttribute('aria-expanded', 'false');
    name.appendChild(button);
    row.appendChild(name);
    for (const key of ['composition', 'attendance', 'recent', 'revenue', 'interaction']) {
      const cell = el('td', ['attendance', 'revenue'].includes(key) ? 'n' : '');
      cell.textContent = key === 'interaction' && person.interaction ? '已记录互动' : cells[key];
      if (key === 'recent') {
        const first = el('span', 'sd-cell-note');
        first.textContent = cells.first;
        cell.appendChild(first);
      }
      row.appendChild(cell);
    }
    body.appendChild(row);
    const expanded = el('tr');
    expanded.hidden = true;
    const visitCell = el('td');
    visitCell.colSpan = AUDIENCE_COLUMNS.length;
    const visits = el('ul');
    for (const visit of person.visits || []) {
      const item = el('li');
      const link = el('a');
      link.href = sessionHash(where.platform, where.uid, visit.startTime);
      link.textContent = new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai',
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
        hourCycle: 'h23'}).format(new Date(visit.startTime)) + ' · ' + (visit.title || '未记录标题');
      item.appendChild(link);
      visits.appendChild(item);
    }
    visitCell.appendChild(visits);
    expanded.appendChild(visitCell);
    body.appendChild(expanded);
    button.addEventListener('click', event => {
      event.preventDefault();
      expanded.hidden = !expanded.hidden;
      button.setAttribute('aria-expanded', String(!expanded.hidden));
    });
  }
  table.appendChild(body);
  scroll.appendChild(table);
  card.appendChild(scroll);
  scrollHint(card);
  if (!data.items?.length) {
    const empty = el('p', 'hint');
    empty.textContent = '该范围和分组暂无观众名单。';
    card.appendChild(empty);
  }
  const pager = el('div', 'sd-pagination');
  const text = el('span', 'hint');
  text.textContent = '共 ' + fmtMetric(data.total) + ' 条 · 第 ' + fmtMetric(data.page) + '/' + fmtMetric(Math.max(1, data.pages)) + ' 页';
  pager.appendChild(text);
  for (const [page, label] of [[data.page - 1, '‹ 上一页'], [data.page + 1, '下一页 ›']]) {
    if (page < 1 || page > data.pages) continue;
    const link = el('a');
    link.href = audienceHash({page});
    link.textContent = label;
    pager.appendChild(link);
  }
  card.append(pager, explanation);
}

let slotsMetric = 'revenue';
let slotsData = null;
let slotsAreas = null;
let slotsEntryRhythm = null;
let slotsEntryChart = null;
let slotsMessage = '';

async function loadStartSlots() {
  const generation = drawerGeneration;
  slotsData = null;
  slotsAreas = null;
  slotsEntryRhythm = null;
  slotsMessage = '正在读取归档数据…';
  renderSlotsDrawer();
  try {
    const response = await api(streamerPath() + '/start-slots?days=' + slotRangeDays(where));
    if (generation !== drawerGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取归档数据');
    slotsData = response.startSlots;
    slotsAreas = response.areas || [];
    slotsEntryRhythm = response.entryRhythm || null;
    slotsMessage = '';
  } catch (error) {
    if (generation !== drawerGeneration || !liveActive) return;
    slotsMessage = '读取失败：' + error.message;
  }
  renderSlotsDrawer();
}

function renderSlotsDrawer() {
  if (!detailDrawer || where.panel !== 'start-slots') return;
  const left = detailDrawer.body.querySelector('.slot-scroll')?.scrollLeft || 0;
  slotsEntryChart?.destroy(); slotsEntryChart = null;
  detailDrawer.body.innerHTML = '';
  renderStartSlots(detailDrawer.body);
  updateDrawerScope();
  const scroll = detailDrawer.body.querySelector('.slot-scroll');
  if (scroll) scroll.scrollLeft = left;
}

function renderStartSlots(host) {
  const card = el('div', 'nv-card hcard start-slots');
  const head = el('h3');
  head.textContent = '开播时段表现';
  card.appendChild(head);
  const ranges = el('div', 'lgpills');
  for (const days of [90, 180]) {
    const link = el('a', 'nv-pill lgpill');
    link.href = dashboardHash({panel: 'start-slots', slotDays: days});
    link.textContent = '近 ' + days + ' 天';
    if (days === slotRangeDays(where)) link.setAttribute('aria-current', 'page');
    ranges.appendChild(link);
  }
  const controls = el('div', 'sd-slot-controls'); host.appendChild(controls); controls.appendChild(ranges);
  const metrics = el('label'); metrics.textContent = '热图指标 ';
  const select = el('select'); select.setAttribute('aria-label', '热图指标');
  for (const [metric, text] of [['revenue', '每小时流水'], ['messages', '每小时弹幕']]) {
    const option = el('option'); option.value = metric; option.textContent = text; select.appendChild(option);
  }
  select.value = slotsMetric;
  select.addEventListener('change', () => { slotsMetric = select.value; renderSlotsDrawer(); });
  metrics.appendChild(select); controls.appendChild(metrics);
  renderRecording(host, slotsData);
  const topHead = el('h4', 'sd-slot-heading'); topHead.textContent = '表现较好的开播时段'; card.appendChild(topHead);
  const method = el('p', 'hint sd-slot-method'); method.textContent = '按实际开播时间所属的星期与时段分组，以整场总量 ÷ 有效直播总时长比较效率；不表示该两小时内的产出。'; card.appendChild(method);
  const order = el('p', 'hint sd-slot-scope'); order.textContent = '按' + (slotsMetric === 'revenue' ? '每小时流水' : '每小时弹幕') + '排序 · 至少 3 场有效样本 · 与下方热图同一观察范围'; card.appendChild(order);
  if (slotsData) {
    const topItems = slotsMetric === 'revenue' ? slotsData.topRevenue : slotsData.topMessages;
    const ranking = el('ol', 'sd-slot-top');
    ranking.setAttribute('aria-label', '表现较好的开播时段，最多三名');
    slotTopText(topItems || [], slotsMetric).slice(0, topItems?.length ? 3 : 0).forEach((text, index) => {
      const item = el('li');
      const entry = topItems?.[index];
      if (!entry) { item.textContent = text; ranking.appendChild(item); return; }
      const cell = slotsData.cells.find(cell => cell.weekday === entry.weekday && cell.startHour === entry.startHour);
      const starts = cell?.starts || cell?.sessionItems?.map(item => item.startTime) || [];
      const link = panelLink('', 'sessions', {starts: starts.join(','), source: 'rhythm', from: slotsData.from, to: slotsData.to});
      link.setAttribute('aria-label', text);
      const time = el('span', 'sd-slot-title'); const rank = el('small'); rank.textContent = String(index + 1); const label = el('span'); label.textContent = slotLabel(entry); time.append(rank, label);
      const value = el('strong'); value.textContent = fmtMetric(entry.value, {money: true}); const unit = el('small'); unit.textContent = slotsMetric === 'revenue' ? ' 元/时' : ' 条/时'; value.appendChild(unit);
      const sample = el('span', 'sd-slot-sample'); sample.textContent = entry.sessions + ' 场有效样本'; const action = el('span'); action.textContent = '查看场次 →'; sample.appendChild(action);
      link.appendChild(time); link.appendChild(value); link.appendChild(sample); item.appendChild(link); ranking.appendChild(item);
    });
    if (topItems?.length) card.appendChild(ranking);
    else {
      const empty = el('p', 'hint sd-slot-method');
      empty.textContent = '当前范围暂无达到样本门槛且效率有效的时段。'; card.appendChild(empty);
    }
    const scroll = el('div', 'slot-scroll');
    scroll.setAttribute('tabindex', '0');
    scroll.setAttribute('role', 'region');
    scroll.setAttribute('aria-label', '开播时段表现，可左右滚动');
    const table = el('table', 'slot-grid');
    const thead = el('thead');
    const headers = el('tr');
    for (const text of ['', ...Array.from({length: 12}, (_, i) => (i * 2) + '–' + (i * 2 + 2) + '时')]) {
      const th = el('th');
      th.setAttribute('scope', 'col');
      th.textContent = text;
      headers.appendChild(th);
    }
    thead.appendChild(headers);
    table.appendChild(thead);
    const tbody = el('tbody');
    const maximum = Math.max(0, ...slotsData.cells.filter(c => c.sessions >= 3).map(c => c[slotsMetric + 'PerHour'] || 0));
    for (let day = 1; day <= 7; day++) {
      const tr = el('tr');
      const th = el('th');
      th.setAttribute('scope', 'row');
      th.textContent = '周' + '一二三四五六日'[day - 1];
      tr.appendChild(th);
      for (const cell of slotsData.cells.filter(c => c.weekday === day)) {
        const td = el('td');
        const button = el('button');
        button.type = 'button';
        const shown = slotCell(cell, slotsMetric, maximum);
        button.innerHTML = esc(shown.text.replace(/ (元|条)\/时$/, '')) + '<small>' + esc(shown.count) + '</small>';
        button.setAttribute('aria-label', slotLabel(cell) + ' · ' + shown.text + ' · ' + shown.count);
        button.setAttribute('aria-pressed', 'false');
        button.disabled = !cell.sessions;
        button.dataset.low = String(shown.intensity == null);
        if (shown.intensity !== null) {
          button.style.background = 'color-mix(in srgb, var(--accent) ' + Math.round(12 + shown.intensity * 48) + '%, var(--surface))';
        }
        button.addEventListener('click', () => {
          if (!cell.sessions) return;
          for (const row of tbody.children) for (const column of row.children) {
            const previous = column.firstChild;
            if (previous?.tagName?.toLowerCase() === 'button') previous.setAttribute('aria-pressed', 'false');
          }
          button.setAttribute('aria-pressed', 'true');
          location.hash = dashboardHash({panel: 'sessions', starts: (cell.starts || cell.sessionItems?.map(item => item.startTime) || []).join(','), from: slotsData.from, to: slotsData.to, source: 'rhythm', dateFrom: null, dateTo: null, page: 1});
        });
        td.appendChild(button);
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    scroll.appendChild(table);
    card.appendChild(scroll);
    scrollHint(card);

  } else {
    const hint = el('p', 'hint');
    hint.textContent = slotsMessage || '正在读取归档数据…';
    card.appendChild(hint);
  }
  const note = el('p', 'hint');
  note.style.margin = '10px 0 0';
  note.textContent = '时段按实际星期与钟表时间，场次按开播时刻归入；数值是这些场次整场总量 ÷ 有效直播时长，不表示该两小时内的产出。少于 3 场为样本不足。';
  card.appendChild(note);
  host.appendChild(card);
  renderEntryRhythm(host);
  renderAreaCompare(host);
}

function renderEntryRhythm(host) {
  const card = el('div', 'nv-card hcard sd-entry-rhythm');
  const title = el('h3'); title.textContent = '开播后入场节奏'; card.appendChild(title);
  const data = slotsEntryRhythm;
  const points = data?.points || [];
  const number = value => value == null ? '暂无数据' : new Intl.NumberFormat('zh-CN', {maximumFractionDigits: 1}).format(value);
  if (points.length) {
    const chartTitle = '开播后入场次数中位数（每 5 分钟一段）';
    const heading = el('h4'); heading.textContent = chartTitle; card.appendChild(heading);
    slotsEntryChart = createChart(card, {title: chartTitle, points: points.map(point => ({...point, start: point.minute, value: point.medianEntries})),
      label: point => '开播 ' + point.minute + ' 分钟 · ' + number(point.entriesSamples) + ' 场有效样本',
      tickLabel: point => point.minute + ' 分钟',
      format: value => value == null ? '暂无数据' : number(value) + ' 次/分钟'});
  } else {
    const empty = el('p', 'hint');
    empty.textContent = slotsMessage || (data?.entrySessions === 0
      ? '所选范围的场次还没有入场分钟数据。' : '所选范围无可用数据。');
    card.appendChild(empty);
  }
  const note = el('p', 'hint');
  note.textContent = '以每场开播时刻为起点，每 5 分钟一段，先算每场段内平均每分钟的入场次数，再取各场中位数；同一人进出多次按次数计。在线与入场分别标明有效样本场次。';
  card.appendChild(note);
  if (data) {
    const count = el('p', 'hint');
    count.textContent = '范围内共 ' + number(data.sessions) + ' 场，其中 ' + number(data.entrySessions) + ' 场有入场分钟数据（更早的场次没有记录）。';
    card.appendChild(count);
  }
  if (points.length) {
    const details = el('details', 'sd-entry-details');
    const summary = el('summary'); summary.textContent = '开播后采样明细 · 每 30 分钟'; details.appendChild(summary);
    const scroll = el('div', 'sd-entry-scroll');
    scroll.setAttribute('tabindex', '0'); scroll.setAttribute('role', 'region');
    scroll.setAttribute('aria-label', '开播后采样明细，可左右滚动');
    const table = el('table', 'sd-entry-table');
    const head = el('thead'); const headers = el('tr');
    for (const text of ['开播后', '在线人数中位数', '入场中位数（次/分钟）', '入场样本场次']) {
      const th = el('th'); th.setAttribute('scope', 'col'); th.textContent = text; headers.appendChild(th);
    }
    head.appendChild(headers); table.appendChild(head);
    const body = el('tbody');
    for (const point of points.filter(point => point.minute % 30 === 0)) {
      const row = el('tr');
      const minute = el('td'); minute.textContent = point.minute + ' 分钟'; row.appendChild(minute);
      const online = el('td');
      const value = el('span'); value.textContent = number(point.medianOnline);
      const samples = el('small'); samples.textContent = number(point.onlineSamples) + ' 场有效样本';
      online.append(value, samples); row.appendChild(online);
      for (const value of [point.medianEntries, point.entriesSamples]) {
        const cell = el('td'); cell.textContent = number(value); row.appendChild(cell);
      }
      body.appendChild(row);
    }
    table.appendChild(body); scroll.appendChild(table); details.appendChild(scroll); card.appendChild(details);
  }
  host.appendChild(card);
}

/**
 * 「直播分区对比」：一张表比各区的场次、有效时长与流水
 *
 * 分区按开播时的那一条归属，中途换分区整场算给开头那个；没有分区轨迹的场次
 * 归入「未记录分区」。整区都没有指标快照时流水写「—」，不补 0。
 * @param host 开播节奏抽屉的正文
 */
function renderAreaCompare(host) {
  const card = el('div', 'nv-card hcard sd-area-compare');
  const head = el('h3');
  head.textContent = '直播分区对比';
  card.appendChild(head);
  const rows = slotsAreas || [];
  if (!rows.length) {
    const empty = el('p', 'hint');
    empty.textContent = slotsMessage || '所选范围无可用于分区对比的有效数值场次。';
    card.appendChild(empty);
  } else {
    const table = el('table', 'sd-area-table');
    const thead = el('thead');
    const headers = el('tr');
    for (const text of ['分区', '场次', '有效时长', '流水', '每小时流水']) {
      const th = el('th');
      th.setAttribute('scope', 'col');
      th.textContent = text;
      headers.appendChild(th);
    }
    thead.appendChild(headers);
    table.appendChild(thead);
    const tbody = el('tbody');
    for (const row of rows) {
      const tr = el('tr');
      const name = el('td');
      const link = el('a');
      link.href = dashboardHash({panel: 'sessions', starts: (row.starts || []).join(','),
        source: 'area', sourceArea: row.area, from: slotsData?.from ?? null, to: slotsData?.to ?? null,
        dateFrom: null, dateTo: null, page: 1});
      link.textContent = row.area;
      name.appendChild(link);
      tr.appendChild(name);
      for (const value of [fmtMetric(row.sessions), fmtDuration(row.effectiveSeconds),
        fmtMetric(row.revenue, {money: true}) + (row.revenue == null ? '' : ' 元'),
        fmtMetric(row.revenuePerHour, {money: true}) + (row.revenuePerHour == null ? '' : ' 元/时')]) {
        const td = el('td', 'n');
        td.textContent = value;
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    card.appendChild(table);
  }
  const note = el('p', 'hint');
  note.textContent = '整场按开播时的分区归属，中途换分区的不拆分；没有分区记录的场次归入「未记录分区」。';
  card.appendChild(note);
  host.appendChild(card);
}

let insightsMetric = 'revenue';
let insightsMode = 'total';
let insightsHideOff = false;
let insightsData = null;
let dashboardGeneration = 0;
let trendChart = null;
let kpiResizeObserver = null;
let detailDrawer = null;
let drawerGeneration = 0;
let drawerChanging = false;
const ranges = [7, 30, 90, 180, 360];
const trendMetrics = [['revenue', '流水', '元', true], ['messages', '弹幕', '条', true], ['audience', '观众', '人', false],
  ['follows', '新增关注', '人', true], ['durationSeconds', '直播时长', '', false], ['sessions', '场次', '场', false]];

function streamerPath() {
  return '/streamers/' + encodeURIComponent(where.platform) + '/' + encodeURIComponent(where.uid);
}

function dashboardHash(changes = {}) {
  const state = {...where, ...changes};
  const params = new URLSearchParams();
  for (const key of ['days', 'panel', 'group', 'sort', 'page', 'slotDays', 'from', 'to', 'starts', 'source', 'sourceDay', 'sourceArea', 'dateFrom', 'dateTo', 'period', 'metric', 'mode', 'hideOff']) {
    if (['source', 'sourceDay', 'sourceArea', 'dateFrom', 'dateTo', 'from', 'to', 'starts'].includes(key) && state.panel !== 'sessions') continue;
    if (['period', 'metric', 'mode'].includes(key) && (state.panel !== 'trend'
      || state[key] === {period: 'day', metric: 'revenue', mode: 'total'}[key])) continue;
    if (state[key] != null && (state[key] !== '' || key === 'starts')) params.set(key, state[key]);
  }
  return detailHash(state.platform, state.uid, 'overview', params.toString());
}

function updateHideOffLinks() {
  // 勾选变化时同步已画出的入口；后续生成的链接由 dashboardHash 保留此参数。
  for (const link of $('#sd-body').querySelectorAll('a')) {
    const hash = link.href?.slice(link.href.indexOf('#'));
    if (!isStreamersHash(hash)) continue;
    const target = parseStreamersHash(hash);
    if (target.view !== 'detail' || target.platform !== where.platform || target.uid !== where.uid) continue;
    const params = new URLSearchParams(hash.split('?')[1]);
    if (insightsHideOff) params.set('hideOff', '1');
    else params.delete('hideOff');
    link.href = detailHash(target.platform, target.uid, 'overview', params.toString());
  }
}

function panelLink(label, panel, changes = {}) {
  const link = el('a', 'lnkbtn sd-detail-link');
  link.textContent = label;
  link.href = dashboardHash({panel, page: 1, from: null, to: null, starts: null, source: panel === 'sessions' ? 'dashboard' : null, sourceDay: null, sourceArea: null, dateFrom: null, dateTo: null, ...changes});
  return link;
}

function closeDetailDrawer() {
  slotsEntryChart?.destroy(); slotsEntryChart = null;
  drawerGeneration++;
  drawerChanging = true;
  detailDrawer?.close();
  detailDrawer = null;
  drawerChanging = false;
}

function drawerViewport(next) {
  if (!detailDrawer || where.panel !== next.panel) return null;
  const body = detailDrawer.body;
  const pager = body.querySelector('.sd-pagination');
  const bounds = body.getBoundingClientRect();
  const pagerBounds = pager?.getBoundingClientRect();
  // 翻页时对齐正在看的翻页栏；其他视图变化保留抽屉的阅读位置。
  const paging = where.page !== next.page && Object.keys(where).every(key => key === 'page' || where[key] === next[key]);
  return {top: body.scrollTop, left: body.scrollLeft,
    pagerEnd: pagerBounds ? pagerBounds.bottom + body.scrollTop - bounds.top : null,
    pagerTop: paging && pagerBounds && pagerBounds.bottom > bounds.top && pagerBounds.top < bounds.bottom
      ? pagerBounds.top : null};
}

async function openDetailDrawer(viewport = null) {
  closeDetailDrawer();
  const titles = {audience: '本期观众名单', 'start-slots': '开播节奏明细', sessions: '直播场次', fans: '粉丝变化', trend: '趋势明细'};
  const panel = where.panel;
  if (!titles[panel]) return;
  detailDrawer = openDrawer({title: titles[panel]});
  const current = detailDrawer;
  updateDrawerScope();
  current.dialog.addEventListener('close', () => {
    if (!drawerChanging && detailDrawer === current) {
      detailDrawer = null;
      drawerGeneration++;
      location.hash = dashboardHash({panel: null, page: null, group: null, sort: null, from: null, to: null, starts: null, source: null, sourceDay: null, sourceArea: null, dateFrom: null, dateTo: null, period: null, metric: null, mode: null});
    }
  });
  if (panel === 'audience') {
    renderRecording(current.body, insightsData); updateDrawerScope();
    const host = el('div'); host.id = 'sd-audience'; current.body.appendChild(host);
    await loadAudience();
  } else if (panel === 'start-slots') await loadStartSlots();
  else if (panel === 'trend') await loadTrendDrawer(current);
  else {
    const generation = drawerGeneration;
    current.body.textContent = '正在读取…';
    try {
      let path = streamerPath() + '/' + panel + '?days=' + where.days;
      if (panel === 'sessions') {
        const params = new URLSearchParams({page: where.page, size: 10});
        for (const key of ['from', 'to', 'starts', 'dateFrom', 'dateTo']) if (where[key] != null) params.set(key, where[key]);
        path = streamerPath() + '/sessions?' + params;
      }
      const response = await api(path);
      if (generation !== drawerGeneration || !liveActive) return;
      if (!response.success) throw new Error(response.message || '未能读取明细');
      current.body.innerHTML = '';
      if (panel === 'sessions') renderSessions(current.body, response.sessions);
      else {
        const fans = response.fans || {};
        const note = el('p', 'hint');
        current.fans = fans; current.fansNote = note;
        current.body.appendChild(note); updateDrawerScope();
        createChart(current.body, {title: '粉丝数', fitRange: true, integerTicks: true, format: value => value == null ? '—' : fmtMetric(value) + ' 人', points: (fans.points || []).map(point => ({start: point.at, end: point.at, label: insightsTime(point.at), value: point.value}))});
        renderDailyFans(current.body, fans.points);
      }
    } catch (error) {
      if (generation === drawerGeneration && liveActive) current.body.textContent = '读取失败：' + error.message;
    }
  }
  if (viewport && detailDrawer === current) {
    const pager = current.body.querySelector('.sd-pagination');
    const offset = viewport.pagerTop != null && pager ? pager.getBoundingClientRect().top - viewport.pagerTop : viewport.top;
    const pagerEnd = pager ? pager.getBoundingClientRect().bottom - current.body.getBoundingClientRect().top : null;
    const remaining = offset - (current.body.scrollHeight - current.body.clientHeight);
    if (viewport.pagerTop != null && pagerEnd >= viewport.pagerEnd && remaining > 0) {
      // 短页返回长页时补足滚动余量，避免翻页栏被页底的滚动边界推走。
      const padding = parseFloat(getComputedStyle(current.body).paddingBottom);
      current.body.style.paddingBottom = (padding + remaining) + 'px';
    }
    // 内容变短时由浏览器收回真实页底，不额外撑高抽屉。
    current.body.scrollTop = offset;
    current.body.scrollLeft = viewport.left;
  }
}

function renderDailyFans(host, points) {
  const section = createSection('每日净增', '按 06:00 换日，比较相邻统计日末次有效快照；首日或缺少前一天快照时不补估。今天尚未结束。', host);
  const table = el('table'), head = el('thead'), row = el('tr');
  for (const name of ['统计日', '末次粉丝数', '净增']) { const th = el('th'); th.textContent = name; row.appendChild(th); }
  head.appendChild(row); table.appendChild(head);
  const body = el('tbody');
  for (const day of dailyFanChanges(points).reverse()) {
    const tr = el('tr');
    for (const value of [day.date + (day.date === statisticalDate(Date.now()) ? '（今天）' : ''), fmtMetric(day.value),
      day.change == null ? '—' : (day.change > 0 ? '+' : '') + fmtMetric(day.change)]) {
      const cell = el('td'); cell.textContent = value; tr.appendChild(cell);
    }
    body.appendChild(tr);
  }
  table.appendChild(body); section.box.appendChild(table);
}

function pageTime(at, year = true) {
  if (at == null) return '—';
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23'}).formatToParts(new Date(Number(at))).map(part => [part.type, part.value]));
  return (year ? parts.year + '/' : '') + parts.month + '/' + parts.day + ' ' + parts.hour + ':' + parts.minute;
}

function dateAt(at) {
  return new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'})
    .format(new Date(Number(at)));
}

function statisticalDate(at) {
  return dateAt(Number(at) - 6 * 3600000);
}

function intervalText(from, to, exclusive = true) {
  if (from == null || to == null) return '正在读取统计范围…';
  return pageTime(from) + ' — ' + pageTime(to) + (exclusive ? '（不含结束时刻）' : '');
}

function renderRecording(host, data) {
  let box = host.querySelector('.sd-recording');
  if (!box) {
    box = el('details', 'sd-recording');
    host.appendChild(box);
  }
  box.innerHTML = '';
  const summary = el('summary');
  if (!data) { summary.textContent = '已记录数据 · 正在读取…'; box.appendChild(summary); return; }
  const capture = data.capture || {};
  summary.textContent = '已记录数据 · 时长覆盖 ' + durationCoverage(data)
    + ' · 名单完整 ' + fmtMetric(data.coverage?.complete ?? 0) + '/' + fmtMetric(data.sessions) + ' 场'
    + (capture.gapSessions > 0 ? ' · 有缺口 ' + fmtMetric(capture.gapSessions) + ' 场' : '');
  box.appendChild(summary);
  const list = el('ul');
  for (const text of [
    '数值只统计记录到的数据，缺口不补估。时长覆盖＝有效时长 ÷ 直播时长；有效时长是每场直播时长减去停机与断线两项缺口里较大的那一项。',
    '缺口合计 ' + fmtWholeGap(capture.gapSeconds) + ' · 有缺口 ' + fmtMetric(capture.gapSessions) + ' 场 · 没有缺口 ' + fmtMetric(data.sessions - capture.gapSessions) + ' 场',
    coverageText(data.coverage),
    '名单完整与否单独判断；时长或流水计入了，不代表名单完整。',
  ]) { const line = el('li'); line.textContent = text; list.appendChild(line); }
  box.appendChild(list);
}

function updateDrawerScope() {
  if (!detailDrawer) return;
  if (detailDrawer.body.querySelector('.sd-recording'))
    renderRecording(detailDrawer.body, where.panel === 'start-slots' ? slotsData : insightsData);
  const head = detailDrawer.dialog.querySelector('.sc-drawer-head');
  let heading = head.querySelector('.sd-drawer-heading');
  if (!heading) {
    heading = el('div', 'sd-drawer-heading');
    heading.appendChild(head.children[0]);
    head.insertBefore(heading, head.children[0]);
    heading.appendChild(el('p', 'sd-drawer-context'));
  }
  let from = where.dateFrom ?? where.from ?? insightsData?.from;
  let to = where.dateTo ?? where.to ?? insightsData?.to;
  if (where.panel === 'sessions' && !where.from && !where.dateFrom) from = detail.overview?.firstStart ?? from;
  if (where.panel === 'start-slots') {
    to = slotsData?.to ?? to;
    from = slotsData?.from ?? (to == null ? null : Date.parse(statisticalDate(to) + 'T06:00:00+08:00') - (slotRangeDays(where) - 1) * 86400000);
  }
  const metric = where.panel === 'trend' ? trendMetrics.find(([key]) => key === where.metric)?.[1] || '流水'
    : where.panel === 'start-slots' ? slotsMetric === 'revenue' ? '每小时流水' : '每小时弹幕' : '';
  if (detailDrawer.fansNote) {
    const fans = detailDrawer.fans;
    detailDrawer.fansNote.textContent = '净增 ' + (fans.change == null ? '暂无数据' : fmtMetric(fans.change) + ' 人')
      + (fans.points?.length ? ' · 快照 ' + pageTime(fans.points.at(-1).at, false) : ' · 缺少有效快照')
      + (fans.partial ? ' · 可用快照未覆盖整个范围' : '');
  }
  heading.querySelector('.sd-drawer-context').textContent = intervalText(from, to) + ' · Asia/Shanghai' + (metric ? ' · ' + metric : '')
    + (where.panel === 'sessions' && where.source === 'calendar' ? ' · 日历所选日期的重叠归档' : '');
}

function renderComparison(data) {
  const head = $('#sd-insights')?.querySelector('.sc-section-head');
  if (!head) return;
  head.querySelector('.sd-comparison')?.remove();
  if (!data) return;
  const explanation = comparisonExplanation(data);
  if (!explanation.count) return;
  const fold = el('details', 'sd-comparison');
  const summary = el('summary'); summary.textContent = '对比说明 · ' + explanation.count + ' 项未比较'; fold.appendChild(summary);
  const body = el('div', 'sd-comparison-body');
  for (const group of explanation.groups) {
    const line = el('p'); line.textContent = group.reason + '：' + group.metrics.join('、'); body.appendChild(line);
  }
  if (explanation.note) { const note = el('p'); note.textContent = explanation.note; body.appendChild(note); }
  fold.appendChild(body); head.appendChild(fold);
}

function scrollHint(host) {
  const note = el('p', 'sd-scroll-hint'); note.textContent = '左右滚动可查看全部列。'; host.appendChild(note);
}

function renderToolbar(data, error) {
  const host = $('#sd-toolbar'); host.innerHTML = '';
  const label = el('strong'); label.textContent = '全页范围'; host.appendChild(label);
  for (const days of ranges) {
    const link = el('a', 'nv-pill lgpill');
    link.href = dashboardHash({days, page: 1, from: null, to: null, starts: null});
    link.textContent = days + '天';
    if (days === where.days) link.setAttribute('aria-current', 'page');
    host.appendChild(link);
  }
  const interval = el('span', 'hint');
  interval.textContent = error ? '读取失败：' + error : data ? '本期统计：' + intervalText(data.from, data.to ?? Date.now()) : '正在读取本期日期…';
  const first = detail.overview?.firstStart;
  interval.textContent += (Number.isFinite(first) && first > 0 && Number.isFinite(data?.to) && first <= data.to ? ' · 可用历史自 ' + pageTime(first) : '')
    + ' · 统计日 06:00–次日06:00 · Asia/Shanghai';
  const meta = el('div', 'sd-meta'); meta.appendChild(interval);
  const scope = el('details', 'sd-scope'); scope.id = 'sd-scope'; meta.appendChild(scope);
  host.appendChild(meta);
}

function renderScope(data = {}, error) {
  const scope = $('#sd-scope'); scope.innerHTML = '';
  const summary = el('summary'); summary.textContent = '?'; summary.setAttribute('aria-label', '统计口径'); summary.title = '统计口径'; scope.appendChild(summary);
  const content = el('div', 'sd-scope-content'); scope.appendChild(content);
  const failure = error ? '读取失败：' + error : null;
  const previous = data.previous?.from != null && data.previous?.to != null
    ? intervalText(data.previous.from, data.previous.to) : null;
  const lines = [
    '全页范围：' + (failure ?? intervalText(data.from, data.to ?? Date.now()) + ' · Asia/Shanghai'),
    '对比范围：' + (failure ?? previous ?? (data.from != null || data.previous ? '—' : intervalText())),
    '近 ' + where.days + ' 天＝含今天的 ' + where.days + ' 个统计日；统计日从早上 6 点起（东八区）。',
    '较上期＝往前挪 ' + where.days + ' 天、长度相同的一段。场次与整场指标按开播统计日归入，不拆分跨日金额。',
    '观众按已有名单去重；首次记录指第一次被记录，不一定是第一次观看。',
    ...(data.sessions != null ? ['归档 ' + fmtMetric(data.sessions) + ' 场 · 名单完整 ' + fmtMetric(data.coverage?.complete ?? 0) + ' 场'] : []),
    coverageText(data.coverage),
    ...(data.capture ? ['归档采集覆盖 ' + durationCoverage(data) + ' · 缺口合计 ' + fmtWholeGap(data.capture.gapSeconds) + ' · 有缺口 ' + fmtMetric(data.capture.gapSessions) + ' 场'] : []),
    ...(data.monitoring ? [monitoringPeriodText(data.monitoring, '本期'), monitoringPeriodText(data.previous?.monitoring, '上期')] : []),
  ];
  if (!comparisonExplanation(data).count && data.comparisonNote) lines.push(data.comparisonNote);
  for (const text of lines.filter(Boolean)) { const line = el('p', 'hint'); line.textContent = text; content.appendChild(line); }
}

function performanceHint() {
  return '近 ' + where.days + ' 天（含今天）· 较上期为再往前的 ' + where.days + ' 天';
}

function renderDashboardHints(data, error) {
  renderArchiveLink(data, error);
  const from = data?.from;
  const to = data?.to ?? Date.now();
  const moment = at => {
    const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
      day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).formatToParts(new Date(at));
    return Object.fromEntries(parts.map(part => [part.type, part.value]));
  };
  let interval = error ? '读取失败：' + error : '正在读取本期日期…';
  if (from != null) {
    const start = moment(from), end = moment(to);
    const compact = date => (start.year !== end.year ? date.year + '/' : '') + date.month + '/' + date.day + ' ' + date.hour + ':' + date.minute;
    interval = compact(start) + ' — ' + compact(end);
  }
  for (const [id, text] of [['sd-insights', error ? interval : performanceHint()], ['sd-trend', interval],
    ['sd-period-audience', interval], ['sd-calendar-card', interval], ['sd-recent-card', interval]]) {
    const hint = $('#' + id)?.querySelector('.sc-section-hint');
    if (hint) hint.textContent = text;
  }
}

function renderDashboard() {
  recentResizeObserver?.disconnect(); recentResizeObserver = null;
  const host = $('#sd-body'); host.innerHTML = '';
  const toolbar = el('div', 'sd-toolbar'); toolbar.id = 'sd-toolbar'; host.appendChild(toolbar);
  const performance = createSection('本期表现', performanceHint(), host).box; performance.id = 'sd-insights';
  const performanceBody = el('div'); performanceBody.id = 'sd-insights-content'; performance.appendChild(performanceBody);
  const columns = el('div', 'sd-columns'); host.appendChild(columns);
  const trendSection = createSection('近期趋势', '正在读取本期日期…', columns);
  const trend = trendSection.box; trend.id = 'sd-trend';
  trendSection.header.appendChild(panelLink('查看明细', 'trend', {period: 'day', metric: insightsMetric, mode: insightsMode}));
  const trendBody = el('div'); trendBody.id = 'sd-trend-content'; trend.appendChild(trendBody);
  const audience = createSection('本期观众', '正在读取本期日期…', columns); audience.box.id = 'sd-period-audience';
  audience.header.appendChild(panelLink('查看本期名单', 'audience', {group: 'all', sort: 'attendance'}));
  const audienceBody = el('div'); audienceBody.id = 'sd-audience-summary'; audience.box.appendChild(audienceBody);
  const calendar = createSection('开播日历', '正在读取本期日期…', columns); calendar.box.id = 'sd-calendar-card';
  calendar.header.appendChild(panelLink('查看历史节奏', 'start-slots'));
  const calendarBody = el('div'); calendarBody.id = 'sd-calendar'; calendar.box.appendChild(calendarBody);
  const sessions = createSection('当前直播与最近归档', '正在读取本期日期…', columns); sessions.box.id = 'sd-recent-card';
  const archiveLink = el('a', 'lnkbtn sd-detail-link sd-archive-link'); archiveLink.textContent = '正在读取归档场数…'; sessions.header.appendChild(archiveLink);
  const current = el('div', 'sd-current-live'); current.id = 'sd-live'; sessions.box.appendChild(current);
  renderLive(); sessions.box.appendChild(recentCard());
  recentResizeObserver = new ResizeObserver(fitRecent);
  for (const box of [columns, calendar.box, sessions.box, current]) recentResizeObserver.observe(box);
  const cumulative = el('details', 'sd-cumulative');
  const title = el('summary'); title.textContent = '累计数据'; cumulative.appendChild(title);
  renderOverview(cumulative); host.appendChild(cumulative);
  insightsData = null; renderToolbar(); renderScope(); renderBanner();
}

function renderArchiveLink(data, error) {
  const link = $('#sd-body')?.querySelector('.sd-archive-link');
  if (!link) return;
  const available = data?.sessions != null && data.from != null && data.to != null;
  if (!available && !error) {
    link.textContent = '正在读取归档场数…';
    link.removeAttribute('href');
    return;
  }
  link.textContent = available ? '查看 ' + fmtMetric(data.sessions) + ' 场归档' : '查看全部归档';
  // 按开播统计日归入，包含范围起点开播的零秒归档。
  link.href = dashboardHash({panel: 'sessions', page: 1, from: null, to: null,
    dateFrom: available ? data.from : null, dateTo: available ? data.to : null,
    starts: null, source: available ? 'dashboard' : null, sourceDay: null});
}

async function loadDashboard() {
  const generation = ++dashboardGeneration;
  const view = viewGeneration;
  const days = where.days;
  renderToolbar(); renderDashboardHints();
  // 范围变更时先清掉旧范围，避免等待期间把两期数字混在一起。
  insightsData = null; periodAudience = null;
  renderScope();
  renderInsights(null, '正在读取归档数据…');
  $('#sd-audience-summary').textContent = '正在读取观众…';
  $('#sd-calendar').innerHTML = '';
  const current = () => generation === dashboardGeneration && view === viewGeneration && liveActive;
  const read = async (endpoint, render) => {
    try {
      const response = await api(streamerPath() + '/' + endpoint + '?days=' + days);
      if (!current()) return;
      if (!response.success) throw new Error(response.message || '未能读取数据');
      render(response);
    } catch (error) {
      if (!current()) return;
      if (endpoint === 'insights') {
        renderInsights(null, '读取失败：' + error.message);
        renderDashboardHints(null, error.message); renderToolbar(null, error.message); renderScope({}, error.message);
      }
      else $(endpoint === 'calendar' ? '#sd-calendar' : '#sd-audience-summary').textContent = '读取失败：' + error.message;
    }
  };
  await Promise.all([
    read('insights', response => { insightsData = response.insights; renderInsights(insightsData, ''); renderToolbar(insightsData); renderDashboardHints(insightsData); renderScope(insightsData); renderAudienceSummary(insightsData); updateDrawerScope(); }),
    read('audience', response => { periodAudience = response.audience; renderAudienceSummary(insightsData); }),
    read('calendar', response => { const host = $('#sd-calendar'); host.innerHTML = ''; createCalendar(host, response.calendar, {onSelect: interval => {
      location.hash = dashboardHash({panel: 'sessions', starts: interval.starts.join(','), from: interval.start, to: interval.end, source: 'calendar', sourceDay: interval.date || statisticalDate(interval.start), dateFrom: null, dateTo: null, page: 1});
    }}); }),
  ]);
}

let periodAudience = null;
function renderAudienceSummary(data) {
  const host = $('#sd-audience-summary'); host.innerHTML = '';
  if (!data) return;
  const counts = periodAudience?.counts || {};
  const audienceRing = createAudienceRing({first: data.composition?.firstRecorded ?? counts.first ?? null,
    returning: data.composition?.returning ?? counts.returning ?? null, total: data.audience,
    format: value => value == null ? '暂无数据' : fmtMetric(value)}, host);
  const caption = audienceRing.querySelector('.sc-audience-label');
  caption.children[0].textContent = '本期去重观众';
  const viewList = el('span', 'sd-ring-link'); viewList.textContent = '查看名单'; caption.appendChild(viewList);
  for (const count of [caption.children[1], ...['.sc-first', '.sc-returning'].map(selector => audienceRing.querySelector(selector)?.children[1])]) {
    if (count?.children[0]) count.children[0].textContent = ' 人';
  }
  for (const [selector, value, label] of [['.sc-first', data.composition?.firstRecorded ?? counts.first, '本期首次记录'],
    ['.sc-returning', data.composition?.returning ?? counts.returning, '历史回访']]) {
    const segment = audienceRing.querySelector(selector);
    const name = segment.children[0]; name.textContent = '';
    const dot = el('i'); dot.setAttribute('aria-hidden', 'true'); name.append(dot, label);
    const share = el('small', 'sd-audience-share');
    share.textContent = data.audience > 0 && value != null ? (value / data.audience * 100).toFixed(1) + '%' : '占比暂无数据';
    const valueRow = el('div', 'sd-audience-value'); valueRow.append(segment.children[1], share); segment.appendChild(valueRow);
  }
  for (const [selector, group, label] of [['.sc-audience-ring', 'all', '全部观众'], ['.sc-first', 'first', '首次记录'], ['.sc-returning', 'returning', '回访']]) {
    const target = audienceRing.querySelector(selector);
    target.setAttribute('role', 'link'); target.setAttribute('tabindex', '0'); target.setAttribute('aria-label', label + '，查看名单');
    const activate = () => { location.hash = audienceHash({group, sort: 'attendance', page: 1}); };
    target.addEventListener('click', activate);
    target.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); activate(); } });
  }
  if (!data.composition) {
    const composition = el('p', 'hint'); composition.textContent = '本期构成：暂无数据';
    composition.title = data.firstRecordedAt == null ? '尚未记录到带名单的场次'
      : data.audience == null ? '所选时段内没有带名单的场次，无法区分首次记录与回访'
      : '名单从 ' + insightsTime(data.firstRecordedAt, false) + '起才开始记录，此前来过的观众无法区分';
    host.appendChild(composition);
  }
  const interaction = panelLink('', 'audience', {group: 'interaction'}); interaction.className = 'sd-interaction';
  const heading = el('div', 'sd-interaction-head');
  const label = el('span'); label.textContent = '本期互动观众';
  const description = el('small'); description.textContent = '本期到场观众中有互动记录的身份'; label.appendChild(description);
  const count = el('strong'); count.textContent = data.interactionAudience == null ? '暂无数据' : fmtMetric(data.interactionAudience) + ' 人';
  heading.append(label, count); interaction.appendChild(heading);
  const share = data.audience > 0 && data.interactionAudience != null && data.interactionAudience <= data.audience ? data.interactionAudience / data.audience * 100 : null;
  const progress = el('div', 'sd-interaction-bar');
  const fill = el('span'); fill.style.width = (share ?? 0) + '%'; progress.appendChild(fill); interaction.appendChild(progress);
  const rate = el('small'); rate.textContent = share == null ? '互动占比暂不可计算' : '占本期观众 ' + share.toFixed(1) + '%'; interaction.appendChild(rate); host.appendChild(interaction);
  const coverage = el('p', 'hint sd-audience-note');
  const coverageSummary = coverageText(data.coverage);
  coverage.textContent = (coverageSummary ? coverageSummary + '。' : '') + '人数仅为记录到的下界，缺失名单不补估。'; host.appendChild(coverage);
  const note = el('p', 'hint sd-audience-note'); note.textContent = '首次记录指系统首次记录，不等同第一次观看直播。';
  if (!data.composition) note.textContent += '当前历史名单不足，首次记录与回访构成暂无数据。';
  host.appendChild(note);
}

function markRecorded(cell, data, key) {
  if (!metricRecorded(data, key)) return;
  const label = el('small', 'sd-kpi-recorded'); label.textContent = '已记录'; cell.children[0].appendChild(label);
}

function renderInsights(data, message) {
  kpiResizeObserver?.disconnect(); kpiResizeObserver = null;
  renderComparison(data);
  const host = $('#sd-insights-content'); host.innerHTML = '';
  const note = el('p', 'hint'); note.id = 'sd-archive-note'; host.appendChild(note); renderArchiveNote();
  if (!data) { const hint = el('p', 'hint'); hint.textContent = message; host.appendChild(hint); renderDailyTrend(); return; }
  const count = value => value == null ? '暂无数据' : fmtMetric(value) + ' 人';
  const fans = data.fans;
  const kpiValues = [fmtDuration(data.durationSeconds), data.metricsKnown ? fmtMetric(data.revenue, {money: true}) + ' 元' : '暂无数据',
    data.metricsKnown ? fmtMetric(data.messages) + ' 条' : '暂无数据', count(data.audience), count(data.interactionAudience),
    fans?.change == null ? '暂无数据' : (fans.change > 0 ? '+' : fans.change < 0 ? '−' : '') + fmtMetric(Math.abs(fans.change)) + ' 人'];
  const keys = ['durationSeconds', 'revenue', 'messages', 'audience', 'interactionAudience', 'fans'];
  const labels = ['直播时长', '流水', '弹幕', '观众', '互动观众', '粉丝净增'];
  const grid = createKpis(keys.map((key, index) => ({label: labels[index], value: kpiValues[index], comparison: key === 'fans' ? '' : insightsComparison(data.comparisons?.[key])?.text || ''})), host);
  kpiResizeObserver = new ResizeObserver(entries => {
    for (const cell of entries.flatMap(entry => [...entry.target.children])) {
      const value = cell.querySelector('.sc-kpi-value');
      if (!value || !value.clientWidth) continue;
      value.style.fontSize = '';
      let size = parseFloat(getComputedStyle(value).fontSize);
      while (value.scrollWidth > value.clientWidth && size > 16) {
        size = Math.max(16, size - .5);
        value.style.fontSize = size + 'px';
      }
    }
  });
  kpiResizeObserver.observe(grid);
  [...grid.children].forEach((cell, index) => {
    markRecorded(cell, data, keys[index]);
    if (keys[index] === 'fans' && fans) {
      cell.children[2].textContent = fans.partial
        ? '可用快照未覆盖整个范围，从 ' + insightsTime(fans.fromAt) + ' 起算'
        : '按区间边界有效快照计算';
    }
    cell.setAttribute('role', 'button'); cell.setAttribute('tabindex', '0'); cell.setAttribute('aria-label', labels[index]);
    const comparison = keys[index] === 'fans' ? null : insightsComparison(data.comparisons?.[keys[index]]);
    if (comparison) cell.children[2].style.color = comparison.color;
    if (keys[index] === 'fans') cell.title = fans ? (fans.partial ? '可用快照未覆盖整个范围；' : '') + insightsTime(fans.fromAt) + ' → ' + insightsTime(fans.toAt) : '可用的粉丝数快照不足两次';
    const activate = () => {
      if (index < 3) location.hash = dashboardHash({panel: 'trend', metric: keys[index], period: 'day', mode: 'total', page: 1, from: null, to: null, starts: null});
      else location.hash = dashboardHash({panel: index === 5 ? 'fans' : 'audience', group: index === 4 ? 'interaction' : 'all', sort: 'attendance', page: 1, starts: null, from: null, to: null});
    };
    cell.addEventListener('click', activate);
    cell.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(); } });
  });
  const more = el('details'); more.id = 'sd-more';
  const summary = el('summary'); summary.textContent = '更多指标'; more.appendChild(summary);
  const hours = data.durationSeconds / 3600;
  const moreGrid = createKpis([{label: '场次', value: fmtMetric(data.sessions)},
    {label: '新增关注', value: data.follows == null ? '—' : fmtMetric(data.follows) + ' 人'},
    {label: '每小时流水', value: hours > 0 && data.revenue != null ? fmtMetric(data.revenue / hours, {money: true}) + ' 元/时' : '—'},
    {label: '每小时弹幕', value: hours > 0 && data.messages != null ? fmtMetric(data.messages / hours, {money: true}) + ' 条/时' : '—'}], more);
  [...moreGrid.children].forEach((cell, index) => markRecorded(cell, data, ['sessions', 'follows', 'revenuePerHour', 'messagesPerHour'][index]));
  host.appendChild(more);
  kpiResizeObserver.observe(moreGrid);
  renderDailyTrend();
}

function trendFormat(metric, mode) {
  const [, , unit, canRate] = trendMetrics.find(([key]) => key === metric);
  const hourly = mode === 'hourly' && canRate;
  return value => value == null ? '—' : metric === 'durationSeconds' ? fmtDuration(value)
    : fmtMetric(value, {money: metric === 'revenue' || hourly}) + ' ' + unit + (hourly ? '/小时' : '');
}

function trendControls(host, {metric, mode, period, onMetric, onMode, onPeriod}) {
  const controls = el('div', 'sd-trend-controls'); host.appendChild(controls);
  if (period) {
    const periods = el('div', 'lgpills'); controls.appendChild(periods);
    for (const [key, name] of [['day', '按统计日'], ['week', '按统计周'], ['month', '按统计月']]) {
      const link = el('a', 'nv-pill lgpill'); link.textContent = name; link.href = onPeriod(key);
      if (key === period) link.setAttribute('aria-current', 'page'); periods.appendChild(link);
    }
  }
  const label = el('label'); label.textContent = '指标 ';
  const select = el('select'); select.setAttribute('aria-label', '趋势指标');
  for (const [key, name] of trendMetrics) { const option = el('option'); option.value = key; option.textContent = name; select.appendChild(option); }
  select.value = metric; select.addEventListener('change', () => onMetric(select.value));
  label.appendChild(select); controls.appendChild(label);
  const canRate = trendMetrics.find(([key]) => key === metric)[3];
  if (!canRate) return controls;
  const modes = el('div', 'lgpills'); modes.setAttribute('aria-label', '趋势数值'); controls.appendChild(modes);
  for (const [key, name] of [['total', '总量'], ['hourly', '每小时']]) {
    const button = el('button', 'nv-pill'); button.type = 'button'; button.textContent = name;
    button.disabled = key === 'hourly' && !canRate;
    button.setAttribute('aria-pressed', String(key === (canRate ? mode : 'total')));
    button.addEventListener('click', () => { if (!button.disabled) onMode(key); }); modes.appendChild(button);
  }
  return controls;
}

function hideOffControl(host, checked, onChange) {
  const bar = el('div', 'sd-trend-daily'); host.appendChild(bar);
  const title = el('strong'); title.textContent = '开播日趋势'; bar.appendChild(title);
  const label = el('label', 'sd-trend-hide');
  label.title = '只显示当前指标有有效数值的开播统计日，保留真实的 0；原始日期和数据可在明细中查看。';
  const input = el('input'); input.type = 'checkbox'; input.checked = checked;
  input.addEventListener('change', () => onChange(input.checked));
  label.append(input, '隐藏未播与无效日期'); bar.appendChild(label);
}

function trendNote(host, period, hideOff, points) {
  const note = el('p', 'sd-trend-note');
  let drawing = period === 'day' ? '整场指标按开播统计日归属，不拆分整场；未播或无有效值时断开，不画虚线。'
    : '整场指标按开播统计日归属，不拆分整场；按统计周期绘制柱图，空周期保留。';
  if (period === 'day' && hideOff) {
    const off = points.filter(point => point.sessions === 0).length;
    const invalid = points.filter(point => point.sessions !== 0 && !Number.isFinite(point.value)).length;
    drawing = '已隐藏 ' + fmtMetric(off) + ' 个未播日、' + fmtMetric(invalid) + ' 个无有效值日期；日期间距已压缩，实线连接保留点，原始明细和统计不变。';
  }
  note.textContent = drawing; host.appendChild(note);
}

function trendPoints(buckets, metric, mode) {
  return buckets.map(bucket => ({...bucket, value: bucket.sessions > 0 ? trendValue(bucket, metric, mode) : null}));
}

function trendSessionHash(interval, buckets) {
  const bucket = buckets.find(item => item.start === interval.start && item.end === interval.end);
  return dashboardHash({panel: 'sessions', from: interval.start, to: interval.end,
    starts: bucket?.starts?.join(',') ?? null, source: 'trend', sourceDay: null, dateFrom: null, dateTo: null, page: 1});
}

function trendSessions(interval, buckets) {
  location.hash = trendSessionHash(interval, buckets);
}

function trendPeriod(bucket, period) {
  if (!Number.isFinite(bucket.start) || !Number.isFinite(bucket.end)) return '—';
  const start = dateAt(bucket.start), end = statisticalDate(bucket.end - 1);
  const short = date => date.slice(5).replace('-', '/');
  return short(start) + (period !== 'day' && end !== start ? '–' + short(end) : '');
}

function renderDailyTrend() {
  trendChart?.destroy(); trendChart = null;
  const host = $('#sd-trend-content'); host.innerHTML = '';
  const controls = trendControls(host, {metric: insightsMetric, mode: insightsMode,
    onMetric: metric => { insightsMetric = metric; renderDailyTrend(); },
    onMode: mode => { insightsMode = mode; renderDailyTrend(); }});
  const periodLabel = el('span', 'hint'); periodLabel.textContent = '按统计日汇总'; controls.appendChild(periodLabel);
  hideOffControl(host, insightsHideOff, checked => {
    insightsHideOff = checked; location.hash = dashboardHash({hideOff: checked ? '1' : null}); renderDailyTrend();
    updateHideOffLinks();
  });
  const detailLink = $('#sd-trend').querySelector('.sd-detail-link');
  if (detailLink) detailLink.href = dashboardHash({panel: 'trend', period: 'day', metric: insightsMetric, mode: insightsMode, hideOff: insightsHideOff ? '1' : null, page: 1});
  if (!insightsData) return;
  const buckets = (insightsData.daily || []).map(day => {
    const start = day.start ?? Date.parse(day.date + 'T06:00:00+08:00');
    return {...day, start, end: day.end ?? start + 86400000, label: day.date};
  });
  trendChart = createChart(host, {title: '按统计日的' + trendMetrics.find(([key]) => key === insightsMetric)[1] + '走势',
    points: trendPoints(buckets, insightsMetric, insightsMode), hideOff: insightsHideOff, isOff: point => point.sessions === 0,
    format: trendFormat(insightsMetric, insightsMode), onSelect: interval => trendSessions(interval, buckets)});
  trendNote(host, 'day', insightsHideOff, trendPoints(buckets, insightsMetric, insightsMode));
}

async function loadTrendDrawer(current) {
  const generation = drawerGeneration;
  const period = where.period || 'day', metric = where.metric || 'revenue', mode = where.mode || 'total';
  const route = changes => dashboardHash({page: 1, ...changes});
  trendControls(current.body, {metric, mode, period,
    onPeriod: next => route({period: next}), onMetric: next => { location.hash = route({metric: next}); },
    onMode: next => { location.hash = route({mode: next}); }});
  renderRecording(current.body, insightsData);
  const content = el('div'); current.body.appendChild(content); content.textContent = '正在读取趋势…';
  try {
    const response = await api(streamerPath() + '/trend?period=' + period + '&days=' + where.days);
    if (generation !== drawerGeneration || !liveActive) return;
    if (!response.success) throw new Error(response.message || '未能读取趋势');
    content.textContent = '';
    const buckets = response.trend?.buckets || [];
    const format = trendFormat(metric, mode);
    const selected = trendMetrics.find(([key]) => key === metric);
    const chart = createSection(selected[1] + (mode === 'hourly' && selected[3] ? ' · 每小时' : ' · 总量'), '', content);
    if (period === 'day') hideOffControl(chart.box, where.hideOff === '1', checked => { location.hash = route({hideOff: checked ? '1' : null}); });
    createChart(chart.box, {title: period === 'day' ? '每日趋势，选择日期查看当天场次' : '点击周期查看场次', points: trendPoints(buckets, metric, mode), period,
      hideOff: where.hideOff === '1', isOff: point => point.sessions === 0, format,
      onSelect: interval => trendSessions(interval, buckets)});
    trendNote(chart.box, period, where.hideOff === '1', trendPoints(buckets, metric, mode));
    const section = createSection('周期明细', '', content);
    const scroll = el('div', 'sd-trend-table'); section.box.appendChild(scroll);
    const table = el('table'), head = el('thead'), tr = el('tr');
    for (const [index, name] of ['周期', '场次', '直播时长', ...(metric === 'durationSeconds' ? [] : [selected[1] + (mode === 'hourly' && selected[3] ? ' / 小时' : '')]), '统计覆盖'].entries()) {
      const th = el('th', name === '统计覆盖' ? 'sd-trend-coverage' : index ? 'n' : ''); th.textContent = name; tr.appendChild(th);
    }
    head.appendChild(tr); table.appendChild(head);
    const ordered = buckets.slice().reverse(), pages = Math.max(1, Math.ceil(ordered.length / 10));
    const page = Math.min(pages, where.page), body = el('tbody');
    for (const bucket of ordered.slice((page - 1) * 10, page * 10)) {
      const row = el('tr');
      if (bucket.sessions > 0) {
        row.setAttribute('tabindex', '0'); row.setAttribute('role', 'link');
        row.setAttribute('aria-label', trendPeriod(bucket, period) + '，查看场次');
        const activate = () => trendSessions(bucket, buckets);
        row.addEventListener('click', activate);
        row.addEventListener('keydown', event => { if (event.key === 'Enter' && event.target === row) { event.preventDefault(); activate(); } });
      }
      for (const [index, value] of [trendPeriod(bucket, period), fmtMetric(bucket.sessions), fmtDuration(bucket.durationSeconds), ...(metric === 'durationSeconds' ? [] : [format(trendValue(bucket, metric, mode))])].entries()) {
        const cell = el('td', index ? 'n' : '');
        if (index === 0 && bucket.sessions > 0) {
          const link = el('a', 'sd-period-link'); link.textContent = value; link.href = trendSessionHash(bucket, buckets);
          link.addEventListener('click', event => event.stopPropagation()); cell.appendChild(link);
        } else cell.textContent = value;
        row.appendChild(cell);
      }
      const coverage = el('td', 'sd-trend-coverage');
      const badge = el('span', 'sd-gap-badge'); badge.textContent = trendCoverageBadge(bucket, period);
      const details = [bucket.sessions > 0 ? '采集 ' + durationCoverage(bucket) : '采集暂无数据',
        ...(period === 'day' ? [monitoringDayText(bucket)] : []),
        '名单完整 ' + fmtMetric(bucket.coverage?.complete ?? 0) + '/' + fmtMetric(bucket.sessions) + ' 场'].join(' · ');
      badge.title = details; badge.setAttribute('tabindex', '0'); badge.setAttribute('aria-label', badge.textContent + '：' + details);
      coverage.appendChild(badge); row.appendChild(coverage);
      body.appendChild(row);
    }
    table.appendChild(body); scroll.appendChild(table);
    const pager = el('div', 'sd-pagination'); section.box.appendChild(pager);
    const note = el('span', 'hint'); note.textContent = '共 ' + fmtMetric(ordered.length) + ' 条 · 第 ' + fmtMetric(page) + '/' + fmtMetric(pages) + ' 页'; pager.appendChild(note);
    for (const [label, target] of [['‹ 上一页', page - 1], ['下一页 ›', page + 1]]) {
      if (target < 1 || target > pages) continue;
      const link = el('a'); link.textContent = label; link.href = route({page: target}); pager.appendChild(link);
    }
  } catch (error) {
    if (generation === drawerGeneration && liveActive) content.textContent = '读取失败：' + error.message;
  }
}

/**
 * 概况顶上那条小横条
 *
 * 单独一个口子，是因为运行状态与详情各走各的一趟：先回来的那一趟画完之后，
 * 另一趟回来时补上这一条。合在渲染里的话，运行状态晚回来的那几次这条横条就不出现了。
 */
function renderBanner() {
  const host = $('#sd-body');
  const old = $('#sd-banner');
  if (old) old.remove();

  if (store.role === 'MEMBER') return;
  const text = totalDataBanner(runtime);
  if (!text || where.view !== 'detail') return;

  const banner = el('div', 'banner b-dim');
  banner.id = 'sd-banner';
  banner.textContent = text;
  host.insertBefore(banner, host.firstChild);
}

function renderOverview(host) {
  const overview = detail.overview || {};

  const stats = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '累计 ';
  const note = el('span', 'h-note');
  note.textContent = '该主播在归档中的全部场次';
  head.appendChild(note);
  stats.appendChild(head);

  const box = el('div', 'stats');
  box.innerHTML = [
    [String(overview.sessions || 0), '总场次'],
    [fmtDuration(overview.durationSeconds), '总时长'],
    // 一场都没有时服务端给 null，这里就写破折号：「场均 0 分」读起来像每场都秒退
    [overview.averageDurationSeconds === null || overview.averageDurationSeconds === undefined
      ? '—' : fmtDuration(overview.averageDurationSeconds), '场均时长'],
    [pageTime(overview.lastStart), '最近一场'],
  ].map(([value, label]) => '<div class="stat"><div class="stat-v">' + esc(value)
    + '</div><div class="stat-l">' + esc(label) + '</div></div>').join('');
  stats.appendChild(box);
  host.appendChild(stats);

  host.appendChild(snapshotCard(overview.snapshot, overview.snapshotMetrics));

}

/** 只读当前这一场；失败时保留上一次成功读到的数据。 */
async function loadLive() {
  if (!canRefreshLive() || livePending === liveGeneration) return;
  const generation = liveGeneration;
  const wasLiving = isLiving();
  livePending = generation;
  try {
    const response = await request(CONSOLE + '/api/streamers/' + encodeURIComponent(where.platform) + '/'
      + encodeURIComponent(where.uid) + '/live?series=online_count');
    if (response.status === 401) {
      location.reload();
      return;
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error('HTTP ' + response.status);
    }
    if (generation !== liveGeneration || !canRefreshLive()) return;
    if (response.status < 200 || response.status >= 300
      || data?.success !== true || typeof data.living !== 'boolean') {
      throw new Error(data?.message || data?.error || 'HTTP ' + response.status);
    }
    live = data;
    liveError = '';
  } catch (error) {
    if (generation !== liveGeneration || !canRefreshLive()) return;
    liveError = error.message || '网络错误';
  } finally {
    if (livePending === generation) livePending = null;
  }
  renderLive();
  const recent = $('#sd-recent');
  if (recent && wasLiving !== isLiving()) { recent.innerHTML = ''; renderRecent(recent); }
  renderArchiveNote();
}

function liveClock(ms, seconds = false) {
  if (ms === null || ms === undefined) return '—';
  return new Intl.DateTimeFormat('en-GB', {timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit',
    ...(seconds ? {second: '2-digit'} : {}), hourCycle: 'h23'}).format(new Date(Number(ms)));
}

function isLiving() {
  return live ? live.living : !!detail?.living;
}

function renderArchiveNote() {
  const note = $('#sd-archive-note');
  if (!note) return;
  note.textContent = isLiving() ? '正在直播的场次下播后才计入' : '';
  note.hidden = !isLiving();
}

/** 概况中的本场卡片，独立更新，累计与归档卡片不使用这份数。 */
function renderLive() {
  const card = $('#sd-live');
  if (!card) return;
  card.hidden = !isLiving();
  card.innerHTML = '';
  if (card.hidden) return;

  const header = el('div', 'live-head');
  const head = el('h3');
  head.textContent = '正在直播';
  header.appendChild(head);
  const badge = el('span', 'sd-live-badge'); badge.textContent = '进行中'; header.appendChild(badge);
  const refresh = el('button', 'nv-btn2 sm');
  refresh.type = 'button';
  refresh.textContent = '刷新';
  refresh.addEventListener('click', loadLive);
  header.appendChild(refresh);
  card.appendChild(header);

  if (!live && !liveError) {
    const loading = el('p', 'hint'); loading.textContent = '正在读取当前直播快照…'; card.appendChild(loading);
    return;
  }
  const times = el('p', 'live-times');
  const since = live ? live.since : detail.since;
  times.textContent = '开播于 ' + pageTime(since)
    + (since != null ? ' · 已开播 ' + fmtDuration(Math.max(0, (Date.now() - since) / 1000)) : '');
  card.appendChild(times);

  const catalog = detail.liveMetrics || detail.sessions?.metrics || [];
  const labels = new Map(catalog.map(one => [one.key, one]));
  const metrics = {...(live?.metrics || {})};
  // 报告图的在线人数使用 online_count：分钟内取最大，最新分钟的值不是本场峰值。
  const online = live?.series?.online_count || [];
  if (detail.platform === 'bilibili') {
    delete metrics.online_count;
    delete metrics.online_rank_count;
  }
  const rows = snapshotRows(metrics, catalog);
  if (detail.platform === 'bilibili') {
    rows.push({key: 'online_count', name: '在线人数', unit: '人', known: true,
      value: online.length ? online[online.length - 1][1] : null});
  }
  const primary = el('dl', 'sd-live-metrics');
  for (const [name, value, unit, money] of [['流水', live?.revenue, '元', true], ['弹幕', live?.messages, '条'], ['在线峰值', live?.metrics?.online_count, '人']]) {
    const row = el('div'), label = el('dt'), shown = el('dd'); label.textContent = name;
    shown.textContent = value == null ? '暂无数据' : fmtMetric(value, {money}) + ' ' + unit;
    row.appendChild(label); row.appendChild(shown); primary.appendChild(row);
  }
  card.appendChild(primary);
  const details = el('details', 'sd-live-details');
  const summary = el('summary'); summary.textContent = '查看本场快照明细'; details.appendChild(summary);
  const metricList = el('dl', 'kv');
  for (const row of rows) {
    const label = el('dt');
    label.textContent = row.known ? row.name : row.key;
    if (!row.known) label.title = '本机采集到一项插件尚未命名的指标，按原始键名显示';
    metricList.appendChild(label);
    const value = el('dd');
    value.textContent = fmtMetric(row.value, labels.get(row.key))
      + (row.value != null && row.unit ? ' ' + row.unit : '');
    metricList.appendChild(value);
  }
  details.appendChild(metricList);
  const checked = el('p', 'hint'); checked.textContent = '检查于 ' + liveClock(live?.checkedAt, true); details.appendChild(checked);
  card.appendChild(details);
  const status = el('p', 'live-status'); status.setAttribute('role', 'status');
  status.textContent = '数据截至 ' + pageTime(live?.dataAt) + ' · 每 15 秒检查更新'; card.appendChild(status);

  if (liveError) {
    const error = el('p', 'live-error');
    error.setAttribute('role', 'status');
    // 原因不拼进来；后半句说明屏幕上的数是哪一刻的，新一场还没取到时不拿上一场的数冒充
    error.textContent = REFRESH_FAILED + (live
      ? '，当前显示的是 ' + liveClock(live.checkedAt, true) + ' 的数据' : '，尚未获取到本场数据');
    card.appendChild(error);
  }
}

/**
 * 最近一次采到的基础数据
 * @param snapshot 接口给的 overview.snapshot
 * @param catalog 接口给的 overview.snapshotMetrics，指标叫什么由平台插件自报
 * @return {HTMLElement} 一张卡
 */
function snapshotCard(snapshot, catalog) {
  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '基础数据 ';
  const note = el('span', 'h-note');
  note.textContent = snapshot ? '采集于 ' + pageTime(snapshot.at) : '';
  head.appendChild(note);
  card.appendChild(head);

  const rows = snapshotRows(snapshot ? snapshot.metrics : null, catalog);
  if (!rows.length) {
    const empty = el('p', 'hint');
    empty.style.margin = '0';
    // 「从来没采到过」与「采到了但全是 0」是两回事，说清是哪一种
    empty.textContent = snapshot
      ? '本次采样没有任何数据。'
      : '尚未采集到该主播的基础数据：粉丝数等数据由平台插件按固定间隔采样保存。';
    card.appendChild(empty);
    return card;
  }

  const list = el('dl', 'kv');
  for (const row of rows) {
    const key = el('dt');
    // 目录里没有的键原样显示，并标一句：藏起来的话，插件新采了一项的那一天屏幕上不会有任何变化
    key.textContent = row.known ? row.name : row.key;
    if (!row.known) key.title = '本机采集到一项插件尚未命名的指标，按原始键名显示';
    list.appendChild(key);
    const value = el('dd');
    value.textContent = Math.round(row.value).toLocaleString('en-US') + (row.unit ? ' ' + row.unit : '');
    list.appendChild(value);
  }
  card.appendChild(list);
  return card;
}

/**
 * 概况里那张「最近几场」
 * @return {HTMLElement} 一张卡
 */
function recentCard() {
  const host = el('div', 'sd-recent'); host.id = 'sd-recent';
  renderRecent(host); return host;
}

function gapBadge(item) {
  const gaps = gapCells(item);
  const badge = el('span', 'sd-gap-badge');
  badge.textContent = gaps.any ? '缺 ' + fmtWholeGap(Math.max(gaps.maintenance, gaps.outage)) : '完整';
  badge.title = gaps.title || '未记录到采集缺口';
  return badge;
}

function sessionRevenue(item, page) {
  return page.revenueKeys?.length > 0 && item.metrics != null && Object.keys(item.metrics).length > 0
    ? page.revenueKeys.reduce((sum, key) => sum + (item.metrics[key] || 0), 0) : null;
}

let recentResizeObserver = null;

// 列表不参与额外高度的计算，避免加一行又把网格撑高、从而继续加行。
// 最低高度保留原来的 5 行（在播 3 行），剩余空间只放得下整行才显示。
function fitRecent() {
  const host = $('#sd-recent');
  if (!host || !host.getClientRects().length) return;
  const base = isLiving() ? 3 : 5;
  const wide = window.matchMedia('(min-width:901px)').matches;
  const rows = [...host.querySelectorAll('.sd-recent-row')];
  if (wide) for (const item of (detail.sessions?.items || []).slice(rows.length)) {
    const row = recentRow(item); host.appendChild(row); rows.push(row);
  }
  rows.forEach((row, index) => { row.style.display = index < base ? '' : 'none'; });
  host.style.minHeight = '';
  if (wide && rows.length) {
    const last = rows[Math.min(base, rows.length) - 1];
    host.style.minHeight = (last.getBoundingClientRect().bottom - host.getBoundingClientRect().top) + 'px';
    const bottom = host.getBoundingClientRect().bottom;
    for (const row of rows.slice(base)) {
      row.style.display = '';
      if (row.getBoundingClientRect().bottom > bottom + 0.5) { row.style.display = 'none'; break; }
    }
  }
  const count = rows.filter(row => row.style.display !== 'none').length;
  host.querySelector('h4').textContent = '最近 ' + count + ' 场归档';
}

function renderRecent(host) {
  const items = (detail.sessions?.items || []).slice(0, isLiving() ? 3 : 5);
  const title = el('h4'); title.textContent = '最近 ' + items.length + ' 场归档'; host.appendChild(title);
  if (!items.length) { const note = el('p', 'hint'); note.textContent = '暂无已归档的场次。每场直播下播时会自动归档一条记录。'; host.appendChild(note); }
  for (const item of items) host.appendChild(recentRow(item));
  return host;
}

function recentRow(item) {
  const link = el('a', 'sd-recent-row'); link.href = sessionHash(item.platform, item.uid, item.startTime);
  const left = el('div', 'sd-recent-time');
  const time = el('span'); time.textContent = pageTime(item.startTime, false);
  const duration = el('small'); duration.textContent = fmtDuration(item.durationSeconds); left.append(time, duration);
  const middle = el('div', 'sd-recent-main');
  const title = el('span'); title.textContent = sessionTitle(item).title || '未记录标题';
  const metrics = el('small');
  const money = sessionRevenue(item, detail.sessions || {});
  const messages = item.metrics?.[detail.sessions?.messageKey];
  metrics.textContent = '流水 ' + fmtMetric(money, {money: true}) + (money == null ? '' : ' 元') + ' · 弹幕 ' + fmtMetric(messages);
  if (item.interrupted) metrics.textContent += ' · ' + item.endReasonText;
  middle.append(title, metrics); link.append(left, middle, gapBadge(item));
  return link;
}

function sessionFilters(host, page) {
  const controls = el('div', 'sd-session-filters');
  const inputs = [];
  for (const [key, label] of [['dateFrom', '从'], ['dateTo', '到']]) {
    const wrapper = el('label'); wrapper.textContent = label;
    const input = el('input'); input.type = 'date'; input.setAttribute('aria-label', label === '从' ? '开始统计日' : '结束统计日');
    input.value = where[key] == null ? '' : statisticalDate(Number(where[key]) - (key === 'dateTo' ? 1 : 0));
    inputs.push(input); wrapper.appendChild(input); controls.appendChild(wrapper);
  }
  const apply = el('button', 'nv-btn2 sm'); apply.type = 'button'; apply.textContent = '筛选';
  const error = el('span', 'hint'); error.setAttribute('role', 'status');
  apply.addEventListener('click', () => {
    const [first, last] = inputs.map(input => input.value);
    const from = first ? Date.parse(first + 'T06:00:00+08:00') : null;
    const to = last ? Date.parse(last + 'T06:00:00+08:00') + 86400000 : null;
    if ((from != null && !Number.isFinite(from)) || (to != null && !Number.isFinite(to)) || (from != null && to != null && from >= to)) {
      error.textContent = '请选择有效的起止统计日'; return;
    }
    location.hash = dashboardHash({panel: 'sessions', dateFrom: from, dateTo: to, page: 1});
  });
  controls.appendChild(apply);
  if (where.dateFrom != null || where.dateTo != null || where.from != null || where.to != null || where.starts != null) {
    const clear = el('a', 'lnkbtn sd-clear-filter'); clear.textContent = '清除筛选';
    clear.href = dashboardHash({dateFrom: null, dateTo: null, from: null, to: null, starts: null, source: null, sourceDay: null, sourceArea: null, page: null});
    controls.appendChild(clear);
  }
  controls.appendChild(error); host.appendChild(controls);
  if (where.source || where.starts != null) {
    const source = el('p', 'hint');
    const name = {calendar: '开播日历（统计日重叠归档，含跨 06:00）', trend: '近期趋势', rhythm: '开播节奏', dashboard: '总览归档', area: '分区对比'}[where.source] || '所选场次';
    const interval = where.source === 'area' ? where.sourceArea : (where.sourceDay ? where.sourceDay.replaceAll('-', '/') : where.from && where.to ? intervalText(where.from, where.to) : '');
    source.textContent = '筛选来源：' + name + (where.source !== 'dashboard' && interval ? ' · ' + interval : '') + ' · ' + fmtMetric(page.total ?? 0) + ' 个场次'
      + (where.source === 'dashboard' ? ' · 与总览使用同一组场次' : '');
    host.appendChild(source);
  }
}

function renderSessions(host, page = {}) {
  const items = page.items || [];
  sessionFilters(host, page);
  const card = el('div', 'nv-card hcard sd-session-list');
  const head = el('h3'); head.textContent = '场次 · ' + fmtMetric(page.total ?? items.length) + ' 场'; card.appendChild(head);
  if (!items.length) { const empty = el('p', 'hint'); empty.textContent = '所选范围暂无已归档的场次。'; card.appendChild(empty); }
  for (const item of items) {
    const link = el('a', 'sd-session-row'); link.href = sessionHash(item.platform, item.uid, item.startTime);
    const top = el('div', 'sd-session-row-head');
    const date = el('span', 'sd-session-date'); date.textContent = pageTime(item.startTime);
    const title = el('strong'); title.textContent = sessionTitle(item).title || '未记录标题';
    const arrow = el('span', 'sd-session-arrow'); arrow.textContent = '›'; arrow.setAttribute('aria-hidden', 'true');
    top.append(date, title, arrow); link.appendChild(top);
    const bottom = el('div', 'sd-session-row-body');
    const metricsList = el('dl', 'sd-session-metrics');
    const money = sessionRevenue(item, page);
    for (const [label, value] of [['直播时长', fmtDuration(item.durationSeconds)],
      ['流水', fmtMetric(money, {money: true}) + (money == null ? '' : ' 元')],
      ['弹幕', fmtMetric(item.metrics?.[page.messageKey]) + (item.metrics?.[page.messageKey] == null ? '' : ' 条')], ['到场人数', item.audience == null ? '—' : fmtMetric(item.audience) + ' 人']]) {
      const group = el('div'), name = el('dt'), shown = el('dd'); name.textContent = label; shown.textContent = value;
      group.append(name, shown); metricsList.appendChild(group);
    }
    const quality = el('div', 'sd-session-quality');
    const coverage = el('span', 'sd-cell-note'); coverage.textContent = sessionCoverageText(item);
    quality.append(gapBadge(item), coverage);
    bottom.append(metricsList, quality); link.appendChild(bottom); card.appendChild(link);
    if (item.interrupted) {
      const reason = el('small', 'hint'); reason.textContent = item.endReasonText; link.appendChild(reason);
    }
  }
  const bar = pageBar(page), foot = el('div', 'sd-pagination'), text = el('span'); text.textContent = bar.text; foot.appendChild(text);
  if (bar.prev) foot.appendChild(pageLink('‹ 上一页', bar.prev));
  if (bar.next) foot.appendChild(pageLink('下一页 ›', bar.next));
  card.appendChild(foot);
  const note = el('p', 'sd-session-note'); note.textContent = '人数按已有观众名单去重，没有名单时显示「—」。采集缺口不补估，计数仅为记录到的下界；被中断或未闭合的场次应单独看待。'; card.appendChild(note);
  host.appendChild(card);
}

/**
 * 翻页那两个链接
 * @param label 写什么
 * @param page 去第几页
 * @return {HTMLElement} 一个链接
 */
function pageLink(label, page) {
  const link = el('a');
  link.href = dashboardHash({panel: 'sessions', page});
  link.textContent = label;
  return link;
}

// ---------------------------------------------------------------- 某一场

async function loadSession() {
  $('#sx-back').setAttribute('href', detailHash(where.platform, where.uid, 'sessions'));
  $('#sx-title').textContent = '单场报告';

  const host = $('#sx-body');
  host.innerHTML = '';

  const card = el('div', 'nv-card hcard');
  const head = el('h3');
  head.textContent = '报告 ';
  const note = el('span', 'h-note');
  note.textContent = '开播于 ' + pageTime(Number(where.start));
  head.appendChild(note);
  card.appendChild(head);

  const body = el('div');
  card.appendChild(body);
  host.appendChild(card);

  paint(body, reportView('loading'));

  let response;
  try {
    response = await request(CONSOLE + '/api' + reportPath(where.platform, where.uid, where.start));
  } catch (e) {
    paint(body, reportView('missing', '报告图获取失败：' + e.message));
    return;
  }

  if (!response.ok) {
    // 那一头回的是一句人话，它分得清「没开下播报告」与「早于留明细那个功能」。
    // 界面自己编一句只会把两种情形说成同一种
    let message = '';
    try {
      message = (await response.json()).message || '';
    } catch (e) {
      message = '';
    }
    paint(body, reportView('missing', message));
    return;
  }

  releaseReport();
  reportUrl = URL.createObjectURL(await response.blob());
  paint(body, reportView('ok'));
}

/**
 * 把三态摆上屏幕
 * @param host 容器
 * @param state 判法给的那一份
 */
function paint(host, state) {
  host.innerHTML = '';
  if (state.image) {
    const image = el('img');
    image.src = reportUrl;
    image.alt = '该场直播报告';
    image.style.cssText = 'max-width:100%;border-radius:var(--r-card);display:block';
    host.appendChild(image);
    return;
  }
  const text = el('p', 'hint');
  text.style.margin = '0';
  text.textContent = state.text;
  host.appendChild(text);
}
