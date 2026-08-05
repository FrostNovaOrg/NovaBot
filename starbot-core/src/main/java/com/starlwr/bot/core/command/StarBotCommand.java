package com.starlwr.bot.core.command;

import java.util.List;

/**
 * 聊天命令
 * <p>
 * 实现本接口并注册为 Bean（插件中用 {@code @StarBotComponent}）即可被命令分发器发现，
 * 无需改动核心的任何注册表。
 * <p>
 * <b>命令只应在自己确实该管的会话里响应。</b>群聊机器人往往同时在多个群，
 * 在无关群里回话是最容易招致反感的行为，因此分发器默认只在**已配置推送的会话**中
 * 处理命令；确有需要的命令可通过 {@link #requiresConfiguredTarget()} 放宽。
 */
public interface StarBotCommand {
    /**
     * 命令名，即触发词
     * @return 命令名
     */
    String name();

    /**
     * 命令别名
     * @return 别名列表，默认无
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 命令说明，用于「菜单」
     * @return 说明
     */
    String description();

    /**
     * 参数用法说明，用于「菜单」，无参数时为空
     * @return 用法说明
     */
    default String usage() {
        return "";
    }

    /**
     * 命令归属的分类，用于「菜单」分组
     * <p>
     * 命令一多，一长串平铺的清单就没人看得下去。分类名自由填写，
     * 「菜单」按各分类首次出现的顺序展示。
     * @return 分类名
     */
    default String category() {
        return "其他";
    }

    /**
     * 是否只在群聊中可用
     * @return 是否仅限群聊
     */
    default boolean groupOnly() {
        return true;
    }

    /**
     * 是否可被「禁用命令」关闭
     * <p>
     * 「菜单」与「启用命令」自身不可禁用：否则一旦关掉就再也没有入口把它开回来。
     * @return 是否可禁用
     */
    default boolean disableable() {
        return true;
    }

    /**
     * 是否仅管理员可用
     * <p>
     * 用于会**影响他人**的命令：「禁用命令」改变的是全群的可用功能，
     * 放任何人执行等于把机器人的开关交给了路过的人。
     * 只影响自己的命令（如「@我」「绑定」「我的数据」）不该设为 true。
     * @return 是否仅管理员可用，默认否
     */
    default boolean requiresAdmin() {
        return false;
    }

    /**
     * 是否要求会话已配置推送
     * <p>
     * 默认为 true：机器人常同时在多个群，在没配置过推送的群里应答等同于打扰。
     * @return 是否要求
     */
    default boolean requiresConfiguredTarget() {
        return true;
    }

    /**
     * 执行命令
     * @param context 执行上下文
     * @return 回复内容，为空则不回复
     */
    CommandReply execute(CommandContext context);
}
