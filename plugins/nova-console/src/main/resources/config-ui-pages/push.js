/**
 * 推送页：左边一棵「主播 → 通道」两级树，右边随选中项换成主播级或通道级
 *
 * 本文件随控制台插件走，由控制台按注册清单装载，落在顶级导航。
 * 三个导出就是与控制台之间的全部约定：render 建出页容器与抽屉、
 * refresh 按地址栏取数、leave 离开本页时收起抽屉。
 *
 * 本文件只管把 push-model.js 算好的东西摆上屏幕。树上标什么记号、模板算默认还是自定义、
 * 「通道设置」那四行摘要写什么，一律在那边判——判断留在这里的话，那几档就只能靠人
 * 打开页面挨个点着看，而其中好几档（命令被群管理员关掉、累计数据没开、群名取不到）
 * 在真机上凑齐一次的代价极高。
 *
 * 管理员从机器人给的名单里挑目标；成员输入完整号码，精确查到后才能添加。
 */

import {ask, keepTabInside} from './confirm.js';
import {$, api, CONSOLE, el, esc, markDirty, phrase, request, say, term} from './core.js';
import {resolveTarget, targetOptions} from './links-model.js';
import {
  atAllStatus, buildDirectory, channelIndex, channelName, channelSession, commandGroups, commandSummary,
  defaultMessages, handlerNames, handlerOf, layoutState, messageOf, noticeSwitches, optionStates, previewRequestBody,
  previewRevenueCaption, pushChannelOf, pushTree, quietSummary, recentPushes, restoredLayoutParams,
  revenueSummary, savedRevenueVisible,
  sessionOf, streamerName, strandedSessions, subscriptionSummary, templateState, typeName,
} from './push-model.js';
import {renderIncomplete, sessionSettings} from './sessions.js';
import {
  clearSessionDrafts, onRevenueDraftChange, revenueDraft, sessionDraftCount, sessionWrites,
} from './session-draft.js';
import {store} from './store.js';
import {detailHash} from './streamers-model.js';
import {buildLayoutEditor, buildTemplateEditor} from './template.js';
import {restoreDefaults, templateAdoption} from './template-model.js';

const isMember = () => store.role === 'MEMBER';

