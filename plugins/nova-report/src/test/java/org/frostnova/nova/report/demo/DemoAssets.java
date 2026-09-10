package org.frostnova.nova.report.demo;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.frostnova.nova.report.util.ImageUtil;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.util.Pair;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;

import static org.mockito.Mockito.mock;

/**
 * README 示意图素材：合成头像／封面，再把合成场次送进报告图插件的真实绘制方法。
 * <p>
 * 固定种子，无网络。人名、房间标题、弹幕一律明显虚构。
 */
public final class DemoAssets {
    static final long SEED = 20_260_910L;

    static final int AVATAR_PX = 256;

    static final int COVER_W = 1280;

    static final int COVER_H = 720;

    static final int REPORT_WIDTH = 1200;

    static final long REPORT_MAX_BYTES = 600 * 1024L;

    static final long STREAMER_UID = 100_000_001L;

    static final long STREAMER_B_UID = 100_000_002L;

    static final long STREAMER_C_UID = 100_000_003L;

    static final long ROOM_ID = 900_000_001L;

    static final String STREAMER_A = "示例主播 A";

    static final String STREAMER_B = "示例主播 B";

    static final String STREAMER_C = "示例主播 C";

    static final String ROOM_TITLE = "演示直播间";

    static final String ROOM_TITLE_LATER = "演示直播间（加场）";

    static final String[] VIEWER_NAMES = {"示例观众甲", "示例观众乙", "示例观众丙", "示例观众丁", "示例观众戊"};

    static final long[] VIEWER_UIDS = {
            100_000_011L, 100_000_012L, 100_000_013L, 100_000_014L, 100_000_015L};

    static final String[] AVATAR_FILES = {"avatar-a.png", "avatar-b.png", "avatar-c.png"};

    static final String COVER_FILE = "cover.png";

    static final String ROSTER_FILE = "roster.txt";

    /** 开播时刻写死，词云种子与时长才两次相同 */
    static final long START_MILLIS = 1_704_067_200_000L;

    static final long DURATION_MILLIS = 90L * 60_000L;

    static final String RENDER_VIA = "org.frostnova.nova.report.painter.BilibiliLiveReportPainter.paint";

    /**
     * 词云语料：六十条中性短句，不含真人名
     */
    static final String[] DANMU = {
            "晚上好", "好听", "点歌", "打卡", "第一次来", "收藏了", "签到", "加油", "辛苦了", "休息一下",
            "今天天气不错", "音质很好", "画面清晰", "节奏刚好", "这首喜欢", "再来一遍", "慢慢来", "喝口水",
            "坐下来听", "晚安", "早上好", "下午好", "周末愉快", "周一加油", "学习中", "作业做完了",
            "吃饭去了", "回来了", "路过打卡", "新来的", "老听众", "前排", "后排围观", "安静听",
            "拍手", "鼓掌", "比心", "抱抱", "谢谢分享", "涨知识", "学到了", "记笔记",
            "收藏备用", "反复听", "单曲循环", "下一首也行", "随便听听", "背景音", "专注中", "休息日",
            "加班结束", "通勤路上", "晚饭时间", "宵夜时间", "午休", "课间", "天气凉快", "下雨了",
            "出太阳了", "开窗通风"
    };

