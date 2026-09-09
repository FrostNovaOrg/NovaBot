package org.frostnova.nova.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.model.HandlerOption;
import lombok.Getter;

import java.util.List;

/**
 * 下播报告的版式选项
 * <p>
 * 与推送目标一一对应：同一场直播推给不同群时，可以各自决定展示哪些区块。
 * 取值来自推送配置的 params，缺省时用这里的默认值。
 * <p>
 * 排行榜类选项的取值是「展示前多少名」，0 表示不展示——沿用上游的表达方式，
 * 一个数字同时表达了开关与规模。
 *
 * <h2>版式项的唯一出处</h2>
 * {@link #layoutOptions()} 那张表就是「这份报告有哪些版式项」的答案，
 * 推送处理器的默认参数、控制台的渲染、以及枚举接口全都从它派生。
 * <p>
 * 🔴 <b>默认值只有一份，写在下面各字段的初值上</b>——那正是 {@link #of} 在参数缺项时用的值。
 * 表里的默认值由 {@link #DEFAULTS} 这个现成实例读出来，不另抄一遍：
 * <b>一张手抄了一份默认值的表，和一张真的与生效值一致的表，在界面上长得一样</b>，
 * 而对不上的那天没有任何地方会报错，只会让人以为「配了没用」。
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
     * 全名单最多展示的人数。0 表示不限，上限挡住把报告拉成几千行的配置
     */
    private static final int MAX_GUARD_LIST_ALL = 1000;

    /**
     * 全名单默认展示的人数
     */
    private static final int DEFAULT_GUARD_LIST_ALL = 30;

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
     * 是否展示这位主播当前的全部大航海，不只本场新开通的
     */
    private boolean guardListAll = true;

    /**
     * 全名单展示前多少人，0 为不限
     */
    private int guardListLimit = DEFAULT_GUARD_LIST_ALL;

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
     * 全部取默认值的一份，供下面那张表读出默认值
     * <p>
     * 不可外借也不可改：字段没有 setter，{@link #of} 每次另建新实例。
     */
    private static final BilibiliLiveReportOptions DEFAULTS = new BilibiliLiveReportOptions();

    /**
     * 版式项一览：key、人话名、一句话说明、类型、默认值、取值范围
     * <p>
     * 顺序即界面上的顺序：先是整块的开关，后是几张排行榜——按使用者读报告时
     * 从上往下的次序排，而不是按字段在类里的先后。
     * <p>
     * ⚠️ <b>这张表与上面的字段必须一一对应</b>，由
     * {@code BilibiliLiveReportLayoutOptionsTest} 两个方向现算：表里有的键 {@link #of} 都真的读，
     * {@link #of} 读的键表里都有。🔴 <b>一个漏在表外的版式项，和一个不存在的版式项，
     * 在界面上长得一样</b>——它照样生效，只是没人配得到它。
     */
    private static final List<HandlerOption> LAYOUT_OPTIONS = List.of(
            HandlerOption.bool("cover", "直播间封面", "报告顶部的封面横幅", DEFAULTS.cover),
            HandlerOption.bool("cards", "数据卡片", "弹幕、礼物、点赞等概览卡片", DEFAULTS.cards),
            HandlerOption.bool("fans_change", "本场变化", "粉丝、粉丝团、大航海的涨幅，每次出报告要多打三个接口", DEFAULTS.fansChange),
            HandlerOption.bool("interaction_curve", "互动曲线", "弹幕、礼物等随时间的变化曲线", DEFAULTS.interactionCurve),
            HandlerOption.bool("guard_list", "大航海名单", "本场新开通大航海的观众", DEFAULTS.guardList),
            HandlerOption.bool("guard_list_all", "大航海全名单",
                    "这位主播当前全部大航海，不只本场新开通。拉不到时这一段会写明，不会让整张报告失败",
                    DEFAULTS.guardListAll),
            HandlerOption.integer("guard_list_limit", "全名单人数", "展示前几名，0 为不限",
                    DEFAULTS.guardListLimit, 0, MAX_GUARD_LIST_ALL),
            HandlerOption.bool("danmu_cloud", "弹幕词云", "本场弹幕的词云图", DEFAULTS.danmuCloud),
            HandlerOption.bool("highlights", "高能时刻", "弹幕最密集的几个时段，对应可剪切片的时间点", DEFAULTS.highlights),
            HandlerOption.bool("title_changes", "标题变化", "本场改过的直播间标题，没改过时不占版面", DEFAULTS.titleChanges),
            HandlerOption.integer("danmu_ranking", "弹幕排行", "展示前几名，0 为不展示",
                    DEFAULTS.danmuRanking, 0, MAX_RANKING_COUNT),
            HandlerOption.integer("gift_ranking", "礼物排行", "展示前几名，0 为不展示",
                    DEFAULTS.giftRanking, 0, MAX_RANKING_COUNT),
            HandlerOption.integer("super_chat_ranking", "醒目留言排行", "展示前几名，0 为不展示",
                    DEFAULTS.superChatRanking, 0, MAX_RANKING_COUNT),
            // 盲盒两榜默认关闭：多数直播间没有盲盒数据，开着只会让报告多两块空白
            HandlerOption.integer("box_ranking", "盲盒排行", "展示前几名，0 为不展示",
                    DEFAULTS.boxRanking, 0, MAX_RANKING_COUNT),
            HandlerOption.integer("box_profit_ranking", "盲盒盈亏排行", "展示前几名，0 为不展示",
                    DEFAULTS.boxProfitRanking, 0, MAX_RANKING_COUNT));

    /**
     * 这份报告有哪些版式项
     * <p>
     * ⚠️ 表里<b>不含金额可见性</b>：那一项不来自推送参数，理由见 {@link #showRevenue}。
     * 把它混进来，界面上就会出现一个「勾了也不算数」的开关——它由会话决定，
     * 而不是由「报告要长什么样」决定。
     * @return 版式项，顺序即界面顺序
     */
    public static List<HandlerOption> layoutOptions() {
        return LAYOUT_OPTIONS;
    }

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
        options.guardListAll = bool(params, "guard_list_all", options.guardListAll);
        options.guardListLimit = bounded(params, "guard_list_limit", options.guardListLimit, 0, MAX_GUARD_LIST_ALL);
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

    /**
     * 读取一个整数并夹到合法区间
     */
    private static int bounded(JSONObject params, String key, int defaultValue, int min, int max) {
        Integer value = params.getInteger(key);
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
