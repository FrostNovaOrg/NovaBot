package org.frostnova.nova.report.painter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词云块的<b>高度分档</b>：一场直播有多少词，这一块就占多高
 *
 * <h2>为什么高度要分档</h2>
 * 固定 380px 是按「塞满 72 个词」定的。十来个词的冷清场次照这个高度排，
 * 词摊开在一整框里；把词挤到中间，四周就是二百多像素的空白。分档之后
 * 词少的场次拿到一块矮的画布，词与词之间该挨多近由排版器自己决定。
 * <p>
 * 🔴 <b>六段的边界值写死在判据侧，不从被测那边取。</b>引被测的分档函数去比对，
 * 那就成了「它等于它自己」——分档改成一律 380px 时判据照样绿。
 * 每一段两头各量一次：只量中间值的话，把 {@code <=8} 写成 {@code <8} 这类差一错
 * 一次都碰不到
 */
@DisplayName("词云块高度分档")
class WordCloudHeightTest {
    /**
     * 词云块的最大高度，与 {@code BilibiliLiveReportPainter.CLOUD_HEIGHT} 对齐
     */
    private static final int MAX_HEIGHT = 380;

    @Test
    @DisplayName("0 个词→96px")
    void emptyBand() {
        assertBand(96, 0, 0);
    }

    @Test
    @DisplayName("1～3 个词→128px")
    void tinyBand() {
        assertBand(128, 1, 3);
    }

    @Test
    @DisplayName("4～8 个词→200px")
    void smallBand() {
        assertBand(200, 4, 8);
    }

    @Test
    @DisplayName("9～12 个词→240px")
    void mediumBand() {
        assertBand(240, 9, 12);
    }

    @Test
    @DisplayName("13～24 个词→300px")
    void largeBand() {
        assertBand(300, 13, 24);
    }

    /**
     * 25 个词往上一律取满，但不许超过调用层给的上限——上限是<b>参数</b>，
     * 换一处版式给一个更矮的框时，分档得跟着让步
     */
    @Test
    @DisplayName("25 个词起→380px，且不超过调用层给的上限")
    void fullBand() {
        List<String> failures = new ArrayList<>();
        for (int count : new int[]{25, 26, 72, 500}) {
            int height = WordCloudLayout.recommendedHeight(count, MAX_HEIGHT);
            if (height != MAX_HEIGHT) {
                failures.add(count + " 个词算出 " + height + "px, 应为 " + MAX_HEIGHT);
            }
            int capped = WordCloudLayout.recommendedHeight(count, 260);
            if (capped != 260) {
                failures.add(count + " 个词在上限 260 下算出 " + capped + "px, 应被上限挡到 260");
            }
        }
        // 上限比本档矮时，词少的那几档也得让步
        int narrow = WordCloudLayout.recommendedHeight(2, 100);
        if (narrow != 100) {
            failures.add("2 个词在上限 100 下算出 " + narrow + "px, 应被上限挡到 100");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * 量一段：段内两头与中间各算一次，都该是同一个高度
     */
    private static void assertBand(int expected, int from, int to) {
        List<String> failures = new ArrayList<>();
        for (int count : new int[]{from, (from + to) / 2, to}) {
            int height = WordCloudLayout.recommendedHeight(count, MAX_HEIGHT);
            if (height != expected) {
                failures.add(count + " 个词算出 " + height + "px, 应为 " + expected);
            }
        }
        assertTrue(failures.isEmpty(), from + "～" + to + " 这一段:\n" + String.join("\n", failures));
    }
}
