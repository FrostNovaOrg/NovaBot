package org.frostnova.nova.core.command;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令开关测试
 */
@DisplayName("命令开关")
class CommandSettingsServiceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 10000003L;

    private static final PushTargetType T = PushTargetType.GROUP;

    private CommandSettingsService service;

    @BeforeEach
    void setUp() {
        service = new CommandSettingsService(new NovaStateStore(new NovaCoreProperties()));
    }

    @Test
    @DisplayName("默认全部启用")
    void enabledByDefault() {
        assertFalse(service.isDisabled(PLATFORM, T, GROUP, "菜单"));
    }

    @Test
    @DisplayName("禁用与启用应改变状态，并如实报告是否发生变化")
    void togglesAndReportsChange() {
        assertTrue(service.disable(PLATFORM, T, GROUP, "菜单"));
        assertTrue(service.isDisabled(PLATFORM, T, GROUP, "菜单"));
        assertFalse(service.disable(PLATFORM, T, GROUP, "菜单"), "本就禁用时不该报告变化");

        assertTrue(service.enable(PLATFORM, T, GROUP, "菜单"));
        assertFalse(service.isDisabled(PLATFORM, T, GROUP, "菜单"));
        assertFalse(service.enable(PLATFORM, T, GROUP, "菜单"), "本就启用时不该报告变化");
    }

    @Test
    @DisplayName("各会话的开关应彼此独立")
    void sessionsAreIndependent() {
        service.disable(PLATFORM, T, GROUP, "菜单");

        assertFalse(service.isDisabled(PLATFORM, T, 10000001L, "菜单"));
        assertFalse(service.isDisabled("telegram", T, GROUP, "菜单"));
    }

    @Test
    @DisplayName("同一平台同一个号：给群关的命令不影响好友，给好友关的不影响群")
    void sameNumberGroupAndFriendAreSeparate() {
        // 用户故障：群里关了某条命令，同号的好友也用不了
        service.disable(PLATFORM, PushTargetType.GROUP, GROUP, "直播报告");
        assertTrue(service.isDisabled(PLATFORM, PushTargetType.GROUP, GROUP, "直播报告"));
        assertFalse(service.isDisabled(PLATFORM, PushTargetType.FRIEND, GROUP, "直播报告"), "群里关的命令套到了同号好友");

        service.disable(PLATFORM, PushTargetType.FRIEND, GROUP, "菜单");
        assertTrue(service.isDisabled(PLATFORM, PushTargetType.FRIEND, GROUP, "菜单"));
        assertFalse(service.isDisabled(PLATFORM, PushTargetType.GROUP, GROUP, "菜单"), "好友那边关的命令套到了同号群");

        assertTrue(service.enable(PLATFORM, PushTargetType.FRIEND, GROUP, "菜单"));
        assertFalse(service.enable(PLATFORM, PushTargetType.FRIEND, GROUP, "直播报告"), "好友那边本来就没关这条");
        assertTrue(service.isDisabled(PLATFORM, PushTargetType.GROUP, GROUP, "直播报告"));
        assertEquals(List.of(PushTargetType.GROUP), service.all().stream().map(CommandSettingsService.Disabled::type).toList());
        assertFalse(service.isDisabled(PLATFORM, PushTargetType.UNKNOWN, GROUP, "直播报告"), "类型未知时一律当没禁用");
    }

    @Test
    @DisplayName("全量列出时应把键还原成平台与会话号")
    void listsAllWithFieldsRestored() {
        service.disable(PLATFORM, T, GROUP, "菜单");
        service.disable(PLATFORM, T, GROUP, "开播@我");

        List<CommandSettingsService.Disabled> all = service.all();

        assertEquals(1, all.size());
        // 平台名含连字符，若切分方式写错会把 qq 与 onebot 拆开
        assertEquals(PLATFORM, all.get(0).platform());
        assertEquals(GROUP.longValue(), all.get(0).num());
        assertEquals(List.of("菜单", "开播@我"), all.get(0).commands());
    }

    @Test
    @DisplayName("全量列出的结果应能直接拿去启用")
    void listedItemsCanBeEnabled() {
        service.disable(PLATFORM, T, GROUP, "菜单");
        service.disable("telegram", T, 10000001L, "我的数据");

        // 管理后台正是这么用的：把列出来的字段原样回传。字段一旦错位，
        // 界面上开回来的会是另一个群的命令
        for (CommandSettingsService.Disabled item : service.all()) {
            for (String command : item.commands()) {
                assertTrue(service.enable(item.platform(), item.type(), item.num(), command));
            }
        }

        assertTrue(service.all().isEmpty());
    }

    @Test
    @DisplayName("全部启用后残留的空记录不应出现在全量列表里")
    void skipsEmptyLeftovers() {
        service.disable(PLATFORM, T, GROUP, "菜单");
        service.enable(PLATFORM, T, GROUP, "菜单");

        // 启用只移除命令名，空对象会留在状态文件里
        assertTrue(service.all().isEmpty(), "界面上不该出现一个「禁用了 0 条命令」的会话");
    }

    @Test
    @DisplayName("多个会话应按平台与会话号排序")
    void sortsByPlatformAndSession() {
        service.disable("telegram", T, 100L, "菜单");
        service.disable(PLATFORM, T, 200L, "菜单");
        service.disable(PLATFORM, T, 100L, "菜单");

        assertEquals(List.of("qq-onebot:100", "qq-onebot:200", "telegram:100"),
                service.all().stream().map(d -> d.platform() + ":" + d.num()).toList());
    }

    @Test
    @DisplayName("没有任何禁用时应返回空表而非报错")
    void listsEmptyWhenNothingDisabled() {
        assertTrue(service.all().isEmpty());
    }
}
