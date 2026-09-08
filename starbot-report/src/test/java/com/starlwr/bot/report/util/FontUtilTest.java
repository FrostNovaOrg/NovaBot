package com.starlwr.bot.report.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.util.Pair;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 字体加载与文字量宽
 *
 * <h2>为什么会有这组判据</h2>
 * 每一张报告图上的每一个字都从这里过两趟：先按字挑一个显示得出它的字体，再拿这个字体量宽。
 * 量错了不会报错，只会让某一行字<b>溢出版面</b>或者<b>被截在半路</b>；
 * 挑错了则是那个字变成一个空豆腐块。两种都只在图发出去之后才看得见。
 * <p>
 * 判据一律只用<b>内置字体</b>作参照物（{@code classpath:fonts/font.ttf}），
 * 不去指望跑测试这台机器装了什么字体——那样量出来的结论换台机器就不成立。
 */
@DisplayName("字体加载与文字量宽")
class FontUtilTest {
    private static final int BUILT_IN_SIZE = 30;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 按给定的字体表造一个已初始化的字体工具
     * <p>
     * 不走 {@code StarBotCoreProperties} 自己那份按操作系统挑的默认表：那份表的内容
     * 取决于这台机器是什么系统，拿它当参照物量出来的结论换台机器就不成立。
     */
    private static FontUtil fontUtil(String... fontDefinitions) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPaint().getFonts().addAll(List.of(fontDefinitions));

