package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号绑定测试
 */
@DisplayName("账号绑定")
class UserBindingServiceTest {
    private static final String PUSH = "qq-onebot";

    private static final String LIVE = "bilibili";

    private static final Long QQ = 2000000002L;

    private static final Long UID = 500000004L;

    private UserBindingService service;

    @BeforeEach
    void setUp() {
        service = new UserBindingService(new StarBotStateStore(new StarBotCoreProperties()));
    }

    @Test
    @DisplayName("未绑定时应返回空")
    void unboundIsEmpty() {
        assertEquals(Optional.empty(), service.get(PUSH, LIVE, QQ));
    }

    @Test
    @DisplayName("绑定后应能查回 uid")
    void bindThenGet() {
        service.bind(PUSH, LIVE, QQ, UID);

        assertEquals(Optional.of(UID), service.get(PUSH, LIVE, QQ));
    }

    @Test
    @DisplayName("重复绑定应覆盖为最新的 uid")
    void rebindOverwrites() {
        service.bind(PUSH, LIVE, QQ, UID);
        service.bind(PUSH, LIVE, QQ, 3000000000000001L);

        assertEquals(Optional.of(3000000000000001L), service.get(PUSH, LIVE, QQ));
    }

    @Test
    @DisplayName("解绑后应查不到，且能识别本就未绑定的情形")
    void unbind() {
        assertFalse(service.unbind(PUSH, LIVE, QQ));

        service.bind(PUSH, LIVE, QQ, UID);
        assertTrue(service.unbind(PUSH, LIVE, QQ));

        assertEquals(Optional.empty(), service.get(PUSH, LIVE, QQ));
    }

    @Test
    @DisplayName("不同账号的绑定应相互隔离")
    void bindingsIsolatedPerSender() {
        service.bind(PUSH, LIVE, QQ, UID);
        service.bind(PUSH, LIVE, 10000L, 3000000000000001L);

        assertEquals(Optional.of(UID), service.get(PUSH, LIVE, QQ));
        assertEquals(Optional.of(3000000000000001L), service.get(PUSH, LIVE, 10000L));
    }

    @Test
    @DisplayName("不同推送平台的同一账号应相互隔离")
    void bindingsIsolatedPerPushPlatform() {
        service.bind(PUSH, LIVE, QQ, UID);

        // QQ 号与其他平台的账号号段可能重合，绑定不能串台
        assertEquals(Optional.empty(), service.get("telegram", LIVE, QQ));
    }
}
