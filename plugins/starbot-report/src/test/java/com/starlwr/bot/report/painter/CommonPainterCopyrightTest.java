package com.starlwr.bot.report.painter;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.report.util.FontUtil;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版权行带发布仓地址
 *
 * <h2>这组判据换过一次方向</h2>
 * 原先钉的是「这里不画地址」，理由是仓库改名或换账号之后，发到聊天里的图会带着
 * 一条指向不存在地方的地址，而收到图的人无从知道它已经失效。
 * <p>
 * 现在钉的是反过来的一条：<b>{@code FrostNovaOrg/NovaBot} 就是宣告的发布仓</b>——
 * 「地址会失效」这一顾虑的前提是没人为它负责，而发布仓有人负责。
 * 一张不写出处的图，收到的人拿它没办法：既问不出这是什么程序，也找不到它从哪来。
 * <p>
 * 🔴 <b>判据钉的是「这一行长什么样」，不是「地址能不能打开」</b>——后者要联网才答得出，
 * 而一条要联网才跑得动的判据，会在断网那天变成一条没人相信的红。
 * 地址有没有失效由发布仓自己保证，不由这一格保证。
 *
 * <h2>它量不到什么</h2>
 * 这一组覆写 {@code drawTextRight} 收文本，拦的是<b>谁调画法</b>：
 * 换一条画法把这一行画进图，这一组照绿。成图那一层由
 * {@link CommonPainterLinkColorPixelTest} 数像素。两组不同层，谁也不替谁。
 */
@DisplayName("版权行带发布仓地址")
class CommonPainterCopyrightTest {

    /**
     * 发布仓地址的<b>唯一书写形态</b>：不带 {@code https://} 前缀
     * <p>
     * 写成裸的主机加路径，而不是一条完整链接：图里的字不可点，带上协议头只是让这一行更长；
     * 而更长的一行在窄图上更容易被挤出右边距。
     */
    private static final String REPOSITORY = "github.com/FrostNovaOrg/NovaBot";

    private static final String VERSION = "4.3.0";

    /**
     * 版权行的字号：<b>脚注档</b>（比正文档小一档）。数字写死在判据侧——
     * 这一行是出处不是内容，正文档的宽度在 900 宽的图上几乎顶到左边
     */
    private static final int COPYRIGHT_FONT_SIZE = 25;

    private CapturingPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        // 用内置字体，免得结论取决于跑测试这台机器装了什么字体
        properties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", VERSION);
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "NovaBot");

        painter = new CapturingPainter(new BuildProperties(buildInfo), properties, fontUtil);
    }

    @Test
    @DisplayName("🔴 版权行里出现发布仓地址，且与产品名同在一行")
    void drawsRepositoryAddress() {
        painter.drawCopyright(20);

        // 🔴 先证这一格量得到东西：一条都没画的话，下面那些断言里的「找到了」会无从谈起，
        //    而「没画」和「画错了」在一条 anyMatch 上长得一样
        assertFalse(painter.drawn.isEmpty(), "版权行一条都没画，这一格就什么都没量到");

        assertEquals(List.of("NovaBot v" + VERSION + " · " + REPOSITORY), painter.drawn,
                "版权行不是约定的那一行");

        // 分开再钉一次：上面那条整行相等的断言一旦被谁改宽，这两条仍然拦得住
        // 「产品名没了」与「地址没了」这两种改法
        assertTrue(painter.drawn.stream().anyMatch(text -> text.contains("NovaBot v" + VERSION)),
                "版权行该带着产品名与版本");
        assertTrue(painter.drawn.stream().anyMatch(text -> text.contains(REPOSITORY)),
                "版权行该带着发布仓地址");
        assertTrue(painter.sizes.stream().allMatch(size -> size == COPYRIGHT_FONT_SIZE),
                "版权行该用脚注档 " + COPYRIGHT_FONT_SIZE + ", 实际 " + painter.sizes);
    }

    @Test
    @DisplayName("🔴 地址不写成完整链接：不带协议头")
    void addressCarriesNoScheme() {
        painter.drawCopyright(20);

        assertFalse(painter.drawn.isEmpty(), "版权行一条都没画，这一格就什么都没量到");
        for (String text : painter.drawn) {
            assertFalse(text.contains("://"), "版权行写成了完整链接: " + text);
        }
    }

    @Test
    @DisplayName("🔴 附加版权信息照旧排在默认那一行之后")
    void extraCopyrightsFollowTheDefaultLine() {
        painter.drawCopyright(20);
        int lines = painter.drawn.size();

        assertEquals(1, lines, "默认版权只该有一行，附加信息由入参与配置各自补");
    }

    /**
     * 把画出去的每一行右对齐文本与字号记下来。
     * 覆写的是 {@code drawTextRightWithStyle}：默认版权行走的是含格式那一层，
     * 换一条不带格式的画法把这一行画进图，这一组照样量得到
     */
    private static final class CapturingPainter extends CommonPainter {
        private final List<String> drawn = new ArrayList<>();

        private final List<Integer> sizes = new ArrayList<>();

        CapturingPainter(BuildProperties buildProperties, NovaCoreProperties properties, FontUtil fontUtil) {
            super(buildProperties, properties, fontUtil, 900, 400, false);
        }

        @Override
        public CommonPainter drawTextRightWithStyle(List<TextWithStyle> texts, int marginRight) {
            texts.forEach(text -> {
                drawn.add(text.getText());
                sizes.add(text.getSize());
            });
            return super.drawTextRightWithStyle(texts, marginRight);
        }
    }
}
