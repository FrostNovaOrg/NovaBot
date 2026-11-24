package org.frostnova.nova.adapter.qqbot;

/** QQ 开放平台调用失败时可供调用方展示的诊断信息。 */
public final class QqBotApiException extends RuntimeException {
    private final int code;
    private final String traceId;
    private final int httpStatus;
    private final boolean deliveryUnknown;

    QqBotApiException(int code, String message, String traceId, int httpStatus) {
        this(code, message, traceId, httpStatus, false);
    }

    QqBotApiException(int code, String message, String traceId, int httpStatus, boolean deliveryUnknown) {
        super(message);
        this.code = code;
        this.traceId = traceId;
        this.httpStatus = httpStatus;
        this.deliveryUnknown = deliveryUnknown;
    }

    public int code() {
        return code;
    }

    public String traceId() {
        return traceId;
    }

    /** 没有收到 HTTP 回包时为 0。 */
    public int httpStatus() {
        return httpStatus;
    }

    boolean duplicateMessage() {
        return code == 40054005;
    }

    boolean eventReplyRejected() {
        return code == 40034025 || code == 40034026 || code == 40034027;
    }

    boolean messageReplyRejected() {
        return switch (code) {
            case 40034128, 304103, 40034005, 40034024 -> true;
            default -> false;
        };
    }

    boolean deliveryUnknown() {
        return deliveryUnknown;
    }
}
