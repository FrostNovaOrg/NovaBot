package com.starlwr.bot.core.datasource;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.exception.DataSourceException;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.DataSourceServiceConfig;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 数据源抽象类测试
 * <p>
 * 这里是全部推送配置的唯一入口：谁被监听、推到哪个群、推什么模板，都由它维护。
 * 它出错的表现几乎总是「某个主播悄悄不推了」，运行期不报任何错，
 * 因此每条增删改的分支都要有用例钉住。
 */
@DisplayName("数据源抽象类")
class AbstractDataSourceTest {
    private static final String PLATFORM = "bilibili";

    private static final String HANDLER = "com.example.TestHandler";

    private ApplicationEventPublisher publisher;

    private StarBotEventHandlerService handlerService;

    private TestDataSource dataSource;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        handlerService = mock(StarBotEventHandlerService.class);

        StarBotEventHandler handler = mock(StarBotEventHandler.class);
        doReturn(StarBotExternalBaseEvent.class).when(handler).getEventType();
        // 每次返回新实例：initPushMessageParams 会往返回值里写入自定义参数，
        // 返回共享实例会让不同推送消息的参数互相串味
        when(handler.getDefaultParams()).thenAnswer(invocation -> {
            JSONObject params = new JSONObject();
            params.put("at_all", false);
            params.put("message", "默认模板");
            return params;
        });
        when(handlerService.getHandler(HANDLER)).thenReturn(Optional.of(handler));
        when(handlerService.getHandler(argThat(name -> !HANDLER.equals(name)))).thenReturn(Optional.empty());

