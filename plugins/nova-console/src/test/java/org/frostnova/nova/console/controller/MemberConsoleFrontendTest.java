package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

class MemberConsoleFrontendTest {
    private String run(String mode) throws Exception {
        Path script = Path.of("src/test/resources/frontend/member-console-fixture.mjs").toAbsolutePath();
        Process process = new ProcessBuilder("node", "--experimental-vm-modules", script.toString(), mode)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "前端执行超时");
        System.out.print(output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    // 成员可见导航、首页、今日卡、名字和主播横幅都应按共享身份绘制，管理员保留原行为。
    @ParameterizedTest @ValueSource(strings = {"loading-member-auth/state", "loading-member-status", "loading-member-pages", "loading-member-streamers", "loading-auth/state", "loading-status", "loading-login", "loading-pages", "sidebar-admin", "sidebar-member", "source", "nav", "home", "setup", "today", "personal", "identity", "streamers", "admin", "legacy", "unknown"})
    void rendersConsoleForCurrentAccount(String mode) throws Exception { run(mode); }

    @ParameterizedTest @ValueSource(strings = {"single-enabled", "single-disabled", "single-prefix", "single-avatar-error", "single-narrow",
            "unchanged-multiple", "unchanged-zero", "unchanged-admin-one"})
    void singleStreamerMemberAndUnchangedAccounts(String mode) throws Exception { run(mode); }

    // 重新打开控制台后应重新数授权名单，不能沿用上一回的一人或多人界面。
    @Test void authorizationChangesApplyOnNextReload() throws Exception {
        run("single-enabled");
        run("unchanged-multiple");
        run("single-enabled");
    }

    // 升级缓存、慢网和重试会阻断首屏或重复导航；正常入口还须取消页面后备提示。
    @ParameterizedTest @ValueSource(strings = {"slow-plugins", "retry-plugins", "retry-member-plugins", "plugin-version", "plugin-version-next", "missing-main", "fallback-started"})
    void startupRecoversWithoutDuplicatePages(String mode) throws Exception { run(mode); }

    // 成员首屏和主播页误调管理接口时，失败对象会被当成空数据，必须用实际权限层拦出这种请求。
    @Test void actualFrontendRequestsPassMemberPermissions() throws Exception {
        String output = run("requests");
        var platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var controllers = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("org.frostnova.nova"))
            if (!bean.getBeanClassName().contains("$")) controllers.add(mock(Class.forName(bean.getBeanClassName())));
        var mvc = MockMvcBuilders.standaloneSetup(controllers.toArray())
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms)).build();
        var member = new ConfigUiAccount("member-a", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "Member A", null, null, null, List.of("bilibili:71001"), Instant.EPOCH, null, true);
        var failures = new ArrayList<String>();
        var requests = output.lines().filter(line -> line.startsWith("REQUEST ")).toList();
        assertFalse(requests.isEmpty());
        for (String line : requests) {
            String[] parts = line.split(" ", 3);
            var response = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[1]), parts[2])
                    .requestAttr(ConfigUiAccess.ACCOUNT, member)).andReturn().getResponse();
            if (response.getStatus() != 200) failures.add(line + " -> " + response.getStatus() + " " + response.getContentAsString(StandardCharsets.UTF_8));
        }
        assertEquals(List.of(), failures);
        System.out.println("Member frontend requests replayed: " + requests.size() + "; rejected: " + failures.size());
    }
}
