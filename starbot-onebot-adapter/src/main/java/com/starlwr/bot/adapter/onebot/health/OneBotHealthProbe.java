package com.starlwr.bot.adapter.onebot.health;

import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OneBot 连接健康探针
 * <p>
 * 只读取 {@link OneBotConnectionState} 中缓存的结果，不发起网络请求：实际探测由启动检查与
 * 定时检测完成。
 */
@StarBotComponent
public class OneBotHealthProbe implements HealthProbe {
    private final OneBotConnectionState state;

    @Autowired
    public OneBotHealthProbe(OneBotConnectionState state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "机器人连接";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Scope scope() {
        return Scope.BOT;
    }

    @Override
    public HealthStatus check() {
        Map<String, OneBotConnectionState.Entry> all = state.all();
        if (all.isEmpty()) {
            return HealthStatus.down("未配置任何机器人",
                    "请在配置文件中填写 starbot.adapter.onebot.senders，至少配置一个 OneBot 连接");
        }

        List<String> summaries = new ArrayList<>();
        List<String> advices = new ArrayList<>();
        HealthStatus.Level worst = HealthStatus.Level.OK;

        for (Map.Entry<String, OneBotConnectionState.Entry> item : all.entrySet()) {
            String sender = item.getKey();
            OneBotConnectionState.Status http = item.getValue().getHttp();
            OneBotConnectionState.Status websocket = item.getValue().getWebsocket();

            summaries.add(sender + "：HTTP " + brief(http) + " / WS " + brief(websocket));

            // HTTP 不通即无法推送消息，属于致命；Websocket 只用于接收事件，断开仅影响插件功能
            if (http.kind() != OneBotConnectionState.Kind.OK) {
                worst = HealthStatus.Level.DOWN;
                advices.add(sender + " " + advise(sender, http));
            } else if (websocket.kind() != OneBotConnectionState.Kind.OK
                    && websocket.kind() != OneBotConnectionState.Kind.DISABLED
                    && worst == HealthStatus.Level.OK) {
                worst = HealthStatus.Level.DEGRADED;
                advices.add(sender + " 的 Websocket " + websocket.detail() + "，消息仍可推送，但收不到群内事件");
            }
        }

        return new HealthStatus(worst, String.join("；", summaries), String.join("；", advices));
    }

    /**
     * 状态的简短描述
     */
    private String brief(OneBotConnectionState.Status status) {
        return switch (status.kind()) {
            case OK -> "正常";
            case TOKEN_INVALID -> "Token 不正确";
            case UNREACHABLE -> "连不上";
            case SERVICE_ABNORMAL -> "服务异常";
            case DISABLED -> "未启用";
            case UNKNOWN -> "尚未检查";
        };
    }

    /**
     * 针对具体失败原因给出修复建议
     * <p>
     * 只说「异常」而不说下一步该做什么，使用者无从下手——排障成本高正是本项目最主要的可用性短板。
     */
    private String advise(String sender, OneBotConnectionState.Status status) {
        return switch (status.kind()) {
            case TOKEN_INVALID -> "的 HTTP Token 与 OneBot 实现中配置的不一致，请核对 one-bot-http-token";
            case UNREACHABLE -> "的 OneBot HTTP 服务连不上，请确认 NapCat 等实现已启动，且 one-bot-address 与 one-bot-http-port 填写正确";
            case SERVICE_ABNORMAL -> "的 OneBot 实现自身状态异常，通常是 QQ 账号已掉线，请检查该实现的登录状态";
            case UNKNOWN -> "尚未完成连接检查，请稍候刷新";
            default -> "连接异常：" + status.detail();
        };
    }
}
