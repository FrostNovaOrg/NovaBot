package org.frostnova.nova.core.protocol;

import org.frostnova.nova.core.properties.EventStreamProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 事件输出协议的真源必须由核心提供
 * <p>
 * 用反射按类名找，而不是直接 import：这条判据要在「类还不在核心里」的那一刻就能红，
 * 直接 import 只会编译不过——编译不过和判据不成立是两件事，
 * 前者看不出是缺了哪一个类，也没法让判据先红一次再转绿。
 *
 * <h2>另一半：搬家不许惊动下游</h2>
 * 真源换了模块，但地址、协议与配置对已经在用的客户端必须一个字都没变。
 * 地址与配置这两样在这里量；协议本身由同目录下那一批端点判据量。
 */
@DisplayName("事件输出真源在核心")
class NovaEventSourceInCoreTest {
    private static final String PACKAGE = "org.frostnova.nova.core.protocol.";

    @Test
    @DisplayName("WS 端点与其装配都在核心模块")
    void endpointAndWiringLiveInCoreModule() {
        assertDoesNotThrow(() -> Class.forName(PACKAGE + "NovaEventEndpoint"),
                "事件输出端点必须由核心提供");
        assertDoesNotThrow(() -> Class.forName(PACKAGE + "NovaEventStreamConfiguration"),
                "端点的装配必须由核心提供");
    }

    @Test
    @DisplayName("编号回补中枢与握手拦截也在核心模块")
    void replayAndHandshakeGuardAlsoInCoreModule() {
        assertDoesNotThrow(() -> Class.forName(PACKAGE + "NovaEventStream"),
                "编号与回补中枢必须由核心提供");
        assertDoesNotThrow(() -> Class.forName(PACKAGE + "NoCredentialsInHandshake"),
                "「握手不带凭据」的拦截必须由核心提供");
    }

    @Test
    @DisplayName("默认值原样：路径仍是 /nova/events，默认仍不开、不要口令")
    void defaultsUnchanged() {
        EventStreamProperties resolved = resolve(Map.of());

        assertEquals("/nova/events", resolved.getPath(), "端点路径不许改, 下游是照它连的");
        assertFalse(resolved.isEnabled(), "默认仍是关闭");
        assertFalse(resolved.isRequireToken(), "默认仍不要求口令");
        assertEquals(2000, resolved.getBufferSize(), "默认缓冲条数不变");
        assertEquals(2, NovaEventEndpoint.PROTOCOL_VERSION, "协议版本不许随搬家改动");
    }

    @Test
    @DisplayName("只写更早一档旧键时不再生效：仍关闭、路径仍是 /nova/events")
    void oldestLegacyKeysAreIgnored() {
        EventStreamProperties resolved = resolve(Map.of(
                "starbot.bilibili.event-stream.enabled", "true",
                "starbot.bilibili.event-stream.path", "/legacy/events"));

        assertFalse(resolved.isEnabled(), "旧键不得再打开事件输出");
        assertEquals("/nova/events", resolved.getPath(), "旧键不得再改路径");
    }

    /**
     * 按给定配置解析一次
     * @param properties 配置项
     * @return 解析结果
     */
    private EventStreamProperties resolve(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("判据", new LinkedHashMap<>(properties)));
        return new NovaEventStreamConfiguration().eventStreamProperties(environment);
    }
}
