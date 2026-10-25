package org.frostnova.nova.core.analytics;

import java.util.List;
import java.util.Map;

/** 可回放的确定性小结素材，时刻为毫秒，金额为元，比例为 0 到 1。 */
public final class SessionRecap {
    private SessionRecap() {}

    public record Streamer(String platform, long uid) {}
    public record Sample(int sessions, long from) {}
    /** path 相对直播归档根目录，selector 指明字段或场次。 */
    public record Evidence(String path, String selector) {}
    public record Item(String key, Streamer streamer, long session, Map<String, Object> values,
                       Sample sample, String strength, List<Long> people, List<Evidence> evidence) {
        public Item {
            values = Map.copyOf(values);
            people = List.copyOf(people);
            evidence = List.copyOf(evidence);
        }
    }
    /** 返回全部规律候选，展示方负责挑选。事实的 strength 为 null。 */
    public record Result(List<Item> facts, List<Item> patterns) {
        public Result {
            facts = List.copyOf(facts);
            patterns = List.copyOf(patterns);
        }
    }
}
