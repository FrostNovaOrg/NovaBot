package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在册主播名单测试
 * <p>
 * 这一份名单存在的理由就是「停用的主播在内存里根本不存在」。因此第一条要钉的是
 * <b>停用的那位仍在名单里</b>——他要是掉了，界面上「停用」看起来就成了「删除」。
 */
@DisplayName("在册主播名单")
class StreamerDirectoryTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private StreamerDirectory directory;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        directory = new StreamerDirectory(properties);
    }

    @Test
    @DisplayName("没有配置文件时是空名单，不是错误")
    void missingFileIsEmpty() {
        assertTrue(directory.entries().isEmpty());
    }

    @Test
    @DisplayName("停用的主播仍在名单里，只是标着停用")
    void disabledStreamerStaysInTheList() throws IOException {
        write("""
                [
                  {"uid": 1001, "platform": "bilibili", "enabled": false,
                   "targets": [{"platform":"qq-onebot","type":1,"num":30003,
                                "messages":[{"handler":"X","enabled":true}]}]}
                ]
                """);

        List<StreamerDirectory.Entry> entries = directory.entries();

        assertEquals(1, entries.size(), "停用不是删除, 他得留在界面上才恢复得了");
        assertFalse(entries.get(0).enabled());
        assertEquals(1001L, entries.get(0).uid());
        assertEquals("bilibili", entries.get(0).platform());
    }

    @Test
    @DisplayName("没写 enabled 按启用计，与数据源加载时的认法一致")
    void missingEnabledMeansEnabled() {
        List<StreamerDirectory.Entry> entries = StreamerDirectory.parse(
                "[{\"uid\":1,\"platform\":\"bilibili\",\"targets\":[]}]");

        assertTrue(entries.get(0).enabled(), "反过来按停用计的话, 一份没写这个字段的配置会整份静默失效");
    }

    @Test
    @DisplayName("通道全关或消息全关都算「只采集不推送」，不是只看通道数")
    void pushingNeedsAnEnabledMessageInAnEnabledTarget() {
        assertFalse(one("[{\"uid\":1,\"platform\":\"bilibili\"}]").pushing(), "没有 targets 字段");
        assertFalse(one("[{\"uid\":1,\"platform\":\"bilibili\",\"targets\":[]}]").pushing(), "一个通道都没配");
        assertFalse(one("""
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":3,"enabled":false,
                   "messages":[{"handler":"X"}]}]}]
                """).pushing(), "通道停用了");
        assertFalse(one("""
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":3,
                   "messages":[{"handler":"X","enabled":false}]}]}]
                """).pushing(), "通道开着但四种通知全关掉, 效果与没有通道完全相同");
        assertTrue(one("""
                [{"uid":1,"platform":"bilibili","targets":[
                  {"platform":"qq-onebot","type":1,"num":3,
                   "messages":[{"handler":"X","enabled":false},{"handler":"Y"}]}]}]
                """).pushing(), "有一条启用的消息就算在推");
    }

    @Test
    @DisplayName("读不懂的那一条跳过，其余照常出现")
    void oneBadEntryDoesNotKillTheRest() {
        List<StreamerDirectory.Entry> entries = StreamerDirectory.parse("""
                [
                  {"platform":"bilibili"},
                  {"uid":2,"platform":""},
                  null,
                  {"uid":3,"platform":"bilibili"}
                ]
                """);

        assertEquals(1, entries.size(), "一条手改坏了的记录不该让其余主播从界面上消失");
        assertEquals(3L, entries.get(0).uid());
    }

    @Test
    @DisplayName("整份读不懂时给空表，不抛异常")
    void unreadableContentIsEmpty() {
        assertTrue(StreamerDirectory.parse("不是 JSON").isEmpty());
        assertTrue(StreamerDirectory.parse("null").isEmpty());
        assertTrue(StreamerDirectory.parse("{}").isEmpty());
    }

    private StreamerDirectory.Entry one(String json) {
        return StreamerDirectory.parse(json).get(0);
    }

    private void write(String content) throws IOException {
        Files.writeString(Path.of(properties.getDatasource().getJsonPath()), content, StandardCharsets.UTF_8);
    }
}
