package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 风控与静默降级健康探针
 * <p>
 * 把「不报错但确实出问题了」的几类信号变成可被自动发现的状态。
 * 阈值由产品侧给定，不要随手改：
 * <ul>
 *   <li>真实 HTTP 412 ≥ 3 次 / 7 天 —— 这是重新评估真实浏览器方案的触发条件，
 *       所以它必须能被自动发现，不能靠人翻日志</li>
 *   <li>业务码 -352 ≥ 5 次 / 1 小时 —— 短时间密集出现才说明被限流，
 *       偶发一次通常是自己的重连风暴打出来的</li>
 *   <li>风控质询 / 验证码 —— 出现任何一次即告警</li>
 *   <li>开播快照项缺失 —— 出现任何一次即告警</li>
 *   <li>长连接 1006 ≥ 10 次 / 1 小时 —— 单次属正常抖动，成串出现才是风暴</li>
 * </ul>
 */
@StarBotComponent
public class BilibiliRiskHealthProbe implements HealthProbe {
    private static final Duration WEEK = Duration.ofDays(7);

    private static final Duration HOUR = Duration.ofHours(1);

    private static final Duration DAY = Duration.ofDays(1);

    private static final int THRESHOLD_412 = 3;

    private static final int THRESHOLD_352 = 5;

    private static final int THRESHOLD_1006 = 10;

    private final BilibiliRiskMetrics metrics;

    @Autowired
    public BilibiliRiskHealthProbe(BilibiliRiskMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return "风控与静默降级";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Scope scope() {
        return Scope.PLATFORM;
    }

    @Override
    public HealthStatus check() {
        List<String> problems = new ArrayList<>();
        List<String> advices = new ArrayList<>();

        long http412 = metrics.count(BilibiliRiskMetrics.Kind.HTTP_412, WEEK);
        if (http412 >= THRESHOLD_412) {
            problems.add("7 天内真实 HTTP 412 " + http412 + " 次");
            advices.add("已达到重新评估真实浏览器方案的触发条件（≥3 次/7 天），请报产品侧决策");
        }

        long code352 = metrics.count(BilibiliRiskMetrics.Kind.CODE_352, HOUR);
        if (code352 >= THRESHOLD_352) {
            problems.add("1 小时内业务码 -352 " + code352 + " 次");
            advices.add("请求被风控限流。先查是不是自己的重连风暴打出来的——"
                    + "看同期长连接 1006 次数，若同时飙升则是自伤而非平台主动风控");
        }

        long gaia = metrics.count(BilibiliRiskMetrics.Kind.GAIA, DAY);
        if (gaia > 0) {
            problems.add("24 小时内风控质询/验证码 " + gaia + " 次");
            advices.add("出现质询说明请求已被判定为异常客户端，"
                    + metrics.lastDetail(BilibiliRiskMetrics.Kind.GAIA).map(d -> "最近一次：" + d + "。").orElse("")
                    + "请报产品侧，不要自行尝试绕过");
        }

        long missing = metrics.count(BilibiliRiskMetrics.Kind.SNAPSHOT_MISSING, DAY);
        if (missing > 0) {
            problems.add("24 小时内开播快照项缺失 " + missing + " 次");
            advices.add("粉丝数、粉丝团、大航海三项快照有接口取不到，"
                    + metrics.lastDetail(BilibiliRiskMetrics.Kind.SNAPSHOT_MISSING).map(d -> "最近一次：" + d + "。").orElse("")
                    + "表现是下播报告里对应的卡片直接消失，数值不会变成 0。"
                    + "常见原因是接口端点被平台下线");
        }

        long disconnects = metrics.count(BilibiliRiskMetrics.Kind.DISCONNECT_1006, HOUR);
        if (disconnects >= THRESHOLD_1006) {
            problems.add("1 小时内长连接 1006 断线 " + disconnects + " 次");
            advices.add("成串的秒级断线通常是握手被拒后反复重连。"
                    + "检查登录态是否正常，以及建连是否绕过了全局连接闸门");
        }

        if (problems.isEmpty()) {
            return HealthStatus.ok(summary(http412, code352, gaia, missing, disconnects));
        }

        return HealthStatus.degraded(String.join("；", problems), String.join(" ", advices));
    }

    /**
     * 正常时也把各项计数显示出来：这些指标本身就是要给人看的，
     * 只在越线时才显示等于平时无从判断趋势
     */
    private String summary(long http412, long code352, long gaia, long missing, long disconnects) {
        long all1006 = metrics.count(BilibiliRiskMetrics.Kind.DISCONNECT_1006, DAY);
        return String.format("412 %d 次/7 天，-352 %d 次/时，质询 %d 次/日，快照缺失 %d 次/日，1006 %d 次/时（%d 次/日）",
                http412, code352, gaia, missing, disconnects, all1006);
    }
}