const PAGE_STYLE = `
/* ==================== QQ 推送 ====================
   左边一棵「主播 → 通道」两级树，右边随选中项换。版式取自 5.1 原型，
   色值一律引本文件顶部的 token，这一段里不写任何十六进制色。 */
.pushwrap{display:grid;grid-template-columns:236px 1fr;gap:16px;align-items:start}
.ptree{position:sticky;top:16px;max-height:calc(100vh - 32px);overflow:auto;overscroll-behavior:contain;background:var(--surface);
  border:1px solid var(--line);border-radius:var(--r-card);box-shadow:var(--shadow);padding:10px}
.ptop{display:grid;gap:6px;margin-bottom:10px}
.ptop>*{justify-self:stretch;text-align:left;background:var(--surface);color:var(--text);
  border:1px solid var(--line);border-radius:var(--r-ctl);padding:6px 10px;font-size:13.5px;
  cursor:pointer;font-family:inherit;text-decoration:none}
.ptop>*:hover{border-color:var(--accent);color:var(--accent)}
.ptree-t{font-size:12.5px;color:var(--dim);padding:4px 6px}
.tgroup{margin-bottom:2px}
.tnode{display:flex;align-items:center;gap:6px;width:100%;background:none;border:none;
  border-radius:var(--r-ctl);padding:6px 8px;font:inherit;font-size:14px;color:var(--text);
  cursor:pointer;text-align:left}
.tnode:hover{background:var(--soft)}
.tnode.on{background:var(--soft);font-weight:600}
.tnode.tchan{padding-left:24px;font-size:13.5px}
.tnode .caret{width:14px;flex:none;color:var(--dim);font-size:11px}
.tnode .tn-ico{flex:none;width:18px;height:18px;border-radius:var(--r-ctl);background:var(--soft);
  color:var(--dim);font-size:11.5px;display:inline-flex;align-items:center;justify-content:center}
.tnode .tn-nm{flex:1 1 5em;min-width:5em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* 名字和小标装进同一个行盒，放不下时整组小标换到名字下面一行，左边与名字对齐；
   一颗小标里的字始终排成一行，不折行，也不裁掉半颗 */
.tnode .tn-body{flex:1;min-width:0;display:flex;flex-wrap:wrap;align-items:center;gap:4px}
.tnode .tn-marks{flex:none;display:flex;flex-wrap:wrap;gap:4px}
.tn-marks .nv-pill{flex:none;white-space:nowrap;padding:1px 6px}
.tempty{padding:6px 8px 6px 24px;font-size:13px;color:var(--dim)}
.pnarrow{display:none;margin-bottom:12px}
.pnarrow label{display:block;font-size:13.5px;color:var(--dim);margin-bottom:4px}
.pnarrow select{width:100%;background:var(--surface);color:var(--text);border:1px solid var(--line);
  border-radius:var(--r-ctl);padding:8px 10px;font-size:14px;font-family:inherit}
.pnarrow-top{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:6px}
.pnarrow-top label{margin:0}
.pnarrow-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.pright{min-width:0}
/* 窄屏：左树摆不下，改成上面那个下拉。树不是「藏起来」而是换了一种画法——
   直接横过来摆的话，正文只剩半屏宽，而通道页四段每一段都是整行的开关 */
@media (max-width:1200px){
  .pushwrap{display:block}
  .ptree{display:none}
  .pnarrow{display:block}
}
.psec>h3{display:flex;align-items:center;gap:8px}
.psec-n{flex:none;width:20px;height:20px;border-radius:50%;background:var(--soft);
  color:var(--accent);font-size:12.5px;display:inline-flex;align-items:center;justify-content:center}
.psec h4{margin:18px 0 6px;font-size:13.5px;font-weight:600;color:var(--dim)}
.ptable{margin-bottom:8px}
.ptable td.n{font-variant-numeric:tabular-nums;color:var(--dim)}
.ptable td{max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.channel-index tbody tr{cursor:pointer}
.channel-index tbody tr:hover,.channel-index tbody tr:focus-within{background:var(--soft)}
.channel-index a{color:inherit}
.channel-index{width:100%;min-width:590px;table-layout:fixed}
.channel-index th:nth-child(1){width:17%}
.channel-index th:nth-child(2){width:22%}
.channel-index th:nth-child(3){width:136px}
.channel-index th:nth-child(5){width:80px}
.channel-index td:nth-child(1),.channel-index td:nth-child(2){white-space:normal;overflow-wrap:anywhere}
.channel-index td.p-notices{max-width:none;overflow:visible;white-space:normal}
.p-notice-tags{display:flex;flex-wrap:wrap;gap:4px}
.p-notice-tag{display:inline-block;border:1px solid var(--softline);background:var(--soft);
  border-radius:var(--r-pill);padding:1px 7px;font-size:11.5px;line-height:1.65;white-space:nowrap}
.ptable .good{color:var(--ok)}
.ptable .bad{color:var(--err)}
/* 最近推送那张表：只在卡片里横着滚，不把整页顶宽。表本身撑到内容宽，
   窄屏上就地横滚——整页一横滚，左树与底下的保存条跟着被推出屏外 */
.push-history-scroll{max-width:100%;overflow-x:auto;overscroll-behavior-x:contain}
.push-history-scroll>.ptable{width:max-content;min-width:100%}
/* 主播级的头 */
.shead{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.sav{width:40px;height:40px;border-radius:50%;flex:none;background:var(--line);object-fit:cover}
.sh-m{flex:1 1 180px;min-width:0}
.sh-nm{font-size:18px;font-weight:600}
.sh-id{color:var(--dim);font-size:13.5px}
.sh-side{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
/* 通道行 */
.rowcard{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:10px 0;
  border-top:1px solid var(--line)}
.rowcard:first-of-type{border-top:none}
.rc-main{flex:1 1 240px;min-width:0}
.rc-nm{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:15px}
.rc-nm a{color:inherit;text-decoration:none}
.rc-nm a:hover{color:var(--accent)}
.rc-sub{color:var(--dim);font-size:13px;margin-top:1px}
.rc-tg{display:flex;gap:5px;flex-wrap:wrap;margin-top:6px}
.rc-side{display:flex;align-items:center;gap:8px;flex:none}
.addrow{width:100%;margin-top:10px;background:none;border:1px dashed var(--line);color:var(--dim);
  border-radius:var(--r-ctl);padding:7px 12px;font-size:13.5px;cursor:pointer;font-family:inherit}
.addrow:hover{border-color:var(--accent);color:var(--accent)}
/* 通道级的头 */
.chead{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.ch-nm{font-size:18px;font-weight:600}
.ch-id{color:var(--dim);font-size:13.5px}
.chead .ghost{margin-left:auto}
.chead .channel-remove{margin-left:auto;color:var(--dim);text-decoration:none;padding:5px 0}
.chead .channel-remove:hover{color:var(--err);text-decoration:underline}
.channel-remove:focus-visible,.p-editor-fold>summary:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
/* 开关一行：左边开关、右边名字与一句说明 */
/* 字号分四档，从重到轻依次是栏目名、行名、摘要、说明：
   四下里挤成同一号字时，一眼扫过去分不出「哪一行是我要改的」与「这句只是解释」 */
.swrow{display:flex;align-items:flex-start;gap:10px;padding:8px 0;border-top:1px solid var(--line)}
.swrow:first-of-type{border-top:none}
.swrow>.switch{flex:none;margin-top:2px}
.swtxt{flex:1;min-width:0;font-size:13.5px}
.swtxt b{font-weight:600;font-size:13.5px}
.swtxt .dim{color:var(--dim);font-weight:400;font-size:12.5px}
.swtxt p{margin:2px 0 0;color:var(--dim);font-size:12.5px}
.weekly-report-tools{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin:0 0 10px 48px;
  padding:10px 12px;background:var(--soft);border:1px solid var(--softline);border-radius:var(--r-ctl)}
.weekly-report-tools>.hint{flex:1 1 180px;margin:0;font-size:12.5px}
.weekly-report-actions{display:flex;align-items:center;flex-wrap:wrap;gap:12px}
.weekly-report-actions>.ghost{padding:4px 10px;font-size:12.5px}
@media (max-width:600px){
  .pnarrow-top{align-items:flex-start;flex-direction:column}
  .weekly-report-tools{margin-left:0}
  .channel-index{min-width:0;display:block}
  .channel-index thead{display:none}
  .channel-index tbody{display:block}
  .channel-index tbody tr{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);
    gap:8px 12px;margin-bottom:12px;padding:12px;border:1px solid var(--line);border-radius:var(--r-ctl)}
  .channel-index td{display:block;height:auto;min-width:0;max-width:none;padding:0;border:0;
    white-space:normal;overflow:visible;overflow-wrap:anywhere}
  .channel-index td:nth-child(n+3){grid-column:1/-1}
  .channel-index td::before{content:attr(data-label);display:block;margin-bottom:3px;
    color:var(--dim);font-size:12px}
}
/* 群里本来就不列的那几条：淡下去但仍然看得见。整行藏掉的话，
   使用者会以为这条命令不存在，而它只是在这个会话里不列 */
.swrow.dimmed{opacity:.6}
.cmdmark{margin-left:8px;font-size:12.5px;color:var(--dim);font-weight:400}
/* 这一类命令的共用范围：整类一句，不与「不可关闭」那些旁注混在同一个标记里 */
.cmdscope{margin-left:8px;font-size:12px;color:var(--dim);font-weight:400;
  border:1px solid var(--softline);border-radius:var(--r-pill);padding:1px 7px;
  white-space:nowrap}
/* 不可关闭的那几行没有开关可画，开关那 38px 留同宽空位：各行文字列照旧对齐。
   「不可关闭」写进右边文字列的标记里，与「仅管理员」同一写法 */
.cmdslot{flex:none;width:38px}
/* 模板那一行的读态 */
.tplstate{display:flex;align-items:center;gap:10px;flex-wrap:wrap;background:var(--soft);
  border:1px solid var(--softline);border-radius:var(--r-ctl);padding:9px 12px;
  margin-bottom:10px;font-size:14px}
.tplstate>span:first-child{flex:1 1 auto}
/* 未改动时状态行旁那句小字：改过它就收起、换「恢复默认」出场 */
.tplstate .tplfree{color:var(--dim);font-size:12.5px}
.p-editor-fold{margin-top:10px;border-top:1px solid var(--line)}
.p-editor-fold>summary{display:flex;align-items:center;gap:8px;padding:11px 0 2px;cursor:pointer;
  list-style:none;font-size:13.5px;font-weight:500}
.p-editor-fold>summary::-webkit-details-marker{display:none}
.p-editor-fold>summary::before{content:"▸";color:var(--dim);font-size:11px}
.p-editor-fold[open]>summary::before{content:"▾"}
.p-editor-fold>summary::after{content:"展开";margin-left:auto;color:var(--dim);font-size:12px;font-weight:400}
.p-editor-fold[open]>summary::after{content:"收起"}
.p-editor-fold[open]>summary{padding-bottom:8px}
.p-editor-body{min-width:0}
.psec>.p-revenue-scope{margin:0;color:var(--dim);font-size:12.5px}
.tplrow{padding:10px 0;border-top:1px solid var(--line)}
.tplrow:first-of-type{border-top:none}
.tplbody{margin:0 0 8px;padding:9px 11px;background:var(--ground);border:1px solid var(--line);
  border-radius:var(--r-ctl);font-family:var(--mono);font-size:13.5px;white-space:pre-wrap;
  word-break:break-all}
/* 通道设置：四行摘要 */
.crow{border-top:1px solid var(--line)}
.crow:first-of-type{border-top:none}
.crow>summary{display:flex;align-items:baseline;gap:10px;padding:10px 0;cursor:pointer;
  list-style:none;font-size:14px}
.crow>summary::-webkit-details-marker{display:none}
.crow>summary::before{content:"▸";color:var(--dim);flex:none}
.crow[open]>summary::before{content:"▾"}
.cr-t{flex:none;min-width:76px;font-weight:600;font-size:14.5px}
.cr-sum{flex:1;min-width:0;color:var(--dim);font-size:13px}
.cr-body{padding:2px 0 14px 16px}
/* 命令分类也是一层原生折叠：点标题即开即收，不必再找「细调」那个按钮 */
.cgroup{border-top:1px solid var(--line);padding:2px 0}
.cgroup>summary.swrow{border-top:none;cursor:pointer;list-style:none;align-items:flex-start}
.cgroup>summary.swrow::-webkit-details-marker{display:none}
.cgroup>summary.swrow::after{content:"▸";color:var(--dim);flex:none;margin-left:auto;
  align-self:center;font-size:11px}
.cgroup[open]>summary.swrow::after{content:"▾"}
.cfine{padding-left:24px}
/* 每行末尾那颗问号：点开、再点关上；键盘 Tab 得到、Esc 关得掉，读屏按按钮念标题 */
.row-help{flex:none;align-self:center;width:22px;height:22px;padding:0;border-radius:50%;
  border:1px solid var(--line);background:var(--surface);color:var(--dim);font-size:12.5px;
  line-height:1;cursor:pointer;font-family:inherit}
.row-help:hover{border-color:var(--accent);color:var(--accent)}
.row-help[aria-expanded="true"]{border-color:var(--accent);color:var(--accent);background:var(--soft)}
.row-help-tip{position:fixed;z-index:30;max-width:min(320px,calc(100vw - 24px));
  padding:9px 12px;background:var(--surface);color:var(--text);border:1px solid var(--line);
  border-radius:var(--r-ctl);box-shadow:var(--shadow);font-size:12.5px;line-height:1.65}
.row-help-tip[hidden]{display:none}
.cmdline{border-top:1px solid var(--line)}
.cmdline:first-child{border-top:none}
.cmdline>.swrow{border-top:none}
.cmdline>.swrow.cmdsub{border-top:1px solid var(--line);padding-left:24px}
.strand{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:8px 0;
  border-top:1px solid var(--line);font-size:13.5px}
.strand:first-of-type{border-top:none}
/* @全体成员：状态行，不是设置 */
.statline{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;border-top:1px solid var(--line);
  padding:10px 0 2px;font-size:13.5px}
.statline .st-nm{flex:none;min-width:76px;font-weight:600;font-size:14px}
.statline .st-v{color:var(--dim)}
.statline .st-w{flex-basis:100%;color:var(--err)}
/* 挑选面板：盖住整页，一次只问一件事 */
/* 危险确认：盖住整页，一次只问一件事。z-index 高于挑选面板，避免抽屉还开着时确认被挡住 */
.drawer{position:fixed;inset:0;background:var(--overlay);display:flex;justify-content:flex-end;
  z-index:20}
.drawer[hidden]{display:none}
.drawer .dw{width:min(420px,100%);height:100%;overflow-y:auto;background:var(--surface);
  border-left:1px solid var(--line);padding:18px 20px}
.dw-hd{display:flex;align-items:center;gap:12px;margin-bottom:4px}
.dw-hd h3{margin:0;font-size:16px;flex:1}
.dwbar{display:flex;gap:8px;margin-bottom:10px}
.dwbar input{flex:1;min-width:0;background:var(--ground);color:var(--text);
  border:1px solid var(--line);border-radius:var(--r-ctl);padding:7px 10px;font-size:14px;
  font-family:inherit}
.dwbar .ghost{flex:none;padding:7px 12px;font-size:13.5px}
.dwlist{display:grid;gap:4px}
.dwrow{display:grid;gap:1px;text-align:left;background:none;border:1px solid transparent;
  border-radius:var(--r-ctl);padding:8px 10px;cursor:pointer;font-family:inherit}
.dwrow:hover:not(:disabled){background:var(--soft);border-color:var(--softline)}
.dwrow:disabled{cursor:not-allowed;opacity:.5}
.dw-nm{font-size:14.5px;color:var(--text)}
.dw-sub{font-size:12.5px;color:var(--dim)}
.dw-tag{font-size:12px;color:var(--dim)}
.dwform{display:grid;grid-template-columns:minmax(0,1fr);gap:8px;margin-bottom:12px}
.dwform input,.dwform select,.dwform button{height:38px}
.dwform input,.dwform select{min-width:0;width:100%;box-sizing:border-box;background:var(--ground);color:var(--text);border:1px solid var(--line);
  border-radius:var(--r-ctl);padding:8px 10px;font-size:14px;font-family:inherit}
.dwout{font-size:13.5px;color:var(--dim)}
.dwfound{display:grid;gap:4px;background:var(--soft);border:1px solid var(--softline);
  border-radius:var(--r-ctl);padding:10px 12px}
.dwfound span{font-size:13px;color:var(--dim)}
.dwfound button{margin-top:6px;justify-self:start}
.dwsub{padding:10px 0;border-top:1px solid var(--line)}
.dwsub:first-of-type{border-top:none}
.dwsub-h{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px;font-size:14px}
.dwsub-h span{color:var(--dim);font-size:13px}
.dwsub-h .ghost{margin-left:auto;padding:4px 10px;font-size:13px}
.dwpills{display:flex;gap:5px;flex-wrap:wrap}
.dwpills .nv-pill{cursor:pointer;font-family:var(--mono)}
.dwpills .nv-pill:hover{border-color:var(--err);color:var(--err)}

/* ---------- 模板编辑器：左边一张张消息卡，右边 QQ 气泡 ---------- */
/* 两栏，窄屏落成上下两段：气泡预览挤成一条竖线的话，它就答不了「群里长什么样」 */
.tpl-wrap{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:14px;
  margin-top:10px}
.tpl-left{min-width:0}
.tpl-right{min-width:0}
.tpl-tabs{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px}
.tpl-tab{background:none;border:1px solid var(--line);border-radius:var(--r-pill);
  padding:4px 14px;cursor:pointer;color:var(--dim);font-size:13.5px;font-family:inherit}
.tpl-tab.on{border-color:var(--softline);background:var(--soft);color:var(--accent)}
.tpl-note{margin:0 0 8px}
.tpl-note[hidden]{display:none}
/* 调色板 */
.tpl-pal{display:flex;gap:6px;flex-wrap:wrap;align-items:center;padding:8px 10px;
  background:var(--soft);border:1px solid var(--softline);border-radius:var(--r-ctl)}
.tpl-pal>.xs{flex:1 0 100%;margin-bottom:2px}
.tpl-blk{background:var(--surface);border:1px solid var(--softline);border-radius:var(--r-pill);
  padding:3px 11px;font-size:13px;font-family:var(--mono);color:var(--accent);cursor:grab}
.tpl-blk:disabled{opacity:.4;cursor:not-allowed}
.tpl-blk.att{border-style:dashed}
/* 一张卡＝一条消息 */
.tpl-cards{margin-top:10px}
.tpl-card{display:flex;gap:8px;padding:10px;border:1px solid var(--line);
  border-radius:var(--r-ctl);margin-bottom:8px;background:var(--surface)}
.tpl-card.over{border-color:var(--accent);background:var(--soft)}
.tpl-c-side{flex:none;display:flex;flex-direction:column;align-items:center;gap:4px}
.tpl-c-h{cursor:grab;color:var(--dim);font-size:14px;line-height:1;user-select:none}
.tpl-c-x{background:none;border:none;color:var(--dim);cursor:pointer;font-size:15px;padding:0;
  line-height:1;font-family:inherit}
.tpl-c-x:hover:not(:disabled){color:var(--err)}
.tpl-c-x:disabled{opacity:.35;cursor:not-allowed}
.tpl-c-main{flex:1 1 auto;min-width:0}
.tpl-c-n{font-size:12.5px;color:var(--dim);margin-bottom:4px}
.tpl-c-body{min-height:34px;padding:7px 9px;border:1px solid var(--line);
  border-radius:var(--r-ctl);background:var(--ground);font-size:14px;white-space:pre-wrap;
  word-break:break-word;line-height:1.9}
.tpl-c-body:focus{outline:2px solid var(--softline);outline-offset:-1px}
/* 药丸。整行的附件块自己占一行，与它在 QQ 里的样子一致 */
.tpl-pill,.tpl-att{display:inline-flex;align-items:center;gap:3px;border-radius:var(--r-pill);
  padding:1px 4px 1px 9px;margin:0 2px;font-size:13px;font-family:var(--mono);
  background:var(--soft);border:1px solid var(--softline);color:var(--accent);
  cursor:grab;user-select:none;vertical-align:baseline}
.tpl-att{display:flex;margin:4px 0;border-style:dashed;justify-content:space-between}
.tpl-x{background:none;border:none;color:inherit;cursor:pointer;font-size:13px;padding:0 3px;
  line-height:1;opacity:.55;font-family:inherit}
.tpl-x:hover{opacity:1;color:var(--err)}
.tpl-add{width:100%;background:none;border:1px dashed var(--line);color:var(--dim);
  border-radius:var(--r-ctl);padding:7px;cursor:pointer;font-size:13.5px;font-family:inherit}
.tpl-add:hover:not(:disabled){border-color:var(--accent);color:var(--accent)}
.tpl-add:disabled{opacity:.5;cursor:not-allowed}
/* 提醒方式在可用块上方，位置由可用块中的 @ 块选择 */
.tpl-at{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-bottom:8px;max-width:100%;font-size:13px}
.tpl-at-note{line-height:1.6}
.tpl-at-s{width:auto;min-width:0;max-width:100%;padding:6px 9px;border:1px solid var(--line);
  border-radius:var(--r-ctl);background:var(--ground);color:var(--text);font:inherit}
.tpl-at-warning{flex:1 0 100%;margin:3px 0 0;color:var(--warn);line-height:1.6}
.tpl-preview-note{margin:8px 0 0;line-height:1.6}
.template-diff{font-size:13px;margin:6px 0}
@media(pointer:coarse){
  .tpl-at-s{min-height:42px}
}
@media(max-width:520px){
  .tpl-at-s{font-size:12.5px}
}
/* 文本形式：给老使用者对照，只能看 */
.tpl-raw{margin-top:10px;font-size:13.5px;color:var(--dim)}
.tpl-raw>summary{cursor:pointer}
.tpl-raw>pre{margin:8px 0 4px;padding:9px 11px;background:var(--ground);
  border:1px solid var(--line);border-radius:var(--r-ctl);font-family:var(--mono);
  font-size:13px;white-space:pre-wrap;word-break:break-all;color:var(--text)}
/* 右边：QQ 气泡 */
.tpl-pv-h{display:flex;align-items:baseline;gap:8px;font-size:13.5px;color:var(--dim);
  margin-bottom:8px}
.tpl-pv-h>.xs{margin-left:auto}
.tpl-pv{padding:12px;background:var(--ground);border:1px solid var(--line);
  border-radius:var(--r-ctl)}
.qq-msg{display:flex;gap:8px;margin-bottom:10px}
.qq-av{flex:none;width:28px;height:28px;border-radius:8px;background:var(--brand-fill)}
.qq-bub{display:inline-block;max-width:100%;padding:7px 11px;border-radius:10px;
  background:var(--surface);border:1px solid var(--line);font-size:14px;
  white-space:pre-wrap;word-break:break-word}
.qq-at{color:var(--accent)}
.qq-atall{color:var(--accent2);font-weight:600}
/* 会被摘掉的那一次画成划掉：注在旁边而不是画出来的话，「配了却没 @ 到人」没有任何现象 */
.qq-atall.gone{text-decoration:line-through;opacity:.6}
.qq-ph{display:inline-block;padding:0 5px;margin:0 1px;border-radius:4px;background:var(--soft);
  color:var(--accent);font-family:var(--mono);font-size:12.5px}
.qq-img{margin-top:5px;padding:16px 10px;border:1px dashed var(--line);border-radius:8px;
  text-align:center;color:var(--dim);font-size:13px;background:var(--surface)}
.tpl-blk,.tpl-pill,.tpl-att,.qq-ph{font-family:var(--font)}
/* ---------- 报告版式：左边开关，右边照着开关画出来的那张图 ---------- */
.rep-wrap.rep-settings{grid-template-columns:minmax(0,1fr)}
.rep-wrap{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.1fr);gap:14px;
  margin-top:10px}
.rep-row{display:flex;align-items:flex-start;gap:10px;padding:7px 0;
  border-top:1px solid var(--line)}
.rep-row:first-of-type{border-top:none}
.rep-txt{min-width:0}
.rep-txt>b{font-size:14px;font-weight:500}
.rep-txt>p{margin:2px 0 0;font-size:13px;color:var(--dim)}
/* 当前金额设定下不出图的那几行：整行淡下去，原因那一行照旧读得清 */
.rep-row.rev-off{opacity:.6}
/* 跟着上一项开关走的子项：缩进挂在那一项下面，那一项关着时同样淡下去 */
.rep-row.rep-sub{padding-left:26px}
.rep-row.parent-off{opacity:.6}
/* 可排先后的那几项：上面「已启用卡片」的小块照占位符药丸，下面「未启用卡片」照调色板 */
.rep-board{padding:7px 0 7px 26px;border-top:1px solid var(--line)}
.rep-board>.rep-zone-t{margin:4px 0;font-size:12px;font-weight:400;color:var(--dim);line-height:1.5}
.rep-zone{display:flex;gap:6px;flex-wrap:wrap;align-items:center;min-height:34px;padding:6px 8px;
  border:1px solid var(--line);border-radius:var(--r-ctl);background:var(--ground)}
.rep-zone.over{border-color:var(--accent);background:var(--soft)}
.rep-zone-off{background:var(--soft);border-color:var(--softline)}
.rep-chip{display:inline-flex;align-items:center;gap:3px;border-radius:var(--r-pill);padding:2px 4px 2px 10px;
  font-size:13px;background:var(--soft);border:1px solid var(--softline);color:var(--accent);
  cursor:grab;user-select:none}
.rep-chip-off{background:var(--surface);padding:2px 10px;cursor:pointer}
.rep-chip:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.rep-chip.rev-off{opacity:.6}
.rep-chip-m{font-size:11px;opacity:.75}
.rep-chip-x{background:none;border:none;color:inherit;cursor:pointer;font-size:14px;padding:0 4px;
  min-width:22px;min-height:22px;line-height:1;opacity:.55;font-family:inherit}
.rep-chip-x:hover:not(:disabled){opacity:1;color:var(--err)}
.rep-board.locked .rep-zone{opacity:.6}
.rep-board.locked .rep-chip{cursor:not-allowed}
.rep-board>.rep-why{margin:6px 0 0;font-size:12.5px;color:var(--dim)}
.rep-txt>.rep-why{font-size:12.5px}
.rep-n{width:78px;flex:none;border:1px solid var(--line);border-radius:var(--r-ctl);
  padding:4px 8px;font-size:14px;font-family:inherit;background:var(--surface);color:var(--text)}
.rep-img{padding:10px;background:var(--ground);border:1px solid var(--line);
  border-radius:var(--r-ctl);min-height:120px}
.rep-shot{display:block;width:100%;height:auto;border-radius:6px}
.rep-view>.hint{margin:8px 2px 0;font-size:13px;color:var(--dim)}
@media (max-width:900px){
  .tpl-wrap,.rep-wrap{grid-template-columns:minmax(0,1fr)}
}
`;

/**
 * 把推送页自己的样式注入一次。附属脚本口只收 .js，样式进不了 assets。
 */
