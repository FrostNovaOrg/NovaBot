package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.UserBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行状态接口的对外面
 * <p>
 * 这里只量两件在界面之外也说得清的事：<b>累计数据这台机器开没开</b>要能从接口读到——
 * 界面把两行置灰、把摘要从 14 改成 12，靠的都是它，前端自己按名字硬认那两条命令的话，
 * 下次多一条「总」字命令就会漏；以及<b>账号绑定已经停用</b>——状态里不再有那一栏、
 * 控制器手上不再有那个服务，解绑接口回 410 而不是装作没有过这条路。
 * <p>
 * 依赖按类型现填，不写死构造参数表：这把尺守的是接口面，不该因为控制器多接一个依赖就编不过。
 */
@DisplayName("运行状态接口")
class RuntimeStateSurfaceTest {
    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private RuntimeStateController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = newController();
    }

    @Test
    @DisplayName("累计数据开没开要从状态里读得到")
    void statePublishesTotalDataAvailability() {
        LiveDataService liveData = dependency(LiveDataService.class);
        assertNotNull(liveData, "控制器没接累计数据这一路，状态里也就无从答起");

        for (boolean supported : new boolean[]{true, false}) {
            when(liveData.supportsTotalData()).thenReturn(supported);

            JSONObject state = controller.state();

            assertTrue(state.containsKey("totalDataAvailable"), "状态里没有这一项: " + state.keySet());
            assertEquals(supported, state.getBooleanValue("totalDataAvailable"));
        }
    }

    @Test
    @DisplayName("状态接口的栏目是一份闭集，bindings 已不在其中")
    void stateKeysAreAClosedSet() {
        JSONObject state = controller.state();

        // 闭集而不是「至少有这几栏」：多一栏也要红。接口面多出一栏没人消费的记录，
        // 与少一栏同样是问题——bindings 正是这么留了一版的
        assertEquals(List.of("commands", "incomplete", "sessions", "subscriptions", "success", "totalDataAvailable"),
                state.keySet().stream().sorted().toList());
        assertFalse(state.containsKey("bindings"),
                "账号绑定整族已停用、界面也早就不渲染它了，这一栏不该还在接口面上");
    }

    @Test
    @DisplayName("绑定已停用：接口连绑定服务都不再接")
    void bindingServiceIsNoLongerWired() {
        // 比「验它没被调用」严一档：手上没有那个服务，就不存在「哪天谁又调了一次」这回事。
        // 依赖表是按构造参数现填的，因此这一格问的是控制器真正接了什么
        assertNull(dependency(UserBindingService.class),
                "控制器仍接着绑定服务，而界面与状态接口都已经不再有绑定这回事: " + dependencies.keySet());
    }

    @Test
    @DisplayName("绑定已停用：接口回 410 而不是装作没有过这条路")
    void bindingEndpointIsGone() {
        // 404 会让旧界面、旧脚本以为地址写错了，去找一条并不存在的新地址；
        // 200 更糟——调用方会当成办成了
        assertEquals(410, controller.removeBinding().getStatusCode().value());
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.get(type));
    }

    private RuntimeStateController newController() throws Exception {
        Constructor<?> constructor = RuntimeStateController.class.getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = dependencies.computeIfAbsent(types[i], type -> mock(type));
        }

        constructor.setAccessible(true);
        return (RuntimeStateController) constructor.newInstance(args);
    }
}
