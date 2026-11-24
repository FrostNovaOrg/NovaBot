package org.frostnova.nova.core.command;

import org.frostnova.nova.core.lang.StringUtil;

import java.util.List;

/**
 * 追问的应答
 * <p>
 * 有些命令答不上来时会反问一句，例如「本群配了好几位主播，回个序号告诉我是哪一位」。
 * 使用者接下来发的那个序号<b>本身不是命令</b>，分发器认不出它。没人认领时它怎么走，
 * 看那一句怎么发的：@ 过的（或私聊里的）交给 {@link CommandFallback} 或菜单，
 * 群里没 @ 的那一个词照旧一声不出。本接口把这类应答翻译回一条命令，
 * 再交给分发器照常执行。
 * <p>
 * 实现只需注册为 Bean（插件中用 {@code @NovaComponent}）即可被发现，核心不维护注册表；
 * 「追问了什么、序号对应谁」由提问方保存。通过 {@link CommandContext#openChoiceList} 发出新清单时，
 * 分发器会让同一会话中同一个人的旧清单作废。
 * <p>
 * <b>被认领的应答不受同会话冷却限制。</b> 冷却防的是「连发无效消息刷屏」，
 * 而这是机器人自己刚问出来的问题的答案：用冷却把它挡在门外等于问了又不听，
 * 而使用者看见的现象是「回了个数字，没反应」。刷屏的路仍然堵着——
 * 同一个答案只认领一次，重复回答可以提示已选，但不再执行原命令。发起追问的命令照样吃冷却。
 */
public interface CommandFollowUp {
    /**
     * 认领一条认不出的消息
     * <p>
     * 只在消息不是任何已知命令时才会被问到；群里没 @ 时还窄一道，整条只有一个词才问到这里。
     * 同一个答案只执行一次，能否接着选别的答案由追问方决定。
     * @param context 执行上下文，命令名的位置放的是消息的第一个词
     * @return 认领结果；不认这条消息时返回 null
     */
    Claimed claim(CommandContext context);

    /**
     * 有消息未经过 {@link #claim} 时通知追问方，包括已知命令和不参与认领的群消息。
     * @param context 原消息的上下文，认领后重跑的命令不会触发本方法
     */
    default void onNonFollowUpMessage(CommandContext context) {
    }

    /** 同一会话中同一个人收到新清单时，清掉此人原有的待选清单。 */
    default void discardChoiceList(CommandContext context) {
    }

    /**
     * 读取待选清单的序号。只接受纯数字，最多三位可作序号。
     * @return 正数为清单序号，0 表示零或超出位数，-1 表示输入不是纯数字
     */
    static int parseChoiceNumber(String word) {
        if (StringUtil.isEmpty(word) || !word.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return word.length() <= 3 ? Integer.parseInt(word) : 0;
    }

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

        public static Claimed outOfRange(String number, int size) {
            String displayedNumber = StringUtil.getOmitString(number, 16);
            return answer(CommandReply.of("清单里没有 " + displayedNumber + " 号，请回 1 到 " + size));
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
