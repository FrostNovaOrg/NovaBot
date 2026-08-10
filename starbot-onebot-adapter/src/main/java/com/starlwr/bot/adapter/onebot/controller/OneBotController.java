package com.starlwr.bot.adapter.onebot.controller;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.dto.MessageDTO;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.adapter.onebot.security.PushApiTokenStore;
import com.starlwr.bot.adapter.onebot.service.OneBotHttpService;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * OneBot 控制器
 */
@Slf4j
@RestController
@StarBotComponent
public class OneBotController {
    private final WebServerApplicationContext webContext;

    private final RequestMappingHandlerMapping mapping;

    private final OneBotAdapterPluginProperties properties;

    private final StarBotSenderService senderService;

    private final OneBotHttpService httpService;

    private final PushApiTokenStore tokenStore;

    @Autowired
    public OneBotController(WebServerApplicationContext webContext, RequestMappingHandlerMapping mapping, OneBotAdapterPluginProperties properties, StarBotSenderService senderService, OneBotHttpService httpService, PushApiTokenStore tokenStore) {
        this.webContext = webContext;
        this.mapping = mapping;
        this.properties = properties;
        this.senderService = senderService;
        this.httpService = httpService;
        this.tokenStore = tokenStore;
    }

    /**
     * 注册 OneBot 推送平台接口
     */
    @Order(-20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Method method;
        try {
            method = getClass().getMethod("send", MessageDTO.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("注册推送 API 异常", e);
        }

        for (OneBotSender sender : properties.getSenders()) {
            if (StringUtil.isBlank(sender.getOneBotHttpToken())) {
                log.error("推送平台 {} 未配置 OneBot HTTP Token, 请完善配置", sender.getName());
                continue;
            }

            String path = properties.getBaseUrl() + sender.getApi();
            String apiToken = resolveApiToken(sender);
            tokenStore.register(path, apiToken);

            try {
                RequestMappingInfo info = RequestMappingInfo
                        .paths(path)
                        .methods(RequestMethod.POST)
                        .build();
                mapping.registerMapping(info, this, method);
            } catch (Exception e) {
                log.error("推送平台 {} 注册异常", sender.getName(), e);
            }

            // url 仍然记着：它是对外推送接口的地址，配置界面与文档都要用。
            // 但核心自己投递不再走它，改为下面的进程内直调，理由见 Sender.LocalDelivery
            String url = "http://127.0.0.1:" + webContext.getWebServer().getPort() + path;
            senderService.addSender(new Sender(sender.getName(), url, apiToken, sender.getDelay(),
                    (headers, params) -> send(toMessage(params))));

            httpService.register(sender);
        }
    }

    /**
     * 解析推送接口 Token，未配置时自动生成
     * @param sender 推送平台配置
     * @return 实际生效的 Token
     */
    private String resolveApiToken(OneBotSender sender) {
        if (StringUtil.isBlank(sender.getApiToken())) {
            String generated = PushApiTokenStore.generate();
            log.info("推送平台 {} 未配置推送接口 Token, 已自动生成随机 Token (指纹 {}), 仅本次运行有效; 如需外部程序调用推送接口, 请在 application.yml 中显式配置 api-token",
                    sender.getName(), PushApiTokenStore.fingerprint(generated));
            return generated;
        }

        String configured = sender.getApiToken().strip();
        if (PushApiTokenStore.isWeak(configured)) {
            String message = String.format("推送平台 %s 配置的推送接口 Token 强度过低, 请改用长度不低于 %d 位的随机字符串",
                    sender.getName(), PushApiTokenStore.MIN_TOKEN_LENGTH);

            if (properties.getSecurity().isFailOnWeakConfig()) {
                throw new IllegalStateException(message);
            }

            log.error("{} (可将 starbot.adapter.onebot.security.fail-on-weak-config 设为 true 以在弱配置时直接终止启动)", message);
        }

        return configured;
    }

    /**
     * 发送消息到 OneBot
     * @param message 消息
     * @return 调用结果
     */
    public JSONObject send(@RequestBody MessageDTO message) {
        return httpService.send(message);
    }

    /**
     * 把核心传来的参数装成 {@link MessageDTO}
     * <p>
     * <b>逐字段显式转换，不借道任何序列化器。</b>走 HTTP 时这一步由 Jackson 完成，
     * 而本项目内部到处用的是 fastjson——两者对同一个 {@code MessageDTO} 的理解并不一致：
     * {@code createTime} 上挂的是 Jackson 的 {@code @JsonProperty("create_time")}，
     * fastjson 不认它，用 fastjson 转会<b>悄悄丢掉时间戳</b>而不报任何错。
     * 与其押注两个库行为一致，不如把六个字段写明白。
     * @param params 与 HTTP 请求体字段完全一致的参数
     * @return 消息
     */
    static MessageDTO toMessage(Map<String, Object> params) {
        MessageDTO message = new MessageDTO();
        message.setPlatform(str(params.get("platform")));
        message.setContent(str(params.get("content")));
        message.setNum(number(params.get("num")));
        message.setSequence(number(params.get("sequence")));
        message.setCreateTime(number(params.get("create_time")));

        Long type = number(params.get("type"));
        // 用 of(code) 而不是按序号取：核心传过来的是 PushTargetType 的 code。
        // 二者在 FRIEND(0) 与 GROUP(1) 上恰好相同，但 UNKNOWN 的 code 是 -1、序号是 2，
        // 只有 of(code) 是照语义来的
        message.setType(type == null ? PushTargetType.UNKNOWN : PushTargetType.of(type.intValue()));

        return message;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
