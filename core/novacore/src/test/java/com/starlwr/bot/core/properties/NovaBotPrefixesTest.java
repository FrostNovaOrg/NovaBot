package com.starlwr.bot.core.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配置键上一档到现行：搬过位的走搬位表，其余只换产品前缀
 */
@DisplayName("配置键现行名")
class NovaBotPrefixesTest {
    @Test
    @DisplayName("告警 qq-num 旧位映到适配器现行键")
    void relocatedAlertQqNumMapsToAdapter() {
        assertEquals(NovaBotPrefixes.ADAPTER_ALERT + ".num",
                NovaBotPrefixes.toCurrent("starbot.core.alert.qq-num"),
                "上一档告警号码应映到适配器现行键, 不是同位换名前缀");
    }

    @Test
    @DisplayName("未搬位的 core 键仍同位换名")
    void samePositionCoreKeyStillRenamesInPlace() {
        assertEquals("novabot.core.foo",
                NovaBotPrefixes.toCurrent("starbot.core.foo"),
                "未进搬位表的键应只换产品前缀");
    }

    @Test
    @DisplayName("NapCat token 旧位映到适配器现行键")
    void relocatedNapCatTokenMapsToAdapter() {
        assertEquals(NovaBotPrefixes.ADAPTER_NAPCAT + ".token",
                NovaBotPrefixes.toCurrent("starbot.core.config-ui.napcat.token"),
                "上一档代登录 token 应映到适配器现行键, 不是同位换名前缀");
    }

    @Test
    @DisplayName("非搬位键原样")
    void unrelatedKeyStaysUnchanged() {
        assertEquals("other.setting",
                NovaBotPrefixes.toCurrent("other.setting"),
                "与搬位表和产品前缀都无关的键应原样返回");
    }
}
