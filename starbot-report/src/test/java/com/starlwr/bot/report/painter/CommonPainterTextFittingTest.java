package com.starlwr.bot.report.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.report.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把文本装进给定宽度里
 *
 * <h2>为什么会有这组判据</h2>
 * 报告与数据查询图里的昵称原先是按<b>字数</b>截的（「最多 12 个字」）。
 * 12 个全角汉字比 12 个半角字母宽一倍多，而昵称里两者混着来——
 * 于是<b>版面安不安全取决于用户起了什么名字</b>。2026-08-14 那场报告的溢出
 * 就是同一族的毛病：没有任何一处真的量过宽度。
 */
@DisplayName("文本按宽度收")
class CommonPainterTextFittingTest {
    private static final int SIZE = 24;

    private CommonPainter painter;

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

        painter = new StarBotCommonPainterFactory(new BuildProperties(buildInfo), properties, fontUtil)
                .create(900, 400, false);
    }

    private TextWithStyle plain(String text) {
        return new TextWithStyle(text, SIZE, Color.BLACK, Font.PLAIN);
    }

    private int width(String text) {
        return painter.getStringWidthAndHeight(plain(text)).getFirst();
    }

    /**
     * 🔴 阳性对照：这把尺子分得出宽窄
     * <p>
     * 少了它，下面的判据在「宽度一律返回 0」时全都是绿的。
     */
    @Test
    @DisplayName("判据自己先能分出全角比半角宽")
    void theRulerTellsWideFromNarrow() {
        assertTrue(width("十二个字的名字测试一下") > width("abcdefghijkl"),
                "12 个全角字应当明显比 12 个半角字母宽, 量不出差别说明这把尺子没在量真宽度");
    }

    /**
     * 🔴 那个 style 重载得真的认粗细
     * <p>
     * 它若只是转给「只传字号」的那个重载，粗体量出来会与常规一样宽——
     * 拿常规的宽度排粗体的版，量出来的是个下界，<b>够用的结论会变成不够用的实物</b>。
     */
    @Test
    @DisplayName("量宽度时认粗体")
    void boldMeasuresWiderThanPlain() {
        String text = "本场收益 ¥1234567.8";

        int plain = painter.getStringWidthAndHeight(new TextWithStyle(text, SIZE, Color.BLACK, Font.PLAIN)).getFirst();
        int bold = painter.getStringWidthAndHeight(new TextWithStyle(text, SIZE, Color.BLACK, Font.BOLD)).getFirst();

        assertTrue(bold > plain, "粗体 " + bold + "px 不比常规 " + plain + "px 宽, 说明这个重载没认粗细");
    }

    /**
     * 反面：装得下的不许动
     * <p>
     * 少了它，无条件加省略号也能让下面那条判据变绿。
     */
    @Test
    @DisplayName("本来就装得下的原样返回")
    void keepsTextThatAlreadyFits() {
        String text = "短名字";

        assertSame(text, painter.truncateToWidth(plain(text), width(text)),
                "刚好装得下也算装得下, 不该截");
        assertEquals(text, painter.truncateToWidth(plain(text), 900));
    }

    /**
     * 🔴 本体：截完必须真的装得下
     * <p>
     * 省略号自己也占宽度。忘了把它算进去的话，截出来的东西照样溢出，
     * 而「截过了」这件事会让人以为已经处理过。
     */
    @Test
    @DisplayName("截断后连省略号一起量也装得下")
    void truncatedTextActuallyFits() {
        String text = "三十个字的超长主播名字这里再补上一些字凑够三十个字看看";

        for (int limit : new int[]{60, 120, 240, 360}) {
            String cut = painter.truncateToWidth(plain(text), limit);

            assertTrue(width(cut) <= limit,
                    "限宽 " + limit + "px, 截出来的「" + cut + "」却宽 " + width(cut) + "px");
            assertTrue(cut.endsWith(CommonPainter.ELLIPSIS), "截过就该留下记号: " + cut);
        }
    }

    /**
     * 🔴 不许把 emoji 劈成半个
     * <p>
     * 原先按 {@code substring} 切，切在增补平面字符中间会留下一个孤立的代理项，
     * 显示出来是个方块或问号。<b>昵称里 emoji 很常见</b>，这不是边角情形。
     */
    @Test
    @DisplayName("截断不会把 emoji 劈成半个代理项")
    void neverSplitsASurrogatePair() {
        String text = "🎀🎀🎀🎀🎀🎀🎀🎀🎀🎀";

        for (int limit = 30; limit <= 240; limit += 10) {
            String cut = painter.truncateToWidth(plain(text), limit);

            // 成对的要一次跨过两个 char，否则下一轮读到的正是那个合法的低位代理项，
            // 判据会把自己走错步当成被测对象的毛病（第一版就是这么红的）
            for (int i = 0; i < cut.length(); ) {
                char c = cut.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < cut.length() && Character.isLowSurrogate(cut.charAt(i + 1)),
                            "限宽 " + limit + "px 时切出了半个代理项: " + cut);
                    i += 2;
                } else {
                    assertFalse(Character.isLowSurrogate(c),
                            "限宽 " + limit + "px 时切出了半个代理项: " + cut);
                    i++;
                }
            }
        }
    }

    /**
     * 宽度小到连省略号都放不下时
     * <p>
     * 返回空串而不是硬塞一个省略号：塞进去照样溢出，而调用方拿到空串，
     * 至少看得出「这里的宽度给得不对」。
     */
    @Test
    @DisplayName("连省略号都放不下时返回空串")
    void returnsEmptyWhenEvenTheEllipsisDoesNotFit() {
        assertEquals("", painter.truncateToWidth(plain("很长的一段名字"), 1));
    }
}
