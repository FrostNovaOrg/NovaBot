package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveStatus.DISABLED;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.SwitchSource.EVENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QqBotHealthProbeTest {
    @TempDir
    Path dataDirectory;

    @Test
    void listsOnlyEnabledPushGroupsWhoseOwnersDisabledProactiveSending() {
        var settings = new QqBotSettings(dataDirectory);
        settings.save("12345678", "fixture-secret");
        var directory = new QqBotDirectory(settings, mock(QqBotApiClient.class), Clock.systemUTC(),
                task -> {}, QqBotDirectory::sha256);
        Instant now = Instant.now();
        long a = directory.groupSeen("fixture-group-a", now);
        long b = directory.groupSeen("fixture-group-b", now);
        long c = directory.groupSeen("fixture-group-c", now);
        directory.saveGroupRemark(a, "开播群");
        directory.saveGroupRemark(b, "闲聊群");
        directory.saveGroupRemark(c, "新群");
        directory.recordProactive(a, DISABLED, EVENT, now);
        directory.recordProactive(b, DISABLED, EVENT, now);
        var enabled = new PushUser();
        enabled.setTargets(List.of(target(a, "qq-official", PushTargetType.GROUP, null),
                target(a, "qq-official", PushTargetType.GROUP, true), target(c, "qq-official", PushTargetType.GROUP, true),
                target(b, "qq-official", PushTargetType.GROUP, false), target(b, "qq-onebot", PushTargetType.GROUP, true),
                target(b, "qq-official", PushTargetType.FRIEND, true)));
        var disabled = new PushUser();
        disabled.setEnabled(false);
        disabled.setTargets(List.of(target(b, "qq-official", PushTargetType.GROUP, true)));
        var source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of(enabled, disabled));
        var gateway = mock(QqBotGateway.class);
        when(gateway.health()).thenReturn(HealthStatus.ok("已连接"));
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> settings)
                .withBean("qqBotDirectory", QqBotDirectory.class, () -> directory)
                .withBean("qqBotGateway", QqBotGateway.class, () -> gateway)
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(AbstractDataSource.class, () -> source)
                .withBean(QqBotHttpTransport.class, () -> (method, uri, headers, body) -> fail("探针不应发请求"),
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    var probe = context.getBean(QqBotHealthProbe.class);
                    var status = probe.checkForConsole();
                    assertEquals(HealthStatus.Level.DEGRADED, status.level(), status::summary);
                    assertTrue(status.summary().contains("（开播群）"));
                    assertFalse(status.summary().contains("闲聊群") || status.summary().contains("新群"));
                    assertEquals(status.summary().indexOf("（开播群）"), status.summary().lastIndexOf("（开播群）"));
                    assertFalse(probe.check().summary().contains("开播群"));
                    assertTrue(status.advice().contains("群成员里点机器人 → 右上角设置 → 打开"));

                    directory.recordProactive(c, DISABLED, EVENT, now);
                    assertTrue(probe.checkForConsole().summary().contains("（新群）"));

                    var down = HealthStatus.down("连接断开", "检查连接", "unreachable");
                    when(gateway.health()).thenReturn(down);
                    assertEquals(down.level(), probe.check().level());
                    assertTrue(probe.check().summary().contains("连接断开"));
                    when(gateway.health()).thenReturn(HealthStatus.ok("已连接"));
                    when(source.getAllUsers()).thenReturn(List.of(disabled));
                    assertEquals(HealthStatus.Level.OK, probe.check().level());
                });
    }

    private static PushTarget target(long num, String platform, PushTargetType type, Boolean enabled) {
        var target = new PushTarget();
        target.setPlatform(platform);
        target.setNum(num);
        target.setType(type);
        target.setEnabled(enabled);
        return target;
    }
}
