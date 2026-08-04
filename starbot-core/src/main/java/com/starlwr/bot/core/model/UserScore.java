package com.starlwr.bot.core.model;

/**
 * 用户在某项指标上的得分，用于排行榜
 * <p>
 * 昵称随计分一并记录，绘制排行榜时无需再对每个用户发一次接口请求——
 * 一场直播的榜单动辄数十人，逐个请求既慢又容易触发风控。
 * @param userUid 用户 UID
 * @param userName 用户昵称，未记录到时为空
 * @param score 得分，含义随指标而定：弹幕为条数，礼物为价值（元）
 */
public record UserScore(Long userUid, String userName, double score) {
    /**
     * 昵称缺失时以 UID 兜底，供直接展示
     * @return 可展示的名称
     */
    public String displayName() {
        return userName == null || userName.isBlank() ? String.valueOf(userUid) : userName;
    }
}