function ensureStyle() {
  if (document.getElementById('push-page-style')) return;
  const style = document.createElement('style');
  style.id = 'push-page-style';
  style.textContent = PAGE_STYLE;
  document.head.appendChild(style);
}

/**
 * 抽屉盖住整页，必须挂在 body 上。放进页容器的话，离开本页时容器 display:none，
 * 固定定位也看不见。
 */
function ensureDrawer() {
  if (document.getElementById('push-drawer')) return;
  const wrap = document.createElement('div');
  wrap.innerHTML = `
<div class="drawer" id="push-drawer" hidden>
  <div class="dw" role="dialog" aria-modal="true" aria-labelledby="push-drawer-title" tabindex="-1">
    <div class="dw-hd">
      <h3 id="push-drawer-title">面板</h3>
      <button class="ghost" type="button" id="push-drawer-close">关闭</button>
    </div>
    <p class="hint" id="push-drawer-lead"></p>
    <div id="push-drawer-body"></div>
  </div>
</div>`;
  document.body.appendChild(wrap.firstElementChild);
}

/** 是否正停在这一页。页内换选中项不重取名单，刚进来才取 */
let pageLive = false;

/** 本次进页已尝试补名的主播；查不回时等下次进页或重取配置再试。 */
let nameLookups = new Set();

/**
 * 建出推送页外壳。控制台只给一个空的 section#page-push。
 * @param section 控制台按落位建好的空容器
 */
export function render(section) {
  ensureStyle();
  ensureDrawer();
  section.innerHTML = `
    <div class="note" id="push-conflict" role="alert" hidden></div>
    <div id="sess-incomplete"></div>
    <div class="pnarrow">
      <div class="pnarrow-top">
        <label for="push-nav">选择主播或推送通道</label>
        <div class="pnarrow-actions">
          <a class="lnkbtn" href="#/push/default">默认模板</a>
          ${isMember() ? '' : '<button type="button" class="lnkbtn" id="push-add-streamer-narrow">＋ 添加主播</button>'}
        </div>
      </div>
      <select id="push-nav"></select>
    </div>
    <div class="pushwrap">
      <aside class="ptree">
        <div class="ptop">
          <a class="lnkbtn" id="push-default-tpl" href="#/push/default">默认模板</a>
          ${isMember() ? '' : '<button type="button" id="push-add-streamer">＋ 添加主播</button>'}
        </div>
        <div class="ptree-t">主播</div>
        <div id="push-tree"></div>
      </aside>
      <div class="pright" id="push-right"></div>
    </div>`;
  bindShell();
}

function routeFromHash() {
  const raw = (location.hash || '').replace(/^#\/?/, '');
  const cut = raw.indexOf('?');
  const path = cut < 0 ? raw : raw.slice(0, cut);
  const parts = path.split('/').filter(Boolean);
  return {sub: parts[1] || '', tail: parts[2] || ''};
}

/**
 * 按地址栏取这一页该看的那一份。顶级页刷新会带上 {sub, tail}。
 * 页内换选中项不重取名单与额度。
 */
export function refresh(route) {
  // 宿主整体刷新也会调用隐藏页；只有地址栏落在本页才算进入。
  if (!/^#\/?push(?:[/?]|$)/.test(location.hash || '')) return;
  const r = route && typeof route.sub === 'string' ? route : routeFromHash();
  const entering = !pageLive;
  pageLive = true;
  showPush(r.sub, r.tail);
  if (entering) return loadPushPage();
  // 整体载入末尾平台清单与配置才齐备，补上先前尚不能查询的昵称。
  return decoratePushData();
}

/** 离开本页时收起抽屉 */
export function leave() {
  pageLive = false;
  nameLookups = new Set();
  closeDrawer();
}

function bindShell() {
  $('#push-nav').addEventListener('change', event => {
    const value = event.target.value;
    if (!value) { location.hash = '#/push'; return; }
    if (value === 'default') { location.hash = '#/push/default'; return; }
    const parts = value.split(':');
    // 尾段原样接回去：通道身份里带着平台名，别按冒号截断
    location.hash = parts[0] === 's' ? '#/push/' + parts[1] : '#/push/' + parts[1] + '/' + parts.slice(2).join(':');
  });
  $('#push-add-streamer')?.addEventListener('click', addStreamer);
  $('#push-add-streamer-narrow')?.addEventListener('click', addStreamer);
  $('#push-drawer-close').addEventListener('click', closeDrawer);
  $('#push-drawer').addEventListener('click', event => {
    if (event.target === $('#push-drawer')) closeDrawer();
  });
  // aria-modal 只拦得住读屏：Esc 与 Tab 得自己接。听在面板上而不是整页上，
  // 面板之上再弹的确认框按 Esc 只关它自己
  $('#push-drawer').addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDrawer();
    } else if (event.key === 'Tab') {
      keepTabInside($('#push-drawer'), event);
    }
  });
}


/**
 * datasource.json 解析结果，改动直接作用其上以保留表单未覆盖的字段
 *
 * 这一份从前摆在宿主的 store 上，由宿主开机去取、由宿主按主播比出改动数。
 * 而本页随插件走：插件卸掉之后宿主仍会去取那份文件、仍按一份取不到的东西算数，
 * 屏幕上却不会有任何异常。现在它住在这里，宿主经改动条的供数方问（见 changeCount／save／reload）。
 */
let pushData = [];

/**
 * 上一次载入时的推送配置快照，序列化后的字符串
 *
 * 改动条要答「改了几处」，而本页的改动是直接落在 pushData 上的，没有逐项记账。
 * 拿它与快照比即可现算出条数。存序列化后的字符串而不是对象引用：存引用的话，
 * 它与 pushData 指向同一批对象，改动会同时反映到「快照」上——那时两边永远相等，
 * 改动条恒显示 0，而这件事在界面上看不出任何异常
 */
let pushSaved = '[]';
const userVersions = new Map();
const userLoads = new Map();
const conflictedUsers = new Set();
let datasourceGeneration = 0;
const CONFLICT_TEXT = '这位主播的配置刚被别人改过，请重新载入';
const userKey = user => user.platform + ':' + user.uid;
const userPath = user => '/datasource/users/' + encodeURIComponent(user.platform) + '/' + encodeURIComponent(user.uid);

function updateSavedUser(key, user) {
  const saved = JSON.parse(pushSaved);
  const index = saved.findIndex(item => userKey(item) === key);
  if (user === undefined) {
    if (index >= 0) saved.splice(index, 1);
  } else if (index >= 0) saved[index] = user;
  else saved.push(user);
  pushSaved = JSON.stringify(saved, dropDisplayOnly, 2);
}

async function loadUser(user) {
  if (!user?._summary) return;
  const key = userKey(user);
  if (userLoads.has(key)) return userLoads.get(key);
  const generation = datasourceGeneration;
  const loading = (async () => {
    const res = await api(userPath(user));
    if (!res.success || !res.user || !res.version) throw new Error(res.message || '主播配置载入失败');
    if (generation !== datasourceGeneration || !pushData.includes(user)) return;
    const full = {...res.user, _uname: user._uname, _roomId: user._roomId, _face: user._face};
    pushData[pushData.indexOf(user)] = full;
    userVersions.set(key, res.version);
    updateSavedUser(key, full);
  })();
  userLoads.set(key, loading);
  try { await loading; }
  finally { if (userLoads.get(key) === loading) userLoads.delete(key); }
}

function paintConflict() {
  const host = $('#push-conflict');
  if (!host) return;
  const user = pushData.find(item => String(item.uid) === String(picked.uid));
  host.innerHTML = '';
  host.textContent = '';
  host.hidden = !user || !conflictedUsers.has(userKey(user));
  if (host.hidden) return;
  host.textContent = CONFLICT_TEXT;
  const button = el('button', 'ghost');
  button.type = 'button';
  button.textContent = '重新载入';
  button.addEventListener('click', async () => {
    const saved = JSON.parse(pushSaved).find(item => userKey(item) === userKey(user));
    const hasDraft = JSON.stringify(user, dropDisplayOnly) !== JSON.stringify(saved, dropDisplayOnly);
    if ((hasDraft || sessionDraftCount() > 0) && !await ask({title: '丢弃未保存的草稿？',
      body: '重新载入会丢弃这位主播尚未保存的修改，包括通道设置里的金额可见、静音时段和命令开关；提醒订阅的改动保留。'})) return;
    try {
      const res = await api(userPath(user));
      if (!res.missing && (!res.success || !res.user || !res.version)) throw new Error(res.message || '重新载入失败');
      if (!pushData.includes(user)) return;
      const full = res.missing ? undefined
        : {...res.user, _uname: user._uname, _roomId: user._roomId, _face: user._face};
      if (full) {
        pushData[pushData.indexOf(user)] = full;
        userVersions.set(userKey(user), res.version);
      } else {
        pushData.splice(pushData.indexOf(user), 1);
        userVersions.delete(userKey(user));
        if (String(picked.uid) === String(user.uid)) location.hash = '#/push';
      }
      updateSavedUser(userKey(user), full);
      conflictedUsers.delete(userKey(user));
      markDirty();
      renderStreamers();
    } catch (e) { say(e.message, 'err'); }
  });
  host.appendChild(button);
}

document.addEventListener('visibilitychange', async () => {
  if (document.hidden || !pageLive) return;
  const user = pushData.find(item => String(item.uid) === String(picked.uid));
  if (!user || !userVersions.has(userKey(user))) return;
  const version = userVersions.get(userKey(user));
  try {
    const res = await api(userPath(user) + '/version');
    if (!pushData.includes(user) || version !== userVersions.get(userKey(user))) return;
    if (!res.success || res.version !== version) conflictedUsers.add(userKey(user));
    paintConflict();
  } catch (e) { say('主播配置检查失败：' + e.message, 'err'); }
});

/** 这一页最近一次取到的运行状态（/api/state），由 loadPushPage 刷新 */
let runtime = {commands: [], sessions: [], subscriptions: [], incomplete: [], totalDataAvailable: null};

/** 最近的推送记录，按通道筛出各自的 5 条 */
let pushHistory = [];

/** @全体成员 今日用量 */
let quota = null;

/** 机器人自己给的群与好友名单，摊成按键查的一张表 */
let directory = {};

/** 挑选目标时那份原样的可选项，认目标要用它 */
let options = [];

/** 加主播格子上的提示，与空着按下时那句共用：两处各写一份就会各说各的 */
export const STREAMER_INPUT_HINT = 'uid、直播间号，或粘贴空间／直播间链接';

/**
 * 地址栏定的选中项：主播 uid 与通道身份。
 * 身份三样齐全时认全三样；只有号（旧地址）时按号取第一个
 */
let picked = {uid: '', num: '', type: null, platform: ''};

/** 地址里的通道没找到、刚退回的那位主播的 uid；下一次重画落到那一级时说一声，重画一次即清 */
let lostChannelOf = '';

/** 树上展开了哪几位主播。收起状态不进地址栏——它是「翻到哪儿了」，不是「在看哪一个」 */
const expanded = new Set();

/** 通道的编辑区展开状态只保留在本次页面会话中，不写入配置或草稿。 */
const editorFolds = new Set();

/**
 * 通道在地址里怎么写
 *
 * 与加通道去重、名单键是同一把尺：「平台|类型码|号」。三样齐了才认得出同一个号下
 * 并存的群与好友——只带号的地址分不出它们，谁排在前面就开谁。
 */
function channelTail(item) {
  return item.platform + '|' + Number(item.type) + '|' + item.num;
}

/**
 * 地址里那一截通道身份：三段即「平台|类型码|号」，只有一段是旧地址的裸号
 *
 * 先解码再认：聊天软件转发、手工复制时「|」常被转成 %7C。解不开的（坏的 % 序列）照原样认。
 */
function parseChannelTail(tail) {
  let raw = String(tail || '');
  try {
    raw = decodeURIComponent(raw);
  } catch (e) {
    // 照原样认
  }
  const parts = raw.split('|');
  if (parts.length === 3 && parts[0] !== '' && parts[1] !== '' && parts[2] !== '') {
    return {platform: parts[0], type: Number(parts[1]), num: parts[2]};
  }
  return {platform: '', type: null, num: raw};
}

/**
 * 地址定的那一个通道
 *
 * 身份三样齐了只认全三样的那一个；只有号（旧地址、书签、手册里的例子）时按号取第一个。
 * 全三样都对不上时返回 null，不退回按号找：那个号下若是另一个类型，开它比不开更错。
 */
function pickChannel(list) {
  const items = list || [];
  if (picked.type !== null && picked.platform) {
    return items.find(item => String(item.platform) === String(picked.platform)
      && Number(item.type) === picked.type
      && String(item.num) === String(picked.num)) || null;
  }
  return items.find(item => String(item.num) === String(picked.num)) || null;
}

/**
 * 切到某一个落点
 *
 * 只认地址栏、不认「谁点了哪一行」：刷新、收藏、后退三种进法走的都是这一条路。
 * @param uid 主播 uid，或 default（默认模板），空串为通道一览
 * @param tail 通道身份（「平台|类型码|号」，或旧地址的裸号），空串为主播级
 */
export async function showPush(uid, tail) {
  const id = parseChannelTail(tail);
  picked = {uid: uid || '', num: id.num || '', type: id.type, platform: id.platform};
  if (picked.uid && picked.uid !== 'default') expanded.add(String(picked.uid));
  renderStreamers();
  const user = pushData.find(item => String(item.uid) === String(picked.uid));
  if (user?._summary) {
    try { await loadUser(user); renderStreamers(); }
    catch (e) { say(e.message, 'err'); }
  }
}

/**
 * 重画整页
 *
 * 名字沿用旧的那一个：设置页与初始设置页在存完推送配置之后调它，
 * 换个名字等于让那两处各自记得本页现在叫什么。
 */
export function renderStreamers() {
  paintConflict();
  renderIncomplete(runtime.incomplete || []);
  paintTree();
  paintRight();
}

/**
 * 取这一页要用的几份数据
 *
 * 一趟取齐而不是各块各取：命令是新的、名单是旧的这种自相矛盾的画面，
 * 在「为什么这个群不响应」这个问题上尤其误事。
 *
 * 名单与额度取不到不算这一趟失败：OneBot 掉线时它们本来就取不到，而推送配置照样看得见、
 * 也照样改得动——整趟判失败的话，屏幕上会只剩一句「载入失败」，配好的东西一个也看不见。
 */
