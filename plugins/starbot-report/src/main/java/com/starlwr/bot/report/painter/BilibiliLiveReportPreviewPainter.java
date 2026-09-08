package com.starlwr.bot.report.painter;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.report.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.report.util.FontUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 报告版式预览：用夹具数据出一张图，给「报告长什么样」那一屏做所见即所得
 *
 * <h2>为什么不拿真数据画</h2>
 * 版式开关要当场看到效果，而「当场」这两个字排除了真数据：使用者第一次配推送时
 * 一场直播都还没采过，报告是空的；配到第十次时又不该为了看一眼版式去翻某位主播的历史场次。
 * 夹具数据是<b>每一块都有内容</b>的一场，于是每一个开关的开与关都看得出差别。
 *
 * <h2>🔴 预览不联网</h2>
 * 画一张真报告要向 B 站取封面、头像、粉丝数；预览<b>一个都不取</b>——
 * 慢、可能触发风控，而且夹具里那个 uid 不是真人，拿它去查等于对着一个不存在的人打接口。
 * 落法是覆写父类「向外部要资料的口子」那一段里的每一个方法（见该段说明），
 * 用本地画出来的占位图与夹具数字顶上。
 * <p>
 * 🔴 <b>父类往那一段里加了新口子而这里忘了覆写时，预览会安静地联网</b>——
 * 一次真去打了接口的预览，和一次用夹具画出来的预览，在图上长得一样。
 * 这一条不指望自觉：{@code BilibiliLiveReportPreviewPainterTest} 断言整趟预览与接口<b>零交互</b>。
 *
 * <h2>时刻写死</h2>
 * 夹具的开播时刻是个常量，于是同一套版式参数两次预览出的字节相同。
 * 取「现在」会让预览每分钟都变一点，而<b>一张每次都不一样的预览，没法拿来比较两套版式</b>。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveReportPreviewPainter extends BilibiliLiveReportPainter {
    /**
     * 夹具主播的 uid 与房间号
     * <p>
     * ⚠️ 一律取<b>保留段假值</b>：位数远超平台真实取值范围，与任何真人天然不重叠。
     * 拿真实身份当夹具正是 2026-08-12 那次泄漏的成因（「照真实场景写最省事」）。
     */
    private static final long PREVIEW_UID = 19604318752096L;

    private static final long PREVIEW_ROOM_ID = 47615208934771L;

    /**
     * 夹具观众的 uid，同样取保留段假值
     */
    private static final long[] PREVIEW_VIEWERS = {
            19338207415562L, 19782064139308L, 19056473928140L, 19410385726914L, 19625049183377L};

    private static final String[] PREVIEW_VIEWER_NAMES = {"观众甲", "观众乙", "观众丙", "观众丁", "观众戊"};

    /**
     * 夹具的开播时刻，写死以保证预览可重复
     */
    private static final long PREVIEW_START = 1_700_000_000_000L;

    /**
     * 夹具场次时长：2 小时 8 分，够曲线画出形状，又不至于把图拉得太长
     */
    private static final long PREVIEW_DURATION_MILLIS = 2 * 3600_000L + 8 * 60_000L;

    private static final LiveStreamerInfo PREVIEW_STREAMER =
            new LiveStreamerInfo(PREVIEW_UID, "示例主播", PREVIEW_ROOM_ID, "preview-face");

    private final BufferedImage banner;

    private final BufferedImage face;

    private final BufferedImage rankingFace;

    @Autowired
    public BilibiliLiveReportPreviewPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api,
                                            FontUtil fontUtil, NovaBilibiliProperties properties,
                                            LiveRoomInfoHistory roomInfoHistory) {
        super(factory, api, fixtureData(), fontUtil, properties, roomInfoHistory);

        // 占位图与历史场次重画那一支共用一份：两处的尺寸必须一致，
        // 各画各的话，版式改宽的那天会变成两张对不齐的图，而图上看不出哪张是错的
        this.banner = PainterPlaceholder.banner();
        this.face = PainterPlaceholder.face();
        this.rankingFace = PainterPlaceholder.rankingFace();
    }

    /**
     * 按指定版式画一张预览图
     * @param options 版式选项
     * @return PNG 字节，画不出来时为空
     */
    public Optional<byte[]> render(BilibiliLiveReportOptions options) {
        return paint(BilibiliPlatform.BILIBILI.id(), PREVIEW_STREAMER, options)
                .map(base64 -> Base64.getDecoder().decode(base64));
    }

    // ================ 向外部要资料的口子：一律用本地夹具顶上 ================

    @Override
    protected BufferedImage loadCover(LiveStreamerInfo source) {
        return banner;
    }

    @Override
    protected BufferedImage faceImage(LiveStreamerInfo source) {
        return face;
    }

    @Override
    protected BufferedImage avatar(String url) {
        return rankingFace;
    }

    @Override
    protected Optional<Long> fansCount(Long uid) {
        return Optional.of(12_480L);
    }

    @Override
    protected Optional<Integer> fansMedalCount(Long uid) {
        return Optional.of(3_260);
    }

    @Override
    protected Optional<Integer> guardCount(Long roomId, Long uid) {
        return Optional.of(38);
    }

    @Override
    protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
        return Optional.of(List.of(
                new GuardMember(PREVIEW_VIEWERS[0], PREVIEW_VIEWER_NAMES[0], 1, 3000),
                new GuardMember(PREVIEW_VIEWERS[1], PREVIEW_VIEWER_NAMES[1], 2, 2000),
                new GuardMember(PREVIEW_VIEWERS[2], PREVIEW_VIEWER_NAMES[2], 3, 1000)));
    }

    @Override
    protected List<RoomInfoSnapshot> titleHistory(String platform, Long uid) {
        // 至少两条才画得出这一块：首条是开播时的初始标题，不算一次改动
        return List.of(
                new RoomInfoSnapshot(PREVIEW_START, "示例直播间标题", "虚拟主播"),
                new RoomInfoSnapshot(PREVIEW_START + 42 * 60_000L, "改过一次的标题", "虚拟主播"));
    }

    /**
     * 夹具场次：每一块版式都填上数，于是每个开关的开与关都看得出差别
     * <p>
     * 数字挑得像一场中等规模的直播，而不是取整——整数会让人以为这是某种上限或默认值。
     */
    private static LiveDataService fixtureData() {
        // 🔴 这一份**必须**关掉落盘。默认直播数据服务的默认值是「存，存到 data.json」，
        //    而那正是真数据所在的那个文件——夹具一旦写进去，一场真直播的数据就被这些
        //    编出来的数字盖掉了，且不会有任何地方报错。
        //    它今天不写盘还有第二个理由（不是 Spring 管的 bean，收不到 ApplicationReady
        //    与 ContextClosed 两个事件，两个写口都不会被触发），但那是**别人的实现细节**：
        //    🔴 哪天有人把它注册成 bean，这里就成了一颗定时炸弹。**把开关关死，
        //    比指望「它碰巧收不到那两个事件」牢靠。**
        NovaCoreProperties fixtureProperties = new NovaCoreProperties();
        fixtureProperties.getLive().setSaveLiveData(false);

        DefaultLiveDataService data = new DefaultLiveDataService(fixtureProperties);
        String platform = BilibiliPlatform.BILIBILI.id();
        long end = PREVIEW_START + PREVIEW_DURATION_MILLIS;

        data.setLiveStartTime(platform, PREVIEW_UID, PREVIEW_START);
        data.setLiveEndTime(platform, PREVIEW_UID, end);

        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.DANMU_COUNT, 1_384);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GIFT_VALUE, 268.4);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GIFT_PAID, 268.4);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.FREE_GIFT_COUNT, 412);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.SUPER_CHAT_COUNT, 7);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.SUPER_CHAT_VALUE, 318);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.BOX_COUNT, 46);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.BOX_PROFIT, -37.5);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.CAPTAIN_COUNT, 3);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.COMMANDER_COUNT, 1);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GUARD_VALUE, 1_386);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.FOLLOW_COUNT, 96);
        data.incrementLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.SHARE_COUNT, 24);
        data.maxLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.LIKE_TOTAL, 2_573);
        data.maxLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.WATCHED_COUNT, 8_642);
        data.maxLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.ONLINE_RANK_COUNT, 137);
        data.maxLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.ONLINE_COUNT, 1_186);

        // 开播那一刻的快照，「本场变化」拿它与上面那三个现值相减
        data.setLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.FANS_AT_START, 12_314);
        data.setLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.FANS_MEDAL_AT_START, 3_209);
        data.setLiveMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GUARD_AT_START, 34);

        fixtureUsers(data, platform);
        fixtureSeries(data, platform, end);
        fixtureWords(data, platform);
        return data;
    }

    /**
     * 各张排行榜的参与者
     */
    private static void fixtureUsers(DefaultLiveDataService data, String platform) {
        double[] danmu = {186, 142, 97, 63, 41};
        double[] gift = {88.8, 52.4, 30.0, 18.6, 9.9};
        double[] superChat = {100, 66, 30};
        double[] box = {18, 12, 9};
        double[] boxProfit = {24.5, -6.2, -18.4};

        for (int i = 0; i < PREVIEW_VIEWERS.length; i++) {
            long viewer = PREVIEW_VIEWERS[i];
            data.recordLiveUserName(platform, PREVIEW_UID, viewer, PREVIEW_VIEWER_NAMES[i]);
            // 头像地址随便给一个非空串即可：预览取头像走的是本地占位图，不看这个值
            data.recordLiveUserFace(platform, PREVIEW_UID, viewer, "preview-face");

            data.incrementLiveUserMetric(platform, PREVIEW_UID, BilibiliLiveMetric.DANMU_USERS, viewer, danmu[i]);
            data.incrementLiveUserMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GIFT_USERS, viewer, gift[i]);
            data.recordLiveMetricUser(platform, PREVIEW_UID, BilibiliLiveMetric.ENTER_USERS, viewer);
            data.recordLiveMetricUser(platform, PREVIEW_UID, BilibiliLiveMetric.LIKE_USERS, viewer);

            if (i < superChat.length) {
                data.incrementLiveUserMetric(platform, PREVIEW_UID,
                        BilibiliLiveMetric.SUPER_CHAT_USERS, viewer, superChat[i]);
                data.incrementLiveUserMetric(platform, PREVIEW_UID,
                        BilibiliLiveMetric.BOX_USERS, viewer, box[i]);
                data.incrementLiveUserMetric(platform, PREVIEW_UID,
                        BilibiliLiveMetric.BOX_PROFIT_USERS, viewer, boxProfit[i]);
            }
            if (i < 4) {
                data.incrementLiveUserMetric(platform, PREVIEW_UID, BilibiliLiveMetric.GUARD_USERS, viewer, 1);
            }
        }
    }

    /**
     * 互动曲线与高能时刻的时间序列
     * <p>
     * 弹幕造出两处明显的密集段，好让「高能时刻」那一块真的挑得出东西——
     * 一条平的曲线会让人以为这个开关坏了。
     */
    private static void fixtureSeries(DefaultLiveDataService data, String platform, long end) {
        int minutes = (int) ((end - PREVIEW_START) / 60_000L);
        for (int minute = 0; minute < minutes; minute++) {
            long at = PREVIEW_START + minute * 60_000L;

            double base = 6 + 5 * Math.sin(minute / 9.0);
            double peak = minute > 36 && minute < 46 ? 34 : 0;
            double secondPeak = minute > 92 && minute < 99 ? 26 : 0;
            data.incrementLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.DANMU_COUNT,
                    at, Math.round(base + peak + secondPeak));

            if (minute % 4 == 0) {
                data.incrementLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.GIFT_VALUE, at, 3.2);
            }
            if (minute % 17 == 0) {
                data.incrementLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.SUPER_CHAT_VALUE, at, 30);
            }
            if (minute % 23 == 0) {
                data.incrementLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.BOX_COUNT, at, 4);
            }
            if (minute % 41 == 0) {
                data.incrementLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.GUARD_VALUE, at, 198);
            }
            data.maxLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.WATCHED_COUNT,
                    at, 900 + minute * 62L);
            double online = 720 + 180 * Math.sin(minute / 11.0);
            if (minute > 36 && minute < 46) {
                online += 280;
            }
            data.maxLiveSeries(platform, PREVIEW_UID, BilibiliLiveMetric.ONLINE_COUNT,
                    at, Math.round(online));
        }
    }

    /**
     * 词云的词频表
     */
    private static void fixtureWords(DefaultLiveDataService data, String platform) {
        String[] words = {"晚上好", "好听", "点歌", "主播", "笑死", "哈哈哈", "太强了", "下次一定",
                "打卡", "第一次来", "收藏了", "求", "签到", "破防", "awsl", "泪目",
                "有被治愈", "手速", "稳", "帅", "可爱", "在听", "催更", "关注了"};
        for (int i = 0; i < words.length; i++) {
            int times = 60 - i * 2;
            for (int n = 0; n < times; n++) {
                data.incrementLiveWordFrequency(platform, PREVIEW_UID, words[i]);
            }
        }
    }

}
