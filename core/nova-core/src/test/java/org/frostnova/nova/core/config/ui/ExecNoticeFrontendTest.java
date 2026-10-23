package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部程序触发规则待调整时，控制台顶上的提醒
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("外部程序触发规则待调整提醒")
class ExecNoticeFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("exec-notice-fixture.mjs");

    @Test
    @DisplayName("有待调整的规则时出现并写明哪几条，没有时不出现")
    void noticeFollowsRulesToAdjust() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "外部程序触发规则待调整提醒");
    }

    @Test
    @DisplayName("页面闲置超过时限后会话到期")
    void openPageStillExpiresWhenIdle() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("node", FrontendFixture.repoRoot()
                .resolve(FrontendFixture.fixture("exec-notice-lifecycle-fixture.mjs")).toString(), "idle")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ConfigUiSessionStore sessions = new ConfigUiSessionStore(Duration.ofDays(1), Duration.ofHours(12));
        ConfigUiSession session = sessions.issue("127.0.0.1", now, ConfigUiSession.Channel.PASSWORD);
        long requests = 0;
        for (String line : output.lines().toList()) {
            if (line.startsWith("request ")) {
                requests++;
                sessions.validate(session.getId(), now.plusMillis(Long.parseLong(line.substring(8))));
            }
        }
        assertTrue(output.contains("elapsed 46800000"), "页面时间已走过闲置时限");
        System.out.println("页面闲置请求数: " + requests);
        assertTrue(sessions.validate(session.getId(), now.plus(Duration.ofHours(13))).isEmpty());
    }

    @Test
    @DisplayName("操作或切回页面后提醒跟随当前身份")
    void noticeUpdatesOnActivity() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("exec-notice-lifecycle-fixture.mjs"), "身份变化", "activity");
    }

    @Test
    @DisplayName("连续打字时 30 秒内只刷新一次提醒")
    void typingRefreshesAtMostOncePerInterval() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("exec-notice-lifecycle-fixture.mjs"), "连续按键", "typing");
    }

    @Test
    @DisplayName("没人操作时窗口获得焦点不发请求")
    void windowFocusAloneSendsNothing() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("exec-notice-lifecycle-fixture.mjs"), "窗口焦点", "focus");
    }

    @Test
    @DisplayName("内容相同的提醒在刷新期间保持原样")
    void unchangedNoticeStaysInPlace() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("exec-notice-lifecycle-fixture.mjs"), "提醒刷新", "stable");
    }
}
