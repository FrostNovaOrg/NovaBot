package com.starlwr.bot.bilibili.handler;

import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 推送处理器公共逻辑
 */
@Slf4j
final class PushHandlerSupport {
    /**
     * @全体成员 的占位符
     */
    private static final String AT_ALL = "{at=all}";

    /**
     * 订阅名单的占位符
     */
    private static final String AT_SUBSCRIBERS = "{at}";

    /**
     * 分条发送的分隔符，与 {@link com.starlwr.bot.core.model.Message#create} 的切分规则一致
     */
    private static final String NEXT = "{next}";

    /**
     * 分句分隔符：中英文标点与换行
     */
    private static final String CLAUSE_DELIMITERS = "，。！？；,.!?;\n";

    private PushHandlerSupport() {
    }

    /**
     * 替换占位符；取值为空时改为移除占位符所在的整个分句
     * <p>
     * 「本场直播时长 {time}」这类修饰性片段在取值缺失时若只以空串替换，
     * 会渲染出「……，本场直播时长 」这样的悬空半句。此处以中英文标点与换行为界
     * 定位占位符所在分句，随分句一并移除其前导分隔符（分句位于句首时移除其后继分隔符）。
     * {next} 是分条边界，分句不会跨越它；移除后变为空白的分条会被整条去掉。
     * @param template 消息模板
     * @param placeholder 占位符
     * @param value 占位符取值
     * @return 处理后的消息内容
     */
    static String replaceOrDropClause(String template, String placeholder, String value) {
        if (StringUtil.isNotBlank(value)) {
            return template.replace(placeholder, value);
        }

        List<String> kept = new ArrayList<>();
        for (String part : template.split("\\{next}", -1)) {
            String cleaned = dropClause(part, placeholder);
            if (part.contains(placeholder) && StringUtil.isBlank(cleaned)) {
                continue;
            }
            kept.add(cleaned);
        }

        return String.join(NEXT, kept);
    }

    /**
     * 移除文本中包含指定占位符的分句
     * @param text 单个分条内的文本
     * @param placeholder 占位符
     * @return 处理后的文本
     */
    private static String dropClause(String text, String placeholder) {
        int index;
        while ((index = text.indexOf(placeholder)) >= 0) {
            int leadingDelimiter = -1;
            int clauseStart = 0;
            for (int i = index - 1; i >= 0; i--) {
                if (CLAUSE_DELIMITERS.indexOf(text.charAt(i)) >= 0) {
                    leadingDelimiter = i;
                    clauseStart = i + 1;
                    break;
                }
            }

            int clauseEnd = text.length();
            boolean hasTrailingDelimiter = false;
            for (int i = index + placeholder.length(); i < text.length(); i++) {
                if (CLAUSE_DELIMITERS.indexOf(text.charAt(i)) >= 0) {
                    clauseEnd = i;
                    hasTrailingDelimiter = true;
                    break;
                }
            }

            if (leadingDelimiter >= 0) {
                // 连同前导分隔符一起移除，保留后继分隔符维持与下一分句的衔接
                text = text.substring(0, leadingDelimiter) + text.substring(clauseEnd);
            } else if (hasTrailingDelimiter) {
                // 分句位于句首，改为移除后继分隔符
                text = text.substring(clauseEnd + 1);
            } else {
                // 整段文本就是这一个分句
                text = "";
            }
        }

        return text;
    }

    /**
     * 发送消息
     * @param sender 消息发送器
     * @param target 推送目标
     * @param content 消息内容
     */
    static void send(StarBotMessageSender sender, PushTarget target, String content) {
        send(sender, target, content, null);
    }

    /**
     * 发送消息，并在「图片没送到、文字送到了」时回调
     * @param onImageDegraded 图片降级回调，可为 null；<b>每一条分条各注册一次</b>，
     *                        所以「文字一条 + 封面一条」的默认模板下，
     *                        只有真正含图的那一条可能触发它
     */
    static void send(StarBotMessageSender sender, PushTarget target, String content, Runnable onImageDegraded) {
        send(sender, target, content, onImageDegraded, null);
    }

