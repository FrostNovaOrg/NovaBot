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
                  Kind kind, Instant deliveredAt, boolean amountVisible, String summary) {
        private static final int SUMMARY_LIMIT = 800;

        public Report {
            if (summary.codePointCount(0, summary.length()) > SUMMARY_LIMIT) {
                summary = summary.substring(0, summary.offsetByCodePoints(0, SUMMARY_LIMIT - 1)).stripTrailing() + "…";
            }
        }
    }
}
