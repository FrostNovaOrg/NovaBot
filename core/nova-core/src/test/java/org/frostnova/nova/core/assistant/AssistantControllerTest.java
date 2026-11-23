package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAgreement;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssistantControllerTest {
    @TempDir Path directory;
    private static final String SECRET = "test-secret-9876543210";
    private MockMvc mvc;
    private ConfigUiAuthService auth;
    private ConfigUiSession session;
    private MockRestServiceServer server;
    private AssistantKeyStore keys;
    private AssistantUsageStore usage;
    private AssistantClient client;
    private AssistantProperties properties;
    private AbstractDataSource dataSource;

    @BeforeEach void setup() {
        properties = new AssistantProperties();
        properties.setEnabled(true);
        keys = new AssistantKeyStore(directory);
        var template = new RestTemplate();
        server = MockRestServiceServer.bindTo(template).build();
        usage = new AssistantUsageStore(directory, Clock.systemUTC());
        client = new AssistantClient(properties, keys, usage, template);
        dataSource = mock(AbstractDataSource.class);
        auth = mock(ConfigUiAuthService.class);
        session = mock(ConfigUiSession.class);
        when(session.getCsrfToken()).thenReturn("test-csrf-token");
        when(session.getChannel()).thenReturn(ConfigUiSession.Channel.PASSWORD);
        when(auth.isEnabled()).thenReturn(true);
        when(auth.validate(any())).thenReturn(Optional.of(session));
        when(auth.account(session)).thenReturn(ConfigUiAccount.local(Instant.EPOCH));
        var agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy("password");
        var filter = new ConfigUiSecurityFilter("unused-token", new IpMatcher(List.of("127.0.0.1")), auth,
                new NovaCoreProperties.ConfigUi.Auth(), agreement);
        mvc = MockMvcBuilders.standaloneSetup(new AssistantController(client, dataSource, StreamerNames.none()))
                .addFilters(filter).build();
    }

    @Test void usageStatusShowsEachStreamerWithNicknameWhenAvailable() throws Exception {
        var named = new PushUser();
        named.setUname("测试主播甲");
        when(dataSource.getUser("bilibili", 1L)).thenReturn(Optional.of(named));
        usage.addCost(new AssistantUsageStore.Streamer("bilibili", 1L), new BigDecimal("0.6"));
        usage.addCost(new AssistantUsageStore.Streamer("bilibili", 9007199254740993L), new BigDecimal("0.5"));
        usage.addCost(null, new BigDecimal("0.1"));
        mvc.perform(get("/config/api/assistant/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyBudgetYuan").value(10))
                .andExpect(jsonPath("$.spentYuan").value(1.2))
                .andExpect(jsonPath("$.streamerUsage.length()").value(2))
                .andExpect(jsonPath("$.streamerUsage[0].nickname").value("测试主播甲"))
                .andExpect(jsonPath("$.streamerUsage[0].todaySpentYuan").value(0.6))
                .andExpect(jsonPath("$.streamerUsage[0].weekSpentYuan").value(0.6))
                .andExpect(jsonPath("$.streamerUsage[1].nickname").doesNotExist())
                .andExpect(jsonPath("$.streamerUsage[1].uid").value("9007199254740993"));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("X-CSRF-Token", "test-csrf-token").contentType(MediaType.APPLICATION_JSON);
    }

    private void successfulProbe() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.max_tokens").value(8))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
    }

    // 抓住保存、查询、试调、确认或删除接口返回明文，或新密钥未验证就保存。
    @Test void managementFlowKeepsSecretEncryptedAndRecordsActor() throws Exception {
        successfulProbe(); successfulProbe();
        String saved = mvc.perform(authorized(post("/config/api/assistant/key")).content("{\"key\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(saved.contains(SECRET));
        mvc.perform(authorized(post("/config/api/assistant/acknowledge"))).andExpect(status().isOk());
        String current = mvc.perform(get("/config/api/assistant/status")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.available").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.maskedKey").value("test-s…10"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.acknowledgement.actor").value("local-admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.acknowledgement.version").value(AssistantClient.ACKNOWLEDGEMENT_VERSION))
                .andReturn().getResponse().getContentAsString();
        assertFalse(current.contains(SECRET));
        assertFalse(Files.readString(directory.resolve("assistant-key.enc")).contains(SECRET));
        mvc.perform(authorized(post("/config/api/assistant/test"))).andExpect(status().isOk());
        mvc.perform(authorized(delete("/config/api/assistant/key"))).andExpect(status().isOk());
        mvc.perform(get("/config/api/assistant/status")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.available").value(false));
        assertFalse(keys.isSet());
        server.verify();
    }

    @Test void responsesProbeAcceptsOutputLimitForSavingKeyAndTestingConnection() throws Exception {
        properties.setBaseUrl("https://model.example/v1");
        new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.api-format", "responses")))
                .bind("novabot.assistant", Bindable.ofInstance(properties));
        client.acknowledge("local-admin");
        keys.save(SECRET);
        String candidate = "replacement-test-secret";
        var outputLimits = new ArrayList<Integer>();
        server.expect(ExpectedCount.manyTimes(), requestTo("https://model.example/v1/responses"))
                .andExpect(request -> outputLimits.add(JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString())
                        .getIntValue("max_output_tokens")))
                .andRespond(withSuccess("""
                        {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],
                         "usage":{"input_tokens":10,"output_tokens":256,"output_tokens_details":{"reasoning_tokens":256}}}
                        """, MediaType.APPLICATION_JSON));

        var saved = mvc.perform(authorized(post("/config/api/assistant/key")).content("{\"key\":\"" + candidate + "\"}"));
        var tested = mvc.perform(authorized(post("/config/api/assistant/test")));
        var headers = new HttpHeaders();
        new AssistantKeyStore(directory).authorize(headers);
        assertAll(
                () -> saved.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)),
                () -> tested.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)),
                () -> assertEquals("Bearer " + candidate, headers.getFirst(HttpHeaders.AUTHORIZATION)),
                () -> assertEquals(List.of(256, 256), outputLimits),
                () -> assertEquals(0, new BigDecimal("0.0044472").compareTo(client.status().spentYuan())));
        server.verify();
    }

    // 抓住错误密钥试调失败后仍落盘，或将服务端原文返回给管理员。
    @Test void failedKeyProbeReturnsSafeReasonWithoutSaving() throws Exception {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(SECRET));
        String response = mvc.perform(authorized(post("/config/api/assistant/key")).content("{\"key\":\"" + SECRET + "\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(response.contains("密钥"));
        assertFalse(response.contains(SECRET));
        assertFalse(keys.isSet());
        assertFalse(client.status().available());
        server.verify();
    }

    // 抓住请求格式错误或诊断日志把输入密钥带出来。
    @Test void malformedKeyInputDoesNotLeakIntoDiagnostics() throws Exception {
        try (var logs = new AssistantLogCapture()) {
            String response = mvc.perform(authorized(post("/config/api/assistant/key")).content("{\"key\":\"" + SECRET))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertFalse(response.contains(SECRET));
            logs.assertNoSecret(SECRET);
        }
        server.verify();
    }

    // 抓住任意写接口漏掉 CSRF 校验，或者成员能够操作模型设置。
    @Test void everyMutationNeedsCsrfAndEveryRouteNeedsAdmin() throws Exception {
        for (var mutation : List.of(post("/config/api/assistant/key"), delete("/config/api/assistant/key"),
                post("/config/api/assistant/test"), post("/config/api/assistant/acknowledge"))) {
            mvc.perform(mutation.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        var member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "member", null, null, null, List.of(), Instant.EPOCH, null, true);
        when(auth.account(session)).thenReturn(member);
        for (var route : List.of(get("/config/api/assistant/status"), post("/config/api/assistant/key"),
                delete("/config/api/assistant/key"), post("/config/api/assistant/test"), post("/config/api/assistant/acknowledge"))) {
            mvc.perform(authorized(route).content("{}")).andExpect(status().isForbidden());
        }
        server.verify();
    }
}
