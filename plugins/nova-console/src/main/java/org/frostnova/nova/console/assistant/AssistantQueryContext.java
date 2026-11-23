package org.frostnova.nova.console.assistant;

/** 调用方为本次提问确定的金额可见设置。 */
public record AssistantQueryContext(boolean amountVisible) {
    static final AssistantQueryContext CONSOLE = new AssistantQueryContext(true);
}