export async function loadPushPage() {
  try {
    runtime = await api('/state');
  } catch (e) {
    say('运行状态加载失败：' + e.message, 'err');
  }

  try {
    pushHistory = (await api('/push-history')).records || [];
  } catch (e) {
    // 推送记录取不到只影响「这个通道最近推送」那一小块，那一块自己会说明
    pushHistory = [];
  }

  try {
    quota = await api('/at-all/quota');
  } catch (e) {
    quota = null;
  }

  try {
    if (isMember()) {
      options = [];
      directory = {};
      const targets = new Map(pushData.flatMap(user => user.targets || []).map(target => [channelTail(target), target]));
      for (const [key, target] of targets) {
        try {
          const found = await lookupTarget(target.platform, Number(target.type) === 1 ? 'group' : 'friend', target.num);
          if (found.success && found.found) directory[key] = {name: found.name || found.nickname || ''};
        } catch (e) {
          // 查不到名称时仍显示已配置的号码。
        }
      }
    } else {
      const [groups, friends] = await Promise.all([
        api('/bot/targets?type=group'), api('/bot/targets?type=friend')]);
      options = targetOptions(groups, friends);
      directory = buildDirectory(options);
    }
  } catch (e) {
    options = [];
    directory = {};
  }

  renderStreamers();
  await decoratePushData();
}

/**
 * 把运行状态交给本页
 *
 * 首页那一趟整体载入也会取一次 /state，交进来省下一次请求。
 * @param data /api/state 回包
 */
export function setRuntimeState(data) {
  runtime = data || runtime;
}

// ============ 左树 ============

function paintTree() {
  const host = $('#push-tree');
  const nav = $('#push-nav');
  const tree = pushTree(pushData, runtime.sessions, store.handlerList, directory);

  host.innerHTML = '';
  nav.innerHTML = '<option value="">通道一览</option><option value="default">默认模板</option>';

  if (!tree.length) {
    host.innerHTML = isMember() ? '<p class="hint">暂无分配给你的主播。</p>'
      : '<p class="hint">暂无主播，请点击上方「＋ 添加主播」开始配置。</p>';
    nav.value = picked.uid === 'default' ? 'default' : '';
    return;
  }

  for (const streamer of tree) {
    const group = el('div', 'tgroup');
    group.appendChild(streamerNode(streamer));

    nav.appendChild(navOption('s:' + streamer.uid, streamer.name));

    if (expanded.has(String(streamer.uid))) {
      for (const channel of streamer.channels) {
        group.appendChild(channelNode(streamer, channel));
      }
      if (!streamer.channels.length) {
        group.appendChild(el('div', 'tempty')).textContent = '暂无推送通道';
      }
    }
    for (const channel of streamer.channels) {
      nav.appendChild(navOption('c:' + streamer.uid + ':' + channelTail(channel), '　└ ' + channel.name));
    }

    host.appendChild(group);
  }

  // 选中项对上的是树上那一行的写法：旧地址要靠这一下才在下拉里停得住
  const current = tree.find(item => String(item.uid) === String(picked.uid));
  const onChannel = current ? pickChannel(current.channels) : null;
  nav.value = picked.uid === 'default' ? 'default'
    : picked.uid ? (onChannel ? 'c:' + picked.uid + ':' + channelTail(onChannel) : 's:' + picked.uid) : '';
}

function navOption(value, text) {
  const item = el('option');
  item.value = value;
  item.textContent = text;
  return item;
}

function streamerNode(streamer) {
  const on = String(streamer.uid) === String(picked.uid) && !picked.num;
  const node = el('button', 'tnode' + (on ? ' on' : ''));
  node.type = 'button';
  if (on) node.setAttribute('aria-current', 'true');

  const caret = el('span', 'caret');
  caret.textContent = expanded.has(String(streamer.uid)) ? '▾' : '▸';
  caret.addEventListener('click', event => {
    // 三角只管展开收起，不换页：点它是「看看它下面有什么」，不是「我要改它」
    event.stopPropagation();
    if (expanded.has(String(streamer.uid))) expanded.delete(String(streamer.uid));
    else expanded.add(String(streamer.uid));
    paintTree();
  });
  node.appendChild(caret);

  const body = el('span', 'tn-body');
  const name = el('span', 'tn-nm');
  name.textContent = streamer.name;
  body.appendChild(name);

  const marks = el('span', 'tn-marks');
  if (!streamer.enabled) marks.appendChild(pill('已停用'));
  else if (streamer.collectOnly) marks.appendChild(pill('仅采集'));
  body.appendChild(marks);
  node.appendChild(body);

  node.addEventListener('click', () => { location.hash = '#/push/' + streamer.uid; });
  return node;
}

function channelNode(streamer, channel) {
  // 高亮认的是「pickChannel 选中的就是这一行」：同一号的群与好友都长得像，
  // 只比号的话两行会一起亮，或者按谁排在前面亮错那行
  const on = String(streamer.uid) === String(picked.uid) && pickChannel(streamer.channels) === channel;
  const node = el('button', 'tnode tchan' + (on ? ' on' : ''));
  node.type = 'button';
  if (on) node.setAttribute('aria-current', 'true');

  const icon = el('span', 'tn-ico');
  icon.textContent = channel.type === 1 ? '群' : '友';
  node.appendChild(icon);

  const body = el('span', 'tn-body');
  const name = el('span', 'tn-nm');
  name.textContent = channel.name;
  body.appendChild(name);
  node.appendChild(body);

  node.addEventListener('click', () => {
    location.hash = '#/push/' + streamer.uid + '/' + channelTail(channel);
  });
  return node;
}

function pill(text, kind) {
  const node = el('span', 'nv-pill' + (kind ? ' ' + kind : ''));
  node.textContent = text;
  return node;
}

// ============ 右区 ============

function paintRight() {
  const host = $('#push-right');
  host.innerHTML = '';
  // 旧的那屏连着版式编辑器一起作废。金额草稿的重画监听是单槽位，不在这里摘掉的话，
  // 切走（含切到没有版式区的通道）后拨金额开关，已离开的编辑器还会按旧通道白发一次预览
  onRevenueDraftChange(null);
  const lost = lostChannelOf;
  lostChannelOf = '';

  if (picked.uid === 'default') {
    renderDefaultTemplates(host);
    return;
  }

  const user = (pushData || []).find(item => String(item.uid) === String(picked.uid));
  if (!picked.uid || !user) {
    renderIndex(host);
    return;
  }

  if (user._summary) {
    host.appendChild(el('p', 'hint')).textContent = '主播配置载入中…';
    return;
  }

  if (!picked.num) {
    if (lost === String(user.uid)) say('未找到地址中的通道（可能已删除），已返回该主播');
    renderStreamerLevel(host, user);
    return;
  }

  const target = pickChannel(user.targets || []);
  if (!target) {
    // 通道刚被删掉、或地址是抄来的：退到这位主播那一级，不留一屏空白。
    // 为什么落在这儿等改了地址、重画到主播那一级时再说：刚打开控制台时宿主载入完会清一次
    // 状态栏，在这里当场说的话会被那一下抹掉
    lostChannelOf = String(user.uid);
    location.replace('#/push/' + user.uid);
    return;
  }
  renderChannelLevel(host, user, target);
}

/** 一张卡的壳 */
function card(host, title, desc) {
  const box = el('div', 'nv-card hcard');
  if (title) {
    const head = el('h3');
    head.textContent = title;
    box.appendChild(head);
  }
  if (desc) {
    const note = el('p', 'hint');
    note.textContent = desc;
    box.appendChild(note);
  }
  host.appendChild(box);
  return box;
}

// ---- 通道一览 ----

function renderIndex(host) {
  const rows = channelIndex(pushData, runtime.sessions, store.handlerList, directory);
  const box = card(host, '通道一览');

  if (!rows.length) {
    box.appendChild(el('p', 'hint')).textContent =
      isMember() ? '暂无推送通道。请打开你名下的主播，为其添加群或好友通道。'
        : '暂无推送通道。请先在左侧添加主播，再为其选择群或好友。';
  } else {
    // 通知按标签换行；手机宽度下每条通道改成卡片。
    const wrap = el('div', 'tblwrap');
    const table = el('table', 'ptable channel-index');
    table.innerHTML = '<thead><tr><th scope="col">主播</th><th scope="col">通道</th><th scope="col">号码</th>'
      + '<th scope="col">已启用通知</th><th scope="col">模板</th></tr></thead><tbody>'
      + rows.map(row => '<tr><td data-label="主播"><a href="#/push/' + esc(row.uid) + '/' + esc(channelTail(row))
        + '" aria-label="' + esc(row.streamer + ' → ' + row.name) + '">' + esc(row.streamer) + '</a></td>'
        + '<td data-label="通道">' + esc(row.name) + '</td>'
        + '<td class="n" data-label="号码">' + esc(row.num) + '</td>'
        + '<td class="p-notices" data-label="已启用通知">' + (row.on.length ? '<span class="p-notice-tags">'
          + row.on.map(name => '<span class="p-notice-tag">' + esc(name) + '</span>').join('')
          + '</span>' : '<span class="dim">全部停用</span>') + '</td>'
        + '<td data-label="模板">' + (row.custom ? '自定义' : '默认') + '</td></tr>').join('')
      + '</tbody>';
    // 原生链接提供 Tab、回车与读屏名称；其余单元格点击走同一地址，无定位覆盖层。
    table.addEventListener('click', event => {
      if (event.target.closest('a')) return;
      if (window.getSelection()?.toString()) return;
      const row = event.target.closest('tbody tr');
      const link = row && row.querySelector('a');
      if (link) {
        const href = link.getAttribute('href');
        if (event.ctrlKey || event.metaKey) window.open(href, '_blank', 'noopener');
        else location.hash = href;
      }
    });
    wrap.appendChild(table);
    box.appendChild(wrap);
  }

  renderStranded(host);
}

/**
 * 配置里已经没有、状态文件里还留着的会话
 *
 * 仅列仍生效的会话命令开关与提醒订阅，供查看和清理。
 */
function renderStranded(host) {
  if (isMember()) return;
  const rows = strandedSessions(pushData, runtime.sessions);
  if (!rows.length) return;

  const box = card(host, '未配置推送但仍有状态记录的会话',
    '这些群或好友不在任何主播的推送通道中，因此不在上表中。'
    + '这里只列仍然生效的会话命令开关和提醒订阅。金额、静音和主播命令的旧记录仅供退回旧版使用。');

  for (const row of rows) {
    const line = el('div', 'strand');
    const text = el('span');
    text.textContent = row.platform + ' · ' + (row.type ? row.type + ' ' : '') + row.num
      + (row.disabled.length ? ' · 已禁用 ' + row.disabled.length + ' 条命令（'
        + row.disabled.join('、') + '）' : ' · 未禁用任何命令');
    line.appendChild(text);

    if (row.disabled.length) {
      const fix = el('button', 'ghost');
      fix.type = 'button';
      fix.textContent = '恢复这些命令';
      fix.addEventListener('click', () => restoreCommands(row.platform, row.typeCode, row.num, row.disabled));
      line.appendChild(fix);
    }
    box.appendChild(line);
  }
}

// ---- 默认模板 ----

/**
 * 默认模板：同一个编辑器，改的是「所有用默认的通道」那一份
 *
 * 改这里等于一次改一批群，因此页下把影响面逐条列出来——「改一次，所有用默认的通道
 * 一起变」是这一页的立身之本，而它同时意味着一次误改会同时落到一批群上。
 *
 * 存的是<b>相对出厂默认的覆盖</b>（见服务端 PushTemplateDefaults），不是整份参数：
 * 存整份的话，出厂默认此后再动这台机器一个也跟不上，而屏幕上它仍显示「默认」。
 */
function renderDefaultTemplates(host) {
  const box = card(host, '默认模板',
    isMember() ? '默认模板由管理员维护，这里只能看。'
      : '使用默认模板的通道随此处同步更新。如需单独调整某个通道，请在该通道中直接修改；修改后该通道不再随默认模板更新。');

  const handlers = (store.handlerList || []).filter(item => (item.placeholders || []).length);
  if (!handlers.length) {
    box.appendChild(el('p', 'hint')).textContent =
      '暂无带文字模板的通知，请检查对应插件是否已加载。';
    return;
  }

  if (isMember()) {
    buildTemplateEditor(box, {
      handlers, editable: false,
      base: handler => handler.factoryParams || handler.defaultParams,
      paramsOf: overridesOf,
      context: {isGroup: true, admin: null, terms: store.vocab}, channelLabel: '群聊',
    });
    renderAdoption(host, handlers);
    return;
  }

  // 这一页的草稿不进底部那条改动条：它存的不是推送配置，走的是自己的保存按钮
  const draft = {};
  const line = el('div', 'tplstate');
  const save = el('button', 'primary');
  save.type = 'button';
  save.textContent = '保存默认模板';
  save.disabled = true;
  line.appendChild(save);

  let currentHandler = handlers[0];

  const reset = el('button', 'ghost');
  reset.type = 'button';
  reset.textContent = '恢复出厂默认';
  reset.addEventListener('click', async () => {
    const name = currentHandler.displayName || currentHandler.className;
    if (!await ask({title: '恢复出厂默认？',
      body: '「' + name + '」的默认模板将恢复为出厂设置，使用默认模板的通道将同步恢复。'})) return;
    delete draft[currentHandler.className];
    saveDefaults(currentHandler, {}, save);
  });
  line.appendChild(reset);
  box.appendChild(line);

  save.addEventListener('click', () => saveDefaults(currentHandler,
    draft[currentHandler.className] || overridesOf(currentHandler), save));

  buildTemplateEditor(box, {
    handlers,
    editable: true,
    // 默认模板对着<b>出厂默认</b>比，比出来的差集正是要存下来的那一份覆盖
    base: handler => handler.factoryParams || handler.defaultParams,
    paramsOf: handler => draft[handler.className] || overridesOf(handler),
    context: {isGroup: true, admin: null, terms: store.vocab},
    channelLabel: '群聊',
    onSelect: handler => {
      currentHandler = handler;
      save.disabled = !draft[handler.className];
    },
    onChange: (handler, params) => {
      currentHandler = handler;
      draft[handler.className] = params;
      save.disabled = false;
      save.textContent = '保存默认模板';
    },
  });

  renderAdoption(host, handlers);
}

/**
 * 这一类通知此刻改过的那几个键
 *
 * 出厂默认与本机默认之差。服务端两份都给（factoryParams 与 defaultParams），
 * 合成一份的话，「改成了与出厂一样的值」与「没改过」就再也分不开。
 */
function overridesOf(handler) {
  const factory = handler.factoryParams || {};
  const now = handler.defaultParams || {};
  const out = {};
  for (const key of Object.keys(now)) {
    if (String(now[key]) !== String(factory[key])) out[key] = now[key];
  }
  return out;
}

