package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.converter.OneBotMessageConverter;
import com.starlwr.bot.adapter.onebot.enums.ResultCode;
import com.starlwr.bot.adapter.onebot.exception.OneBotApiException;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.dto.MessageDTO;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * OneBot HTTP 服务
 */
@Slf4j
@StarBotComponent
public class OneBotHttpService {
    private final TaskScheduler taskScheduler;

    private final ThreadPoolTaskExecutor executor;

    private final OneBotAdapterPluginProperties properties;

    private final AlertService alertService;

    private final OneBotHttpAdapter http;

    private final OneBotMessageConverter converter;

    private final OneBotConnectionState state;

    private final Map<String, OneBotSender> senders = new HashMap<>();

    @Autowired
    public OneBotHttpService(TaskScheduler taskScheduler, @Qualifier("oneBotThreadPool") ThreadPoolTaskExecutor executor, OneBotAdapterPluginProperties properties, AlertService alertService, OneBotHttpAdapter http, OneBotMessageConverter converter, OneBotConnectionState state) {
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.properties = properties;
        this.alertService = alertService;
        this.http = http;
        this.converter = converter;
        this.state = state;
    }

    /**
     * 获取已注册的推送平台
     * @param name 推送平台名
     * @return 推送平台信息，不存在时返回 null
     */
    public OneBotSender getSender(String name) {
        return senders.get(name);
    }

    /**
     * OneBot HTTP 可用性检查
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        for (String senderName : senders.keySet()) {
            OneBotSender sender = senders.get(senderName);

            log.info("开始检测 {} 的 OneBot HTTP 服务可用性", senderName);
            log.info("{} 的 OneBot HTTP 连接地址: http://{}:{}", senderName, sender.getOneBotAddress(), sender.getOneBotHttpPort());
            try {
                JSONObject versionInfo = http.getVersionInfo(sender, new JSONObject());
                log.info("{} 的 OneBot HTTP 连接正常, 版本 v{}", senderName, versionInfo.getString("app_version"));
                JSONObject loginInfo = http.getLoginInfo(sender, new JSONObject());
                log.info("{} 当前登录账号: {}({})", senderName, loginInfo.getString("nickname"), loginInfo.getLong("user_id"));

                state.httpOk(senderName, "v" + versionInfo.getString("app_version")
                        + "，登录账号 " + loginInfo.getString("nickname") + "(" + loginInfo.getLong("user_id") + ")");

                if (properties.getDetect().isEnableHttpDetect()) {
                    startDetect(sender);
                }
            } catch (HttpClientErrorException.Forbidden e) {
                log.error("{} 的 OneBot HTTP Token 配置不正确, 将无法推送消息, 请检查 Token 配置", senderName, e);
                state.httpFailed(senderName, OneBotConnectionState.Kind.TOKEN_INVALID, "Token 不正确");
            } catch (Exception e) {
                log.error("{} 的 OneBot HTTP 服务不可用, 请检查配置和服务状态", senderName, e);
                state.httpFailed(senderName, OneBotConnectionState.Kind.UNREACHABLE, e.getMessage());
            }
        }
    }

    /**
     * 注册 OneBot HTTP 推送平台
     * @param sender OneBot HTTP 推送平台
     */
    public void register(OneBotSender sender) {
        senders.put(sender.getName(), sender);
    }

    /**
     * 发送消息到 OneBot
     *
     * @param message 消息
     */
    public JSONObject send(MessageDTO message) {
        OneBotSender sender = senders.get(message.getPlatform());

        try {
            JSONObject params = new JSONObject();

            JSONArray elements = converter.convert(message.getContent());
            if (elements.isEmpty()) {
                return new JSONObject().fluentPut("code", ResultCode.EMPTY_MESSAGE.getCode()).fluentPut("message", ResultCode.EMPTY_MESSAGE.getMsg()).fluentPut("id", null);
            }

            params.put("message", elements);

            JSONObject result;
            if (message.getType() == PushTargetType.FRIEND) {
                params.put("user_id", String.valueOf(message.getNum()));
                result = http.sendPrivateMsg(sender, params);
            } else if (message.getType() == PushTargetType.GROUP) {
                params.put("group_id", String.valueOf(message.getNum()));
                result = http.sendGroupMsg(sender, params);
            } else {
                return new JSONObject().fluentPut("code", ResultCode.UNKNOWN_TARGET_TYPE.getCode()).fluentPut("message", ResultCode.UNKNOWN_TARGET_TYPE.getMsg()).fluentPut("id", null);
            }

            return new JSONObject().fluentPut("code", ResultCode.SUCCESS.getCode()).fluentPut("message", ResultCode.SUCCESS.getMsg()).fluentPut("id", result.getString("message_id"));
        } catch (OneBotApiException e) {
            return new JSONObject().fluentPut("code", ResultCode.API_ERROR.getCode()).fluentPut("message", ResultCode.API_ERROR.getMsg() + ": " + e.getMsg()).fluentPut("id", null);
        } catch (Exception e) {
            log.error("OneBot HTTP 发送消息异常", e);
            return new JSONObject().fluentPut("code", ResultCode.UNKNOWN.getCode()).fluentPut("message", "OneBot HTTP 发送消息异常, 请检查插件日志错误信息").fluentPut("id", null);
        }
    }

    /**
     * HTTP 服务可用性检测
     * @param sender OneBot 推送平台信息
     */
    private void startDetect(OneBotSender sender) {
        int detectInterval = properties.getDetect().getHttpDetectInterval();

        taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            String alarm = "";
            try {
                JSONObject status = http.getStatus(sender, new JSONObject());
                if (!Boolean.TRUE.equals(status.getBoolean("good"))) {
                    alarm = sender.getName() + " 的 OneBot 服务状态异常, 请检查服务状态";
                }

                if (!Boolean.TRUE.equals(status.getBoolean("online"))) {
                    alarm = sender.getName() + " 的 OneBot 服务登录状态异常, 请检查账号是否已掉线";
                }

                if (StringUtil.isBlank(alarm)) {
                    state.httpOk(sender.getName(), "服务正常");
                } else {
                    state.httpFailed(sender.getName(), OneBotConnectionState.Kind.SERVICE_ABNORMAL, alarm);
                }
            } catch (Exception e) {
                alarm = sender.getName() + " 的 OneBot HTTP 服务不可用, 请检查服务状态";
                state.httpFailed(sender.getName(), OneBotConnectionState.Kind.UNREACHABLE, e.getMessage());
            }

            if (StringUtil.isBlank(alarm)) {
                alertService.resolve("onebot-http:" + sender.getName());
                return;
            }

            log.warn(alarm);
            // 收敛交由告警服务统一处理，此处不再各自维护一套间隔逻辑
            alertService.alert("onebot-http:" + sender.getName(), "StarBot OneBot 服务异常告警", alarm);
        }), Instant.now().plusSeconds(detectInterval), Duration.ofSeconds(detectInterval));
    }
}
