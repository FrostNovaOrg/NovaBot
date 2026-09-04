package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.EventStreamTokenService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查主播时那张确认小卡上的粉丝数
 * <p>
 * uid 打错一位仍可能查到一位真实存在的人，昵称与头像未必看得出不对，
 * 而粉丝数往往差着数量级——它是这张卡上最容易发现「认错人」的一项。
 * <p>
 * 要钉的另一头是<b>取不到时给 null 而不是 0</b>：0 会显示成
 * 「这位主播一个粉丝都没有」，那是一句凭空编出来的话。
 */
@DisplayName("查主播补粉丝数")
class StreamerLookupFansTest {
    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private DataSourceServiceRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        registry = mock(DataSourceServiceRegistry.class);
    }

    @Test
    @DisplayName("查到主播时把粉丝数一并给出")
    void lookupCarriesFansCount() {
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(service(243L)));

        JSONObject result = controller().lookupStreamer(request("1001"));

        assertTrue(result.getBooleanValue("success"));
        assertEquals("主播甲", result.getString("uname"));
        assertEquals(243L, result.getLongValue("fans"));
    }

    @Test
    @DisplayName("取不到粉丝数时给 null，其余字段照常返回")
    void missingFansIsNullNotZero() {
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(service(null)));

        JSONObject result = controller().lookupStreamer(request("1001"));

        assertTrue(result.getBooleanValue("success"), "粉丝数是附带字段, 拉不下来不该让整次查询判失败");
        assertEquals("主播甲", result.getString("uname"));
        assertNull(result.get("fans"), "0 会显示成「这位主播一个粉丝都没有」");
    }

    @Test
    @DisplayName("数据源实现自己抛了异常时也只是没有粉丝数，主播照样查得到")
    void throwingImplementationDoesNotBreakLookup() {
        DataSourceService service = new DataSourceService() {
            @Override
            public void completePushUser(PushUser user) {
                user.setUname("主播甲");
                user.setRoomId(20002L);
            }

            @Override
            public Optional<Long> getFansCount(Long uid) {
                throw new IllegalStateException("接口不可用");
            }
        };
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(service));

        JSONObject result = controller().lookupStreamer(request("1001"));

        assertTrue(result.getBooleanValue("success"));
        assertNull(result.get("fans"));
    }

    @Test
    @DisplayName("没实现取粉丝数的数据源，默认就是取不到")
    void defaultImplementationReturnsEmpty() {
        DataSourceService bare = user -> user.setUname("主播甲");

        assertTrue(bare.getFansCount(1001L).isEmpty(),
                "不是每个平台都有「粉丝」这个概念, 默认得是空而不是 0");
    }

    private DataSourceService service(Long fans) {
        return new DataSourceService() {
            @Override
            public void completePushUser(PushUser user) {
                user.setUname("主播甲");
                user.setRoomId(20002L);
                user.setFace("https://example.invalid/face.jpg");
            }

            @Override
            public Optional<Long> getFansCount(Long uid) {
                return Optional.ofNullable(fans);
            }
        };
    }

    private JSONObject request(String uid) {
        JSONObject body = new JSONObject();
        body.put("platform", "bilibili");
        body.put("uid", uid);
        return body;
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller() {
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.<HealthProbe>of());

        StarBotSenderService senders = mock(StarBotSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                registry,
                mock(ConfigurationLevelResolver.class),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                mock(RuntimeConfigurationApplier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(ObjectProvider.class),
                new PushGate(properties),
                mock(LiveDataService.class),
                mock(TimelineStore.class),
                mock(ConfigUiAuthService.class));
    }
}
