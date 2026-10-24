package org.frostnova.nova.core.assistant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;

/** 智能助手的模型连接与额度配置，密钥单独加密保存。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "novabot.assistant")
public class AssistantProperties {
    /** 是否启用智能助手，默认关闭；启用还需要模型密钥和当前版本的配置者确认。 */
    private boolean enabled = false;
    /** 模型接口地址，兼容 /chat/completions 格式的接口。 */
    private String baseUrl = "https://api.deepseek.com";
    /** 模型名称，默认 deepseek-flash。 */
    private String model = "deepseek-flash";
    /** 每月金额上限，单位：元，必须大于 0，按东八区自然月计算。 */
    private BigDecimal monthlyBudgetYuan = new BigDecimal("30");
    /** 每个账号每天最多提问次数，按东八区自然日计算。 */
    private int dailyQuestionsPerAccount = 20;
    /** 一问最多查询数据的次数，供问答编排使用。 */
    private int maxToolCalls = 6;
    /** 一问累计输入 token 上限，供问答编排使用。 */
    private int maxQuestionTokens = 30000;
    /** 一次模型回答的输出 token 上限。 */
    private int maxAnswerTokens = 800;
    /** 未命中缓存的输入单价，单位：元／百万 token。默认按 DeepSeek 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    private BigDecimal priceInputYuanPerMillion = new BigDecimal("2.2");
    /** 命中缓存的输入单价，单位：元／百万 token。默认按 DeepSeek 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    private BigDecimal priceCachedInputYuanPerMillion = new BigDecimal("0.05");
    /** 输出单价，单位：元／百万 token。默认按 DeepSeek 2026-10 公布的高峰价折人民币取整；换服务商或服务商调价时请改。 */
    private BigDecimal priceOutputYuanPerMillion = new BigDecimal("8.6");

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

    public void setDailyQuestionsPerAccount(int value) { dailyQuestionsPerAccount = positive(value); }
    public void setMaxToolCalls(int value) { maxToolCalls = positive(value); }
    public void setMaxQuestionTokens(int value) { maxQuestionTokens = positive(value); }
    public void setMaxAnswerTokens(int value) { maxAnswerTokens = positive(value); }
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
