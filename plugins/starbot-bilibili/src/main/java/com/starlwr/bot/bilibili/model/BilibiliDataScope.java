package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;

import java.util.List;

/**
 * 数据查询的范围
 * <p>
 * 「我的数据」与「我的总数据」除了读哪一份数据之外完全一样，排行榜与直播间数据同理。
 * 把这个差异收成一个枚举，六个命令就能共用同一套取数与排版逻辑，
 * 而不是把每段代码抄两遍——抄两遍的地方迟早会只改一边。
 */
public enum BilibiliDataScope {
    /**
     * 本场直播，开播时清零
     */
    LIVE("本场") {
        @Override
        public double metric(LiveDataService service, String platform, Long uid, String metric) {
            return service.getLiveMetric(platform, uid, metric);
        }

        @Override
        public double userMetric(LiveDataService service, String platform, Long uid, String metric, Long userUid) {
            return service.getLiveUserMetric(platform, uid, metric, userUid);
        }

        @Override
        public int userRank(LiveDataService service, String platform, Long uid, String metric, Long userUid) {
            return service.getLiveUserRank(platform, uid, metric, userUid);
        }

        @Override
        public List<UserScore> ranking(LiveDataService service, String platform, Long uid, String metric, int limit) {
            return service.getLiveUserRanking(platform, uid, metric, limit);
        }

        @Override
        public int userCount(LiveDataService service, String platform, Long uid, String metric) {
            return service.getLiveMetricUserCount(platform, uid, metric);
        }
    },

    /**
     * 跨场次累计，需配置外部存储
     */
    TOTAL("累计") {
        @Override
        public double metric(LiveDataService service, String platform, Long uid, String metric) {
            return service.getTotalMetric(platform, uid, metric);
        }

        @Override
        public double userMetric(LiveDataService service, String platform, Long uid, String metric, Long userUid) {
            return service.getTotalUserMetric(platform, uid, metric, userUid);
        }

        @Override
        public int userRank(LiveDataService service, String platform, Long uid, String metric, Long userUid) {
            return service.getTotalUserRank(platform, uid, metric, userUid);
        }

        @Override
        public List<UserScore> ranking(LiveDataService service, String platform, Long uid, String metric, int limit) {
            return service.getTotalUserRanking(platform, uid, metric, limit);
        }

        @Override
        public int userCount(LiveDataService service, String platform, Long uid, String metric) {
            return service.getTotalMetricUserCount(platform, uid, metric);
        }
    };

    /**
     * 范围的中文说法，用于图片标题
     */
    private final String label;

    BilibiliDataScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否为累计范围。累计需要外部存储，未配置时命令要明确告知而非返回一堆 0
     */
    public boolean isTotal() {
        return this == TOTAL;
    }

    public abstract double metric(LiveDataService service, String platform, Long uid, String metric);

    public abstract double userMetric(LiveDataService service, String platform, Long uid, String metric, Long userUid);

    public abstract int userRank(LiveDataService service, String platform, Long uid, String metric, Long userUid);

    public abstract List<UserScore> ranking(LiveDataService service, String platform, Long uid, String metric, int limit);

    public abstract int userCount(LiveDataService service, String platform, Long uid, String metric);
}
