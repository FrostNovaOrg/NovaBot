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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * OneBot HTTP 服务
 */
@Slf4j
@StarBotComponent
public class OneBotHttpService {
    private final TaskScheduler taskScheduler;

    private final ThreadPoolTaskExecutor executor;

    private final OneBotAdapterPluginProperties properties;

    private final OneBotHttpAdapter http;

    private final OneBotMessageConverter converter;

    private final OneBotConnectionState state;

    /**
     * 已注册的推送平台
     * <p>
     * 注册不再只发生在启动那一刻：从控制台配好第一台机器人时也会往里加一条，
     * 而此时定期检测与推送投递正在别的线程上读它，因此不能用普通 HashMap
     */
    private final Map<String, OneBotSender> senders = new ConcurrentHashMap<>();

    /**
     * 各推送平台的 HTTP 可用性检测任务
     * <p>
     * 记着它才停得掉。改一次连接就挂一个新的、旧的照跑，几次之后同一个平台上有好几份检测
     * 在轮流写连接状态——而它们持有的地址各不相同，界面于是在「正常」与「连不上」之间跳
     */
    private final Map<String, ScheduledFuture<?>> detectTasks = new ConcurrentHashMap<>();

    @Autowired
    public OneBotHttpService(TaskScheduler taskScheduler, @Qualifier("oneBotThreadPool") ThreadPoolTaskExecutor executor, OneBotAdapterPluginProperties properties, OneBotHttpAdapter http, OneBotMessageConverter converter, OneBotConnectionState state) {
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.properties = properties;
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
        for (OneBotSender sender : senders.values()) {
            check(sender);
        }
    }

    /**
     * 按这个推送平台<b>当前的</b>配置体检一次，并重新挂上定期检测
     * <p>
     * 启动那条路走的也是这里，理由与 {@code OneBotWebsocketService#start} 同：
     * 「开机时查一次」与「改了配置再查一次」若各写一份，两份迟早会在某个细节上分家。
     * @param sender OneBot 推送平台信息
     */
    public void check(OneBotSender sender) {
        String senderName = sender.getName();

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
        stopDetect(sender.getName());

        ScheduledFuture<?> task = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            try {
                JSONObject status = http.getStatus(sender, new JSONObject());

                // good 是「实现自身跑得好不好」，online 是「QQ 账号还在不在线」，分开记：
                // 两者的处理方式完全不同，前者重启服务，后者要重新扫码登录
                if (Boolean.TRUE.equals(status.getBoolean("good"))) {
                    state.httpOk(sender.getName(), "服务正常");
                } else {
                    state.httpFailed(sender.getName(), OneBotConnectionState.Kind.SERVICE_ABNORMAL, "实现自身状态异常");
                }

                Boolean online = status.getBoolean("online");
                if (online == null) {
                    // 并非所有实现都上报该字段，取不到时不要臆断成掉线
                    state.accountUnknown(sender.getName(), "该 OneBot 实现未上报登录状态");
                } else if (online) {
                    state.accountOnline(sender.getName(), "在线");
                } else {
                    state.accountOffline(sender.getName(), "QQ 账号已掉线");
                }
            } catch (Exception e) {
                log.warn("{} 的 OneBot HTTP 服务不可用: {}", sender.getName(), e.getMessage());
                state.httpFailed(sender.getName(), OneBotConnectionState.Kind.UNREACHABLE, e.getMessage());
                state.accountUnknown(sender.getName(), "连不上 OneBot 实现，无法判断");
            }

            // 告警不在此处发出：写进连接状态后，由健康探针与 HealthAlertMonitor 统一告警。
            // 两条路都发的话，同一次 OneBot 故障会收到两条内容雷同、标识不同的告警
        }), Instant.now().plusSeconds(detectInterval), Duration.ofSeconds(detectInterval));

        detectTasks.put(sender.getName(), task);
    }

    /**
     * 停掉某推送平台的 HTTP 可用性检测
     * @param platformName 推送平台名
     */
    private void stopDetect(String platformName) {
        ScheduledFuture<?> previous = detectTasks.remove(platformName);
        if (previous != null) {
            previous.cancel(false);
        }
    }
}
