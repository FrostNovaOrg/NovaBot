package org.frostnova.nova.core.config.ui.vocab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台词表的合并
 */
@DisplayName("控制台词表")
class ConsoleVocabulariesTest {
    private record Vocab(String id, Map<String, String> terms) implements ConsoleVocabulary {
    }

    @Test
    @DisplayName("两供方并集、同键按序用斜线连、闭集外与空白都丢掉")
    void mergeUnionJoinAndDrop() {
        List<String> reds = new ArrayList<>();

        try {
            Map<String, String> merged = ConsoleVocabularies.merge(List.of(
                    new Vocab("a", Map.of("bot.platform", "QQ")),
                    new Vocab("b", Map.of("bot.impl", "NapCat"))));
            assertEquals("QQ", merged.get("bot.platform"), "不同键应保留双方");
            assertEquals("NapCat", merged.get("bot.impl"), "不同键应保留双方");
            assertEquals(2, merged.size(), "并集应恰两项: " + merged);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            Vocab qq = new Vocab("a", Map.of("bot.platform", "QQ"));
            Vocab telegram = new Vocab("b", Map.of("bot.platform", "Telegram"));
            assertEquals("QQ／Telegram",
                    ConsoleVocabularies.merge(List.of(qq, telegram)).get("bot.platform"),
                    "同键应按入参顺序用／连接");
            assertEquals("Telegram／QQ",
                    ConsoleVocabularies.merge(List.of(telegram, qq)).get("bot.platform"),
                    "换序后连法应跟着换");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            Map<String, String> terms = new LinkedHashMap<>();
            terms.put("bot.unknown", "X");
            terms.put("bot.platform", "QQ");
            terms.put("bot.impl", "  ");
            terms.put("bot.family", "");
            Map<String, String> merged = ConsoleVocabularies.merge(List.of(new Vocab("a", terms)));
            assertEquals(Map.of("bot.platform", "QQ"), merged, "闭集外键与空白值都不应入表");
            assertFalse(merged.containsKey("bot.unknown"), "闭集外键应被丢掉");
            assertFalse(merged.containsKey("bot.impl"), "空白值视同未供");
            assertFalse(merged.containsKey("bot.family"), "空串视同未供");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
