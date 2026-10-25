package org.frostnova.nova.core.analytics;

import java.math.BigDecimal;
import java.util.List;

/** 从封存归档派生的观众记录；时刻均为毫秒，金额为元。 */
public final class AudienceLedger {
    private AudienceLedger() {}

    public record Session(String platform, long streamerUid, long startTime, boolean hasUserSets,
                          boolean qualified, String listQuality) {}
    /** 保留每次上舰，月份未知时 months 为 null，原始数量与单位仍可查。 */
    public record Guard(long at, int level, int count, String unit, Long months) {}
    public record Row(String platform, long streamerUid, long startTime, long viewerUid,
                      String nickname, boolean streamer, boolean entered, boolean liked,
                      boolean spoke, Long firstDanmuAt, long danmuCount, boolean followed,
                      BigDecimal giftYuan, Integer guardLevel, Long guardMonths, List<Guard> guards) {}
    public record Visit(Row row, int previousSessions, long firstSession, Long previousSession) {}
    public record History(Long ledgerStart, List<Visit> visits) {}
}
