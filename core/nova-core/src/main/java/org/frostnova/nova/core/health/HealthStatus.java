package org.frostnova.nova.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康状况
 *
 * @param level 严重程度
 * @param summary 当前状态的简短描述，例如 {@code 正常（uid <你的 uid>）}
 * @param advice 修复建议，正常时为空字符串。异常时务必给出使用者下一步能做什么，
 *               而不是只说「异常」——排障成本高正是本项目最主要的可用性短板
 * @param reason 给界面选待办标题用的原因码，空串表示没有结构化原因。
 *               取值 {@code unconfigured}／{@code unreachable}／{@code account}，由探针填写
 * @param members 本次检查中的异常成员，键为稳定标识，值为可点名的文字；默认空表
 */
public record HealthStatus(Level level, String summary, String advice, String reason,
                           Map<String, String> members) {
    /** 保留不提供成员的构造方式。 */
    public HealthStatus(Level level, String summary, String advice, String reason) {
        this(level, summary, advice, reason, Map.of());
    }

    /**
     * 不带原因码的构造：既有三参调用点不必改
     * @param level 严重程度
     * @param summary 状态描述
     * @param advice 修复建议
     */
    public HealthStatus(Level level, String summary, String advice) {
        this(level, summary, advice, "");
    }

    public HealthStatus {
        members = members == null || members.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(members));
        if (advice == null) {
            advice = "";
        }
        if (reason == null) {
            reason = "";
        }
    }

    /**
     * 严重程度
     */
    public enum Level {
        /**
         * 正常
         */
        OK,

        /**
         * 降级，功能部分可用
         */
        DEGRADED,

        /**
         * 不可用
         */
        DOWN
    }

    /**
     * 构造正常状态
     * @param summary 状态描述
     * @return 健康状况
     */
    public static HealthStatus ok(String summary) {
        return new HealthStatus(Level.OK, summary, "");
    }

    /**
     * 构造降级状态
     * @param summary 状态描述
     * @param advice 修复建议
     * @return 健康状况
     */
    public static HealthStatus degraded(String summary, String advice) {
        return new HealthStatus(Level.DEGRADED, summary, advice);
    }

    /**
     * 构造不可用状态
     * @param summary 状态描述
     * @param advice 修复建议
     * @return 健康状况
     */
    public static HealthStatus down(String summary, String advice) {
        return new HealthStatus(Level.DOWN, summary, advice);
    }

    /**
     * 构造不可用状态，并带上界面用来选待办标题的原因码
     * @param summary 状态描述
     * @param advice 修复建议
     * @param reason 原因码
     * @return 健康状况
     */
    public static HealthStatus down(String summary, String advice, String reason) {
        return new HealthStatus(Level.DOWN, summary, advice, reason);
    }
}
