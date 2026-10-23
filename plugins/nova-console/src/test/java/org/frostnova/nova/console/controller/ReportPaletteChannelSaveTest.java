package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ReportPaletteChannelSaveTest {
    @TempDir Path dir;

    @DataSourceServiceConfig(name = "bilibili")
    private static class LocalService implements DataSourceService {
        public void completePushUser(PushUser user) { }
    }

    @Test
    void wholeUserSaveWritesPaletteAndClearsItOnDiskAndReload() throws Exception {
        var properties = new NovaCoreProperties();
        Path file = dir.resolve("datasource.json");
        properties.getDatasource().setJsonPath(file.toString());
        properties.getDatasource().setJsonAutoReload(false);
        properties.getConfigUi().setBackupKeep(0);
        Files.writeString(file, """
                [{"platform":"bilibili","uid":1,"targets":[{"platform":"qq","type":1,"num":11,"messages":[]}]}]
                """);
        var source = new JsonDataSource(mock(ApplicationEventPublisher.class),
                new DataSourceServiceRegistry(List.of(new LocalService())), message -> true, properties.getDatasource());
        try {
            source.load();
            var validator = new ConfigurationValidator(mock(NovaEventHandlerService.class));
            var constructor = Arrays.stream(ConfigUiController.class.getConstructors())
                    .min(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
            Object[] args = Arrays.stream(constructor.getParameterTypes()).map(type -> {
                if (type == NovaCoreProperties.class) return properties;
                if (type == AbstractDataSource.class) return source;
                if (type == ConfigurationValidator.class) return validator;
                if (type == PushTemplateDefaults.class) return new PushTemplateDefaults(properties);
                return mock(type);
            }).toArray();
            var mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(args)).build();
            String path = "/config/api/datasource/users/bilibili/1";
            for (String id : Arrays.asList("night", "warm", null)) {
                var loaded = JSONObject.parseObject(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
                JSONObject user = loaded.getJSONObject("user");
                user.getJSONArray("targets").getJSONObject(0).put("reportPalette", id);
                String body = JSONObject.toJSONString(JSONObject.of("user", user, "version", loaded.getString("version")),
                        com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
                var response = mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse();
                assertEquals(200, response.getStatus(), response.getContentAsString());
                assertTrue(JSONObject.parseObject(response.getContentAsString()).getBooleanValue("success"), response.getContentAsString());
                JSONObject disk = JSONArray.parseArray(Files.readString(file)).getJSONObject(0).getJSONArray("targets").getJSONObject(0);
                assertTrue(disk.containsKey("reportPalette"));
                assertEquals(id, disk.getString("reportPalette"));
                source.reload();
                assertEquals(id, source.getUser("bilibili", 1L).orElseThrow().getTargets().get(0).getReportPalette());
                var refreshed = JSONObject.parseObject(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
                assertEquals(id, refreshed.getJSONObject("user").getJSONArray("targets").getJSONObject(0).getString("reportPalette"));
            }
        } finally {
            source.shutdown();
        }
    }
}
