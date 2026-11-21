package org.frostnova.nova.core.assistant;

import lombok.Getter;
import lombok.Setter;
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
@Setter
@ConfigurationProperties(prefix = "novabot.assistant")
public class AssistantProperties {
    /** 是否启用智能助手，默认关闭；启用还需要模型密钥和当前版本的配置者确认。 */
    @ConfigLabel("启用智能助手")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean enabled = false;
    /** 模型接口的基础地址，NovaBot 会按接口格式补上请求路径。 */
    @ConfigLabel("模型接口地址")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String baseUrl = "https://api.deepseek.com";
    /** 模型名称，默认 deepseek-flash。 */
    @ConfigLabel("模型名称")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String model = "deepseek-flash";
    /** 模型接口收发请求的格式。默认 /chat/completions，模型只在 /responses 上支持调工具时选 /responses。 */
    @ConfigLabel("接口格式")
    @ConfigLevel(ConfigLevel.Level.ADVANCED)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigChoices(value = {"chat", "responses"}, labels = {"/chat/completions", "/responses"})
    private String apiFormat = "chat";
    /** 模型回答前先想一想的程度。适用于 DeepSeek 接口和 /responses 格式。低／高／最高分别多留 4000／16000／32000 token。设为关闭时，/responses 使用模型默认强度，仍多留 4000 token。强度越高，回答前想得越久、花费越多，也更容易超时。 */
    @ConfigLabel("思考强度")
    @ConfigSummary("适用于 DeepSeek 和 /responses。低／高／最高分别多留 4000／16000／32000 token。默认关闭，/responses 此时用模型默认强度。")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigChoices(value = {"off", "low", "high", "max"}, labels = {"关闭", "低", "高", "最高"})
    private String thinking = "off";
    /** 每月金额上限，单位：元，必须大于 0，按东八区自然月计算。 */
    @ConfigLabel("每月金额上限")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private BigDecimal monthlyBudgetYuan = new BigDecimal("30");
    /** 每个账号每天最多提问次数，按东八区自然日计算。 */
    @ConfigLabel("每个账号每日提问次数")
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int dailyQuestionsPerAccount = 20;
    /** 一次模型回答的输出 token 上限。 */
    @ConfigLabel("回答输出上限")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int answerLimit = 800;
    /** 未命中缓存的输入单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("未命中缓存的输入单价")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private BigDecimal priceInputYuanPerMillion = new BigDecimal("2.2");
    /** 命中缓存的输入单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("命中缓存的输入单价")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private BigDecimal priceCachedInputYuanPerMillion = new BigDecimal("0.05");
    /** 输出单价，单位：元／百万 token。默认值按默认服务商 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    @ConfigLabel("输出单价")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private BigDecimal priceOutputYuanPerMillion = new BigDecimal("8.6");

    /** 一问最多执行的只读查数次数。 */
    @ConfigLabel("每问查数次数上限")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int maxToolCalls = 6;
    /** 一问各轮累计的输入 token 上限。 */
    @ConfigLabel("每问累计输入上限")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int questionInputLimit = 30000;
    /** 拒绝名单中的观众编号；这些观众的原话、代号与占比不会发送给模型。 */
    @ConfigLabel("观众拒绝名单")
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private volatile List<Long> excludedUids = List.of();

    public void setMaxToolCalls(int value) { maxToolCalls = positive(value); }
    public void setQuestionInputLimit(int value) { questionInputLimit = positive(value); }
    public void setExcludedUids(List<Long> value) {
        if (value == null || value.stream().anyMatch(uid -> uid == null || uid <= 0))
            throw new IllegalArgumentException("观众拒绝名单请填写正整数编号");
        excludedUids = List.copyOf(value);
    }

    public void setMonthlyBudgetYuan(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("智能助手每月金额上限必须大于 0");
        monthlyBudgetYuan = value;
    }

    public void setBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("智能助手接口地址请填写完整的 http 或 https 地址，不带账号、查询串或片段");
        }
        baseUrl = value;
    }

    public void setModel(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("智能助手模型名称不能为空");
        model = value;
    }

    public void setApiFormat(String value) {
        if (!"chat".equals(value) && !"responses".equals(value))
            throw new IllegalArgumentException("智能助手接口格式请选择 chat 或 responses");
        apiFormat = value;
    }

    public void setThinking(String value) {
        if (value == null || !List.of("off", "low", "high", "max").contains(value))
            throw new IllegalArgumentException("智能助手思考强度请选择关闭（off）、低（low）、高（high）或最高（max）");
        thinking = value;
    }

    public void setDailyQuestionsPerAccount(int value) { dailyQuestionsPerAccount = positive(value); }
    public void setAnswerLimit(int value) { answerLimit = positive(value); }
    public void setPriceInputYuanPerMillion(BigDecimal value) { priceInputYuanPerMillion = price(value); }
    public void setPriceCachedInputYuanPerMillion(BigDecimal value) { priceCachedInputYuanPerMillion = price(value); }
    public void setPriceOutputYuanPerMillion(BigDecimal value) { priceOutputYuanPerMillion = price(value); }

    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("智能助手次数与 token 上限必须大于 0");
        return value;
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("智能助手单价不能小于 0");
        return value;
    }
}
