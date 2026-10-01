package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.LiveDataService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水排行的旧场次回落：礼物与醒目留言的分人金额按人相加
 * <p>
 * 分人流水表是后来才有的，升级前就在播的那一场读不到它。能算的那部分——礼物与
 * 醒目留言的分人金额表——早就各自在记，按人相加就是那一场的流水；上舰的分人金额
 * 旧数据里没有，不含，口径说明 {@link BilibiliLiveMetric#REVENUE_RANKING_FALLBACK_NOTE} 说清。
 * <p>
 * 下播报告图与「数据排行榜」命令共用这一份合成：同一群人在两个入口看到的必须是
 * 同一张榜——各写一遍的话，迟早一边改了另一边没跟。
 */
public final class RevenueRankings {
    private RevenueRankings() {
    }

    /**
     * 全量合成（不截名次）：调用方要先知道回落榜一共几人时用这一支，
     * 免得为了个数把同一份名单合成两遍
     */
    public static List<UserScore> legacyFallback(LiveDataService service, String platform, Long uid) {
        return legacyFallback(service, platform, uid, Integer.MAX_VALUE);
    }

    /**
     * 合成后取前 {@code limit} 名。两张表整表取（排序在内存里做完，多取不另花代价）
     */
    public static List<UserScore> legacyFallback(LiveDataService service, String platform, Long uid, int limit) {
        Map<Long, Double> merged = new LinkedHashMap<>();
        Map<Long, UserScore> byUser = new LinkedHashMap<>();
        for (String metric : List.of(BilibiliLiveMetric.GIFT_USERS, BilibiliLiveMetric.SUPER_CHAT_USERS)) {
            for (UserScore user : service.getLiveUserRanking(platform, uid, metric, Integer.MAX_VALUE)) {
                if (user.userUid() == null) {
                    continue;
                }
                merged.merge(user.userUid(), user.score(), Double::sum);
                byUser.putIfAbsent(user.userUid(), user);
            }
        }
        return merged.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    UserScore known = byUser.get(entry.getKey());
                    return new UserScore(entry.getKey(), known == null ? null : known.userName(),
                            known == null ? null : known.userFace(), entry.getValue());
                })
                .toList();
    }
}
