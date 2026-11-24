package org.frostnova.nova.core.command;

/**
 * 由适配器注册为 Bean，声明群聊是否能收到没 @ 机器人的消息。
 */
public interface GroupMessageCapability {
    String platform();

    boolean receivesUnmentionedGroupMessages();
}
