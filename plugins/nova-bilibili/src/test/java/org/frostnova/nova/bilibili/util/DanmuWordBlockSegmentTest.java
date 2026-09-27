package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.service.BilibiliLiveStatsAggregator;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词云屏蔽词在切词时不被切散。
 * <p>
 * 分词器不认识的多字屏蔽词会被切成几段，每段单看都不含屏蔽词，画图时按「含屏蔽词」
 * 过滤就挡不住这几段。切词时把屏蔽词整个留成一个词，画图那边才认得出它。
 */
@DisplayName("词云屏蔽词切词时整个留下")
class DanmuWordBlockSegmentTest {
    private static final String PHRASE = "原神启动";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(19_000_000_000_301L, "主播甲", 47_000_000_000_401L, "https://pic.example/face.jpg");

    @TempDir
    Path dir;

    /**
     * 抓的故障：主播屏蔽了「原神启动」，弹幕里的「原神启动」被切成「原神」「启动」，
     * 这两段照样进了词云。
     */
    @Test
    @DisplayName("多字屏蔽词在弹幕里出现时整个留成一个词，不切出碎片")
    void blockedPhraseIsNotSplit() {
        List<String> plain = DanmuWordUtil.extractWords(PHRASE + "了");
        assertFalse(plain.contains(PHRASE), "前提不成立：分词器本来就不切这个词，换一个词测: " + plain);

        List<String> words = DanmuWordUtil.extractWords("大家" + PHRASE + "了", List.of(PHRASE));

        assertAll(
                () -> assertTrue(words.contains(PHRASE), "屏蔽词没有整个留下: " + words),
                () -> assertFalse(words.stream().anyMatch(word -> !word.contains(PHRASE)
                                && PHRASE.contains(word)),
                        "屏蔽词被切出了碎片: " + words),
                () -> assertTrue(words.contains("大家"), "屏蔽词以外的词没切出来: " + words));
    }

    /**
     * 抓的故障：屏蔽表里写的是大写，弹幕里是小写（或反过来），切词时没认出来。
     */
    @Test
    @DisplayName("认屏蔽词时英文不分大小写")
    void blockedPhraseIgnoresLetterCase() {
        List<String> words = DanmuWordUtil.extractWords("主播YYDS永远的神", List.of("yyds永远"));

        assertTrue(words.stream().anyMatch(word -> word.equalsIgnoreCase("yyds永远")),
                "大小写不同就没认出屏蔽词: " + words);
    }

    /**
     * 抓的故障：直播中入表那一路没带屏蔽表，下播报告用的词频里仍是碎片。
     */
    @Test
    @DisplayName("直播中弹幕入词频表时，屏蔽词整个记下、不记碎片")
    void liveRecordingKeepsBlockedPhraseWhole() {
        NovaCoreProperties core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        DefaultLiveDataService data = new DefaultLiveDataService(core);
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setWordCloudBlockWords(new ArrayList<>(List.of(PHRASE)));
        BilibiliLiveStatsAggregator aggregator =
                new BilibiliLiveStatsAggregator(data, new LiveDetailArchive(core), properties);
        List<String> plain = DanmuWordUtil.extractWords(PHRASE + "了");

        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, viewer(), PHRASE + "了", PHRASE + "了"));

        Map<String, Integer> stored = data.getLiveWordFrequencies("bilibili", STREAMER.getUid());
        assertAll(
                () -> assertTrue(stored.containsKey(PHRASE), "屏蔽词没有整个记下: " + stored),
                () -> assertFalse(plain.stream().anyMatch(stored::containsKey),
                        "记下了屏蔽词的碎片 " + plain + ": " + stored));
    }

    /**
     * 抓的故障：主播把「手」这一个字填进屏蔽表以后，弹幕被按这个字切断、两边各自再切，
     * 冒出原句里本不成词的两字碎片「机原」上了词云。单字屏蔽词只在画图时过滤。
     */
    @Test
    @DisplayName("单字屏蔽词不把原文切断，不冒出原句里本不成词的两字碎片")
    void singleCharBlockWordDoesNotChopText() {
        String text = "苹果手机原神启动真好用";
        List<String> plain = DanmuWordUtil.extractWords(text);
        assertAll(
                () -> assertFalse(plain.contains("机原"), "前提不成立：原句本来就会切出「机原」，换一句测: " + plain),
                () -> assertFalse(plain.contains("原神启动"), "前提不成立：分词器本来就不切「原神启动」，换一个词测: " + plain));

        List<String> words = DanmuWordUtil.extractWords(text, List.of("手"));
        assertAll(
                () -> assertEquals(plain, words, "单字屏蔽词不该参与切词，带它切出来的词应与不带表时一模一样: " + words),
                () -> assertFalse(words.contains("机原"), "单字屏蔽词把原文切断，切出了碎片「机原」: " + words));

        List<String> whole = DanmuWordUtil.extractWords(text, List.of("原神启动"));
        assertTrue(whole.contains("原神启动"), "两个字以上的屏蔽词照旧整个留下: " + whole);
    }

    private static UserInfo viewer() {
        return new UserInfo(19_000_000_000_202L, "观众甲", null);
    }
}
