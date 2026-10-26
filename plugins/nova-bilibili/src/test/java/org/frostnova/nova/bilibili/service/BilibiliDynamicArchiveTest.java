package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BilibiliDynamicArchiveTest {
    private static final long UID = 910001L;
    private static final String BODY = "今天[微笑] @示例作者 #散步# 说明 https://example.invalid/page";
    @TempDir Path directory;
    private BilibiliApiUtil api;
    private BilibiliAccountService account;
    private AbstractDataSource source;
    private NovaBilibiliProperties properties;
    private NovaCoreProperties coreProperties;
    private final List<Object> events = new ArrayList<>();
    private Runnable poll;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        source = mock(AbstractDataSource.class);
        when(source.getUsers(BilibiliPlatform.BILIBILI.id())).thenReturn(List.of(user(true)));
        properties = new NovaBilibiliProperties();
        properties.getDynamic().setAutoFollow(false);
        coreProperties = new NovaCoreProperties();
        coreProperties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        restart();
    }

    private void restart() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliDynamicService service = new BilibiliDynamicService(api, account, properties, events::add, scheduler,
                new BilibiliDynamicArchive(coreProperties));
        service.start(source);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), any(Duration.class));
        poll = task.getValue();
    }

    @ParameterizedTest(name = "{0}: 首轮也能查到全文和链接，且不留图片地址")
    @ValueSource(strings = {"WORD", "DRAW", "AV", "ARTICLE", "FORWARD", "RESERVE"})
    @DisplayName("防止常见动态事后查不到内容或把图片地址存入档案")
    void archivesCommonTypesWithoutMedia(String kind) throws Exception {
        Dynamic dynamic = sample(kind, "7001");
        long before = System.currentTimeMillis();
        feed(dynamic);
        assertTrue(Files.exists(file()), "首次抓到即使不推送，也必须留下动态");
        List<String> lines = Files.readAllLines(file());
        assertEquals(1, lines.size());
        String line = lines.get(0);
        JSONObject row = JSON.parseObject(line);
        assertEquals("bilibili", row.getString("platform"));
        assertEquals(UID, row.getLongValue("uid"));
        assertEquals("7001", row.getString("id"));
        assertEquals(dynamic.getType(), row.getString("type"));
        assertEquals(dynamic.getPublishTime().orElseThrow().toEpochMilli(), row.getLongValue("publishedAt"));
        assertTrue(row.getLongValue("capturedAt") >= before);
        assertTrue(row.getLongValue("capturedAt") <= System.currentTimeMillis());
        assertEquals(BODY, row.getString("text"));
        List<String> links = row.getList("links", String.class);
        assertTrue(links.contains(dynamic.getUrl()));
        assertTrue(links.contains("https://example.invalid/page"));
        if (List.of("AV", "ARTICLE", "DRAW", "RESERVE").contains(kind)) {
            assertEquals("示例标题", row.getJSONArray("objects").getJSONObject(0).getString("title"));
            assertTrue(links.contains("https://example.invalid/object"));
        }
        if (kind.equals("AV")) assertEquals("示例简介", row.getJSONArray("objects").getJSONObject(0).getString("description"));
        if (List.of("DRAW", "ARTICLE").contains(kind)) assertEquals(BODY, row.getJSONArray("objects").getJSONObject(0).getString("summary"));
        if (kind.equals("FORWARD")) {
            JSONObject origin = row.getJSONObject("origin");
            assertEquals(910002L, origin.getLongValue("uid"));
            assertEquals("7002", origin.getString("id"));
            assertEquals("DYNAMIC_TYPE_FORWARD", origin.getString("type"));
            assertEquals(BODY, origin.getString("text"));
            assertTrue(origin.getList("links", String.class).contains("https://t.bilibili.com/7002"));
            assertFalse(origin.containsKey("origin"), "被转发的原文只展开一层");
        }
        assertFalse(line.contains("media.invalid"), "封面、头像、正文图片和表情图片都不能落盘");
        assertFalse(line.contains("modules"));
        assertTrue(events.isEmpty(), "首轮存档仍须遵守不补推的规则");
    }

    @Test
    @DisplayName("防止同一动态重复抓取或重启后被存两遍、改写首次内容")
    void deduplicatesAcrossRestart() throws Exception {
        feed(sample("WORD", "7001"));
        String original = Files.readString(file());
        feed(sample("AV", "7001"));
        restart();
        feed(sample("ARTICLE", "7001"));
        assertEquals(original, Files.readString(file()));
    }

    @Test
    @DisplayName("防止将未启用动态推送、停用或未配置主播的内容存入档案")
    void archivesOnlyConfiguredDynamicUsers() throws Exception {
        when(source.getUsers("bilibili")).thenReturn(List.of(user(false)));
        feed(sample("WORD", "7001"));
        assertFalse(Files.exists(file()));
        PushUser disabled = user(true);
        disabled.setEnabled(false);
        when(source.getUsers("bilibili")).thenReturn(List.of(disabled));
        feed(sample("WORD", "7002"));
        assertFalse(Files.exists(file()));
        when(source.getUsers("bilibili")).thenReturn(List.of());
        feed(sample("WORD", "7003"));
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("防止过期、开播跳过和含屏蔽词的动态没有历史记录")
    void archivesBeforePushDecisions() throws Exception {
        feed();
        properties.getDynamic().setBlockWords(List.of("今天"));
        Dynamic old = sample("WORD", "7001");
        old.getModules().getJSONObject("module_author").put("pub_ts", 1000);
        Dynamic live = sample("WORD", "7002");
        live.setType("DYNAMIC_TYPE_LIVE_RCMD");
        feed(old, live, sample("WORD", "7003"));
        assertEquals(3, Files.readAllLines(file()).size());
        assertEquals(1, events.size(), "过期及开播动态仍按原有规则跳过推送");
    }

    @Test
    @DisplayName("防止接口缺字段或出现新类型时归档中断、动态无法继续推送")
    void toleratesMissingFieldsAndUnknownTypes() throws Exception {
        feed();
        Dynamic unknown = sample("WORD", "7001");
        unknown.setType("DYNAMIC_TYPE_FUTURE");
        unknown.getModules().getJSONObject("module_dynamic").put("major", "unexpected");
        unknown.getModules().getJSONObject("module_author").remove("pub_ts");
        Dynamic sparse = sample("WORD", "7002");
        sparse.setType(null);
        sparse.getModules().remove("module_dynamic");
        feed(unknown, sparse, new Dynamic());
        assertEquals(2, events.size(), "有作者与 id 的新类型和缺正文动态仍应正常推送");
        List<String> lines = Files.readAllLines(file());
        assertEquals(2, lines.size());
        JSONObject first = JSON.parseObject(lines.get(0));
        assertEquals("DYNAMIC_TYPE_FUTURE", first.getString("type"));
        assertEquals(BODY, first.getString("text"));
        assertNull(first.get("publishedAt"));
        JSONObject second = JSON.parseObject(lines.get(1));
        assertEquals("UNKNOWN", second.getString("type"));
        assertNull(second.get("text"));
    }

    @Test
    @DisplayName("防止磁盘写入失败阻断推送，或恢复后仍永远漏存该动态")
    void retriesArchiveAfterWriteFailureWithoutBlockingPush() throws Exception {
        feed();
        Path obstruction = directory.resolve("dynamics");
        Files.writeString(obstruction, "occupied");
        feed(sample("WORD", "7001"));
        assertEquals(1, events.size());
        Files.delete(obstruction);
        feed(sample("WORD", "7001"));
        assertEquals(1, Files.readAllLines(file()).size());
        assertEquals(1, events.size(), "补存成功不能再推一次");
    }

    @Test
    @DisplayName("防止坏行和断电半行使后续动态不可读或重启后重复写入")
    void skipsBrokenLinesAndSeparatesTruncatedTail() throws Exception {
        feed(sample("WORD", "7001"));
        Files.writeString(file(), "broken\n{\"id\":", java.nio.file.StandardOpenOption.APPEND);
        restart();
        feed(sample("WORD", "7001"), sample("WORD", "7002"));
        BilibiliDynamicArchive archive = new BilibiliDynamicArchive(coreProperties);
        var rows = archive.read(UID, 0, Long.MAX_VALUE);
        assertEquals(List.of("7001", "7002"), rows.stream().map(row -> row.id()).toList());
        assertEquals(4, Files.readAllLines(file()).size(), "两条好行、两条坏行，半行不会吞掉新记录");
    }

    @Test
    @DisplayName("防止按主播和发布时间查询时混入别人的动态或时间范围外的动态")
    void readsByAuthorAndTime() {
        Dynamic early = sample("WORD", "7001");
        early.getModules().getJSONObject("module_author").put("pub_ts", 1000);
        Dynamic late = sample("WORD", "7002");
        late.getModules().getJSONObject("module_author").put("pub_ts", 2000);
        feed(early, late);
        BilibiliDynamicArchive archive = new BilibiliDynamicArchive(coreProperties);
        assertEquals(List.of("7001"), archive.read(UID, 1_000_000, 2_000_000).stream().map(row -> row.id()).toList());
        assertTrue(archive.read(910002L, 0, Long.MAX_VALUE).isEmpty());
    }

    @Test
    @DisplayName("防止正文仅有富文本节点时漏掉表情、提及、话题或网页的实际地址")
    void readsRichTextAndOriginalWebLinkWithoutTextField() throws Exception {
        Dynamic dynamic = sample("WORD", "7001");
        JSONObject desc = dynamic.getModules().getJSONObject("module_dynamic").getJSONObject("desc");
        desc.remove("text");
        desc.getJSONArray("rich_text_nodes").getJSONObject(1).put("text", "微笑");
        JSONObject web = desc.getJSONArray("rich_text_nodes").getJSONObject(4);
        web.remove("jump_url");
        web.put("orig_text", "https://example.invalid/original");
        feed(dynamic);
        JSONObject row = JSON.parseObject(Files.readString(file()));
        assertEquals(BODY, row.getString("text"));
        assertTrue(row.getList("links", String.class).contains("https://example.invalid/original"));
    }

    @Test
    @DisplayName("防止富文本节点不全时把接口提供的完整正文截短")
    void preservesFullTextWhenNodesAreIncomplete() throws Exception {
        Dynamic dynamic = sample("WORD", "7001");
        JSONObject desc = dynamic.getModules().getJSONObject("module_dynamic").getJSONObject("desc");
        desc.getJSONArray("rich_text_nodes").subList(1, desc.getJSONArray("rich_text_nodes").size()).clear();
        feed(dynamic);
        JSONObject row = JSON.parseObject(Files.readString(file()));
        assertEquals(BODY, row.getString("text"));
        assertTrue(row.getList("links", String.class).contains("https://example.invalid/page"));
    }

    @Test
    @DisplayName("防止正文或网页节点指向媒体文件时把图片和视频地址写进档案")
    void excludesMediaLinksEvenInTextFields() throws Exception {
        Dynamic dynamic = sample("WORD", "7001");
        dynamic.getModules().getJSONObject("module_dynamic").put("desc", JSON.parseObject("""
                {"text":"正文 https://media.invalid/photo.png //media.invalid/cover.webp https://media.invalid/movie.mp4",
                 "rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_WEB","text":"正文 https://media.invalid/photo.png",
                   "jump_url":"https://media.invalid/cover.gif"}]}
                """));
        feed(dynamic);
        String line = Files.readString(file());
        assertFalse(line.contains("media.invalid"));
        assertEquals(List.of(dynamic.getUrl()), JSON.parseObject(line).getList("links", String.class));
    }

    @Test
    @DisplayName("防止直播附带对象的标题和直播间链接丢失或把封面一起保存")
    void readsLiveRecommendationContent() throws Exception {
        Dynamic dynamic = sample("WORD", "7001");
        dynamic.setType("DYNAMIC_TYPE_LIVE_RCMD");
        JSONObject liveInfo = JSONObject.of("title", "示例直播", "room_id", 920001,
                "cover", "https://media.invalid/live.jpg");
        dynamic.getModules().getJSONObject("module_dynamic").put("major", JSONObject.of("live_rcmd",
                JSONObject.of("content", JSON.toJSONString(JSONObject.of("live_play_info", liveInfo)))));
        feed(dynamic);
        JSONObject row = JSON.parseObject(Files.readString(file()));
        assertEquals("示例直播", row.getJSONArray("objects").getJSONObject(0).getString("title"));
        assertTrue(row.getList("links", String.class).contains("https://live.bilibili.com/920001"));
        assertFalse(Files.readString(file()).contains("media.invalid"));
    }

    @Test
    @DisplayName("防止视频和专栏缺少跳转字段时已有对象编号却没有可用链接")
    void buildsLinksFromObjectIdentifiers() throws Exception {
        Dynamic video = sample("AV", "7001");
        JSONObject archive = video.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("archive");
        archive.remove("jump_url");
        archive.put("bvid", "BV1Example000");
        Dynamic article = sample("ARTICLE", "7002");
        JSONObject object = article.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("article");
        object.remove("jump_url");
        object.put("id", "730001");
        feed(video, article);
        List<String> lines = Files.readAllLines(file());
        assertTrue(JSON.parseObject(lines.get(0)).getList("links", String.class).contains("https://www.bilibili.com/video/BV1Example000"));
        assertTrue(JSON.parseObject(lines.get(1)).getList("links", String.class).contains("https://www.bilibili.com/read/cv730001"));
    }

    private Path file() {
        return directory.resolve("dynamics/bilibili-" + UID + ".jsonl");
    }

    private void feed(Dynamic... dynamics) {
        when(api.getDynamicUpdateList()).thenReturn(List.of(dynamics));
        poll.run();
    }

    private static PushUser user(boolean dynamicEnabled) {
        PushUser user = new PushUser();
        user.setUid(UID);
        user.setPlatform("bilibili");
        if (dynamicEnabled) {
            PushMessage message = new PushMessage();
            message.setEventClass(BilibiliDynamicUpdateEvent.class);
            PushTarget target = new PushTarget();
            target.setMessages(List.of(message));
            user.setTargets(List.of(target));
        }
        return user;
    }

    private static Dynamic sample(String kind, String id) {
        Dynamic dynamic = new Dynamic();
        dynamic.setId(id);
        dynamic.setType("DYNAMIC_TYPE_" + (kind.equals("RESERVE") ? "WORD" : kind));
        JSONObject modules = JSON.parseObject("""
                {"module_author":{"mid":910001,"pub_ts":0,"face":"https://media.invalid/avatar.png"},
                 "module_dynamic":{"desc":{"text":"今天[微笑] @示例作者 #散步# 说明 https://example.invalid/page",
                   "rich_text_nodes":[
                     {"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"今天"},
                     {"type":"RICH_TEXT_NODE_TYPE_EMOJI","text":"[微笑]","emoji":{"icon_url":"https://media.invalid/emoji.png"}},
                     {"type":"RICH_TEXT_NODE_TYPE_AT","text":" @示例作者"},
                     {"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":" #散步# "},
                     {"type":"RICH_TEXT_NODE_TYPE_WEB","text":"说明","jump_url":"https://example.invalid/page"},
                     {"type":"RICH_TEXT_NODE_TYPE_TEXT","text":" https://example.invalid/page"}]}}}
                """);
        modules.getJSONObject("module_author").put("pub_ts", Instant.now().getEpochSecond());
        dynamic.setModules(modules);
        JSONObject content = modules.getJSONObject("module_dynamic");
        JSONObject object = JSON.parseObject("""
                {"title":"示例标题","desc":"示例简介","jump_url":"//example.invalid/object",
                 "cover":"https://media.invalid/cover.png","pics":[{"url":"https://media.invalid/body.png"}]}
                """);
        if (kind.equals("AV")) content.put("major", JSONObject.of("archive", object));
        if (kind.equals("ARTICLE")) {
            object.put("desc", BODY);
            content.put("major", JSONObject.of("article", object));
        }
        if (kind.equals("DRAW")) {
            object.put("summary", content.remove("desc"));
            content.put("major", JSONObject.of("opus", object));
        }
        if (kind.equals("RESERVE")) content.put("additional", JSONObject.of("reserve", object));
        if (kind.equals("FORWARD")) {
            Dynamic origin = sample("WORD", "7002");
            origin.setType("DYNAMIC_TYPE_FORWARD");
            origin.getModules().getJSONObject("module_author").put("mid", 910002L);
            origin.setOrigin(sample("WORD", "7003"));
            dynamic.setOrigin(origin);
        }
        return dynamic;
    }
}
