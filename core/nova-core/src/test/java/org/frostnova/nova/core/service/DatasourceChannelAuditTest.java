package org.frostnova.nova.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAudit;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasourceChannelAuditTest {
    @TempDir Path directory;
    Path file;
    JsonDataSource source;
    DatasourceChannelStore store;
    Logger logger;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach void setup() throws Exception {
        var properties = new NovaCoreProperties();
        file = directory.resolve("datasource.json");
        properties.getDatasource().setJsonPath(file.toString());
        Files.writeString(file, """
                [{"platform":"bilibili","uid":11,"targets":[{"platform":"qq","type":1,"num":100}]}]
                """);
        source = mock(JsonDataSource.class);
        var validator = mock(ConfigurationValidator.class);
        store = new DatasourceChannelStore(properties, source, validator);
        logger = (Logger) LoggerFactory.getLogger(DatasourceChannelStore.class);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
    }

    @AfterEach void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        logger.detachAppender(logs); logs.stop();
    }

    private void save() {
        assertTrue(store.update(users -> users.getJSONObject(0).getJSONArray("targets")
                .getJSONObject(0).put("revenueVisible", false), false));
    }

    // 主播名和操作者名不能用 NEL 伪造日志行或用方向控制符反转显示，中文表情仍保留。
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void namesCannotInsertLinesOrChangeDirection(boolean actorName) {
        String unsafe = "中文🙂\u0085甲\u202E乙\u2066丙";
        var user = new PushUser(); user.setPlatform("bilibili"); user.setUid(11L);
        user.setUname(actorName ? "主播" : unsafe);
        when(source.getAllUsers()).thenReturn(List.of(user));
        var account = new ConfigUiAccount("member-1", ConfigUiAccount.Role.ADMIN, ConfigUiAccount.Status.ENABLED,
                actorName ? unsafe : "操作者", null, null, null, List.of(), Instant.now(), null, true);
        var request = new MockHttpServletRequest(); request.setAttribute(ConfigUiAccess.ACCOUNT, account);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        save();
        assertEquals(1, logs.list.size());
        String line = logs.list.get(0).getFormattedMessage();
        assertTrue(line.contains("中文🙂 甲 乙 丙"), line);
        assertEquals(1, line.split("[\\r\\n\\u0085\\u2028\\u2029]", -1).length);
        assertFalse(line.contains("\u202E") || line.contains("\u2066"));
        verify(source).reload();
    }

    // 审计中断不能只留下无原因的警告；文件照存、重载照常执行，异常原因可供排查。
    @Test void auditWarningIncludesOriginalCauseAndStillReloads() throws Exception {
        var failure = new IllegalStateException("streamer lookup unavailable");
        when(source.getAllUsers()).thenThrow(failure);
        save();
        verify(source).reload();
        assertFalse(JSONArray.parseArray(Files.readString(file)).getJSONObject(0).getJSONArray("targets")
                .getJSONObject(0).getBooleanValue("revenueVisible"));
        var warning = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertTrue(warning.getFormattedMessage().contains("审计失败"));
        assertNotNull(warning.getThrowableProxy(), "警告必须携带审计异常");
        assertEquals(failure.getMessage(), warning.getThrowableProxy().getMessage());
        assertEquals(failure.getClass().getName(), warning.getThrowableProxy().getClassName());
    }

    // 内存耗尽等 Error 不能当普通审计失败吞掉。
    @Test void auditErrorsPropagate() {
        var failure = new AssertionError("audit fatal failure");
        when(source.getAllUsers()).thenThrow(failure);
        assertSame(failure, assertThrows(AssertionError.class, this::save));
    }

    // 全部控制字符及方向控制符都应清理，普通字符和已有换行清理规则不变。
    @Test void allControlAndDirectionalCharactersBecomeSpaces() {
        StringBuilder controls = new StringBuilder();
        for (int i = 0; i <= 0x9f; i++) if (Character.getType(i) == Character.CONTROL) controls.append((char) i);
        controls.append("\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u2028\u2029");
        assertEquals(" ".repeat(controls.length()), ConfigUiAudit.safe(controls.toString()));
        assertEquals("中文🙂 café", ConfigUiAudit.safe("中文🙂 café"));
        assertEquals("", ConfigUiAudit.safe(null));
    }
}
