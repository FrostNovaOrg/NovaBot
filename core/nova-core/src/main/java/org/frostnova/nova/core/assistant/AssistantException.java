package org.frostnova.nova.core.assistant;

/** 可以展示的固定提示、机器可读原因和模型服务错误字段。 */
public final class AssistantException extends RuntimeException {
    public record ProviderError(String type, String code, String param) { }

    private final String reason;
    private final ProviderError providerError;

    public AssistantException(String message) { this("provider_error", message); }

    public AssistantException(String reason, String message) {
        this(reason, message, null);
    }

    AssistantException(String reason, String message, ProviderError providerError) {
        super(message);
        this.reason = reason;
        this.providerError = providerError;
    }

    public String reason() { return reason; }

    public ProviderError providerError() { return providerError; }
}
