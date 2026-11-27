package org.frostnova.nova.core.assistant;

import lombok.Getter;
import org.frostnova.nova.core.properties.ConfigChoices;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigLevel;
import org.frostnova.nova.core.properties.ConfigSummary;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

/** 智能助手的模型连接与额度配置，密钥单独加密保存。 */
@Getter
@ConfigurationProperties(prefix = "novabot.assistant")
public class AssistantProperties {
    public static final int MAX_CONCURRENT_QUESTIONS = 8;

    /** 是否启用智能助手，默认关闭；启用还需要模型密钥和当前版本的配置者确认。 */
    @ConfigLabel("启用智能助手")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private boolean enabled = false;
    /** 模型接口的基础地址，NovaBot 会按接口格式补上请求路径。 */
    @ConfigLabel("模型接口地址")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private String baseUrl = "https://api.deepseek.com";
    /** 模型名称，默认 deepseek-flash。 */
    @ConfigLabel("模型名称")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private String model = "deepseek-flash";
    /** 模型接口收发请求的格式。默认 /chat/completions，模型只在 /responses 上支持调工具时选 /responses。 */
    @ConfigLabel("接口格式")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    @ConfigChoices(value = {"chat", "responses"}, labels = {"/chat/completions", "/responses"})
    private String apiFormat = "chat";
    /**
     * 选「关闭／模型默认」时，DeepSeek 接口关闭思考。/responses 格式下不指定强度，由模型用它的默认强度，输出上限仍多留 4000 token。
     * /responses 格式下，输出上限在低档多留 4000 token，中档多留 8000 token，高档多留 16000 token，最高档多留 32000 token。
     * DeepSeek 接口把中档按低档发送，多留 4000 token。
     */
    @ConfigLabel("思考强度")
    @ConfigSummary("设置模型回答前的思考强度，默认「关闭／模型默认」。强度越高，通常耗时越长、花费越多，也更容易超时。")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    @ConfigChoices(value = {"off", "low", "medium", "high", "max"}, labels = {"关闭／模型默认", "低", "中", "高", "最高"})
    private String thinking = "off";
    /** 开启后，每次提问先判断难度，为难题调整思考强度和限时。判断的花费同时计入该主播额度和每周总额。 */
    @ConfigLabel("按难度调思考强度")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private boolean adaptiveThinking = false;
    /** 默认高。难题取这一档和「思考强度」中较高的一档。 */
    @ConfigLabel("难题的思考强度")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    @ConfigChoices(value = {"medium", "high", "max"}, labels = {"中", "高", "最高"})
    private String hardThinking = "high";
    /** 留空时用回答的模型。填写后仍使用同一个接口地址和密钥。判断难度这一小问按回答模型的单价记账。 */
    @ConfigLabel("判断难度用的模型名称")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private String difficultyModel = "";
    /** 控制台和聊天共用，默认同时回答 2 个问题，可设为 1 至 8。其余问题按提问顺序排队。 */
    @ConfigLabel("同时最多回答几个问题")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int maxConcurrentQuestions = 2;
    /** 难题整问的限时，默认 180 秒。普通问题限时 55 秒。 */
    @ConfigLabel("难题限时（秒）")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int hardTimeoutSeconds = 180;
    /** 全实例共用的金额上限，单位为元，必须大于 0。东八区每周一 0 点恢复。 */
    @ConfigLabel("每周金额上限")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal weeklyBudgetYuan = BigDecimal.TEN;
    /** 每位主播每天在控制台和聊天中的合计金额上限，单位为元。填 0 表示不限，东八区每天 0 点恢复。 */
    @ConfigLabel("每位主播每日金额上限")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal streamerDailyBudgetYuan = BigDecimal.ONE;
    /** 每位主播每周在控制台和聊天中的合计金额上限，单位为元。填 0 表示不限，东八区每周一 0 点恢复。 */
    @ConfigLabel("每位主播每周金额上限")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal streamerWeeklyBudgetYuan = new BigDecimal("5");
    /** 一次模型回答的输出 token 上限。 */
    @ConfigLabel("回答输出上限")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int answerLimit = 800;
    /** 未命中缓存的输入单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("未命中缓存的输入单价")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal priceInputYuanPerMillion = new BigDecimal("2.2");
    /** 命中缓存的输入单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("命中缓存的输入单价")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal priceCachedInputYuanPerMillion = new BigDecimal("0.05");
    /** 输出单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("输出单价")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private BigDecimal priceOutputYuanPerMillion = new BigDecimal("8.6");

    /** 一问最多执行的只读查数次数。 */
    @ConfigLabel("每问查数次数上限")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int maxToolCalls = 6;
    /** 一问各轮累计的输入 token 上限。未命中的全额计入，命中的按命中单价除以未命中单价折算。未命中单价为零或服务商未报命中数时，全部按原数计入。 */
    @ConfigLabel("每问累计输入上限")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int questionInputLimit = 30000;
    /** 拒绝名单中的观众编号；这些观众的原话、代号与占比不会发送给模型。 */
    @ConfigLabel("观众拒绝名单")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private volatile List<Long> excludedUids = List.of();

