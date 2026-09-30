package org.frostnova.nova.report.painter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词云里过长的整句缩略成「十一个看得见的字＋省略号」，名额只数看得见的字
 *
 * <h2>抓的用户故障</h2>
 * 弹幕词云里带 ❤️ 的长句，每带一个 ❤️，截出来的字就少一个：
 * 心形后面的变体选择符 U+FE0F 不出墨、不占宽，截词时却占掉一个名额。
 * 再一处：心形恰好落在截断处时，省略号前只剩心形、跟在它后面的 U+FE0F 被切掉。
 * <p>
 * 变体选择符只指定前一个字用哪种字形（判法见 FontUtil.isVariantSelector）：
 * 截词不该数它，也不该把它和它前面那个字拆开。
 * <p>
 * 这里只量缩略出来的字。量宽、摆位是另一条路，不在本尺射程内：
 * 量宽器给个正数尺寸让词落进版面就够，量不到真字宽。
 */
@DisplayName("词云截长词数看得见的字")
class WordCloudTruncateTest {

    /** 心形 U+2764 后跟变体选择符 U+FE0F，写作「❤️」 */
    private static final String HEART = "❤️";

    /** 一串 13 个字，越过缩略线 */
    private static final String PLAIN_LONG = "甲乙丙丁戊己庚辛壬癸子丑寅";

    /** 同一串字，中间插三个 ❤️：看得见的字还是 13 个，码位多了 6 个 */
    private static final String HEARTY_LONG =
            "甲乙丙" + HEART + "丁戊己" + HEART + "庚辛壬" + HEART + "癸子丑寅";

    @Test
    @DisplayName("带 ❤️ 的长词与不带 ❤️ 的同长词，截出来看得见的字一样多")
    void heartsDoNotStealSlots() {
        String with = label(HEARTY_LONG);
        String without = label(PLAIN_LONG);

        System.out.println("截词 不带❤️「" + without + "」 带❤️「" + with + "」");

        assertTrue(without.endsWith("…"), "不带 ❤️ 那串没被截断，这把尺没在量截词: " + without);
        assertEquals(visibleCount(without), visibleCount(with),
                "带不带 ❤️ 该截出一样多看得见的字: 带 ❤️「" + with + "」截出 " + visibleCount(with)
                        + " 个, 不带「" + without + "」截出 " + visibleCount(without) + " 个");
    }

    @Test
    @DisplayName("心形正好卡在截断处，连着它的选择符一起留")
    void heartAtCutKeepsItsSelector() {
        // 前十个字加一个 ❤️：这个心形正好是第十一个看得见的字；后面还有两个字，整串会截断
        String text = "甲乙丙丁戊己庚辛壬癸" + HEART + "子丑";
        String got = label(text);

        System.out.println("截词 卡在截断处「" + got + "」");

        assertEquals("甲乙丙丁戊己庚辛壬癸" + HEART + "…", got,
                "截断处不许把选择符和它前面那个字拆开");
    }

    @Test
    @DisplayName("没超长的词照旧原样，带 ❤️ 的也不截")
    void notOverlongStaysAsIs() {
        // 十二个看得见的字加两个 ❤️：选择符不占名额，这一串不该被截
        String text = "甲乙丙" + HEART + "丁戊己" + HEART + "庚辛壬癸";
        String got = label(text);

        System.out.println("截词 未超长「" + got + "」");

        assertEquals(text, got, "看得见的字不满十三个就该原样展示");
    }

    /**
     * 取这一串缩略后的展示文字。走的是排版那条真路：摆位落下来的字就是画上图的字
     */
    private static String label(String text) {
        WordCloudLayout.Result result = WordCloudLayout.layout(
                List.of(new WordCloudLayout.Word(text, 5)), 830, 200, 42L,
                (t, size) -> new Dimension(60, 24));
        assertEquals(1, result.placements().size(), "词没落进版面，量不到截词结果: " + text);
        return result.placements().get(0).text();
    }

    /**
     * 看得见的字数：不算变体选择符，也不算末尾的省略号
     * <p>
     * 只把 U+FE0F 当选择符数：这三格的夹具里只有它。判法与被测认成两套，
     * 一处写错时对得上号的机会还在。码位写成数字而不是字面量：
     * 选择符本身不出墨，写进源码也看不出来
     */
    private static int visibleCount(String label) {
        String cut = label.endsWith("…") ? label.substring(0, label.length() - 1) : label;
        return (int) cut.codePoints().filter(codePoint -> codePoint != 0xFE0F).count();
    }
}
