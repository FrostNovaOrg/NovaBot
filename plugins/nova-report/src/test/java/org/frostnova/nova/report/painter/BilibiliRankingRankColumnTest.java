package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 排行榜名次那一列放不放得下三位数
 * <p>
 * 名次画在行首、头像紧跟着，列宽只按两位数留的话，从第 100 名起第三位就压在头像圆底下，
 * 读不出来。这里用画手自己的字体量「300」有多宽，再看名次墨迹的右端有没有顶进头像区。
 */
@DisplayName("排行榜名次列：三位数不被头像盖住")
class BilibiliRankingRankColumnTest {
    private static final Color AVATAR_RED = new Color(220, 40, 40);

    private static final int RANK_SIZE = 24;

    private NovaCommonPainterFactory factory;

    private BilibiliDataQueryPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage avatar = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = avatar.createGraphics();
        graphics.setColor(AVATAR_RED);
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.fetchBilibiliImage(anyString())).thenReturn(Optional.of(avatar));

        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @Test
    @DisplayName("从第 281 名画到第 300 名：名次区放得下「300」，不压到头像")
    void threeDigitRankFitsBeforeAvatar() throws Exception {
        String base64 = painter.paintRanking(
                new BilibiliDataQueryPainter.Header("弹幕排行榜", "累计数据 · 测试主播的直播间", null),
                ranking(281, 300), 281, score -> Math.round(score) + " 条",
                "前 300 名 · 共 300 人").orElseThrow();
        BufferedImage image = decode(base64);

        // 用画手自己的字体量「300」，而不是另找一把尺
        CommonPainter probe = factory.create(760, 200, true);
        int width300 = probe.getStringWidthAndHeight(
                new TextWithStyle("300", RANK_SIZE, Color.BLACK, Font.BOLD)).getFirst();

        int avatarTop = firstAvatarTop(image);
        assertTrue(avatarTop >= 0, "图上找不到头像");
        int avatarLeft = avatarLeftEdge(image, avatarTop);
        int rankLeft = rankLeftEdge(image, avatarTop, avatarLeft);
        assertTrue(rankLeft < avatarLeft, "名次没画在头像左边？头像左端 " + avatarLeft + "，名次左端 " + rankLeft);

        assertTrue(rankLeft + width300 <= avatarLeft,
                "名次区右端 " + (rankLeft + width300) + " 压进头像区（头像左端 " + avatarLeft
                        + "，「300」宽 " + width300 + "）——第 100 名起名次读不出来");
    }

    /**
     * 造一页连号用户，得分随名次递减
     */
    private static List<UserScore> ranking(int from, int to) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(new UserScore((long) i, "观众" + i,
                    "https://pic.example/avatar" + i + ".jpg", (to - i + 1) * 13.0));
        }
        return rows;
    }

    private static BufferedImage decode(String base64) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertNotNull(image, "解不出图");
        return image;
    }

    private static boolean isAvatar(int rgb) {
        return ((rgb >> 16) & 0xFF) > 180 && ((rgb >> 8) & 0xFF) < 90 && (rgb & 0xFF) < 90;
    }

    private static boolean isInk(int rgb) {
        return !(((rgb >> 16) & 0xFF) > 245 && ((rgb >> 8) & 0xFF) > 245 && (rgb & 0xFF) > 245);
    }

    /**
     * 头像那一行的上沿：版面上没有别的红色，第一处红就是头像
     */
    private static int firstAvatarTop(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isAvatar(image.getRGB(x, y))) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int avatarLeftEdge(BufferedImage image, int avatarTop) {
        int left = Integer.MAX_VALUE;
        int bottom = Math.min(image.getHeight(), avatarTop + 30);
        for (int y = avatarTop; y < bottom; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isAvatar(image.getRGB(x, y))) {
                    left = Math.min(left, x);
                }
            }
        }
        return left;
    }

    /**
     * 名次墨迹的左端：只看头像那一行上下这一条、只看头像左边——
     * 头部文字在更上面、昵称与得分在头像右边，都圈不进来
     */
    private static int rankLeftEdge(BufferedImage image, int avatarTop, int avatarLeft) {
        int left = Integer.MAX_VALUE;
        for (int y = Math.max(0, avatarTop - 12); y < Math.min(image.getHeight(), avatarTop + 30); y++) {
            for (int x = 0; x < avatarLeft; x++) {
                if (isInk(image.getRGB(x, y))) {
                    left = Math.min(left, x);
                }
            }
        }
        return left == Integer.MAX_VALUE ? -1 : left;
    }
}