    private DemoAssets() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String mode = args.length == 0 ? "all" : args[0];
        Path root = repoRoot();
        Path demo = root.resolve("docs").resolve("assets").resolve("demo");
        Path report = root.resolve("docs").resolve("assets").resolve("report-demo.png");
        FontUtil fonts = bundledFonts();
        if ("generate".equals(mode) || "all".equals(mode)) {
            Path dir = generate(demo, fonts);
            System.out.println("wrote " + dir);
        }
        if ("render".equals(mode) || "all".equals(mode)) {
            if (!Files.isRegularFile(demo.resolve(COVER_FILE))) {
                generate(demo, fonts);
            }
            Rendered rendered = render(demo, report, fonts);
            System.out.println("rendered-by " + rendered.via());
            System.out.println("png " + rendered.width() + "x" + rendered.height()
                    + " bytes=" + rendered.bytes() + " " + rendered.path());
        }
    }

    static FontUtil bundledFonts() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("内置");
        FontUtil fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        return fonts;
    }

    static Path generate(Path dir, FontUtil fonts) throws IOException {
        Files.createDirectories(dir);
        Random rng = new Random(SEED);
        String[] initials = {"A", "B", "C"};
        for (int i = 0; i < AVATAR_FILES.length; i++) {
            writePng(avatar(rng, fonts, initials[i]), dir.resolve(AVATAR_FILES[i]));
        }
        writePng(cover(rng, fonts), dir.resolve(COVER_FILE));
        Files.writeString(dir.resolve(ROSTER_FILE), rosterText(), StandardCharsets.UTF_8);
        return dir;
    }

    static Rendered render(Path demoDir, Path reportPng, FontUtil fonts) throws IOException {
        Files.createDirectories(reportPng.getParent());
        BufferedImage[] avatars = new BufferedImage[AVATAR_FILES.length];
        for (int i = 0; i < AVATAR_FILES.length; i++) {
            String name = AVATAR_FILES[i];
            avatars[i] = ImageUtil.readImageFromPath(demoDir.resolve(name).toString())
                    .orElseThrow(() -> new IOException("missing " + name));
        }
        BufferedImage coverRaw = ImageUtil.readImageFromPath(demoDir.resolve(COVER_FILE).toString())
                .orElseThrow(() -> new IOException("missing " + COVER_FILE));

        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "5.4.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fonts);

        DemoReportPainter painter = new DemoReportPainter(
                factory, mock(BilibiliApiUtil.class), fixtureData(), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class),
                coverRaw, avatars);

        JSONObject params = new JSONObject();
        params.put("box_ranking", 5);
        params.put("box_profit_ranking", 5);
        Optional<String> base64 = painter.paint(
                BilibiliPlatform.BILIBILI.id(),
                new LiveStreamerInfo(STREAMER_UID, STREAMER_A, ROOM_ID, "demo-face-0"),
                BilibiliLiveReportOptions.of(params, true));
        if (base64.isEmpty()) {
            throw new IOException("BilibiliLiveReportPainter.paint returned empty");
        }

        BufferedImage nativeImage = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.get())));
        if (nativeImage == null) {
            throw new IOException("paint bytes were not a PNG");
        }
        // 绘制器画布宽 900。示意图要 1200±100：按宽放大后再收成索引色，
        // 直接 ImageIO 无损 PNG 会超过 600 KB。
        BufferedImage scaled = ImageUtil.resizeByWidth(nativeImage, REPORT_WIDTH);
        BufferedImage compact = indexed(scaled);
        writePng(compact, reportPng);
        long bytes = Files.size(reportPng);
        if (bytes > REPORT_MAX_BYTES) {
            throw new IOException("report PNG " + bytes + " bytes exceeds " + REPORT_MAX_BYTES);
        }
        System.out.println("rendered-by " + RENDER_VIA);
        return new Rendered(reportPng, compact.getWidth(), compact.getHeight(), bytes, RENDER_VIA);
    }

    static String rosterText() {
        StringBuilder text = new StringBuilder();
        text.append(STREAMER_A).append('\t').append(STREAMER_UID).append('\n');
        text.append(STREAMER_B).append('\t').append(STREAMER_B_UID).append('\n');
        text.append(STREAMER_C).append('\t').append(STREAMER_C_UID).append('\n');
        text.append("房间号\t").append(ROOM_ID).append('\n');
        text.append("标题\t").append(ROOM_TITLE).append('\n');
        text.append("改题\t").append(ROOM_TITLE_LATER).append('\n');
        for (int i = 0; i < VIEWER_NAMES.length; i++) {
            text.append(VIEWER_NAMES[i]).append('\t').append(VIEWER_UIDS[i]).append('\n');
        }
        return text.toString();
    }

    static List<String> fictionalNames() {
        return List.of(STREAMER_A, STREAMER_B, STREAMER_C,
                VIEWER_NAMES[0], VIEWER_NAMES[1], VIEWER_NAMES[2], VIEWER_NAMES[3], VIEWER_NAMES[4],
                ROOM_TITLE, ROOM_TITLE_LATER);
    }

    static List<Long> fictionalUids() {
        return List.of(STREAMER_UID, STREAMER_B_UID, STREAMER_C_UID, ROOM_ID,
                VIEWER_UIDS[0], VIEWER_UIDS[1], VIEWER_UIDS[2], VIEWER_UIDS[3], VIEWER_UIDS[4]);
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("plugins"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot find repository root from " + System.getProperty("user.dir"));
    }

    private static BufferedImage avatar(Random rng, FontUtil fonts, String initial) {
        int n = AVATAR_PX;
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        hint(graphics);
        Color from = new Color(40 + rng.nextInt(90), 70 + rng.nextInt(110), 130 + rng.nextInt(90));
        Color to = new Color(170 + rng.nextInt(70), 80 + rng.nextInt(90), 130 + rng.nextInt(90));
        graphics.setPaint(new GradientPaint(0, 0, from, n, n, to));
        graphics.fillOval(4, 4, n - 8, n - 8);
        graphics.setColor(new Color(255, 255, 255, 70));
        int shape = rng.nextInt(3);
        if (shape == 0) {
            Polygon triangle = new Polygon();
            triangle.addPoint(n / 2, 42);
            triangle.addPoint(n - 50, n - 58);
            triangle.addPoint(50, n - 58);
            graphics.fillPolygon(triangle);
        } else if (shape == 1) {
            Polygon diamond = new Polygon();
            diamond.addPoint(n / 2, 38);
            diamond.addPoint(n - 42, n / 2);
            diamond.addPoint(n / 2, n - 38);
            diamond.addPoint(42, n / 2);
            graphics.fillPolygon(diamond);
        } else {
            graphics.fillOval(58, 58, n - 116, n - 116);
        }
        drawCentered(graphics, fonts, initial, n / 2, n / 2, 108, Color.WHITE);
        graphics.dispose();
        return image;
    }

    private static BufferedImage cover(Random rng, FontUtil fonts) {
        BufferedImage image = new BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        hint(graphics);
        Color from = new Color(36 + rng.nextInt(40), 48 + rng.nextInt(50), 92 + rng.nextInt(50));
        Color to = new Color(210 + rng.nextInt(30), 96 + rng.nextInt(50), 140 + rng.nextInt(40));
        graphics.setPaint(new GradientPaint(0, 0, from, COVER_W, COVER_H, to));
        graphics.fillRect(0, 0, COVER_W, COVER_H);
        graphics.setColor(new Color(255, 255, 255, 40));
        for (int i = 0; i < 6; i++) {
            int size = 120 + rng.nextInt(180);
            graphics.fillOval(rng.nextInt(COVER_W) - 40, rng.nextInt(COVER_H) - 40, size, size);
        }
        drawCentered(graphics, fonts, ROOM_TITLE, COVER_W / 2, COVER_H / 2 - 24, 72, Color.WHITE);
        drawCentered(graphics, fonts, STREAMER_A, COVER_W / 2, COVER_H / 2 + 64, 36, new Color(255, 255, 255, 220));
        graphics.dispose();
        return image;
    }

    private static void hint(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void drawCentered(Graphics2D graphics, FontUtil fonts, String text,
                                     int centerX, int centerY, int size, Color color) {
        TextWithStyle spec = new TextWithStyle(text, size, color, Font.BOLD);
        Pair<Integer, Integer> box = fonts.getStringWidthAndHeight(graphics, spec);
        int x = centerX - box.getFirst() / 2;
        int y = centerY + box.getSecond() / 4;
        Font original = graphics.getFont();
        graphics.setColor(color);
        int cursor = x;
        for (int codePoint : text.codePoints().toArray()) {
            Font font = fonts.findFontForCharacter(codePoint).deriveFont(Font.BOLD, (float) size);
            graphics.setFont(font);
            String ch = new String(Character.toChars(codePoint));
            graphics.drawString(ch, cursor, y);
            cursor += graphics.getFontMetrics().stringWidth(ch);
        }
        graphics.setFont(original);
    }

    private static BufferedImage indexed(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D graphics = out.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return out;
    }

    private static void writePng(BufferedImage source, Path path) throws IOException {
        BufferedImage toWrite = source;
        if (source.getType() != BufferedImage.TYPE_BYTE_INDEXED
                && source.getType() != BufferedImage.TYPE_INT_RGB) {
            toWrite = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = toWrite.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, toWrite.getWidth(), toWrite.getHeight());
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            ImageIO.write(toWrite, "png", path.toFile());
            return;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.0f);
        }
        try (OutputStream stream = Files.newOutputStream(path);
             ImageOutputStream output = ImageIO.createImageOutputStream(stream)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(toWrite, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static LiveDataService fixtureData() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        String platform = BilibiliPlatform.BILIBILI.id();
        long end = START_MILLIS + DURATION_MILLIS;
        data.setLiveStartTime(platform, STREAMER_UID, START_MILLIS);
        data.setLiveEndTime(platform, STREAMER_UID, end);

        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.DANMU_COUNT, 1_086);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.GIFT_VALUE, 214.6);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.GIFT_PAID, 214.6);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.FREE_GIFT_COUNT, 328);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.SUPER_CHAT_COUNT, 5);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.SUPER_CHAT_VALUE, 210);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.BOX_COUNT, 31);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.BOX_PROFIT, -18.4);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.CAPTAIN_COUNT, 2);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.COMMANDER_COUNT, 1);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.GUARD_VALUE, 996);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.FOLLOW_COUNT, 47);
        data.incrementLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.SHARE_COUNT, 11);
        data.maxLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.LIKE_TOTAL, 1_864);
        data.maxLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.WATCHED_COUNT, 5_420);
        data.maxLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.ONLINE_RANK_COUNT, 86);
        data.maxLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.ONLINE_COUNT, 742);
        data.setLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.FANS_AT_START, 8_640);
        data.setLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.FANS_MEDAL_AT_START, 2_180);
        data.setLiveMetric(platform, STREAMER_UID, BilibiliLiveMetric.GUARD_AT_START, 22);

        double[] danmu = {164, 121, 88, 57, 36};
        double[] gift = {72.4, 48.0, 31.2, 16.8, 8.6};
        double[] superChat = {80, 50, 30, 20, 10};
        double[] box = {11, 8, 6, 4, 2};
        double[] boxProfit = {18.6, 4.2, -3.5, -9.8, -16.4};
        for (int i = 0; i < VIEWER_UIDS.length; i++) {
            long viewer = VIEWER_UIDS[i];
            data.recordLiveUserName(platform, STREAMER_UID, viewer, VIEWER_NAMES[i]);
            data.recordLiveUserFace(platform, STREAMER_UID, viewer, "demo-face-" + (i % AVATAR_FILES.length));
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.DANMU_USERS, viewer, danmu[i]);
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.GIFT_USERS, viewer, gift[i]);
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.SUPER_CHAT_USERS, viewer, superChat[i]);
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.BOX_USERS, viewer, box[i]);
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.BOX_PROFIT_USERS, viewer, boxProfit[i]);
            data.incrementLiveUserMetric(platform, STREAMER_UID, BilibiliLiveMetric.GUARD_USERS, viewer, 1);
            data.recordLiveMetricUser(platform, STREAMER_UID, BilibiliLiveMetric.ENTER_USERS, viewer);
            data.recordLiveMetricUser(platform, STREAMER_UID, BilibiliLiveMetric.LIKE_USERS, viewer);
        }

        int minutes = (int) (DURATION_MILLIS / 60_000L);
        for (int minute = 0; minute < minutes; minute++) {
            long at = START_MILLIS + minute * 60_000L;
            double base = 5 + 4 * Math.sin(minute / 8.0);
            double peak = minute > 28 && minute < 38 ? 28 : 0;
            double second = minute > 62 && minute < 70 ? 22 : 0;
            data.incrementLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.DANMU_COUNT,
                    at, Math.round(base + peak + second));
            if (minute % 5 == 0) {
                data.incrementLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.GIFT_VALUE, at, 2.8);
            }
            if (minute % 18 == 0) {
                data.incrementLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.SUPER_CHAT_VALUE, at, 30);
            }
            if (minute % 21 == 0) {
                data.incrementLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.BOX_COUNT, at, 3);
            }
            if (minute % 37 == 0) {
                data.incrementLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.GUARD_VALUE, at, 138);
            }
            data.maxLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.WATCHED_COUNT,
                    at, 640 + minute * 48L);
            double online = 480 + 140 * Math.sin(minute / 10.0);
            if (minute > 28 && minute < 38) {
                online += 220;
            }
            data.maxLiveSeries(platform, STREAMER_UID, BilibiliLiveMetric.ONLINE_COUNT,
                    at, Math.round(online));
        }

        for (int i = 0; i < DANMU.length; i++) {
            int times = 64 - i;
            for (int n = 0; n < times; n++) {
                data.incrementLiveWordFrequency(platform, STREAMER_UID, DANMU[i]);
            }
        }
        return data;
    }

    record Rendered(Path path, int width, int height, long bytes, String via) {
    }

    /**
     * 走正式绘制器，只把「向平台取图」的口子换成本地合成图。
     */
    static final class DemoReportPainter extends BilibiliLiveReportPainter {
        private final BufferedImage coverRaw;

        private final BufferedImage face;

        private final BufferedImage[] rankingFaces;

        DemoReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api, LiveDataService liveDataService,
                          FontUtil fontUtil, NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory,
                          BufferedImage coverRaw, BufferedImage[] avatars) {
            super(factory, api, liveDataService, fontUtil, properties, roomInfoHistory);
            this.coverRaw = coverRaw;
            this.face = ImageUtil.maskToCircle(ImageUtil.resize(avatars[0], AVATAR_SIZE, AVATAR_SIZE));
            this.rankingFaces = new BufferedImage[avatars.length];
            for (int i = 0; i < avatars.length; i++) {
                this.rankingFaces[i] = ImageUtil.maskToCircle(
                        ImageUtil.resize(avatars[i], RANKING_AVATAR_SIZE, RANKING_AVATAR_SIZE));
            }
        }

        @Override
        protected BufferedImage loadCover(LiveStreamerInfo source) {
            BufferedImage scaled = ImageUtil.resizeByWidth(coverRaw, CONTENT_WIDTH);
            if (scaled.getHeight() < COVER_HEIGHT) {
                scaled = ImageUtil.resizeByHeight(coverRaw, COVER_HEIGHT);
            }
            int cropX = Math.max(0, (scaled.getWidth() - CONTENT_WIDTH) / 2);
            int cropY = Math.max(0, (scaled.getHeight() - COVER_HEIGHT) / 2);
            BufferedImage banner = scaled.getSubimage(cropX, cropY,
                    Math.min(CONTENT_WIDTH, scaled.getWidth()), Math.min(COVER_HEIGHT, scaled.getHeight()));
            return ImageUtil.maskToRoundedRectangle(banner, CANVAS_RADIUS - 5);
        }

        @Override
        protected BufferedImage faceImage(LiveStreamerInfo source) {
            return face;
        }

        @Override
        protected BufferedImage avatar(String url) {
            if (url == null || url.isBlank()) {
                return rankingFaces[0];
            }
            int index = Math.floorMod(url.hashCode(), rankingFaces.length);
            return rankingFaces[index];
        }

        @Override
        protected Optional<Long> fansCount(Long uid) {
            return Optional.of(8_688L);
        }

        @Override
        protected Optional<Integer> fansMedalCount(Long uid) {
            return Optional.of(2_204);
        }

        @Override
        protected Optional<Integer> guardCount(Long roomId, Long uid) {
            return Optional.of(27);
        }

        @Override
        protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
            return Optional.of(List.of(
                    new GuardMember(VIEWER_UIDS[0], VIEWER_NAMES[0], 1, 2800),
                    new GuardMember(VIEWER_UIDS[1], VIEWER_NAMES[1], 2, 1900),
                    new GuardMember(VIEWER_UIDS[2], VIEWER_NAMES[2], 3, 1100),
                    new GuardMember(VIEWER_UIDS[3], VIEWER_NAMES[3], 3, 800),
                    new GuardMember(VIEWER_UIDS[4], VIEWER_NAMES[4], 3, 500)));
        }

        @Override
        protected List<RoomInfoSnapshot> titleHistory(String platform, Long uid) {
            return List.of(
                    new RoomInfoSnapshot(START_MILLIS, ROOM_TITLE, "娱乐 · 视频聊天"),
                    new RoomInfoSnapshot(START_MILLIS + 38 * 60_000L, ROOM_TITLE_LATER, "娱乐 · 视频聊天"));
        }
    }
}
