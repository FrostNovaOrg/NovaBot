package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
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
 *   <li>业务码 -509 ≥ 5 次 / 1 小时 —— 与 -352 同族，都是「请求太密」的应答，取同一个阈</li>
 *   <li>业务码 -401 ≥ 3 次 / 24 小时 —— 单次多半是登录态过期这种自己的事，
 *       成串才说明请求被要求验证</li>
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

    private static final int THRESHOLD_401 = 3;

    private static final int THRESHOLD_509 = 5;

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

        long code509 = metrics.count(BilibiliRiskMetrics.Kind.CODE_509, HOUR);
        if (code509 >= THRESHOLD_509) {
            problems.add("1 小时内业务码 -509 " + code509 + " 次");
            advices.add("请求过于频繁。先看轮询间隔与同期 1006 次数，"
                    + "确认不是自己的重连风暴把请求量顶上去的");
        }

        long code401 = metrics.count(BilibiliRiskMetrics.Kind.CODE_401, DAY);
        if (code401 >= THRESHOLD_401) {
            problems.add("24 小时内业务码 -401 " + code401 + " 次");
            advices.add("请求被要求验证。先核对登录态是否已过期，"
                    + "过期只需重新扫码；登录态正常仍成串出现的才要报产品侧");
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

        long unknownOp = metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, DAY);
        if (unknownOp >= 1) {
            String opDetail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_OP).orElse("op=?");
            String firstSeen = metrics.last(BilibiliRiskMetrics.Kind.UNKNOWN_OP)
                    .map(Instant::toString)
                    .orElse("?");
            problems.add("协议层出现未知操作码 " + opDetail + "（首见 " + firstSeen + "）");
            advices.add("多半是 B 站长连协议改了，看日志并抓语料");
        }

        // 与未知操作码同档：版本号不认识意味着可能解不开平台新出的压缩格式
        long unknownVer = metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, DAY);
        if (unknownVer >= 1) {
            String verDetail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_VER).orElse("ver=?");
            String firstSeen = metrics.last(BilibiliRiskMetrics.Kind.UNKNOWN_VER)
                    .map(Instant::toString)
                    .orElse("?");
            problems.add("协议层出现未知协议版本 " + verDetail + "（首见 " + firstSeen + "）");
            advices.add("不认识的版本号会当裸负载处理，压缩格式改了会整批丢消息；看日志并抓语料");
        }

        String unknownCmdLine = unknownCmdSummaryLine();
        String silentLines = silentLossLine(BilibiliRiskMetrics.Kind.PARSE_FAILURE, "解析失败", "类")
                + silentLossLine(BilibiliRiskMetrics.Kind.FIELD_MISSING, "缺字段", "类")
                + silentLossLine(BilibiliRiskMetrics.Kind.API_DATA_MISSING, "接口缺 data", "个端点")
                + silentLossLine(BilibiliRiskMetrics.Kind.PACKET_CORRUPT, "数据包异常", "类")
                + silentLossLine(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD,
                "未知字段：协议 pb 字段号／接口顶层键／枚举取值（detail 形：报文类型:字段号｜端点:键｜CMD:键=值）", "个")
                + overflowLine();

        if (problems.isEmpty()) {
            return HealthStatus.ok(summary(http412, code352, gaia, missing, disconnects) + unknownCmdLine + silentLines);
        }

        return HealthStatus.degraded(String.join("；", problems) + unknownCmdLine + silentLines,
                String.join(" ", advices));
    }

    /**
     * 计数顶到每类保留上限、被挤出去的那一截
     * <p>
     * {@code count()} 封顶在保留上限，顶到之后「恰好顶格」与「二十万次」读出来一模一样，
     * 而这两种要做的事完全不同。有溢出才出这一段——一类都没顶到时，这一行必须与
     * 加这一段之前<b>逐字相同</b>，否则首页文案会莫名其妙地长出一截。
     * <p>
     * 只进摘要、不改档也不动任何阈值：读数封顶说明的是「量太大」，不是「坏了」，
     * 该由哪一类的阈值降档，那一类自己已经在上面判过了。
     */
    private String overflowLine() {
        List<String> spilled = new ArrayList<>();
        for (BilibiliRiskMetrics.Kind kind : BilibiliRiskMetrics.Kind.values()) {
            long dropped = metrics.overflow(kind);
            if (dropped > 0) {
                spilled.add(kind.getLabel() + " " + dropped + " 条");
            }
        }

        if (spilled.isEmpty()) {
            return "";
        }
        return "，计数已顶到保留上限，另有 " + String.join("、", spilled) + " 被挤掉（真实次数还要加上这些）";
    }

    /**
     * 未知消息类型只进摘要、不改档位：出现新 cmd 不等于连接坏了，但首页得看得见。
     */
    private String unknownCmdSummaryLine() {
        long n = metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_CMD, DAY);
        if (n <= 0) {
            return "";
        }
        String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_CMD).orElse("");
        String name = detail.isBlank() ? "?" : detail.split("\\s+")[0];
        return "，近 24h 未知消息类型 " + uniqueOf(detail) + " 种（最近 " + name + "）";
    }

    /**
     * 五类静默信号（解析失败、缺字段、接口缺 data、数据包异常、未知字段）
     * 只进摘要、不改档位：它们说明「有些消息或应答被丢了」或「报文里多了点什么」，
     * 不是连接坏了，但首页得看得见。
     * <p>
     * 数据包异常是其中最贵的一类——协议层没读下来时丢的是<b>整批</b>，
     * 一批里可能有几十条弹幕与礼物。未知字段则是另一头：<b>今天什么都没丢</b>，
     * 但平台已经在报文里放了我们不认识的东西，这往往是数据搬家的前一步。
     */
    private String silentLossLine(BilibiliRiskMetrics.Kind kind, String label, String unit) {
        long n = metrics.count(kind, DAY);
        if (n <= 0) {
            return "";
        }
        String detail = metrics.lastDetail(kind).orElse("");
        String name = detail.isBlank() ? "?" : detail.split("\\s+")[0];
        return "，近 24h " + label + " " + uniqueOf(detail) + " " + unit + "（最近 " + name + "）";
    }

    /**
     * 从记账 detail 里取出 {@code unique=N} 的种数；读不到时退回 1——能走到这里至少发生过一种
     */
    private static String uniqueOf(String detail) {
        int idx = detail.indexOf("unique=");
        if (idx < 0) {
            return "1";
        }
        int start = idx + "unique=".length();
        int end = start;
        while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
            end++;
        }
        return end > start ? detail.substring(start, end) : "1";
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
