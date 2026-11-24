package org.frostnova.nova.adapter.qqbot;

/** QQ 开放平台调用失败时可供调用方展示的诊断信息。 */
public final class QqBotApiException extends RuntimeException {
    private final int code;
    private final String traceId;
    private final int httpStatus;

    QqBotApiException(int code, String message, String traceId, int httpStatus) {
        super(message);
        this.code = code;
        this.traceId = traceId;
        this.httpStatus = httpStatus;
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
}
