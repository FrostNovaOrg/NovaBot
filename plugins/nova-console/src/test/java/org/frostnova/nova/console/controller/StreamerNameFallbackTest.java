package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推送页上现场查不回主播名时的退路
 * <p>
 * 平台断网或被风控时打开推送页，现场去平台查名全落空，一排主播都显示成一串 uid，认不出谁是谁；
 * 同一时刻运行状态页和主播页却显示得出名字。推送页查不回时改问这里：
 * 先取内存里加载着的昵称，再取最近一场归档里的，都没有才让页面照旧显示 uid。
 */
@DisplayName("推送页主播名退路：现场查不回时退回内存名、再退回最近一场的昵称")
class StreamerNameFallbackTest {
    private static final long UID = 5151L;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private AbstractDataSource dataSource;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        dataSource = mock(AbstractDataSource.class);
    }

    private StreamerNameController controller() {
        return new StreamerNameController(dataSource, new StreamerNames(new LiveSessionArchive(properties)));
    }

    private static PushUser user(String platform, long uid, String uname) {
        PushUser user = new PushUser();
        user.setPlatform(platform);
        user.setUid(uid);
        user.setUname(uname);
        return user;
    }

    /** 往场次归档里追加一场，只写取昵称要用的几项 */
    private void archive(String platform, long uid, String uname, long startTime) throws IOException {
        JSONObject line = new JSONObject();
        line.put("platform", platform);
        line.put("uid", uid);
        line.put("uname", uname);
        line.put("startTime", startTime);
        line.put("endTime", startTime + 3_600_000L);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    @DisplayName("内存名为空、归档里有昵称：给最近一场的昵称，推送页不再只显示 uid")
    void blankLoadedNameFallsBackToLatestSession() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("bilibili", UID, "")));
        archive("bilibili", UID, "早先的昵称", 1_700_000_000_000L);
        archive("bilibili", UID, "最近的昵称", 1_700_100_000_000L);
        archive("bilibili", UID + 1, "别人的昵称", 1_700_200_000_000L);

        JSONObject result = controller().streamerName("bilibili", UID);

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals("最近的昵称", result.getString("uname"));
    }

    @Test
    @DisplayName("这位不在内存里（例如已停用）、归档里有昵称：照样给归档昵称")
    void notLoadedFallsBackToLatestSession() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of());
        archive("bilibili", UID, "归档里的昵称", 1_700_100_000_000L);

        JSONObject result = controller().streamerName("bilibili", UID);

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals("归档里的昵称", result.getString("uname"));
    }

    @Test
    @DisplayName("内存里有昵称：给内存的，不被归档里的旧名盖掉")
    void loadedNameWinsOverArchive() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("bilibili", UID, "内存里的昵称")));
        archive("bilibili", UID, "归档里的旧昵称", 1_700_100_000_000L);

        JSONObject result = controller().streamerName("bilibili", UID);

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals("内存里的昵称", result.getString("uname"));
    }

    @Test
    @DisplayName("别的平台同号主播的内存名不算这一位的")
    void otherPlatformSameUidIsNotUsed() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("otherlive", UID, "别家的昵称")));
        archive("bilibili", UID, "归档里的昵称", 1_700_100_000_000L);

        JSONObject result = controller().streamerName("bilibili", UID);

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals("归档里的昵称", result.getString("uname"));
    }

    @Test
    @DisplayName("内存与归档都没有：回失败，页面照旧显示 uid")
    void noNameAnywhereFails() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("bilibili", UID, "")));
        archive("bilibili", UID + 1, "别人的昵称", 1_700_200_000_000L);

        JSONObject result = controller().streamerName("bilibili", UID);

        assertFalse(result.getBooleanValue("success"), result.toString());
        assertFalse(result.containsKey("uname"), result.toString());
    }
}
