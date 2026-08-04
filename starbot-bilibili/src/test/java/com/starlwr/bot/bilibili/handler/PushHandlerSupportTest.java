package com.starlwr.bot.bilibili.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 推送处理器公共逻辑测试
 * <p>
 * 覆盖占位符取值为空时的分句移除规则：这是默认下播模板得以内置 {time}
 * 的前提——时长取不到时消息必须自动退化为不含时长的版本，而不是留下悬空半句。
 */
@DisplayName("占位符分句移除")
class PushHandlerSupportTest {
    @Test
    @DisplayName("取值非空时应正常替换")
    void replacesWhenValuePresent() {
        assertEquals("主播 直播结束了，本场直播时长 2 分 8 秒",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了，本场直播时长 {time}", "{time}", "2 分 8 秒"));
    }

    @Test
    @DisplayName("默认模板在时长缺失时应退化为不含时长的版本")
    void dropsClauseInDefaultTemplate() {
        assertEquals("主播 直播结束了",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了，本场直播时长 {time}", "{time}", ""));
    }

    @Test
    @DisplayName("分句在中间时应连同前导分隔符移除并保持前后衔接")
    void dropsMiddleClause() {
        assertEquals("主播 直播结束了，欢迎下次再来",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了，本场直播时长 {time}，欢迎下次再来", "{time}", ""));
    }

    @Test
    @DisplayName("分句在句首时应改为移除后继分隔符")
    void dropsLeadingClause() {
        assertEquals("欢迎下次再来",
                PushHandlerSupport.replaceOrDropClause("本场直播时长 {time}，欢迎下次再来", "{time}", ""));
    }

    @Test
    @DisplayName("换行也应视为分句边界")
    void treatsNewlineAsDelimiter() {
        assertEquals("主播 直播结束了\nhttps://example.com",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了，本场直播时长 {time}\nhttps://example.com", "{time}", ""));
    }

    @Test
    @DisplayName("分句不应跨越分条边界，且移除后变空白的分条应整条去掉")
    void respectsNextBoundary() {
        assertEquals("主播 直播结束了",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了{next}本场直播时长 {time}", "{time}", ""));
    }

    @Test
    @DisplayName("整个模板只有该分句时应返回空串")
    void returnsEmptyWhenTemplateIsSingleClause() {
        assertEquals("",
                PushHandlerSupport.replaceOrDropClause("本场直播时长 {time}", "{time}", ""));
    }

    @Test
    @DisplayName("占位符出现多次时应逐一移除所在分句")
    void dropsEveryOccurrence() {
        assertEquals("A，C",
                PushHandlerSupport.replaceOrDropClause("A，时长 {time}，C，再报一次 {time}", "{time}", ""));
    }

    @Test
    @DisplayName("不含占位符的模板应原样返回")
    void keepsTemplateWithoutPlaceholder() {
        assertEquals("主播 直播结束了",
                PushHandlerSupport.replaceOrDropClause("主播 直播结束了", "{time}", ""));
    }
}
