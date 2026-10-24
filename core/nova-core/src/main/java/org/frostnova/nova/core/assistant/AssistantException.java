package org.frostnova.nova.core.assistant;

/** 可以直接展示的固定提示，不保留请求、响应或原始异常。 */
public final class AssistantException extends RuntimeException {
    public AssistantException(String message) { super(message); }
}