        FontUtil util = new FontUtil(new DefaultResourceLoader(), properties);
        util.init();
        return util;
    }

    private static List<String> logsOf(Level level, Supplier<?> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(FontUtil.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            action.get();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Nested
    @DisplayName("解析单个字体")
    class Parsing {
        @Test
        @DisplayName("内置字体总是加载得到")
        void loadsBuiltInFont() {
            Font font = fontUtil().parseFont("内置").orElseThrow();

            assertEquals(BUILT_IN_SIZE, font.getSize(), "解析出来的字体一律是这个号, 后面再按需要 derive");
            assertEquals(Font.PLAIN, font.getStyle());
        }

        @Test
        @DisplayName("系统字体按名字取，大小写不计")
        void loadsSystemFontIgnoringCase() {
            FontUtil util = fontUtil();

            assertTrue(util.parseFont("SansSerif").isPresent());
            assertTrue(util.parseFont("sansserif").isPresent(), "使用者在 yml 里怎么写大小写都该认");
            assertEquals(BUILT_IN_SIZE, util.parseFont("SansSerif").orElseThrow().getSize());
        }

        @Test
        @DisplayName("字体文件按路径取，扩展名大小写不计")
        void loadsFontFileByPath(@TempDir Path directory) throws IOException {
            Path lower = copyBuiltInFontTo(directory.resolve("custom.ttf"));
            Path upper = copyBuiltInFontTo(directory.resolve("custom-upper.TTF"));

            FontUtil util = fontUtil();

            assertTrue(util.parseFont(lower.toString()).isPresent());
            assertTrue(util.parseFont(upper.toString()).isPresent(), "扩展名写成大写的也该认");
        }

        @Test
        @DisplayName("路径指向的文件不存在时给空，不抛异常")
        void missingFontFileYieldsEmpty(@TempDir Path directory) {
            FontUtil util = fontUtil();

            assertTrue(util.parseFont(directory.resolve("not-exists.ttf").toString()).isEmpty());
        }

        @Test
        @DisplayName("既不是系统字体也不是字体文件时，要说一句再给空")
        void unknownFontWarnsOnce() {
            FontUtil util = fontUtil();

            List<String> warnings = logsOf(Level.WARN, () -> util.parseFont("绝无此字体"));

            assertEquals(1, warnings.size(), "认不出的字体应当正好说一句: " + warnings);
            assertTrue(warnings.get(0).contains("绝无此字体"),
                    "提示里要带上使用者写的那个名字, 否则不知道是哪一项写错了: " + warnings.get(0));
        }

        @Test
        @DisplayName("null 也只是给空，不抛异常")
        void nullFontDefinitionYieldsEmpty() {
            FontUtil util = fontUtil();

            assertTrue(util.parseFont(null).isEmpty(), "加载字体的失败一律收敛成空, 不许炸到调用方");
        }

        private Path copyBuiltInFontTo(Path target) throws IOException {
            try (InputStream stream = FontUtilTest.class.getResourceAsStream("/fonts/font.ttf")) {
                assertTrue(stream != null, "内置字体不在类路径上");
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }
    }

    @Nested
    @DisplayName("按配置装字体表")
    class Loading {
        @Test
        @DisplayName("装出来的顺序就是配置里的顺序")
        void keepsConfiguredOrder() {
            List<String> names = fontUtil("内置", "SansSerif").getFontNames();

            assertEquals(2, names.size());
            assertEquals("SansSerif", names.get(1), "顺序决定了挑字体时谁先被问到");
        }

        @Test
        @DisplayName("装不上的那一项跳过，不占位置")
        void skipsUnloadableEntries() {
            List<String> names = fontUtil("绝无此字体", "内置").getFontNames();

            assertEquals(1, names.size(), "装不上的不该留一个 null 在表里: " + names);
        }

        /**
         * 启动时那句提示要带上配置键的全名——使用者看到字体不对时，
         * 得从这一句里知道去哪个配置项改。
         */
        @Test
        @DisplayName("启动时要报出字体表与改它的配置项")
        void announcesConfiguredFontsAndTheKey() {
            List<String> messages = logsOf(Level.INFO, () -> {
                fontUtil("内置");
                return null;
            });

            assertEquals(1, messages.size(), "启动时正好说一句: " + messages);
            assertTrue(messages.get(0).contains("starbot.core.paint.fonts"),
                    "要指出改哪个配置项: " + messages.get(0));
            assertTrue(messages.get(0).contains("内置"), "要报出实际用的那张表: " + messages.get(0));
        }
    }

    @Nested
    @DisplayName("按字挑字体")
    class Picking {
        @Test
        @DisplayName("第一个字体显示得出这个字时就用它")
        void takesTheFirstFontThatCanShowIt() {
            FontUtil util = fontUtil("内置", "SansSerif");
            Font builtIn = util.parseFont("内置").orElseThrow();

            int shown = IntStream.rangeClosed(0x20, 0xFFFF)
                    .filter(builtIn::canDisplay)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("内置字体一个字都显示不出, 判据自己错了"));

            assertEquals(util.getFontNames().get(0), util.findFontForCharacter(shown).getName(),
                    "第一个字体显示得出就不该往后找");
        }

        /**
         * 内置字体与系统逻辑字体的覆盖面必定不同，但哪一边多哪一边少取决于这台机器
         * 装了什么字体——所以两种顺序都试，用真的分得开的那一种来量。
         */
        @Test
        @DisplayName("第一个字体显示不出的字，要交给后面的字体")
        void skipsFontsThatCannotShowTheCharacter() {
            for (String[] order : List.of(new String[]{"内置", "SansSerif"}, new String[]{"SansSerif", "内置"})) {
                FontUtil util = fontUtil(order);
                Font first = util.parseFont(order[0]).orElseThrow();
                Font second = util.parseFont(order[1]).orElseThrow();

                OptionalInt discriminating = IntStream.rangeClosed(0x20, 0xFFFF)
                        .filter(codePoint -> !first.canDisplay(codePoint) && second.canDisplay(codePoint))
                        .findFirst();
                if (discriminating.isEmpty()) {
                    continue;
                }

                List<String> names = util.getFontNames();
                assertEquals(2, names.size());
                assertNotEquals(names.get(0), names.get(1), "两项得是不同的字体, 否则这条判据量不出东西");
                assertEquals(names.get(1), util.findFontForCharacter(discriminating.getAsInt()).getName(),
                        "第一个字体显示不出 U+" + Integer.toHexString(discriminating.getAsInt())
                                + ", 应当交给第二个而不是硬用第一个");
                return;
            }

            fail("内置字体与系统字体在整个基本平面上覆盖面完全一致, 这条判据量不出东西");
        }

        /**
         * 一个字体都显示不出时回落到<b>表里第一个</b>，而不是抛错也不是给 null——
         * 画出来是个豆腐块，但整张图还在。
         */
        @Test
        @DisplayName("一个都显示不出时回落到表里第一个")
        void fallsBackToTheFirstFont() {
            FontUtil util = fontUtil("内置");
            Font builtIn = util.parseFont("内置").orElseThrow();

            OptionalInt unsupported = IntStream.rangeClosed(0x20, 0xFFFF)
                    .filter(codePoint -> !builtIn.canDisplay(codePoint))
                    .findFirst();
            assertTrue(unsupported.isPresent(), "内置字体不可能覆盖整个基本平面, 找不到说明判据自己错了");

            assertEquals(util.getFontNames().get(0),
                    util.findFontForCharacter(unsupported.getAsInt()).getName());
        }

        /**
         * 配置里一个字体都装不上时，画图会在挑字体这一步当场抛。
         * 钉住是因为这条路很容易在改写时被「顺手补个默认字体」抹掉——
         * 那样问题会从「启动就炸」变成「图上全是豆腐块」，更难查。
         */
        @Test
        @DisplayName("一个字体都没装上时当场抛，不是悄悄画成空白")
        void emptyFontTableThrows() {
            FontUtil util = fontUtil("绝无此字体");

            assertEquals(List.of(), util.getFontNames());
            assertThrows(IndexOutOfBoundsException.class, () -> util.findFontForCharacter('字'));
        }
    }

    @Nested
    @DisplayName("量文字宽高")
    class Measuring {
        private final BufferedImage canvas = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        private final Graphics2D draw = canvas.createGraphics();
        private final FontUtil util = fontUtil("内置");

        private Pair<Integer, Integer> measure(String text) {
            return util.getStringWidthAndHeight(draw, new TextWithStyle(text, BUILT_IN_SIZE));
        }

        @Test
        @DisplayName("空文本量出来是零，不是某个行高")
        void emptyTextMeasuresZero() {
            assertEquals(0, measure("").getFirst());
            assertEquals(0, measure("").getSecond(), "空串不占高度, 否则空行会把版面撑开");
            assertEquals(0, measure(null).getFirst(), "null 与空串同一路, 不许抛");
            assertEquals(0, measure(null).getSecond());
        }

        /**
         * 🔴 阳性对照：这把尺子量得出宽窄。
         * <p>
         * 少了它，下面几条在「一律返回 0」时全是绿的。
         */
        @Test
        @DisplayName("判据自己先能分出长短")
        void theRulerTellsLongFromShort() {
            assertTrue(measure("wwwwwwwwww").getFirst() > measure("i").getFirst());
            assertTrue(measure("字").getSecond() > 0, "行高得是个正数");
        }

        @Test
        @DisplayName("整串的宽度就是逐字宽度之和")
        void widthIsTheSumOfItsCharacters() {
            int together = measure("字体").getFirst();
            int apart = measure("字").getFirst() + measure("体").getFirst();

            assertEquals(apart, together, "现码是逐字累加, 没有字距调整; 改成整串量会得出别的数");
        }

        /**
         * 增补平面的字（一个字占两个 char）必须按<b>一个字</b>量。
         * 拆成两个孤立的代理项去量，得到的是两个「显示不出」的宽度，
         * 表情符号那一路的版面就全错了。
         */
        @Test
        @DisplayName("增补平面的字按一个字量")
        void measuresSupplementaryCharactersAsOne() {
            String clef = new String(Character.toChars(0x1D11E));
            Font builtIn = util.parseFont("内置").orElseThrow().deriveFont(Font.PLAIN, (float) BUILT_IN_SIZE);

            int measured = measure(clef).getFirst();

            assertTrue(measured > 0, "量出 0 的话这条判据分不出对错");
            assertEquals(draw.getFontMetrics(builtIn).stringWidth(clef), measured,
                    "按 char 逐个量会把一个字拆成两个孤立代理项, 得到的是另一个数");
        }

        @Test
        @DisplayName("高度取的是字体的行高")
        void heightIsTheFontLineHeight() {
            Font builtIn = util.parseFont("内置").orElseThrow().deriveFont(Font.PLAIN, (float) BUILT_IN_SIZE);

            assertEquals(draw.getFontMetrics(builtIn).getHeight(), measure("字").getSecond());
        }

        @Test
        @DisplayName("指定了字体就用指定的那个，字号也照办")
        void honoursExplicitFontAndSize() {
            Font builtIn = util.parseFont("内置").orElseThrow();

            int small = util.getStringWidthAndHeight(draw,
                    new TextWithStyle("Hello", builtIn, 20, Color.BLACK, Font.PLAIN)).getFirst();
            int large = util.getStringWidthAndHeight(draw,
                    new TextWithStyle("Hello", builtIn, 40, Color.BLACK, Font.PLAIN)).getFirst();

            assertTrue(large > small * 1.5, "字号翻倍宽度该跟着涨: " + small + " → " + large);
        }

        /**
         * 🔴 量完必须把画笔的字体放回去。
         * <p>
         * 量宽这个动作会在画笔上换字体（逐字换），漏了这一步，
         * 下一笔画上去的字就会用最后量到的那个字的字体和字号——
         * 而这种错只在混排的那几行上出现。
         */
        @Test
        @DisplayName("量完要把画笔的字体放回原样")
        void restoresTheGraphicsFontAfterMeasuring() {
            Font marker = new Font("SansSerif", Font.BOLD, 7);
            draw.setFont(marker);

            measure("量一串字abc");

            assertSame(marker, draw.getFont(), "量宽不该留下副作用");
        }

        @Test
        @DisplayName("空文本时也不动画笔的字体")
        void doesNotTouchTheGraphicsFontForEmptyText() {
            Font marker = new Font("SansSerif", Font.BOLD, 7);
            draw.setFont(marker);

            measure("");

            assertSame(marker, draw.getFont());
        }
    }
}
