package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.model.Message.PushKind;

import java.time.Instant;
import java.util.List;

/** 外发记录快照。图片字节由记录编号和段在列表中的下标定位。 */
public record OutboxRecord(long id, long conversationId, String conversationName, Streamer streamer,
                           PushKind kind, Instant createdAt, Instant expiresAt, Status status,
                           List<Segment> segments, boolean atAll, String claimant, Instant claimedAt,
                           long claimNumber, String note) {
    public OutboxRecord {
        segments = List.copyOf(segments);
    }

    public record Streamer(String platform, Long uid, String name) {}
    public record Segment(SegmentType type, String value) {}
    public enum SegmentType { TEXT, IMAGE_BYTES, IMAGE_URL }
    public enum Status {
        PENDING("待发"), SENDING("发送中"), SENT("已发"), FAILED("没发成"), SKIPPED("跳过"),
        UNKNOWN("结果不明"), EXPIRED("过期未发"), LIMITED("超限未入箱"), VOID("已作废");

        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }
}
