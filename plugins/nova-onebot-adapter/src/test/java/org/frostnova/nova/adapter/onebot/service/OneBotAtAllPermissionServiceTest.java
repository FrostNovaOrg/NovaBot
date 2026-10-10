package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @全体成员 权限判定：只有明确拿到角色才下结论
 *
 * <h2>这里守的是什么</h2>
 * 实现偶尔不返回角色时，明明是管理员的机器人，开播推送里的 @全体成员 被悄悄去掉，
 * 最长 30 分钟。反过来，接口抖一下时的「放行」若也记 30 分钟，这期间机器人被撤了管理员
 * 也照样 @全体。查不出就放行、且不写缓存，下次推送再查。
 */
@DisplayName("@全体成员 权限判定")
class OneBotAtAllPermissionServiceTest {
    private static final String SENDER = "qq-onebot";

    private static final long GROUP = 10000002L;

    private OneBotHttpAdapter http;

    private OneBotAtAllPermissionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        http = mock(OneBotHttpAdapter.class);
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));

        OneBotHttpService httpService = mock(OneBotHttpService.class);
        when(httpService.getSender(SENDER)).thenReturn(sender());

        ObjectProvider<OneBotHttpService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(httpService);

        service = new OneBotAtAllPermissionService(http, provider);
    }

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("token");
        return sender;
    }

    /**
     * 🔴 取不到成员信息时被算成「没有权限」，@全体成员 被悄悄摘掉，还记 30 分钟。
     */
    @Test
    @DisplayName("⚠️ 取成员信息回 null 时放行, 下次照旧再查")
    void missingMemberAllowsAndDoesNotCache() {
        when(http.getGroupMemberInfo(any(), any())).thenReturn(null);

        assertTrue(service.canAtAll(SENDER, GROUP), "查不出角色要放行");

        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"member\"}"));
        assertFalse(service.canAtAll(SENDER, GROUP), "上一次的放行不该被缓存, 这次该判出普通成员");
    }

    @Test
    @DisplayName("⚠️ 成员信息没有 role 时放行, 下次照旧再查")
    void missingRoleAllowsAndDoesNotCache() {
        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));

        assertTrue(service.canAtAll(SENDER, GROUP), "查不出角色要放行");

        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"member\"}"));
        assertFalse(service.canAtAll(SENDER, GROUP), "上一次的放行不该被缓存, 这次该判出普通成员");
    }

    @Test
    @DisplayName("⚠️ role 是不认识的值时放行, 下次照旧再查")
    void unknownRoleAllowsAndDoesNotCache() {
        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"guest\"}"));

        assertTrue(service.canAtAll(SENDER, GROUP), "不认识的角色要放行");

        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"member\"}"));
        assertFalse(service.canAtAll(SENDER, GROUP), "上一次的放行不该被缓存, 这次该判出普通成员");
    }

    /**
     * 🔴 接口抖一下时的「放行」也被记 30 分钟，这期间机器人被撤了管理员也照样 @全体。
     */
    @Test
    @DisplayName("⚠️ 查角色的接口出错时放行, 下次照旧再查")
    void queryFailureAllowsAndDoesNotCache() {
        doThrow(new IllegalStateException("接口抖了一下")).when(http).getGroupMemberInfo(any(), any());

        assertTrue(service.canAtAll(SENDER, GROUP), "接口出错要放行");

        // 换个回包：上一次那个抛异常的桩还在时, when() 那一下本身就会被它抛出来
        doReturn(JSON.parseObject("{\"role\":\"member\"}")).when(http).getGroupMemberInfo(any(), any());
        assertFalse(service.canAtAll(SENDER, GROUP), "上一次的放行不该被缓存, 这次该判出普通成员");
    }

    /**
     * 明确拿到角色时照旧下结论、照旧缓存，这是上面几格的对照。
     */
    @Test
    @DisplayName("阴性对照：明确是普通成员时判不允许, 且 30 分钟内不再问接口")
    void knownMemberIsCached() {
        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"member\"}"));

        assertFalse(service.canAtAll(SENDER, GROUP));
        assertFalse(service.canAtAll(SENDER, GROUP));

        verify(http, times(1)).getGroupMemberInfo(any(), any());
    }

    @Test
    @DisplayName("阴性对照：群主与管理员判允许, 且 30 分钟内不再问接口")
    void knownAdminIsCached() {
        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"owner\"}"));

        assertTrue(service.canAtAll(SENDER, GROUP));
        assertTrue(service.canAtAll(SENDER, GROUP));

        verify(http, times(1)).getGroupMemberInfo(any(), any());
    }
}