    /** 一问及其各轮请求使用同一份设置。与运行时的批量更新共用这把锁。 */
    public synchronized Settings snapshot() {
        return new Settings(enabled, baseUrl, model, apiFormat, thinking, adaptiveThinking, hardThinking,
                difficultyModel, hardTimeoutSeconds, weeklyBudgetYuan, streamerDailyBudgetYuan, streamerWeeklyBudgetYuan,
                answerLimit, priceInputYuanPerMillion, priceCachedInputYuanPerMillion, priceOutputYuanPerMillion,
                maxToolCalls, questionInputLimit, excludedUids);
    }

    public record Settings(boolean enabled, String baseUrl, String model, String apiFormat, String thinking,
                           boolean adaptiveThinking, String hardThinking, String difficultyModel, int hardTimeoutSeconds,
                           BigDecimal weeklyBudgetYuan, BigDecimal streamerDailyBudgetYuan, BigDecimal streamerWeeklyBudgetYuan,
                           int answerLimit, BigDecimal priceInputYuanPerMillion, BigDecimal priceCachedInputYuanPerMillion,
                           BigDecimal priceOutputYuanPerMillion, int maxToolCalls, int questionInputLimit, List<Long> excludedUids) {
        public Settings {
            excludedUids = List.copyOf(excludedUids);
        }
    }

    public synchronized void setEnabled(boolean value) { enabled = value; }
    public synchronized void setAdaptiveThinking(boolean value) { adaptiveThinking = value; }
    public synchronized void setMaxConcurrentQuestions(int value) {
        if (value < 1 || value > MAX_CONCURRENT_QUESTIONS) throw new IllegalArgumentException("同时最多回答几个问题请填写 1 至 8 的整数");
        maxConcurrentQuestions = value;
        // 排队的问题与批量保存共用此锁，整批生效后即可按新上限放行。
        notifyAll();
    }
    public synchronized void setMaxToolCalls(int value) { maxToolCalls = positive(value); }
    public synchronized void setQuestionInputLimit(int value) { questionInputLimit = positive(value); }
    public synchronized void setExcludedUids(List<Long> value) {
        if (value == null || value.stream().anyMatch(uid -> uid == null || uid <= 0))
            throw new IllegalArgumentException("观众拒绝名单请填写正整数编号");
        excludedUids = List.copyOf(value);
    }

    public synchronized void setWeeklyBudgetYuan(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("智能助手每周金额上限必须大于 0");
        weeklyBudgetYuan = value;
    }

    public synchronized void setStreamerDailyBudgetYuan(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("每位主播每日金额上限不能小于 0");
        streamerDailyBudgetYuan = value;
    }

    public synchronized void setStreamerWeeklyBudgetYuan(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("每位主播每周金额上限不能小于 0");
        streamerWeeklyBudgetYuan = value;
    }

    public synchronized void setBaseUrl(String value) {
        int port;
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            port = uri.getPort();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("智能助手接口地址请填写完整的 http 或 https 地址，不带账号、查询串或片段");
        }
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("智能助手接口地址的端口必须在 1–65535 之间");
        }
        baseUrl = value;
    }

    public synchronized void setModel(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("智能助手模型名称不能为空");
        model = value;
    }

    public synchronized void setApiFormat(String value) {
        if (!"chat".equals(value) && !"responses".equals(value))
            throw new IllegalArgumentException("智能助手接口格式请选择 chat 或 responses");
        apiFormat = value;
    }

    public synchronized void setThinking(String value) {
        if (value == null || !List.of("off", "low", "medium", "high", "max").contains(value))
            throw new IllegalArgumentException("智能助手思考强度请选择关闭／模型默认（off）、低（low）、中（medium）、高（high）或最高（max）");
        thinking = value;
    }

    public synchronized void setHardThinking(String value) {
        if (value == null || !List.of("medium", "high", "max").contains(value))
            throw new IllegalArgumentException("难题的思考强度请选择中（medium）、高（high）或最高（max）");
        hardThinking = value;
    }

    public synchronized void setDifficultyModel(String value) { difficultyModel = value == null ? "" : value.strip(); }

    public synchronized void setHardTimeoutSeconds(int value) {
        if (value <= 0) throw new IllegalArgumentException("难题限时必须大于 0 秒");
        hardTimeoutSeconds = value;
    }

    public synchronized void setAnswerLimit(int value) { answerLimit = positive(value); }
    public synchronized void setPriceInputYuanPerMillion(BigDecimal value) { priceInputYuanPerMillion = price(value); }
    public synchronized void setPriceCachedInputYuanPerMillion(BigDecimal value) { priceCachedInputYuanPerMillion = price(value); }
    public synchronized void setPriceOutputYuanPerMillion(BigDecimal value) { priceOutputYuanPerMillion = price(value); }

    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("智能助手次数与 token 上限必须大于 0");
        return value;
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("智能助手单价不能小于 0");
        return value;
    }
}
