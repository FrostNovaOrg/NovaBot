package org.frostnova.nova.core.service;

import java.util.List;

/** 只保存主播明确设置的条目，不保存私聊消息。时间使用毫秒时间戳；未设置时为空。 */
public record StreamerProfile(int tier, Long tierSetAt, List<Concern> concerns,
                              List<Exclusion> exclusions, boolean paused, Long pauseSetAt, Status status) {
    public enum Status { READY, UNREADABLE }
    public enum Pattern {
        newcomer_speak_return("新人开口", List.of("新人", "新观众", "开口")),
        regulars_absent("老观众", List.of("老观众", "常客", "老朋友")),
        slot_compare("开播时间", List.of("开播时间", "时段", "几点播")),
        pk_share("PK", List.of("PK"));

        private final String label;
        private final List<String> aliases;
        Pattern(String label, List<String> aliases) { this.label = label; this.aliases = aliases; }
        public String label() { return label; }
        public List<String> aliases() { return aliases; }
    }
    public record Concern(String text, long setAt) {
        public Concern {
            if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 30 || setAt < 0)
                throw new IllegalArgumentException("在意的条目不正确");
        }
    }
    public record Exclusion(Pattern type, long setAt) {
        public Exclusion {
            if (type == null || setAt < 0) throw new IllegalArgumentException("别提的条目不正确");
        }
    }

    public StreamerProfile {
        if (tier != 1 && tier != 2) throw new IllegalArgumentException("档位只能是 1 或 2");
        if (status == null) throw new IllegalArgumentException("缺少档案状态");
        if ((tierSetAt != null && tierSetAt < 0) || (pauseSetAt != null && pauseSetAt < 0)
                || (tier == 2 && tierSetAt == null) || (paused && pauseSetAt == null))
            throw new IllegalArgumentException("档案设置时间不正确");
        concerns = List.copyOf(concerns);
        exclusions = List.copyOf(exclusions);
    }
    public static StreamerProfile empty(Status status) {
        return new StreamerProfile(1, null, List.of(), List.of(), false, null, status);
    }
}
