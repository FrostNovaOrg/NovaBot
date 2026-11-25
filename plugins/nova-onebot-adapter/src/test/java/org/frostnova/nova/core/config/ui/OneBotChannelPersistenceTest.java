package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotRuntimeAppliers;
import org.frostnova.nova.adapter.onebot.controller.OneBotController;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionTester;
import org.frostnova.nova.adapter.onebot.service.OneBotConnectionManager;
import org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.adapter.onebot.service.OneBotHttpService;
import org.frostnova.nova.adapter.onebot.service.OneBotWebsocketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OneBotChannelPersistenceTest {
    @Test
    @DisplayName("从旧版配置停用后重新读取文件，保留连接且启动时不连接或体检")
    void restartKeepsChannelDisabled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, """
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                          websocket: true
                          one-bot-address: localhost
                          one-bot-http-token: test
                          one-bot-websocket-token: test
                """);
        var running = load(file);
        assertFalse(running.channelDisabled());
        var mvc = console(file, running);
        toggle(mvc, false);
        assertTrue(running.channelDisabled());
        var restarted = load(file);
        assertTrue(restarted.channelDisabled());
        var sender = restarted.getSenders().get(0);
        assertEquals("localhost", sender.getOneBotAddress());
        assertTrue(sender.isWebsocket());

        var scheduler = mock(TaskScheduler.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var state = new OneBotConnectionState();
        var api = mock(org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter.class);
        var http = new OneBotHttpService(scheduler, executor, restarted, api, null, state);
        http.register(sender);
        http.onApplicationReadyEvent();
        new OneBotWebsocketService(scheduler, executor, restarted, http, state, null).onApplicationReadyEvent();
        verifyNoInteractions(scheduler, executor, api);

        toggle(mvc, true);
        assertFalse(running.channelDisabled());
        assertFalse(load(file).channelDisabled());
        try (var logs = Files.list(dir.resolve("console-audit"))) {
            var rows = Files.readAllLines(logs.findFirst().orElseThrow());
            assertEquals(List.of("停用 NapCat 通道", "启用 NapCat 通道"),
                    rows.stream().map(row -> JSONObject.parseObject(row).getString("description")).toList());
        }
    }

    private MockMvc console(Path file, OneBotAdapterPluginProperties running) {
        var beans = new StaticListableBeanFactory();
        var connections = new OneBotConnectionManager(running, mock(OneBotController.class),
                mock(OneBotWebsocketService.class), mock(OneBotHttpService.class), new OneBotConnectionState(),
                beans.getBeanProvider(OneBotTargetDirectory.class));
        beans.addBean("connections", connections);
        beans.addBean("tester", new OneBotConnectionTester(null, running, connections));
        var appliers = new OneBotRuntimeAppliers(running, beans.getBeanProvider(OneBotConnectionManager.class));
        var core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(file.resolveSibling("live.json").toString());
        var controller = mock(ConfigUiController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "metadataService", new ConfigurationMetadataService());
        ReflectionTestUtils.setField(controller, "fileService", new ConfigurationFileService(file));
        ReflectionTestUtils.setField(controller, "connectionTesters", beans.getBeanProvider(BotConnectionTester.class));
        ReflectionTestUtils.setField(controller, "aliasContributors", beans.getBeanProvider(ConfigurationKeyAliasContributor.class));
        ReflectionTestUtils.setField(controller, "runtimeApplier", RuntimeConfigurationApplier.bench(core)
                .contributors(List.of(appliers)).build());
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ConfigUiOperationFilter(new ConfigUiOperationStore(core))).build();
    }

    private void toggle(MockMvc mvc, boolean enabled) throws Exception {
        mvc.perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType("application/json").content(JSONObject.toJSONString(
                                Map.of(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY, Boolean.toString(enabled)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.restartRequired").isEmpty());
    }

    private OneBotAdapterPluginProperties load(Path file) throws Exception {
        var env = new MockEnvironment();
        new YamlPropertySourceLoader().load("settings", new FileSystemResource(file))
                .forEach(env.getPropertySources()::addFirst);
        return Binder.get(env).bind("novabot.adapter.onebot", Bindable.of(OneBotAdapterPluginProperties.class)).get();
    }
}
