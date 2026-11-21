package org.frostnova.nova.console.assistant;

/** 调用方为本次提问确定的数据可见范围。 */
public record AssistantQueryContext(boolean amountVisible, boolean audienceQuotesVisible) {
    static final AssistantQueryContext CONSOLE = new AssistantQueryContext(true);

    public AssistantQueryContext(boolean amountVisible) {
        this(amountVisible, true);
    }
}
