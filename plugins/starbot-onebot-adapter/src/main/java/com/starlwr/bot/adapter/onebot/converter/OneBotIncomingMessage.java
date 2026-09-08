package com.starlwr.bot.adapter.onebot.converter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 从 OneBot 收到的一条聊天消息
 * <p>
 * 只回答两个问题：<b>这条消息 @ 了机器人吗</b>、<b>去掉那个 @ 之后正文是什么</b>。
 * 群聊里「这话是不是说给机器人听的」只有这一层看得出来——再往上传，
 * 事件里只剩一串文本，@ 了谁已经无从判断。
 *
 * <h2>为什么两种形态都读</h2>
 * OneBot 实现上报消息有两种格式：{@code message} 是分段数组，或者是一整串 CQ 码。
 * 两种都真实存在（取决于实现方的 post 格式配置），只认一种的话，
 * 换一个实现或者改一次配置，群里的命令就会整体失灵，而日志里什么都不会出现。
 * 因此 @ 的判定<b>两处都看，任一处认出即算</b>；正文则一律从 {@code raw_message} 上剪，
 * 那一串里连图片、表情都还在，命令的参数不会因为分段解析而丢。
 */
public record OneBotIncomingMessage(boolean mentionsBot, String text) {
    /**
     * CQ 码的起始标记
     */
    private static final String CQ_START = "[CQ:";

    /**
     * 解析一条消息事件
     * @param event OneBot 上报的消息事件
     * @return 解析结果
     */
    public static OneBotIncomingMessage of(JSONObject event) {
        String raw = event.getString("raw_message");
        raw = raw == null ? "" : raw;

        Long selfId = event.getLong("self_id");
        if (selfId == null) {
            // 不知道自己是谁就判不出「@ 的是不是我」。此时宁可当作没被 @：
            // 反过来把任何 @ 都当成 @ 自己，机器人会在别人互相 @ 的时候插嘴
            return new OneBotIncomingMessage(false, raw.trim());
        }

        String self = String.valueOf(selfId);
        boolean mentioned = mentionedInSegments(event.get("message"), self);

        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = raw.indexOf(CQ_START, cursor);
            if (start < 0) {
                rest.append(raw, cursor, raw.length());
                break;
            }
            int end = raw.indexOf(']', start);
            if (end < 0) {
                // 没有收尾的方括号，剩下的按普通文本处理
                rest.append(raw, cursor, raw.length());
                break;
            }

            rest.append(raw, cursor, start);
            String body = raw.substring(start + CQ_START.length(), end);
            if (isAtSelf(body, self)) {
                mentioned = true;
            } else {
                rest.append(raw, start, end + 1);
            }
            cursor = end + 1;
        }

        return new OneBotIncomingMessage(mentioned, rest.toString().trim());
    }

    /**
     * 分段数组里有没有 @ 机器人的那一段
     * @param message {@code message} 字段，可能是数组、字符串或缺失
     * @param self 机器人自己的账号
     * @return 是否 @ 了机器人
     */
    private static boolean mentionedInSegments(Object message, String self) {
        if (!(message instanceof JSONArray segments)) {
            return false;
        }

        for (Object element : segments) {
            if (!(element instanceof JSONObject segment) || !"at".equals(segment.getString("type"))) {
                continue;
            }
            JSONObject data = segment.getJSONObject("data");
            // qq 取值可能是 all（@全体成员），那不是在叫机器人
            if (data != null && self.equals(data.getString("qq"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一段 CQ 码是不是「@ 机器人」
     * @param body CQ 码去掉首尾标记后的内容，形如 {@code at,qq=123456}
     * @param self 机器人自己的账号
     * @return 是否为 @ 机器人
     */
    private static boolean isAtSelf(String body, String self) {
        String[] parts = body.split(",");
        if (parts.length == 0 || !"at".equals(parts[0])) {
            return false;
        }

        for (int i = 1; i < parts.length; i++) {
            // 除 qq 外还可能带 name 等参数，逐项找而不是认第一项
            if (parts[i].startsWith("qq=") && self.equals(parts[i].substring(3))) {
                return true;
            }
        }
        return false;
    }
}
