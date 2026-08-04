package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.exception.DataSourceException;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * JSON 数据源测试
 * <p>
 * 删除 MySQL 数据源后这是唯一的数据源实现，全部推送配置都经由此处进入内存。
 * 解析出错的表现通常不是报错而是「少了一个主播」或「少了一条推送」，
 * 因此对缺字段、缺省值与取值映射都要有用例。
 */
@DisplayName("JSON 数据源")
class JsonDataSourceTest {
    private JsonDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = newDataSource(new StarBotCoreProperties());
    }

    @Test
    @DisplayName("应解析出完整的推送用户、目标与消息")
    void shouldParseFullConfiguration() {
        List<PushUser> users = dataSource.parse("""
                [
                  {
                    "uid": 12345678,
                    "platform": "bilibili",
                    "targets": [
                      {
                        "platform": "qq-onebot",
                        "type": 1,
                        "num": 987654321,
                        "messages": [
                          { "handler": "com.example.LiveOnHandler" }
                        ]
                      }
                    ]
                  }
                ]
                """);

        assertEquals(1, users.size());
        PushUser user = users.get(0);
        assertEquals(12345678L, user.getUid());
        assertEquals("bilibili", user.getPlatform());

        PushTarget target = user.getTargets().get(0);
        assertEquals("qq-onebot", target.getPlatform());
        assertEquals(987654321L, target.getNum());
        assertEquals("com.example.LiveOnHandler", target.getMessages().get(0).getHandler());
    }

    @Test
    @DisplayName("未写 enabled 时三个层级都应默认启用")
    void shouldDefaultToEnabled() {
        PushUser user = parseSingle();

        assertTrue(user.getEnabled(), "推送用户默认启用");
        assertTrue(user.getTargets().get(0).getEnabled(), "推送目标默认启用");
        assertTrue(user.getTargets().get(0).getMessages().get(0).getEnabled(), "推送消息默认启用");
    }

    @Test
    @DisplayName("type 为 1 应解析为群聊, 0 应解析为私聊")
    void shouldMapTargetType() {
        assertEquals(PushTargetType.GROUP, parseWithType(1).getType());
        assertEquals(PushTargetType.FRIEND, parseWithType(0).getType());
    }

    @Test
    @DisplayName("type 取值非法时应解析为未知类型")
    void shouldMapUnknownTargetType() {
        // 手写 JSON 绕开了界面校验，此时非法取值会一路带到发送阶段才被丢弃，
        // 表现为「配置看着没问题却一条也发不出去」
        assertEquals(PushTargetType.UNKNOWN, parseWithType(2).getType());
    }

    @Test
    @DisplayName("缺少 uid 或 platform 应拒绝解析并指明缺哪个字段")
    void shouldRejectMissingUserFields() {
        DataSourceException missingUid = assertThrows(DataSourceException.class,
                () -> dataSource.parse("[{\"platform\": \"bilibili\"}]"));
        assertTrue(missingUid.getMessage().contains("uid"), "实际为: " + missingUid.getMessage());

        DataSourceException missingPlatform = assertThrows(DataSourceException.class,
                () -> dataSource.parse("[{\"uid\": 1}]"));
        assertTrue(missingPlatform.getMessage().contains("platform"), "实际为: " + missingPlatform.getMessage());
    }

    @Test
    @DisplayName("推送目标缺少必填字段应拒绝解析")
    void shouldRejectMissingTargetFields() {
        assertThrows(DataSourceException.class, () -> dataSource.parse("""
                [{"uid": 1, "platform": "bilibili", "targets": [{"platform": "qq-onebot", "type": 1}]}]
                """));
    }

    @Test
    @DisplayName("推送消息缺少 handler 应拒绝解析")
    void shouldRejectMissingHandler() {
        DataSourceException exception = assertThrows(DataSourceException.class, () -> dataSource.parse("""
                [{"uid": 1, "platform": "bilibili", "targets": [
                  {"platform": "qq-onebot", "type": 1, "num": 1, "messages": [{"params": {}}]}
                ]}]
                """));
        assertTrue(exception.getMessage().contains("handler"), "实际为: " + exception.getMessage());
    }

    @Test
    @DisplayName("params 应原样保留为字符串, 供后续与处理器默认参数合并")
    void shouldKeepParamsAsString() {
        PushUser user = dataSource.parse("""
                [{"uid": 1, "platform": "bilibili", "targets": [
                  {"platform": "qq-onebot", "type": 1, "num": 1, "messages": [
                    {"handler": "com.example.Handler", "params": {"at_all": true}}
                  ]}
                ]}]
                """).get(0);

        PushMessage message = user.getTargets().get(0).getMessages().get(0);
        assertNotNull(message.getParams());
        assertTrue(message.getParams().contains("at_all"), "实际为: " + message.getParams());
    }

    @Test
    @DisplayName("反向引用应被正确设置")
    void shouldWireBackReferences() {
        PushUser user = parseSingle();
        PushTarget target = user.getTargets().get(0);

        assertSame(user, target.getUser());
        assertSame(target, target.getMessages().get(0).getTarget());
    }

    @Test
    @DisplayName("空数组应解析为空列表而非报错")
    void shouldAcceptEmptyArray() {
        assertTrue(dataSource.parse("[]").isEmpty());
    }

    @Test
    @DisplayName("没有 targets 的推送用户也应能解析")
    void shouldAcceptUserWithoutTargets() {
        PushUser user = dataSource.parse("[{\"uid\": 1, \"platform\": \"bilibili\"}]").get(0);

        assertNotNull(user.getTargets(), "应为空列表而非 null, 否则后续遍历会空指针");
        assertTrue(user.getTargets().isEmpty());
    }

    @Test
    @DisplayName("文件不存在时应给出包含路径的提示")
    void shouldReportMissingFileWithPath(@TempDir Path directory) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(directory.resolve("not-exists.json").toString());
        properties.getDatasource().setJsonAutoReload(false);

        DataSourceException exception = assertThrows(DataSourceException.class, () -> newDataSource(properties).load());

        assertTrue(exception.getMessage().contains("not-exists.json"),
                "提示里要带上实际用的路径, 否则使用者不知道程序在找哪个文件: " + exception.getMessage());
    }

    @Test
    @DisplayName("文件内容不是合法 JSON 时应拒绝加载")
    void shouldRejectMalformedFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("datasource.json");
        Files.writeString(file, "{ 这不是 JSON");

        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(file.toString());
        properties.getDatasource().setJsonAutoReload(false);

        assertThrows(DataSourceException.class, () -> newDataSource(properties).load());
    }

    /**
     * 构造被测数据源
     * @param properties 配置
     * @return JSON 数据源
     */
    private JsonDataSource newDataSource(StarBotCoreProperties properties) {
        return new JsonDataSource(
                mock(ApplicationEventPublisher.class),
                new DataSourceServiceRegistry(List.of()),
                mock(StarBotEventHandlerService.class),
                properties
        );
    }

    /**
     * 解析一份最简配置并返回其中唯一的推送用户
     * @return 推送用户
     */
    private PushUser parseSingle() {
        return parseWithType(1).getUser();
    }

    /**
     * 解析一份指定推送目标类型的配置
     * @param type 推送目标类型取值
     * @return 推送目标
     */
    private PushTarget parseWithType(int type) {
        return dataSource.parse("""
                [{"uid": 1, "platform": "bilibili", "targets": [
                  {"platform": "qq-onebot", "type": %d, "num": 1, "messages": [
                    {"handler": "com.example.Handler"}
                  ]}
                ]}]
                """.formatted(type)).get(0).getTargets().get(0);
    }
}