        DataSourceServiceRegistry registry = new DataSourceServiceRegistry(List.of(new BilibiliDataSourceService()));
        dataSource = new TestDataSource(publisher, registry, handlerService);
    }

    @Test
    @DisplayName("添加后应能按平台与 UID 查到, 并发布新增事件")
    void shouldAddAndIndexUser() {
        dataSource.add(user(1L, true));

        assertTrue(dataSource.getUser(PLATFORM, 1L).isPresent());
        assertEquals(1, dataSource.getUsers(PLATFORM).size());
        assertEquals(1, dataSource.getAllUsers().size());
        assertEquals("主播1", dataSource.getUser(PLATFORM, 1L).orElseThrow().getUname(), "应由平台数据源服务补全昵称");

        verify(publisher).publishEvent(any(StarBotDataSourceAddEvent.class));
    }

    @Test
    @DisplayName("未启用的推送用户不应被加载")
    void shouldSkipDisabledUser() {
        dataSource.add(new ArrayList<>(List.of(user(1L, false), user(2L, true))));

        assertTrue(dataSource.getUser(PLATFORM, 1L).isEmpty());
        assertTrue(dataSource.getUser(PLATFORM, 2L).isPresent());
    }

    @Test
    @DisplayName("未启用的推送目标与推送消息应被剔除")
    void shouldStripDisabledTargetsAndMessages() {
        PushUser user = user(1L, true);
        user.getTargets().add(target(2222L, false, message(HANDLER, true)));
        user.getTargets().get(0).getMessages().add(message(HANDLER, false));

        dataSource.add(user);

        PushUser loaded = dataSource.getUser(PLATFORM, 1L).orElseThrow();
        assertEquals(1, loaded.getTargets().size(), "未启用的推送目标应被剔除");
        assertEquals(1, loaded.getTargets().get(0).getMessages().size(), "未启用的推送消息应被剔除");
    }

    @Test
    @DisplayName("同一平台同一 UID 重复添加应被拒绝")
    void shouldRejectDuplicateUser() {
        dataSource.add(user(1L, true));

        DataSourceException exception = assertThrows(DataSourceException.class, () -> dataSource.add(user(1L, true)));
        assertTrue(exception.getMessage().contains("已存在"), "实际为: " + exception.getMessage());
    }

    @Test
    @DisplayName("同一批次内出现重复用户应被拒绝")
    void shouldRejectDuplicateWithinBatch() {
        List<PushUser> users = new ArrayList<>(Arrays.asList(user(1L, true), user(1L, true)));

        assertThrows(DataSourceException.class, () -> dataSource.add(users));
    }

    @Test
    @DisplayName("缺少对应平台的数据源服务时只跳过该平台, 不影响其他平台")
    void shouldSkipOnlyUnsupportedPlatform() {
        PushUser unsupported = user(9L, true);
        unsupported.setPlatform("douyu");

        dataSource.add(new ArrayList<>(List.of(user(1L, true), unsupported)));

        assertTrue(dataSource.getUser(PLATFORM, 1L).isPresent(), "已支持的平台不应受牵连");
        assertTrue(dataSource.getUser("douyu", 9L).isEmpty());
        assertEquals(1, dataSource.getAllUsers().size());
    }

    @Test
    @DisplayName("移除后应查不到, 并发布移除事件")
    void shouldRemoveUser() {
        dataSource.add(user(1L, true));
        clearInvocations(publisher);

        dataSource.remove(dataSource.getUser(PLATFORM, 1L).orElseThrow());

        assertTrue(dataSource.getUser(PLATFORM, 1L).isEmpty());
        assertTrue(dataSource.getUsers(PLATFORM).isEmpty());
        assertTrue(dataSource.getAllUsers().isEmpty());
        verify(publisher).publishEvent(any(StarBotDataSourceRemoveEvent.class));
    }

    @Test
    @DisplayName("移除数据源中不存在的用户应被拒绝")
    void shouldRejectRemovingUnknownUser() {
        assertThrows(DataSourceException.class, () -> dataSource.remove(user(1L, true)));
    }

    @Test
    @DisplayName("更新时新用户走新增, 被禁用的用户走移除")
    void shouldRouteUpdateToAddAndRemove() {
        dataSource.add(user(1L, true));
        clearInvocations(publisher);

        dataSource.update(new ArrayList<>(List.of(user(1L, false), user(2L, true))));

        assertTrue(dataSource.getUser(PLATFORM, 1L).isEmpty(), "已禁用的用户应被移除");
        assertTrue(dataSource.getUser(PLATFORM, 2L).isPresent(), "新出现的用户应被新增");
        verify(publisher).publishEvent(any(StarBotDataSourceAddEvent.class));
        verify(publisher).publishEvent(any(StarBotDataSourceRemoveEvent.class));
    }

    @Test
    @DisplayName("更新后 getAllUsers 应返回新配置")
    void shouldRefreshUserListAfterUpdate() {
        dataSource.add(user(1L, true));

        PushUser updated = user(1L, true);
        updated.getTargets().add(target(3333L, true, message(HANDLER, true)));
        dataSource.update(updated);

        // getUser 走索引、getAllUsers 走列表，两者若不同步，
        // 配置界面「当前已加载的推送用户」会一直显示改动前的内容，
        // 使用者据此判断「配置没生效」，而实际推送用的已经是新配置
        assertEquals(2, dataSource.getUser(PLATFORM, 1L).orElseThrow().getTargets().size());
        assertEquals(1, dataSource.getAllUsers().size(), "更新不应造成重复条目");
        assertEquals(2, dataSource.getAllUsers().get(0).getTargets().size(), "getAllUsers 与 getUser 必须给出同一份配置");
    }

    @Test
    @DisplayName("更新内容与原先完全相同时不应发布更新事件")
    void shouldNotPublishWhenNothingChanged() {
        dataSource.add(user(1L, true));
        clearInvocations(publisher);

        dataSource.update(user(1L, true));

        verify(publisher, never()).publishEvent(any(StarBotDataSourceUpdateEvent.class));
    }

    @Test
    @DisplayName("更新时应发布携带新旧配置的更新事件")
    void shouldPublishUpdateEventWithBothVersions() {
        dataSource.add(user(1L, true));
        clearInvocations(publisher);

        PushUser updated = user(1L, true);
        updated.getTargets().add(target(3333L, true, message(HANDLER, true)));
        dataSource.update(updated);

        ArgumentCaptor<StarBotDataSourceUpdateEvent> captor = ArgumentCaptor.forClass(StarBotDataSourceUpdateEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(1, captor.getValue().getOldUser().getTargets().size());
        assertEquals(2, captor.getValue().getUser().getTargets().size());
    }

    @Test
    @DisplayName("事件处理器不存在时该条推送消息应被剔除")
    void shouldDropMessageWithUnknownHandler() {
        PushUser user = user(1L, true);
        user.getTargets().get(0).getMessages().add(message("com.example.NotExists", true));

        dataSource.add(user);

        PushUser loaded = dataSource.getUser(PLATFORM, 1L).orElseThrow();
        assertEquals(1, loaded.getTargets().get(0).getMessages().size(), "认不出处理器的推送消息留着也发不出去");
        assertEquals(HANDLER, loaded.getTargets().get(0).getMessages().get(0).getHandler());
    }

    @Test
    @DisplayName("自定义 params 应覆盖处理器的默认参数")
    void shouldMergeCustomParamsOverDefaults() {
        PushUser user = user(1L, true);
        user.getTargets().get(0).getMessages().get(0).setParams("{\"at_all\":true}");

        dataSource.add(user);

        JSONObject params = dataSource.getUser(PLATFORM, 1L).orElseThrow()
                .getTargets().get(0).getMessages().get(0).getParamsJsonObject();
        assertEquals(true, params.getBoolean("at_all"), "自定义项应覆盖默认值");
        assertEquals("默认模板", params.getString("message"), "未覆盖的默认项应保留");
    }

    /**
     * 构造一个带一个推送目标、一条推送消息的推送用户
     * @param uid UID
     * @param enabled 是否启用
     * @return 推送用户
     */
    private PushUser user(long uid, boolean enabled) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform(PLATFORM);
        user.setEnabled(enabled);
        user.setTargets(new ArrayList<>(List.of(target(1111L, true, message(HANDLER, true)))));
        return user;
    }

    /**
     * 构造推送目标
     * @param num 群号
     * @param enabled 是否启用
     * @param messages 推送消息
     * @return 推送目标
     */
    private PushTarget target(long num, boolean enabled, PushMessage... messages) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(num);
        target.setEnabled(enabled);
        target.setMessages(new ArrayList<>(List.of(messages)));
        return target;
    }

    /**
     * 构造推送消息
     * @param handler 事件处理器全类名
     * @param enabled 是否启用
     * @return 推送消息
     */
    private PushMessage message(String handler, boolean enabled) {
        PushMessage message = new PushMessage();
        message.setHandler(handler);
        message.setEnabled(enabled);
        return message;
    }

    /**
     * 供测试实例化的数据源实现，load 由各具体数据源负责，此处不参与
     */
    private static class TestDataSource extends AbstractDataSource {
        TestDataSource(ApplicationEventPublisher publisher, DataSourceServiceRegistry registry, StarBotEventHandlerService handlerService) {
            super(publisher, registry, handlerService);
        }

        @Override
        public void load() {
            // 由具体数据源实现，本测试只覆盖基类的增删改
        }
    }

    /**
     * 模拟平台插件提供的数据源服务，负责补全昵称等信息
     */
    @DataSourceServiceConfig(name = PLATFORM)
    private static class BilibiliDataSourceService implements DataSourceService {
        @Override
        public void completePushUser(PushUser user) {
            user.setUname("主播" + user.getUid());
            user.setRoomId(user.getUid() * 10);
        }
    }
}
