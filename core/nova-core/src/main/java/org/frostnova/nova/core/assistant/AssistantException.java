package org.frostnova.nova.core.assistant;

/** 可以展示的固定提示与机器可读原因，不保留请求、响应或原始异常。 */
public final class AssistantException extends RuntimeException {
    private final String reason;

    public AssistantException(String message) { this("provider_error", message); }

    public AssistantException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
