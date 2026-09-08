package com.starlwr.bot.adapter.onebot.health;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OneBot 连接健康探针
 * <p>
 * 只读取 {@link OneBotConnectionState} 中缓存的结果，不发起网络请求：实际探测由启动检查与
 * 定时检测完成。
 * <p>
 * 判据有四项：HTTP 通不通、账号在不在线、Websocket 在不在、以及<b>调用有多快</b>。
 * 最后一项是 2026-08-10 那次故障补上的——当时接口每次要 2~11 秒，前三项全绿了十个小时，
 * 而带图的推送一直在丢。「通不通」答不了「够不够用」。
 */
@StarBotComponent
public class OneBotHealthProbe implements HealthProbe {
    private final OneBotConnectionState state;

    private final OneBotAdapterPluginProperties properties;

    @Autowired
    public OneBotHealthProbe(OneBotConnectionState state, OneBotAdapterPluginProperties properties) {
        this.state = state;
        this.properties = properties;
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
                    "请在配置文件中填写 novabot.adapter.onebot.senders，至少配置一个 OneBot 连接");
        }

        List<String> summaries = new ArrayList<>();
        List<String> advices = new ArrayList<>();
        HealthStatus.Level worst = HealthStatus.Level.OK;

        for (Map.Entry<String, OneBotConnectionState.Entry> item : all.entrySet()) {
            String sender = item.getKey();
            OneBotConnectionState.Status http = item.getValue().getHttp();
            OneBotConnectionState.Status account = item.getValue().getAccount();
            OneBotConnectionState.Status websocket = item.getValue().getWebsocket();

            Optional<Long> slowMedian = slowMedian(item.getValue().getLatency());

            summaries.add(sender + "：HTTP " + brief(http)
                    + " / 账号 " + briefAccount(account)
                    + " / WS " + brief(websocket)
                    + slowMedian.map(m -> " / 调用慢（中位 " + format(m) + "）").orElse(""));

            // HTTP 不通即无法推送消息，属于致命；Websocket 只用于接收事件，断开仅影响插件功能
            if (http.kind() != OneBotConnectionState.Kind.OK) {
                worst = HealthStatus.Level.DOWN;
                advices.add(sender + " " + advise(sender, http));
            } else if (account.kind() == OneBotConnectionState.Kind.SERVICE_ABNORMAL) {
                // 接口调得通不代表消息发得出去：账号掉线时一切看起来都正常，消息却无人收到
                worst = HealthStatus.Level.DOWN;
                advices.add(sender + " 的 QQ 账号已掉线，接口仍可调用但消息不会送达，请到 OneBot 实现的界面重新扫码登录");
            } else if (websocket.kind() != OneBotConnectionState.Kind.OK
                    && websocket.kind() != OneBotConnectionState.Kind.DISABLED
                    && worst == HealthStatus.Level.OK) {
                worst = HealthStatus.Level.DEGRADED;
                advices.add(sender + " 的 Websocket " + websocket.detail() + "，消息仍可推送，但收不到群内事件");
            }

            // 慢与上面几项是并列的判据，不能放进同一条 else-if 链：
            // 「Websocket 也断了」不该让「调用很慢」这条建议消失，两者的处置办法不同
            if (slowMedian.isPresent()) {
                if (worst == HealthStatus.Level.OK) {
                    worst = HealthStatus.Level.DEGRADED;
                }
                advices.add(sender + " 的接口调用已明显变慢（最近 " + OneBotConnectionState.LATENCY_SAMPLES
                        + " 次中位 " + format(slowMedian.get())
                        + "，最慢 " + item.getValue().getLatency().max().map(this::format).orElse("未知")
                        + "），接口仍然通，但带图的推送可能因超时被丢弃。"
                        + "先用 curl 直接调一次 OneBot 实现：curl 快而本程序慢，说明慢在本程序这一侧"
                        + "（查过的方向：cgroup 内存软上限节流、GC、线程占满），两边都慢才是 OneBot 实现的问题");
            }
        }

        return new HealthStatus(worst, String.join("；", summaries), String.join("；", advices));
    }

    /**
     * 判定接口调用是否已明显变慢
     * <p>
     * 取最近若干次的中位数：健康时也有偶发的慢调用，只看最近一次会被毛刺反复翻黄。
     * 样本不足时不作判断——宁可不说，也不要拿两三个样本硬出一个结论。
     * @param latency 耗时记录
     * @return 已判定为慢时返回中位耗时，否则为空
     */
    private Optional<Long> slowMedian(OneBotConnectionState.Latency latency) {
        int threshold = properties.getDetect().getSlowThresholdMillis();
        if (threshold <= 0) {
            return Optional.empty();
        }
        return latency.median().filter(median -> median > threshold);
    }

    /**
     * 毫秒数的可读形式
     */
    private String format(long millis) {
        return millis >= 1000 ? String.format("%.1f 秒", millis / 1000.0) : millis + " 毫秒";
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
     * 账号在线状态的简短描述
     * <p>
     * 账号状态只有「在线 / 掉线 / 还不知道」三种，沿用通用描述会出现「账号 服务异常」这种读不通的话。
     */
    private String briefAccount(OneBotConnectionState.Status status) {
        return switch (status.kind()) {
            case OK -> "在线";
            case SERVICE_ABNORMAL -> "已掉线";
            default -> "未知";
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
