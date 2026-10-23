package org.frostnova.nova.report.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.BilibiliStreamerMetric;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.StreamerCalendar;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.frostnova.nova.report.painter.BilibiliWeeklyReportPainter;
import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.util.FontUtil;
import org.frostnova.nova.report.util.ImageUtil;
import org.springframework.boot.info.BuildProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配色样张用的三种报告图：下播报告、运营周报、动态推送。
 * <p>
 * 全部用 {@link DemoAssets} 的虚构主播、观众与合成图，不连网。
 * palette 传 null 时走各绘制器不带配色的那个入口，即升级前的样子（与「经典」相同）。
 */
final class ReportSampleFixtures {
    enum Kind {
        LIVE("下播报告"),
        WEEKLY("运营周报"),
        DYNAMIC("动态推送");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 周报按北京时间 10 月 12 日上午出，统计 10 月 5 日至 11 日这一周。 */
    static final Instant WEEKLY_NOW = Instant.parse("2026-10-12T02:00:00Z");

    private static final Instant WEEK_FIRST_EVENING = Instant.parse("2026-10-05T12:00:00Z");

    private static final long DAY = 86_400L;

    private final FontUtil fonts;

    private final Path demoDir;

    private final NovaCommonPainterFactory factory;

    private final BufferedImage[] avatars;

    private final BufferedImage cover;

    ReportSampleFixtures(Path demoDir, FontUtil fonts) throws IOException {
        this.fonts = fonts;
        this.demoDir = DemoAssets.generate(demoDir, fonts);
        NovaCoreProperties core = new NovaCoreProperties();
        core.getPaint().getFonts().add("内置");
        core.getPaint().getFonts().add("内置表情");
        Properties build = new Properties();
        build.setProperty("version", DemoAssets.projectVersion(DemoAssets.repoRoot()));
        build.setProperty("group", "org.frostnova.nova");
        build.setProperty("artifact", "nova-core");
        build.setProperty("name", "NovaBot");
        this.factory = new NovaCommonPainterFactory(new BuildProperties(build), core, fonts);
        this.avatars = new BufferedImage[DemoAssets.AVATAR_FILES.length];
        for (int i = 0; i < avatars.length; i++) {
            avatars[i] = read(this.demoDir.resolve(DemoAssets.AVATAR_FILES[i]));
        }
        this.cover = read(this.demoDir.resolve(DemoAssets.COVER_FILE));
    }

    BufferedImage render(Kind kind, ReportPalette palette) throws IOException {
        return switch (kind) {
            case LIVE -> live(palette);
            case WEEKLY -> weekly(palette);
            case DYNAMIC -> dynamic(palette);
        };
    }

    private BufferedImage live(ReportPalette palette) throws IOException {
        var painter = DemoAssets.demoPainter(demoDir, fonts, factory);
        JSONObject params = new JSONObject();
        params.put("box_ranking", 5);
        var options = BilibiliLiveReportOptions.of(params, true);
        var streamer = new LiveStreamerInfo(DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A, DemoAssets.ROOM_ID, "demo-face-0");
        String platform = BilibiliPlatform.BILIBILI.id();
        Optional<String> base64 = palette == null
                ? painter.paint(platform, streamer, options)
                : painter.paint(platform, streamer, options, palette);
        return decode(base64.orElseThrow(() -> new IOException("live report painter returned empty")));
    }

    private BufferedImage weekly(ReportPalette palette) throws IOException {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(avatars[0]));
        var painter = new BilibiliWeeklyReportPainter(factory, api, new NovaBilibiliProperties());
        var streamer = new LiveStreamerInfo(DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A, DemoAssets.ROOM_ID,
                "https://face.example/demo-a.png");
        var data = weeklyData();
        byte[] png = palette == null ? painter.render(streamer, data) : painter.render(streamer, data, palette);
        return read(png);
    }

