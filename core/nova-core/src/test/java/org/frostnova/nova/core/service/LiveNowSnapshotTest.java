package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.listener.LoadDataSourceListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveNowSnapshotTest {
    private static final long UID = 10001L;
    private static final String PLATFORM = "bilibili";
    private static final String STREAMER = "{\"uid\":10001,\"platform\":\"bilibili\"}";

    @TempDir Path dir;
    private Path rosterPath;
    private Path liveDataPath;
    private AnnotationConfigApplicationContext context;
    private JsonDataSource dataSource;
    private DefaultLiveDataService liveData;

    @BeforeEach
    void start() throws Exception {
        rosterPath = dir.resolve("datasource.json");
        liveDataPath = dir.resolve("data.json");
        Files.writeString(rosterPath, "[" + STREAMER + ", {\"uid\":10002,\"platform\":\"bilibili\"}]");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(rosterPath.toString());
        properties.getDatasource().setJsonAutoReload(false);
        properties.getLive().setLiveDataPath(liveDataPath.toString());
        properties.getLive().setAutoSaveLiveDataInterval(3600);

        DataSourceServiceRegistry registry = mock(DataSourceServiceRegistry.class);
        when(registry.getDataSourceService(PLATFORM)).thenReturn(Optional.of(mock(DataSourceService.class)));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(NovaCoreProperties.class, () -> properties);
        dataSource = new JsonDataSource(context, registry, null, properties.getDatasource());
        context.registerBean(AbstractDataSource.class, () -> dataSource);
        context.registerBean(DefaultLiveDataService.class);
        context.registerBean(LoadDataSourceListener.class);
        context.refresh();
        liveData = context.getBean(DefaultLiveDataService.class);
    }

    private void loadRoster() {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        liveData.setLiveStatus(PLATFORM, UID, true);
    }

    @AfterEach
    void stop() {
        if (context != null) context.close();
        if (dataSource != null) dataSource.shutdown();
    }

    @Test
    @DisplayName("探针漏掉控制台显示为在播的启用主播")
    void includesEnabledLiveStreamer() throws Exception {
        loadRoster();
        liveData.setLiveStatus(PLATFORM, UID + 1, false);
        assertEquals(List.of(UID), snapshot().getList(PLATFORM, Long.class));
    }

    @Test
    @DisplayName("主播在播时被移出监听清单，残留状态仍让探针等窗")
    void excludesRemovedLiveStreamer() throws Exception {
        loadRoster();
        Files.writeString(rosterPath, "[]");
        dataSource.reload();

        assertTrue(snapshot().isEmpty());
    }

    @Test
    @DisplayName("主播在播时被停用，残留状态仍让探针等窗")
    void excludesDisabledLiveStreamer() throws Exception {
        loadRoster();
        Files.writeString(rosterPath, "[{\"uid\":10001,\"platform\":\"bilibili\",\"enabled\":false}]");
        dataSource.reload();

        assertTrue(snapshot().isEmpty());
    }

    @Test
    @DisplayName("清单重载失败时，探针漏掉仍由程序监听的在播主播")
    void keepsLiveStreamerAfterFailedReload() throws Exception {
        loadRoster();
        Files.writeString(rosterPath, "[{\"uid\":10001,\"platform\":\"bilibili\",\"enabled\":false,\"targets\":null}]");
        dataSource.reload();

        assertTrue(dataSource.getUser(PLATFORM, UID).orElseThrow().getEnabled());
        assertEquals(List.of(UID), snapshot().getList(PLATFORM, Long.class));
    }

    @Test
    @DisplayName("启动写盘时监听清单还没加载，探针误报全员空闲")
    void doesNotPublishAnIdleRosterBeforeLoad() throws Exception {
        Files.writeString(liveDataPath, """
                {"LiveStatus:bilibili":{"10001":true},"LiveNow":{}}
                """);
        liveData.onApplicationReadyEvent();

        JSONObject data = JSONObject.parseObject(Files.readString(liveDataPath));
        assertTrue(data.containsKey("LiveNow"));
        assertNull(data.get("LiveNow"), "启动写盘应标记名单尚未就绪");
    }

    private JSONObject snapshot() throws Exception {
        liveData.saveNow(false);
        JSONObject data = JSONObject.parseObject(Files.readString(liveDataPath));
        assertTrue(data.getJSONObject("LiveStatus:" + PLATFORM).getBooleanValue(Long.toString(UID)));
        return assertInstanceOf(JSONObject.class, data.get("LiveNow"), "写盘缺少在播名单");
    }
}
