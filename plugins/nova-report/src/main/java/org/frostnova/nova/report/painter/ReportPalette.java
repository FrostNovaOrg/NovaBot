package org.frostnova.nova.report.painter;

import lombok.Builder;
import java.awt.Color;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /** 经典：既有报告图的样子，就是 {@link #DEFAULT}。 */
    public static final ReportPalette CLASSIC = DEFAULT;

    /** 粉蓝：近白微粉的底、淡蓝卡片，粉色主调、蓝色辅调。 */
    public static final ReportPalette PINK_BLUE = builder()
            .background(new Color(0xFFFBFD))
            .card(new Color(0xF1F5FC))
            .text(new Color(0x262B36))
            .tip(new Color(0x5E6878))
            .streamerName(new Color(0xD2386F))
            .accent(new Color(0xC23468))
            .curveRevenue(new Color(0xE0568A))
            .up(new Color(0xC22A52))
            .down(new Color(0x1D7553))
            .defaultText(new Color(0x262B36))
            .defaultTip(new Color(0x687386))
            .link(new Color(0x2366B5))
            .originBackground(new Color(0xF3F6FB))
            .loss(new Color(0x5BB98C))
            .curveWatched(new Color(0x7B6FE0))
            .curveOnline(new Color(0xE0568A))
            .curveDanmu(new Color(0x2A7FCC))
            .curveGapHatch(new Color(0xD6DDE8))
            .curveGapEdge(new Color(0xA5B1C3))
            .tagBackground(new Color(0xFEEFF5))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFFFF))
            .rankFirst(new Color(0xDA3E78))
            .rankSecond(new Color(0x3477CC))
            .rankThird(new Color(0x8560D0))
            .rankOther(new Color(0x7D8899))
            .returningAudience(new Color(0x6FA6E6))
            .calendarRecorded(new Color(0xF9C4D7))
            .calendarOff(new Color(0xF7F9FC))
            .calendarUnknown(new Color(0xE3E9F1))
            .calendarBorder(new Color(0xD2DAE5))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0xF7D5E2))
            .placeholderTo(new Color(0xC3D8F2))
            .markFrom(new Color(0x3E86DA))
            .markTo(new Color(0xE0568A))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x687386))
            .wordCloud(List.of(new Color(0xD2386F), new Color(0x3477CC), new Color(0x7D57C9),
                    new Color(0x15789F), new Color(0x5E6878), new Color(0xB8541F)))
            .build();

    /** 暖阳：奶油色的底、杏色卡片，橙与砖红为主调，深棕文字。 */
    public static final ReportPalette WARM = builder()
            .background(new Color(0xFFF9F2))
            .card(new Color(0xFBEEDF))
            .text(new Color(0x3A2A1F))
            .tip(new Color(0x76594A))
            .streamerName(new Color(0xBF4E16))
            .accent(new Color(0xA84A16))
            .curveRevenue(new Color(0xE07A2E))
            .up(new Color(0xB8322A))
            .down(new Color(0x2F6E3B))
            .defaultText(new Color(0x3A2A1F))
            .defaultTip(new Color(0x7C6252))
            .link(new Color(0x9A4A12))
            .originBackground(new Color(0xFCF1E4))
            .loss(new Color(0x8DBE7A))
            .curveWatched(new Color(0xD9A23A))
            .curveOnline(new Color(0xD2603A))
            .curveDanmu(new Color(0xB8562A))
            .curveGapHatch(new Color(0xEFDCC8))
            .curveGapEdge(new Color(0xC9AE95))
            .tagBackground(new Color(0xFEEEDF))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFCF8))
            .rankFirst(new Color(0xC2620E))
            .rankSecond(new Color(0x8C6E5C))
            .rankThird(new Color(0xB4472E))
            .rankOther(new Color(0x967A68))
            .returningAudience(new Color(0xE9B06A))
            .calendarRecorded(new Color(0xF7C08C))
            .calendarOff(new Color(0xFFF6EC))
            .calendarUnknown(new Color(0xEEDFCF))
            .calendarBorder(new Color(0xE2CDB8))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0xFBE0C6))
            .placeholderTo(new Color(0xF0BE92))
            .markFrom(new Color(0xE9853A))
            .markTo(new Color(0xD24F5E))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x7C6252))
            .wordCloud(List.of(new Color(0xC2501A), new Color(0xD0731B), new Color(0xA85A3A),
                    new Color(0x8E6418), new Color(0x76594A), new Color(0xB23A4E)))
            .build();

    /** 夜幕：深蓝灰的底、浅色文字，粉、蓝、金点缀，适合夜里看。 */
    public static final ReportPalette NIGHT = builder()
            .background(new Color(0x161A23))
            .card(new Color(0x222835))
            .text(new Color(0xE9ECF3))
            .tip(new Color(0xA6AEC0))
            .streamerName(new Color(0xFF8FB8))
            .accent(new Color(0xFF85B0))
            .curveRevenue(new Color(0xFF85B0))
            .up(new Color(0xFF7A8A))
            .down(new Color(0x4CD39B))
            .defaultText(new Color(0xE9ECF3))
            .defaultTip(new Color(0x949DB1))
            .link(new Color(0x76BCFF))
            .originBackground(new Color(0x1F2430))
            .loss(new Color(0x4CC38C))
            .curveWatched(new Color(0x5FD49C))
            .curveOnline(new Color(0xFF7A9E))
            .curveDanmu(new Color(0x55C6F5))
            .curveGapHatch(new Color(0x343B4C))
            .curveGapEdge(new Color(0x5A6379))
            .tagBackground(new Color(0x3D2636))
            .avatarRing(new Color(0x161A23))
            .sessionBadge(new Color(0x2E3545))
            .rankFirst(new Color(0xFFC75A))
            .rankSecond(new Color(0xC7D0DD))
            .rankThird(new Color(0xEBA77C))
            .rankOther(new Color(0x8790A4))
            .returningAudience(new Color(0x7FB0F0))
            .calendarRecorded(new Color(0x7E3553))
            .calendarOff(new Color(0x1E2330))
            .calendarUnknown(new Color(0x2B3242))
            .calendarBorder(new Color(0x3A4257))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0x2B3243))
            .placeholderTo(new Color(0x485268))
            .markFrom(new Color(0x8C7CFF))
            .markTo(new Color(0xFF6FA6))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0xA6AEC0))
            .wordCloud(List.of(new Color(0xFF85B0), new Color(0xA895FF), new Color(0xC9BCFF),
                    new Color(0x55C6F5), new Color(0xA6AEC0), new Color(0xFFC266)))
            .build();

    /** 薄荷：近白薄荷底、浅绿卡片，深绿主调、青绿点缀。 */
    public static final ReportPalette MINT = builder()
            .background(new Color(0xF7FCF9))
            .card(new Color(0xEAF5EE))
            .text(new Color(0x20392F))
            .tip(new Color(0x50695C))
            .streamerName(new Color(0x147451))
            .accent(new Color(0x007469))
            .curveRevenue(new Color(0x16856B))
            .up(new Color(0xA43F40))
            .down(new Color(0x236845))
            .defaultText(new Color(0x20392F))
            .defaultTip(new Color(0x566D62))
            .link(new Color(0x146F80))
            .originBackground(new Color(0xEEF7F2))
            .loss(new Color(0x78AD80))
            .curveWatched(new Color(0x5E8D38))
            .curveOnline(new Color(0x16856B))
            .curveDanmu(new Color(0x21789A))
            .curveGapHatch(new Color(0xD1E3D8))
            .curveGapEdge(new Color(0x8FAB9B))
            .tagBackground(new Color(0xE2F3E9))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFFFF))
            .rankFirst(new Color(0x147451))
            .rankSecond(new Color(0x21789A))
            .rankThird(new Color(0x647A32))
            .rankOther(new Color(0x64786E))
            .returningAudience(new Color(0x70A99A))
            .calendarRecorded(new Color(0xB9DEC9))
            .calendarOff(new Color(0xF4FAF6))
            .calendarUnknown(new Color(0xDCEBE2))
            .calendarBorder(new Color(0xB7CFC1))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0xD4EBDD))
            .placeholderTo(new Color(0xA8D9CF))
            .markFrom(new Color(0x16856B))
            .markTo(new Color(0x21789A))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x566D62))
            .wordCloud(List.of(new Color(0x147451), new Color(0x21789A), new Color(0x536F2D),
                    new Color(0x0D7371), new Color(0x50695C), new Color(0x8A5930)))
            .build();

    /** 海蓝：近白蓝底、浅蓝卡片，深蓝主调、海蓝点缀。 */
    public static final ReportPalette OCEAN = builder()
            .background(new Color(0xF7FAFE))
            .card(new Color(0xEAF1F9))
            .text(new Color(0x203047))
            .tip(new Color(0x53657D))
            .streamerName(new Color(0x1D4F91))
            .accent(new Color(0x146B98))
            .curveRevenue(new Color(0x2874B8))
            .up(new Color(0xA43D4D))
            .down(new Color(0x26664F))
            .defaultText(new Color(0x203047))
            .defaultTip(new Color(0x586A80))
            .link(new Color(0x145E9C))
            .originBackground(new Color(0xEDF3FA))
            .loss(new Color(0x78A7C6))
            .curveWatched(new Color(0x5A8EB0))
            .curveOnline(new Color(0x1D4F91))
            .curveDanmu(new Color(0x2874B8))
            .curveGapHatch(new Color(0xD0DDEB))
            .curveGapEdge(new Color(0x90A8C2))
            .tagBackground(new Color(0xE2EDF9))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFFFF))
            .rankFirst(new Color(0x1D4F91))
            .rankSecond(new Color(0x2874B8))
            .rankThird(new Color(0x527C9B))
            .rankOther(new Color(0x63758B))
            .returningAudience(new Color(0x75A5CC))
            .calendarRecorded(new Color(0xBCD4EC))
            .calendarOff(new Color(0xF4F8FD))
            .calendarUnknown(new Color(0xDCE6F2))
            .calendarBorder(new Color(0xB4C7DE))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0xD2E3F4))
            .placeholderTo(new Color(0xA3C7E5))
            .markFrom(new Color(0x1D4F91))
            .markTo(new Color(0x2874B8))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x586A80))
            .wordCloud(List.of(new Color(0x1D4F91), new Color(0x2874B8), new Color(0x356486),
                    new Color(0x146B98), new Color(0x53657D), new Color(0x55558E)))
            .build();

    /** 星紫：近白紫底、淡紫卡片，深紫主调、兰紫点缀。 */
    public static final ReportPalette VIOLET = builder()
            .background(new Color(0xFCF9FF))
            .card(new Color(0xF2ECF8))
            .text(new Color(0x35283F))
            .tip(new Color(0x6A5878))
            .streamerName(new Color(0x7043A5))
            .accent(new Color(0x8650A0))
            .curveRevenue(new Color(0x8A5AB8))
            .up(new Color(0xAF375D))
            .down(new Color(0x356955))
            .defaultText(new Color(0x35283F))
            .defaultTip(new Color(0x705E7D))
            .link(new Color(0x6846A5))
            .originBackground(new Color(0xF5EFFA))
            .loss(new Color(0x9E8EBF))
            .curveWatched(new Color(0x6D70A7))
            .curveOnline(new Color(0x8A5AB8))
            .curveDanmu(new Color(0x6755AE))
            .curveGapHatch(new Color(0xE0D5EC))
            .curveGapEdge(new Color(0xB09AC7))
            .tagBackground(new Color(0xF0E4F8))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFFFF))
            .rankFirst(new Color(0x7043A5))
            .rankSecond(new Color(0x8A5AB8))
            .rankThird(new Color(0xA05287))
            .rankOther(new Color(0x7C6B88))
            .returningAudience(new Color(0xA99ACD))
            .calendarRecorded(new Color(0xD8C4EB))
            .calendarOff(new Color(0xFAF6FE))
            .calendarUnknown(new Color(0xE9DFF1))
            .calendarBorder(new Color(0xCDBBDD))
            .guardGovernor(DEFAULT.guardGovernor())
            .guardCommander(DEFAULT.guardCommander())
            .guardCaptain(DEFAULT.guardCaptain())
            .guardUnknown(DEFAULT.guardUnknown())
            .placeholderFrom(new Color(0xE7D7F3))
            .placeholderTo(new Color(0xCBB4E4))
            .markFrom(new Color(0x7043A5))
            .markTo(new Color(0xA05287))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x705E7D))
            .wordCloud(List.of(new Color(0x7043A5), new Color(0x8A5AB8), new Color(0x805392),
                    new Color(0x6755AE), new Color(0x6A5878), new Color(0x9B4477)))
            .build();

    /** 墨白：白底、浅灰卡片，黑白灰区分层次，文字与数字保持高对比度。 */
    public static final ReportPalette INK = builder()
            .background(new Color(0xFFFFFF))
            .card(new Color(0xF1F1F1))
            .text(new Color(0x161616))
            .tip(new Color(0x555555))
            .streamerName(new Color(0x202020))
            .accent(new Color(0x454545))
            .curveRevenue(new Color(0x222222))
            .up(new Color(0x222222))
            .down(new Color(0x626262))
            .defaultText(new Color(0x161616))
            .defaultTip(new Color(0x555555))
            .link(new Color(0x333333))
            .originBackground(new Color(0xF5F5F5))
            .loss(new Color(0xAAAAAA))
            .curveWatched(new Color(0x999999))
            .curveOnline(new Color(0x222222))
            .curveDanmu(new Color(0x666666))
            .curveGapHatch(new Color(0xD5D5D5))
            .curveGapEdge(new Color(0x888888))
            .tagBackground(new Color(0xE5E5E5))
            .avatarRing(new Color(0xFFFFFF))
            .sessionBadge(new Color(0xFFFFFF))
            .rankFirst(new Color(0x202020))
            .rankSecond(new Color(0x555555))
            .rankThird(new Color(0x777777))
            .rankOther(new Color(0x777777))
            .returningAudience(new Color(0xA0A0A0))
            .calendarRecorded(new Color(0xBFBFBF))
            .calendarOff(new Color(0xFFFFFF))
            .calendarUnknown(new Color(0xE0E0E0))
            .calendarBorder(new Color(0x999999))
            .guardGovernor(new Color(0x333333))
            .guardCommander(new Color(0x555555))
            .guardCaptain(new Color(0x777777))
            .guardUnknown(new Color(0x999999))
            .placeholderFrom(new Color(0xE0E0E0))
            .placeholderTo(new Color(0xB0B0B0))
            .markFrom(new Color(0x222222))
            .markTo(new Color(0x666666))
            .markStar(new Color(0xFFFFFF))
            .wordCloudEmpty(new Color(0x555555))
            .wordCloud(List.of(new Color(0x202020), new Color(0x454545), new Color(0x555555),
                    new Color(0x606060), new Color(0x686868), new Color(0x707070)))
            .build();

    /** 具名预设，按展示顺序。id 供存储，name 供展示。 */
    public static final List<Preset> PRESETS = List.of(
            new Preset("classic", "经典", CLASSIC),
            new Preset("pink-blue", "粉蓝", PINK_BLUE),
            new Preset("warm", "暖阳", WARM),
            new Preset("night", "夜幕", NIGHT),
            new Preset("mint", "薄荷", MINT),
            new Preset("ocean", "海蓝", OCEAN),
            new Preset("violet", "星紫", VIOLET),
            new Preset("ink", "墨白", INK));

    /**
     * 按存储用的 id 找预设
     * @param id 预设 id，可为 null
     * @return 认不出时为空
     */
    public static Optional<Preset> preset(String id) {
        return PRESETS.stream().filter(preset -> preset.id().equals(id)).findFirst();
    }

    public record Preset(String id, String name, ReportPalette palette) {
        public Preset {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(palette, "palette");
        }
    }

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

    Color curveGridOnBackground() {
        return withAlpha(curveGapEdge, 88);
    }

    Color curveGridOnArea() {
        return withAlpha(background, 64);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
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
