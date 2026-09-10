package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.event.live.*;
import org.frostnova.nova.bilibili.model.BilibiliEmojiInfo;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.model.FansMedal;
import org.frostnova.nova.bilibili.model.Guard;
import org.frostnova.nova.bilibili.protocol.BilibiliProtobufReader;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播间消息解析器
 * <p>
 * 将直播间长连接下发的原始消息解析为 NovaBot 事件。直播间消息的字段随版本频繁变动，
 * 且同一字段在不同消息中可能缺失，因此所有取值一律做空值防护：任何单条消息解析失败
 * 都只影响该条消息，不会中断整个直播间的消息处理。
 */
@Slf4j
@NovaComponent
public class BilibiliEventParser {
    /**
     * 礼物与大航海接口返回的价格单位为电池的千分之一，1000 对应 1 元
     */
    private static final double PRICE_UNIT = 1000.0;

    /**
     * 从大航海播报文案里取陪伴天数，如「今天是TA陪伴主播的第1171天」
     * <p>
     * 「陪伴」与「第」之间隔着主播名等文字，长度不定，因此用惰性匹配并限长——
     * 不限长的话可能跨过整句话去匹配到后面某个不相干的数字。
     */
    private static final Pattern COMPANION_DAYS = Pattern.compile("陪伴[^0-9]{0,30}?第\\s*([0-9]{1,6})\\s*天");

    /**
     * 陪伴天数的合理上限，超出即认为匹配错了位置。按平台自身年龄留足余量
     */
    private static final int MAX_COMPANION_DAYS = 36500;

    /**
     * 已播报过的红包，键为 {@code lot_id}，值为首次见到的时刻
     * <p>
     * 红包的开启消息<b>会被周期性重播</b>：实测一条 {@code POPULARITY_RED_POCKET_START}
     * 的 {@code start_time} 是十分钟前，而 {@code current_time} 就是当下。
     * 不去重就会把同一个红包反复感谢。
     */
    private final Map<String, Instant> seenRedPockets = new ConcurrentHashMap<>();

    /**
     * 按名记账的静默损失计数：解析失败的 cmd、截断的 pb、过短的弹幕 info 共用一本账
     * <p>
     * {@code info[0]} 过短原先只有计数、解析失败与 pb 截断原先<b>一声不响</b>：
     * 格式一旦变了，消息会静默消失而任何地方都看不到。计数按 1、10、100… 报，
     * 既不会淹掉日志与指标，也不会让「丢了多少」无从得知。
     */
    private final ConcurrentHashMap<String, AtomicLong> namedEventCounts = new ConcurrentHashMap<>();

    /**
     * 分派表外的 cmd 名计数，按名去重。超过 {@link #MAX_UNKNOWN_CMD_NAMES} 的新名只累加总数。
     */
    private final ConcurrentHashMap<String, AtomicLong> unknownCmds = new ConcurrentHashMap<>();

    /**
     * 各未知 cmd 的首次出现时刻，写入指标 detail 用。
     */
    private final ConcurrentHashMap<String, Instant> unknownCmdFirstSeen = new ConcurrentHashMap<>();

    /**
     * 名表已满后仍碰到的新 cmd 条数
     */
    private final AtomicLong unknownCmdNameTableOverflow = new AtomicLong();

    /**
     * 未知 cmd 名表上限，与风控指标每类条数上限对齐
     */
    private static final int MAX_UNKNOWN_CMD_NAMES = 512;

    /**
     * pb 报文里字段表外的字段号计数，键为 {@code <报文类型>:<字段号>}，按名去重。
     * 超过 {@link #MAX_UNKNOWN_FIELD_NAMES} 的新名只累加总数。
     */
    private final ConcurrentHashMap<String, AtomicLong> unknownFields = new ConcurrentHashMap<>();

    /**
     * 各未知字段号的首次出现时刻，写入指标 detail 用。
     */
    private final ConcurrentHashMap<String, Instant> unknownFieldFirstSeen = new ConcurrentHashMap<>();

    /**
     * 名表已满后仍碰到的新字段号条数
     */
    private final AtomicLong unknownFieldNameTableOverflow = new AtomicLong();

    /**
     * 未知字段名表上限，与未知 cmd 名表同一个数
     */
    private static final int MAX_UNKNOWN_FIELD_NAMES = 512;

    /**
     * 红包记录的条目上限，防止长期运行后无限增长
     */
    private static final int MAX_SEEN_RED_POCKETS = 256;

    /**
     * 红包记录的保留时长。单个红包最长 600 秒，留一小时余量足够
     */
    private static final Duration RED_POCKET_RETENTION = Duration.ofHours(1);

    /**
     * {@code INTERACT_WORD_V2} 的 protobuf 字段号
     * <p>
     * 平台没有公开 {@code .proto}，以下字段号全部由实抓语料反推：2026-08-10 在单个直播间
     * 采集的 2122 条登录态 {@code INTERACT_WORD_V2}，用最小 wire 读取器逐条统计得出，
     * 并与 {@code protoc --decode_raw} 抽样核对过结构。
     * <p>
     * <b>反推必须用登录态语料。</b>匿名连接下 uid 被平台抹成 0，而 proto3 不序列化零值，
     * {@link #V2_UID} 会整个消失——照匿名语料会得出「uid 只在少数消息里出现」的错误结论。
     * 登录态语料里 uid 的出现率是 2122/2122。
     * <p>
     * 各字段的证据强度差别很大，取值时的防护按此区分：
     * <pre>
     *   字段  语义              证据
     *     1   观众 uid          2122/2122，1978 个不同值，与 22.1 逐条一致
     *     2   观众昵称          2122/2122，与 22.2.1 一致
     *     5   消息类型          2122/2122，取值只有 1/2/3，分布 2117/4/1
     *     6   房间号            2122/2122，恒为采集所在房间
     *     7   秒级时间戳        2122/2122，10 位，全部落在采集窗口内
     *     9   本房间粉丝勋章    859/2122（其中 6 条为空消息），子字段见下
     *    10   是否推广位进入    3/2122，恒为 1，与 V1 的 is_spread 对应
     *    13   推广来源文案      3/2122，与字段 10 同现，值为「流量包推广」
     *    22   观众完整信息      2122/2122，子字段见下
     *   9.1   勋章所属主播 uid  853/853
     *   9.2   勋章等级          853/853
     *   9.3   勋章名称          853/853
     *   9.8   勋章是否点亮      284/853（proto3 省略 0，未出现即未点亮）
     *  9.12   勋章所属房间号    853/853
     *  22.1   观众 uid          2122/2122
     *  22.2   基础信息 {1:昵称, 2:头像}
     *  22.3   勋章全量 {11:大航海等级, 13:大航海图标}
     *  22.4   财富等级 {1:等级}
     *  22.6   大航海 {1:等级, 2:到期时间}
     * </pre>
     * <b>不使用</b>字段 8：它多数时候等于字段 7 的毫秒形式，但 287/2122 条对不上，
     * 最多超出字段 7 达三天半，语义未明，拿它当时间戳会把事件时刻记错。
     * <p>
     * <b>不使用</b>字段 16：疑似大航海等级，29/2122 条出现且恒为 3，与 22.3.11、22.6.1
     * 逐条一致。但只见过舰长这一种取值，无法证明总督与提督也走同一个字段，
     * 因此大航海仍从 {@code uinfo} 里取，见 {@link #parseGuardV2}。
     * <p>
     * 字段 4、11、12、15、19、23、24 语义未坐实，均未取用，详见实现汇报。
     * 其中 11 只在推广位进房那一份语料里出现（length-delimited），此前漏记在本表里，
     * 落 {@link #INTERACT_V2_KNOWN_FIELDS} 时按语料补回——<b>见过</b>与<b>取用</b>是两回事，
     * 已知集要的是前者。
     */
    private static final int V2_UID = 1;

    private static final int V2_UNAME = 2;

    private static final int V2_MSG_TYPE = 5;

    private static final int V2_TIMESTAMP = 7;

    private static final int V2_FANS_MEDAL = 9;

    private static final int V2_IS_SPREAD = 10;

    private static final int V2_SPREAD_DESC = 13;

    private static final int V2_UINFO = 22;

    private static final int V2_MEDAL_TARGET_UID = 1;

    private static final int V2_MEDAL_LEVEL = 2;

    private static final int V2_MEDAL_NAME = 3;

    private static final int V2_MEDAL_LIGHTED = 8;

    private static final int V2_MEDAL_ROOM_ID = 12;

    private static final int V2_UINFO_BASE = 2;

    private static final int V2_UINFO_MEDAL = 3;

    private static final int V2_UINFO_WEALTH = 4;

    private static final int V2_UINFO_GUARD = 6;

    private static final int V2_BASE_NAME = 1;

    private static final int V2_BASE_FACE = 2;

    private static final int V2_FULL_MEDAL_GUARD_LEVEL = 11;

    private static final int V2_FULL_MEDAL_GUARD_ICON = 13;

    private static final int V2_LEVEL = 1;

    /**
     * {@code INTERACT_WORD_V2} 顶层<b>已知</b>的字段号
     * <p>
     * 「已知」＝上面那张字段表里出现过的号，<b>不是</b>「取值时读到的号」：
     * 6（房间号）、8（毫秒时间戳）、16（疑似大航海等级）与 4、11、12、15、19、23、24
     * 都反推过、都决定不取，但它们是<b>见过</b>的字段，不该被当成平台新增。
     * <p>
     * 这个集合只用来做差集，不参与任何取值——取值仍走上面各个 {@code V2_*} 常量。
     * 差集里冒出来的号才是「这条报文比我们反推那天多出来的东西」。
     * <p>
     * ⚠️ <b>只管顶层</b>。子消息（勋章 9、uinfo 22 等）另有各自的布局，一张表套不住，
     * 且子消息的未知字段与「平台改了接口」不是同一件事的可能性更大。
     */
    private static final Set<Integer> INTERACT_V2_KNOWN_FIELDS = Set.of(
            V2_UID, V2_UNAME, V2_MSG_TYPE, V2_TIMESTAMP, V2_FANS_MEDAL,
            V2_IS_SPREAD, V2_SPREAD_DESC, V2_UINFO,
            4, 6, 8, 11, 12, 15, 16, 19, 23, 24);