    private BufferedImage dynamic(ReportPalette palette) throws IOException {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            if (url.contains("cover")) return Optional.of(cover);
            return Optional.of(url.contains("demo-b") ? avatars[1] : avatars[0]);
        });
        var painter = new BilibiliDynamicPainter(factory, api, new NovaBilibiliProperties());
        Dynamic dynamic = dynamicSample();
        Optional<String> base64 = palette == null ? painter.paint(dynamic) : painter.paint(dynamic, palette);
        return decode(base64.orElseThrow(() -> new IOException("dynamic painter returned empty")));
    }

    /** 主播 A 在开播前一天傍晚转发主播 B 的合作视频，带一段自己的话。 */
    private static Dynamic dynamicSample() {
        long published = DemoAssets.START_MILLIS / 1000 - 14 * 3600;
        Dynamic origin = new Dynamic();
        origin.setId("900000000000000002");
        origin.setType("DYNAMIC_TYPE_AV");
        origin.setVisible(true);
        origin.setModules(JSON.parseObject("{\"module_author\":{\"mid\":" + DemoAssets.STREAMER_B_UID
                + ",\"name\":\"" + DemoAssets.STREAMER_B + "\",\"face\":\"https://face.example/demo-b.png\",\"pub_ts\":"
                + (published - 3600) + "},\"module_dynamic\":{\"desc\":{\"text\":\"和 A 一起录的合唱上线了，"
                + "两个人对着节拍器练了一整周，欢迎来听～\"},\"major\":{\"type\":\"MAJOR_TYPE_ARCHIVE\","
                + "\"archive\":{\"title\":\"演示合唱\",\"cover\":\"https://pic.example/demo-cover.png\"}}}}"));
        Dynamic forward = new Dynamic();
        forward.setId("900000000000000001");
        forward.setType("DYNAMIC_TYPE_FORWARD");
        forward.setVisible(true);
        forward.setModules(JSON.parseObject("{\"module_author\":{\"mid\":" + DemoAssets.STREAMER_UID
                + ",\"name\":\"" + DemoAssets.STREAMER_A + "\",\"face\":\"https://face.example/demo-a.png\",\"pub_ts\":"
                + published + "},\"module_dynamic\":{\"desc\":{\"text\":\"合唱视频出来啦！今晚八点照常开播，"
                + "直播间里再唱一遍完整版。\"}}}"));
        forward.setOrigin(origin);
        return forward;
    }

    /** 本周六场、上周四场，金额可见；本周有两位观众是头一回记到。日历最早十来天在监看记录开始之前，读成「说不准」。 */
    static WeeklyReport.Data weeklyData() {
        var sessions = new ArrayList<LiveSession>();
        int[] lastWeek = {-7, -5, -3, -2};
        double[] lastRevenue = {620, 410, 880, 530};
        long[] lastDanmu = {2_400, 1_850, 3_100, 2_200};
        for (int i = 0; i < lastWeek.length; i++) {
            sessions.add(session(WEEK_FIRST_EVENING.plusSeconds(lastWeek[i] * DAY), 9_000, lastRevenue[i], lastDanmu[i],
                    viewers(0, 3), DemoAssets.ROOM_TITLE));
        }
        int[] thisWeek = {0, 1, 3, 4, 5, 6};
        long[] durations = {9_600, 7_800, 10_800, 8_400, 12_600, 9_000};
        double[] revenue = {760, 455.5, 1_280, 690, 2_150.4, 820};
        long[] danmu = {2_900, 2_050, 3_600, 2_480, 4_320, 2_760};
        for (int i = 0; i < thisWeek.length; i++) {
            sessions.add(session(WEEK_FIRST_EVENING.plusSeconds(thisWeek[i] * DAY), durations[i], revenue[i], danmu[i],
                    viewers(i, 4), i == 4 ? DemoAssets.ROOM_TITLE_LATER : DemoAssets.ROOM_TITLE));
        }
        var snapshots = List.of(
                snapshot(WEEK_FIRST_EVENING.minusSeconds(3_600), 8_520),
                snapshot(WEEK_FIRST_EVENING.plusSeconds(6 * DAY + 14_400), 8_688));
        var data = WeeklyReport.calculate(DemoAssets.STREAMER_A, sessions, snapshots, WEEKLY_NOW,
                new BilibiliLiveMetricCatalog().insights(), true);
        long watchedFrom = WEEK_FIRST_EVENING.minusSeconds(19 * DAY).toEpochMilli();
        var watched = List.of(new LiveGap(watchedFrom, WEEKLY_NOW.toEpochMilli(), LiveGap.Reason.UNKNOWN));
        var downtime = List.of(new LiveGap(WEEK_FIRST_EVENING.minusSeconds(10 * DAY).toEpochMilli(),
                WEEK_FIRST_EVENING.minusSeconds(9 * DAY).toEpochMilli(), LiveGap.Reason.MAINTENANCE));
        return data.withCalendar(new StreamerCalendar().calculate(sessions, watched, downtime, List.of(),
                data.lastDay(), 35, Clock.fixed(WEEKLY_NOW, StatisticsDay.ZONE)));
    }

    private static LiveSession session(Instant start, long seconds, double revenue, long danmu,
                                       List<Long> viewers, String title) {
        return new LiveSession(BilibiliPlatform.BILIBILI.id(), DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A,
                DemoAssets.ROOM_ID, start.toEpochMilli(), start.plusSeconds(seconds).toEpochMilli(), seconds,
                Map.of(BilibiliLiveMetric.GIFT_VALUE, revenue, BilibiliLiveMetric.DANMU_COUNT, (double) danmu,
                        BilibiliLiveMetric.FOLLOW_COUNT, 12d),
                Map.of(), LiveEndReason.NORMAL, List.of(new RoomInfoSnapshot(start.toEpochMilli(), title, "唱见电台")),
                0, Map.of(BilibiliLiveMetric.ENTER_USERS, viewers, BilibiliLiveMetric.DANMU_USERS, viewers),
                0, Map.of(), Map.of());
    }

    private static List<Long> viewers(int offset, int count) {
        var list = new ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            list.add(DemoAssets.VIEWER_UIDS[(offset + i) % DemoAssets.VIEWER_UIDS.length]);
        }
        return list;
    }

    private static StreamerSnapshot snapshot(Instant at, double fans) {
        return new StreamerSnapshot(BilibiliPlatform.BILIBILI.id(), DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A,
                at.toEpochMilli(), Map.of(BilibiliStreamerMetric.FANS, fans));
    }

    private static BufferedImage decode(String base64) throws IOException {
        return read(Base64.getDecoder().decode(base64));
    }

    private static BufferedImage read(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("painter bytes were not an image");
        return image;
    }

    private static BufferedImage read(Path file) throws IOException {
        return ImageUtil.readImageFromPath(file.toString()).orElseThrow(() -> new IOException("missing " + file));
    }
}
