package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行栏「磁盘剩余」那一行下发的两个字段
 * <p>
 * 数据与日志都写在数据目录那块盘上，写不进去的那一刻推送记录与报告才开始丢——
 * 而使用者在本界面上对此一无所知。磁盘量得到时要给数值与档位；量不到时两个键
 * 都不发（缺席即没有），界面那一格写「—」，不是一个编出来的数。
 * <p>
 * 档位在服务端判（不足 2GB 黄、不足 500MB 红）：阈值只有一份，界面只照着画。
 * 量法走真路：捏造的读数经 {@code usableSpaceBytes} 那道缝喂进去，断言打在
 * {@code status()} 的回包上——只量一个脱离回包的纯函数时，键名少一个字母这一层看不见。
 */
@DisplayName("运行栏磁盘剩余")
class RuntimeDiskSpaceTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private AbstractDataSource dataSource;

    private LiveDataService liveDataService;

    private TimelineStore timeline;

    private ConfigUiAuthService authService;

    private ConfigurationFileService fileService;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        fileService = mock(ConfigurationFileService.class);

        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        liveDataService = mock(LiveDataService.class);
        when(liveDataService.supportsTotalData()).thenReturn(true);

        timeline = mock(TimelineStore.class);
        when(timeline.countsOn(any())).thenReturn(Map.of());

        authService = mock(ConfigUiAuthService.class);
        when(authService.isEnabled()).thenReturn(true);
    }

    /**
     * 量真盘的那一位与读数用捏造值的那一位
     * <p>
     * 真盘还剩多少不归测试管，两档阈值要喂捏造的读数才量得全。
     * 子类里那个方法<b>不写 {@code @Override}</b>：这一组用例要在还没有它的旧代码上
     * 先跑红——加了注解，红的就是「缺符号」的编译错，而不是「回包里缺字段」。
     * @param usableBytes 捏造的磁盘可用字节数；null 表示量真盘，负数表示量不到
     */
    @SuppressWarnings("unchecked")
    private ConfigUiController controller(Long usableBytes) {
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> List.<HealthProbe>of().stream());

        NovaSenderService senders = mock(NovaSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        if (usableBytes == null) {
            return new ConfigUiController(
                    mock(ConfigurationMetadataService.class),
                    fileService,
                    properties,
                    dataSource,
                    healthProbes,
                    mock(ConfigurationValidator.class),
                    senders,
                    mock(NovaMessageSender.class),
                    mock(ObjectProvider.class),
                    mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                    mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                    mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                    mock(ConfigurationLevelResolver.class),
                    new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
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
                    authService,
                    new PushTemplateDefaults(new NovaCoreProperties()),
                    mock(UpdateCheckService.class));
        }

        long bytes = usableBytes;
        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                fileService,
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
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
                authService,
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class)) {
            long usableSpaceBytes() {
                return bytes;
            }
        };
    }

    /**
     * 数据目录就是那一行要量的事：@TempDir 真存在，回包里就该有两个键
     */
    @Test
    @DisplayName("量得到时给剩余兆数与档位")
    void measurableDiskCarriesFreeAndLevel() {
        JSONObject runtime = controller(null).status().getJSONObject("runtime");

        assertTrue(runtime.containsKey("diskFreeMb"), "量得到就要给 diskFreeMb，那一格不该一直是「—」");
        assertTrue(runtime.getLongValue("diskFreeMb") >= 0, "剩余兆数不该是负数");
        assertTrue(runtime.containsKey("diskLevel"), "档位要与数值一起给，界面不自己判");
    }

    /**
     * 量不到时的退法：两个键都不发，而不是 0 或编出来的数
     * <p>
     * 读数以负数记「量不到」。真要把这一档演出来得让取盘这件事失败，
     * 而正常路径上它几乎不会失败（目录不存在会上溯到已存在的祖先），所以走那道缝喂。
     */
    @Test
    @DisplayName("量不出盘时两个键都不发，其余运行字段照给")
    void unmeasurableDiskOmitsKeys() {
        JSONObject runtime = controller(-1L).status().getJSONObject("runtime");

        assertFalse(runtime.containsKey("diskFreeMb"), "量不到就不发（缺席即没有），那一格由界面写「—」");
        assertFalse(runtime.containsKey("diskLevel"), "没有数值就不该有档位：两个键各走各的时，屏幕上不会有任何异常");
        assertTrue(runtime.containsKey("heapUsedMb"), "一块盘取不到不该让堆内存那几行陪葬");
    }

    /**
     * 两档阈值与边界：500MB 整不红、2GB 整不黄，压线的那一个字节不算进低档
     */
    @Test
    @DisplayName("不足 2GB 黄、不足 500MB 红，边界值留在高一档")
    void tiersByUsableBytes() {
        JSONObject at500Mb = controller(500L * 1024 * 1024).status().getJSONObject("runtime");
        assertEquals(500, at500Mb.getLongValue("diskFreeMb"));
        assertEquals("warn", at500Mb.getString("diskLevel"));

        JSONObject justUnder500Mb = controller(500L * 1024 * 1024 - 1).status().getJSONObject("runtime");
        assertEquals(499, justUnder500Mb.getLongValue("diskFreeMb"), "不足 1MB 的零头向下取整，档位按未取整的字节判");
        assertEquals("err", justUnder500Mb.getString("diskLevel"));

        JSONObject at2Gb = controller(2L * 1024 * 1024 * 1024).status().getJSONObject("runtime");
        assertEquals(2048, at2Gb.getLongValue("diskFreeMb"));
        assertEquals("ok", at2Gb.getString("diskLevel"));

        JSONObject justUnder2Gb = controller(2L * 1024 * 1024 * 1024 - 1).status().getJSONObject("runtime");
        assertEquals("warn", justUnder2Gb.getString("diskLevel"));

        JSONObject plenty = controller(12L * 1024 * 1024 * 1024 + 300L * 1024 * 1024)
                .status().getJSONObject("runtime");
        assertEquals(12588, plenty.getLongValue("diskFreeMb"));
        assertEquals("ok", plenty.getString("diskLevel"));
    }
}
