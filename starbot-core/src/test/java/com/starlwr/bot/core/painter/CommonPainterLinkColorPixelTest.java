package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版权行不带仓库地址 —— 成图那一层
 *
 * <h2>为什么另起一组</h2>
 * {@link CommonPainterCopyrightTest} 覆写 {@code drawTextRight} 收文本，拦的是<b>谁调画法</b>：
 * 换一条画法把地址画进图，那一组照绿。这一组数的是<b>成图结果</b>里的像素。
 * 两组不同层，谁也不替谁，谁也不许被读成「这张图是干净的」。
 *
 * <h2>这一组数不到什么</h2>
 * 它只认「用链接色画」。<b>换个颜色把地址画进图，它读 0。</b>
 * 另有一件顺带记下：图里的计数今天是结构性的 0——{@code COLOR_LINK} 全树只有一处声明、
 * 没有任何一处在用。<b>这个 0 买到的是「没人画链接色」，不是「图干净」。</b>
 * 所以计数旁边还印一个会动的数：<b>最近的像素离链接色有多远</b>。
 * 哪天有人用相近的蓝画了东西，计数仍是 0，而这个距离会掉下来。
 */
@DisplayName("版权行不带仓库地址（成图那一层）")
class CommonPainterLinkColorPixelTest {

    /** 容差：距离不超过它就算一枚。取值见本轮读数，两个方向都量过。 */
    private static final int TOLERANCE = 40;

    /** 尺自己的源码，用来印出这一跑量的是哪一版。 */
    private static final Path GAUGE_SOURCE =
            Path.of("src/test/java/com/starlwr/bot/core/painter/LinkColorPixels.java");

    /**
     * 曾经画在版权行上的那条地址，原样取自公开历史里的那一行，一个字节没改。
     * 不另造一条「像地址但从没出现过」的串——那会多出一份要解释的东西。
     */
    private static final String HISTORICAL_ADDRESS = "https://github.com/rossinova/NovaBot";

