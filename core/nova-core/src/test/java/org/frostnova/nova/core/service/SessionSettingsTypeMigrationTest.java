package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级时把旧版按「平台:会话号」存的会话设置抄成按会话类型分开存
 */
@DisplayName("会话设置按会话类型分开存的升级迁移")
class SessionSettingsTypeMigrationTest {
    private static final String PLATFORM = "qq-onebot";

    /** 推送配置里只有群 */
    private static final long GROUP_ONLY = 10001L;

    /** 推送配置里只有好友 */
    private static final long FRIEND_ONLY = 10002L;

    /** 推送配置里群与好友都有 */
    private static final long BOTH = 10003L;

    /** 推送配置里一种都没有 */
    private static final long NEITHER = 10004L;

    private NovaStateStore store;

    private RevenueVisibilityService revenue;

    private CommandSettingsService commands;

    private SessionQuietHoursService quiet;

    @BeforeEach
    void setUp() {
        store = new NovaStateStore(new NovaCoreProperties());
        revenue = new RevenueVisibilityService(store);
        commands = new CommandSettingsService(store);
        quiet = new SessionQuietHoursService(store);

        // 升级前的形状：键只有「平台:号」
        for (long num : List.of(GROUP_ONLY, FRIEND_ONLY, BOTH, NEITHER)) {
            String legacy = PLATFORM + ":" + num;
            store.write(RevenueVisibilityService.NAMESPACE, data -> data.put(legacy, true));
            store.write(CommandSettingsService.NAMESPACE, data -> data.put(legacy, new JSONObject().fluentPut("直播报告", 1)));
            store.write(SessionQuietHoursService.NAMESPACE, data -> data.put(legacy, new JSONObject().fluentPut("mode", "off")));
        }
    }

    private static List<PushUser> configured() {
        PushUser user = new PushUser();
        user.setUid(1L);
        user.setPlatform("bilibili");
        user.getTargets().add(target(PushTargetType.GROUP, GROUP_ONLY));
        user.getTargets().add(target(PushTargetType.FRIEND, FRIEND_ONLY));
        user.getTargets().add(target(PushTargetType.GROUP, BOTH));
        PushTarget friend = target(PushTargetType.FRIEND, BOTH);
        // 停用的目标也还在配置里
        friend.setEnabled(false);
        user.getTargets().add(friend);
        return List.of(user);
    }

