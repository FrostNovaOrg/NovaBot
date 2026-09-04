package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号比较的纯函数
 * <p>
 * 「有没有新版」这条判断只许有一份实现，而它最容易错的地方不在整段比较，
 * 在两个角落：<b>数字段按文本比</b>（1.9 与 1.10 按文本比是 1.9 大，按数值比才轮得到 1.10）
 * 与<b>预发版当正式版</b>（发布了 1.2.0-beta.1 时，正在跑 1.2.0 的机器不该被提示「有新版」）。
 * 这两处错了界面照常工作，只是那枚药丸该出的时候不出、不该出的时候常驻——
 * 靠人点开页面看是分辨不出来的。
 */
@DisplayName("版本号比较")
class VersionOrderTest {

    @Test
    @DisplayName("数字段按数值比：1.10 晚于 1.9")
    void numericSegmentsCompareNumerically() {
        assertTrue(VersionOrder.compare("1.10", "1.9") > 0, "按文本比的话 1.9 会大，这正是要防的那一个");
        assertTrue(VersionOrder.compare("1.9", "1.10") < 0);
    }

    @Test
    @DisplayName("从最高位比起，先比完的更早：1.10 早于 1.10.1")
    void shorterNumberComesFirstWhenPrefixEqual() {
        assertTrue(VersionOrder.compare("1.10", "1.10.1") < 0);
        assertTrue(VersionOrder.compare("1.10.1", "1.10") > 0);
    }

    @Test
    @DisplayName("预发版早于同一号的正式版：1.2.0-beta.1 早于 1.2.0")
    void prereleasePrecedesRelease() {
        assertTrue(VersionOrder.compare("1.2.0-beta.1", "1.2.0") < 0,
                "把预发当正式的话，跑着 1.2.0 的机器会被告知「新版 1.2.0-beta.1」");
        assertTrue(VersionOrder.compare("1.2.0", "1.2.0-beta.1") > 0);
    }

    @Test
    @DisplayName("两个预发之间也分得出先后：beta.2 晚于 beta.1，beta.10 晚于 beta.9")
    void prereleasesOrderAmongThemselves() {
        assertTrue(VersionOrder.compare("1.2.0-beta.2", "1.2.0-beta.1") > 0);
        assertTrue(VersionOrder.compare("1.2.0-beta.10", "1.2.0-beta.9") > 0);
    }

    @Test
    @DisplayName("v 前缀剥掉再比：v1.2.0 与 1.2.0 相等")
    void leadingVIsStripped() {
        assertEquals(0, VersionOrder.compare("v1.2.0", "1.2.0"));
        assertTrue(VersionOrder.compare("v1.2.1", "1.2.0") > 0);
    }

    @Test
    @DisplayName("完全相同的版本号相等")
    void identicalVersionsAreEqual() {
        assertEquals(0, VersionOrder.compare("4.3.1", "4.3.1"));
    }
}
