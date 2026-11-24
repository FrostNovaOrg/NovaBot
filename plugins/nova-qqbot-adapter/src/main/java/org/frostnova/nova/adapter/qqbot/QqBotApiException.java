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

    /** 已知会同时阻止图文和纯文字的故障。其余错误保留去图补发的机会。 */
    boolean preventsTextDelivery() {
        // 限流等待结束、本地凭据错误或建连失败都可以在没有 HTTP 回包时确定失败。
        if (httpStatus == 0) return !deliveryUnknown;
        if (httpStatus == 401 || messageReplyRejected()) return true;
        return switch (code) {
            case 40034105, 40054002, 850018, 40034101, 40054003, 40054016 -> true;
            case 40054007, 40054010 -> true;
            case 11241, 11243, 100016, 100007, 10004, 100001 -> true;
            default -> false;
        };
    }
}
