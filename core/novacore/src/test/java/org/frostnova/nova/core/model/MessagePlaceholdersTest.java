package org.frostnova.nova.core.model;

import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagePlaceholdersTest {
    @Test
    @DisplayName("外来文字中的完整或残缺花括号不拆条，其余文字保持")
    void externalTextCannotSplitMessages() {
        String text = "甲{next}乙{at=all} } { 中文｛原有｝\n";
        String escaped = MessagePlaceholders.escapeExternalText(text);
        assertEquals("甲｛next｝乙｛at=all｝ ｝ ｛ 中文｛原有｝\n", escaped);
        assertEquals(1, Message.create("qq-onebot", PushTargetType.GROUP, 30003L, escaped).size());
        assertEquals(escaped, MessagePlaceholders.escapeExternalText(escaped));
        assertNull(MessagePlaceholders.escapeExternalText(null));
        assertEquals("", MessagePlaceholders.escapeExternalText(""));
    }
}
