package com.starlwr.bot.adapter.onebot.extension.napcat.util;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Napcat 服务容器
 */
@StarBotComponent
public class NapcatServiceHolder {
    private final Map<String, OneBotSender> senders = new HashMap<>();

    /**
     * 注册 Napcat 推送平台
     * @param sender OneBot 推送平台信息
     */
    public void registerNapcat(OneBotSender sender) {
        senders.put(sender.getName(), sender);
    }

    /**
     * 判断推送平台是否为 Napcat 服务
     * @param senderName 推送平台名称
     * @return 是否为 Napcat 服务
     */
    public boolean isNapcat(String senderName) {
        return senders.containsKey(senderName);
    }

    /**
     * 获取 OneBot 推送平台信息
     * @param senderName 推送平台名称
     * @return OneBot 推送平台信息
     */
    public OneBotSender getNapcat(String senderName) {
        if (!isNapcat(senderName)) {
            throw new NoSuchElementException(senderName + " 不是一个 Napcat 服务");
        }

        return senders.get(senderName);
    }
}
