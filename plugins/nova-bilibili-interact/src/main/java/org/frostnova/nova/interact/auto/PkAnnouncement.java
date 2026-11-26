package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService.Profile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

final class PkAnnouncement {
    static final String FEATURE = "pkOpponent";
    static final int PRIORITY = 5;
    static final DanmakuTemplate TEXT = new DanmakuTemplate("PK 对手信息播报",
            "本场PK对手：{对手}，粉丝 {粉丝}，大航海 {舰队}，高能 {高能}", Set.of("对手", "主播", "粉丝", "舰队", "高能"));

    static String render(String override, String streamer, Profile profile) {
        if (profile == null || DanmakuTemplate.blank(profile.nickname())) return null;
        String template = override;
        if (template.isEmpty()) {
            template = TEXT.defaultText();
            if (profile.fans() == null) template = template.replace("，粉丝 {粉丝}", "");
            if (profile.guards() == null) template = template.replace("，大航海 {舰队}", "");
            if (profile.onlineRankCount() == null) template = template.replace("，高能 {高能}", "");
        }
        return TEXT.render(template, field -> switch (field) {
            case "对手" -> RoomThanks.shortName(profile.nickname());
            case "主播" -> RoomThanks.shortName(streamer);
            case "粉丝" -> count(profile.fans());
            case "舰队" -> count(profile.guards());
            case "高能" -> count(profile.onlineRankCount());
            default -> throw new IllegalArgumentException("未知的 PK 占位符");
        });
    }

    private static String count(Number value) {
        if (value == null) return "未知";
        if (value.longValue() < 10000) return value.toString();
        return BigDecimal.valueOf(value.longValue(), 4).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "万";
    }
}