    private static PushTarget target(PushTargetType type, long num) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(num);
        return target;
    }

    @Test
    @DisplayName("只有群的迁到群，只有好友的迁到好友")
    void oneConfiguredTypeMovesToThatSide() {
        // 用户故障：升级后旧设置要么丢了，要么套到同号的另一边
        new SessionSettingsTypeMigration(store, null).migrate(configured());

        assertTrue(revenue.isVisible(PLATFORM, PushTargetType.GROUP, GROUP_ONLY));
        assertNull(revenue.explicit(PLATFORM, PushTargetType.FRIEND, GROUP_ONLY));
        assertTrue(commands.isDisabled(PLATFORM, PushTargetType.GROUP, GROUP_ONLY, "直播报告"));
        assertFalse(commands.isDisabled(PLATFORM, PushTargetType.FRIEND, GROUP_ONLY, "直播报告"));
        assertEquals(Mode.OFF, quiet.get(PLATFORM, PushTargetType.GROUP, GROUP_ONLY).mode());
        assertEquals(Mode.FOLLOW, quiet.get(PLATFORM, PushTargetType.FRIEND, GROUP_ONLY).mode());

        assertEquals(Boolean.TRUE, revenue.explicit(PLATFORM, PushTargetType.FRIEND, FRIEND_ONLY));
        assertNull(revenue.explicit(PLATFORM, PushTargetType.GROUP, FRIEND_ONLY));
        assertTrue(commands.isDisabled(PLATFORM, PushTargetType.FRIEND, FRIEND_ONLY, "直播报告"));
        assertFalse(commands.isDisabled(PLATFORM, PushTargetType.GROUP, FRIEND_ONLY, "直播报告"));
        assertEquals(Mode.OFF, quiet.get(PLATFORM, PushTargetType.FRIEND, FRIEND_ONLY).mode());
        assertEquals(Mode.FOLLOW, quiet.get(PLATFORM, PushTargetType.GROUP, FRIEND_ONLY).mode());
    }

    @Test
    @DisplayName("两种都有、或一种都没有：两边各抄一份，与升级前生效的一样")
    void ambiguousCopiesToBothSides() {
        // 用户故障：认不出该归哪边时只迁一边，另一边升级后悄悄变了样
        new SessionSettingsTypeMigration(store, null).migrate(configured());

        for (long num : List.of(BOTH, NEITHER)) {
            for (PushTargetType type : List.of(PushTargetType.GROUP, PushTargetType.FRIEND)) {
                String where = num + " " + type;
                assertEquals(Boolean.TRUE, revenue.explicit(PLATFORM, type, num), where);
                assertTrue(commands.isDisabled(PLATFORM, type, num, "直播报告"), where);
                assertEquals(Mode.OFF, quiet.get(PLATFORM, type, num).mode(), where);
            }
        }

        // 两份是各自的：改一边不带动另一边
        commands.enable(PLATFORM, PushTargetType.FRIEND, NEITHER, "直播报告");
        assertTrue(commands.isDisabled(PLATFORM, PushTargetType.GROUP, NEITHER, "直播报告"));
    }

    @Test
    @DisplayName("推送配置为空：照一种都没有处理，两边都抄，也钉标记")
    void emptyDataSourceCopiesToBothSides() {
        new SessionSettingsTypeMigration(store, null).migrate(List.of());

        for (long num : List.of(GROUP_ONLY, FRIEND_ONLY, BOTH, NEITHER)) {
            assertTrue(revenue.isVisible(PLATFORM, PushTargetType.GROUP, num), String.valueOf(num));
            assertTrue(commands.isDisabled(PLATFORM, PushTargetType.FRIEND, num, "直播报告"), String.valueOf(num));
        }
        for (String namespace : SessionSettingsTypeMigration.NAMESPACES) {
            assertTrue(store.namespace(namespace).containsKey(SessionSettingsTypeMigration.MIGRATED_KEY), namespace);
        }
    }

    @Test
    @DisplayName("标记钉上后再启动不重迁；旧键原样留着，列表里不列旧键")
    void runsOnceAndKeepsLegacyKeys() {
        // 用户故障：每次启动都重抄一遍，把在控制台改过的设置又盖回升级前的样子
        new SessionSettingsTypeMigration(store, null).migrate(configured());
        revenue.set(PLATFORM, PushTargetType.FRIEND, NEITHER, false);
        commands.enable(PLATFORM, PushTargetType.GROUP, GROUP_ONLY, "直播报告");
        quiet.set(PLATFORM, PushTargetType.GROUP, BOTH, Mode.FOLLOW, null, null);

        new SessionSettingsTypeMigration(store, null).migrate(List.of());

        assertEquals(Boolean.FALSE, revenue.explicit(PLATFORM, PushTargetType.FRIEND, NEITHER));
        assertFalse(commands.isDisabled(PLATFORM, PushTargetType.GROUP, GROUP_ONLY, "直播报告"));
        assertEquals(Mode.FOLLOW, quiet.get(PLATFORM, PushTargetType.GROUP, BOTH).mode());
        assertNull(revenue.explicit(PLATFORM, PushTargetType.GROUP, FRIEND_ONLY), "第二次按空配置重迁了");

        for (String namespace : SessionSettingsTypeMigration.NAMESPACES) {
            JSONObject data = store.namespace(namespace);
            for (long num : List.of(GROUP_ONLY, FRIEND_ONLY, BOTH, NEITHER)) {
                assertTrue(data.containsKey(PLATFORM + ":" + num), namespace + " 的旧键 " + num + " 不见了");
            }
        }
        assertEquals(6, revenue.all().size(), "列表里混进了旧键：" + revenue.all());
        assertTrue(revenue.all().stream().allMatch(item -> item.type() != null));
    }
}
