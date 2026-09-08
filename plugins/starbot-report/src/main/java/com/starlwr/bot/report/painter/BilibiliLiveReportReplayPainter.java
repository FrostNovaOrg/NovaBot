package com.starlwr.bot.report.painter;

import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.report.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.report.util.FontUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 从明细重画一场历史报告
 *
 * <h2>为什么能重画</h2>
 * 报告图是缓存、会过期，而<b>画它要的数据一份不少地留在明细里</b>：
 * 曲线、排行榜、词频、高能时刻、标题轨迹、缺口区间。把这些灌回一个内存里的直播数据服务，
 * 报告绘制器根本分不出自己面对的是刚下播的那一场还是半年前的那一场——
 * 这正是版式预览那一支已经走通的路子（见 {@link BilibiliLiveReportPreviewPainter}），
 * 差别只在数据是夹具还是明细。
 *
 * <h2>🔴 重画不联网</h2>
 * 与预览同理，且理由更硬：向 B 站取到的封面、头像、粉丝数是<b>今天的</b>，
 * 而这一场是去年的。一张「曲线是去年、粉丝数是今天」的图看起来毫无异常，
 * 却在每一个数字上都可能骗人。落法同样是覆写父类「向外部要资料的口子」那一段里的每一个。
 * <p>
 * 🔴 <b>父类往那一段里加了新口子而这里忘了覆写时，重画会安静地联网</b>，
 * 且画出来的图与正确的那张长得一样。这一条不指望自觉：
 * {@code BilibiliLiveReportReplayPainterTest} 断言整趟重画与接口<b>零交互</b>。
 *
 * <h2>与当时那张图的差别</h2>
 * 封面、头像、榜单头像用占位图；「本场变化」那一块<b>整块不画</b>——
 * 粉丝数的现值当时是现取的，明细里只有开播那一刻的快照，
 * 拿快照减快照会得出「本场 +0」，那是一句假话。<b>其余每一块都与当时一致</b>。
 *
 * <h2>不是 Bean</h2>
 * 每重画一场就要一份只装着那一场的数据服务，因此每场 new 一个，由
 * {@code BilibiliLiveReportRedrawer} 持有。
 * 🔴 <b>更要紧的是它不能被注册成组件</b>：它与父类同类型，
 * 而下播报告处理器按类型注入画手——同类型双注册会让整个程序起不来。
 * 版式预览那一支正是这样炸过一次（2026-09-04，报告画手与其预览画手同类型双注册）。
 */
@Slf4j
public class BilibiliLiveReportReplayPainter extends BilibiliLiveReportPainter {
    private final LiveDetail detail;

    private final BufferedImage banner;

    private final BufferedImage face;

    private final BufferedImage rankingFace;

    public BilibiliLiveReportReplayPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api,
                                           FontUtil fontUtil, NovaBilibiliProperties properties,
                                           LiveRoomInfoHistory roomInfoHistory, LiveDetail detail) {
        super(factory, api, replayData(detail), fontUtil, properties, roomInfoHistory);
        this.detail = detail;
        this.banner = PainterPlaceholder.banner();
        this.face = PainterPlaceholder.face();
        this.rankingFace = PainterPlaceholder.rankingFace();
    }

    /**
     * 重画这一场的报告
     * @param options 版式选项
     * @return PNG 字节，画不出来时为空
     */
    public Optional<byte[]> render(BilibiliLiveReportOptions options) {
        // 昵称取归档里记的那个（当时的值），空了退回 UID：图上写一个 null 比写一串数字更糟。
        // 头像地址传空——它只会走到被覆写掉的那个口子里，不会真去取
        String uname = detail.uname() == null || detail.uname().isBlank()
                ? "UID " + detail.uid() : detail.uname();
        LiveStreamerInfo streamer = new LiveStreamerInfo(detail.uid(), uname, detail.roomId(), null);
        return paint(detail.platform(), streamer, options)
                .map(base64 -> Base64.getDecoder().decode(base64));
    }

    // ================ 向外部要资料的口子：一律不联网 ================

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

    /**
     * 粉丝数取不到——<b>刻意的</b>，见类注释「与当时那张图的差别」
     */
    @Override
    protected Optional<Long> fansCount(Long uid) {
        return Optional.empty();
    }

    @Override
    protected Optional<Integer> fansMedalCount(Long uid) {
        return Optional.empty();
    }

    @Override
    protected Optional<Integer> guardCount(Long roomId, Long uid) {
        return Optional.empty();
    }

    /**
     * 当时的全名单明细里没有，现拉会变成今天的人画在去年的报告上
     */
    @Override
    protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
        return Optional.of(List.of());
    }

    /**
     * 标题轨迹取自明细，而不是问状态存储要
     * <p>
     * 状态存储里只有<b>正在进行的那一场</b>的轨迹，问它要历史场次的，
     * 拿到的是当前这一场的标题——而那看起来完全像一份正常的结果。
     */
    @Override
    protected List<RoomInfoSnapshot> titleHistory(String platform, Long uid) {
        return detail.titles() == null ? List.of() : detail.titles();
    }

    /**
     * 把明细灌回一份内存里的直播数据服务
     * <p>
     * 🔴 <b>必须关掉落盘。</b>默认直播数据服务的默认值是「存，存到 data.json」，
     * 而那正是<b>真数据所在的那个文件</b>——这一份一旦写进去，
     * 正在进行的那一场直播的数据就被一场历史数据盖掉了，且不会有任何地方报错。
     * 理由与版式预览那一份完全相同，那边还多一层「收不到写盘事件」的巧合兜着，
     * 这里连那层巧合都不该指望。
     */
    private static LiveDataService replayData(LiveDetail detail) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);

        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        String platform = detail.platform();
        Long uid = detail.uid();

        data.setLiveStartTime(platform, uid, detail.startTime());
        data.setLiveEndTime(platform, uid, detail.endTime());
        // 历史场次一律按已下播画：不设这一项的话，「直播中」那条支路会拿当前时刻当终点，
        // 于是一场去年的直播会被画成「已经播了 300 天」
        data.setLiveStatus(platform, uid, false);

        if (detail.metrics() != null) {
            detail.metrics().forEach((metric, value) -> data.setLiveMetric(platform, uid, metric, value));
        }

        // 排行榜按分数灌回去：计分表的大小就是独立人数，因此人数不必另灌一遍——
        // 另灌一遍就是两本账，而两本账迟早对不上
        if (detail.rankings() != null) {
            detail.rankings().forEach((metric, users) -> {
                for (UserScore user : users) {
                    if (user.userUid() == null) {
                        continue;
                    }
                    data.incrementLiveUserMetric(platform, uid, metric, user.userUid(), user.score());
                    data.recordLiveUserName(platform, uid, user.userUid(), user.userName());
                    data.recordLiveUserFace(platform, uid, user.userUid(), user.userFace());
                }
            });
        }

        if (detail.series() != null) {
            detail.series().forEach((metric, buckets) -> buckets.forEach((at, value) ->
                    data.incrementLiveSeries(platform, uid, metric, at, value)));
        }

        // 词频只有「加一」这一个入口，于是一个出现 60 次的词要调 60 次。
        // 一场的词频总和就是弹幕的词数，量级在几万，一次重画多花十几毫秒——
        // 为它单开一个「直接设定词频」的接口，换来的是接口面上多一个只有这里用的方法
        if (detail.words() != null) {
            detail.words().forEach((word, times) -> {
                for (int i = 0; i < times; i++) {
                    data.incrementLiveWordFrequency(platform, uid, word);
                }
            });
        }

        // 缺口按成因分两处灌回：断流是这个房间自己的事，其余是进程层面的。
        // 灌错地方的话，报告概览那一行的分栏会说成另一个成因
        if (detail.gaps() != null) {
            for (LiveGap gap : detail.gaps()) {
                if (gap.reason() == LiveGap.Reason.STREAM_LOSS) {
                    data.recordRoomOutage(platform, uid, gap.from(), gap.to());
                } else {
                    data.recordDowntime(gap.from(), gap.to(),
                            gap.reason() == null ? LiveGap.Reason.UNKNOWN : gap.reason());
                }
            }
        }

        return data;
    }
}
