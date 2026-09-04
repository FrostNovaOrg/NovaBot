package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 配置内容校验测试
 * <p>
 * 这套校验存在的意义是防止使用者把自己锁在门外：配置写坏后主程序起不来，配置界面也随之挂掉。
 * 因此重点覆盖两类断言——该拦的必须拦住，以及<b>不该拦的绝不能拦</b>：
 * 校验一旦误伤合法内容，使用者就只能去改文件，界面反而成了阻碍。
 */
@DisplayName("配置内容校验")
class ConfigurationValidatorTest {
    private static final String HANDLER = "com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler";

    private ConfigurationValidator validator;

    @BeforeEach
    void setUp() {
        StarBotEventHandlerService handlers = mock(StarBotEventHandlerService.class);
        when(handlers.getRegisteredHandlerClasses()).thenReturn(Set.of(HANDLER));

        validator = new ConfigurationValidator(handlers);
    }

    @Test
    @DisplayName("合法的推送配置应通过")
    void acceptsValidDatasource() {
        String json = """
                [{"uid":19805387116684,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":12345,"messages":[{"handler":"%s"}]}
                ]}]
                """.formatted(HANDLER);

        assertTrue(validator.validateDatasource(json, Set.of("qq-onebot")).isEmpty());
    }

    @Test
    @DisplayName("未注册的处理器类名应被拒绝")
    void rejectsUnknownHandler() {
        String json = """
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":12345,"messages":[{"handler":"com.example.NotExist"}]}
                ]}]
                """;

        List<String> issues = validator.validateDatasource(json, Set.of("qq-onebot"));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("com.example.NotExist"), issues.get(0));
    }

    @Test
    @DisplayName("未配置的推送平台应被拒绝, 并列出可用的平台")
    void rejectsUnknownPlatform() {
        String json = """
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"typo-onebot","type":1,"num":12345,"messages":[{"handler":"%s"}]}
                ]}]
                """.formatted(HANDLER);

        List<String> issues = validator.validateDatasource(json, Set.of("qq-onebot"));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("typo-onebot"), issues.get(0));
        assertTrue(issues.get(0).contains("qq-onebot"), "应提示可用平台: " + issues.get(0));
    }

    @Test
    @DisplayName("非法的推送类型应被拒绝")
    void rejectsInvalidTargetType() {
        // 2 是最容易踩的错值：直觉上「1 群聊、2 私聊」，但实际取值来自 PushTargetType，
        // 2 会被解析为 UNKNOWN，运行期直接丢弃该消息
        for (int type : new int[]{2, 9, -5}) {
            String json = """
                    [{"uid":1,"platform":"bilibili","targets":[
                      {"platform":"qq-onebot","type":%d,"num":12345,"messages":[]}
                    ]}]
                    """.formatted(type);

            List<String> issues = validator.validateDatasource(json, Set.of("qq-onebot"));
            assertEquals(1, issues.size(), "type=" + type + " 应被拒绝");
            assertTrue(issues.get(0).contains("type"), issues.get(0));
        }
    }

    @Test
    @DisplayName("私聊与群聊的合法取值都不得误伤")
    void acceptsBothValidTargetTypes() {
        for (int type : new int[]{0, 1}) {
            String json = """
                    [{"uid":1,"platform":"bilibili","targets":[
                      {"platform":"qq-onebot","type":%d,"num":12345,"messages":[]}
                    ]}]
                    """.formatted(type);

            assertTrue(validator.validateDatasource(json, Set.of("qq-onebot")).isEmpty(),
                    "type=" + type + " 是合法取值, 不应被拦下");
        }
    }

    @Test
    @DisplayName("同一平台下重复的 uid 应被拒绝")
    void rejectsDuplicateUser() {
        String json = """
                [{"uid":1,"platform":"bilibili","targets":[]},
                 {"uid":1,"platform":"bilibili","targets":[]}]
                """;

        List<String> issues = validator.validateDatasource(json, Set.of("qq-onebot"));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("重复"), issues.get(0));
    }

    @Test
    @DisplayName("非 JSON 数组应被拒绝")
    void rejectsMalformedJson() {
        assertFalse(validator.validateDatasource("{ 不是数组 }", Set.of()).isEmpty());
    }

    @Test
    @DisplayName("处理器尚未注册完毕时不应误报")
    void skipsHandlerCheckBeforeRegistration() {
        StarBotEventHandlerService empty = mock(StarBotEventHandlerService.class);
        when(empty.getRegisteredHandlerClasses()).thenReturn(Set.of());

        String json = """
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":1,"messages":[{"handler":"com.example.Any"}]}
                ]}]
                """;

        assertTrue(new ConfigurationValidator(empty).validateDatasource(json, Set.of("qq-onebot")).isEmpty());
    }
}