    /**
     * 发送消息，并备下 @全体成员 发不出去时的替代文本
     * @param atAllFallback @全体成员 被摘掉时用来顶替它的文本，可为空
     */
    static void send(StarBotMessageSender sender, PushTarget target, String content,
                     Runnable onImageDegraded, String atAllFallback) {
        if (StringUtil.isBlank(content)) {
            return;
        }

        List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);
        for (Message message : messages) {
            if (onImageDegraded != null) {
                message.addOnImageDegradedCallback(onImageDegraded);
            }
            // 挂在每一条上而不是只挂含占位符的那一条：谁含占位符是 {next} 切出来的结果，
            // 在这里再判一遍等于把切分规则抄第二份，而抄错的那一次没有任何现象
            if (StringUtil.isNotBlank(atAllFallback)) {
                message.setAtAllFallback(atAllFallback);
            }
        }
        messages.forEach(sender::send);
    }

    /**
     * 按 @ 模式在消息开头补上 @ 块
     * <p>
     * <b>模板里使用者自己写的占位符一律照旧生效</b>，补的这一块只在模板<b>没写</b>时才加：
     * 两处各 @ 一遍是刷屏，而默认模板正是因此才把 {@code {at}} 去掉、改由模式生成——
     * 去掉之后「@ 谁」只剩一个说法，不会出现「模板里写了一套、下拉里选了另一套」。
     * <p>
     * 仅群聊有 @全体成员，私聊里那两档不补任何东西。
     * <p>
     * <b>三档都拼在正文首行，不另起一条。</b>@全体成员 那两档原先拼的是
     * {@code {at=all}} + 分条 + 正文，于是一次开播在群里是两条消息、两声提示音；
     * 而分条这件事对 @ 本身没有任何好处——@ 与它说的那件事本就该在同一条里。
     * 摘掉 @ 的那一次（没权限或额度用尽）也随之变干净：以前是「空的那一条整条不发」，
     * 现在只是正文前面少了一截。
     * @param mode @ 模式
     * @param target 推送目标
     * @param template 原始模板，用于判断使用者是否已自己写了占位符
     * @param content 占位符已替换完毕的消息内容
     * @param subscriberAt 订阅名单拼成的 @ 串，无人订阅或本类通知没有订阅这回事时为空串
     * @return 处理后的消息内容
     */
    static String withAtBlock(AtMode mode, PushTarget target, String template, String content, String subscriberAt) {
        return switch (mode) {
            case SUBSCRIBERS -> template.contains(AT_SUBSCRIBERS) || StringUtil.isBlank(subscriberAt)
                    ? content
                    : subscriberAt + content;
            case ALL, ALL_OR_SUBSCRIBERS -> PushTargetType.GROUP != target.getType() || template.contains(AT_ALL)
                    ? content
                    : AT_ALL + content;
        };
    }

    /**
     * @全体成员 发不出去时该顶上来的文本
     * <p>
     * 只有「@全体成员，不行就 @订阅的人」这一档有替代文本。模板里已经自己写了
     * {@code {at}} 的那种情形返回空串：订阅的人在正文里已经被 @ 过一遍了，
     * 再顶一份上来是同一批人被 @ 两次。
     * @return 替代文本，没有时为空串
     */
    static String atAllFallback(AtMode mode, PushTarget target, String template, String subscriberAt) {
        boolean applicable = AtMode.ALL_OR_SUBSCRIBERS == mode
                && PushTargetType.GROUP == target.getType()
                && !template.contains(AT_SUBSCRIBERS);

        return applicable ? subscriberAt : "";
    }

    /**
     * 把订阅了「@我」的成员拼成 @ 串
     * <p>
     * 无人订阅时返回空串，模板里的 {at} 会因此消失，不会留下多余空行。
     * @param subscribers 订阅者账号
     * @return @ 串
     */
    static String atSubscribers(List<Long> subscribers) {
        if (subscribers == null || subscribers.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (Long uid : subscribers) {
            text.append("{at=").append(uid).append("}");
        }
        return text.toString();
    }

    /**
     * 获取主播的最新昵称
     * <p>
     * 事件中携带的昵称来自推送配置，可能已过时，因此优先请求接口获取最新昵称，失败时回退到事件中的值。
     * @param api 接口工具
     * @param source 主播信息
     * @return 昵称
     */
    static String resolveUname(BilibiliApiUtil api, LiveStreamerInfo source) {
        try {
            String uname = api.getUpInfoByUid(source.getUid()).getUname();
            if (StringUtil.isNotBlank(uname)) {
                return uname;
            }
        } catch (Exception e) {
            log.debug("获取 uid {} 的最新昵称失败: {}", source.getUid(), e.getMessage());
        }

        return StringUtil.isBlank(source.getUname()) ? String.valueOf(source.getUid()) : source.getUname();
    }
}