/** 把改过的默认模板存下去 */
async function saveDefaults(handler, params, button) {
  if (isMember()) return;
  button.disabled = true;
  button.textContent = '保存中…';
  try {
    const res = await api('/templates', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({className: handler.className, params}),
    });
    if (!res.success) {
      say((res.message || '保存失败') + (res.issues ? '：' + res.issues.join('；') : ''), 'err');
      button.disabled = false;
      button.textContent = '保存默认模板';
      return;
    }
    say(res.message || '已保存', 'ok');
    // 重新取一遍处理器清单：默认变了，树上「模板：默认／自定义」那一列跟着变
    await reloadHandlers();
    renderStreamers();
  } catch (e) {
    say('保存失败：' + e.message, 'err');
    button.disabled = false;
    button.textContent = '保存默认模板';
  }
}

/** 默认改了之后，各通道是默认还是自定义要重新算 */
async function reloadHandlers() {
  try {
    const res = await api('/handlers');
    store.handlerList = res.handlers || store.handlerList;
    const index = await api('/datasource/users');
    if (!index.success || !Array.isArray(index.users)) throw new Error(index.message || '通道摘要载入失败');
    // 默认值变动后，尚未打开的通道也要重新判断模板状态。
    const saved = new Map(JSON.parse(pushSaved).map(user => [userKey(user), user]));
    for (const summary of index.users) {
      const at = pushData.findIndex(user => userKey(user) === userKey(summary));
      const user = pushData[at];
      if (!user?._summary || JSON.stringify(user, dropDisplayOnly) !== JSON.stringify(saved.get(userKey(user)))) continue;
      pushData[at] = {...summary, _summary: true, _uname: user._uname, _roomId: user._roomId, _face: user._face};
      updateSavedUser(userKey(user), pushData[at]);
    }
  } catch (e) {
    say('推送处理器重新读取失败，页面上的「默认／自定义」状态可能未更新：' + e.message, 'err');
  }
}

/** 谁在用默认、谁自定义了 */
function renderAdoption(host, handlers) {
  const box = card(host, '默认模板使用情况',
    isMember() ? '使用默认模板的通道会随管理员的修改同步更新；已自定义的通道不受影响。'
      : '在上方修改默认模板后，下列使用默认模板的通道将同步更新；已自定义的通道不受影响。');

  for (const handler of handlers) {
    const rows = templateAdoption(pushData, handler, store.vocab);
    const item = el('div', 'tplrow');
    item.appendChild(el('b')).textContent = handler.displayName || handler.className;
    item.appendChild(el('p', 'hint')).textContent =
      '使用默认 ' + rows.following.length + ' 个通道 · 自定义 ' + rows.custom.length + ' 个通道';
    if (rows.following.length) {
      item.appendChild(el('p', 'xs dim')).textContent = '同步更新：' + describe(rows.following);
    }
    if (rows.custom.length) {
      item.appendChild(el('p', 'xs dim')).textContent = '不受影响：' + describe(rows.custom);
    }
    box.appendChild(item);
  }
}

/** 把几个通道写成一句话，取的是屏幕上那个名字而不是号 */
function describe(rows) {
  return rows.map(row => {
    const session = sessionOf(runtime.sessions, row.platform, row.num, row.type);
    return channelName(session, row, directory);
  }).join('、');
}

// ---- 主播级 ----

function renderStreamerLevel(host, user) {
  const box = card(host, null, null);
  const head = el('div', 'shead');
  head.innerHTML = (user._face
      ? '<img class="sav" referrerpolicy="no-referrer" src="' + esc(user._face) + '" alt="">'
      : '<span class="sav"></span>')
    + '<div class="sh-m"><div class="sh-nm">' + esc(streamerName(user)) + '</div>'
    + '<div class="sh-id">uid ' + esc(user.uid)
    + (user._roomId ? ' · 直播间 ' + esc(user._roomId) : '') + '</div></div>';

  const side = el('div', 'sh-side');
  const enable = el('label', 'switch');
  enable.innerHTML = '<input type="checkbox"' + (user.enabled === false ? '' : ' checked')
    + '><span>启用</span>';
  enable.querySelector('input').addEventListener('change', event => {
    user.enabled = event.target.checked;
    markDirty();
    renderStreamers();
  });
  side.appendChild(enable);

  const remove = el('button', 'ghost danger');
  remove.type = 'button';
  remove.textContent = '删除主播';
  remove.addEventListener('click', async () => {
    if (!await ask({title: '删除该主播？',
      body: '「' + streamerName(user) + '」的 '
        + (user.targets || []).length + ' 个通道将一并删除，历史场次数据保留。'})) return;
    pushData.splice(pushData.indexOf(user), 1);
    expanded.delete(String(user.uid));
    markDirty();
    location.hash = '#/push';
  });
  if (!isMember()) side.appendChild(remove);
  head.appendChild(side);
  box.appendChild(head);

  if (user.enabled === false) {
    box.appendChild(el('div', 'note')).textContent =
      '该主播已停用：配置保留，但不采集数据，也不推送。';
  } else if (!(user.targets || []).length) {
    box.appendChild(el('div', 'note')).textContent =
      '该主播尚未配置推送通道，当前仅采集数据。添加通道后才会有人收到消息。';
  }

  const list = card(host, '推送通道', '每个通道对应一个群或一位好友。点击某一行进入，设置推送类型与消息模板。');
  for (const target of user.targets || []) {
    list.appendChild(channelRow(user, target));
  }

  const add = el('button', 'addrow');
  add.type = 'button';
  add.textContent = '＋ 添加通道';
  add.addEventListener('click', () => addChannel(user));
  list.appendChild(add);

  const lists = card(host, '这位主播自己的屏蔽名单',
    '设置页里还有一份全实例的，两份合起来用；这里只能多挡，挡不掉那一份里的。');
  for (const [key, title] of [['dynamicBlockWords', '动态屏蔽词'],
    ['wordCloudBlockWords', '词云屏蔽词'], ['wordCloudExcludeUids', '词云排除的观众 uid']]) {
    const field = el('div', 'field');
    const label = el('label');
    label.setAttribute('for', 'push-' + key);
    label.textContent = title;
    field.appendChild(label);
    const input = el('textarea');
    input.id = 'push-' + key;
    input.setAttribute('data-block-list', key);
    input.rows = 4;
    input.placeholder = key === 'wordCloudExcludeUids' ? '一行一个观众 uid（纯数字的正整数）' : '一行一个词';
    input.value = (Array.isArray(user[key]) ? user[key] : []).join('\n');
    input.addEventListener('input', () => {
      const entries = input.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
      const current = (Array.isArray(user[key]) ? user[key] : [])
        .map(value => String(value).trim()).filter(Boolean);
      // 空行不算改动，也不为原本缺失的字段添加空数组。
      if ((Object.hasOwn(user, key) && !Array.isArray(user[key]))
        || JSON.stringify(entries) !== JSON.stringify(current)) user[key] = entries;
      markDirty();
    });
    field.appendChild(input);
    lists.appendChild(field);
  }

  const data = card(host, '主播数据', '场次、趋势及每一场的详细报告均可在主播页查看。');
  const link = el('a', 'lnkbtn');
  link.href = detailHash(user.platform, user.uid);
  link.textContent = '打开 ' + streamerName(user) + ' 的数据 →';
  data.appendChild(link);
}

function channelRow(user, target) {
  const session = channelSession(sessionOf(runtime.sessions, target.platform, target.num, target.type), target, user);
  const switches = noticeSwitches(user, target, store.handlerList);
  const row = el('div', 'rowcard');

  const main = el('div', 'rc-main');
  const title = el('div', 'rc-nm');
  const link = el('a');
  link.href = '#/push/' + user.uid + '/' + channelTail(target);
  link.textContent = channelName(session, target, directory);
  title.appendChild(link);
  title.appendChild(pill(typeName(session, target)));
  if (session.disabledCount) {
    title.appendChild(pill('已禁用 ' + session.disabledCount + ' 条命令', 'err'));
  }
  // 与段 4 静音那一行的「静音中」同一份判法：认服务端的 quietActive，草稿改过档位时不认
  if (quietSummary(session, runtime.quietGlobal, savedQuiet(user, target)).active) {
    title.appendChild(pill('静音中', 'warn'));
  }
  main.appendChild(title);

  const sub = el('div', 'rc-sub');
  sub.textContent = (Number(target.type) === 1
    ? term('bot.target.group', '群号') : term('bot.target.user', '用户号')) + ' ' + target.num;
  main.appendChild(sub);

  const tags = el('div', 'rc-tg');
  for (const item of switches) {
    tags.appendChild(pill(item.displayName + (item.on ? ' 已启用' : ' 已停用'), item.on ? 'ok' : ''));
  }
  main.appendChild(tags);
  row.appendChild(main);

  const side = el('div', 'rc-side');
  side.appendChild(pill(templateState(target, store.handlerList).custom ? '模板：自定义' : '模板：默认'));
  const open = el('a', 'lnkbtn');
  open.href = '#/push/' + user.uid + '/' + channelTail(target);
  open.textContent = '打开';
  side.appendChild(open);
  row.appendChild(side);

  return row;
}

// ---- 通道级：四段 ----

function renderChannelLevel(host, user, target) {
  const session = channelSession(sessionOf(runtime.sessions, target.platform, target.num, target.type), target, user);
  const name = channelName(session, target, directory);

  const head = card(host, null, null);
  const line = el('div', 'chead');
  const back = el('a', 'lnkbtn');
  back.href = '#/push/' + user.uid;
  back.textContent = '‹ ' + streamerName(user);
  line.appendChild(back);
  const title = el('span', 'ch-nm');
  title.textContent = name;
  line.appendChild(title);
  line.appendChild(pill(typeName(session, target)));
  const num = el('span', 'ch-id');
  num.textContent = (Number(target.type) === 1
    ? term('bot.target.group', '群号') : term('bot.target.user', '用户号')) + ' ' + target.num;
  line.appendChild(num);

  const remove = el('button', 'lnkbtn channel-remove');
  remove.type = 'button';
  remove.textContent = '移除通道';
  remove.addEventListener('click', async () => {
    if (!await ask({title: '移除该通道？',
      body: '不再向 ' + name + ' 推送「' + streamerName(user) + '」'
        + '。这条通道的通道设置（金额可见、静音时段、命令开关）将一并删除，「@我」订阅名单保留；其他主播的推送不受影响。'})) return;
    user.targets.splice(user.targets.indexOf(target), 1);
    markDirty();
    location.hash = '#/push/' + user.uid;
  });
  line.appendChild(remove);
  head.appendChild(line);
  head.appendChild(el('p', 'hint')).textContent = streamerName(user) + ' → ' + name;

  sectionNotices(host, user, target, session);
  sectionTemplate(host, user, target, session);
  sectionOptions(host, user, target, session);
  sectionSession(host, user, target, session);
}

/** 段头：一个序号加一句话 */
function sectionHead(host, index, title, desc) {
  const box = el('div', 'nv-card hcard psec');
  const head = el('h3');
  head.innerHTML = '<span class="psec-n">' + index + '</span>' + esc(title);
  box.appendChild(head);
  if (desc) box.appendChild(el('p', 'hint')).textContent = desc;
  host.appendChild(box);
  return box;
}

/** 只收起占空间的编辑区，默认状态和适用范围仍留在卡片外层。 */
function editorFold(host, user, target, name, title) {
  const key = user.uid + ':' + target.platform + ':' + target.type + ':' + target.num + ':' + name;
  const fold = el('details', 'p-editor-fold');
  fold.open = editorFolds.has(key);
  fold.appendChild(el('summary')).textContent = title;
  const body = el('div', 'p-editor-body');
  fold.appendChild(body);
  fold.addEventListener('toggle', () => {
    if (!fold.isConnected) return;
    if (fold.open) editorFolds.add(key);
    else editorFolds.delete(key);
  });
  host.appendChild(fold);
  return body;
}

/** 段 1：推送类型 */
function sectionNotices(host, user, target, session) {
  const box = sectionHead(host, 1, '推送类型', '选择以下事件发生时是否向此通道发送通知');
  const switches = noticeSwitches(user, target, store.handlerList);

  if (!switches.length) {
    box.appendChild(el('p', 'hint')).textContent =
      '暂无已加载的推送处理器，请检查对应插件是否已加载。';
  }

  for (const item of switches) {
    const row = el('div', 'swrow');
    const label = el('label', 'switch');
    label.innerHTML = '<input type="checkbox"' + (item.on ? ' checked' : '') + '>';
    label.querySelector('input').addEventListener('change', event => {
      toggleNotice(target, item.className, event.target.checked);
    });
    row.appendChild(label);

    const text = el('div', 'swtxt');
    text.innerHTML = '<b>' + esc(item.displayName) + '</b>'
      + '<p>' + esc(item.description) + '</p>';
    row.appendChild(text);
    box.appendChild(row);
    if (item.className === 'org.frostnova.nova.core.handler.WeeklyReportPushHandler') {
      weeklyReportActions(box, user, target);
    }
  }

  const recent = el('h4');
  const group = Number(target.type) === 1;
  recent.textContent = group ? '本群最近推送' : '该好友最近推送';
  box.appendChild(recent);
  if (isMember()) box.appendChild(el('p', 'hint')).textContent = '只列你名下主播的推送。';
  else if (group) box.appendChild(el('p', 'hint')).textContent = '推送到本群的所有主播均计算在内。';

  const rows = recentPushes(pushHistory, session, 5);
  if (!rows.length) {
    box.appendChild(el('p', 'hint')).textContent = session && session.type
      ? (group ? '本次启动以来，暂无发往本群的推送。'
        : '本次启动以来，暂无发给该好友的推送。')
      : '此通道尚未加载，无法获取其推送记录。';
  } else {
    const table = el('table', 'ptable');
    table.innerHTML = '<thead><tr><th>时间</th><th>内容</th><th>结果</th></tr></thead><tbody>'
      + rows.map(row => '<tr><td>' + esc(row.at) + '</td>'
        + '<td title="' + esc(row.summary) + '">' + esc(row.summary) + '</td>'
        + '<td>' + (row.success ? '<span class="good">成功</span>'
          : row.uncertain ? '<span>送达状态未知（可能已发出，未重发）：' + esc(row.reason || '未知原因') + '</span>'
          : '<span class="bad">失败：' + esc(row.reason || '未知原因') + '</span>') + '</td></tr>').join('')
      + '</tbody>';
    // 表只在卡片里横着滚，不把整页顶宽：窄屏上整页一横滚，左树与底下的保存条跟着推出屏外
    const scroll = el('div', 'push-history-scroll');
    scroll.appendChild(table);
    box.appendChild(scroll);
  }

  const channel = pushChannelOf(session);
  if (channel) {
    const more = el('a', 'lnkbtn');
    // 落在推送栏而不是全部栏：开播、下播记录不属任何群，落进全部栏会被「只看这个群」筛掉
    more.href = '#/log?cat=PUSH&chan=' + encodeURIComponent(channel);
    more.textContent = '在日志页查看全部 →';
    box.appendChild(more);
  }
}

