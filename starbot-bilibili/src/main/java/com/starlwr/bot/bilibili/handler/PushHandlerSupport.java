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

    private PushHandlerSupport() {
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
