package com.starlwr.bot.bilibili.analytics;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.List;

/**
 * 哔哩哔哩指标说明
 * <p>
 * 只列**可累加**的指标：周月统计做的就是相加，列进来就等于声明「这个数相加有意义」。
 * <p>
 * <b>三项开播快照被有意排除</b>——{@code fans_at_start} / {@code fans_medal_at_start} /
 * {@code guard_at_start} 记的是开播那一刻的存量，把一个月里十场的粉丝数加起来，
 * 得到的既不是月初也不是月末的粉丝数，是个无中生有的数。
 * 想看粉丝增长得记每场的涨幅（终值减快照），归档里没有这一项，硬凑不如不给。
 * <p>
 * 顺序即界面展示顺序，按运营关心的程度排：先看互动量，再看收入，最后是长尾。
 */
@StarBotComponent
public class BilibiliLiveMetricCatalog implements LiveMetricCatalog {
    private static final List<Metric> METRICS = List.of(
            Metric.count(BilibiliLiveMetric.DANMU_COUNT, "弹幕", "条"),
            Metric.money(BilibiliLiveMetric.GIFT_VALUE, "礼物价值"),
            Metric.money(BilibiliLiveMetric.GIFT_PAID, "礼物实付"),
            Metric.count(BilibiliLiveMetric.SUPER_CHAT_COUNT, "醒目留言", "条"),
            Metric.money(BilibiliLiveMetric.SUPER_CHAT_VALUE, "醒目留言价值"),
            Metric.money(BilibiliLiveMetric.GUARD_VALUE, "大航海价值"),
            Metric.count(BilibiliLiveMetric.CAPTAIN_COUNT, "舰长", "人次"),
            Metric.count(BilibiliLiveMetric.COMMANDER_COUNT, "提督", "人次"),
            Metric.count(BilibiliLiveMetric.GOVERNOR_COUNT, "总督", "人次"),
            Metric.count(BilibiliLiveMetric.FOLLOW_COUNT, "新增关注", "人次"),
            Metric.count(BilibiliLiveMetric.LIKE_TOTAL, "点赞", "次"),
            Metric.count(BilibiliLiveMetric.SHARE_COUNT, "分享", "人次"),
            // 单位写「人次」而不是「人」：单场是这场的独立人数，而周月汇总是把各场相加，
            // 同一个人两场都来会被算两次。与已有的点赞总数（同为服务端下发的累计值）口径一致
            Metric.count(BilibiliLiveMetric.WATCHED_COUNT, "看过", "人次"),
            Metric.count(BilibiliLiveMetric.ONLINE_RANK_COUNT, "高能用户", "人次"),
            Metric.count(BilibiliLiveMetric.BOX_COUNT, "盲盒", "个"),
            Metric.money(BilibiliLiveMetric.BOX_PROFIT, "盲盒盈亏"),
            Metric.count(BilibiliLiveMetric.FREE_GIFT_COUNT, "免费礼物", "个")
    );

    @Override
    public String platform() {
        return "bilibili";
    }

    @Override
    public List<Metric> metrics() {
        return METRICS;
    }
}