    private StarBotCoreProperties properties;
    private FontUtil fontUtil;
    private BuildProperties buildProperties;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        // 用内置字体，免得结论取决于跑测试这台机器装了什么字体
        properties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        java.util.Properties buildInfo = new java.util.Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");
        buildProperties = new BuildProperties(buildInfo);
    }

    @Test
    @DisplayName("🔴 干净图里没有链接色像素；同时印出最近的像素有多远")
    void cleanImageHasNoLinkColorPixels() {
        BufferedImage image = cleanImage();
        LinkColorPixels.Reading reading = LinkColorPixels.read(image, CommonPainter.COLOR_LINK, TOLERANCE);

        // 先证这一格量得到东西：一张全透明的图上，下面那条断言会白白通过
        assertTrue(reading.visible() > 0, "图上一个可见像素都没有，这一格什么都没量到");

        assertEquals(0, reading.within(),
                "干净图里出现了链接色像素：" + describe(reading));

        System.out.println("干净图　" + describe(reading));
    }

    @Test
    @DisplayName("🔴 把地址画回去，链接色计数必须为红")
    void addressDrawnBackIsCounted() {
        BufferedImage image = imageWithAddressDrawnBack();
        LinkColorPixels.Reading reading = LinkColorPixels.read(image, CommonPainter.COLOR_LINK, TOLERANCE);

        assertTrue(reading.within() > 0,
                "地址画回图上了，链接色像素却一枚都没数到：" + describe(reading));

        System.out.println("画回地址　" + describe(reading));
    }

    @Test
    @DisplayName("🔴 这把尺不是恒红的：容差收到量不到的地步，同一张图必须转绿")
    void countGoesAwayWhenNothingCanBeWithinTolerance() {
        BufferedImage image = imageWithAddressDrawnBack();

        // 容差取负数，任何距离都不可能落进去。若这样它还数出东西，那红就不是比出来的
        LinkColorPixels.Reading blind = LinkColorPixels.read(image, CommonPainter.COLOR_LINK, -1);
        assertEquals(0, blind.within(), "容差为负还能数到像素，说明它压根没在比");

        // 容差取 0：只认一模一样的颜色。地址原本就是用链接色画的，所以这里仍该有
        LinkColorPixels.Reading exact = LinkColorPixels.read(image, CommonPainter.COLOR_LINK, 0);
        assertTrue(exact.within() > 0, "地址就是用链接色画的，容差 0 也该数得到");

        System.out.println("容差 -1　" + describe(blind));
        System.out.println("容差 0 　" + describe(exact));
    }

    @Test
    @DisplayName("🔴 那个「会动的数」真的会动：画一笔相近的蓝，距离掉下来而计数不动")
    void nearestDistanceMovesWhenSomethingGetsCloser() {
        LinkColorPixels.Reading before =
                LinkColorPixels.read(cleanImage(), CommonPainter.COLOR_LINK, TOLERANCE);

        // 一个明显更近、但仍在容差之外的蓝
        Color nearerBlue = new Color(
                CommonPainter.COLOR_LINK.getRed() + TOLERANCE + 20,
                CommonPainter.COLOR_LINK.getGreen(),
                CommonPainter.COLOR_LINK.getBlue());

        BufferedImage painted = cleanImage();
        Graphics2D g = painted.createGraphics();
        g.setColor(nearerBlue);
        g.fillRect(0, 0, 10, 10);
        g.dispose();

        LinkColorPixels.Reading after =
                LinkColorPixels.read(painted, CommonPainter.COLOR_LINK, TOLERANCE);

        assertTrue(after.nearest() < before.nearest(),
                "画了一笔更近的蓝，最近距离却没掉：" + describe(before) + " → " + describe(after));
        assertEquals(0, after.within(),
                "那一笔在容差之外，计数不该动：" + describe(after));

        // 反过来：不画那一笔，距离维持原值。否则「掉下来」也可能只是每跑一次都不一样
        LinkColorPixels.Reading again =
                LinkColorPixels.read(cleanImage(), CommonPainter.COLOR_LINK, TOLERANCE);
        assertEquals(before.nearest(), again.nearest(), "同一张干净图两次读数不一致");

        System.out.println("干净图最近 " + before.nearest()
                + " → 画一笔 " + describe(nearerBlue) + " 之后最近 " + after.nearest()
                + "，计数仍 " + after.within());
    }

    @Test
    @DisplayName("🔴 读数四件齐印：阳性数、阴性数、判次、尺的 sha256")
    void printsAllFourReadings() throws IOException, NoSuchAlgorithmException {
        LinkColorPixels.Reading clean =
                LinkColorPixels.read(cleanImage(), CommonPainter.COLOR_LINK, TOLERANCE);
        LinkColorPixels.Reading dirty =
                LinkColorPixels.read(imageWithAddressDrawnBack(), CommonPainter.COLOR_LINK, TOLERANCE);

        // 取不到就让这一格红：一份来路不明的读数，比没有读数坏
        assertTrue(Files.isRegularFile(GAUGE_SOURCE),
                "取不到尺的源码（" + GAUGE_SOURCE.toAbsolutePath() + "），这一跑的读数说不出是哪一版量的");
        String gaugeSha = sha256(Files.readAllBytes(GAUGE_SOURCE));

        System.out.println("── 本跑读数 ──");
        System.out.println("  容差　　　　" + TOLERANCE + "（逐通道差取最大）");
        System.out.println("  量的哪张图　" + clean.width() + "×" + clean.height()
                + "，由 drawCopyright(20) 出图；可见像素 " + clean.visible() + "／" + clean.total());
        System.out.println("  画回地址　　" + dirty.within() + " 枚（最近 " + dirty.nearest() + "）");
        System.out.println("  干净图　　　" + clean.within() + " 枚（最近 " + clean.nearest() + "）");
        System.out.println("  🔴 干净图这个 0 是结构性的：COLOR_LINK 全树一处声明、零处使用。");
        System.out.println("     它买到的是「没人画链接色」，不是「图干净」——会动的数是上面那个「最近」。");
        // 容差取这个值不是抄来的：两个方向都量一遍，让读的人自己看见它为什么落在中间
        System.out.println("  容差两个方向：");
        for (int t : new int[]{0, 10, 20, 40, 80, 120, 146, 147, 160}) {
            LinkColorPixels.Reading c = LinkColorPixels.read(cleanImage(), CommonPainter.COLOR_LINK, t);
            LinkColorPixels.Reading d = LinkColorPixels.read(imageWithAddressDrawnBack(), CommonPainter.COLOR_LINK, t);
            System.out.println("    容差 " + t + "：画回地址 " + d.within() + " 枚，干净图 " + c.within() + " 枚"
                    + (c.within() > 0 ? "  ← 干净图开始被误收" : ""));
        }
        System.out.println("  尺 sha256 　" + gaugeSha);
        System.out.println("  判次　　　　本方法跑到这里即为通过；退出码由构建给出");

        assertNotEquals(clean.within(), dirty.within(), "两个方向读数一样，这把尺分辨不出改没改");
    }

    private BufferedImage cleanImage() {
        return newPainter().drawCopyright(20).getImage();
    }

    private BufferedImage imageWithAddressDrawnBack() {
        return new AddressPainter(buildProperties, properties, fontUtil).drawCopyright(20).getImage();
    }

    private CommonPainter newPainter() {
        return new CommonPainter(buildProperties, properties, fontUtil, 900, 400, false);
    }

    private static String describe(LinkColorPixels.Reading r) {
        return r.within() + " 枚／可见 " + r.visible() + "／共 " + r.total()
                + "（" + r.width() + "×" + r.height() + "），最近 " + r.nearest();
    }

    private static String describe(Color c) {
        return "(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")";
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * 把当年那一行原样画回去。走的是产品自己的画法，不是往画布上直接涂像素——
     * 涂像素量到的是计数器，不是产品。
     */
    private static final class AddressPainter extends CommonPainter {

        AddressPainter(BuildProperties buildProperties, StarBotCoreProperties properties, FontUtil fontUtil) {
            super(buildProperties, properties, fontUtil, 900, 400, false);
        }

        @Override
        public CommonPainter drawCopyright(List<List<TextWithStyle>> extraMiddle,
                                           List<List<TextWithStyle>> extraBottom,
                                           int marginRight) {
            super.drawCopyright(extraMiddle, extraBottom, marginRight);
            return drawTextRight(HISTORICAL_ADDRESS, COLOR_LINK, marginRight);
        }
    }
}
