package org.frostnova.nova.core.service;

import org.frostnova.nova.core.enums.PushTargetType;

import java.time.Instant;

/** 接收已送达的报告摘要。摘要只含主播和汇总数据，最多 800 字。 */
@FunctionalInterface
public interface ReportDeliveryListener {
    void reportDelivered(Report report);

    enum Kind {
        LIVE, WEEKLY
    }

    record Report(String platform, PushTargetType type, Long num, String streamerPlatform, Long uid,
                  Kind kind, Instant deliveredAt, boolean amountVisible, String summary, String summaryWithoutAmounts) {
        private static final int SUMMARY_LIMIT = 800;

        public Report(String platform, PushTargetType type, Long num, String streamerPlatform, Long uid,
                      Kind kind, Instant deliveredAt, boolean amountVisible, String summary) {
            this(platform, type, num, streamerPlatform, uid, kind, deliveredAt, amountVisible, summary, null);
        }

        public Report {
            summary = clip(summary);
            if (!amountVisible) summaryWithoutAmounts = summary;
            else if (summaryWithoutAmounts != null) summaryWithoutAmounts = clip(summaryWithoutAmounts);
        }

        private static String clip(String text) {
            if (text.codePointCount(0, text.length()) <= SUMMARY_LIMIT) return text;
            return text.substring(0, text.offsetByCodePoints(0, SUMMARY_LIMIT - 1)).stripTrailing() + "…";
        }
    }
}
