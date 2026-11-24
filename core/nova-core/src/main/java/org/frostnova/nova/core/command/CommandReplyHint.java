package org.frostnova.nova.core.command;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommandReplyHint {
    public static final CommandReplyHint DEFAULT = new CommandReplyHint(List.of());

    private final List<GroupMessageCapability> capabilities;

    public CommandReplyHint(List<GroupMessageCapability> capabilities) {
        this.capabilities = List.copyOf(capabilities);
    }

    public String replyVerb(CommandContext context) {
        return replyVerb(context, "");
    }

    /** 「请」等前缀直接接「回」，遇到 @ 则用空格隔开。 */
    public String replyVerb(CommandContext context, String prefix) {
        if (context.isGroup() && capabilities.stream().anyMatch(capability ->
                capability.platform().equals(context.getPlatform())
                        && !capability.receivesUnmentionedGroupMessages())) {
            return prefix.isEmpty() ? "@我 回" : prefix + " @我 回";
        }
        return prefix + "回";
    }
}
