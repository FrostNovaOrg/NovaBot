package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.util.DurableFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 磁盘写不进时登录凭据的去向
 * <p>
 * 凭据是扫码登录的唯一成果，盘上只有这一份。这一组专门验证「写盘失败」的各个时点
 * （续期落盘、TV 令牌落盘、扫码登录落盘、明文迁移加密）都不会把可用凭据弄丢：
 * 任何一处失败，重启后重新 load 都必须仍读得出一份能用的凭据。
 * <p>
 * 写盘失败用同一个办法模拟：凭据落盘走「先写同目录临时件再换名」，把临时件的位置
 * 占成一个目录，写入就会在打开临时件那一步失败，而原件分毫不动——正是磁盘出毛病时
 * 希望守住的性质。
 */
@DisplayName("磁盘写不进时的登录凭据保全")
class BilibiliCredentialPersistenceFailureTest {
    @TempDir
    Path dir;

    private NovaBilibiliProperties properties;
    private Path cookiePath;

    @BeforeEach
    void setUp() {
        cookiePath = dir.resolve("cookies.json");

        properties = new NovaBilibiliProperties();
        properties.getAccount().setCookiePath(cookiePath.toString());
        properties.getAccount().setKeyPath(dir.resolve("cookies.key").toString());
    }

    /**
     * 让此后每一次凭据写盘都失败，且失败前不改动盘上已有的文件
     */
    private void breakCredentialWrites() throws IOException {
        Files.createDirectory(DurableFiles.temporary(cookiePath));
    }

    /**
     * 构造一份 TV 端登录取得的凭据
     * @param accessToken 访问令牌
     * @param remaining 距令牌到期还剩多久
     * @return 凭据
     */
    private Cookies tvCookies(String accessToken, Duration remaining) {
        Cookies cookies = new Cookies("sess", "jct", "buvid", "refresh-token");
        cookies.setAccessToken(accessToken);
        cookies.setAccessTokenExpiresAt(System.currentTimeMillis() + remaining.toMillis());
        return cookies;
    }

    private List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Web 续期后新凭据存盘失败: 不作废旧凭据, 内存回退, 重启后仍是能用的旧凭据")
    void webRefreshWhoseSaveFailsMustNotInvalidateOldCredential() throws Exception {
        // 抓的用户故障: 磁盘写不进时, 盘上留的是旧凭据, 旧凭据却又被 confirm 作废,
        // 内存里的新凭据只活到这次进程结束——一重启就掉登录, 只能重新扫码
        Cookies current = new Cookies("old-sess", "old-jct", "old-buvid", "old-refresh-token");
        BilibiliCredentialStore store = new BilibiliCredentialStore(properties.getAccount());
        store.save(current);
        assertEquals("old-refresh-token", store.load().orElseThrow().getRefreshToken(),
                "前置条件: 旧凭据已在盘上且能读回");
        breakCredentialWrites();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.fetchLoginUid()).thenReturn(19805387116684L);

        BilibiliAccountService service = new BilibiliAccountService(api, store, properties);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");

        Cookies refreshed = new Cookies("new-sess", "new-jct", "new-buvid", "new-refresh-token");
        when(api.getCookies()).thenReturn(current);
        when(api.checkCookieRefresh()).thenReturn(new BilibiliApiUtil.CookieRefreshHint(true, 1684466082562L));
        when(api.getRefreshCsrf(anyString())).thenReturn("csrf-token");
        when(api.refreshCookies("csrf-token", "old-refresh-token")).thenReturn(refreshed);

        assertFalse(service.refreshCookiesIfNeeded(), "新凭据没存上盘, 这次续期不算完成");

        // 旧凭据没被作废: 盘上留的只有旧凭据, 作废了它, 重启后必然掉登录
        verify(api, never()).confirmCookieRefresh(anyString());

        // 内存回退到原凭据, 与盘上一致, 都是还有效的旧凭据
        InOrder order = inOrder(api);
        order.verify(api).setCookies(refreshed);
        order.verify(api).setCookies(current);

