package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.core.model.DanmuRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按弹幕原文重算词频：只计文字弹幕，词汇量上限与直播中入表相同。
 */
@DisplayName("按弹幕原文重算词频")
class DanmuWordCloudFrequenciesTest {
    private static final String PLATFORM = "bilibili";

    private static final long STREAMER = 19_000_000_000_301L;

    @Test
    @DisplayName("名单里的人整条不计，表情和醒目留言也不进词云")
    void skipsListedUsersAndNonText() {
        List<DanmuRecord> records = List.of(
                row(19_000_000_000_201L, "欢迎", DanmuRecord.Type.DANMU),
                row(19_000_000_000_202L, "欢迎", DanmuRecord.Type.EMOJI),
                row(19_000_000_000_202L, "感谢", DanmuRecord.Type.SUPER_CHAT),
                row(19_000_000_000_202L, "唱歌", DanmuRecord.Type.DANMU),
                row(19_000_000_000_202L, "唱歌", DanmuRecord.Type.DANMU));

        Map<String, Integer> words = DanmuWordCloudFrequencies.recount(
                PLATFORM, STREAMER, records, Set.of(19_000_000_000_201L));

        assertFalse(words.containsKey("欢迎"), "名单里的人或表情弹幕进了词云: " + words);
        assertFalse(words.containsKey("感谢"), "醒目留言进了词云: " + words);
        assertEquals(2, words.get("唱歌"), "文字弹幕没有按原话计入: " + words);
    }

    @Test
    @DisplayName("词汇量满了之后新词不收，先到的词继续累加")
    void vocabularyLimitKeepsEarlierWords() {
        List<DanmuRecord> records = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            records.add(row(1L, token(i), DanmuRecord.Type.DANMU));
        }
        records.add(row(1L, token(0), DanmuRecord.Type.DANMU));
        records.add(row(1L, token(5_000), DanmuRecord.Type.DANMU));

        Map<String, Integer> words = DanmuWordCloudFrequencies.recount(PLATFORM, STREAMER, records, Set.of());

        assertEquals(5_000, words.size(), "词汇量上限不是 5000: " + words.size());
        assertEquals(2, words.get(token(0)), "先到的词满了之后不再累加");
        assertEquals(1, words.get(token(4_999)));
        assertFalse(words.containsKey(token(5_000)), "满了之后的新词仍被收进词云");
    }

    @Test
    @DisplayName("不是纯数字的行不进名单")
    void nonNumericLinesAreIgnored() {
        assertEquals(Set.of(19_000_000_000_201L),
                DanmuWordCloudFrequencies.parseUids(List.of("19000000000201", "迎客机", "", "  ")));
    }

    private static DanmuRecord row(long uid, String text, DanmuRecord.Type type) {
        return new DanmuRecord(1L, uid, "观众", text, type);
    }

    private static String token(int n) {
        return "w" + Integer.toString(n, 36);
    }
}
