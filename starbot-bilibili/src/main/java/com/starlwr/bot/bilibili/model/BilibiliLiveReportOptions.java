package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * 下播报告的版式选项
 * <p>
 * 与推送目标一一对应：同一场直播推给不同群时，可以各自决定展示哪些区块。
 * 取值来自推送配置的 params，缺省时用这里的默认值。
 * <p>
 * 排行榜类选项的取值是「展示前多少名」，0 表示不展示——沿用上游的表达方式，
 * 一个数字同时表达了开关与规模。
 */
@Getter
public class BilibiliLiveReportOptions {
    /**
     * 排行榜默认展示的名次数
     */
    private static final int DEFAULT_RANKING_COUNT = 5;

    /**
     * 单张榜最多展示的名次数，防止一场大直播把报告拉成长图
     */
    private static final int MAX_RANKING_COUNT = 20;

    /**
     * 是否展示直播间封面横幅
     */
    private boolean cover = true;

    /**
     * 是否展示数据卡片栅格
     */
    private boolean cards = true;

    /**
     * 弹幕排行榜展示前多少名，0 为不展示
     */
    private int danmuRanking = DEFAULT_RANKING_COUNT;

    /**
     * 礼物排行榜展示前多少名，0 为不展示
     */
    private int giftRanking = DEFAULT_RANKING_COUNT;

    /**
     * 醒目留言排行榜展示前多少名，0 为不展示
     */
    private int superChatRanking = DEFAULT_RANKING_COUNT;

    /**
     * 盲盒数量排行榜展示前多少名，0 为不展示
     */
    private int boxRanking;

    /**
     * 盲盒盈亏排行榜展示前多少名，0 为不展示
     */
    private int boxProfitRanking;

    /**
     * 是否展示本场开通大航海的观众名单
     */
    private boolean guardList = true;

    /**
     * 是否展示粉丝、粉丝团与大航海的本场变化
     * <p>
     * 这一项每次出报告都要额外打三个接口，关掉它可以省下这笔开销。
     */
    private boolean fansChange = true;

    /**
     * 是否展示互动曲线
     */
    private boolean interactionCurve = true;

    /**
     * 是否展示弹幕词云
     */
    private boolean danmuCloud = true;

    /**
     * 是否展示高能时刻
     * <p>
     * 判据是弹幕密度，不涉及任何金额，因此不随金额可见性降级。
     */
    private boolean highlights = true;

    /**
     * 是否展示本场的标题变化
     * <p>
     * 只在本场真的改过标题时才会占版面，没改过时这一项开着也不会画出空区块。
     */
    private boolean titleChanges = true;

    /**
     * 是否展示金额
     * <p>
     * <b>这一项不来自推送参数</b>，而是取自会话级的金额可见性设置：它回答的是「这份东西给谁看」，
     * 与「报告要长什么样」不是一个问题。同一套版式推给主播私聊和推给大群，
     * 该显示的区块完全相同，该不该带金额则完全相反。
     * <p>
     * 关闭后并非简单地少画几块：概览行的收益整段省略，卡片改用人数、条数等非金额表述，
     * 曲线保留形状但不标峰值，而礼物、醒目留言、盲盒盈亏三张榜整榜不画——
     * 那三张榜的每一行都是「某人花了多少钱」，去掉数字也仍然在排消费。
     */
    private boolean showRevenue = true;

    /**
     * 从推送参数解析版式选项，缺省项用默认值
     * @param params 推送参数，可为 null
     * @param showRevenue 该会话能否看到金额
     * @return 版式选项
     */
    public static BilibiliLiveReportOptions of(JSONObject params, boolean showRevenue) {
        BilibiliLiveReportOptions options = new BilibiliLiveReportOptions();
        options.showRevenue = showRevenue;
        if (params == null) {
            return options;
        }

        options.cover = bool(params, "cover", options.cover);
        options.cards = bool(params, "cards", options.cards);
        options.danmuRanking = ranking(params, "danmu_ranking", options.danmuRanking);
        options.giftRanking = ranking(params, "gift_ranking", options.giftRanking);
        options.superChatRanking = ranking(params, "super_chat_ranking", options.superChatRanking);
        options.boxRanking = ranking(params, "box_ranking", options.boxRanking);
        options.boxProfitRanking = ranking(params, "box_profit_ranking", options.boxProfitRanking);
        options.guardList = bool(params, "guard_list", options.guardList);
        options.fansChange = bool(params, "fans_change", options.fansChange);
        options.interactionCurve = bool(params, "interaction_curve", options.interactionCurve);
        options.danmuCloud = bool(params, "danmu_cloud", options.danmuCloud);
        options.highlights = bool(params, "highlights", options.highlights);
        options.titleChanges = bool(params, "title_changes", options.titleChanges);
        return options;
    }

    private static boolean bool(JSONObject params, String key, boolean defaultValue) {
        Boolean value = params.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 读取排行榜名次数，并夹到合法区间
     * <p>
     * 配置里写了负数或超大值不该让报告画崩，就近取合法值即可。
     */
    private static int ranking(JSONObject params, String key, int defaultValue) {
        Integer value = params.getInteger(key);
        if (value == null) {
            return defaultValue;
        }
        return Math.max(0, Math.min(MAX_RANKING_COUNT, value));
    }
}