/** 运营周报的操作只作用于当前主播和当前通道。 */
function weeklyReportActions(host, user, target) {
  const path = '/weekly-report/' + encodeURIComponent(user.platform) + '/' + user.uid;
  const query = '?pushPlatform=' + encodeURIComponent(target.platform)
    + '&type=' + Number(target.type) + '&num=' + target.num;
  const tools = el('div', 'weekly-report-tools');
  host.appendChild(tools);
  const status = el('p', 'hint');
  status.textContent = '正在读取周报状态…';
  status.setAttribute('role', 'status');
  tools.appendChild(status);
  const update = () => api(path + '/status' + query)
    .then(res => { status.textContent = res.status; })
    .catch(() => { status.textContent = '周报状态读取失败'; });
  update();
  const actions = el('div', 'weekly-report-actions');
  tools.appendChild(actions);
  const preview = el('a', 'lnkbtn');
  preview.textContent = '预览';
  preview.href = CONSOLE + '/api' + path + '/preview' + query;
  preview.target = '_blank';
  preview.rel = 'noopener';
  actions.appendChild(preview);
  const trial = el('button', 'ghost');
  trial.type = 'button';
  trial.textContent = '试发';
  trial.addEventListener('click', async () => {
    if (!await ask({title: '试发运营周报？', danger: false,
      body: '立即把上周的周报发到这个会话？这是试发，不影响周一的定时发送。'})) return;
    trial.disabled = true;
    try {
      const res = await api(path + '/trial' + query, {method: 'POST'});
      say(res.message, res.success ? 'ok' : 'err');
    } catch (err) { say('试发失败：' + err.message, 'err'); }
    finally { trial.disabled = false; }
  });
  actions.appendChild(trial);
}

/**
 * 开关一类通知
 *
 * 勾上＝在这个通道的消息里加一条，取消＝把那条去掉。键形与推送配置里原本的一模一样
 * （只有 handler 一个必填键，其余参数由处理器的默认值补齐），不在这里另造一种写法。
 *
 * 🔴 <b>「这一类通知」认的是主名与它的全部旧名</b>（见 push-model 的 handlerNames）。
 * 只认主名的话，老配置里写着旧名的那一条：取消勾选删不掉它，它留在文件里继续推；
 * 勾上又会另加一条主名的，同一类通知在配置里变成两条，群里收两遍。
 *
 * 旧名那一条<b>就地扶正成主名</b>，而这只发生在使用者动了这个开关之后：
 * 迁移由这一下点击触发、看得见，不是后台趁读一次配置偷偷改人家的文件。
 */
function toggleNotice(target, className, on) {
  target.messages = target.messages || [];
  const handler = handlerOf({handler: className}, store.handlerList) || {className, aliases: []};
  const names = handlerNames(handler);
  const mine = target.messages.filter(item => names.includes(item.handler));

  if (on) {
    // 已经有就不重复添加，以免覆盖掉使用者写过的参数
    if (!mine.length) {
      target.messages.push({handler: handler.className});
    } else {
      // 留第一条并把名字扶正，同类的其余条目去掉：进来时就是两条的配置（旧名与主名各一条）
      // 若原样留着，这一类通知每次都推两遍
      mine[0].handler = handler.className;
      target.messages = target.messages.filter(item => item === mine[0] || !names.includes(item.handler));
    }
  } else {
    target.messages = target.messages.filter(item => !names.includes(item.handler));
  }
  markDirty();
  renderStreamers();
}

/** 段 2：消息模板 */
function sectionTemplate(host, user, target, session) {
  const box = sectionHead(host, 2, '消息模板', '一张卡片即一条消息，花括号变量块可拖动');
  // 只列这个通道<b>开着</b>的那几类：关着的那一类改了模板也不会有人收到，
  // 而屏幕上它与改好了长得一样
  const on = noticeSwitches(user, target, store.handlerList)
    .filter(item => item.hasTemplate && item.on)
    .map(item => item.className);
  const handlers = (store.handlerList || []).filter(item => on.includes(item.className));
  const state = templateState(target, store.handlerList);

  const line = el('div', 'tplstate');
  const label = el('span');

  // 未改动时状态行旁的小字：这一段默认就能改，先把「改过就不再跟着默认走」说在改之前——
  // 等到改完才发现回不去默认，与改不了是一样地堵人
  const free = el('span', 'tplfree');
  free.textContent = '可直接修改；修改后此通道不再随默认模板更新，可随时恢复默认';

  // 「恢复默认」只在与默认不同时出现。改了第一下就得出场、改回相同就得收起，
  // 而这只能靠改状态行自己这几个节点：整段重画会把正在打字的输入框焦点弄丢
  const act = el('button', 'ghost');
  act.type = 'button';
  act.textContent = '恢复默认';
  act.addEventListener('click', async () => {
    // 「恢复默认」丢得掉使用者写了很久的东西，因此先问一句，并说清丢的是什么
    if (!await ask({title: '恢复默认？',
      body: '此通道的消息模板将恢复为默认，自定义的内容将会丢失。'
        + '此后默认模板的修改将同步应用到此通道。'})) return;
    for (const message of target.messages || []) {
      // 按主名与旧名一起认：老配置里写着旧名的那一条按主名找不着，「恢复默认」会静静地跳过它，
      // 而屏幕上这个通道已经写着「默认模板」
      const handler = handlerOf(message, store.handlerList);
      if (!handler || !(handler.placeholders || []).length) continue;
      // 与编辑器的 onChange 同一条规矩：恢复完空了、而快照里这条消息本来就没有 params 这一键的，
      // 把键也删掉（见 savedMessageHasParams）
      const params = restoreDefaults(message.params, handler);
      if (Object.keys(params).length || savedMessageHasParams(target, handler)) {
        message.params = params;
      } else {
        delete message.params;
      }
    }
    markDirty();
    renderStreamers();
  });

  /** 状态行那一排跟着判法走：默认／自定义、小字、「恢复默认」出现还是收起 */
  const syncState = () => {
    const now = templateState(target, store.handlerList);
    label.innerHTML = '当前模板：<b>' + (now.custom ? '自定义' : '默认模板') + '</b>'
      + (now.custom ? ' · 与默认不同' : ' ✓');
    free.hidden = now.custom;
    act.hidden = !now.custom;
  };
  syncState();
  line.appendChild(label);
  line.appendChild(free);
  line.appendChild(act);

  const toDefault = el('a', 'lnkbtn');
  toDefault.href = '#/push/default';
  toDefault.textContent = '查看默认模板';
  line.appendChild(toDefault);
  box.appendChild(line);

  if (state.custom) {
    const which = (store.handlerList || [])
      .filter(item => state.changed.includes(item.className))
      .map(item => item.displayName || item.className);
    box.appendChild(el('div', 'note template-diff')).textContent = '与默认模板不同的通知：' + which.join('、');
  }

  const editorHost = editorFold(box, user, target, 'template', '模板编辑与消息预览');
  buildTemplateEditor(editorHost, {
    handlers,
    // 这一段默认就能改，不传锁态；编辑器本身仍支持锁态（template.js 那一套），留给要用它的地方
    editable: true,
    emptyNote: '此通道未启用任何带文字模板的通知。未启用的通知即使修改模板也不会有人收到，'
      + '因此此处不列出；请先在上方「推送类型」中启用。',
    paramsOf: handler => (messageOf(target, handler) || {}).params || {},
    context: {
      isGroup: Number(target.type) === 1,
      admin: adminOf(target),
      terms: store.vocab,
    },
    channelLabel: channelName(session, target, directory),
    onChange: (handler, params) => {
      const message = messageOf(target, handler);
      if (!message) return;
      // 改完 params 空了、而快照里这条消息本来就没有 params 这一键的，把键也删掉，回到
      // 原来的形状——不然改动条会平白多记一处（见 savedMessageHasParams）
      if (Object.keys(params).length || savedMessageHasParams(target, handler)) {
        message.params = params;
      } else {
        delete message.params;
      }
      markDirty();
      syncState();
    },
  });
}

/** 机器人在这个群里是不是管理员。名单取不到时为 null，预览据此不画划掉线 */
function adminOf(target) {
  const known = directory[target.platform + '|' + Number(target.type) + '|' + target.num];
  return known?.admin ?? null;
}

/** 段 3：按推送类型顺序逐类显示配置；只有报告版式监听金额草稿。 */
function sectionOptions(host, user, target, session) {
  const previews = [];
  for (const state of optionStates(user, target, store.handlerList)) {
    const editor = sectionHandlerOptions(host, user, target, session, state);
    if (state.preview) previews.push(editor);
  }
  if (previews.length) onRevenueDraftChange(() => {
    for (const editor of previews) editor.repaint();
  });
}

function sectionHandlerOptions(host, user, target, session, state) {
  const handler = state.handler || {};
  const name = handler.displayName || handler.className;
  const box = sectionHead(host, 3, name + (state.preview ? '版式' : '设置'), null);

  const line = el('div', 'tplstate');
  const label = el('span');

  // 与段 2 同一条规矩：改了第一下当场出现、改回相同当场收起，只动状态行这几个节点
  const act = el('button', 'ghost');
  act.type = 'button';
  act.textContent = '恢复默认';
  act.addEventListener('click', async () => {
    if (!await ask({title: '恢复默认？',
      body: state.preview
        ? '此通道的报告版式将恢复为默认，自定义的版式设置将会丢失。'
          + '此后默认版式的修改将同步应用到此通道。'
        : '此通道的「' + name + '」设置将恢复为默认，自定义的设置将会丢失。'})) return;
    const message = messageOf(target, handler);
    if (message) {
      // 与段 2 的「恢复默认」同理（见 savedMessageHasParams）
      const params = restoredLayoutParams(message.params, handler);
      if (Object.keys(params).length || savedMessageHasParams(target, handler)) {
        message.params = params;
      } else {
        delete message.params;
      }
    }
    markDirty();
    renderStreamers();
  });

  const syncState = () => {
    const now = layoutState(target, [handler]);
    label.innerHTML = (state.preview ? '当前版式' : '当前设置') + '：<b>'
      + (now.custom ? '自定义' : '默认') + '</b>';
    act.hidden = !now.custom;
  };
  syncState();
  line.appendChild(label);
  line.appendChild(act);
  box.appendChild(line);

  // 金额可见按「会话存的＋草稿」现算：草稿里拨了（还没保存），灰不灰、预览图与
  // 图下说明都当场跟着变——不然开关旁说按草稿画、图上还是存的设定，两头各说各话
  const draftVisible = () => target.revenueVisible ?? (Number(target.type) === 0);

  // 收起预览时，当前金额可见范围仍在状态行下方；与预览图共用现成的判法。
  const revenueScope = state.preview ? el('p', 'p-revenue-scope') : null;
  const syncRevenueScope = () => {
    if (revenueScope) revenueScope.textContent = previewRevenueCaption(session, target, draftVisible());
  };
  if (revenueScope) {
    syncRevenueScope();
    box.appendChild(revenueScope);
  }
  const editorHost = editorFold(box, user, target, 'options:' + handler.className,
    state.preview ? '版式编辑与报告预览' : '通知详细设置');
  const editor = buildLayoutEditor(editorHost, {
    // 与段 2 同理：这一段默认就能改，不传锁态
    editable: true,
    preview: state.preview,
    items: handler.options || [],
    paramsOf: () => (messageOf(target, handler) || {}).params || {},
    caption: state.preview ? () => previewRevenueCaption(session, target, draftVisible()) : null,
    revenueVisible: state.preview ? draftVisible : null,
    onChange: params => {
      const message = messageOf(target, handler);
      if (!message) return;
      // 与段 2 同一条规矩：改完 params 空了、而快照里这条消息本来就没有 params 这一键的，
      // 把键也删掉，回到原来的形状（见 savedMessageHasParams）
      if (Object.keys(params).length || savedMessageHasParams(target, handler)) {
        message.params = params;
      } else {
        delete message.params;
      }
      markDirty();
      syncState();
    },
    render: state.preview ? layoutPreviewRenderer(target, draftVisible, user) : null,
  });

  return {repaint() {
    syncRevenueScope();
    editor.repaint();
  }};
}

/**
 * 给一个版式编辑器配一支「按当前这套版式画一张」（buildLayoutEditor 的 render 用）
 *
 * 图由服务端画，与真出报告读的是同一段解析码：各画各的话，预览会在
 * 「越界值怎么夹」「缺项取什么默认」这些地方悄悄给出与实际不同的图。
 * 请求体带上当前通道，服务端按这个群的「金额可见」画；带上草稿值时
 * 只这一张按草稿画，存着的设定不动。
 *
 * 撤销哪张地址，各编辑器只记自己的一份，不放在模块级共用：切通道后旧编辑器
 * 晚到的回应只作废它自己那张——共用一份的话，这一下撤销的就是当前通道正显示
 * 的图地址，新图还没载完时预览那一块就白了。
 * @param target 当前通道
 * @param revenueVisible 算金额可见性的那一支（含草稿），画的时候现取
 * @return {Function} 收版式参数，回图片地址；请求失败时抛错，服务端画不出来时为空
 */
function layoutPreviewRenderer(target, revenueVisible, user) {
  let url = null;
  return async params => {
    const res = await request(CONSOLE + '/api/report/preview', {
      method: 'POST',
      headers: Object.assign({'Content-Type': 'application/json'},
        store.csrfToken ? {'X-CSRF-Token': store.csrfToken} : {}),
      body: JSON.stringify(previewRequestBody(params, target, revenueVisible(), user)),
    });
    if (!res.ok) return '';
    try {
      // 上一张画完就没用了。不撤销的话，来回调开关几十次会把几十张图一直挂在内存里
      if (url) URL.revokeObjectURL(url);
      url = URL.createObjectURL(await res.blob());
      return url;
    } catch (e) {
      return '';
    }
  };
}

/** 这条通道已保存的那一份，没存过时是空对象 */
function savedTarget(user, target) {
  return JSON.parse(pushSaved).find(saved => userKey(saved) === userKey(user))?.targets?.find(saved =>
    saved.platform === target.platform && Number(saved.type) === Number(target.type) && String(saved.num) === String(target.num)) || {};
}

/** 这条通道已保存的静音档位 */
function savedQuiet(user, target) {
  return savedTarget(user, target).quietHours || {mode: 'follow'};
}

