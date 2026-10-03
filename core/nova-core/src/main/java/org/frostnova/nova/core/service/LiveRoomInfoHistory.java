package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.event.live.common.RoomInfoChangeEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
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
 * 只有把变更时刻和互动曲线放在一起才看得出来。此前这类改动完全没有记录，
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

    private final NovaStateStore store;

    @Autowired
    public LiveRoomInfoHistory(NovaStateStore store) {
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
     * 先后两头都有约定，定在 {@code -9999}：
     * <ul>
     *     <li><b>必须晚于核心重置本场数据</b>（{@code -10000}）。未闭合场次的补档在那一步里，
     *         靠的就是这份还没清的轨迹——清空若抢到补档前面，停机里结束的那一场
     *         就带着空标题轨迹归了档</li>
     *     <li><b>必须抢在各平台补写开播快照之前完成</b>（快照是 {@code 0} 一档），
     *         否则刚记下的初始标题会被随后的清空抹掉</li>
     * </ul>
     */
    @Order(-9999)
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
        return history(platform, uid, Long.MAX_VALUE);
    }

    /**
     * 读取截至某时刻的变更记录
     * <p>
     * 补档那一路用：未闭合场次的结束时刻之后，主播可能又改过标题——
     * {@link #onRoomInfoChange} 记变更不看在不在播，两场之间改的那条会挂在旧场名下。
     * 按时刻截掉，改在结束之后的标题才不会记进已结束的那一场。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param upTo 截止时刻（毫秒），只取 {@code at} 不晚于它的条目
     * @return 按记录顺序排列的变更，没有记录时为空表
     */
    public List<RoomInfoSnapshot> history(@NonNull String platform, @NonNull Long uid, long upTo) {
        JSONArray entries = store.namespace(NAMESPACE).getJSONArray(key(platform, uid));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<RoomInfoSnapshot> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry == null || entry.getLongValue("at") > upTo) {
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
