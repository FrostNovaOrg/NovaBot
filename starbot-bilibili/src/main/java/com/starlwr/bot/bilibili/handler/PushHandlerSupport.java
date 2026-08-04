package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
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
        if (StringUtil.isBlank(content)) {
            return;
        }

        List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);
        messages.forEach(sender::send);
    }

    /**
     * 按配置在消息前追加 @全体成员
     * <p>
     * 仅群聊支持 @全体成员，且消息中已包含该占位符时不重复添加。
     * @param params 推送参数
     * @param target 推送目标
     * @param content 消息内容
     * @return 处理后的消息内容
     */
    static String withAtAll(JSONObject params, PushTarget target, String content) {
        boolean atAll = params.getBooleanValue("at_all")
                && PushTargetType.GROUP == target.getType()
                && !content.contains(AT_ALL);

        return atAll ? AT_ALL + "{next}" + content : content;
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
