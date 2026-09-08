package com.starlwr.bot.adapter.onebot.extension.napcat.util;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 哪几个推送平台的对面是 NapCat
 * <p>
 * 这份名单<b>不是配出来的，是运行期问出来的</b>：连上、问过版本、认出对面自称 NapCat，
 * 才会有这一条（见 {@code NapCatServiceDiscoveryAspect}）。因此
 * 「查不到」是常态而不是故障——一台连着别家 OneBot 实现的机器上这份名单本就该是空的，
 * 本扩展的功能对它安静让路即可。
 * <p>
 * 按平台<b>名称</b>存而不是按地址：同一个 NapCat 可以被配成两个推送平台，
 * 而消息上带着的正是平台名称。
 */
@StarBotComponent
public class NapcatServiceHolder {
    private final Map<String, OneBotSender> senders = new HashMap<>();

    /**
     * 记下一个已认出的 NapCat 推送平台
     * <p>
     * 同名覆盖：重连之后拿到的是新的平台信息对象，留着旧的会让后续请求打到改配置之前的地址上
     * @param sender OneBot 推送平台信息
     */
    public void registerNapcat(OneBotSender sender) {
        senders.put(sender.getName(), sender);
    }

    /**
     * 这个推送平台的对面是不是 NapCat
     * @param senderName 推送平台名称
     * @return 是否为 Napcat 服务
     */
    public boolean isNapcat(String senderName) {
        return senders.containsKey(senderName);
    }

    /**
     * 取出这个推送平台的信息
     * <p>
     * 只在 {@link #isNapcat} 已经答是之后调用；查不到就抛，是为了不让调用方拿着一个
     * {@code null} 继续往下走——那会让「名单里没有它」这件事在离原因很远的地方才现形
     * @param senderName 推送平台名称
     * @return OneBot 推送平台信息
     */
    public OneBotSender getNapcat(String senderName) {
        OneBotSender sender = senders.get(senderName);
        if (sender == null) {
            throw new NoSuchElementException(senderName + " 不是一个 Napcat 服务");
        }

        return sender;
    }
}
