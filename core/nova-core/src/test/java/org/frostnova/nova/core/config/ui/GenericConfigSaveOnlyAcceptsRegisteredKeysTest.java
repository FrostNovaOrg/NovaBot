package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通用保存只收设置页认得的键，认证项按规范名拦，键名不许夹 YAML 结构字符
 * <p>
 * 通用写口此前既不核键在不在设置页元数据里，认证四项也只做精确比较，
 * 键名还按 {@code .} 切段后原样写进文件。于是同一枚已登录会话，
 * 换个大小写／驼峰／下划线就能改掉登录口令，键名里夹换行还能在配置文件里造出新的一段。
 */
@DisplayName("通用保存只收设置页认得的键：认证项按规范名拦截，键名拒结构字符")
class GenericConfigSaveOnlyAcceptsRegisteredKeysTest {
    private static final String OLD_PASSWORD = "correct horse battery staple";

    private static final String NEW_PASSWORD = "another horse another staple";

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String ENABLED_KEY = "novabot.core.config-ui.enabled";

    private static final String UNKNOWN_KEY = "novabot.core.not-registered-anywhere";

    /**
     * 键名里夹换行：被原样拼进文件后，换行之后那半截会变成另一把键
     */
    private static final String NEWLINE_KEY = "novabot.core.pwned\n  stolen: true";

