package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 「消息图片 · 允许的内网网段」重启才生效；写错的那条启动时只记一条警告并跳过，
 * 管理员以为放行了、其实没生效，也看不到那条警告。所以保存时就要拒绝并指出是哪一条
 */
@DisplayName("消息图片 · 允许的内网网段：保存时校验")
class ImageAllowedNetworksSaveTest {
    private static final String KEY = "novabot.core.push.image-allowed-networks";

    @TempDir
    Path directory;

    private Path config;
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void start() throws Exception {
        config = directory.resolve("application.yml");
        Files.writeString(config, "novabot:\n  core:\n    push:\n      enabled: true\n");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data").resolve("live.json").toString());
        context = new AnnotationConfigApplicationContext();
        context.registerBean(NovaCoreProperties.class, () -> properties);
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(ExcludedViewersFileTest.controller(context, config)).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    private JSONObject save(String value, int status) throws Exception {
        var response = mvc.perform(post("/config/api/values")
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JSONObject(Map.of(KEY, value)).toJSONString())
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andReturn().getResponse();
        JSONObject body = JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(status, response.getStatus(), body::toJSONString);
        return body;
    }

    @Test
    @DisplayName("写错一条：整份不收，提示指出是哪一条、是哪一项，配置文件不动")
    void mistypedEntryIsRejectedOnSave() throws Exception {
        String before = Files.readString(config);
        for (String wrong : List.of("10.0.0.300", "192.168.1.0/40", "nas-host", "10.0.0.0/abc",
                "http://192.168.1.30")) {
            JSONObject rejected = save("192.168.1.30\n" + wrong, 400);
            String message = rejected.getString("message");
            assertFalse(rejected.getBooleanValue("success"), rejected::toJSONString);
            assertTrue(message.contains("「" + wrong + "」不是有效的 IP 或网段"), message);
            assertTrue(message.contains("消息图片 · 允许的内网网段"), message);
            assertTrue(message.contains("本批未保存"), message);
            assertFalse(message.contains("「192.168.1.30」"), "写对的那条不点名: " + message);
        }
        assertEquals(before, Files.readString(config), "配置文件不动");
    }

    @Test
    @DisplayName("写对的照收，说明要重启；留空也收（不放行任何内网网段）")
    void validListIsSavedAndWaitsForRestart() throws Exception {
        JSONObject saved = save("192.168.1.30\n\n10.0.0.0/24\nfd00::/8", 200);
        assertTrue(saved.getBooleanValue("success"), saved::toJSONString);
        assertEquals(List.of(KEY), saved.getList("restartRequired", String.class), saved::toJSONString);
        var source = new MapConfigurationPropertySource(new ConfigurationFileService(config).readAsLoaded());
        var bound = new Binder(source).bind("novabot.core", NovaCoreProperties.class).get();
        assertEquals(List.of("192.168.1.30", "10.0.0.0/24", "fd00::/8"), bound.getPush().getImageAllowedNetworks());

        JSONObject emptied = save("", 200);
        assertTrue(emptied.getBooleanValue("success"), emptied::toJSONString);
    }
}
