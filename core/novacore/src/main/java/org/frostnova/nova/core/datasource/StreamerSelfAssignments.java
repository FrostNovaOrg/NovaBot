package org.frostnova.nova.core.datasource;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.enums.PushTargetType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 读取配置时，无法确认唯一收件人的标记一律忽略，不按文件顺序挑选收件人。 */
@Slf4j
public final class StreamerSelfAssignments {
    private StreamerSelfAssignments() { }

    public static void normalize(JSONArray users) {
        normalize(users, warning -> log.warn("{}", warning));
    }

    /** 保存已校验的主播前，清除其他主播被忽略的旧标记，避免它们再次占用号码。 */
    public static void normalizeUnchanged(JSONArray users, Set<String> submitted) {
        JSONArray unchanged = new JSONArray();
        for (Object value : users) {
            if (value instanceof JSONObject user && submitted.contains(user.getString("platform") + ":" + user.getString("uid"))) {
                unchanged.add(new JSONObject());
            } else unchanged.add(value);
        }
        normalize(unchanged);
        omitUnconfirmed(users);
    }

    /** 未确认时不写字段，保持老配置的形态。 */
    public static void omitUnconfirmed(JSONArray users) {
        for (Object value : users) {
            if (!(value instanceof JSONObject user) || !(user.get("targets") instanceof JSONArray targets)) continue;
            for (Object entry : targets) {
                if (entry instanceof JSONObject target && (target.get("streamerSelf") == null
                        || Boolean.FALSE.equals(target.get("streamerSelf")))) target.remove("streamerSelf");
            }
        }
    }

    public static void normalize(JSONArray users, Consumer<String> warning) {
        List<Mark> marks = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<Long, Set<String>> owners = new HashMap<>();
        for (Object value : users) {
            if (!(value instanceof JSONObject user) || !(user.get("targets") instanceof JSONArray targets)) continue;
            String streamer = user.getString("platform") + ":" + user.getString("uid");
            for (int i = 0; i < targets.size(); i++) {
                if (!(targets.get(i) instanceof JSONObject target)) continue;
                Object self = target.get("streamerSelf");
                if (self == null || Boolean.FALSE.equals(self)) continue;
                Long number = null;
                Integer type = null;
                try { number = target.getLong("num"); type = target.getInteger("type"); }
                catch (RuntimeException malformed) { /* 无效的通道不能作为确认收件人。 */ }
                marks.add(new Mark(target, streamer, i + 1, number, type));
                if (Boolean.TRUE.equals(self)) {
                    counts.merge(streamer, 1, Integer::sum);
                    if (number != null) owners.computeIfAbsent(number, ignored -> new HashSet<>()).add(streamer);
                }
            }
        }
        for (Mark mark : marks) {
            String reason = !Boolean.TRUE.equals(mark.target().get("streamerSelf")) ? "标记必须是布尔值"
                    : !Objects.equals(mark.type(), PushTargetType.FRIEND.getCode()) ? "群通道不能标成主播本人"
                    : mark.number() == null ? "通道号码无效"
                    : counts.getOrDefault(mark.streamer(), 0) > 1 ? "一位主播最多只能标记一条好友通道"
                    : owners.getOrDefault(mark.number(), Set.of()).size() > 1 ? "这个号码同时被另一位主播标成主播本人"
                    : null;
            if (reason == null) continue;
            mark.target().remove("streamerSelf");
            warning.accept("主播 " + mark.streamer() + " 的第 " + mark.index() + " 条通道（"
                    + mark.target().getString("platform") + "，号码 " + mark.target().getString("num")
                    + "）忽略主播本人标记：" + reason);
        }
    }

    private record Mark(JSONObject target, String streamer, int index, Long number, Integer type) { }
}
