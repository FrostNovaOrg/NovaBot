package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.protocol.EventStreamTokenService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /api/status 里的新版那一块
 * <p>
 * 侧栏药丸、点开的小面板与首页软待办全部由这一个字段驱动，键名错了三处一起瞎。
 * 检查器本身的判定另有 {@link UpdateCheckServiceTest} 钉着；本组钉的是
 * <b>控制台把它放进了 status、放成了什么形状、以及首装机器上它压根不在</b>。
 * <p>
 * 「整块缺席」与「键在、值为空」在这里不是同一种约定：有没有新版由缺席表达，
 * 因此阴性用例断的是<b>键不在</b>——多一个空对象，界面就多一种两头都没定义的中间态。
 */
@DisplayName("状态接口的新版字段")
class UpdateStatusFieldsTest {
    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private ConfigurationFileService fileService;

    private AbstractDataSource dataSource;

    /** 假来源说最新版是 v5.1.0，当前跑着 5.0.0：足够构造出「该提示」的那一档 */
    private UpdateCheckService updateCheck;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        fileService = mock(ConfigurationFileService.class);
        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        JSONObject release = new JSONObject();
        release.put("tag_name", "v5.1.0");
        release.put("body", "修了开播误报\n第二行说明\n第三行说明");
        release.put("html_url", "https://example.invalid/release");
        updateCheck = new UpdateCheckService(properties, new StarBotStateStore(properties), buildOf("5.0.0"), url -> release);
        updateCheck.checkNow();
    }

    @Test
    @DisplayName("有新版且已配过：版本、说明、链接一并下发")
    void updateBlockCarriesVersionNotesAndUrl() {
        when(fileService.exists()).thenReturn(true);
        PushUser user = new PushUser();
        user.setUid(3493L);
        user.setUname("柚子");
        user.setPlatform("bilibili");
        user.setEnabled(true);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        JSONObject update = controller().status().getJSONObject("update");

        assertNotNull(update, "检查器已判定该提示，status 里就该有这一块");
        assertEquals("v5.1.0", update.getString("latestVersion"));
        assertEquals(List.of("修了开播误报", "第二行说明", "第三行说明"), update.getJSONArray("notes").toJavaList(String.class));
        assertEquals("https://example.invalid/release", update.getString("url"));
    }

    @Test
    @DisplayName("首装机器：哪怕有新版也不下发这一块")
    void firstInstallGetsNoUpdateBlock() {
        // 同意协议会写出配置文件，但还没有主播。按文件在不在判的话这里会下发药丸
        when(fileService.exists()).thenReturn(true);

        JSONObject status = controller().status();

        assertFalse(status.containsKey("update"), "还没配主播的机器不该见到这枚药丸，缺席即没有");
    }

    @Test
    @DisplayName("没有新版时：已配过的机器上同样缺席")
    void noUpdateMeansAbsentBlock() {
        JSONObject sameVersion = new JSONObject();
        sameVersion.put("tag_name", "v5.0.0");
        sameVersion.put("html_url", "https://example.invalid/release");
        updateCheck = new UpdateCheckService(properties, new StarBotStateStore(properties), buildOf("5.0.0"), url -> sameVersion);
        updateCheck.checkNow();
        when(fileService.exists()).thenReturn(true);
        PushUser user = new PushUser();
        user.setUid(3493L);
        user.setUname("柚子");
        user.setPlatform("bilibili");
        user.setEnabled(true);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        JSONObject status = controller().status();

        assertFalse(status.containsKey("update"), "没有新版＝没有这一块，不发空对象");
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller() {
        ObjectProvider healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> List.of().stream());

        StarBotSenderService senders = mock(StarBotSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.supportsTotalData()).thenReturn(true);
        when(liveDataService.getLiveStatus(any(), any())).thenReturn(Optional.of(false));

        TimelineStore timeline = mock(TimelineStore.class);
        when(timeline.countsOn(any())).thenReturn(Map.of());

        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                fileService,
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(PushActivityRecorder.class),
                mock(StarBotEventHandlerService.class),
                mock(DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                mock(RuntimeConfigurationApplier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(ObjectProvider.class),
                new PushGate(properties),
                liveDataService,
                timeline,
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new com.starlwr.bot.core.service.PushTemplateDefaults(properties),
                updateCheck);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> buildOf(String version) {
        Properties entries = new Properties();
        entries.put("version", version);
        BuildProperties build = new BuildProperties(entries);

        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(build);
        return provider;
    }
}
