package org.frostnova.nova.report.factory;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.painter.CommonPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.image.BufferedImage;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绘图器工厂
 *
 * <h2>为什么会有这组判据</h2>
 * 报告图那一路自己不持有版本号、配置和字体表，全靠这个工厂在造绘图器时把三样一起塞进去。
 * 少塞一样不会在造的时候报错，会在画到<b>版权行</b>（要版本号）、
 * <b>某个字</b>（要字体表）或<b>自动加高</b>（要配置）时才炸，或者更糟——画出来是错的。
 * <p>
 * 所以这里量的不是「返回了一个非 null 的绘图器」，是造出来的绘图器<b>三样都能用</b>。
 */
@DisplayName("绘图器工厂")
class NovaCommonPainterFactoryTest {
    private static final int WIDTH = 400;

    private static final int HEIGHT = 200;

    private NovaCommonPainterFactory factory;

    private NovaCoreProperties properties;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        // 用内置字体，免得结论取决于跑测试这台机器装了什么字体
        properties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "NovaBot");

        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), properties, fontUtil);
    }

    @Test
    @DisplayName("造出来的画布就是要的尺寸")
    void createsCanvasOfRequestedSize() {
        CommonPainter painter = factory.create(WIDTH, HEIGHT);

        assertEquals(WIDTH, painter.getWidth());
        assertEquals(HEIGHT, painter.getHeight());

        BufferedImage image = painter.getImage();
        assertEquals(WIDTH, image.getWidth());
        assertEquals(HEIGHT, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType(), "画布要带透明通道");
    }

    @Test
    @DisplayName("不说自动加高时就是不加高")
    void doesNotAutoExpandByDefault() {
        CommonPainter painter = factory.create(WIDTH, HEIGHT);

        painter.expandHeightIfNeeded(HEIGHT * 10, 100);

        assertEquals(HEIGHT, painter.getHeight(), "两个参数那一版必须等价于 autoExpand = false");
    }

    /**
     * 🔴 阳性对照：这把尺子分得出加高与不加高。
     * <p>
     * 少了它，上一条在「加高这件事根本没实现」时也是绿的。
     */
    @Test
    @DisplayName("说了自动加高就真的会加高")
    void autoExpandsWhenAsked() {
        CommonPainter painter = factory.create(WIDTH, HEIGHT, true);

        painter.expandHeightIfNeeded(HEIGHT + 50, 100);

        assertTrue(painter.getHeight() > HEIGHT,
                "开了自动加高却没长, 说明这个开关没被传下去: " + painter.getHeight());
    }

    @Test
    @DisplayName("每次要都是一张新画布")
    void everyCallGivesAFreshPainter() {
        CommonPainter first = factory.create(WIDTH, HEIGHT);
        CommonPainter second = factory.create(WIDTH, HEIGHT);

        assertNotSame(first, second, "共用一张画布会让两张报告互相画到对方身上");
        assertNotSame(first.getImage(), second.getImage());
    }

    @Test
    @DisplayName("字体表要跟着传下去，画得出的字量得出宽")
    void passesTheFontTableThrough() {
        CommonPainter painter = factory.create(WIDTH, HEIGHT);

        assertTrue(painter.getStringWidthAndHeight("量一串字").getFirst() > 0,
                "量不出宽说明字体表没传下去");
    }

    @Test
    @DisplayName("版本号要跟着传下去，画得出版权行")
    void passesTheBuildInfoThrough() {
        CommonPainter painter = factory.create(WIDTH, HEIGHT);

        assertDoesNotThrow(() -> painter.drawCopyright(10), "版权行要用到版本号, 没传下去这里会炸");
    }

    @Test
    @DisplayName("配置要跟着传下去，加高步长按配置走")
    void passesThePropertiesThrough() {
        properties.getPaint().setAutoExpandHeight(64);
        CommonPainter painter = factory.create(WIDTH, HEIGHT, true);

        painter.expandHeightIfNeeded(HEIGHT + 1);

        assertEquals(HEIGHT + 64, painter.getHeight(), "加高的步长应当来自配置项 novabot.core.paint.auto-expand-height");
    }
}
