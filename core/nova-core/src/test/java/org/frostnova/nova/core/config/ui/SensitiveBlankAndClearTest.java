package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 机密项留空与显式清除：照提示留空不再误删已存的授权码
 *
 * <h2>它治的是哪一种病</h2>
 * 告警卡「发件授权码」框里放着后端给的遮点，框下写着「留空＝保持原值」。
 * 可存盘那一侧只把原样送回的遮点算作「这一项没动」，空串照常往下走，
 * 落盘时就成了「这一项没有值」。于是使用者删光遮点、照着提示留空、按保存，
 * 已存的授权码当场被删掉——界面上那句提示成了骗人的话，
 * 而发现它的时候多半已经是下一次告警没发出去。
 *
 * <h2>因此改成两条</h2>
 * 留空（空串或只有空白）与原样送回遮点一样，算「没动」；真要清掉，按旁边那个
 * 「清除」钮显式说清，保存时把清除标记送上来，后端才删键。
 *
 * <h2>四问各有各的反面</h2>
 * 只钉「留空不删」的话，会把清掉这条路一并堵死；只钉「能清掉」的话，
 * 误删那条病还留着。非机密项的留空照旧，否则这次改动会顺手改掉别人的规矩。
 */
@DisplayName("机密项留空与显式清除")
class SensitiveBlankAndClearTest {
    /** 显式清除标记，与后端同一份（{@link SensitiveFields#CLEAR}）：两处各写一份迟早对不上 */
    private static final String CLEAR = SensitiveFields.CLEAR;
    /** 存过授权码、也存过一个非机密项的配置 */
    private static final String SAVED = "server:\n  port: 7827\nspring:\n  mail:\n    username: sender@example.invalid\n    password: mail-secret-value\nnovabot:\n  core:\n    alert:\n      webhook-url: https://api.day.app/AbCdEfPushKey123/\n";

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    private ConfigurationFileService fileService;

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
    }

    /**
     * 按给定内容起一份配置，并接上控制台
     * <p>
     * 元数据服务用真的，不给桩：「这一项是不是机密」的答案里有一半出自类型表，
     * 桩一份等于把判据要问的那件事自己答了。其余依赖与同目录的遮蔽用例一致，一律给桩。
     */
    @SuppressWarnings("unchecked")
    private void start(String yaml) throws IOException {
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        fileService = new ConfigurationFileService(config);
        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                fileService,
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
    @DisplayName("机密项留空保存后原值还在：照提示留空不再误删已存的授权码")
    void blankSecretKeepsTheSavedValue() throws IOException {
        start(SAVED);

        controller.save(Map.of("spring.mail.password", ""));

        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains("mail-secret-value"),
                "照提示留空等于没动, 已存的授权码必须还在盘上: " + after);
    }

    @Test
    @DisplayName("机密项只剩空白也当没动：空格与空串同义")
    void blankOnlyWhitespaceKeepsTheSavedValue() throws IOException {
        start(SAVED);

        controller.save(Map.of("spring.mail.password", "   "));

        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains("mail-secret-value"),
                "只有空白的框与空框一样是「留空」, 不该把授权码删掉: " + after);
    }

    @Test
    @DisplayName("点「清除」再保存后键被删，这一项读回来没有值")
    void explicitClearRemovesTheKey() throws IOException {
        start(SAVED);

        controller.save(Map.of("spring.mail.password", CLEAR));

        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertFalse(after.contains("mail-secret-value"),
                "显式清除之后授权码不该还在盘上: " + after);
        assertFalse(fileService.read().containsKey("spring.mail.password"),
                "读回来不该再有这一项, 「没有」与「空值」在界面上要分得开");
    }

    @Test
    @DisplayName("清一个本来就没值的机密项是无操作，不写一行空值出来")
    void clearingAnAbsentKeyChangesNothing() throws IOException {
        start(SAVED);
        String before = Files.readString(config, StandardCharsets.UTF_8);

        controller.save(Map.of("spring.data.redis.password", CLEAR));

        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                "盘上本没有这一项, 清除不该往文件里添一行空值");
    }

    @Test
    @DisplayName("非机密项留空照旧删掉，不被这次改动顺手改掉规矩")
    void ordinaryFieldBlankStillRemoves() throws IOException {
        start(SAVED);

        controller.save(Map.of("spring.mail.username", ""));

        assertFalse(fileService.read().containsKey("spring.mail.username"),
                "非机密项留空的规矩照旧: 清空就是不配这一项");
    }

    @Test
    @DisplayName("遮点原样送回照旧算没动，占位值不写进配置文件")
    void placeholderStillMeansUnchanged() throws IOException {
        start(SAVED);
        String before = Files.readString(config, StandardCharsets.UTF_8);

        controller.save(Map.of("spring.mail.password", SensitiveFields.MASK));

        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                "送回遮点等于没改, 盘上必须还是原来那一份");
    }

    @Test
    @DisplayName("换一个新值照常落盘，留空的那条新规矩不挡真改动")
    void newValueStillWrites() throws IOException {
        start(SAVED);

        controller.save(Map.of("spring.mail.password", "another-secret"));

        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains("another-secret"), "真改了就要写进去: " + after);
        assertFalse(after.contains("mail-secret-value"), "旧值要被换掉: " + after);
    }

    @Test
    @DisplayName("非机密项送清除标记整批拒绝并点名，那串一个字都不许写进配置")
    void clearMarkerOnOrdinaryFieldRejectsTheBatch() throws IOException {
        start(SAVED);
        String before = Files.readString(config, StandardCharsets.UTF_8);

        JSONObject result = controller.save(Map.of("spring.mail.username", CLEAR)).getBody();

        assertFalse(result.getBooleanValue("success"),
                "清除标记只认机密项, 非机密项送上来要整批拒收: " + result);
        assertTrue(String.valueOf(result.get("message")).contains("spring.mail.username"),
                "回包要写明是哪个键: " + result);
        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertEquals(before, after, "本批未保存, 配置一个字都不该动");
        assertFalse(after.contains(CLEAR), "清除标记不许写进配置: " + after);
    }

    @Test
    @DisplayName("清除也算一次改动：回包把清除计进改动项数")
    void clearCountsAsAChange() throws IOException {
        start(SAVED);

        JSONObject result = controller.save(Map.of("spring.mail.password", CLEAR)).getBody();

        assertTrue(result.getBooleanValue("success"), "清除是一次正常保存: " + result);
        assertEquals(1, result.getIntValue("changed"), "清除也要计进改动项数: " + result);
    }
}
