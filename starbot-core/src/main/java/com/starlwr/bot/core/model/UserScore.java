package com.starlwr.bot.core.model;

/**
 * 用户在某项指标上的得分，用于排行榜
 * @param userUid 用户 UID
 * @param score 得分，含义随指标而定：弹幕为条数，礼物为价值（元）
 */
public record UserScore(Long userUid, double score) {
}
