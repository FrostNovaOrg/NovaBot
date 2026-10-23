package org.frostnova.nova.report.painter;

import lombok.Builder;
import java.awt.Color;
import java.util.List;
import java.util.Objects;

/** 报告图配色。每张画布持有一份不可变配色，平台返回的粉丝牌颜色保持原样。 */
@Builder(toBuilder = true)
public record ReportPalette(
        /** 画布底色 */ Color background,
        /** 卡片与图表基线 */ Color card,
        /** 报告正文 */ Color text,
        /** 报告提示 */ Color tip,
        /** 主播名 */ Color streamerName,
        /** 主题强调 */ Color accent,
        /** 流水曲线 */ Color curveRevenue,
        /** 上涨 */ Color up,
        /** 下跌 */ Color down,
        /** 通用文字与边框默认色 */ Color defaultText,
        /** 通用提示与版权文字 */ Color defaultTip,
        /** 链接 */ Color link,
        /** 转发动态底色 */ Color originBackground,
        /** 亏损比例条 */ Color loss,
        /** 看过人数曲线 */ Color curveWatched,
        /** 在线人数曲线 */ Color curveOnline,
        /** 弹幕曲线 */ Color curveDanmu,
        /** 采集缺口斜纹 */ Color curveGapHatch,
        /** 采集缺口边界 */ Color curveGapEdge,
        /** 本场标签与礼物占位底色 */ Color tagBackground,
        /** 头像描边 */ Color avatarRing,
        /** 周报场次标签底色 */ Color sessionBadge,
        /** 第一名 */ Color rankFirst,
        /** 第二名 */ Color rankSecond,
        /** 第三名 */ Color rankThird,
        /** 其余名次 */ Color rankOther,
        /** 回访观众 */ Color returningAudience,
        /** 日历已播 */ Color calendarRecorded,
        /** 日历未播 */ Color calendarOff,
        /** 日历未记录 */ Color calendarUnknown,
        /** 日历边框 */ Color calendarBorder,
        /** 总督缺省标记 */ Color guardGovernor,
        /** 提督缺省标记 */ Color guardCommander,
        /** 舰长缺省标记 */ Color guardCaptain,
        /** 未知大航海等级缺省标记 */ Color guardUnknown,
        /** 占位渐变起点 */ Color placeholderFrom,
        /** 占位渐变终点 */ Color placeholderTo,
        /** 页脚图标渐变起点 */ Color markFrom,
        /** 页脚图标渐变终点 */ Color markTo,
        /** 页脚图标星形 */ Color markStar,
        /** 词云无内容提示 */ Color wordCloudEmpty,
        /** 词云：主词、次主词、辅助词、交错词、普通词、点缀词。 */ List<Color> wordCloud) {
    /** 与既有报告图逐色一致的默认配色。 */
    public static final ReportPalette DEFAULT = new ReportPalette(
            Color.WHITE,
            new Color(246, 247, 249),
            new Color(51, 51, 51),
            new Color(153, 162, 170),
            new Color(251, 114, 153),
            new Color(251, 114, 153),
            new Color(251, 114, 153),
            new Color(0xCA1226),
            new Color(0x167349),
            Color.BLACK,
            Color.LIGHT_GRAY,
            new Color(23, 139, 207),
            new Color(244, 245, 247),
            new Color(110, 199, 122),
            new Color(110, 199, 122),
            new Color(255, 99, 132),
            new Color(0, 174, 236),
            new Color(206, 212, 218),
            new Color(168, 178, 188),
            new Color(255, 236, 242),
            Color.WHITE,
            Color.WHITE,
            new Color(240, 173, 78),
            new Color(160, 174, 192),
            new Color(205, 133, 96),
            new Color(251, 168, 193),
            new Color(0x69A9CC),
            new Color(252, 159, 186),
            new Color(250, 251, 252),
            new Color(225, 228, 231),
            new Color(210, 215, 220),
            new Color(0xE6, 0xB4, 0x22),
            new Color(0xA7, 0x73, 0xF1),
            new Color(0x3F, 0xB4, 0xF6),
            new Color(0x9E, 0x9E, 0x9E),
            new Color(203, 213, 225),
            new Color(148, 163, 184),
            new Color(0x7C, 0x5C, 0xFF),
            new Color(0xFF, 0x5F, 0x9E),
            Color.WHITE,
            new Color(0x6E6A86),
            List.of(new Color(0xE0479E), new Color(0x7A4DFF), new Color(0xA38BFF),
                    new Color(0x00A6D6), new Color(0x6E6A86), new Color(0xF2A93B)));

    public ReportPalette {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tip, "tip");
        Objects.requireNonNull(streamerName, "streamerName");
        Objects.requireNonNull(accent, "accent");
        Objects.requireNonNull(curveRevenue, "curveRevenue");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(defaultText, "defaultText");
        Objects.requireNonNull(defaultTip, "defaultTip");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(originBackground, "originBackground");
        Objects.requireNonNull(loss, "loss");
        Objects.requireNonNull(curveWatched, "curveWatched");
        Objects.requireNonNull(curveOnline, "curveOnline");
        Objects.requireNonNull(curveDanmu, "curveDanmu");
        Objects.requireNonNull(curveGapHatch, "curveGapHatch");
        Objects.requireNonNull(curveGapEdge, "curveGapEdge");
        Objects.requireNonNull(tagBackground, "tagBackground");
        Objects.requireNonNull(avatarRing, "avatarRing");
        Objects.requireNonNull(sessionBadge, "sessionBadge");
        Objects.requireNonNull(rankFirst, "rankFirst");
        Objects.requireNonNull(rankSecond, "rankSecond");
        Objects.requireNonNull(rankThird, "rankThird");
        Objects.requireNonNull(rankOther, "rankOther");
        Objects.requireNonNull(returningAudience, "returningAudience");
        Objects.requireNonNull(calendarRecorded, "calendarRecorded");
        Objects.requireNonNull(calendarOff, "calendarOff");
        Objects.requireNonNull(calendarUnknown, "calendarUnknown");
        Objects.requireNonNull(calendarBorder, "calendarBorder");
        Objects.requireNonNull(guardGovernor, "guardGovernor");
        Objects.requireNonNull(guardCommander, "guardCommander");
        Objects.requireNonNull(guardCaptain, "guardCaptain");
        Objects.requireNonNull(guardUnknown, "guardUnknown");
        Objects.requireNonNull(placeholderFrom, "placeholderFrom");
        Objects.requireNonNull(placeholderTo, "placeholderTo");
        Objects.requireNonNull(markFrom, "markFrom");
        Objects.requireNonNull(markTo, "markTo");
        Objects.requireNonNull(markStar, "markStar");
        Objects.requireNonNull(wordCloudEmpty, "wordCloudEmpty");
        wordCloud = List.copyOf(wordCloud);
        if (wordCloud.size() != 6) throw new IllegalArgumentException("wordCloud requires six colors");
    }

    public Color rank(int rank) {
        return switch (rank) {
            case 1 -> rankFirst;
            case 2 -> rankSecond;
            case 3 -> rankThird;
            default -> rankOther;
        };
    }

    public Color guard(int level) {
        return switch (level) {
            case 1 -> guardGovernor;
            case 2 -> guardCommander;
            case 3 -> guardCaptain;
            default -> guardUnknown;
        };
    }
}
