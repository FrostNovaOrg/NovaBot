package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版权行不带仓库地址
 *
 * <h2>为什么会有这组判据</h2>
 * 版权行原先画着一条仓库地址，而这张图是<b>发到聊天里</b>的。
 * 仓库一旦改名、转私有或换账号，那条地址就指向一个不存在的地方，
 * 而收到图的人无从知道它已经失效——图早就发出去了，改不回来。
 * 判据钉的是「这里不画地址」，不是「地址写对了」：
 * 后者要有人一直保证它有效，这里保证不了。
 */
@DisplayName("版权行不带仓库地址")
class CommonPainterCopyrightTest {

    private CapturingPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        // 用内置字体，免得结论取决于跑测试这台机器装了什么字体
        properties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        painter = new CapturingPainter(new BuildProperties(buildInfo), properties, fontUtil);
    }

    @Test
    @DisplayName("🔴 版权行里不出现任何仓库地址")
    void drawsNoRepositoryAddress() {
        painter.drawCopyright(20);

        // 🔴 先证这一格量得到东西：一条都没画的话，下面那条断言会白白通过。
        //    「干净」和「压根没画」必须分得开
        assertFalse(painter.drawn.isEmpty(), "版权行一条都没画，这一格就什么都没量到");
        assertTrue(painter.drawn.stream().anyMatch(s -> s.contains("NovaBot")),
                "产品名该还在——去掉的是地址，不是整行");

        for (String text : painter.drawn) {
            assertFalse(text.toLowerCase().contains("github"),
                    "版权行画出了仓库地址: " + text);
            assertFalse(text.contains("://"),
                    "版权行画出了链接: " + text);
        }
    }

    /**
     * 把画出去的每一行右对齐文本记下来
     */
    private static final class CapturingPainter extends CommonPainter {
        private final List<String> drawn = new ArrayList<>();

        CapturingPainter(BuildProperties buildProperties, StarBotCoreProperties properties, FontUtil fontUtil) {
            super(buildProperties, properties, fontUtil, 900, 400, false);
        }

        @Override
        public CommonPainter drawTextRight(String text, Color color, int marginRight) {
            drawn.add(text);
            return super.drawTextRight(text, color, marginRight);
        }
    }
}
