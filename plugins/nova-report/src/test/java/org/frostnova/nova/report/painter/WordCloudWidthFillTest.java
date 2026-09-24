package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕词云的<b>横向铺满</b>判据：词团外接框要撑开到版面宽的大部分
 *
 * <h2>抓的用户故障</h2>
 * 词云只占中间一块，两侧大片空白——字小、团窄，横向版面白白让出去。
 * 这把尺子只量一件事：<b>外接框宽度占版面宽的几成</b>。词多时要求更满
 * （≥13 个词至少 85%），词少时允许松一点（≤12 个词至少 70%）。
 *
 * <h2>五组语料</h2>
 * 5、12、20、40、72 个词各一组，词频按 Zipf 造、种子固定。
 * 少词与多词走的是两条排版路径，两条都得铺开。
 *
 * <h2>顺带量两件几何底线</h2>
 * 词不许重叠、不许出画布——铺开不能以压词或越界换。
 */
@DisplayName("弹幕词云横向铺满")
class WordCloudWidthFillTest {
    /**
     * 与 {@code BilibiliLiveReportPainter} 里的同名常量对齐
     */
    private static final int CONTENT_WIDTH = 830;

    /**
     * 外接框宽度占版面宽的下限：13 个词起
     */
    private static final double WIDTH_RATIO_DENSE = 0.85;

    /**
     * 外接框宽度占版面宽的下限：12 个词及以内
     */
    private static final double WIDTH_RATIO_SPARSE = 0.70;

    /**
     * 相邻两词至少隔开：横 6px <b>或</b>纵 5px，与另外两把几何尺同线
     */
    private static final int MIN_GAP_X = 6;

    private static final int MIN_GAP_Y = 5;

    private static final int FRAME_MARGIN = 24;

    /**
     * 词云块按词量分档的高度，写死在判据侧，不从被测那边取
     */
    private static final int HEIGHT_5 = 200;

    private static final int HEIGHT_12 = 240;

    private static final int HEIGHT_20 = 300;

    private static final int HEIGHT_40 = 380;

    private static final int HEIGHT_72 = 380;

    private static FontUtil fontUtil;

    @BeforeAll
    static void setUpFont() {
        System.setProperty("java.awt.headless", "true");
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        // 用核心内置字体，免得版式结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();
    }

    @Test
    @DisplayName("5 个词：外接框宽至少占版面七成")
    void fiveWordsFillMostOfTheWidth() {
        assertSpread(zipfWords(5, 20_260_924L), HEIGHT_5, WIDTH_RATIO_SPARSE);
    }

    @Test
    @DisplayName("12 个词：外接框宽至少占版面七成")
    void twelveWordsFillMostOfTheWidth() {
        assertSpread(zipfWords(12, 20_260_925L), HEIGHT_12, WIDTH_RATIO_SPARSE);
    }

    @Test
    @DisplayName("20 个词：外接框宽至少占版面八成五")
    void twentyWordsFillMostOfTheWidth() {
        assertSpread(zipfWords(20, 20_260_926L), HEIGHT_20, WIDTH_RATIO_DENSE);
    }

    @Test
    @DisplayName("40 个词：外接框宽至少占版面八成五")
    void fortyWordsFillMostOfTheWidth() {
        assertSpread(zipfWords(40, 20_260_927L), HEIGHT_40, WIDTH_RATIO_DENSE);
    }

    @Test
    @DisplayName("72 个词：外接框宽至少占版面八成五")
    void seventyTwoWordsFillMostOfTheWidth() {
        assertSpread(zipfWords(72, 20_260_928L), HEIGHT_72, WIDTH_RATIO_DENSE);
    }

    /**
     * 量一组：外接框宽度占比、无重叠、不出界，三条都记完再判
     */
    private static void assertSpread(List<WordCloudLayout.Word> words, int height, double minRatio) {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);
        WordCloudLayout.Result result =
                WordCloudLayout.layout(words, CONTENT_WIDTH, height, 20_260_924L, renderer);

        List<String> failures = new ArrayList<>();
        Rectangle bounds = result.bounds();
        double ratio = bounds.width / (double) CONTENT_WIDTH;
        String reading = words.size() + " 词 " + CONTENT_WIDTH + "×" + height
                + " 外接框 " + bounds.width + "×" + bounds.height
                + " 宽占比 " + String.format("%.3f", ratio)
                + "（下限 " + String.format("%.2f", minRatio) + "）"
                + " 落 " + result.placements().size() + " 丢 " + result.dropped();

        if (ratio < minRatio) {
            failures.add(reading + " —— 外接框宽占比不足, 两侧空白");
        }

        List<WordCloudLayout.Placement> placed = result.placements();
        for (int i = 0; i < placed.size(); i++) {
            Rectangle a = placed.get(i).box();
            if (a.x < FRAME_MARGIN || a.y < FRAME_MARGIN
                    || a.getMaxX() > CONTENT_WIDTH - FRAME_MARGIN
                    || a.getMaxY() > height - FRAME_MARGIN) {
                failures.add("「" + placed.get(i).text() + "」越界或离框边不足 " + FRAME_MARGIN + "px " + a);
            }
            for (int j = i + 1; j < placed.size(); j++) {
                Rectangle b = placed.get(j).box();
                int gapX = gap(a.x, a.width, b.x, b.width);
                int gapY = gap(a.y, a.height, b.y, b.height);
                if (gapX < MIN_GAP_X && gapY < MIN_GAP_Y) {
                    failures.add("「" + placed.get(i).text() + "」与「" + placed.get(j).text()
                            + "」粘连 横隔 " + gapX + "px 纵隔 " + gapY + "px");
                }
            }
        }

        assertTrue(failures.isEmpty(), reading + "\n" + String.join("\n", failures));
    }

    /**
     * 造一组 Zipf 词频的语料：第 n 名词频约为头名的 1/n，种子定词形。
     * 词形一律两字——弹幕里常见的是短词，这一族专门看短词能不能把版面横向铺开
     */
    private static List<WordCloudLayout.Word> zipfWords(int size, long seed) {
        Random random = new Random(seed);
        String pool = "晚上好听唱歌打游戏厉害加油可爱笑死太强岁月史书下次一定主播前排签到早安冲鸭泪目破防上号整活求歌单可以再来";
        List<String> texts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (texts.size() < size) {
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                word.append(pool.charAt(random.nextInt(pool.length())));
            }
            if (seen.add(word.toString())) {
                texts.add(word.toString());
            }
        }
        List<WordCloudLayout.Word> words = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            words.add(new WordCloudLayout.Word(texts.get(i), Math.max(1, (int) Math.round(240.0 / (i + 1)))));
        }
        return words;
    }

    /**
     * 两个区间在一根轴上隔开多少；重叠时为负
     */
    private static int gap(int aStart, int aLength, int bStart, int bLength) {
        return Math.max(bStart - (aStart + aLength), aStart - (bStart + bLength));
    }
}
