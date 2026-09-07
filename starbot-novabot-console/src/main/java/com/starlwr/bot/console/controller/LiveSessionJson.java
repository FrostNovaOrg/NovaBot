package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.RoomInfoSnapshot;

/**
 * 把一场直播转为界面用的 JSON
 * <p>
 * <b>单独成件是因为有两处在读它</b>：运营分析的逐场流水，与主播详情的场次表。
 * 各写一份的话，两张表迟早在同一场直播上显示出不同的数——而更糟的是
 * 下面那几条「空不等于零」的规矩只会被改对其中一处，另一处继续把
 * 历史场次显示成「零观众」。
 */
final class LiveSessionJson {
    private LiveSessionJson() {
    }

    /**
     * 把一场直播转为界面用的 JSON
     * @param session 场次
     * @return 界面用的 JSON
     */
    static JSONObject of(LiveSession session) {
        JSONObject item = new JSONObject();
        item.put("platform", session.platform());
        item.put("uid", session.uid());
        item.put("uname", session.uname());
        item.put("roomId", session.roomId());
        item.put("startTime", session.startTime());
        item.put("endTime", session.endTime());
        item.put("durationSeconds", session.durationSeconds());
        item.put("metrics", session.metrics() == null ? new JSONObject() : new JSONObject(session.metrics()));
        item.put("userCounts", session.userCounts() == null ? new JSONObject() : new JSONObject(session.userCounts()));

        // 被平台中断的场次要能一眼认出来：它的时长与营收和正常场次不可比，
        // 混在一张表里看就成了「这天状态怎么这么差」
        LiveEndReason reason = session.endReason() == null ? LiveEndReason.NORMAL : session.endReason();
        item.put("endReason", reason.name());
        item.put("endReasonText", reason.getDescription());
        item.put("interrupted", session.interrupted());

        // 有缺口的场次，各项计数只是下界。不标出来的话，一次维护重启会被读成「这天人气差」
        item.put("maintenanceGapSeconds", session.maintenanceGapSeconds());
        // 单房断线缺口。与上面那项**分两个字段给出去，不相加**：
        // 程序停机期间所有房间都在断，两段必然重叠，相加就是重复计数
        item.put("roomOutageSeconds", session.roomOutageSeconds());

        // ⚠️ 名单这一项，「空」有两种含义，必须让消费方分得开：
        // 一是这一场真的没人参与，二是这条记录来自还没有名单功能的年代。
        // 只丢一个空对象出去的话，历史场次会被显示成「零观众」——
        // 与缺口读成 0 是同一类误读：**0 是「不知道」，不是「我保证没有」**
        item.put("hasUserSets", session.hasUserSets());
        JSONObject userSets = new JSONObject();
        if (session.userSets() != null) {
            session.userSets().forEach((metric, uids) -> userSets.put(metric, new JSONArray(uids)));
        }
        item.put("userSets", userSets);

        // ⚠️ 峰值这一项与名单同理，「空」有两种含义：一是这一场那条曲线一个点都没有，
        // 二是这条记录早于「峰值入归档」。场次表上前者该显示 0，后者该显示「—」——
        // 只丢一个空对象出去的话，几个月前的场次会显示成「人气峰 0」，
        // 而那是一句假话：那时候的序列早就没了，我们不是知道它是 0，是不知道它是多少
        item.put("hasPeaks", session.hasPeaks());
        JSONObject peaks = new JSONObject();
        if (session.peaks() != null) {
            session.peaks().forEach((metric, peak) -> {
                JSONObject one = new JSONObject();
                one.put("at", peak.at());
                one.put("value", peak.value());
                peaks.put(metric, one);
            });
        }
        item.put("peaks", peaks);

        JSONArray titles = new JSONArray();
        if (session.titles() != null) {
            for (RoomInfoSnapshot title : session.titles()) {
                JSONObject entry = new JSONObject();
                entry.put("at", title.at());
                entry.put("title", title.title());
                entry.put("area", title.area());
                titles.add(entry);
            }
        }
        item.put("titles", titles);
        item.put("titleChangeCount", session.titleChangeCount());

        return item;
    }
}