    /**
     * {@code SEND_GIFT_V2} 的 protobuf 字段号
     * <p>
     * 平台同样没有公开 {@code .proto}，字段号由 2026-09-04/05 实抓的 2 条登录态样本反推
     * （跨 2 个房间，送礼者为同一人，礼物均为 ¥0.1 的牛哇牛哇），并与同时段同房的
     * 2 条 V1 报文对照过语义；2026-09-05 生产又核到同一对真样（粉丝团灯牌与粉丝手幅），
     * 34 与 37 号的读法出自这一对。样本量远小于当初 INTERACT_WORD_V2 的 2122 条，
     * 各字段的把握见下，子布局与 INTERACT_WORD_V2 <b>不通用</b>的地方单独标了 ⚠️。
     * <pre>
     *   顶层字段  语义                证据
     *      1     观众 uid            2/2，与 15.1 一致
     *      2     观众昵称            2/2，与 15.2.1 一致
     *      3     观众头像            2/2，与 15.2.2 一致（仅作核对，取值走 uinfo）
     *      8     旧式勋章            2/2，布局同 V1 的 medal_info；V1 不取它，这里同样忽略
     *      9     盲盒子消息           6/29 样本 2026-09-07（只在盲盒条出现），子字段见下
     *     10     礼物块              2/2，子字段见下
     *     11     是否首次            2/2，恒为 1，与 V1 的 is_first 对应，不取用
     *     13     财富等级 {1:等级}    2/2。⚠️ 礼物版 uinfo 里没有 wealth（2/2 无 15.4），
     *                                等级放在顶层而不是 uinfo.4，与 INTERACT_WORD_V2 相反
     *     15     观众完整信息 uinfo   2/2，uid 在 1、昵称头像在 2{1,2}（与 INTERACT_WORD_V2
     *                                一致，那三个常量直接复用）；其余子布局不同，见下
     * </pre>
     * <pre>
     *   礼物块（10）字段  语义              证据
     *        1           礼物 id           2/2
     *        2           礼物名            2/2
     *        3           数量              2/2
     *        5           疑似原价          2/2，与 6 相等（样本礼物不打折），区分不出，不取用
     *        6           折扣价            2/2，与 V1 discount_price 对应
     *        7           实扣 total_coin   2/2，量纲与 V1 一致（千分之一元）
     *        8           币种              2/2，取值 gold
     *       10           秒级时间戳        2/2，落在采集窗口内
     *       12           连击批次号        2/2，不取用
     *       18           动作文案          2/2，如「投喂」，不取用
     *       34           表情特效          2/2 空；2026-09-05 生产实样定读：灯牌为
     *                     {1:id, 2:type}    {id:5632012, type:1}，手幅为空。face_effect 一类，
     *                                       非盲盒（初版 2 条恒空曾疑开出物名，方向错了），
     *                                       与入账无关，TRACE 留 id/type
     *       35           礼物信息 {1:图}   2/2，与 V1 gift_info.img_basic 对应
     *       37           特效清单          2/2 空；生产实样灯牌为 {1:1; 2:{id,type} ×2}
     *                     repeated {1,2}    （repeated 子消息），手幅为空。用途未知，不取用
     * </pre>
     * <pre>
     *   盲盒（9）字段     语义              证据
     *        1           盲盒配置 id      6/6，对应 V1 blind_gift_config_id，记而不取
     *        2           投入的盒子 id    6/6，对应 V1 original_gift_id
     *        3           投入的盒子名     6/6，对应 V1 original_gift_name
     *        5           动作文案         6/6，对应 V1 gift_action（如「爆出」），记而不取
     *        6           盒价             6/6，对应 V1 original_gift_price（千分之一元）
     * </pre>
     * 9.4 与 V1 gift_tip_price 在样本里都没有对应，不取。
     * <pre>
     *   勋章（15.3）字段  语义              证据
     *        1           勋章名            2/2（字符串）
     *        2           勋章等级          2/2
     *        7           疑似大航海等级    0/2，按 V1 medal.guard_level 的位置类推，未经样本证实
     *        8           疑似大航海图标    0/2，同上
     *        9           是否点亮          1/2（另一条没有该字段，按 proto3 省略零值读作未点亮）
     *       10           勋章所属主播 uid  2/2，逐条等于房间主播
     * </pre>
     * ⚠️ <b>这套勋章布局与 INTERACT_WORD_V2 的整套不通用</b>：那条的勋章名在子字段 3、
     * 所属主播在 1、是否点亮在 8；这条完全换了位置。两份表套错会把主播 uid 读成勋章名，
     * 因此勋章与大航海单独一套方法（{@link #parseGiftFansMedalV2} / {@link #parseGiftGuardV2}），
     * 不复用 {@link #parseFansMedalV2} / {@link #parseGuardV2}。
     * <p>
     * V1 还取 {@code bag_gift}（背包礼物）与 {@code blind_gift}（盲盒）。背包礼物在 V2
     * 里字段号未知（样本里没出现过），暂时认不出来，实扣按 total_coin 照记。盲盒是顶层
     * 9 号子消息，见 {@link #parseGiftV2}。
     */
    private static final int GIFT_V2_UID = 1;

    private static final int GIFT_V2_UNAME = 2;

    private static final int GIFT_V2_BLIND = 9;

    private static final int GIFT_V2_INFO = 10;

    private static final int GIFT_V2_BLIND_ORIGINAL_ID = 2;

    private static final int GIFT_V2_BLIND_ORIGINAL_NAME = 3;

    private static final int GIFT_V2_BLIND_ORIGINAL_PRICE = 6;

    private static final int GIFT_V2_WEALTH = 13;

    private static final int GIFT_V2_UINFO = 15;

    private static final int GIFT_V2_ID = 1;

    private static final int GIFT_V2_NAME = 2;

    private static final int GIFT_V2_NUM = 3;

    private static final int GIFT_V2_DISCOUNT_PRICE = 6;

    private static final int GIFT_V2_TOTAL_COIN = 7;

    private static final int GIFT_V2_COIN_TYPE = 8;

    private static final int GIFT_V2_TIMESTAMP = 10;

    private static final int GIFT_V2_FACE_EFFECT = 34;

    private static final int GIFT_V2_FACE_EFFECT_ID = 1;

    private static final int GIFT_V2_FACE_EFFECT_TYPE = 2;

    private static final int GIFT_V2_GIFT_INFO = 35;

    private static final int GIFT_V2_IMG_BASIC = 1;

    private static final int GIFT_V2_UINFO_MEDAL = 3;

    private static final int GIFT_V2_MEDAL_NAME = 1;

    private static final int GIFT_V2_MEDAL_LEVEL = 2;

    private static final int GIFT_V2_MEDAL_GUARD_LEVEL = 7;

    private static final int GIFT_V2_MEDAL_GUARD_ICON = 8;

    private static final int GIFT_V2_MEDAL_LIGHTED = 9;

    private static final int GIFT_V2_MEDAL_TARGET_UID = 10;

    /**
     * {@code SEND_GIFT_V2} 顶层<b>已知</b>的字段号
     * <p>
     * 口径同 {@link #INTERACT_V2_KNOWN_FIELDS}：3（观众头像，取值走 uinfo）、8（旧式勋章）、
     * 11（是否首次）都在字段表里且都决定不取，它们是见过的字段。
     * <p>
     * ⚠️ 两张表<b>不通用</b>，与勋章子布局不通用是同一个理由（见上面的字段表）：
     * 拿这一张去量进房报文，13 与 15 会被判成未知、22 会被判成新增——两边都错。
     */
    private static final Set<Integer> GIFT_V2_KNOWN_FIELDS = Set.of(
            GIFT_V2_UID, GIFT_V2_UNAME, GIFT_V2_BLIND, GIFT_V2_INFO, GIFT_V2_WEALTH, GIFT_V2_UINFO,
            3, 8, 11);

    private final NovaBilibiliProperties properties;

    private final BilibiliGiftService giftService;

    private final BilibiliApiSupport apiSupport;

    private final BilibiliGuardReconciler guardReconciler;

    private final BilibiliRiskMetrics riskMetrics;

    /**
     * 消息类型到解析方法的映射
     */
    private final Map<String, BiFunction<JSONObject, LiveStreamerInfo, NovaBaseLiveEvent>> parsers = new HashMap<>();

    public BilibiliEventParser(NovaBilibiliProperties properties, BilibiliGiftService giftService,
                               BilibiliApiSupport apiSupport, BilibiliGuardReconciler guardReconciler) {
        this(properties, giftService, apiSupport, guardReconciler, new BilibiliRiskMetrics());
    }

    @Autowired
    public BilibiliEventParser(NovaBilibiliProperties properties, BilibiliGiftService giftService,
                               BilibiliApiSupport apiSupport, BilibiliGuardReconciler guardReconciler,
                               BilibiliRiskMetrics riskMetrics) {
        this.properties = properties;
        this.giftService = giftService;
        this.apiSupport = apiSupport;
        this.guardReconciler = guardReconciler;
        this.riskMetrics = riskMetrics;

        parsers.put("LIVE", this::parseLiveOn);
        parsers.put("PREPARING", this::parseLiveOff);
        parsers.put("DANMU_MSG", this::parseDanmu);
        // 两种格式都要收。2026-08 起平台改发 V2，三次抓包都是 0 条 V1——只认老格式的话
        // 进房、关注、分享会恒为 0 条且没有任何报错。V1 仍然保留，平台随时可能回滚
        parsers.put("INTERACT_WORD", this::parseInteract);
        parsers.put("INTERACT_WORD_V2", this::parseInteractV2);
        // 礼物同样两种格式并存：2026-09-01 起平台按房间灰度改发 V2（正文在 data.pb），
        // 只认老格式的房间礼物会整类消失，直播报告随之缺收入。V1 保留，平台随时可能回滚
        parsers.put("SEND_GIFT", this::parseGift);
        parsers.put("SEND_GIFT_V2", this::parseGiftV2);
        parsers.put("SUPER_CHAT_MESSAGE", this::parseSuperChat);
        parsers.put("USER_TOAST_MSG", this::parseGuard);
        parsers.put("USER_TOAST_MSG_V2", this::parseGuardV2);
        parsers.put("GUARD_BUY", this::parseGuardBuy);
        parsers.put("POPULARITY_RED_POCKET_START",
                (data, source) -> parseRedPocket("POPULARITY_RED_POCKET_START", data, source));
        parsers.put("POPULARITY_RED_POCKET_V2_START",
                (data, source) -> parseRedPocket("POPULARITY_RED_POCKET_V2_START", data, source));
        parsers.put("LIKE_INFO_V3_CLICK", this::parseLike);
        parsers.put("LIKE_INFO_V3_UPDATE", this::parseLikeUpdate);
        parsers.put("WATCHED_CHANGE", this::parseWatchedUpdate);
        parsers.put("ONLINE_RANK_COUNT", this::parseOnlineRankCount);
        parsers.put("ROOM_CHANGE", this::parseRoomInfoChange);
        parsers.put("WARNING", this::parseWarning);
        parsers.put("CUT_OFF", this::parseCutOff);
        parsers.put("ROOM_LOCK", this::parseRoomLock);
    }

    /**
     * 一条消息的解析产出
     *
     * @param event 解析出的事件，消息类型不受支持或解析失败时为空
     * @param degraded 是否<b>解析降级</b>：未知 cmd 或已知 cmd 解析抛异常。
     *                 注意「合法的空返回」（如开播消息不带开播时间）不是降级——
     *                 那是「没这一条」，不是「解析不出来」，算进去的话
     *                 解析失败计数永远对不上
     */
    public record ParsedMessage(Optional<NovaBaseLiveEvent> event, boolean degraded) {
    }

    /**
     * 解析一条直播间消息，并给出解析降级标志
     * @param data 消息内容
     * @param source 直播间信息
     * @return 解析产出（事件＋降级标志）
     */
    public ParsedMessage parseMessage(JSONObject data, LiveStreamerInfo source) {
        if (data == null) {
            return new ParsedMessage(Optional.empty(), false);
        }

        String type = data.getString("cmd");
        if (type == null) {
            return new ParsedMessage(Optional.empty(), false);
        }

        // 部分消息的 cmd 带有形如 DANMU_MSG:4:0:2:2:2:0 的后缀
        int colon = type.indexOf(':');
        if (colon > 0) {
            type = type.substring(0, colon);
        }

        if (properties.getDebug().isLiveRoomRawMessageLog()) {
            log.debug("{}: {} -> {}", type, source.getRoomId(), data.toJSONString());
        }

        BiFunction<JSONObject, LiveStreamerInfo, NovaBaseLiveEvent> parser = parsers.get(type);
        if (parser == null) {
            noteUnknownCmd(type);
            return new ParsedMessage(Optional.empty(), true);
        }

        try {
            NovaBaseLiveEvent event = parser.apply(data, source);
            if (event != null) {
                // 原始报文随事件一起走：事件输出协议要把它透传给下游，排障时也要对着它看
                // 「解析出来的字段」与「平台实际下发的内容」是不是一回事。存引用不做序列化，
                // 详见 NovaBaseLiveEvent.rawMessage
                event.setRawMessage(data);
            }
            return new ParsedMessage(Optional.ofNullable(event), false);
        } catch (Exception e) {
            log.error("解析直播间 {} 的 {} 类型消息异常, 内容: {}", source.getRoomId(), type, data.toJSONString(), e);
            // 异常被吞掉等于这条消息没来过。逐条记一笔，同 cmd 只在量级处换一份文本样本
            noteNamed(BilibiliRiskMetrics.Kind.PARSE_FAILURE, type);
            return new ParsedMessage(Optional.empty(), true);
        }
    }

