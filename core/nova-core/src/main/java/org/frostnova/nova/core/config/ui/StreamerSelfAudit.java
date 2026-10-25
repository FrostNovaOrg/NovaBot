package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按主播身份比较接收号码，忽略通道排序，每位主播每次变动只记一条。 */
public final class StreamerSelfAudit {
    private StreamerSelfAudit() { }

    public static void record(JSONArray before, JSONArray after, String actor,
                              AbstractDataSource source, TimelineWriter timeline) {
        Map<String, JSONObject> previous = index(before);
        Map<String, JSONObject> current = index(after);
        Map<String, JSONObject> all = new LinkedHashMap<>(previous);
        all.putAll(current);
        for (var entry : all.entrySet()) {
            List<String> old = marked(previous.get(entry.getKey()));
            List<String> selected = marked(current.get(entry.getKey()));
            if (Objects.equals(old, selected)) continue;
            JSONObject user = entry.getValue();
            String platform = user.getString("platform");
            String uid = user.getString("uid");
            String streamer = ConfigUiAudit.streamer(source, platform, uid);
            String change = selected.isEmpty() ? "取消了主播本人标记，原号码 " + String.join("、", old)
                    : old.isEmpty() ? "将主播本人设为 " + String.join("、", selected)
                    : "将主播本人从 " + String.join("、", old) + " 改为 " + String.join("、", selected);
            timeline.record(TimelineEvent.of(TimelineEventType.STREAMER_SELF_CHANGED, TimelineEvent.Level.INFO)
                    .streamer(streamer).channel(selected.isEmpty() ? String.join("、", old) : String.join("、", selected))
                    .text(ConfigUiAudit.safe(actor) + " 为主播 " + streamer + " " + change)
                    .detail("actor", ConfigUiAudit.safe(actor))
                    .detail("streamerPlatform", platform).detail("streamerUid", uid)
                    .detail("previous", String.join("、", old)).detail("current", String.join("、", selected))
                    .build());
        }
    }

    private static Map<String, JSONObject> index(JSONArray users) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (Object item : users) {
            if (item instanceof JSONObject user) result.put(user.getString("platform") + ":" + user.getString("uid"), user);
        }
        return result;
    }

    private static List<String> marked(JSONObject user) {
        if (user == null || user.getJSONArray("targets") == null) return List.of();
        return user.getJSONArray("targets").stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .filter(target -> Boolean.TRUE.equals(target.get("streamerSelf")))
                .map(target -> (Objects.equals(target.getInteger("type"), 1) ? "群号 " : "好友号 ")
                        + target.getString("num") + "（" + ConfigUiAudit.safe(target.getString("platform")) + "）")
                .sorted().toList();
    }
}