/** 段 4：当前主播的通道设置。 */
function sectionSession(host, user, target, session) {
  const box = sectionHead(host, 4, '通道设置', Number(target.type) === 1
    ? '只作用于这位主播在本群的推送和命令' : '只作用于这位主播与该好友');
  const settings = el('div');
  box.appendChild(settings);
  const render = () => {
    const current = channelSession(session, target, user);
    const baseline = savedTarget(user, target);
    const commands = (runtime.commands || []).filter(command => command.disableable !== false && command.channelScoped !== false);
    const subscriptions = (runtime.subscriptions || []).filter(sub => String(sub.streamerUid) === String(user.uid));
    settings.innerHTML = '';
    sessionSettings(settings, {
      channel: true, repaint: render, session: current, target, commands, subscriptions,
      baseline,
      totalDataAvailable: runtime.totalDataAvailable, quietGlobal: runtime.quietGlobal,
      summary: {
        revenue: revenueSummary(current), quiet: quietSummary(current, runtime.quietGlobal, savedQuiet(user, target)),
        command: commandSummary(commands, current, runtime.totalDataAvailable),
        subscription: subscriptionSummary(subscriptions, current),
        atAll: atAllStatus(quota, current, target, directory),
      },
      groups: commandGroups(commands, current, runtime.totalDataAvailable),
    });
  };
  render();
}

// ============ 抽屉 ============

/** 打开面板时焦点所在的那个控件：关掉时焦点还给它 */
let drawerOpener = null;

/**
 * 面板正文里头一个能用的控件
 *
 * 禁用的、藏着的跳过：焦点落到它们身上等于没落，只用键盘的人还是要先按好几下 Tab
 * 才摸得到能用的那一个。一个能用的都没有时由调用方回落到关闭钮
 * @param body 面板正文
 * @return {Element|null} 头一个能用的控件
 */
function firstUsable(body) {
  for (const node of body.querySelectorAll('input, select, textarea, button, a[href]')) {
    if (node.disabled || node.hidden || (node.style && node.style.display === 'none')) continue;
    return node;
  }
  return null;
}

/**
 * 摊开一个只问一件事的面板
 *
 * 先显示、再把焦点放到正文里第一个能用的控件上：面板还藏着时聚焦会落空，焦点留在打开它的按钮上。
 * 面板开着时再摊一份（如「刷新」名单）只换内容，打开它的仍是最早那一个。
 * @param title 标题
 * @param lead 一句说明
 * @param build 往面板正文里建内容
 */
export function openDrawer(title, lead, build) {
  const drawer = $('#push-drawer');
  if (drawer.hidden) drawerOpener = document.activeElement;
  $('#push-drawer-title').textContent = title;
  $('#push-drawer-lead').textContent = lead || '';
  const body = $('#push-drawer-body');
  body.innerHTML = '';
  build(body);
  drawer.hidden = false;
  const first = firstUsable(body) || $('#push-drawer-close');
  first.focus();
}

/**
 * 收起面板。×、点遮罩、Esc、办完自动关都走这里，焦点还给打开它的那个控件；
 * 那个控件已随重画不在页上了就不管
 */
export function closeDrawer() {
  const drawer = $('#push-drawer');
  const wasOpen = !drawer.hidden;
  drawer.hidden = true;
  $('#push-drawer-body').innerHTML = '';
  const opener = drawerOpener;
  drawerOpener = null;
  if (wasOpen && opener && opener.isConnected && typeof opener.focus === 'function') opener.focus();
}

/**
 * 从机器人已经加入的群、已经加的好友里挑
 *
 * 🔴 <b>没有手填号码的格子。</b>填错一位数不会有任何报错，消息只是发去了别处，
 * 而「配好了群里没动静」这几类错的表现完全一样。认出来的那一条走 resolveTarget，
 * 与「发一条试试」是同一份判法——那一份有 node 夹具在量（含四例手填的反面对照）。
 * @param opts 标题、说明、已经是通道的那些（灰掉）、挑中之后做什么
 */
function pickTarget(opts) {
  openDrawer(opts.title, opts.lead, body => {
    const bar = el('div', 'dwbar');
    const search = el('input');
    search.type = 'search';
    search.placeholder = '搜索群名、群号或好友昵称';
    search.setAttribute('aria-label', '搜索可选的群与好友');
    bar.appendChild(search);

    const refresh = el('button', 'ghost');
    refresh.type = 'button';
    refresh.textContent = '刷新';
    refresh.addEventListener('click', async () => {
      refresh.disabled = true;
      try {
        await api('/bot/targets/refresh', {method: 'POST'});
        await loadPushPage();
        pickTarget(opts);
      } catch (e) {
        say('名单刷新失败：' + e.message, 'err');
      }
      refresh.disabled = false;
    });
    bar.appendChild(refresh);
    body.appendChild(bar);

    const list = el('div', 'dwlist');
    body.appendChild(list);

    const paint = () => {
      const keyword = search.value.trim().toLowerCase();
      list.innerHTML = '';

      if (!options.length) {
        list.appendChild(el('p', 'hint')).textContent =
          '无法获取机器人的群与好友名单。机器人可能未连接，请到「连接」页检查。';
        return;
      }

      const rows = options.filter(item => !keyword
        || String(item.num).includes(keyword)
        || (item.name || '').toLowerCase().includes(keyword));
      if (!rows.length) {
        list.appendChild(el('p', 'hint')).textContent = '未找到匹配的群或好友。';
        return;
      }

      for (const item of rows) {
        const taken = (opts.taken || []).includes(item.key);
        const otherStreamers = item.type === 1 ? pushData
          .filter(user => user !== opts.user && (user.targets || []).some(target =>
            target.platform === item.sender && Number(target.type) === 1
            && String(target.num) === String(item.num)))
          .map(streamerName) : [];
        const pushing = otherStreamers.length
          ? ' · 正在推送 ' + otherStreamers.slice(0, 3).join('、')
            + (otherStreamers.length > 3 ? '等 ' + otherStreamers.length + ' 位' : '')
          : '';
        const row = el('button', 'dwrow' + (taken ? ' taken' : ''));
        row.type = 'button';
        row.disabled = taken;
        row.innerHTML = '<span class="dw-nm">' + esc(item.name || item.num) + '</span>'
          + '<span class="dw-sub">' + (item.type === 1
            ? term('bot.target.group', '群号') + ' ' : term('bot.target.user', '用户号') + ' ')
          + esc(item.num)
          + (item.memberCount ? ' · ' + esc(item.memberCount) + ' 人' : '')
          + (item.admin ? ' · 机器人为管理员' : '') + esc(pushing) + '</span>'
          + (taken ? '<span class="dw-tag">已添加</span>' : '');
        row.addEventListener('click', () => {
          const hit = resolveTarget(options, item.key);
          if (!hit.ok) {
            say(hit.reason, 'err');
            return;
          }
          closeDrawer();
          opts.onPick(hit);
        });
        list.appendChild(row);
      }
    };

    search.addEventListener('input', paint);
    paint();
  });
}

function lookupTarget(platform, type, num) {
  return api('/bot/targets/lookup?' + new URLSearchParams({platform, type, num: String(num)}));
}

/** 成员只查询输入的完整号码，不读取或展示机器人会话名单。 */
function pickMemberTarget(opts) {
  const platformNames = [...new Set([
    ...(opts.user.targets || []).map(target => target.platform),
    ...pushData.flatMap(user => user.targets || []).map(target => target.platform),
    ...(runtime.sessions || []).map(session => session.platform),
    ...(store.senderList || []),
  ].filter(Boolean))];
  const numberLabel = term('bot.target.group', '群号') + phrase('bot.target.user', v => '或 ' + v, '或用户号');
  openDrawer(opts.title, '填写完整的' + numberLabel + '，查到后再添加。', body => {
    const form = el('div', 'dwform');
    body.appendChild(form);
    const sender = platformNames.length > 1 ? el('select') : null;
    if (sender) {
      sender.setAttribute('aria-label', '推送平台');
      for (const platform of platformNames) {
        const option = el('option');
        option.value = platform;
        option.textContent = platform;
        sender.appendChild(option);
      }
      sender.value = platformNames[0];
      form.appendChild(sender);
    }
    const kind = el('select');
    kind.setAttribute('aria-label', '会话类型');
    kind.innerHTML = '<option value="group">群</option><option value="friend">好友</option>';
    kind.value = 'group';
    form.appendChild(kind);
    const number = el('input');
    number.type = 'text';
    number.inputMode = 'numeric';
    number.setAttribute('aria-label', '完整的' + numberLabel);
    number.placeholder = '完整的' + numberLabel;
    form.appendChild(number);
    const go = el('button', 'primary');
    go.type = 'button';
    go.textContent = '查';
    form.appendChild(go);
    const out = el('div');
    out.setAttribute('aria-live', 'polite');
    body.appendChild(out);
    let revision = 0;
    const normalizedNumber = () => number.value.trim().replace(/[０-９]/g, digit =>
      String.fromCharCode(digit.charCodeAt(0) - 0xFEE0));
    const invalid = () => {
      const num = normalizedNumber();
      if (!/^\d+$/.test(num)) return '请填写完整的' + numberLabel + '，只能填数字';
      if (num.startsWith('0')) return '请填写完整的' + numberLabel + '，开头不能是 0';
      return '';
    };
    const changed = () => {
      revision++;
      go.disabled = false;
      out.innerHTML = '';
      if (number.value) out.textContent = invalid();
    };
    number.addEventListener('input', changed);
    kind.addEventListener('change', changed);
    if (sender) sender.addEventListener('change', changed);
    if (!platformNames.length) out.textContent = '没有可用的推送平台，请联系管理员。';
    go.addEventListener('click', async () => {
      out.innerHTML = '';
      const error = invalid();
      if (error) { out.textContent = error; return; }
      const platform = sender ? sender.value : platformNames[0];
      if (!platform) { out.textContent = '没有可用的推送平台，请联系管理员。'; return; }
      const attempt = ++revision;
      const num = normalizedNumber();
      const type = kind.value;
      go.disabled = true;
      try {
        const found = await lookupTarget(platform, type, num);
        if (attempt !== revision) return;
        if (!found.success || !found.found) {
          out.textContent = found.message || '未查到这个会话';
          return;
        }
        const hit = {platform, type: type === 'group' ? 1 : 0, num: found.num};
        const key = channelTail(hit);
        const name = found.name || found.nickname || String(found.num);
        directory[key] = {name};
        out.appendChild(el('p')).textContent = name;
        if (found.message) out.appendChild(el('p', 'hint')).textContent = found.message;
        const add = el('button', 'primary');
        add.type = 'button';
        add.textContent = '加上';
        add.disabled = opts.taken.includes(key);
        add.addEventListener('click', () => {
          if (attempt !== revision || opts.taken.includes(key)) return;
          closeDrawer();
          opts.onPick(hit);
        });
        out.appendChild(add);
        if (add.disabled) out.appendChild(el('p', 'hint')).textContent = '这个通道已经加过了';
      } catch (e) {
        if (attempt === revision) out.textContent = e.message;
      } finally {
        if (attempt === revision) go.disabled = false;
      }
    });
  });
}

/** 给某位主播添加一个通道 */
function addChannel(user) {
  const taken = (user.targets || [])
    .map(item => item.platform + '|' + Number(item.type) + '|' + item.num);

  (isMember() ? pickMemberTarget : pickTarget)({
    title: '给「' + streamerName(user) + '」添加通道',
    lead: '从机器人已加入的群和已添加的好友中选择，已添加为通道的显示为灰色。通道设置（金额可见、静音时段、命令开关）每条通道各一份，不影响同群的其他主播。',
    user,
    taken,
    onPick: hit => {
      user.targets = user.targets || [];
      // 新通道默认四种通知全开、全用默认模板：这与初始设置第 4 步给的是同一份起点
      user.targets.push({
        platform: hit.platform, type: hit.type, num: hit.num, enabled: true,
        messages: defaultMessages(store.handlerList, user.platform),
      });
      markDirty();
      say('已添加通道，保存后生效', 'ok');
      location.hash = '#/push/' + user.uid + '/' + channelTail(hit);
      renderStreamers();
    },
  });
}

// ============ 添加主播 ============

/**
 * 添加一位主播
 *
 * 平台由已注册的数据源服务决定：一个都没有就把这件事停掉并写明原因，只有一个就默认它，
 * 有多个才让人挑。写死一个平台名的话，没装那个插件时按钮照样点得动，
 * 点完才在服务端被回一句「没有可用的数据源服务」。
 */
export function addStreamer() {
  const platformNames = store.platforms || [];

  openDrawer('添加主播', '添加后需为其配置至少一个通道，才会有人收到消息。', body => {
    if (!platformNames.length) {
      body.appendChild(el('p', 'hint')).textContent =
        '未加载任何直播平台插件，无法添加主播。';
      return;
    }

    const form = el('div', 'dwform');
    if (platformNames.length > 1) {
      const pick = el('select');
      pick.id = 'add-platform';
      pick.setAttribute('aria-label', '主播所属平台');
      pick.innerHTML = platformNames.map(name =>
        '<option value="' + esc(name) + '">' + esc(name) + '</option>').join('');
      form.appendChild(pick);
    }

    const input = el('input');
    input.id = 'add-uid';
    input.placeholder = '输入 ' + STREAMER_INPUT_HINT;
    input.setAttribute('aria-label', '主播 UID、直播间号或链接');
    form.appendChild(input);

    const go = el('button', 'primary');
    go.id = 'add-streamer';
    go.type = 'button';
    go.textContent = '查询';
    form.appendChild(go);
    body.appendChild(form);

    const out = el('div', 'dwout');
    body.appendChild(out);

    const submit = () => lookupStreamer(platformNames, input, go, out);
    go.addEventListener('click', submit);
    input.addEventListener('keydown', event => { if (event.key === 'Enter') submit(); });
  });
}

async function lookupStreamer(platformNames, input, go, out) {
  const platform = platformNames.length > 1
    ? ($('#add-platform') || {}).value || platformNames[0]
    : platformNames[0];
  const value = input.value.trim();
  if (!value) {
    out.textContent = '请先输入 ' + STREAMER_INPUT_HINT + '。';
    return;
  }

  go.disabled = true;
  out.textContent = '查询中…';
  try {
    // 先把昵称显示出来让人确认，避免 uid 打错一位却配了个陌生人
    const res = await api('/streamer/lookup', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform, uid: value}),
    });

    if (!res.success) {
      out.textContent = res.message;
      return;
    }
    if ((pushData || []).some(item => Number(item.uid) === Number(res.uid))) {
      out.textContent = '主播 ' + res.uname + ' 已在列表中。';
      return;
    }

    out.innerHTML = '';
    const found = el('div', 'dwfound');
    found.innerHTML = '<b>' + esc(res.uname) + '</b><span>uid ' + esc(res.uid)
      + (res.roomId ? ' · 直播间 ' + esc(res.roomId) : '') + '</span>';
    const add = el('button', 'primary');
    add.type = 'button';
    add.textContent = '确认添加';
    add.addEventListener('click', () => {
      pushData.push({
        uid: res.uid, platform, enabled: true, targets: [],
        _uname: res.uname, _roomId: res.roomId, _face: res.face,
      });
      closeDrawer();
      markDirty();
      say('已添加 ' + res.uname + '，请为其配置通道后保存', 'ok');
      expanded.add(String(res.uid));
      location.hash = '#/push/' + res.uid;
      renderStreamers();
    });
    found.appendChild(add);
    out.appendChild(found);
  } catch (e) {
    out.textContent = '查询失败：' + e.message;
  } finally {
    go.disabled = false;
  }
}

