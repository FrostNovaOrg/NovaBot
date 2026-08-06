package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.event.live.common.RoomInfoChangeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 场次内的标题与分区变更记录
 * <p>
 * 一场直播改了几次标题、什么时候改的，是真实的运营信息：换个标题之后人气有没有起来，
 * 只有把变更时刻和互动曲线放在一起才看得出来。此前这类改动完全不留痕，
 * 下播报告里只有一个「当前标题」，无从判断这场究竟经历了什么。
 * <p>
 * 记录随本场直播存在，开播时清空。<b>存进状态存储而不是内存</b>，是为了让直播中途
 * 重启的程序仍能保住这场已经发生的变更——改标题往往就发生在开播头几分钟。
 */
@Slf4j
@Service
public class LiveRoomInfoHistory {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "LiveRoomInfo";

    /**
     * 单场最多记录的变更条数
     * <p>
     * 正常直播改标题不过三五次。设上限是因为这条数据由平台下发驱动，
     * 一旦对方开始重复下发，没有上限的列表会把状态文件撑大而没人察觉。
     */
    private static final int MAX_ENTRIES = 50;

    private final StarBotStateStore store;

    @Autowired
    public LiveRoomInfoHistory(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 记录一次标题或分区变更
     * <p>
     * 与上一条完全相同的内容会被丢弃：平台在主播打开设置又原样保存时照样下发，
     * 不判重的话报告会声称「本场改过 5 次标题」而其实一次都没改。
     */
    @EventListener
    public void onRoomInfoChange(RoomInfoChangeEvent event) {
        LiveStreamerInfo source = event.getSource();
        if (source == null || source.getUid() == null) {
            return;
        }

        record(event.getPlatform(), source.getUid(), event.getTimestamp(), event.getTitle(), event.fullAreaName());
    }

    /**
     * 开播时清空该主播的记录
     * <p>
     * 与核心重置本场数据同为 {@code -10000}，都要抢在各平台补写开播快照之前完成，
     * 否则刚记下的初始标题会被随后的清空抹掉。
     */
    @Order(-10000)
    @EventListener
    public void onLiveOn(LiveOnEvent event) {
        LiveStreamerInfo source = event.getSource();
        if (source == null || source.getUid() == null || event.isReconnect()) {
            return;
        }

        store.write(NAMESPACE, data -> data.remove(key(event.getPlatform(), source.getUid())));
    }

    /**
     * 记录一条变更
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param at 变更时刻（毫秒）
     * @param title 变更后的标题
     * @param area 变更后的分区描述
     */
    public void record(@NonNull String platform, @NonNull Long uid, long at, String title, String area) {
        String normalizedTitle = title == null ? "" : title.strip();
        String normalizedArea = area == null ? "" : area.strip();
        if (normalizedTitle.isEmpty() && normalizedArea.isEmpty()) {
            return;
        }

        store.write(NAMESPACE, data -> {
            JSONArray entries = data.getJSONArray(key(platform, uid));
            if (entries == null) {
                entries = new JSONArray();
                data.put(key(platform, uid), entries);
            }

            if (isSameAsLast(entries, normalizedTitle, normalizedArea)) {
                return;
            }

            if (entries.size() >= MAX_ENTRIES) {
                log.warn("{} 本场的标题变更已达 {} 条上限, 后续变更不再记录", uid, MAX_ENTRIES);
                return;
            }

            JSONObject entry = new JSONObject();
            entry.put("at", at);
            entry.put("title", normalizedTitle);
            entry.put("area", normalizedArea);
            entries.add(entry);
        });
    }

    /**
     * 读取本场的变更记录
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 按记录顺序排列的变更，没有记录时为空表
     */
    public List<RoomInfoSnapshot> history(@NonNull String platform, @NonNull Long uid) {
        JSONArray entries = store.namespace(NAMESPACE).getJSONArray(key(platform, uid));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<RoomInfoSnapshot> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry == null) {
                continue;
            }
            result.add(new RoomInfoSnapshot(
                    entry.getLongValue("at"), entry.getString("title"), entry.getString("area")));
        }
        return result;
    }

    /**
     * 本场标题实际改动的次数
     * <p>
     * 记录里的第一条是开播时的初始标题，不算一次改动，因此比条数少一。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 改动次数
     */
    public int changeCount(@NonNull String platform, @NonNull Long uid) {
        return Math.max(0, history(platform, uid).size() - 1);
    }

    /**
     * 本场最后一次记录到的标题
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 标题，没有记录时为空字符串
     */
    public String currentTitle(@NonNull String platform, @NonNull Long uid) {
        List<RoomInfoSnapshot> history = history(platform, uid);
        return history.isEmpty() ? "" : history.get(history.size() - 1).title();
    }

    /**
     * 判断待记录的内容与最后一条是否完全相同
     */
    private boolean isSameAsLast(JSONArray entries, String title, String area) {
        if (entries.isEmpty()) {
            return false;
        }

        JSONObject last = entries.getJSONObject(entries.size() - 1);
        return last != null && title.equals(last.getString("title")) && area.equals(last.getString("area"));
    }

    private static String key(String platform, Long uid) {
        return platform + ":" + uid;
    }
}
