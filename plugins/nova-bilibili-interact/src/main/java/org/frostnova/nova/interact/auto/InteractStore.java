package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService.Room;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设置立即存盘，发送记录随核心的运行状态定期存盘。 */
@NovaComponent
public class InteractStore {
    private static final String THANKED = "LiveInteractThanked";
    private static final String SETTINGS = "LiveInteractSettings";
    private static final String HISTORY = "LiveInteractHistory";
    private static final int LIMIT = 2000;
    private static final Duration RETENTION = Duration.ofDays(7);
    public static final int PAGE_SIZE = 20;
    public enum Outcome { SENT, NOT_SENT, UNCERTAIN, DROPPED }

    private final NovaStateStore store;

    public InteractStore(NovaStateStore store) {
        this.store = store;
    }

    public synchronized RoomSettings settings(long uid) {
        String key = String.valueOf(uid);
        return store.read(SETTINGS, key, section -> RoomSettings.parse(section.getJSONObject(key)))
                .orElse(RoomSettings.DEFAULT);
    }

    synchronized void save(long uid, RoomSettings settings) {
        String key = String.valueOf(uid);
        var previous = store.read(SETTINGS, key, section -> section.getJSONObject(key));
        store.write(SETTINGS, section -> section.put(key, JSONObject.from(settings)));
        if (!store.trySave()) {
            store.write(SETTINGS, section -> previous.ifPresentOrElse(
                    value -> section.put(key, value), () -> section.remove(key)));
            throw new IllegalStateException("主播设置未能写入磁盘");
        }
    }

    /** 只恢复同一开播时间的已答谢观众，合并窗口不写入状态文件。 */
    Set<String> thanked(long uid, long start, String feature) {
        String key = String.valueOf(uid);
        return store.read(THANKED, key, root -> {
            JSONObject session = root.getJSONObject(key);
            JSONArray viewers = session.getJSONArray(feature);
            return session.getLongValue("start") == start && viewers != null
                    ? new HashSet<>(viewers.toList(String.class)) : new HashSet<String>();
        }).orElseGet(HashSet::new);
    }

    void rememberThanked(long uid, long start, String feature, Set<String> viewers) {
        String key = String.valueOf(uid);
        store.write(THANKED, root -> {
            JSONObject session = root.getJSONObject(key);
            if (session == null || session.getLongValue("start") != start) {
                session = JSONObject.of("start", start);
                root.put(key, session);
            }
            session.putIfAbsent(feature, new JSONArray());
            session.getJSONArray(feature).addAll(viewers);
        });
    }

    void forgetThanked(long uid) {
        String key = String.valueOf(uid);
        if (store.read(THANKED, key, root -> true).isPresent()) store.write(THANKED, root -> root.remove(key));
    }

    public void result(Room room, String feature, DanmakuResult result, Instant now) {
        Outcome outcome = result.sent() ? Outcome.SENT : result.uncertain() ? Outcome.UNCERTAIN : Outcome.NOT_SENT;
        append(room, feature, result.text(), outcome, result.sent() ? "已发出" : result.failure().description(), now);
    }

    void drop(Room room, String feature, String text, String reason, Instant now) {
        append(room, feature, text, Outcome.DROPPED, reason, now);
    }

    private void append(Room room, String feature, String text, Outcome result, String reason, Instant now) {
        store.write(HISTORY, root -> {
            JSONObject section = root.getJSONObject("rooms");
            if (section == null) {
                section = new JSONObject();
                root.put("rooms", section);
            }
            String key = String.valueOf(room.uid());
            JSONArray rows = section.getJSONArray(key);
            if (rows == null) {
                rows = new JSONArray();
                section.put(key, rows);
            }
            long time = now.toEpochMilli();
            int at = rows.size();
            while (at > 0 && rows.getJSONObject(at - 1).getLongValue("time") > time) at--;
            rows.add(at, JSONObject.of("time", time, "uid", key, "streamer", room.name(), "feature", feature, "text", text,
                    "outcome", result.name(), "reason", reason));
            trim(rows, now);
        });
    }

    private static void trim(JSONArray rows, Instant now) {
        long cutoff = now.minus(RETENTION).toEpochMilli();
        int remove = Math.max(0, rows.size() - LIMIT);
        while (remove < rows.size() && rows.getJSONObject(remove).getLongValue("time") <= cutoff) remove++;
        if (remove > 0) rows.subList(0, remove).clear();
    }

    void pruneHistory(Instant now) {
        store.write(HISTORY, root -> {
            JSONObject section = root.getJSONObject("rooms");
            if (section == null) return;
            section.keySet().removeIf(key -> {
                JSONArray rows = section.getJSONArray(key);
                trim(rows, now);
                return rows.isEmpty();
            });
            if (section.isEmpty()) root.remove("rooms");
        });
    }

    /** 移除主播后，保留期内仍可选中他的发送记录。 */
    public List<JSONObject> historyRooms(Instant now) {
        return store.read(HISTORY, "rooms", root -> {
            JSONObject rooms = root.getJSONObject("rooms");
            List<JSONObject> streamers = new ArrayList<>();
            long cutoff = now.minus(RETENTION).toEpochMilli();
            for (String key : rooms.keySet()) {
                JSONArray rows = rooms.getJSONArray(key);
                JSONObject last = rows.getJSONObject(rows.size() - 1);
                if (last.getLongValue("time") > cutoff) {
                    streamers.add(JSONObject.of("uid", key, "name", last.getString("streamer")));
                }
            }
            return streamers;
        }).orElse(List.of());
    }

    public record Page(List<JSONObject> rows, int total) {}

    public Page history(long uid, Outcome outcome, int page, Instant now) {
        if (page < 1) throw new IllegalArgumentException("页码从 1 开始");
        String key = String.valueOf(uid);
        return store.read(HISTORY, "rooms", root -> {
            JSONArray rows = root.getJSONObject("rooms").getJSONArray(key);
            if (rows == null) return new Page(List.of(), 0);
            List<JSONObject> selected = new ArrayList<>();
            long offset = ((long) page - 1) * PAGE_SIZE;
            long cutoff = now.minus(RETENTION).toEpochMilli();
            int count = 0;
            for (int i = rows.size() - 1; i >= 0; i--) {
                JSONObject row = rows.getJSONObject(i);
                if (row.getLongValue("time") <= cutoff) continue;
                if (outcome != null && !outcome.name().equals(row.getString("outcome"))) continue;
                if (count++ >= offset && selected.size() < PAGE_SIZE) selected.add(new JSONObject(row));
            }
            return new Page(selected, count);
        }).orElseGet(() -> new Page(List.of(), 0));
    }
}