// ============ 存盘与补全 ============

/** 推送配置此刻手里那一份，改它即改本页的草稿态。 */
export function pushEntries() {
  return pushData;
}

/** 整份替换推送页草稿。 */
export function setPushEntries(list) {
  pushData = list;
}

/** 对齐到已保存的快照，保留请求发出后继续编辑的内容。 */
export function markPushSaved(snapshot) {
  pushSaved = snapshot === undefined ? serializePush() : snapshot;
}

/** 向导保存成功后只合入这一位，其他主播的草稿、快照与版本继续保留。 */
const setupVersions = new Map();
export function trackSetupVersions(key, versions) { setupVersions.set(key, versions); }

export function acceptAddedPushUser(user, version) {
  user = {...user, _summary: true};
  const key = userKey(user);
  const existing = pushData.find(item => userKey(item) === key);
  let acceptVersion = !existing;
  if (!existing) pushData.push(user);
  else {
    const saved = JSON.parse(pushSaved).find(item => userKey(item) === key);
    // 返回向导重选目标后同步未编辑的主播；本地已有的改动继续保留。
    if (JSON.stringify(existing, dropDisplayOnly) === JSON.stringify(saved)) {
      pushData[pushData.indexOf(existing)] = user;
      acceptVersion = true;
    }
  }
  if (acceptVersion) userVersions.set(key, version);
  updateSavedUser(key, user);
}

// 下划线开头的字段仅供界面展示（昵称、头像等），既不写进配置文件，也不该算作改动
function dropDisplayOnly(key, value) {
  return key.startsWith('_') ? undefined : value;
}

/**
 * 下划线开头的字段仅供界面展示（昵称、头像等），不应写进配置文件。
 *
 * 「哪些字段只是给人看的」这条规则只有 dropDisplayOnly 一份：写两份的话，
 * 改动计数与真正写盘的内容会按两套规则算，于是「有改动却存不出东西」这种事没人查得出来
 */
export function serializePush() {
  return JSON.stringify(pushData, dropDisplayOnly, 2);
}

/**
 * 快照里这条通道消息上原本有没有 params 这一键
 *
 * 向导刚建出来的通道，消息就是一条 {handler}，一个参数都没存过。编辑器改完又改回原样时
 * 交回来的 params 空了：照写 message.params = {} 的话，与快照里没有这一键仍差一截——
 * 改动条按整段 JSON 与快照比（见 pushChangeCount），多这一个空键就多记一处；反过来
 * 一律删键的话，快照里显式存着 params（哪怕是空对象）的那份又少一个键，同样多记一处。
 * 所以照快照论。
 * @param target 推送目标
 * @param handler 处理器；主名与旧名都认，与 messageOf 同一把尺
 * @return {boolean} 有则为 true。快照解析不了、找不到这条消息时按有算：删键是替人改形状，
 *         拿不准时不动
 */
function savedMessageHasParams(target, handler) {
  const names = handlerNames(handler);
  const owner = (pushData || []).find(user => (user.targets || []).includes(target)) || {};
  let saved;
  try {
    saved = JSON.parse(pushSaved || '[]');
  } catch (e) {
    return true;
  }
  for (const user of saved || []) {
    if (String(user.platform) !== String(owner.platform)
      || String(user.uid) !== String(owner.uid)) continue;
    for (const item of ((user.targets) || [])) {
      if (item.platform !== target.platform || Number(item.type) !== Number(target.type)
        || String(item.num) !== String(target.num)) continue;
      const message = ((item.messages) || []).find(one => names.includes(one.handler));
      return !message || 'params' in message;
    }
  }
  return true;
}

/**
 * 推送配置那一份此刻有几处改过还没保存：按主播逐条比，不是「一整份变了没有」
 *
 * 整份比只能得出 0 或 1，而屏幕上写的是「N 处改动」——那个 1 会被读成「只改了一处」。
 * 两边都是现算，没有「改动次数」的累加器：累加器的毛病是改回原样它也照加，
 * 屏幕上会显示「1 处改动」而实际什么都没变。
 * @return {number} 改过未保存的主播数
 */
function pushChangeCount() {
  const byKey = list => {
    const map = new Map();
    for (const user of list || []) {
      map.set(user.platform + ':' + user.uid, JSON.stringify(user, dropDisplayOnly));
    }
    return map;
  };

  let saved;
  try {
    saved = byKey(JSON.parse(pushSaved || '[]'));
  } catch (e) {
    // 快照都解析不了时不猜数：本页此刻处于「文件内容不合法」的状态，界面上另有说明
    return 0;
  }

  const now = byKey(pushData);
  let n = 0;
  now.forEach((value, key) => { if (saved.get(key) !== value) n++; });
  saved.forEach((value, key) => { if (!now.has(key)) n++; });
  return n;
}

/**
 * 本页此刻有几处改过还没保存：推送配置那一份按主播算，通道设置那一份按改动项算
 *
 * 这是宿主改动条问本页的三件事之一（另两件是 save 与 reload），见 core.js 的
 * registerChangeSource。宿主不知道这个数是怎么算出来的，也不该知道。
 * @return {number} 改过未保存的处数
 */
export function changeCount() {
  return pushChangeCount() + sessionDraftCount();
}

/**
 * 重取本页那一份，宿主整体载入与「放弃改动」时各走一趟
 *
 * 自己取而不是等宿主喂：这份配置是本页的产品形状，宿主卸掉本插件之后
 * 不该还认得 /datasource 这条接口。
 *
 * 内容不合法时不在界面上开一个编辑器让人现场改文件——那条路已经撤了。
 * 这里只把话说清楚：改哪个文件、在哪儿改，路径就显示在设置页底部。
 * @return {Promise<boolean|undefined>} 回 false 表示已在状态栏上写了话，宿主别替它清掉
 */
export async function reload(options) {
  const keepSessionDrafts = options?.keepSessionDrafts === true;
  // 「放弃」是整页一起还原：通道设置那份草稿与推送配置那份一并丢
  if (!keepSessionDrafts) clearSessionDrafts();
  const knownNames = new Map((pushData || []).filter(user => user._uname)
    .map(user => [user.platform + ':' + user.uid, user]));
  const res = await api('/datasource/users');
  try {
    if (!res.success || !Array.isArray(res.users)) throw new Error(res.message || '无法读取主播列表');
    datasourceGeneration++;
    userVersions.clear();
    userLoads.clear();
    conflictedUsers.clear();
    pushData = res.users.map(user => ({...user, _summary: true}));
    nameLookups = new Set();
    for (const user of pushData) {
      const known = knownNames.get(userKey(user));
      if (known) Object.assign(user, {_uname: known._uname, _roomId: known._roomId, _face: known._face});
    }
    pushSaved = serializePush();
  } catch (e) {
    pushData = [];
    pushSaved = '[]';
    say('推送配置 datasource.json 无法载入：' + e.message + '。请修正后重新加载', 'err');
    return false;
  }
  if (pageLive) {
    await showPush(picked.uid, picked.platform ? channelTail(picked) : picked.num);
    await decoratePushData(false);
  }
}

/**
 * 把两段草稿写回去：推送配置 → datasource.json，通道设置 → 状态接口
 *
 * 两段分开写，哪段没改动就整段不发：通道设置干净时不该碰状态文件，
 * 推送配置干净时也不该重写 datasource.json。
 * 任一段失败都不撤另一段已经写成的；没写成的那一段草稿留着，下次保存再发。
 * 清空订阅名单那一笔自带二次确认，确认没过的那一笔跳过、其余照写。
 *
 * 状态栏写的是这一趟真正落地了什么：成的那一段显示服务端回的那句（两段都成就两句并列），
 * 通道设置一处没改时不论推送那段成败都不提它——那一段根本没发过请求。
 */
export async function save() {
  $('#save').disabled = true;
  say('保存中…');
  const notes = [];
  let pushOk = true;
  let stateOk = true;
  let skipped = 0;
  let pushTried = false;
  let pushMsg = '';
  const stateMsgs = [];
  let stateWrote = 0;

  // 固定这一趟要发的内容；等待回包时的新修改继续留在草稿里。
  const saved = new Map(JSON.parse(pushSaved).map(user => [userKey(user), user]));
  const current = new Map(JSON.parse(serializePush()).map(user => [userKey(user), user]));
  const writes = [];
  for (const key of new Set([...saved.keys(), ...current.keys()])) {
    const before = saved.get(key);
    const user = current.get(key);
    if (JSON.stringify(before) === JSON.stringify(user)) continue;
    writes.push({key, user, before, version: userVersions.get(key)});
  }
  for (const {key, user, before, version} of writes) {
    pushTried = true;
    const identity = user || before;
    const display = pushData.find(item => userKey(item) === key) || before;
    const label = streamerName(display) + '（' + identity.platform + '/' + identity.uid + '）';
    try {
      if (before && !version) throw new Error('请重新载入这位主播后保存');
      const path = !before ? '/datasource/users' : userPath(identity) + (!user ? '/delete' : '');
      const res = await api(path, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({user, version}),
      });
      if (res.success) {
        updateSavedUser(key, user);
        if (user) {
          userVersions.set(key, res.version);
          setupVersions.get(key)?.set(key, res.version);
        } else {
          userVersions.delete(key);
          setupVersions.get(key)?.delete(key);
        }
        conflictedUsers.delete(key);
        pushMsg = res.message || '已保存';
      } else {
        pushOk = false;
        if (res.conflict) conflictedUsers.add(key);
        const issues = res.issues?.length ? '：' + res.issues.join('；') : '';
        notes.push(label + '：' + (res.message || '保存失败') + issues
          + (res.conflict || issues ? '' : '，请重新载入这位主播'));
      }
    } catch (e) {
      pushOk = false;
      notes.push(label + '：' + e.message);
    }
  }

  const wanted = [];
  for (const write of sessionWrites()) {
    if (write.ask) {
      let ok = false;
      try {
        ok = await ask(write.ask);
      } catch (e) {
        ok = false;
      }
      if (!ok) {
        skipped++;
        continue;
      }
    }
    wanted.push(write);
  }

  if (wanted.length) {
    for (const write of wanted) {
      try {
        const res = await api(write.path, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(write.body),
        });
        if (res.success) {
          write.done();
          stateWrote++;
          stateMsgs.push(res.message || '已保存');
        } else {
          stateOk = false;
          notes.push(res.message || '提醒订阅保存失败');
        }
      } catch (e) {
        stateOk = false;
        notes.push('提醒订阅保存失败：' + e.message);
      }
    }
  }

  const statePhrase = stateWrote > 0 ? stateMsgs.join('；') : '';

  if (pushOk && stateOk) {
    const parts = [];
    if (pushTried && pushMsg) parts.push(pushMsg);
    if (statePhrase) parts.push(statePhrase);
    if (!parts.length) parts.push('已保存');
    let text = parts.join(' · ');
    if (skipped) text += ' · ' + skipped + ' 项删除未确认，未写入';
    say(text, 'ok');
  } else if (pushOk && !stateOk) {
    const parts = [];
    if (pushTried && pushMsg) parts.push(pushMsg);
    parts.push('提醒订阅保存失败' + (notes.length ? '：' + notes.join('；') : ''));
    say(parts.join(' · '), 'err');
  } else if (!pushOk && stateOk) {
    // 成的那段与败的那句并列：塞进括号里跟着失败那句走的话，存上的东西会被读成没存上。
    // 通道设置一处没动时（stateWrote 为 0）不提它——那一段根本没发过请求
    const parts = ['推送配置保存失败' + (notes.length ? '：' + notes.join('；') : '')];
    if (stateWrote > 0) parts.push('提醒订阅已保存' + (statePhrase ? '（' + statePhrase + '）' : ''));
    say(parts.join(' · '), 'err');
  } else {
    say('推送配置与提醒订阅均保存失败：' + notes.join('；'), 'err');
  }
  markDirty();
  try {
    await loadPushPage();
  } catch (e) {
    // 保存结果已经说过了；刷新摘要失败不改那一趟的成败
  }
}

/**
 * 配置文件里只有 uid，昵称要另行补全才显示得出来
 *
 * 先用程序手上已有的昵称，再现场去平台查昵称、房间号与头像。
 * 现场查失败时保留已有昵称；两处都没有才照 uid 显示。
 * @param {boolean} repaint 补全后是否立即重画；重取配置时由宿主统一重画
 */
export async function decoratePushData(repaint = true) {
  const attempted = nameLookups;
  await Promise.all((pushData || []).map(async user => {
    // 补昵称要靠对应平台的数据源服务，没注册的平台查不了，照 uid 显示就是
    if (user._uname || !(store.platforms || []).includes(user.platform)) return;
    const key = user.platform + ':' + user.uid;
    if (attempted.has(key)) return;
    attempted.add(key);
    try {
      const known = await api('/streamer/name?platform=' + encodeURIComponent(user.platform)
        + '&uid=' + encodeURIComponent(String(user.uid)));
      if (known.success && known.uname) {
        user._uname = known.uname;
        if (repaint) renderStreamers();
      }
    } catch (e) {
      // 已知昵称取不到，仍继续现场查询
    }
    try {
      const res = await api('/streamer/lookup', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({platform: user.platform, uid: String(user.uid)}),
      });
      if (res.success) {
        if (res.uname) user._uname = res.uname;
        user._roomId = res.roomId;
        user._face = res.face;
        if (repaint) renderStreamers();
      }
    } catch (e) {
      // 现场查失败时保留先前补上的昵称
    }
  }));
}

/**
 * 把被群管理员关掉的命令一起改回来
 * @param platform 推送平台
 * @param type 会话类型码，1 群 0 好友：同号的群与好友各有各的命令开关
 * @param num 会话号
 * @param names 命令名
 */
export async function restoreCommands(platform, type, num, names) {
  try {
    const res = await api('/state/commands', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({platform, type: Number(type), num, commands: names, disabled: false}),
    });
    say(res.message || (res.success ? '已恢复' : '操作失败'), res.success ? 'ok' : 'err');
    if (res.success) await loadPushPage();
  } catch (e) {
    say('操作失败：' + e.message, 'err');
  }
}

