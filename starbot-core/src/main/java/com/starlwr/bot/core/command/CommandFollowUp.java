package com.starlwr.bot.core.command;

import java.util.List;

/**
 * 追问的应答
 * <p>
 * 有些命令答不上来时会反问一句，例如「本群配了好几位主播，回个序号告诉我是哪一位」。
 * 使用者接下来发的那个序号<b>本身不是命令</b>，分发器认不出它，按现有规矩会回一份菜单——
 * 机器人问了一句，然后当没问过。本接口把这类应答翻译回一条命令，再交给分发器照常执行。
 * <p>
 * 实现只需注册为 Bean（插件中用 {@code @StarBotComponent}）即可被发现，核心不维护注册表；
 * 「追问了什么、序号对应谁」这些知识全部留在提问的那一方，核心只负责把话递过去。
 * <p>
 * <b>被认领的应答不受同会话冷却限制。</b> 冷却防的是「连发无效消息刷屏」，
 * 而这是机器人自己刚问出来的问题的答案：用冷却把它挡在门外等于问了又不听，
 * 而使用者看见的现象是「回了个数字，没反应」。刷屏的路仍然堵着——
 * 一次追问只认领一次（认领即消费），而发起追问的那条命令本身照样吃冷却。
 */
public interface CommandFollowUp {
    /**
     * 认领一条认不出的消息
     * <p>
     * 只在消息不是任何已知命令时才会被问到。<b>认领即消费</b>：同一次追问不该被认领两次，
     * 否则一个序号能一直重放。
     * @param context 执行上下文，命令名的位置放的是消息的第一个词
     * @return 认领结果；不认这条消息时返回 null
     */
    Claimed claim(CommandContext context);

    /**
     * 认领的结果：要么直接回一句，要么改写成一条命令交回分发器执行
     * <p>
     * 走「改写成命令」而不是自己执行，是为了让改写出来的这一条<b>照样过分发器的关</b>：
     * 被本群关掉的命令、只有管理员能用的命令，不该因为换了个入口就放行。
     *
     * @param reply 直接回复；为 null 表示改跑一条命令
     * @param command 要执行的命令名；为 null 表示只回复
     * @param args 该命令的参数
     */
    record Claimed(CommandReply reply, String command, List<String> args) {
        /**
         * 直接回一句，不执行任何命令
         * @param reply 回复
         * @return 认领结果
         */
        public static Claimed answer(CommandReply reply) {
            return new Claimed(reply, null, List.of());
        }

        /**
         * 改跑一条命令
         * @param command 命令名
         * @param args 参数
         * @return 认领结果
         */
        public static Claimed rerun(String command, List<String> args) {
            return new Claimed(null, command, List.copyOf(args));
        }
    }
}
