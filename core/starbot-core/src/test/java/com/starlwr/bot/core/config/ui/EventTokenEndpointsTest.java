package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.protocol.EventStreamTokenService;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 控制台的只读口令签发／吊销接口
 * <p>
 * 这里最要紧的一条不是「功能能用」，而是<b>响应里绝不能出现哈希或明文</b>——
 * 控制台可能在直播画面里被打开过，这不是假设，配置界面的机密项遮蔽就是为此存在的。
 */
@DisplayName("控制台只读口令接口")
class EventTokenEndpointsTest {
    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(properties.getLive());

        // 构造器只做赋值，其余依赖对本组用例毫无参与，全部给桩
        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.NovaSenderService.class),
                mock(com.starlwr.bot.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」。
                // 给个空上下文即可，本组用例不看生效时机
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                tokens,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                mock(com.starlwr.bot.core.timeline.TimelineStore.class),
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new StarBotCoreProperties()),
                mock(UpdateCheckService.class));
    }

    @Test
    @DisplayName("🔒 会话校验端点：只回 2xx 且不带响应体")
    void sessionCheckReturnsNoContent() {
        var response = controller.sessionCheck();

        // nginx 的 auth_request 只看状态码：2xx 放行、401/403 拒绝。
        // 这一位是契约——改成「200 带一段 body」也照样能用，
        // 但那段 body 会被 auth_request 直接丢掉，白白多一次序列化，
        // 而且会诱使后来人往里塞信息，那些信息谁也看不到
        assertTrue(response.getStatusCode().is2xxSuccessful(), "2xx 才会被 auth_request 当成放行");
        assertNull(response.getBody(), "校验端点唯一的输出就是状态码");
    }

    @Test
    @DisplayName("签发应返回明文，并明说这是唯一一次显示")
    void issueReturnsPlaintextOnce() {
        JSONObject result = controller.issueEventToken(Map.of("label", "面板-甲"));

        assertTrue(result.getBooleanValue("success"));
        String token = result.getString("token");
        assertNotNull(token);
        assertTrue(token.length() >= 32);
        assertTrue(result.getString("message").contains("唯一一次"),
                "必须把「关掉就再也看不到」讲清楚，否则会被当成缺陷");
    }

    @Test
    @DisplayName("没填「签给谁」应当拒绝签发")
    void refusesToIssueWithoutLabel() {
        assertFalse(controller.issueEventToken(Map.of()).getBooleanValue("success"));
        assertFalse(controller.issueEventToken(Map.of("label", "  ")).getBooleanValue("success"));
        // 没有标签就只能一次全撤，等于退回复用控制台令牌时的粒度
        assertEquals(0, tokens.list().size(), "拒绝之后不该留下半条记录");
    }

    @Test
    @DisplayName("🔴 守卫：清单绝不能带出哈希或明文")
    void listNeverLeaksSecrets() {
        String token = controller.issueEventToken(Map.of("label", "面板-甲")).getString("token");
        String hash = tokens.list().get(0).hash();

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        String serialized = items.toJSONString();

        assertFalse(serialized.contains(token), "明文绝不能出现在清单里");
        assertFalse(serialized.contains(hash), "哈希同样不该送到浏览器里");
        // 阳性对照：这条断言得能分辨——指纹是该出现的，出现了才说明我们确实在看这份数据
        assertTrue(serialized.contains(tokens.fingerprintOf(tokens.list().get(0))),
                "指纹该在，否则上面两条「不含」可能只是因为清单是空的");
    }

    @Test
    @DisplayName("清单要列出已吊销的，那是审计事实")
    void listIncludesRevoked() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));

        controller.revokeEventToken(fingerprint);

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        assertEquals(1, items.size(), "撤了不等于消失");
        JSONObject item = items.getJSONObject(0);
        assertFalse(item.getBooleanValue("active"));
        assertTrue(item.getLongValue("revokedAt") > 0);
        assertEquals("面板-甲", item.getString("label"), "签给谁要留着，否则不知道撤的是谁");
    }

    @Test
    @DisplayName("吊销的回执必须提醒「已建连接不会自动断」")
    void revokeWarnsAboutLiveConnections() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));

        JSONObject result = controller.revokeEventToken(fingerprint);

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("不会自动断开"),
                "这个坑换了机制也还在，回执里必须说");
    }

    /**
     * 连接页上那张外部面板卡按的就是这两个按钮，因此这条走的是端到端的一整趟
     * <p>
     * 两向都要量：撤过的那把必须被拒，紧接着新签的那把必须放行。
     * 只量前一向的话，一个「verify 一律返回 false」的实现照样全绿，而那时所有面板都连不上；
     * 只量后一向则连吊销做没做都不知道。
     * <p>
     * 两个端点各测各的不算数：这一条要答的是「界面上点了吊销之后，那把口令还认不认」，
     * 而吊销与校验分属两支代码，中间那一步（指纹认到了哪一行）正是最容易接错的地方。
     */
    @Test
    @DisplayName("🔴 吊销后旧口令必须被拒，而同一时刻新签的那把必须放行")
    void revokedTokenIsRejectedWhileFreshOneStillWorks() {
        String old = controller.issueEventToken(Map.of("label", "客厅那台")).getString("token");
        // 尺子先过阳性对照：撤之前它是认的，否则下面那句「撤完不认」可能只是从来就没认过
        assertTrue(tokens.verify(old), "刚签出来的口令就该认");

        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));
        assertTrue(controller.revokeEventToken(fingerprint).getBooleanValue("success"));

        assertFalse(tokens.verify(old), "撤过的口令必须被拒——它曾经有效，与「无效」不是同一件事");

        String fresh = controller.issueEventToken(Map.of("label", "换的那台")).getString("token");
        assertTrue(tokens.verify(fresh), "撤掉一把不该把这条路整个堵死");
        assertFalse(tokens.verify(old), "签了新的也不会让旧的复活");
    }

    @Test
    @DisplayName("撤一把不影响另一把")
    void revokeIsTargeted() {
        controller.issueEventToken(Map.of("label", "面板-甲"));
        controller.issueEventToken(Map.of("label", "面板-乙"));
        String first = tokens.fingerprintOf(tokens.list().get(0));

        controller.revokeEventToken(first);

        JSONArray items = controller.listEventTokens().getJSONArray("tokens");
        assertFalse(items.getJSONObject(0).getBooleanValue("active"));
        assertTrue(items.getJSONObject(1).getBooleanValue("active"));
    }

    @Test
    @DisplayName("签发写不进磁盘时回人话错误，不把明文交出去")
    void issueWriteFailureIsAHumanError() throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "occupied");
        ConfigUiController broken = controllerFor(blocker.resolve("data.json"));

        JSONObject result = broken.issueEventToken(Map.of("label", "面板-丁"));

        assertFalse(result.getBooleanValue("success"), "写盘失败不该报签发成功");
        assertNull(result.get("token"), "写盘失败不该交出明文");
        assertTrue(result.getString("message").contains("磁盘"),
                () -> "应当用人话说明写盘失败, 实际: " + result.getString("message"));
    }

    @Test
    @DisplayName("吊销写不进磁盘与「没找到」不是同一句话")
    void revokeWriteFailureIsNotTheSameAsMissing() throws Exception {
        controller.issueEventToken(Map.of("label", "面板-戊"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));

        JSONObject missing = controller.revokeEventToken("no-such-token");
        assertFalse(missing.getBooleanValue("success"));
        String missingMessage = missing.getString("message");

        Path ledger = dir.resolve("event-stream-tokens.jsonl");
        var original = Files.getPosixFilePermissions(ledger);
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("r--r--r--"));
        try {
            JSONObject failed = controller.revokeEventToken(fingerprint);
            assertFalse(failed.getBooleanValue("success"), "写不进盘不该报吊销成功");
            assertNotEquals(missingMessage, failed.getString("message"),
                    "写盘失败与没找到必须分句, 实际都是: " + failed.getString("message"));
            assertTrue(failed.getString("message").contains("磁盘"),
                    () -> "应当用人话说明写盘失败, 实际: " + failed.getString("message"));
        } finally {
            Files.setPosixFilePermissions(ledger, original);
        }
    }

    /**
     * 两口的每个失败分支都带机器可读的 reason，与文案分开
     * <p>
     * 吊销「没找到」与「写不进盘」原先只靠文案区分，而界面不能拿文案当分派键——
     * 文案是会改的东西，改的那天前端就把「清单已过期，该重取」错读成「写坏了，别动」。
     */
    @Test
    @DisplayName("🔴 两口的每个失败分支都带机器可读的 reason，与文案分开")
    void failureBodiesCarryMachineReadableReason() throws Exception {
        JSONObject missingLabel = controller.issueEventToken(Map.of());
        assertFalse(missingLabel.getBooleanValue("success"));
        assertEquals("missing_label", missingLabel.getString("reason"));
        assertFalse(missingLabel.getString("message").isBlank(), "message 得在，reason 不是文案的替代");

        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "occupied");
        JSONObject issueFailed = controllerFor(blocker.resolve("data.json"))
                .issueEventToken(Map.of("label", "面板-丁"));
        assertFalse(issueFailed.getBooleanValue("success"));
        assertEquals("write_failed", issueFailed.getString("reason"));
        assertFalse(issueFailed.getString("message").isBlank());

        JSONObject missing = controller.revokeEventToken("no-such-token");
        assertFalse(missing.getBooleanValue("success"));
        assertEquals("not_found", missing.getString("reason"));
        assertFalse(missing.getString("message").isBlank());

        controller.issueEventToken(Map.of("label", "面板-己"));
        String fingerprint = tokens.fingerprintOf(tokens.list().get(0));
        Path ledger = dir.resolve("event-stream-tokens.jsonl");
        var original = Files.getPosixFilePermissions(ledger);
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("r--r--r--"));
        try {
            JSONObject revokeFailed = controller.revokeEventToken(fingerprint);
            assertFalse(revokeFailed.getBooleanValue("success"));
            assertEquals("write_failed", revokeFailed.getString("reason"));
            assertFalse(revokeFailed.getString("message").isBlank());
        } finally {
            Files.setPosixFilePermissions(ledger, original);
        }
    }

    private ConfigUiController controllerFor(Path dataFile) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dataFile.toString());
        EventStreamTokenService localTokens = new EventStreamTokenService(properties.getLive());
        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.NovaSenderService.class),
                mock(com.starlwr.bot.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                localTokens,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                mock(com.starlwr.bot.core.timeline.TimelineStore.class),
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new StarBotCoreProperties()),
                mock(UpdateCheckService.class));
    }
}
