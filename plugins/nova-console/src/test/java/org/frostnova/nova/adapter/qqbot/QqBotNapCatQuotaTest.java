package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.adapter.onebot.service.OneBotAtAllPermissionService;
import org.frostnova.nova.adapter.onebot.service.OneBotHttpService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QqBotNapCatQuotaTest {
    @Test
    void napCatKeepsTheSecondMentionWithBothAdaptersInstalled(@TempDir Path directory) {
        String platform = "qq-onebot";
        long group = 30003L;
        var napCat = new OneBotSender();
        napCat.setName(platform);
        var service = mock(OneBotHttpService.class);
        when(service.getSender(platform)).thenReturn(napCat);
        var http = mock(OneBotHttpAdapter.class);
        when(http.getLoginInfo(eq(napCat), any())).thenReturn(JSONObject.of("user_id", 10001L));
        when(http.getGroupMemberInfo(eq(napCat), any())).thenReturn(JSONObject.of("role", "admin"));

        new ApplicationContextRunner()
                .withBean(QqBotDirectory.class, () -> new QqBotDirectory(new QqBotSettings(directory), mock(QqBotApiClient.class)))
                .withBean(OneBotHttpAdapter.class, () -> http)
                .withBean(OneBotHttpService.class, () -> service)
                .withBean(OneBotAtAllPermissionService.class)
                .withBean(NovaCoreProperties.class)
                .withBean(AtAllQuotaService.class)
                .withBean(NovaSenderService.class)
                .run(context -> {
                    var quota = context.getBean(AtAllQuotaService.class);
                    assertEquals(1, quota.sessionDailyLimit("qq-official"));
                    var registry = context.getBean(NovaSenderService.class);
                    var delivered = new ArrayList<String>();
                    registry.addSender(new Sender(platform, null, "", 0, (headers, params) -> {
                        delivered.add((String) params.get("content"));
                        return JSONObject.of("code", 0);
                    }));
                    var sender = new NovaMessageSender(null, registry, mock(PushActivityRecorder.class), null,
                            event -> {}, quota, context.getBeanProvider(AtAllPermissionResolver.class), null);
                    try {
                        for (int i = 0; i < 2; i++) {
                            var notice = Message.create(platform, PushTargetType.GROUP, group, "{at=all}开播了").get(0);
                            assertEquals(0, sender.sendNow(notice).getIntValue("code"));
                        }
                        assertEquals(List.of("{at=all}开播了", "{at=all}开播了"), delivered);
                        assertEquals(2, quota.used(platform, group));
                        verify(http).getGroupMemberInfo(napCat, JSONObject.of("group_id", group, "user_id", 10001L));
                    } finally {
                        sender.shutdown();
                    }
                });
    }
}
