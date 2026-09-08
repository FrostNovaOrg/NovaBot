package com.starlwr.bot.report.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕词云的<b>场景适应性</b>判据：一场直播能有 0 个词，也能有 72 个词
 *
 * <h2>为什么按场景分类量而不是只量一份语料</h2>
 * 上一版排版器只按「830×380 塞满 72 个词」调过。冷清场次交给它，十来个词摊在
 * 一整框里；调用层干脆在词数不足时整块不画。<b>词少的那些场次从没被任何判据看过</b>——
 * 它们不出图，也就没有图可以判对错。这把尺子把词量、词频形状、脏输入各排成一类，
 * 每一类都要交出<b>显示了几个词、没放下几个</b>的实数。
 * <p>
 * 🔴 <b>几何量的是排版结果里的矩形，不是成图上的墨。</b>两个词在图上挨得再近也还是墨，
 * 从像素反推不出「这一块属于哪个词」。成图那一面只判一件几何判不出来的事：
 * 一个词都没有时，图上要有「暂无有效弹幕词」那几个字（{@link #emptySceneStillDrawsSomething}）。
 *
 * <h2>语料是合成的</h2>
 * 词与词频都由固定种子造出来。真实场次的弹幕里带着观众昵称，不进夹具。
 */
@DisplayName("弹幕词云场景适应性")
class WordCloudScenarioTest {
    /**
     * 与 {@code BilibiliLiveReportPainter} 里的同名常量对齐
     */
    private static final int CONTENT_WIDTH = 830;

    private static final int CLOUD_MAX_HEIGHT = 380;

    private static final int CLOUD_MAX_WORDS = 72;

    /**
     * 🔴 下面这几个数字<b>写死在判据侧，不从被测那边取</b>。引 {@code WordCloudLayout.FRAME_MARGIN}
     * 看着更「不重复」，可那样一来把留白改成 0，判据会跟着一起松开，
     * 就再也逮不住它本来要逮的那件事
     */
    private static final int FRAME_MARGIN = 24;

    /**
     * 相邻两词至少隔开的横向与纵向距离。两轴各有一条线，满足其一即算分得开——
     * 一个词只在横向让开、纵向压着另一个词的半行，看上去仍是两个词
     */
    private static final int MIN_GAP_X = 6;

    private static final int MIN_GAP_Y = 5;

    /**
     * 字号上下限，量的是<b>落到图上的字号</b>。下限 18 是可读线：报告正文最小 22，
     * 词云尾词比正文再小一档还认得出，更小就只剩一团色块
     */
    private static final int FONT_SIZE_MIN = 18;

    private static final int FONT_SIZE_MAX = 56;

    /**
     * 场景跑用的种子。逐场固定的种子在生产里由开播时刻算出，这里取一个定值
     */
    private static final long SEED = 20_260_908L;

    private static FontUtil fontUtil;

    @BeforeAll
    static void setUpFont() {
        System.setProperty("java.awt.headless", "true");
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 用核心内置字体，免得版式结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();
    }

    /**
     * 一类场景：一份输入、一个画布高度，以及这一类该显示几个词、该有几个没放下
     *
     * @param name    场景名，读数表里按它列
     * @param words   交给排版器的词，可含脏数据
     * @param height  画布高度，按词量分档（分档表另有一把尺子逐档量），写死在判据侧
     * @param shown   期望显示的词数
     * @param dropped 期望没放下的词数
     */
    private record Scene(String name, List<WordCloudLayout.Word> words, int height, int shown, int dropped) {
    }

    /**
     * 判据①：15 类场景逐类量显示词数、未放入数、包围盒、留白、字号、可复现与输入顺序
     * <p>
     * 一类一行读数，全部合规才算绿；任何一条不合规都把那一类的实值报出来。
     * 🔴 三问不许 assert 短路：每一类的每一条都记进 {@code failures} 再在末尾一并判，
     * 否则第一类红掉之后，后面十四类这一趟等于没跑过
     */
    @Test
    @DisplayName("判据①：15 类场景逐类量显示词数、未放入数、无重叠、留白 24px、可复现、输入顺序无关")
    void fifteenScenesLayOutAsMeasured() throws Exception {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);
        List<String> readings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        readings.add("场景\t显示词数\t未放入\t三跑均值毫秒\t字号\t最小横距\t最小纵距");

        for (Scene scene : scenes()) {
            // 🔴 一类当场抛异常不许把后面十四类一起带走：抛出来的也是一条读数
            try {
                measureScene(renderer, scene, readings, failures);
            } catch (RuntimeException e) {
                failures.add(scene.name() + " 排版当场抛异常: " + e);
                readings.add(scene.name() + "\t—\t—\t—\t—\t—\t—");
            }
        }

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-scenarios.tsv"), String.join("\n", readings).getBytes());

        assertTrue(failures.isEmpty(), "逐类读数:\n" + String.join("\n", readings)
                + "\n不合规:\n" + String.join("\n", failures));
    }

    /**
     * 量一类场景：三跑（同种子两跑 + 输入顺序倒过来一跑），逐条记进 {@code failures}
     */
    private void measureScene(WordCloudRenderer renderer, Scene scene,
                              List<String> readings, List<String> failures) throws Exception {
        long start = System.nanoTime();
        WordCloudLayout.Result result =
                WordCloudLayout.layout(scene.words(), CONTENT_WIDTH, scene.height(), SEED, renderer);
        WordCloudLayout.Result again =
                WordCloudLayout.layout(scene.words(), CONTENT_WIDTH, scene.height(), SEED, renderer);
        List<WordCloudLayout.Word> shuffled = new ArrayList<>(scene.words());
        Collections.reverse(shuffled);
        WordCloudLayout.Result reversed =
                WordCloudLayout.layout(shuffled, CONTENT_WIDTH, scene.height(), SEED, renderer);
        long elapsedMillis = Math.round((System.nanoTime() - start) / 3e6);

        if (result.placements().size() != scene.shown()) {
            failures.add(scene.name() + " 显示 " + result.placements().size() + " 个词, 应为 " + scene.shown());
        }
        if (result.dropped() != scene.dropped()) {
            failures.add(scene.name() + " 未放入 " + result.dropped() + " 个词, 应为 " + scene.dropped());
        }

        // 同一颗种子两跑：先比排版结果，再比成图字节。只比排版结果的话，
        // 「摆的位置一样、画出来两张图」这件事仍然看不见
        if (!result.equals(again)) {
            failures.add(scene.name() + " 同种子两跑排版结果不同");
        }
        if (!Arrays.equals(png(renderer, result, scene.height()), png(renderer, again, scene.height()))) {
            failures.add(scene.name() + " 同种子两跑成图字节不同");
        }
        if (!result.equals(reversed)) {
            failures.add(scene.name() + " 输入顺序倒过来之后排版结果变了");
        }

        List<WordCloudLayout.Placement> placed = result.placements();
        int minGapX = Integer.MAX_VALUE;
        int minGapY = Integer.MAX_VALUE;
        int minFontSize = Integer.MAX_VALUE;
        int maxFontSize = 0;
        for (int i = 0; i < placed.size(); i++) {
            WordCloudLayout.Placement one = placed.get(i);
            Rectangle box = one.box();
            minFontSize = Math.min(minFontSize, one.fontSize());
            maxFontSize = Math.max(maxFontSize, one.fontSize());

            if (one.fontSize() < FONT_SIZE_MIN || one.fontSize() > FONT_SIZE_MAX) {
                failures.add(scene.name() + " 「" + one.text() + "」字号 " + one.fontSize()
                        + " 不在 " + FONT_SIZE_MIN + "～" + FONT_SIZE_MAX);
            }
            if (box.x < FRAME_MARGIN || box.y < FRAME_MARGIN
                    || box.getMaxX() > CONTENT_WIDTH - FRAME_MARGIN
                    || box.getMaxY() > scene.height() - FRAME_MARGIN) {
                failures.add(scene.name() + " 「" + one.text() + "」离框边不足 " + FRAME_MARGIN + "px " + box
                        + "（框 " + CONTENT_WIDTH + "×" + scene.height() + "）");
            }

            for (int j = i + 1; j < placed.size(); j++) {
                Rectangle other = placed.get(j).box();
                int gapX = gap(box.x, box.width, other.x, other.width);
                int gapY = gap(box.y, box.height, other.y, other.height);
                minGapX = Math.min(minGapX, gapX);
                minGapY = Math.min(minGapY, gapY);
                if (gapX < MIN_GAP_X && gapY < MIN_GAP_Y) {
                    failures.add(scene.name() + " 「" + one.text() + "」与「" + placed.get(j).text()
                            + "」横隔 " + gapX + "px 纵隔 " + gapY + "px, 两轴都不足（"
                            + MIN_GAP_X + "／" + MIN_GAP_Y + "）" + box + " 与 " + other);
                }
            }
        }

        readings.add(String.format("%s\t%d\t%d\t%d\t%s\t%s\t%s", scene.name(), placed.size(), result.dropped(),
                elapsedMillis,
                placed.isEmpty() ? "—" : minFontSize + "~" + maxFontSize,
                minGapX == Integer.MAX_VALUE ? "—" : String.valueOf(minGapX),
                minGapY == Integer.MAX_VALUE ? "—" : String.valueOf(minGapY)));
    }

    /**
     * 判据①的成图一面：一个有效词都没有时，图上要有话说
     * <p>
     * 几何上「没有词」与「排版器崩了只剩空结果」是同一个空列表，分不出来。
     * 这一条量成图：透明底上得有墨，而且那几个字要落在框里
     */
    @Test
    @DisplayName("判据①（成图）：0 个有效词时画出空态，不是一张空图")
    void emptySceneStillDrawsSomething() throws Exception {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);
        int height = 96;
        WordCloudLayout.Result result = WordCloudLayout.layout(List.of(), CONTENT_WIDTH, height, SEED, renderer);
        BufferedImage image = renderer.render(result, CONTENT_WIDTH, height);

        int ink = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    ink++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        String reading = "空态图 " + CONTENT_WIDTH + "×" + height + ", 墨点 " + ink
                + ", 墨迹外框 [" + minX + "," + minY + "]～[" + maxX + "," + maxY + "]\n";
        Files.write(dir.resolve("wordcloud-empty.txt"), reading.getBytes());
        ImageIO.write(image, "png", dir.resolve("wordcloud-empty.png").toFile());

        List<String> failures = new ArrayList<>();
        if (ink <= 0) {
            failures.add("0 个有效词时图上一个墨点都没有, 报告里会留一块什么都没有的空白");
        }
        if (ink > 0 && (minX < 0 || minY < 0 || maxX >= image.getWidth() || maxY >= image.getHeight())) {
            failures.add("空态文字越出了画布");
        }
        assertTrue(failures.isEmpty(), reading + String.join("\n", failures));
    }

    /**
     * 15 类场景。词量一族取合成语料的前 n 名，其余各类按名字造
     */
    private static List<Scene> scenes() {
        List<WordCloudLayout.Word> ranked = rankedCorpus();

        List<Scene> scenes = new ArrayList<>();
        scenes.add(new Scene("00-没有有效词", List.of(), 96, 0, 0));
        scenes.add(new Scene("01-只有一个词", flat("晚上好"), 128, 1, 0));
        scenes.add(new Scene("02-只有两个词", ranked.subList(0, 2), 128, 2, 0));
        scenes.add(new Scene("03-只有三个词", ranked.subList(0, 3), 128, 3, 0));
        scenes.add(new Scene("05-五个低频词", flat("晚上好", "来了", "好听", "晚安", "谢谢"), 200, 5, 0));
        scenes.add(new Scene("12-词量场景", ranked.subList(0, 12), 240, 12, 0));
        scenes.add(new Scene("13-词量场景", ranked.subList(0, 13), 300, 13, 0));
        scenes.add(new Scene("24-词量场景", ranked.subList(0, 24), 300, 24, 0));
        scenes.add(new Scene("40-词量场景", ranked.subList(0, 40), CLOUD_MAX_HEIGHT, 40, 0));
        scenes.add(new Scene("72-词量场景", ranked, CLOUD_MAX_HEIGHT, 72, 0));

        // 全场词频一模一样：没有「相对大小」可言，谁大谁小只能靠排序定死
        scenes.add(new Scene("73-词频完全相同",
                ranked.stream().map(word -> new WordCloudLayout.Word(word.text(), 1)).toList(),
                CLOUD_MAX_HEIGHT, 72, 0));

        // 一句话刷屏：头名词频甩开第二名五个数量级
        List<WordCloudLayout.Word> flooded = new ArrayList<>(ranked);
        flooded.set(0, new WordCloudLayout.Word(flooded.get(0).text(), 1_000_000));
        scenes.add(new Scene("74-一个词反复刷屏", flooded, CLOUD_MAX_HEIGHT, 72, 0));

        // 中英数字与整句：分词器交出来的词长 2～8，这一类防的是别的数据源直连排版器
        scenes.add(new Scene("75-中英数字和长句", List.of(
                new WordCloudLayout.Word("这是一整句非常非常长的弹幕用来检查布局是否会越过边界而影响整张图", 100),
                new WordCloudLayout.Word("Welcome", 60),
                new WordCloudLayout.Word("Minecraft", 35),
                new WordCloudLayout.Word("今天也辛苦啦", 22),
                new WordCloudLayout.Word("2333333", 18),
                new WordCloudLayout.Word("生日快乐", 9),
                new WordCloudLayout.Word("888", 4),
                new WordCloudLayout.Word("谢谢主播", 3)), 200, 8, 0));

        // 脏输入：null 元素、null 词、纯空白、零与负计数、首尾空格的重复词
        // 🔴 高度按<b>有效</b>词数（2 个）分档，与调用层同口径——调用层先滤后算高度
        scenes.add(new Scene("76-空白重复无效计数", Arrays.asList(
                null,
                new WordCloudLayout.Word(null, 3),
                new WordCloudLayout.Word("  ", 3),
                new WordCloudLayout.Word("零", 0),
                new WordCloudLayout.Word("负数", -1),
                new WordCloudLayout.Word(" 晚安 ", Integer.MAX_VALUE),
                new WordCloudLayout.Word("晚安", 5),
                new WordCloudLayout.Word("谢谢", 3)), 128, 2, 0));

        // 容量边界：72 个 8 码点的标签装不进 830×380，放不下的要如实计入 dropped
        // 🔴 37／35 是<b>用本仓内置字体</b>量出来的。这一类的显示词数取决于字形有多宽，
        // 换一套字体就换一个数——排版器算得对不对与它无关，故这两个数只钉住
        // 「本仓这套字体下的容量」，改动它的是字体或排版规则，不是别的
        List<WordCloudLayout.Word> longLabels = new ArrayList<>();
        for (int i = 0; i < CLOUD_MAX_WORDS; i++) {
            longLabels.add(new WordCloudLayout.Word(String.format("弹幕场景词条%02d", i), 100 - i));
        }
        scenes.add(new Scene("77-大量较长标签", longLabels, CLOUD_MAX_HEIGHT, 37, 35));

        return scenes;
    }

    /**
     * 造一份 72 词的合成语料，按词频降序。同一份代码每次跑出同一份语料
     */
    private static List<WordCloudLayout.Word> rankedCorpus() {
        Random random = new Random(SEED);
        String pool = "晚上好听唱歌打游戏厉害加油可爱笑死太强岁月史书下次一定主播前排签到早安冲鸭泪目破防上号整活求歌单可以再来";
        Map<String, Integer> words = new LinkedHashMap<>();
        int count = 99;
        while (words.size() < CLOUD_MAX_WORDS) {
            StringBuilder word = new StringBuilder();
            int length = 2 + random.nextInt(3);
            for (int i = 0; i < length; i++) {
                word.append(pool.charAt(random.nextInt(pool.length())));
            }
            if (words.putIfAbsent(word.toString(), count) == null) {
                count = Math.max(1, count - 1 - random.nextInt(3));
            }
        }
        return words.entrySet().stream()
                .map(entry -> new WordCloudLayout.Word(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 一组词频全为 1 的词
     */
    private static List<WordCloudLayout.Word> flat(String... texts) {
        return Arrays.stream(texts).map(text -> new WordCloudLayout.Word(text, 1)).toList();
    }

    private static byte[] png(WordCloudRenderer renderer, WordCloudLayout.Result result, int height) throws Exception {
        BufferedImage image = renderer.render(result, CONTENT_WIDTH, height);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ImageIO.write(image, "png", sink);
        return sink.toByteArray();
    }

    /**
     * 两个区间在一根轴上隔开多少；重叠时为负
     */
    private static int gap(int aStart, int aLength, int bStart, int bLength) {
        return Math.max(bStart - (aStart + aLength), aStart - (bStart + bLength));
    }
}
