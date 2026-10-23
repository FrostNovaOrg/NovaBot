package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamStatus;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventStreamValuesSaveTest {
    private static final String KEY = "novabot.core.event-stream.allowed-origins";
    @TempDir
    Path dir;
    private Path config;
    private ConfigUiController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        config = dir.resolve("application.yml");
        Files.writeString(config, "server:\n  port: 7827\n");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
    }

    @Test
    void consoleUpdatesPausedConfiguration() throws Exception {
        Files.writeString(config, "novabot:\n  core:\n    event-stream:\n      enabled: true\n"
                + "      allowed-origins:\n        - https://panel.example/\n");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("settings",
                    new org.springframework.core.io.FileSystemResource(config));
            sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.registerBean(ConfigUiController.class, () -> controller);
            context.registerBean(EventStreamTokenService.class, () -> mock(EventStreamTokenService.class));
            context.register(org.frostnova.nova.core.protocol.NovaEventStreamConfiguration.class);
            assertDoesNotThrow(context::refresh);
            var mapping = context.getBean("novaEventStreamHandlerMapping",
                    org.springframework.web.servlet.handler.SimpleUrlHandlerMapping.class);
            assertTrue(mapping.getUrlMap().isEmpty());
            var console = context.getBean(ConfigUiController.class);
            assertTrue(console.values().getBooleanValue("success"));
            assertEquals("paused", console.listEventTokens().getJSONObject("stream").getString("state"));
            assertTrue(console.save(Map.of(KEY, "https://panel.example")).getBody().getBooleanValue("success"));
        }
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            new org.springframework.boot.env.YamlPropertySourceLoader().load("settings",
                    new org.springframework.core.io.FileSystemResource(config))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.registerBean(EventStreamTokenService.class, () -> mock(EventStreamTokenService.class));
            context.register(org.frostnova.nova.core.protocol.NovaEventStreamConfiguration.class);
            assertDoesNotThrow(context::refresh);
            assertFalse(context.getBean("novaEventStreamHandlerMapping",
                    org.springframework.web.servlet.handler.SimpleUrlHandlerMapping.class).getUrlMap().isEmpty());
        }
    }

    @Test
    void streamStateInTokenList() {
        assertEquals("off", controller.listEventTokens().getJSONObject("stream").getString("state"));

        EventStreamStatus status = mock(EventStreamStatus.class);
        when(status.state()).thenReturn(EventStreamStatus.State.RUNNING);
        controller.setEventStreamStatus(status);
        var stream = controller.listEventTokens().getJSONObject("stream");
        assertEquals("running", stream.getString("state"));
        assertFalse(stream.containsKey("unlistedOrigin"));

        when(status.unlistedOrigin()).thenReturn("https://other.example");
        assertEquals("https://other.example",
                controller.listEventTokens().getJSONObject("stream").getString("unlistedOrigin"));

        when(status.state()).thenReturn(EventStreamStatus.State.PAUSED);
        assertEquals("paused", controller.listEventTokens().getJSONObject("stream").getString("state"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://panel.example/", "*", "null", "https://panel.example/path",
            "https://panel.example:70000", "https://panel.example?view=1", "https://user@panel.example",
            "https://面板.example", "https://panel.example\nhttps://other.example/"})
    void declinedValues(String value) throws Exception {
        String before = Files.readString(config);
        var response = controller.save(Map.of(KEY, value, "server.port", "7828"));
        assertEquals(400, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(response.getBody().getString("message").contains("页面来源"));
        assertEquals(before, Files.readString(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://panel.example", "http://localhost:7830", "http://[::1]:7830",
            "https://panel.example\nhttp://localhost:7830", "  https://panel.example  \n\n", ""})
    void ordinaryValues(String value) throws Exception {
        var response = controller.save(Map.of(KEY, value));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getBooleanValue("success"));
        String saved = new ConfigurationFileService(config).read().get(KEY);
        assertEquals(value.strip(), saved == null ? "" : saved);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://panel.example:443|https://panel.example",
            "http://localhost:80|http://localhost",
            "http://[::1]:80|http://[::1]",
            "HTTPS://Panel.Example:443|HTTPS://Panel.Example",
            "https://panel.example:8443|https://panel.example:8443",
            "https://panel.example:80|https://panel.example:80",
            "http://panel.example:443|http://panel.example:443"})
    void defaultPortValues(String value, String expected) throws Exception {
        var response = controller.save(Map.of(KEY, value));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, new ConfigurationFileService(config).read().get(KEY));
    }

    @Test
    void defaultPortInList() throws Exception {
        assertEquals(200, controller.save(Map.of(KEY, "https://panel.example:443\nhttp://localhost:7830"))
                .getStatusCode().value());
        assertEquals("https://panel.example\nhttp://localhost:7830", new ConfigurationFileService(config).read().get(KEY));
    }
}