    /**
     * 解析一条直播间消息
     * @param data 消息内容
     * @param source 直播间信息
     * @return 解析出的事件，消息类型不受支持或解析失败时返回空
     */
    public Optional<NovaBaseLiveEvent> parse(JSONObject data, LiveStreamerInfo source) {
        return parseMessage(data, source).event();
    }

    /**
     * 解析开播消息
     */
    private NovaBaseLiveEvent parseLiveOn(JSONObject data, LiveStreamerInfo source) {
        Long liveTime = data.getLong("live_time");
        if (liveTime == null) {
            // 开播消息在直播间连接建立时也会重复下发，此时不带开播时间，不应视为一次新的开播
            return null;
        }

        return new BilibiliLiveOnEvent(source, Instant.ofEpochSecond(liveTime));
    }

    /**
     * 解析下播消息
     */
    private NovaBaseLiveEvent parseLiveOff(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliLiveOffEvent(source);
    }

    /**
     * 解析弹幕与表情弹幕消息
     * <p>
     * 原名 {@code parseMessage}，与带降级标志的公开入口 {@link #parseMessage} 重名冲突后改名，
     * 顺带与其他按消息命名的解析方法（parseGift、parseGuard…）对齐
     */
    private NovaBaseLiveEvent parseDanmu(JSONObject data, LiveStreamerInfo source) {
        // info 中只有下标 0 是必需的，粉丝勋章与荣耀等级所在的下标可能不存在，按可选处理
        JSONArray primary = arrayAt(data.getJSONArray("info"), 0);
        if (primary == null || primary.size() < 16) {
            reportShortInfo(primary);
            return null;
        }

        JSONArray info = data.getJSONArray("info");

        JSONObject meta = primary.getJSONObject(15);
        JSONObject senderInfo = meta == null ? null : meta.getJSONObject("user");
        if (senderInfo == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "DANMU_MSG:user");
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(senderInfo, source);
        // 弹幕消息的粉丝勋章位于 info[3]，为定长数组而非对象
        sender.setFansMedal(parseArrayFansMedal(arrayAt(info, 3), source));
        sender.setHonorLevel(Optional.ofNullable(arrayAt(info, 16)).map(array -> array.getInteger(0)).orElse(null));
        sender.setRoomAdmin(parseRoomAdmin(arrayAt(info, 2)));

        Instant timestamp = Optional.ofNullable(primary.getLong(4)).map(Instant::ofEpochMilli).orElseGet(Instant::now);
        JSONObject extra = parseExtra(meta.getString("extra"));

        // primary[13] 为字符串时是普通弹幕，为对象时是表情弹幕
        if (primary.get(13) instanceof JSONObject emojiInfo) {
            BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(
                    emojiInfo.getString("emoticon_unique"),
                    extra.getString("content"),
                    emojiInfo.getString("url"),
                    emojiInfo.getInteger("width"),
                    emojiInfo.getInteger("height"),
                    null
            );
            return new BilibiliEmojiEvent(source, sender, emoji, timestamp);
        }

        String content = extra.getString("content");
        String contentText = content;

        List<BilibiliEmojiInfo> emojis = new ArrayList<>();
        JSONObject emots = extra.getJSONObject("emots");
        if (emots != null) {
            for (String emojiName : emots.keySet()) {
                JSONObject emojiInfo = emots.getJSONObject(emojiName);
                if (emojiInfo == null) {
                    continue;
                }

                if (contentText != null) {
                    contentText = contentText.replace(emojiName, "");
                }

                emojis.add(new BilibiliEmojiInfo(
                        emojiInfo.getString("emoticon_unique"),
                        emojiName,
                        emojiInfo.getString("url"),
                        emojiInfo.getInteger("width"),
                        emojiInfo.getInteger("height"),
                        emojiInfo.getInteger("count")
                ));
            }
        }

        BilibiliDanmuEvent event = new BilibiliDanmuEvent(source, sender, content, contentText, timestamp);
        event.setEmojis(emojis);
        event.setReply(parseReply(extra, source));

        return event;
    }

    /**
     * 登记一条分派表外的 cmd：<b>每条都计数</b>，按名去重的只是 detail 里那份文本样本，
     * 首见与 10/100/1000… 量级各换一次。detail 只含 cmd 名、计数、种数与首见时刻，不写入报文。
     */
    private void noteUnknownCmd(String cmd) {
        if (cmd == null || cmd.isBlank() || riskMetrics == null) {
            return;
        }
        AtomicLong existing = unknownCmds.get(cmd);
        if (existing == null && unknownCmds.size() >= MAX_UNKNOWN_CMD_NAMES) {
            long overflow = unknownCmdNameTableOverflow.incrementAndGet();
            riskMetrics.record(BilibiliRiskMetrics.Kind.UNKNOWN_CMD, isMagnitude(overflow)
                    ? "名表溢出 count=" + overflow + " unique=" + unknownCmds.size()
                    : null);
            return;
        }
        long count = unknownCmds.computeIfAbsent(cmd, key -> new AtomicLong()).incrementAndGet();
        Instant first = unknownCmdFirstSeen.computeIfAbsent(cmd, key -> Instant.now());
        boolean sample = isMagnitude(count);
        riskMetrics.record(BilibiliRiskMetrics.Kind.UNKNOWN_CMD, sample
                ? cmd + " count=" + count + " unique=" + unknownCmds.size() + " at=" + first
                : null);
        if (sample) {
            log.warn("未知直播间消息类型 {} 已出现 {} 次（已登记 {} 种）", cmd, count, unknownCmds.size());
        }
    }

    /**
     * 登记这条 pb 报文里字段表外的字段号
     * <p>
     * <b>热路径</b>：{@code INTERACT_WORD_V2} 每秒数十条。差集在读取器里做（那里能不分配就
     * 不分配），字符串拼接排在「已判定为未知」<b>之后</b>——绝大多数报文一个未知号都没有，
     * 这一行走到 {@code isEmpty()} 就回去了，一次拼接都不做。
     * <p>
     * 按「报文类型＋字段号」去重、逐条计数，detail 只写名、计数、种数与首见时刻。
     * <b>不写取值</b>：新字段里装的很可能正是观众信息，而 detail 是要显示在健康页上的。
     * @param cmd 报文类型
     * @param message 已读出的报文
     * @param known 该报文类型顶层的已知字段号
     */
    private void noteUnknownFields(String cmd, BilibiliProtobufReader message, Set<Integer> known) {
        if (riskMetrics == null) {
            return;
        }
        List<Integer> unknown = message.unknownFields(known);
        if (unknown.isEmpty()) {
            return;
        }

        for (int field : unknown) {
            noteUnknownField(cmd + ":" + field);
        }
    }

    /**
     * 登记一个未知字段号：<b>每次都计数</b>，按名去重的只是 detail 里那份文本样本，
     * 首见与 10/100/1000… 量级各换一次。名表满后新名只累加溢出数，与未知 cmd 同一套办法。
     */
    private void noteUnknownField(String name) {
        AtomicLong existing = unknownFields.get(name);
        if (existing == null && unknownFields.size() >= MAX_UNKNOWN_FIELD_NAMES) {
            long overflow = unknownFieldNameTableOverflow.incrementAndGet();
            riskMetrics.record(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, isMagnitude(overflow)
                    ? "名表溢出 count=" + overflow + " unique=" + unknownFields.size()
                    : null);
            return;
        }
        long count = unknownFields.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
        Instant first = unknownFieldFirstSeen.computeIfAbsent(name, key -> Instant.now());
        boolean sample = isMagnitude(count);
        riskMetrics.record(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, sample
                ? name + " count=" + count + " unique=" + unknownFields.size() + " at=" + first
                : null);
        if (sample) {
            log.warn("直播间消息 {} 出现字段表外的字段号, 已出现 {} 次（已登记 {} 种）。"
                    + "这多半是平台加了新字段, 取值不受影响, 但字段表该复核了", name, count, unknownFields.size());
        }
    }

    /**
     * 1、10、100、1000… 这样的量级。与 {@link #noteNamed} 同一套判据。
     * <p>
     * 它决定的是<b>换不换一份文本样本、打不打一条日志</b>，不决定计不计数：
     * 格式真变了的时候丢弃是成千上万条的，逐条打日志会把日志本身冲垮；
     * 但计数漏一条就等于让那一条静默消失，而阈值全都建在计数上。
     */
    private static boolean isMagnitude(long count) {
        return Long.toString(count).matches("10*");
    }

    /**
     * 按名记账一条静默损失（解析失败的 cmd、截断的 pb、过短的弹幕 info）。
     * <b>每条都计数</b>，同名只在 1/10/100… 量级处换一份 detail；
     * detail 只含名、计数与种数，不写入报文。
     * @return 该名累计到的次数（含这一次），没记成时为 0
     */
    private long noteNamed(BilibiliRiskMetrics.Kind kind, String name) {
        if (name == null || name.isBlank() || riskMetrics == null) {
            return 0;
        }
        long count = namedEventCounts.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
        riskMetrics.record(kind, isMagnitude(count)
                ? name + " count=" + count + " unique=" + namedEventCounts.size()
                : null);
        return count;
    }

