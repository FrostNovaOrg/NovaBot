package org.frostnova.nova.core.analytics;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可回放的确定性小结素材，时刻为毫秒，金额为元，比例为 0 到 1。 */
public final class SessionRecap {
    private SessionRecap() {}

    public record Streamer(String platform, long uid) {}
    public record Sample(int sessions, long from, int qualifiedSessions) {
        public Sample(int sessions, long from) { this(sessions, from, sessions); }
    }
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
    /** 模板需要的场次信息与少量展示昵称；拒绝名单仍由展示方再次检查。 */
    public record Presentation(long start, int previousSessions, String listQuality, Long previousSession,
                               Map<Long, String> nicknames, Set<Long> excludedUids,
                               Map<String, Set<Long>> wordNicknameOwners) {
        public Presentation(long start, int previousSessions, String listQuality, Long previousSession,
                            Map<Long, String> nicknames, Set<Long> excludedUids) {
            this(start, previousSessions, listQuality, previousSession, nicknames, excludedUids, Map.of());
        }
        public Presentation {
            nicknames = Map.copyOf(nicknames);
            excludedUids = Set.copyOf(excludedUids);
            wordNicknameOwners = wordNicknameOwners.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        }
    }
    public record Result(List<Item> facts, List<Item> patterns, Presentation presentation) {
        public Result(List<Item> facts, List<Item> patterns) { this(facts, patterns, null); }
        public Result {
            facts = List.copyOf(facts);
            patterns = List.copyOf(patterns);
        }
    }
}
