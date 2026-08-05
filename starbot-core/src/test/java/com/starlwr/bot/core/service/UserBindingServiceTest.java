package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    @DisplayName("全量列出时应把键还原成推送平台、直播平台与账号")
    void listsAllWithFieldsRestored() {
        service.bind(PUSH, LIVE, QQ, UID);

        List<UserBindingService.Binding> all = service.all();

        assertEquals(1, all.size());
        UserBindingService.Binding item = all.get(0);
        // 推送平台名含连字符，若切分方式写错会把 qq 与 onebot 拆开
        assertEquals(PUSH, item.pushPlatform());
        assertEquals(LIVE, item.livePlatform());
        assertEquals(QQ.longValue(), item.senderUid());
        assertEquals(UID.longValue(), item.liveUid());
    }

    @Test
    @DisplayName("全量列出的结果应能直接拿去解绑")
    void listedItemsCanBeUnbound() {
        service.bind(PUSH, LIVE, QQ, UID);
        service.bind("telegram", LIVE, 10000L, 3000000000000001L);

        // 管理后台正是这么用的：把列出来的字段原样回传。字段一旦错位，
        // 界面上点「解绑」会解掉另一个人的绑定
        for (UserBindingService.Binding item : service.all()) {
            assertTrue(service.unbind(item.pushPlatform(), item.livePlatform(), item.senderUid()));
        }

        assertTrue(service.all().isEmpty());
    }

    @Test
    @DisplayName("全量列出应按平台与账号排序")
    void sortsByPlatformAndSender() {
        service.bind(PUSH, LIVE, 200L, UID);
        service.bind("telegram", LIVE, 100L, UID);
        service.bind(PUSH, LIVE, 100L, UID);

        assertEquals(List.of("qq-onebot:100", "qq-onebot:200", "telegram:100"),
                service.all().stream().map(b -> b.pushPlatform() + ":" + b.senderUid()).toList());
    }

    @Test
    @DisplayName("未绑定任何人时应返回空表而非报错")
    void listsEmptyWhenNoBinding() {
        assertTrue(service.all().isEmpty());
    }
}
