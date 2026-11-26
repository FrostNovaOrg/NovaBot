package org.frostnova.nova.interact.speaker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.interact.config.InteractProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 发言账号
 * <p>
 * 各格对着「情形表」里的一行：没登录不发请求、同一个号拒绝、监听账号免登录时照样能登、
 * 发言账号失效不碰监听账号、凭据加密且与监听账号的文件分开、重启后保持登录、退出只退自己。
 */
@DisplayName("哔哩哔哩发言账号")
class BilibiliSpeakerAccountTest {
    private static final long LISTENER_UID = 42L;

    private static final long SPEAKER_UID = 7L;

    /**
     * 凭据取值：落盘与日志里都不该出现这几个字
     */
    private static final String SESS = "speaker-sess-value-0001";

    private static final String JCT = "speaker-jct-value-0001";

    @TempDir
    Path dir;

    private ScheduledExecutorService worker;

    private BilibiliApiUtil api;

    private BilibiliAccountService listener;

    private ListAppender<ILoggingEvent> logs;

    private final List<Logger> attached = new ArrayList<>();

    @BeforeEach
    void setUp() {
        worker = Executors.newSingleThreadScheduledExecutor();
        api = mock(BilibiliApiUtil.class);
        listener = mock(BilibiliAccountService.class);
        when(listener.getLoginUid()).thenReturn(LISTENER_UID);

        logs = new ListAppender<>();
        logs.start();
        for (Class<?> type : List.of(BilibiliAccountService.class, BilibiliSpeakerAccount.class,
                BilibiliCredentialStore.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.addAppender(logs);
            attached.add(logger);
        }
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
        attached.forEach(logger -> logger.detachAppender(logs));
    }

    private NovaBilibiliProperties.Account settings(NovaBilibiliProperties bilibili) {
        InteractProperties interact = new InteractProperties();
        interact.getAccount().setCookiePath(dir.resolve("speaker-cookies.json").toString());
        interact.getAccount().setKeyPath(dir.resolve("speaker-cookies.key").toString());
        return new SpeakerAccountSettings(bilibili.getAccount(), interact.getAccount());
    }

    private BilibiliSpeakerAccount speaker(BilibiliApiUtil api, NovaBilibiliProperties.Account settings,
                                           BilibiliCredentialStore store) {
        return new BilibiliSpeakerAccount(api, settings, store, listener, worker);
    }

    private void drain() throws Exception {
        // 单线程：排在后面的空任务跑到了，前面那一轮就跑完了。扫码一轮等一次轮询（3 秒），留足余量
        worker.submit(() -> { }).get(30, TimeUnit.SECONDS);
    }

    private void scanSucceedsAs(BilibiliApiUtil api, long uid) {
        when(api.getTvQrCodeLoginInfo()).thenReturn(new BilibiliApiUtil.QrCodeLogin("https://example.invalid/qr", "key"));
        when(api.getTvQrCodeLoginStatus(anyString())).thenReturn(true);
        Cookies cookies = new Cookies(SESS, JCT, "buvid-demo");
        cookies.setAccessToken("speaker-token-value-0001");
        cookies.setRefreshToken("speaker-refresh-value-0001");
        cookies.setAccessTokenExpiresAt(System.currentTimeMillis() + 86_400_000L);
        when(api.getCookies()).thenReturn(cookies);
        when(api.getLoginUid()).thenReturn(uid);
    }

    /**
     * 抓的用户故障：只是装了插件、没打算用发言功能，程序却拿一个没登录的号去问哔哩哔哩，
     * 平白多出请求，还可能招来风控
     */
    @Test
    @DisplayName("没登录发言账号：启动、例行复检、连接页刷新、自检都不向哔哩哔哩发请求")
    void noRequestsWithoutLogin() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.empty());
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);
        BilibiliSpeakerLoginProvider provider = new BilibiliSpeakerLoginProvider(speaker);
        BilibiliSpeakerLoginHealthProbe probe = new BilibiliSpeakerLoginHealthProbe(speaker);

        speaker.onApplicationReady();
        drain();
        speaker.maintainIfLoggedIn();
        provider.isLoggedIn();
        provider.accountName();
        provider.pendingQrCodeContent();
        provider.loginNotice();
        provider.credentialNote();

        verifyNoInteractions(api);
        assertFalse(probe.active(), "从没登录过发言账号不算故障，自检项不该出现");
        assertFalse(new BilibiliSpeakerService(speaker).available());
    }

    /**
     * 抓的用户故障：把监听账号也扫成了发言账号，发言一旦被限制，监听、开播通知和直播报告一起停
     */
    @Test
    @DisplayName("扫出来的是监听账号：拒绝登录、不存盘、说明原因")
    void sameAccountAsListenerIsRefused() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.empty());
        scanSucceedsAs(api, LISTENER_UID);
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);

        assertEquals("正在获取二维码，请稍候", speaker.startLogin());
        drain();

        assertFalse(speaker.isLoggedIn(), "同一个号不该登上");
        verify(store, never()).save(any());
        verify(api).setCookies(argThat(cookies -> cookies.getSessData() == null));
        assertEquals(Optional.of(BilibiliSpeakerAccount.SAME_AS_LISTENER), speaker.notice());
        assertTrue(speaker.notice().get().contains("监听"), "理由里得说清楚是监听账号");
        // 想用同一个号发弹幕的人，被拒之后得知道去哪儿改：指到「直播间互动」页上的那个选项
        assertTrue(speaker.notice().get().contains("直播间互动")
                        && speaker.notice().get().contains("发弹幕用哪个账号")
                        && speaker.notice().get().contains("和监听直播间用的同一个账号"),
                "拒绝的文案没有指路到「发弹幕用哪个账号」那个选项: " + speaker.notice().get());
        assertTrue(speaker.pendingQrCodeContent().isEmpty(), "被拒之后不该再摆着那张码");
        assertFalse(new BilibiliSpeakerLoginHealthProbe(speaker).active(), "被拒的那次不算登录过");
    }

    /**
     * 抓的用户故障：监听账号开着免登录模式，发言账号也跟着被当成「不许登录」
     */
    @Test
    @DisplayName("监听账号免登录模式：发言账号照样可以扫码登录")
    void anonymousListenerStillAllowsSpeaker() throws Exception {
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        bilibili.getAccount().setAnonymous(true);
        when(listener.isAnonymous()).thenReturn(true);
        when(listener.getLoginUid()).thenReturn(null);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.save(any())).thenReturn(true);
        scanSucceedsAs(api, SPEAKER_UID);
        BilibiliSpeakerAccount speaker = speaker(api, settings(bilibili), store);

        speaker.startLogin();
        drain();

        assertTrue(speaker.isLoggedIn());
        assertEquals(SPEAKER_UID, speaker.uid());
        verify(store).save(any());
        assertTrue(new BilibiliSpeakerService(speaker).available());
    }

    /**
     * 抓的用户故障：发言账号凭据过期，连带把监听账号也当成掉了登录；或者反过来，
     * 发言账号已经不能用了，界面上却毫无动静
     */
    @Test
    @DisplayName("发言账号失效：只报它自己，监听账号一下都不碰")
    void speakerExpiryLeavesListenerAlone() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies(SESS, JCT, "buvid-demo")));
        when(api.fetchLoginUid()).thenReturn(SPEAKER_UID);
        when(api.getCookies()).thenReturn(new Cookies(SESS, JCT, "buvid-demo"));
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);
        BilibiliSpeakerLoginHealthProbe probe = new BilibiliSpeakerLoginHealthProbe(speaker);

        speaker.restore();
        assertTrue(speaker.isLoggedIn());
        assertEquals(HealthStatus.Level.OK, probe.check().level());

        when(api.fetchLoginUid()).thenThrow(new ResponseCodeException(BilibiliApiUtil.CODE_NOT_LOGGED_IN, "账号未登录"));
        speaker.maintainIfLoggedIn();

        assertFalse(speaker.isLoggedIn());
        assertTrue(probe.active(), "登录过、后来失效：自检项要报出来");
        HealthStatus status = probe.check();
        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("监听账号") && status.advice().contains("不受影响"), status.advice());
        verify(store, never()).clear();
        verify(listener, atLeast(0)).getLoginUid();
        verifyNoMoreInteractions(listener);

        // 失效之后不再拿这个号去问：答案已经知道了
        speaker.maintainIfLoggedIn();
        verify(api, atLeast(1)).fetchLoginUid();
        int calls = org.mockito.Mockito.mockingDetails(api).getInvocations().size();
        speaker.maintainIfLoggedIn();
        assertEquals(calls, org.mockito.Mockito.mockingDetails(api).getInvocations().size());
    }

    /**
     * 抓的用户故障：把发言账号的凭据文件配成了监听账号那一份，在发言账号卡上点「退出登录」，
     * 监听账号的凭据文件被删掉，监听账号跟着掉了登录
     */
    @Test
    @DisplayName("凭据文件与监听账号的同一个：发言账号不启用，不读不写不删那个文件，卡上与自检说明原因")
    void sharedCredentialFileWithListenerIsRefused() throws Exception {
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        Path listenerCookies = Files.writeString(dir.resolve("cookies.json"), "{\"payload\":\"listener\"}");
        Path listenerKey = Files.writeString(dir.resolve("cookies.key"), "listener-key");
        bilibili.getAccount().setCookiePath(listenerCookies.toString());
        bilibili.getAccount().setKeyPath(listenerKey.toString());
        byte[] cookiesBefore = Files.readAllBytes(listenerCookies);
        byte[] keyBefore = Files.readAllBytes(listenerKey);

        InteractProperties interact = new InteractProperties();
        // 写法不同、落点相同：路径里绕一层目录再回来
        Files.createDirectories(dir.resolve("sub"));
        interact.getAccount().setCookiePath(dir.resolve("sub/../cookies.json").toString());
        interact.getAccount().setKeyPath(dir.resolve("speaker-cookies.key").toString());
        scanSucceedsAs(api, SPEAKER_UID);
        BilibiliSpeakerAccount speaker = speaker(api,
                new SpeakerAccountSettings(bilibili.getAccount(), interact.getAccount()), null);
        BilibiliSpeakerLoginHealthProbe probe = new BilibiliSpeakerLoginHealthProbe(speaker);

        speaker.onApplicationReady();
        drain();
        speaker.startLogin();
        drain();
        speaker.logout();
        drain();

        assertArrayEquals(cookiesBefore, Files.readAllBytes(listenerCookies), "监听账号的凭据文件被改了或被删了");
        assertArrayEquals(keyBefore, Files.readAllBytes(listenerKey), "监听账号的密钥文件被改了");
        verifyNoInteractions(api);
        assertFalse(speaker.isLoggedIn());
        assertTrue(speaker.notice().orElse("").contains("不能共用同一个凭据文件"), speaker.notice().toString());
        assertTrue(probe.active(), "路径冲突要在自检里报出来");
        assertEquals(HealthStatus.Level.DOWN, probe.check().level());
        assertTrue(probe.check().advice().contains("各自的路径"), probe.check().advice());
        assertFalse(new BilibiliSpeakerService(speaker).available());
    }

    /**
     * 抓的用户故障：发言账号的凭据明文落盘、混进监听账号的文件，或者把监听账号的凭据改掉；
     * 重启之后发言账号要重新扫码
     */
    @Test
    @DisplayName("凭据加密存进自己的文件、重启后保持登录、退出只清自己的；监听账号的文件分毫不动，日志里没有凭据值")
    void credentialsAreEncryptedSeparateAndSurviveRestart() throws Exception {
        Path listenerCookies = Files.writeString(dir.resolve("cookies.json"), "{\"payload\":\"listener\"}");
        Path listenerKey = Files.writeString(dir.resolve("cookies.key"), "listener-key");
        byte[] listenerCookiesBefore = Files.readAllBytes(listenerCookies);
        byte[] listenerKeyBefore = Files.readAllBytes(listenerKey);

        NovaBilibiliProperties.Account settings = settings(new NovaBilibiliProperties());
        scanSucceedsAs(api, SPEAKER_UID);
        BilibiliSpeakerAccount speaker = speaker(api, settings, null);
        speaker.startLogin();
        drain();
        assertTrue(speaker.isLoggedIn());

        Path saved = dir.resolve("speaker-cookies.json");
        Path key = dir.resolve("speaker-cookies.key");
        String content = Files.readString(saved, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"payload\""), "凭据文件不是加密格式: " + content);
        assertFalse(content.contains(SESS) || content.contains(JCT), "凭据明文落盘了");
        assertTrue(Files.exists(key), "加密密钥应存进发言账号自己的密钥文件");
        if (Files.getFileStore(saved).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)));
        }

        // 重启：新的一份接口工具、新的一个发言账号，同一份配置
        BilibiliApiUtil restarted = mock(BilibiliApiUtil.class);
        when(restarted.fetchLoginUid()).thenReturn(SPEAKER_UID);
        BilibiliSpeakerAccount again = speaker(restarted, settings, null);
        again.restore();
        assertTrue(again.isLoggedIn(), "重启后发言账号应保持登录");
        verify(restarted).setCookies(argThat(cookies -> SESS.equals(cookies.getSessData()) && JCT.equals(cookies.getBiliJct())));

        again.logout();
        assertFalse(Files.exists(saved), "退出登录应清掉发言账号的凭据文件");
        assertFalse(again.isLoggedIn());
        verify(listener, never()).logout();

        assertArrayEquals(listenerCookiesBefore, Files.readAllBytes(listenerCookies), "监听账号的凭据文件被改了");
        assertArrayEquals(listenerKeyBefore, Files.readAllBytes(listenerKey), "监听账号的密钥文件被改了");

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("发言账号：")), "发言账号的日志要认得出是哪个号: " + lines);
        assertTrue(lines.stream().noneMatch(line -> line.contains(SESS) || line.contains(JCT)
                || line.contains("speaker-token-value") || line.contains("speaker-refresh-value")), "日志里出现了凭据值");
    }

    /**
     * 抓的用户故障：取不到二维码时程序每 10 秒重试一次、一直试下去，替没人在场的界面一直向平台要码
     */
    @Test
    @DisplayName("取码失败：不反复重试，停下并在卡上说明")
    void qrCodeFetchFailureStops() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(api.getTvQrCodeLoginInfo()).thenThrow(new IllegalStateException("网络不通"));
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);

        speaker.startLogin();
        drain();

        verify(api).getTvQrCodeLoginInfo();
        assertFalse(speaker.isLoggingIn(), "取码失败之后不该还挂着一轮扫码");
        assertEquals(Optional.of("没能取到登录二维码，请稍后再试"), speaker.notice());
    }

    /**
     * 抓的用户故障：点了「扫码登录」紧接着点「退出登录」，排着的那一轮照样跑起来，去向平台要码
     */
    @Test
    @DisplayName("排上还没开始就退出：那一轮作废，不发请求")
    void logoutDropsQueuedRound() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        worker.execute(() -> {
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);

        speaker.startLogin();
        speaker.logout();
        hold.countDown();
        drain();

        verify(api, never()).init();
        verify(api, never()).getTvQrCodeLoginInfo();
        assertFalse(speaker.isLoggingIn());
    }

    /**
     * 抓的用户故障：点了退出登录，正在等人扫的那一轮却还在，之后有人扫了照样登上
     */
    @Test
    @DisplayName("等人扫码时点退出：那一轮当场停下，两下连点只排一轮")
    void logoutStopsPendingQrCode() throws Exception {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(api.getTvQrCodeLoginInfo()).thenReturn(new BilibiliApiUtil.QrCodeLogin("https://example.invalid/qr", "key"));
        when(api.getTvQrCodeLoginStatus(anyString())).thenReturn(false);
        BilibiliSpeakerAccount speaker = speaker(api, settings(new NovaBilibiliProperties()), store);

        speaker.startLogin();
        // 等它进入轮询、码摆出来
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (speaker.pendingQrCodeContent().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(speaker.pendingQrCodeContent().isPresent(), "点了扫码登录, 码没出来");
        assertEquals("正在等待扫码", speaker.startLogin(), "两下连点只排一轮");

        // 退出登录也要能把正在等的那一轮停下
        speaker.logout();
        drain();
        assertTrue(speaker.pendingQrCodeContent().isEmpty());
        assertFalse(speaker.isLoggingIn());
        verify(api).getTvQrCodeLoginInfo();
        assertNull(speaker.uid());
    }
}