        // 重启(重新 load)后仍是能用的旧凭据
        Cookies afterRestart = new BilibiliCredentialStore(properties.getAccount())
                .load().orElseThrow(() -> new AssertionError("续期存盘失败后, 盘上的旧凭据应仍能读回"));
        assertEquals("old-sess", afterRestart.getSessData());
        assertEquals("old-refresh-token", afterRestart.getRefreshToken());
    }

    @Test
    @DisplayName("TV 令牌续期后新令牌存盘失败: 续期不算完成, 内存留新令牌, 盘上留旧令牌")
    void appRefreshWhoseSaveFailsMustNotReportRenewalComplete() throws Exception {
        // 抓的用户故障: TV 续期存不上盘时照记「登录令牌续期完成」并返回成功, 盘上还是旧令牌;
        // 重启后令牌对不上, 可能要重新扫码, 日志里却没有一句提醒
        Cookies current = tvCookies("old-access-token", Duration.ofDays(3));
        BilibiliCredentialStore store = new BilibiliCredentialStore(properties.getAccount());
        store.save(current);
        assertEquals("old-access-token", store.load().orElseThrow().getAccessToken(),
                "前置条件: 旧令牌已在盘上且能读回");
        breakCredentialWrites();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.fetchLoginUid()).thenReturn(19805387116684L);

        BilibiliAccountService service = new BilibiliAccountService(api, store, properties);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");
        clearInvocations(api);

        Cookies refreshed = tvCookies("new-access-token", Duration.ofDays(180));
        when(api.getCookies()).thenReturn(current);
        when(api.refreshAppToken()).thenReturn(refreshed);

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliAccountService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean renewed;
        try {
            renewed = service.refreshCookiesIfNeeded();
        } finally {
            logger.detachAppender(appender);
        }

        assertFalse(renewed, "新令牌没存上盘, 这次续期不算完成");
        assertFalse(messages(appender).stream().anyMatch(message -> message.contains("登录令牌续期完成")),
                "存盘失败时不许再记「续期完成」");
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("存盘失败")
                        && event.getFormattedMessage().contains("重新扫码")),
                "要有一条 error 日志写明新令牌存盘失败、重启后可能要重新扫码, 实为 " + messages(appender));

        // 平台侧已换发新令牌, 旧令牌还能不能用无从得知: 新令牌留在内存里接着用, 不回退
        verify(api).setCookies(refreshed);
        verify(api, never()).setCookies(current);

        // 重启(重新 load)后盘上仍是旧令牌
        Cookies afterRestart = new BilibiliCredentialStore(properties.getAccount())
                .load().orElseThrow(() -> new AssertionError("TV 续期存盘失败后, 盘上的旧令牌应仍能读回"));
        assertEquals("old-access-token", afterRestart.getAccessToken());
    }

    @Test
    @DisplayName("扫码登录后凭据存盘失败: 登录照常, 但要有一条 error 提醒重启后要重新扫码")
    void qrLoginWhoseSaveFailsMustStillLoginAndWarn() throws Exception {
        // 抓的用户故障: 扫码登录存不上盘时这次登录照用, 重启后要重新扫码, 日志里却没有
        // 一句提醒——使用者到重启那天才发现登录没了
        breakCredentialWrites();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getTvQrCodeLoginInfo())
                .thenReturn(new BilibiliApiUtil.QrCodeLogin("https://example.invalid/qr", "test-key"));
        when(api.getTvQrCodeLoginStatus(anyString())).thenReturn(true);
        when(api.getCookies()).thenReturn(new Cookies("sess", "jct", "buvid", "refresh-token"));
        when(api.getLoginUid()).thenReturn(null);

        BilibiliCredentialStore store = new BilibiliCredentialStore(properties.getAccount());
        BilibiliAccountService service = new BilibiliAccountService(api, store, properties);

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliAccountService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean loggedIn;
        try {
            loggedIn = service.loginByQrCode();
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(loggedIn, "存盘失败不该拦下登录: 凭据已在内存里, 本次进程内一切可用");
        assertFalse(Files.exists(cookiePath), "前置条件核对: 这格测的正是存盘失败");
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("存盘失败")
                        && event.getFormattedMessage().contains("重新扫码")),
                "要有一条 error 日志写明登录凭据存盘失败、重启后要重新扫码, 实为 " + messages(appender));
    }

    @Test
    @DisplayName("迁移加密时写加密件失败: 主文件仍是原来那份能读的明文, 重新 load 仍读得出凭据")
    void failedMigrationMustKeepReadablePlaintext() throws Exception {
        // 抓的用户故障: 迁移先把主文件挪去备份再写加密件, 加密件写失败时主文件已经不在原处,
        // 日志却说「凭据仍以明文保存」; 下次启动 load 读不到, 掉登录, 只能重新扫码
        JSONObject plain = new JSONObject();
        plain.put("sessData", "plain-sess");
        plain.put("biliJct", "plain-jct");
        plain.put("buvid3", "plain-buvid");
        plain.put("refreshToken", "plain-refresh-token");
        Files.writeString(cookiePath, plain.toJSONString());
        breakCredentialWrites();

        // 读到明文且配置开着加密, load 会顺手尝试迁移; 触发迁移的那次 load 应照常给出凭据
        Optional<Cookies> first = new BilibiliCredentialStore(properties.getAccount()).load();
        assertTrue(first.isPresent(), "触发迁移的那次 load 应照常给出凭据");

        // 重启(重新 load)后仍读得出原凭据——迁移失败不许弄丢主文件
        Cookies afterRestart = new BilibiliCredentialStore(properties.getAccount())
                .load().orElseThrow(() -> new AssertionError("迁移失败后主文件应仍在原处且能读, 重启后不该掉登录"));
        assertEquals("plain-sess", afterRestart.getSessData());
        assertEquals("plain-refresh-token", afterRestart.getRefreshToken());
    }
}
