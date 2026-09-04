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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 已经挂过推送接口的平台名
     */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

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
        for (OneBotSender sender : properties.getSenders()) {
            register(sender);
        }
    }

    /**
     * 把一个推送平台的推送接口挂上去
     * <p>
     * 从控制台配好第一台机器人时也会走这里，因此必须<b>可以重复调用</b>：
     * 已经挂过的直接返回，不再挂第二遍。挂第二遍的后果是接口路径撞号、
     * 平台名撞号（{@code addSender} 直接抛），以及<b>推送接口 Token 换了一把</b>——
     * 未配置时那把 Token 是随机生成的，重新生成一次就等于把已经拿到旧 Token 的调用方全部踢掉。
     * <p>
     * 幂等靠平台名（{@link OneBotSender#getName()}）做键。运行期改平台名这条路不通：
     * 已挂过的仍按旧名认「已经有了」，新名不会另挂一份；界面上也没有改平台名的入口。
     * @param sender OneBot 推送平台信息
     * @return 挂上了或本来就挂着时为 true；缺 HTTP Token 而挂不上时为 false
     */
    public synchronized boolean register(OneBotSender sender) {
        if (StringUtil.isBlank(sender.getOneBotHttpToken())) {
            log.error("推送平台 {} 未配置 OneBot HTTP Token, 请完善配置", sender.getName());
            return false;
        }

        // 连接信息是就地改在同一个 OneBotSender 上的，因此已注册的平台换了地址也不必重挂：
        // 接口路径、推送 Token 与平台名都没变，变的只是它连向哪里
        if (!registered.add(sender.getName())) {
            return true;
        }

        Method method;
        try {
            method = getClass().getMethod("send", MessageDTO.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("注册推送 API 异常", e);
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
        return true;
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
