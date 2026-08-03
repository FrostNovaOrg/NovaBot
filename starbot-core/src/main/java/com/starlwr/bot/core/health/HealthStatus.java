package com.starlwr.bot.core.health;

/**
 * 健康状况
 *
 * @param level 严重程度
 * @param summary 当前状态的简短描述，例如「正常（uid 200000001）」
 * @param advice 修复建议，正常时为空字符串。异常时务必给出使用者下一步能做什么，
 *               而不是只说「异常」——排障成本高正是本项目最主要的可用性短板
 */
public record HealthStatus(Level level, String summary, String advice) {
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
}