    /**
     * 取出直播间消息的 {@code data} 对象。缺了就记一笔缺字段并返回空——调用方仍按原样丢掉这条消息。
     */
    private JSONObject requireData(JSONObject json, String cmd) {
        JSONObject meta = json.getJSONObject("data");
        if (meta == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, cmd + ":data");
        }
        return meta;
    }

    /**
     * 枚举值写进记账名时截到 32 个字符，避免把整段异常文本写进健康栏。
     */
    private static String clipped(Object value) {
        String raw = String.valueOf(value);
        return raw.length() <= 32 ? raw : raw.substring(0, 32);
    }

    /**
     * 登记一条「负载根本不是合法 JSON」的解析失败
     * <p>
     * 与分派表内解析抛异常走同一本账（同一张去重表、同一个 {@code unique=} 口径）：
     * 各记各的会让健康页上的种数取决于最后写的是哪一本。
     * @param cmd 消息类型，读不出来时传 null，记作 {@code ?}
     */
    public void noteParseFailure(String cmd) {
        noteNamed(BilibiliRiskMetrics.Kind.PARSE_FAILURE, cmd == null || cmd.isBlank() ? "?" : cmd);
    }

    /**
     * 报告一条因 {@code info[0]} 过短而被丢弃的弹幕
     * <p>
     * 按 1、10、100… 这样的次数报，而不是每条都报：格式真变了的时候丢弃是成千上万条的，
     * 逐条打日志只会把日志本身冲垮，而完全不打就等于让弹幕静默消失。
     */
    private void reportShortInfo(JSONArray primary) {
        long count = noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "DANMU_MSG:info<16");
        if (isMagnitude(count)) {
            log.warn("已有 {} 条弹幕因 info[0] 过短被丢弃（本条 {} 项，需要至少 16 项）。"
                            + "若这个数在持续增长，说明报文格式变了，需要重新核对下标",
                    count, primary == null ? 0 : primary.size());
        }
    }

    /**
     * 解析房管标志
     * <p>
     * 房管标志在<b>旧格式的发送者数组</b> {@code info[2]} 的第 3 位，
     * 而昵称、头像这些我们是从新格式的 {@code uinfo} 里取的——两处并存，
     * 但 {@code uinfo} 里<b>没有</b>房管标志（实测 1346 条弹幕，uinfo 的键始终是
     * anon / base / guard / guard_leader / medal / title / uhead_frame / uid / wealth）。
     * <p>
     * <b>{@code guard_leader} 不是房管</b>，那是大航海的舰长头衔，两者无关。
     * <p>
     * 实测印证：同一批样本里 24 条取 1、1322 条取 0，{@code info[2]} 的其余位恒定不变。
     * @param sender 旧格式发送者数组
     * @return 是否为房管，数组缺失时为空——空表示「这条消息没说」
     */
    private Boolean parseRoomAdmin(JSONArray sender) {
        if (sender == null || sender.size() < 3) {
            return null;
        }

        Integer admin = sender.getInteger(2);
        return admin == null ? null : admin == 1;
    }

    /**
     * 解析弹幕中的回复对象
     */
    private UserInfo parseReply(JSONObject extra, LiveStreamerInfo source) {
        Long replyUid = extra.getLong("reply_mid");
        if (replyUid == null || replyUid == 0L) {
            return null;
        }

        String replyUname = extra.getString("reply_uname");
        if (!properties.getLive().isCompleteEvent()) {
            return new UserInfo(replyUid, replyUname);
        }

        return new UserInfo(replyUid, replyUname, apiSupport.completeFace(replyUid, source).orElse(null));
    }

    /**
     * 解析弹幕消息中的附加信息
     */
    private JSONObject parseExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return new JSONObject();
        }

        try {
            JSONObject parsed = JSON.parseObject(extra);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception e) {
            log.debug("解析弹幕附加信息失败: {}", extra);
            return new JSONObject();
        }
    }

    /**
     * 解析进房、关注与分享消息
     */
    private NovaBaseLiveEvent parseInteract(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "INTERACT_WORD");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(meta.getJSONObject("uinfo"), source);
        if (sender.getUid() == null) {
            sender.setUid(meta.getLong("uid"));
        }
        if (sender.getUname() == null) {
            sender.setUname(meta.getString("uname"));
        }
        sender.setFansMedal(parseObjectFansMedal(meta.getJSONObject("fans_medal"), source));

        Instant timestamp = Optional.ofNullable(meta.getLong("timestamp")).map(Instant::ofEpochSecond).orElseGet(Instant::now);

        Integer msgType = meta.getInteger("msg_type");
        if (msgType == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "INTERACT_WORD:msg_type");
            return null;
        }

        switch (msgType) {
            case 1 -> {
                BilibiliEnterRoomEvent event = new BilibiliEnterRoomEvent(source, sender, timestamp);
                event.setFromPromotion(isOne(meta.getInteger("is_spread")));
                event.setPromotionSource(Optional.ofNullable(meta.getString("spread_desc")).filter(s -> !s.isBlank()).orElse(null));
                return event;
            }
            case 2 -> {
                return new BilibiliFollowEvent(source, sender, timestamp);
            }
            case 3 -> {
                return new BilibiliShareEvent(source, sender, timestamp);
            }
            default -> {
                log.debug("未处理的直播间互动消息类型: {}", msgType);
                noteNamed(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, "INTERACT_WORD:msg_type=" + clipped(msgType));
                return null;
            }
        }
    }

    /**
     * 解析进房、关注与分享消息的新版格式（{@code INTERACT_WORD_V2}）
     * <p>
     * 与 {@code INTERACT_WORD} 是同一件事的两种格式，产出<b>完全相同的三种事件</b>，
     * 下游无需区分。差别只在承载方式：这一版把正文塞进 {@code data.pb}，
     * 是一段 base64 编码的 protobuf，字段号的来历与证据见 {@link #V2_UID} 处的字段表。
     * <p>
     * <b>验收覆盖度：</b>进房（{@code msg_type=1}）在 2117 条实抓样本上比对过，
     * 是这条通路的正主。<b>关注与分享同走一套分支，两者都已按正式指标看待</b>：
     * 关注 10 条（跨 4 个直播间，字段布局逐条一致）在 4.3.0 即已转正；
     * <b>分享于 2026-09-02 转正</b>——2026-08-12～08-30 生产语料复核，分享 13 条、
     * 跨 2 个直播间、13/13 带 uid，且分享出现过的字段全在「关注 81 条 ∪ 进房 3726 条」
     * 出现过的字段里，<b>没有一个是只在分享里出现的</b>。见下面各自的说明。
     * <p>
     * ⚠️ <b>匿名连接下被抹掉身份的只有「进房」，关注与分享 100% 带 uid。</b>
     * 这一点很容易记反，而记反了会得出「匿名攒不到关注/分享样本」的错误结论。
     * <table border="1">
     *   <caption>同房同时段的登录态/匿名对照（{@code t3arch} 那对日志，2026-08-11）</caption>
     *   <tr><th>连接</th><th>进房</th><th>关注</th><th>分享</th><th>进房带 uid 比例</th></tr>
     *   <tr><td>匿名</td><td>232</td><td>0</td><td>0</td><td>10/232</td></tr>
     *   <tr><td>登录态</td><td>2117</td><td>4</td><td>1</td><td><b>100%</b></td></tr>
     * </table>
     * ⚠️ 最后一列<b>只说进房，且只是这一对日志里的一格</b>。
     * <b>全语料重算是 30132 帧匿名进房里 95 帧带 uid ＝ 0.32%</b>，大多数房是 0.00%，
     * 两个最大的房都是 0。此处一度写作「约 4%」并当成通则用，<b>那是把一格外推了，已作废</b>。
     * 上面那 2117 条进房样本正是取自登录态那一侧。
     * <p>
     * 平台还会把这两种互动折叠成 {@code DM_INTERACTION}
     * type=103/105（"N 人关注了主播" / "N 人分享了直播间"），
     * <b>聚合形态只有人数、没有 uid，也就不会走到这个方法</b>。
     * 2026-08-11/12 做过同一直播间、同一次分享的对照：
     * <b>登录态连接收到带 uid 的单播事件，匿名连接同一时刻只收到聚合卡片</b>。
     * <p>
     * ⚠️ <b>别把那次对照读成「聚合是平台给匿名连接的替代形态」</b>——它分不开两种解释。
     * 同房同窗的另一组对照显示<b>匿名每一类消息都只收到登录态的约 1/9</b>
     * （连服务端定时广播的 {@code WATCHED_CHANGE} 也是，逐分钟无一分钟为零，所以不是掉线）。
     * 在整条流被等比抽稀的前提下，「平台改发聚合」与「那条单播恰好没抽中」同样成立。
     * <b>这个 1/9 只有一房一窗，跨房未验，别当通用折扣率用。</b>
     * <p>
     * ⚠️ <b>已被证伪、别再重提</b>：「房间流量越高越倾向折叠」——
     * 被一个人气仅 1327、却只发聚合卡片的直播间推翻。
     * <p>
     * 排查时记住：日志里「这个房没人分享」与「这个房根本不单播分享」
     * <b>长得一模一样</b>，都是计数为 0。要看它出不出 {@code DM_INTERACTION}，
     * 不能看分享计数。
     * 对下播报告的影响见 {@link org.frostnova.nova.bilibili.model.BilibiliLiveMetric#FOLLOW_COUNT}。
     */
    private NovaBaseLiveEvent parseInteractV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "INTERACT_WORD_V2");
        if (meta == null) {
            return null;
        }

        byte[] payload = decodePayload(meta.getString("pb"), source, "INTERACT_WORD_V2");
        if (payload == null) {
            return null;
        }

        BilibiliProtobufReader message = BilibiliProtobufReader.parse(payload);
        if (message.isTruncated()) {
            // 报文读到一半就断了。已读到的字段仍然可用（uid 与 msg_type 都在开头），
            // 因此照常往下走，只留一行日志并记一笔缺字段——格式真的变了才有迹可循
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "INTERACT_WORD_V2:pb-truncated");
            log.debug("直播间 {} 的 INTERACT_WORD_V2 报文未能读完, 已按读到的 {} 个字段继续: {}",
                    source.getRoomId(), message.size(), meta.getString("pb"));
        }

        // 排在取值之前：字段表外的号出现与否，与这条报文最后出不出事件是两件事——
        // 取不到 msg_type 就返回的那条路上，恰恰最需要知道「报文里多了什么」
        noteUnknownFields("INTERACT_WORD_V2", message, INTERACT_V2_KNOWN_FIELDS);

        Long msgType = message.number(V2_MSG_TYPE);
        if (msgType == null) {
            // proto3 不序列化零值，取不到既可能是缺字段也可能是 msg_type=0。
            // 而 0 不在已知取值 1/2/3 里，两种情况都该丢弃。
            // 因此这里不记 FIELD_MISSING：记了会把「类型 0」算成缺字段。
            log.debug("直播间 {} 的 INTERACT_WORD_V2 消息取不到互动类型, 已忽略", source.getRoomId());
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfoV2(message.message(V2_UINFO), source);
        if (sender.getUid() == null) {
            sender.setUid(message.number(V2_UID));
        }
        if (sender.getUname() == null) {
            sender.setUname(message.string(V2_UNAME));
        }
        sender.setFansMedal(parseFansMedalV2(message.message(V2_FANS_MEDAL), source));

        // 字段 7 是秒级。事件模型内部统一用 Instant，换算成毫秒是事件输出协议的事，
        // 与醒目留言的 start_time / end_time 同一处理方式
        Instant timestamp = Optional.ofNullable(epochSecond(message.number(V2_TIMESTAMP))).orElseGet(Instant::now);

        return switch (msgType.intValue()) {
            case 1 -> {
                BilibiliEnterRoomEvent event = new BilibiliEnterRoomEvent(source, sender, timestamp);
                // 字段 10 与 13 只在 3/2122 条里出现，值分别为 1 与「流量包推广」，
                // 与 V1 的 is_spread / spread_desc 对得上。样本少，但取不到就是「没走推广位」，
                // 与 V1 对字段缺失的处理一致，不会造成假信息
                Long spread = message.number(V2_IS_SPREAD);
                event.setFromPromotion(spread != null && spread == 1L);
                event.setPromotionSource(Optional.ofNullable(message.string(V2_SPREAD_DESC))
                        .filter(desc -> !desc.isBlank()).orElse(null));
                yield event;
            }
            // 关注：10 条样本、跨 4 个直播间、10/10 带 uid，字段布局逐条一致。
            // 顶层字段集是进房的**子集**（13 个字段条条齐全，没有进房之外的新字段），
            // uid、昵称、时间戳与勋章都在与进房相同的字段号上。
            // 反向的差异也量过了：进房几乎必带的字段 24（32248/32249）在关注里 0/10，
            // 这是真实差异不是采样不足；另外 5 个进房独有字段本身就稀有（0.03%~1%），
            // n=10 说明不了它们，也不需要说明——本分支不依赖它们
            case 2 -> new BilibiliFollowEvent(source, sender, timestamp);
            // 分享：与关注同一套分支，2026-09-02 转正（此前按实验性对待，等的就是样本量）。
            // 依据是 2026-08-12～08-30 的生产语料：分享 13 条、跨 2 个直播间、13/13 带 uid，
            // 且分享出现过的字段全都在「关注 81 条 ∪ 进房 3726 条」出现过的字段里——
            // 一个「只在分享里出现」的字段都没有，那正是当初留标要防的那一格。
            // ⚠️ 这里有一处极容易读错，写下来免得下一个人再判一次：字段 16 在分享 13 条里
            // 只有 3 条带、关注 81 条一条没带，但进房 182/3726（4.88%）也带、值域是 {2,3}，
            // 所以它是这套报文共用的可选字段，本解析器的字段表根本不读它。
            // 「关注恰好没带某个可选字段」与「分享长出了新字段」，在读数上长得一模一样。
            case 3 -> new BilibiliShareEvent(source, sender, timestamp);
            default -> {
                log.debug("未处理的直播间互动消息类型: {}", msgType);
                noteNamed(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, "INTERACT_WORD_V2:msg_type=" + clipped(msgType));
                yield null;
            }
        };
    }

    /**
     * 取出并解码 protobuf 正文
     * <p>
     * 实测 INTERACT_WORD_V2 的 2122 条、SEND_GIFT_V2 的 2 条样本的 {@code pb} 全为
     * 标准 base64（字符集只含 {@code A-Za-z0-9+/=}），因此用标准解码器。
     * 解码失败只留日志不抛出：单条报文的编码出问题不该影响整个直播间。
     * @param cmd 消息类型，仅用于日志
     * @return 正文字节，字段缺失或解码失败时为空
     */
    private byte[] decodePayload(String base64, LiveStreamerInfo source, String cmd) {
        if (base64 == null || base64.isBlank()) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, cmd + ":pb");
            log.debug("直播间 {} 的 {} 消息没有 pb 字段, 已忽略", source.getRoomId(), cmd);
            return null;
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            noteNamed(BilibiliRiskMetrics.Kind.PARSE_FAILURE, cmd + ":pb-b64");
            log.debug("直播间 {} 的 {} 消息的 pb 不是合法 base64, 已忽略: {}", source.getRoomId(), cmd, base64);
            return null;
        }
    }

    /**
     * 从 protobuf 形式的 uinfo 构造发送者信息
     * <p>
     * 与 {@link #buildSenderFromUinfo} 是同一件事的两种承载形式，取值位置一一对应：
     * {@code uid} 在字段 1、昵称与头像在 {@code base}、财富等级在 {@code wealth}、
     * 大航海在勋章全量里。
     */
    private BilibiliUserInfo buildSenderFromUinfoV2(BilibiliProtobufReader uinfo, LiveStreamerInfo source) {
        if (uinfo == null) {
            return new BilibiliUserInfo();
        }

        Long uid = uinfo.number(V2_UID);
        BilibiliProtobufReader base = uinfo.message(V2_UINFO_BASE);

        String uname = base == null ? null : base.string(V2_BASE_NAME);
        String face = base == null ? null : base.string(V2_BASE_FACE);

        if (base == null && properties.getLive().isCompleteEvent() && uid != null) {
            uname = apiSupport.completeUname(uid, source).orElse(null);
            face = apiSupport.completeFace(uid, source).orElse(null);
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(uid, uname, face);
        sender.setGuard(parseGuardV2(uinfo));
        sender.setHonorLevel(Optional.ofNullable(uinfo.message(V2_UINFO_WEALTH))
                .map(wealth -> wealth.number(V2_LEVEL))
                .map(Long::intValue)
                .orElse(null));

        return sender;
    }

    /**
     * 解析 protobuf 形式的大航海信息
     * <p>
     * 同一条报文里大航海等级出现在<b>三处</b>：勋章全量的字段 11、{@code guard} 子消息的字段 1、
     * 以及顶层的字段 16。实抓的 29 条上舰观众消息里三处恒为同一个值。
     * <p>
     * 以勋章全量为主，与 {@link #buildSenderFromUinfo} 从 {@code medal} 取的先例一致，
     * 且只有那里同时带图标地址；勋章缺失时退到 {@code guard} 子消息——
     * 此时<b>只有等级没有图标</b>，宁可少一个图标也不要把上舰的人显示成普通观众。
     * @return 大航海信息，两处都取不到或等级为 0 时为空
     */
    private Guard parseGuardV2(BilibiliProtobufReader uinfo) {
        Long guardLevel = Optional.ofNullable(uinfo.message(V2_UINFO_MEDAL))
                .map(medal -> medal.number(V2_FULL_MEDAL_GUARD_LEVEL))
                .orElse(null);
        if (guardLevel != null && guardLevel != 0L) {
            String icon = Optional.ofNullable(uinfo.message(V2_UINFO_MEDAL))
                    .map(medal -> medal.string(V2_FULL_MEDAL_GUARD_ICON))
                    .orElse(null);
            return new Guard(guardLevel.intValue(), icon);
        }

        Long fallback = Optional.ofNullable(uinfo.message(V2_UINFO_GUARD))
                .map(guard -> guard.number(V2_LEVEL))
                .orElse(null);
        if (fallback == null || fallback == 0L) {
            return null;
        }

        return new Guard(fallback.intValue(), null);
    }

    /**
     * 解析 protobuf 形式的粉丝勋章
     * <p>
     * 与 {@link #parseObjectFansMedal} 对应，同样是「观众在本直播间的勋章」，
     * 没有勋章时平台下发一条<b>空的</b>子消息（实抓 859 条里有 6 条如此）而非省略字段，
     * 因此判据仍是所属主播 uid 取不到就当没有勋章。
     */
    private FansMedal parseFansMedalV2(BilibiliProtobufReader medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.number(V2_MEDAL_TARGET_UID);
        if (uid == null || uid == 0L) {
            return null;
        }

        Long level = medal.number(V2_MEDAL_LEVEL);
        Long lighted = medal.number(V2_MEDAL_LIGHTED);
        return buildFansMedal(uid, null,
                medal.number(V2_MEDAL_ROOM_ID),
                medal.string(V2_MEDAL_NAME),
                level == null ? null : level.intValue(),
                // proto3 省略零值：284/853 条带这个字段且恒为 1，其余是未点亮而非「没说」
                lighted != null && lighted == 1L,
                source);
    }

    /**
     * 解析礼物消息
     */
    private NovaBaseLiveEvent parseGift(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "SEND_GIFT");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(meta.getLong("uid"), meta.getString("uname"), meta.getString("face"));
        sender.setHonorLevel(meta.getInteger("wealth_level"));

        JSONObject medal = Optional.ofNullable(meta.getJSONObject("sender_uinfo"))
                .map(uinfo -> uinfo.getJSONObject("medal"))
                .orElse(null);
        sender.setFansMedal(parseMedalFansMedal(medal, source));
        sender.setGuard(parseGuard(medal));

        Instant timestamp = Optional.ofNullable(meta.getLong("timestamp")).map(Instant::ofEpochSecond).orElseGet(Instant::now);

        Integer count = meta.getInteger("num");
        GiftInfo gift = new GiftInfo(
                meta.getLong("giftId"),
                meta.getString("giftName"),
                toYuan(meta.getInteger("discount_price")),
                count,
                Optional.ofNullable(meta.getJSONObject("gift_info")).map(info -> info.getString("img_basic")).orElse(null)
        );

        // total_coin 惰性读取（lambda 不在此处求值）：银瓜子礼物不算实扣，
        // 早退前不该碰它——getInteger 对非数值串会抛，会把免费礼物整条吞掉
        return buildGiftEvent("SEND_GIFT", source, sender, gift, timestamp,
                meta.getString("coin_type"), () -> meta.getInteger("total_coin"),
                fromBag(meta), fromBlindGift(meta.getJSONObject("blind_gift")));
    }

    /**
     * 解析礼物消息的新版格式（{@code SEND_GIFT_V2}，正文为 protobuf）
     * <p>
     * 与 {@code SEND_GIFT} 是同一件事的两种格式，产出相同的事件，差别只在承载方式，
     * 字段号的来历与证据见 {@link #GIFT_V2_UID} 处的字段表。
     * <p>
     * <b>盲盒：</b>顶层 9 号子消息是投入的盒子（id／名／价对应 V1 {@code blind_gift}
     * 的 original_gift_*），礼物块（10 号）仍是开出物。没有 9 号时按普通礼物入账。
     * 34 号是表情特效，不是开出物名，见字段表。
     * <p>
     * <b>背包礼物：</b>V2 里 {@code bag_gift} 的对应字段未知（样本里没出现过），V2 的背包
     * 礼物暂时认不出来，实扣只能按 {@code total_coin} 照记。等样本。
     */
    private NovaBaseLiveEvent parseGiftV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "SEND_GIFT_V2");
        if (meta == null) {
            return null;
        }

        byte[] payload = decodePayload(meta.getString("pb"), source, "SEND_GIFT_V2");
        if (payload == null) {
            return null;
        }

        BilibiliProtobufReader message = BilibiliProtobufReader.parse(payload);
        if (message.isTruncated()) {
            // 与 INTERACT_WORD_V2 同一取舍：礼物块（字段 10）在报文前部，截断通常只伤到尾巴，
            // 已读到的部分照常入账，只留一行日志并记一笔缺字段
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "SEND_GIFT_V2:pb-truncated");
            log.debug("直播间 {} 的 SEND_GIFT_V2 报文未能读完, 已按读到的 {} 个字段继续: {}",
                    source.getRoomId(), message.size(), meta.getString("pb"));
        }

        // 同 INTERACT_WORD_V2：排在取值之前，礼物块都取不到的那条路上更需要这一笔
        noteUnknownFields("SEND_GIFT_V2", message, GIFT_V2_KNOWN_FIELDS);

        BilibiliProtobufReader gift = message.message(GIFT_V2_INFO);
        if (gift == null) {
            // 礼物块是这条消息的正主，连它都取不到就没有可入账的内容了
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "SEND_GIFT_V2:gift");
            log.debug("直播间 {} 的 SEND_GIFT_V2 消息取不到礼物块, 已忽略", source.getRoomId());
            return null;
        }

        // 34 号是表情特效子消息 {1:id, 2:type}（见字段表），与入账无关。记录只到 TRACE
        // 且只打取到的两个数：字段语义尚未被平台文档证实，打整块字节只会得到乱码
        BilibiliProtobufReader faceEffect = gift.message(GIFT_V2_FACE_EFFECT);
        Long faceEffectId = faceEffect == null ? null : faceEffect.number(GIFT_V2_FACE_EFFECT_ID);
        Long faceEffectType = faceEffect == null ? null : faceEffect.number(GIFT_V2_FACE_EFFECT_TYPE);
        if (log.isTraceEnabled() && (faceEffectId != null || faceEffectType != null)) {
            log.trace("直播间 {} 的 SEND_GIFT_V2 礼物带表情特效: id={}, type={}",
                    source.getRoomId(), faceEffectId, faceEffectType);
        }

        BilibiliProtobufReader uinfo = message.message(GIFT_V2_UINFO);
        BilibiliUserInfo sender = buildSenderFromUinfoV2(uinfo, source);
        // 礼物版 uinfo 的子布局与 INTERACT_WORD_V2 不通用（见字段表）：勋章字段号整套不同、
        // 财富等级在顶层 13 而不在 uinfo.4。上面的通用构造只可靠地取到了 uid 与昵称头像
        // （这些位置两边一致），勋章、大航海与财富等级按礼物自己的表重取
        sender.setGuard(parseGiftGuardV2(uinfo));
        sender.setFansMedal(parseGiftFansMedalV2(uinfo == null ? null : uinfo.message(GIFT_V2_UINFO_MEDAL), source));
        if (sender.getHonorLevel() == null) {
            sender.setHonorLevel(Optional.ofNullable(message.message(GIFT_V2_WEALTH))
                    .map(wealth -> wealth.number(V2_LEVEL))
                    .map(Long::intValue)
                    .orElse(null));
        }
        if (sender.getUid() == null) {
            sender.setUid(message.number(GIFT_V2_UID));
        }
        if (sender.getUname() == null) {
            sender.setUname(message.string(GIFT_V2_UNAME));
        }

        Instant timestamp = Optional.ofNullable(epochSecond(gift.number(GIFT_V2_TIMESTAMP))).orElseGet(Instant::now);

        GiftInfo giftInfo = new GiftInfo(
                gift.number(GIFT_V2_ID),
                gift.string(GIFT_V2_NAME),
                toYuan(intValue(gift.number(GIFT_V2_DISCOUNT_PRICE))),
                intValue(gift.number(GIFT_V2_NUM)),
                Optional.ofNullable(gift.message(GIFT_V2_GIFT_INFO)).map(info -> info.string(GIFT_V2_IMG_BASIC)).orElse(null)
        );

        return buildGiftEvent("SEND_GIFT_V2", source, sender, giftInfo, timestamp,
                gift.string(GIFT_V2_COIN_TYPE), () -> intValue(gift.number(GIFT_V2_TOTAL_COIN)),
                false, parseBlindV2(message));
    }

    /**
     * 礼物事件的公共收尾：按币种分免费与付费，盲盒另走随机礼物
     * <p>
     * V1（JSON）与 V2（protobuf）只是取值位置不同，取完之后的口径完全一致，收在这一处——
     * 两边各写一份的话，将来改口径（如实扣算法）很容易只改一边。
     * @param cmd 这条礼物消息的 cmd（{@code SEND_GIFT} 或 {@code SEND_GIFT_V2}），记账用，不归并
     * @param gift 礼物信息，数量从中取
     * @param coinType 货币类型
     * @param totalCoin 取实扣（千分之一元）的函数，平台没给时算出 null；惰性求值，见 {@link #chargedOf}
     * @param fromBag 是否来自背包；V2 认不出背包礼物，恒由调用方按事实传
     * @param blind 投入的盒子；V1 从 {@code blind_gift} 读出，V2 从顶层 9 号读出，没有则为 null
     * @return 礼物事件，币种不认识时为 null
     */
    private NovaBaseLiveEvent buildGiftEvent(String cmd, LiveStreamerInfo source, BilibiliUserInfo sender, GiftInfo gift,
                                                Instant timestamp, String coinType, Supplier<Integer> totalCoin,
                                                boolean fromBag, BlindBox blind) {
        Integer count = gift.getCount();

        if ("silver".equals(coinType)) {
            return new BilibiliFreeGiftEvent(source, sender, gift, timestamp);
        }

        if (!"gold".equals(coinType)) {
            log.debug("未处理的直播间礼物货币类型: {}", coinType);
            noteNamed(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, cmd + ":coin_type=" + clipped(coinType));
            return null;
        }

        if (blind == null) {
            Double value = gift.getPrice() == null || count == null ? null : gift.getPrice() * count;
            BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(source, sender, gift, value, timestamp);
            event.setCharged(chargedOf(totalCoin, fromBag, gift.getName(), value));
            event.setFromBag(fromBag);
            return event;
        }

        Long randomGiftId = blind.originalGiftId();
        GiftInfo randomGift = new GiftInfo(
                randomGiftId,
                blind.originalGiftName(),
                toYuan(blind.originalGiftPrice()),
                count,
                properties.getLive().isCompleteEvent() ? giftService.getGiftUrl(randomGiftId).orElse(null) : null
        );

        Double price = randomGift.getPrice() == null || count == null ? null : randomGift.getPrice() * count;
        Double value = gift.getPrice() == null || count == null ? null : gift.getPrice() * count;

        BilibiliRandomGiftEvent event = new BilibiliRandomGiftEvent(source, sender, randomGift, gift, price, value, timestamp);
        // 盲盒的实扣就是盲盒本身的价，与 total_coin 应当一致。以 total_coin 为准并在不一致时留下日志
        event.setCharged(chargedOf(totalCoin, fromBag, gift.getName(), price));
        event.setFromBag(fromBag);
        return event;
    }

    /**
     * 把 V1 {@code blind_gift} 对象收成 {@link BlindBox}。缺席时为 null，行为与原先直接传 JSONObject 一致
     */
    private BlindBox fromBlindGift(JSONObject blind) {
        if (blind == null) {
            return null;
        }
        return new BlindBox(
                blind.getLong("original_gift_id"),
                blind.getString("original_gift_name"),
                blind.getInteger("original_gift_price")
        );
    }

    /**
     * 从 SEND_GIFT_V2 顶层 9 号子消息读盲盒。没有 9 号时为 null，按普通礼物入账
     */
    private BlindBox parseBlindV2(BilibiliProtobufReader message) {
        BilibiliProtobufReader blind = message.message(GIFT_V2_BLIND);
        if (blind == null) {
            return null;
        }
        return new BlindBox(
                blind.number(GIFT_V2_BLIND_ORIGINAL_ID),
                blind.string(GIFT_V2_BLIND_ORIGINAL_NAME),
                intValue(blind.number(GIFT_V2_BLIND_ORIGINAL_PRICE))
        );
    }

    /**
     * 盲盒入账用的盒子：id／名／价（千分之一元）。V1、V2 取值位置不同，收成同一份再交给 {@link #buildGiftEvent}
     */
    private record BlindBox(Long originalGiftId, String originalGiftName, Integer originalGiftPrice) {
    }

    /**
     * 判断礼物是否来自背包
     * <p>
     * 判别字段是 {@code bag_gift}：背包礼物为一个对象，普通礼物为 {@code null}。
     * <b>不要拿金额去反推</b>——理由见 {@code NovaLiveGiftEvent.fromBag} 的契约说明。
     * @param meta 礼物消息体
     * @return 是否来自背包
     */
    private boolean fromBag(JSONObject meta) {
        return meta.getJSONObject("bag_gift") != null;
    }

    /**
     * 取观众为这一笔实际付出的金额
     * <p>
     * {@code total_coin} 是<b>服务端给出的实际扣除额</b>，比自己拿单价乘数量更可靠：
     * 打折与盲盒这些情形都体现在它身上，而单价字段体现不出来。
     * 已用一次真实的 ¥0.1 礼物核对过 {@code total_coin == discount_price × num}，
     * 又用一次真实的心动盲盒核对过 {@code total_coin} 跟的是盒子的价而非开出物的价。
     * <p>
     * <b>唯独背包礼物是例外，只能靠 {@code bag_gift} 认出来。</b>
     * 2026-08-06 实测两条背包礼物，{@code total_coin} 都<b>等于礼物原价而不是 0</b>
     * （小花花 100、人气票 100），照它记就会把白来的礼物算成观众的支出。
     * 判别字段是 {@code bag_gift}：背包礼物为一个对象，普通礼物为 {@code null}。
     * <p>
     * 字段缺失时回退到调用方算出的金额，<b>而不是当作 0</b>——
     * 把「取不到」记成「没花钱」会让营收凭空少一截，且不会有任何报错。
     * <p>
     * {@code totalCoin} 惰性求值：银瓜子早退在 {@link #buildGiftEvent}、背包早退在本方法，
     * 都先于取值——V1 的 {@code getInteger} 遇到非数值串会抛异常，提前取会把
     * 根本不算实扣的银瓜子礼物整条吞掉。取值时机与 V2 共用前的旧 V1 代码逐字一致。
     * @param totalCoin 取服务端实扣（千分之一元）的函数，没给时算出 null
     * @param fromBag 是否来自背包，判别方式见上
     * @param giftName 礼物名，仅用于日志
     * @param expected 字段缺失时的回退值
     * @return 实扣金额（元）
     */
    private Double chargedOf(Supplier<Integer> totalCoin, boolean fromBag, String giftName, Double expected) {
        if (fromBag) {
            // 背包礼物来自红包、活动或签到，观众没有为这一笔花钱。
            // 这里必须早于 total_coin 判断：它在背包礼物上给的是原价，不是扣除额
            return 0.0;
        }

        Integer coin = totalCoin.get();
        if (coin == null) {
            // 留空而不是填一个算出来的值：空表示「平台没告诉我们」，
            // 填上则表示「平台就是这么说的」。下游据此才能分辨
            // 「两个口径确实相等」与「取不到才回退成相等」，回退由消费方自己做
            return null;
        }

        double charged = coin / PRICE_UNIT;
        if (expected != null && Math.abs(charged - expected) > 0.001) {
            log.debug("礼物 {} 的实扣 {} 与按单价算出的 {} 不一致, 以实扣为准",
                    giftName, charged, expected);
        }
        return charged;
    }

    /**
     * 解析醒目留言消息
     */
    private NovaBaseLiveEvent parseSuperChat(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "SUPER_CHAT_MESSAGE");
        if (meta == null) {
            return null;
        }

        JSONObject senderInfo = meta.getJSONObject("uinfo");
        BilibiliUserInfo sender = buildSenderFromUinfo(senderInfo, source);
        if (senderInfo != null) {
            JSONObject medal = senderInfo.getJSONObject("medal");
            sender.setFansMedal(parseMedalFansMedal(medal, source));
            sender.setGuard(parseGuard(medal));
        }

        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli).orElseGet(Instant::now);

        BilibiliSuperChatEvent event = new BilibiliSuperChatEvent(source, sender, meta.getString("message"), meta.getDouble("price"), timestamp);

        // 字段名与量纲取自 2026-08-07 的实抓样本: time=60（秒）、start_time 与 end_time
        // 是秒级时间戳且相差正好等于 time、id 是醒目留言自己的编号。
        // 这里全部按「取不到就留空」处理——0 秒的 SC 与「不知道多久」是两件事
        event.setDurationSec(meta.getInteger("time"));
        event.setMessageId(meta.getLong("id"));
        event.setStartTime(epochSecond(meta.getLong("start_time")));
        event.setEndTime(epochSecond(meta.getLong("end_time")));

        return event;
    }

    /**
     * 秒级时间戳转时刻
     * @param seconds 秒级时间戳，为空或非正数时视为平台没给
     * @return 时刻，取不到时为空
     */
    private Instant epochSecond(Long seconds) {
        return seconds == null || seconds <= 0 ? null : Instant.ofEpochSecond(seconds);
    }

    /**
     * 解析红包消息（{@code POPULARITY_RED_POCKET_START} 与其 V2 形式）
     * <p>
     * <b>红包不给主播带来收益</b>，钱进的是红包，只有中奖者把奖品换成礼物送出主播才分成。
     * 因此产出的是 {@link BilibiliRedPocketEvent} 而非任何购买事件——
     * 详见 {@link org.frostnova.nova.core.event.live.common.RedPocketEvent} 的说明。
     * <p>
     * 只认「开启」这一条。中奖名单（{@code ..._WINNER_LIST}）暂不处理：
     * 它同样有 v1/V2 两种形式，而目前只抓到过两个不同 {@code lot_id} 的样本，
     * <b>无法证明同一个红包会不会同时下发两版</b>，贸然处理有重复计数的风险。
     * @param cmd 分发表里的真 cmd（{@code POPULARITY_RED_POCKET_START} 或其 V2），记账用，不归并
     * @param data 消息内容
     * @param source 主播信息
     * @return 红包事件，无法识别或属于重播时为空
     */
    private NovaBaseLiveEvent parseRedPocket(String cmd, JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, cmd);
        if (meta == null) {
            return null;
        }

        Object lotId = meta.get("lot_id");
        if (lotId == null) {
            // 认不出是哪个红包就没法挡重播。按本项目一贯的取舍，宁可漏播一次也不要反复感谢
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, cmd + ":lot_id");
            log.debug("红包消息缺少 lot_id, 已忽略");
            return null;
        }

        Instant now = Instant.now();
        if (seenRedPockets.putIfAbsent(String.valueOf(lotId), now) != null) {
            log.debug("红包开启消息重播, 已忽略: lot={}", lotId);
            return null;
        }
        sweepRedPockets(now);

        // V2 形式的发送者字段位置未实测过。按 USER_TOAST_MSG_V2 的先例，
        // 新格式会把人塞进 sender_uinfo，所以优先读它，读不到再退回平铺字段
        JSONObject uinfo = meta.getJSONObject("sender_uinfo");
        JSONObject base = uinfo == null ? null : uinfo.getJSONObject("base");
        Long uid = Optional.ofNullable(uinfo).map(info -> info.getLong("uid")).orElseGet(() -> meta.getLong("sender_uid"));
        String uname = Optional.ofNullable(base).map(info -> info.getString("name")).orElseGet(() -> meta.getString("sender_name"));
        String face = Optional.ofNullable(base).map(info -> info.getString("face")).orElseGet(() -> meta.getString("sender_face"));
        if (uid == null && uname == null) {
            // 连是谁发的都取不到，这条就没有播报价值了。留一行日志，格式变了才有迹可循
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, cmd + ":sender");
            log.debug("红包消息认不出发送者, 已忽略: lot={}", lotId);
            return null;
        }

        // 用红包自己的开始时刻而不是收到消息的时刻：首次见到的可能已经是重播
        Instant startedAt = Optional.ofNullable(meta.getLong("start_time")).map(Instant::ofEpochSecond).orElse(now);

        BilibiliRedPocketEvent event = new BilibiliRedPocketEvent(source, new BilibiliUserInfo(uid, uname, face), startedAt);
        event.setLotteryId(String.valueOf(lotId));
        // total_price 与礼物价格同单位（千分之一元），已用真实账单核对：
        // 一笔 total_price=2000 的红包，发红包的人实际支出 20 电池即 ¥2.00
        event.setCost(Optional.ofNullable(meta.getInteger("total_price")).map(price -> price / PRICE_UNIT).orElse(null));

        JSONArray awards = meta.getJSONArray("awards");
        if (awards != null && !awards.isEmpty()) {
            JSONObject award = awards.getJSONObject(0);
            if (award != null) {
                event.setAwardName(award.getString("gift_name"));
                event.setAwardCount(award.getInteger("num"));
            }
        }
        return event;
    }

    /**
     * 清掉超出保留期的红包记录
     */
    private void sweepRedPockets(Instant now) {
        if (seenRedPockets.size() >= MAX_SEEN_RED_POCKETS) {
            seenRedPockets.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(RED_POCKET_RETENTION) > 0);
        }
    }

    /**
     * 解析大航海消息（{@code USER_TOAST_MSG}）
     * <p>
     * 这条带的 {@code price} 是<b>实际成交价</b>，与 {@code GUARD_BUY} 的挂牌价不是一回事，
     * 取舍见 {@link BilibiliGuardReconciler}。
     */
    private NovaBaseLiveEvent parseGuard(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "USER_TOAST_MSG");
        if (meta == null) {
            return null;
        }

        Integer guardLevel = meta.getInteger("guard_level");
        if (guardLevel == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "USER_TOAST_MSG:guard_level");
            return null;
        }

        Long senderUid = meta.getLong("uid");
        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli).orElseGet(Instant::now);

        if (!guardReconciler.acceptToast(meta.getString("payflow_id"), senderUid, guardLevel, timestamp)) {
            return null;
        }

        return buildGuardEvent("USER_TOAST_MSG", source, senderUid, meta.getString("username"), meta.getString("role_name"),
                guardLevel, toYuan(meta.getInteger("price")), meta.getInteger("num"), meta.getString("unit"),
                GuardOperateType.of(Optional.ofNullable(meta.getInteger("op_type")).orElse(-1)),
                companionDaysOf(meta.getString("toast_msg")), timestamp);
    }

    /**
     * 解析大航海消息的新版格式（{@code USER_TOAST_MSG_V2}）
     * <p>
     * 与 {@code USER_TOAST_MSG} 是同一件事的两种格式，字段位置不同：开通者在 {@code sender_uinfo}，
     * 等级与操作类型在 {@code guard_info}，金额在 {@code pay_info}。
     * <p>
     * <b>两种格式都要收。</b>2026-08-06 抓的 49 笔上舰里有 7 笔只以 V2 形式下发、
     * 且没有 {@code GUARD_BUY} 兜底——只认老格式就会让这 14% 完全消失，而且不会有任何报错。
     * 重复的那部分靠 {@code payflow_id} 去重。
     */
    private NovaBaseLiveEvent parseGuardV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "USER_TOAST_MSG_V2");
        if (meta == null) {
            return null;
        }

        JSONObject guardInfo = meta.getJSONObject("guard_info");
        JSONObject payInfo = meta.getJSONObject("pay_info");
        if (guardInfo == null || payInfo == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "USER_TOAST_MSG_V2:guard_info|pay_info");
            return null;
        }

        Integer guardLevel = guardInfo.getInteger("guard_level");
        if (guardLevel == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "USER_TOAST_MSG_V2:guard_level");
            return null;
        }

        JSONObject senderInfo = meta.getJSONObject("sender_uinfo");
        Long senderUid = senderInfo == null ? null : senderInfo.getLong("uid");
        JSONObject base = senderInfo == null ? null : senderInfo.getJSONObject("base");

        Instant timestamp = Optional.ofNullable(data.getLong("send_time")).map(Instant::ofEpochMilli)
                .or(() -> Optional.ofNullable(guardInfo.getLong("start_time")).map(Instant::ofEpochSecond))
                .orElseGet(Instant::now);

        if (!guardReconciler.acceptToast(payInfo.getString("payflow_id"), senderUid, guardLevel, timestamp)) {
            return null;
        }

        return buildGuardEvent("USER_TOAST_MSG_V2", source, senderUid, base == null ? null : base.getString("name"),
                guardInfo.getString("role_name"), guardLevel, toYuan(payInfo.getInteger("price")),
                payInfo.getInteger("num"), payInfo.getString("unit"),
                GuardOperateType.of(Optional.ofNullable(guardInfo.getInteger("op_type")).orElse(-1)),
                companionDaysOf(meta.getString("toast_msg")), timestamp);
    }

    /**
     * 解析大航海开通消息（{@code GUARD_BUY}）
     * <p>
     * <b>解析出的事件不在这里返回，而是交给 {@link BilibiliGuardReconciler} 压住等 toast。</b>
     * 这条的 {@code price} 是挂牌价（35 个样本里舰长恒为 198000），toast 的才是实际成交价；
     * 而这条又恒定先到，不压住就必然取到挂牌价，实测高估 15.4%。
     * <p>
     * 字段也更少：实测 35 条<b>全都没有 {@code unit}</b>，且 {@code start_time == end_time}，
     * 所以 {@link #unitOf} 的两条路都走不通，单位只能是空——这也是宁可等 toast 的理由之一。
     */
    private NovaBaseLiveEvent parseGuardBuy(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "GUARD_BUY");
        if (meta == null) {
            return null;
        }

        Integer guardLevel = meta.getInteger("guard_level");
        if (guardLevel == null) {
            noteNamed(BilibiliRiskMetrics.Kind.FIELD_MISSING, "GUARD_BUY:guard_level");
            return null;
        }

        Long senderUid = meta.getLong("uid");
        Integer count = meta.getInteger("num");
        Double price = toYuan(meta.getInteger("price"));
        Instant timestamp = Optional.ofNullable(meta.getLong("start_time"))
                .map(Instant::ofEpochSecond).orElseGet(Instant::now);

        // 单价还是总价？至今 35 个样本全是 num=1，区分不出来。多买时把三个数一起记下来，
        // 首次出现就能人工核对——按单价处理而实际是总价的话，多月开通会被乘重
        if (count != null && count > 1) {
            log.info("大航海开通数量大于 1, 请核对价格口径: price={} num={} 按单价算得 {} 元",
                    meta.getInteger("price"), count, price == null ? null : price * count);
        }

        guardReconciler.holdGuardBuy(senderUid, guardLevel, timestamp,
                buildGuardEvent("GUARD_BUY", source, senderUid, meta.getString("username"), meta.getString("gift_name"),
                        guardLevel, price, count, unitOf(meta), GuardOperateType.UNKNOWN, null, timestamp));
        return null;
    }

    /**
     * 从播报文案里取陪伴天数
     * <p>
     * 平台没有给这个字段，只把它写进 {@code toast_msg}，如
     * 「&lt;%某人%&gt; 在主播某某的直播间开通了舰长，今天是TA陪伴主播的第1171天」。
     * <p>
     * <b>这是在解析文案，不是解析字段，随时可能因为改版而失效。</b>
     * 因此取不到就返回空，<b>绝不返回 0</b>——「陪伴 0 天」会变成假信息出现在感谢文案里。
     * 同理超出常理的值也当作没取到：正则一旦匹配错位置，宁可丢掉也不要拿去展示。
     * @return 陪伴天数，解析不出或不合常理时为空
     */
    private Integer companionDaysOf(String toastMsg) {
        if (toastMsg == null || toastMsg.isBlank()) {
            return null;
        }

        Matcher matcher = COMPANION_DAYS.matcher(toastMsg);
        if (!matcher.find()) {
            return null;
        }

        try {
            int days = Integer.parseInt(matcher.group(1));
            // 上限按平台自身年龄留足余量。超出说明多半匹配到了别的数字
            return days > 0 && days <= MAX_COMPANION_DAYS ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按等级组装大航海事件
     * <p>
     * 三条播报消息（{@code GUARD_BUY}、{@code USER_TOAST_MSG}、{@code USER_TOAST_MSG_V2}）
     * 字段位置各不相同，取值的差异留在各自的解析方法里，这里只负责组装。
     * @param cmd 这条大航海消息的 cmd（{@code USER_TOAST_MSG}／{@code USER_TOAST_MSG_V2}／{@code GUARD_BUY}），记账用，不归并
     * @param iconName 用于查图标的名称，各消息取自不同字段
     * @param companionDays 陪伴天数，{@code GUARD_BUY} 没有文案可解析，传空
     * @return 等级不认识时返回 null
     */
    private NovaBaseLiveEvent buildGuardEvent(String cmd, LiveStreamerInfo source, Long senderUid, String username,
                                                 String iconName, Integer guardLevel, Double price, Integer count,
                                                 String unit, GuardOperateType operateType, Integer companionDays,
                                                 Instant timestamp) {
        boolean complete = properties.getLive().isCompleteEvent();
        BilibiliUserInfo sender = new BilibiliUserInfo(
                senderUid,
                username,
                complete ? apiSupport.completeFace(senderUid, source).orElse(null) : null
        );
        sender.setGuard(new Guard(guardLevel, complete ? giftService.getGuardIcon(iconName).orElse(null) : null));

        return switch (guardLevel) {
            case 1 -> {
                BilibiliGovernorEvent event = new BilibiliGovernorEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            case 2 -> {
                BilibiliCommanderEvent event = new BilibiliCommanderEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            case 3 -> {
                BilibiliCaptainEvent event = new BilibiliCaptainEvent(source, sender, price, count, unit, timestamp);
                event.setOperateType(operateType);
                event.setCompanionDays(companionDays);
                yield event;
            }
            default -> {
                log.debug("未处理的直播间大航海类型: {}", guardLevel);
                noteNamed(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, cmd + ":guard_level=" + clipped(guardLevel));
                yield null;
            }
        };
    }

    /**
     * 取出大航海开通时长的单位
     * <p>
     * 先认消息自己给的 {@code unit}。{@code GUARD_BUY} 到底带不带这个字段，我们没有实测过
     * ——曾有过「不带」的说法，但那是把「没记录」当成了「不存在」，已被撤回。所以这里不预设，
     * 有就用，只在没有时才回退到起止时刻。
     * <p>
     * 回退时按天数归类而不是精确换算：大航海只按月/年售卖，而月长本就有 28~31 天的浮动，
     * 硬算会得出「1.03 个月」这种东西。
     * @param meta 消息内容
     * @return 「月」或「年」，推不出来时为 null 而不是猜一个
     */
    private String unitOf(JSONObject meta) {
        String declared = meta.getString("unit");
        if (declared != null && !declared.isBlank()) {
            return declared;
        }

        Long start = meta.getLong("start_time");
        Long end = meta.getLong("end_time");
        if (start == null || end == null || end <= start) {
            return null;
        }

        long days = (end - start) / 86400;
        if (days >= 300) {
            return "年";
        }
        return days >= 20 ? "月" : null;
    }

    /**
     * 解析点赞消息
     */
    private NovaBaseLiveEvent parseLike(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "LIKE_INFO_V3_CLICK");
        if (meta == null) {
            return null;
        }

        BilibiliUserInfo sender = buildSenderFromUinfo(meta.getJSONObject("uinfo"), source);
        if (sender.getUid() == null) {
            sender.setUid(meta.getLong("uid"));
        }
        if (sender.getUname() == null) {
            sender.setUname(meta.getString("uname"));
        }
        sender.setFansMedal(parseObjectFansMedal(meta.getJSONObject("fans_medal"), source));

        return new BilibiliLikeEvent(source, sender);
    }

    /**
     * 解析点赞数更新消息
     */
    private NovaBaseLiveEvent parseLikeUpdate(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "LIKE_INFO_V3_UPDATE");
        if (meta == null) {
            return null;
        }

        return new BilibiliLikeUpdateEvent(source, meta.getInteger("click_count"));
    }

    /**
     * 解析看过人数更新消息
     */
    private NovaBaseLiveEvent parseWatchedUpdate(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "WATCHED_CHANGE");
        if (meta == null) {
            return null;
        }

        return new BilibiliWatchedUpdateEvent(source, meta.getInteger("num"), meta.getString("text_large"));
    }

    /**
     * 解析高能用户数更新消息
     * <p>
     * {@code online_count} 与 {@code count_text} 只在部分版本的消息里出现，
     * 取不到时为空即可——这两项都只是展示用，缺了不影响 {@code count} 这个正主。
     */
    private NovaBaseLiveEvent parseOnlineRankCount(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "ONLINE_RANK_COUNT");
        if (meta == null) {
            return null;
        }

        return new BilibiliOnlineRankCountUpdateEvent(source,
                meta.getInteger("count"), meta.getInteger("online_count"), meta.getString("count_text"));
    }

    /**
     * 解析直播间标题与分区变更消息
     */
    private NovaBaseLiveEvent parseRoomInfoChange(JSONObject data, LiveStreamerInfo source) {
        JSONObject meta = requireData(data, "ROOM_CHANGE");
        if (meta == null) {
            return null;
        }

        return new BilibiliRoomInfoChangeEvent(source,
                meta.getString("title"),
                meta.getString("parent_area_name"),
                meta.getString("area_name"));
    }

    /**
     * 解析违规警告消息
     */
    private NovaBaseLiveEvent parseWarning(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliLiveWarningEvent(source, data.getString("msg"));
    }

    /**
     * 解析直播流被切断消息
     */
    private NovaBaseLiveEvent parseCutOff(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliCutOffEvent(source, data.getString("msg"));
    }

    /**
     * 解析直播间封禁消息
     * <p>
     * 该消息只给解封时刻、不给理由，与警告和切流的字段结构不同。
     */
    private NovaBaseLiveEvent parseRoomLock(JSONObject data, LiveStreamerInfo source) {
        return new BilibiliRoomLockEvent(source, data.getString("msg"), parseShanghaiTime(data.getString("expire")));
    }

    /**
     * 解析接口以东八区本地时间给出的时刻
     * <p>
     * 形如 {@code 2019-06-30 03:57:04}，不带时区。<b>解析失败返回 null 而不是当前时刻</b>：
     * 用「现在」冒充解封时间会让告警声称直播间已经解封。
     * @param text 时间文本
     * @return 对应时刻，缺失或无法解析时为 null
     */
    private Instant parseShanghaiTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(text.strip().replace(' ', 'T'))
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant();
        } catch (Exception e) {
            log.debug("无法解析时间文本 {}", text);
            return null;
        }
    }

    /**
     * 从通用的 uinfo 结构构造发送者信息
     */
    private BilibiliUserInfo buildSenderFromUinfo(JSONObject uinfo, LiveStreamerInfo source) {
        if (uinfo == null) {
            return new BilibiliUserInfo();
        }

        Long uid = uinfo.getLong("uid");
        JSONObject base = uinfo.getJSONObject("base");

        String uname = base == null ? null : base.getString("name");
        String face = base == null ? null : base.getString("face");

        if (base == null && properties.getLive().isCompleteEvent() && uid != null) {
            uname = apiSupport.completeUname(uid, source).orElse(null);
            face = apiSupport.completeFace(uid, source).orElse(null);
        }

        BilibiliUserInfo sender = new BilibiliUserInfo(uid, uname, face);
        sender.setGuard(parseGuard(uinfo.getJSONObject("medal")));
        sender.setHonorLevel(Optional.ofNullable(uinfo.getJSONObject("wealth")).map(wealth -> wealth.getInteger("level")).orElse(null));

        return sender;
    }

    /**
     * 解析大航海信息
     */
    private Guard parseGuard(JSONObject medal) {
        if (medal == null) {
            return null;
        }

        Integer guardLevel = medal.getInteger("guard_level");
        if (guardLevel == null || guardLevel == 0) {
            return null;
        }

        return new Guard(guardLevel, medal.getString("guard_icon"));
    }

    /**
     * 解析礼物消息（V2）里的大航海信息
     * <p>
     * 礼物版勋章（uinfo 子字段 3）里疑似大航海等级在 7、图标在 8——按 V1 的
     * {@code medal.guard_level} / {@code guard_icon} 类推的位置。实抓的 2 条样本里送礼者
     * 都不是大航海成员，这两个字段一次都没出现过，<b>未经样本证实</b>；取不到就返回 null，
     * 与「没有大航海」一致，不会造假信息。
     * @param uinfo 观众完整信息，缺失时为 null
     * @return 大航海信息，等级取不到或为 0 时为空
     */
    private Guard parseGiftGuardV2(BilibiliProtobufReader uinfo) {
        if (uinfo == null) {
            return null;
        }

        Long guardLevel = Optional.ofNullable(uinfo.message(GIFT_V2_UINFO_MEDAL))
                .map(medal -> medal.number(GIFT_V2_MEDAL_GUARD_LEVEL))
                .orElse(null);
        if (guardLevel == null || guardLevel == 0L) {
            return null;
        }

        String icon = Optional.ofNullable(uinfo.message(GIFT_V2_UINFO_MEDAL))
                .map(medal -> medal.string(GIFT_V2_MEDAL_GUARD_ICON))
                .orElse(null);
        return new Guard(guardLevel.intValue(), icon);
    }

    /**
     * 解析礼物消息（V2）里的粉丝勋章
     * <p>
     * ⚠️ 布局与 {@link #parseFansMedalV2}（INTERACT_WORD_V2）<b>不通用</b>：那条的
     * 勋章名在子字段 3、所属主播在 1、是否点亮在 8；这条的勋章名在 1、等级在 2、
     * 所属主播在 10、是否点亮在 9。两份表套错会把颜色值或主播 uid 读到错的字段上，
     * 因此单独一套方法。判据与 V1 的 {@link #parseMedalFansMedal} 一致：
     * 所属主播 uid 取不到就当没有勋章。
     */
    private FansMedal parseGiftFansMedalV2(BilibiliProtobufReader medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.number(GIFT_V2_MEDAL_TARGET_UID);
        if (uid == null || uid == 0L) {
            return null;
        }

        Long level = medal.number(GIFT_V2_MEDAL_LEVEL);
        Long lighted = medal.number(GIFT_V2_MEDAL_LIGHTED);
        return buildFansMedal(uid, null, null,
                medal.string(GIFT_V2_MEDAL_NAME),
                level == null ? null : level.intValue(),
                // proto3 省略零值：2 条样本一条带 9=1、一条没有，后者是未点亮而非「没说」
                lighted != null && lighted == 1L,
                source);
    }

    /**
     * 解析弹幕消息中以定长数组形式给出的粉丝勋章
     */
    private FansMedal parseArrayFansMedal(JSONArray medal, LiveStreamerInfo source) {
        if (medal == null || medal.size() < 13) {
            return null;
        }

        Long uid = medal.getLong(12);
        return buildFansMedal(uid, medal.getString(2), medal.getLong(3), medal.getString(1), medal.getInteger(0), isOne(medal.getInteger(11)), source);
    }

    /**
     * 解析以 fans_medal 对象形式给出的粉丝勋章
     */
    private FansMedal parseObjectFansMedal(JSONObject medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.getLong("target_id");
        if (uid == null || uid == 0L) {
            return null;
        }

        return buildFansMedal(uid, null, medal.getLong("anchor_roomid"), medal.getString("medal_name"),
                medal.getInteger("medal_level"), isOne(medal.getInteger("is_lighted")), source);
    }

    /**
     * 解析以 medal 对象形式给出的粉丝勋章
     */
    private FansMedal parseMedalFansMedal(JSONObject medal, LiveStreamerInfo source) {
        if (medal == null) {
            return null;
        }

        Long uid = medal.getLong("ruid");
        if (uid == null || uid == 0L) {
            return null;
        }

        return buildFansMedal(uid, null, null, medal.getString("name"), medal.getInteger("level"), isOne(medal.getInteger("is_light")), source);
    }

    /**
     * 构造粉丝勋章，按配置决定是否补全缺失的主播信息
     */
    private FansMedal buildFansMedal(Long uid, String uname, Long roomId, String name, Integer level, Boolean lighted, LiveStreamerInfo source) {
        if (!properties.getLive().isCompleteEvent()) {
            return new FansMedal(uid, uname, roomId, name, level, lighted);
        }

        return new FansMedal(
                uid,
                uname != null ? uname : apiSupport.completeUname(uid, source).orElse(null),
                roomId != null ? roomId : apiSupport.completeRoomId(uid, source).orElse(null),
                apiSupport.completeFace(uid, source).orElse(null),
                name, level, lighted
        );
    }

    /**
     * 将以千分之一元为单位的价格换算为元
     */
    private Double toYuan(Integer price) {
        return price == null ? null : price / PRICE_UNIT;
    }

    /**
     * protobuf 读出的整数收窄为事件模型用的 Integer，取不到时为 null
     */
    private Integer intValue(Long value) {
        return value == null ? null : value.intValue();
    }

    /**
     * 判断整数标志位是否为 1，兼容字段缺失的情况
     */
    private boolean isOne(Integer value) {
        return value != null && value == 1;
    }

    /**
     * 按下标安全取出子数组
     * <p>
     * 弹幕消息以定长数组承载各类信息，其长度会随版本变化，越界时直接返回空而非抛出异常。
     * @param array 数组
     * @param index 下标
     * @return 子数组，越界或类型不符时返回 null
     */
    private JSONArray arrayAt(JSONArray array, int index) {
        if (array == null || index < 0 || index >= array.size()) {
            return null;
        }

        return array.get(index) instanceof JSONArray nested ? nested : null;
    }
}
