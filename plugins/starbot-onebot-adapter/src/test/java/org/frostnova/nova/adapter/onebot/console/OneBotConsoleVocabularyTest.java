package org.frostnova.nova.adapter.onebot.console;

import org.frostnova.nova.core.config.ui.vocab.ConsoleVocabularies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneBot 控制台词表
 */
@DisplayName("OneBot 控制台词表")
class OneBotConsoleVocabularyTest {
    @Test
    @DisplayName("terms 恰七键、皆非空白、皆在闭集内")
    void sixFilledKeysInsideClosedSet() {
        List<String> reds = new ArrayList<>();
        Map<String, String> terms = new OneBotConsoleVocabulary().terms();

        try {
            assertEquals(7, terms.size(), "应恰七键，实际 " + terms.size() + "：" + terms.keySet());
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            for (Map.Entry<String, String> entry : terms.entrySet()) {
                assertFalse(entry.getValue() == null || entry.getValue().isBlank(),
                        "键 " + entry.getKey() + " 的值为空白");
            }
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            List<String> outside = new ArrayList<>();
            for (String key : terms.keySet()) {
                if (!ConsoleVocabularies.KEYS.contains(key)) {
                    outside.add(key);
                }
            }
            assertTrue(outside.isEmpty(), "闭集外的键：" + outside);
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
