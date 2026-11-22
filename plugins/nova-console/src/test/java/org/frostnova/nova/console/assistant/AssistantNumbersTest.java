package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 回答里正确的降幅或小时数被当成编造的数字，删掉之后冒出莫名其妙的话。
class AssistantNumbersTest {
    @ParameterizedTest
    @CsvSource({
            "changeRatio, -0.234, 流水下降 23.4%,",
            "changeRatio, -0.234, 降幅 23.4%,",
            "changeRatio, -0.234, 流水增长 23.4%, 23.4%",
            "changeRatio, 0.234, 弹幕下降 23.4%, 23.4%",
            "changeRatio, -0.234, 变化幅度为 23.4%,",
            "changeRatio, -0.234, -23.4%,",
            "durationSeconds, 44460, 开播 12.4 小时,",
            "durationSeconds, 44460, 开播 741 分钟,",
            "durationSeconds, 44460, 开播 13.1 小时, 13.1",
            "messages, 44460, 12.4, 12.4"
    })
    void verifiesAnswerAgainstToolNumber(String field, BigDecimal value, String answer, String unverified) {
        var numbers = new AssistantNumbers();
        numbers.include(Map.of(field, value));
        assertEquals(unverified == null ? List.of() : List.of(unverified), numbers.unverified(answer));
    }

    @ParameterizedTest
    @CsvSource({"弹幕增幅为 0.0%", "弹幕下降 0%"})
    void verifiesZeroPercentageWithDirection(String answer) {
        var numbers = new AssistantNumbers();
        numbers.include(Map.of("changeRatio", new BigDecimal("0.0")));

        assertEquals(List.of(), numbers.unverified(answer));
    }

    @Test
    void rejectsMinuteConversionAsSessionCount() {
        var numbers = new AssistantNumbers();
        numbers.include(Map.of("durationSeconds", 44460, "sessionCount", 2));

        assertEquals(List.of("741"), numbers.unverified("共 741 场"));
    }

    @ParameterizedTest
    @CsvSource({
            "741 小时, 741",
            "12.4 分钟, 12.4",
            "开播 12.4 个小时,",
            "开播 12.4钟头,",
            "开播 12.4  个钟头,",
            "开播 741分钟,",
            "开播时长为 12.4, 12.4",
            "开播时长为 44460 秒,"
    })
    void checksDurationConversionAgainstFollowingUnit(String answer, String unverified) {
        var numbers = new AssistantNumbers();
        numbers.include(Map.of("durationSeconds", 44460));

        assertEquals(unverified == null ? List.of() : List.of(unverified), numbers.unverified(answer));
    }

    @ParameterizedTest
    @CsvSource({
            "0.234, 流水上涨 23.4％,",
            "-0.234, 流水跌了 23.4％,",
            "0.234, 变化幅度为 23.4%,",
            "-0.234, 流水增长 -23.4%,",
            "-0.234, 流水下降 +23.4%, +23.4%",
            "0.234, 流水下降 +23.4%,",
            "0.234, 流水上涨 -23.4%, -23.4%",
            "0.234, 弹幕下降，流水变化 23.4%,",
            "0.234, 弹幕下降。流水变化 23.4%,",
            "0.234, 弹幕下降；流水变化 23.4%,",
            "-0.234, '弹幕增加, 流水变化 23.4%',",
            "-0.234, 弹幕增加. 流水变化 23.4%,",
            "-0.234, 弹幕增加; 流水变化 23.4%,",
            "0.234, 弹幕下降后又上涨 23.4%,",
            "-0.234, 流水下降 23.4% 后又上涨,",
            "-0.234, 流水上涨 23.4% 后又下降, 23.4%"
    })
    void readsDirectionBeforeUnsignedPercentage(BigDecimal ratio, String answer, String unverified) {
        var numbers = new AssistantNumbers();
        numbers.include(Map.of("changeRatio", ratio));
        assertEquals(unverified == null ? List.of() : List.of(unverified), numbers.unverified(answer));
    }

    @Test
    void convertsNamedSecondsInNestedObjects() {
        var numbers = new AssistantNumbers();
        numbers.include(JSONObject.of("items", JSONArray.of(JSONObject.of("capture",
                JSONObject.of("effectiveSeconds", 44460, "SeCoNdS", 66600, "secondsSpent", 135900)))));

        assertEquals(List.of("37.8"), numbers.unverified("12.35 小时、18.5 小时、741 分钟、1110 分钟、37.8 小时"));
    }

    @Test
    void convertsSecondsInSqlRowsUsingColumnPositions() {
        var numbers = new AssistantNumbers();
        numbers.include(JSONObject.of("columns", JSONArray.of("messages", "effective_seconds", "seconds"),
                "rows", JSONArray.of(JSONArray.of(135900, null, 66600), JSONArray.of(135900, 44460, null))));

        assertEquals(List.of("37.8"), numbers.unverified("12.4 小时、18.5 小时、741 分钟、1110 分钟、37.8 小时"));
    }

    @Test
    void convertsSecondsInAggregateRowsAndTotals() {
        var numbers = new AssistantNumbers();
        numbers.include(JSONObject.of("columns", JSONArray.of("day", "sum:durationSeconds", "sum:messages"),
                "rows", JSONArray.of(JSONArray.of("11-21", 44460, 135900)),
                "totals", JSONArray.of("合计", 87660, 135900)));

        assertEquals(List.of("37.8"), numbers.unverified("12.4 小时、24.4 小时、741 分钟、1461 分钟、37.8 小时"));
    }

    @Test
    void skipsSmallIntegersDatesTimesAndAudienceAliases() {
        var numbers = new AssistantNumbers();
        assertEquals(List.of("11"), numbers.unverified("1 到 10，2026-11-22 18:24，观众-123abc，11 场"));
    }
}