    private static final String TEMPLATE = """
            novabot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
                    totp: true
                    totp-secret: %s
            """.formatted(OLD_PASSWORD, SECRET);

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(OLD_PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret(SECRET);

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = controller(properties, authService);
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(NovaCoreProperties properties, ConfigUiAuthService authService) {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        // 认证项本身是真实配置项、在元数据里登记过：白名单放它们过去，专靠认证项那一层拦
        when(metadata.getKnownTypes()).thenReturn(Map.of(
                ENABLED_KEY, "java.lang.Boolean",
                ConfigUiAuthService.PASSWORD_PROPERTY, "java.lang.String",
                ConfigUiAuthService.TOTP_PROPERTY, "java.lang.Boolean",
                ConfigUiAuthService.TOTP_SECRET_PROPERTY, "java.lang.String",
                ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY, "java.lang.Boolean"));

        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).build();

        return new ConfigUiController(
                metadata,
                fileService,
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                applier,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                authService,
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    /**
     * 通用保存的回包体，两种返回形态都能读
     * <p>
     * 本口现在回 JSON、改后回带状态码的实体；判据要先在原样码上跑出红，写法对两边都成立，
     * 免得「红」是因为编译不过而不是行为不对。
     */
    @SuppressWarnings("unchecked")
    private static JSONObject bodyOf(Object saveResult) {
        if (saveResult instanceof ResponseEntity<?> response) {
            return (JSONObject) response.getBody();
        }
        return (JSONObject) saveResult;
    }

    private static int statusOf(Object saveResult) {
        if (saveResult instanceof ResponseEntity<?> response) {
            return response.getStatusCode().value();
        }
        return 200;
    }

    private Object save(Map<String, String> body) {
        return controller.save(body);
    }

    private String fileText() throws IOException {
        return Files.readString(config, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("偷得会话的人用宽松写法（大小写、驼峰、下划线）经通用保存改密码：被拒，配置文件不变")
    void looseFormPasswordWriteIsRejectedAndLeavesTheFileUntouched() throws IOException {
        String before = fileText();

        // 同一口令项的几种异写：过得了精确比较，Spring 宽松绑定却都认得它
        String[] looseForms = {
                "novabot.core.configUI.auth.password",
                "novabot.core.config_ui.auth.password",
                "NOVABOT.CORE.CONFIG-UI.AUTH.PASSWORD",
                "novabot.core.config-ui.auth.passWord",
                "novabot.core.config-ui.auth.totpSecret",
        };

        for (String loose : looseForms) {
            Object raw = save(Map.of(loose, NEW_PASSWORD));
            JSONObject result = bodyOf(raw);
            assertAll(
                    () -> assertFalse(result.getBooleanValue("success"),
                            "宽松写法「" + loose + "」不该改掉登录口令, 实际 message=" + result.getString("message")),
                    () -> assertTrue(result.getString("message") != null
                                    && result.getString("message").contains("登录与安全"),
                            "宽松写法「" + loose + "」应被认成认证项、把人领去专用口, 实际 message="
                                    + result.getString("message")),
                    () -> assertEquals(before, fileText(),
                            "宽松写法「" + loose + "」写进了配置文件就等于门已经换了, 拒了就该逐字同"));
        }
    }

    @Test
    @DisplayName("键名夹换行，想在配置文件里造出新的一段：被拒，配置文件不变")
    void keyNameCarryingNewlineIsRejectedAndLeavesTheFileUntouched() throws IOException {
        String before = fileText();

        Object raw = save(Map.of(NEWLINE_KEY, "x"));
        JSONObject result = bodyOf(raw);

        assertAll(
                () -> assertEquals(400, statusOf(raw),
                        "整批拒绝该回 400, 实际 body=" + result.toJSONString()),
                () -> assertFalse(result.getBooleanValue("success"),
                        "success 必须为 false, 实际 message=" + result.getString("message")),
                () -> assertTrue(result.getString("message") != null
                                && result.getString("message").contains(NEWLINE_KEY),
                        "回包要写明是哪个键, 实际 message=" + result.getString("message")),
                () -> assertEquals(before, fileText(),
                        "键名里的换行一旦按行写出，配置文件就多出了一段, 拒了就该逐字同"));
    }

    @Test
    @DisplayName("设置页元数据里没有的键：被拒，配置文件不变")
    void keyNotRegisteredInSettingsMetadataIsRejectedAndLeavesTheFileUntouched() throws IOException {
        String before = fileText();

        Object raw = save(Map.of(UNKNOWN_KEY, "x"));
        JSONObject result = bodyOf(raw);

        assertAll(
                () -> assertEquals(400, statusOf(raw),
                        "整批拒绝该回 400, 实际 body=" + result.toJSONString()),
                () -> assertFalse(result.getBooleanValue("success"),
                        "success 必须为 false, 实际 message=" + result.getString("message")),
                () -> assertTrue(result.getString("message") != null
                                && result.getString("message").contains(UNKNOWN_KEY),
                        "回包要写明是哪个键, 实际 message=" + result.getString("message")),
                () -> assertEquals(before, fileText(),
                        "元数据里没有的键写进配置文件就是凭空多了一项, 拒了就该逐字同"));
    }

    @Test
    @DisplayName("正常的设置项照旧能存（阳性对照）")
    void registeredSettingsItemStillSaves() throws IOException {
        Object raw = save(Map.of(ENABLED_KEY, "false"));
        JSONObject result = bodyOf(raw);

        assertAll(
                () -> assertTrue(result.getBooleanValue("success"),
                        "登记过的设置项该存得下, 实际 message=" + result.getString("message")),
                () -> assertTrue(fileText().contains("enabled: false"),
                        "保存后配置文件该是新值: \n" + fileText()));
    }

    @Test
    @DisplayName("键名夹换行时配置文件服务整批拒写（第 3 层兜底，不走通用保存口）")
    void fileServiceRejectsKeySegmentsCarryingYamlStructure() throws IOException {
        String before = fileText();

        // 直接打文件服务：通用保存口就算全拆了，这一层也得把改写配置文件结构的键名挡在门外
        IOException error = assertThrows(IOException.class,
                () -> fileService.write(Map.of(NEWLINE_KEY, "x")),
                "键名段含换行时整批该拒写");

        assertAll(
                () -> assertTrue(error.getMessage() != null && error.getMessage().contains("键名"),
                        "报错要说清是键名的问题, 实际=" + error.getMessage()),
                () -> assertEquals(before, fileText(),
                        "拒写时配置文件一个字都不该动, 实际=\\n" + fileText()));
    }
}
