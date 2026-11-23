package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.UserBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行状态接口的对外面
 * <p>
 * 验证状态字段、金额可见性和已停用的账号绑定接口。
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
    @DisplayName("状态接口的栏目是一份闭集，bindings 已不在其中")
    void stateKeysAreAClosedSet() {
        JSONObject state = controller.state();

        // 闭集而不是「至少有这几栏」：多一栏也要红。接口面多出一栏没人消费的记录，
        // 与少一栏同样是问题——bindings 正是这么留了一版的
        assertEquals(List.of("commands", "incomplete", "quietGlobal", "sessions", "subscriptions", "success"),
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

    @Test
    @DisplayName("群会话金额可见性走 isVisible：替身回 true 时接口也是 true")
    void groupSessionRevenueVisibleFollowsService() {
        PushUser user = new PushUser();
        user.setUid(1L);
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(10001L);
        user.setTargets(List.of(target));
        when(dependency(AbstractDataSource.class).getAllUsers()).thenReturn(List.of(user));
        when(dependency(RevenueVisibilityService.class)
                .isVisible(any(), eq(PushTargetType.GROUP), any())).thenReturn(true);

        JSONObject session = controller.state().getJSONArray("sessions").getJSONObject(0);
        assertTrue(session.getBooleanValue("revenueVisible"));
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
