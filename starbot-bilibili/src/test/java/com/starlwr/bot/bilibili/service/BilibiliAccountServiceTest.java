package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 哔哩哔哩账号服务测试
 * <p>
 * 重点覆盖停机时的行为。扫码登录是一个可能持续数分钟的轮询循环，若不感知停机就会一直占着
 * 调度线程：Spring 停止生命周期 Bean 时会等待其自行结束，等满 30 秒超时后才中断，
 * 表现为 SIGTERM 后进程要 31 秒才退出，且首次部署尚未扫码时必然命中。
 */
@DisplayName("哔哩哔哩账号服务")
class BilibiliAccountServiceTest {
    /**
     * 判定「立即结束」的时间上限
     * <p>
     * 取值需明显小于轮询间隔（3 秒），否则「靠停机信号提前返回」与「恰好等完一轮」无法区分。
     */
    private static final Duration ABORT_LIMIT = Duration.ofSeconds(2);

    @Test
    @DisplayName("收到停机信号后应立即中止扫码登录")
    void shouldAbortQrCodeLoginOnShutdown() throws Exception {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        when(store.load()).thenReturn(Optional.empty());
        when(api.getTvQrCodeLoginInfo()).thenReturn(new BilibiliApiUtil.QrCodeLogin("https://example.invalid/qr", "test-key"));
        // 始终未扫码，使登录停在轮询循环中
        when(api.getTvQrCodeLoginStatus(anyString())).thenReturn(false);

        BilibiliAccountService service = newService(api, store);

        CompletableFuture<Boolean> login = CompletableFuture.supplyAsync(service::login);

        // 等待登录流程真正进入轮询循环，避免停机信号早于循环开始导致测试失去意义
        waitUntilPolling(service);

        Instant start = Instant.now();
        service.onContextClosed();

        Boolean result = login.get(ABORT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        Duration elapsed = Duration.between(start, Instant.now());

        assertFalse(result, "因停机中止的登录应返回 false");
        assertTrue(service.isStopping(), "收到停机信号后应处于停机状态");
        assertFalse(service.isLoggedIn(), "未扫码不应被判定为已登录");
        assertTrue(elapsed.compareTo(ABORT_LIMIT) < 0,
                "收到停机信号后应立即返回, 实际耗时 " + elapsed.toMillis() + " 毫秒");
    }

    @Test
    @DisplayName("停机后不应再发起新的二维码请求")
    void shouldNotRequestNewQrCodeAfterShutdown() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        BilibiliAccountService service = newService(api, store);
        service.onContextClosed();

        assertFalse(service.loginByQrCode(), "停机状态下扫码登录应直接返回 false");
        verify(api, never()).getTvQrCodeLoginInfo();
    }

    @Test
    @DisplayName("已保存的凭据有效时应直接登录, 不进入扫码流程")
    void shouldLoginWithSavedCredential() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);

        assertTrue(service.login(), "凭据有效时应登录成功");
        assertTrue(service.isLoggedIn());
        assertEquals(200000001L, service.getLoginUid());
        verify(api, never()).getTvQrCodeLoginInfo();
    }

    @Test
    @DisplayName("已保存的凭据失效时应清除并转入扫码流程")
    void shouldClearInvalidCredential() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        // uid 为空代表凭据已失效
        when(api.getLoginUid()).thenReturn(null);

        BilibiliAccountService service = newService(api, store);
        // 先置为停机，使其在清除凭据后立即退出扫码循环，避免测试阻塞
        service.onContextClosed();

        assertFalse(service.login());
        verify(store).clear();
    }

    @Test
    @DisplayName("已有扫码流程在进行时不应再申请新的二维码")
    void shouldNotStartConcurrentQrCodeLogin() throws Exception {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        when(store.load()).thenReturn(Optional.empty());
        when(api.getTvQrCodeLoginInfo()).thenReturn(new BilibiliApiUtil.QrCodeLogin("https://example.invalid/qr", "test-key"));
        when(api.getTvQrCodeLoginStatus(anyString())).thenReturn(false);

        BilibiliAccountService service = newService(api, store);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(service::login);
        waitUntilPolling(service);

        // 退出登录会再次发起扫码，若不加约束，两个流程会各自申请二维码并互相覆盖待扫码内容，
        // 界面上便会出现扫了却不生效的二维码
        assertFalse(service.loginByQrCode(), "已有流程在进行时应直接返回");
        verify(api, times(1)).getTvQrCodeLoginInfo();

        service.onContextClosed();
        first.get(ABORT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("复检遇到「账号未登录」应判定为失效")
    void shouldMarkLoggedOutOnNotLoggedInCode() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.fetchLoginUid()).thenThrow(new ResponseCodeException(BilibiliApiUtil.CODE_NOT_LOGGED_IN, "账号未登录"));

        assertFalse(service.verify(), "凭据失效时复检应返回 false");
        assertFalse(service.isLoggedIn(), "登录态应被置回未登录");
        assertNull(service.getLoginUid(), "失效后不应继续保留 uid");
        assertNotNull(service.getLastVerifiedAt(), "已得到服务端明确答复, 应记为一次有效复检");
    }

    @Test
    @DisplayName("复检遇到网络故障应维持原状态, 不得误判为掉登录")
    void shouldKeepStateOnNetworkFailure() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.fetchLoginUid()).thenThrow(new NetworkException("连接超时"));

        assertTrue(service.verify(), "网络故障时应维持原登录态");
        assertTrue(service.isLoggedIn(), "一次网络抖动不应被判定为掉登录");
        assertNull(service.getLastVerifiedAt(), "未得到服务端答复, 不应记为一次有效复检");
    }

    @Test
    @DisplayName("复检遇到其他业务错误代码应维持原状态")
    void shouldKeepStateOnUnexpectedCode() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.fetchLoginUid()).thenThrow(new ResponseCodeException(-352, "风控校验失败"));

        assertTrue(service.verify(), "未预期的错误代码不应直接判定为掉登录");
        assertTrue(service.isLoggedIn());
    }

    @Test
    @DisplayName("凭据恢复后复检应重新置为已登录")
    void shouldRecoverWhenCredentialBecomesValidAgain() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.fetchLoginUid())
                .thenThrow(new ResponseCodeException(BilibiliApiUtil.CODE_NOT_LOGGED_IN, "账号未登录"))
                .thenReturn(200000001L);

        assertFalse(service.verify());
        assertTrue(service.verify(), "凭据恢复后应重新判定为已登录");
        assertTrue(service.isLoggedIn());
        assertEquals(200000001L, service.getLoginUid());
    }

    @Test
    @DisplayName("停机后不应再发起复检请求")
    void shouldNotVerifyAfterShutdown() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);
        service.onContextClosed();

        service.verify();

        verify(api, never()).fetchLoginUid();
    }

    // ============ Cookie 续期 ============
    // 续期一旦确认便不可回退，因此这组用例的重点全在「失败时是否维持原状」上：
    // 只要旧凭据没被作废，任何一步出错都只是本轮续期没做成，账号不会因此掉登录。

    @Test
    @DisplayName("服务端未提示需要续期时不应续期")
    void shouldNotRefreshWhenServerSaysNotNeeded() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.getCookies()).thenReturn(refreshableCookies());
        when(api.checkCookieRefresh()).thenReturn(new BilibiliApiUtil.CookieRefreshHint(false, 0L));

        assertFalse(service.refreshCookiesIfNeeded());
        verify(api, never()).getRefreshCsrf(anyString());
        verify(api, never()).confirmCookieRefresh(anyString());
    }

    @Test
    @DisplayName("凭据中缺少持久化刷新口令时应跳过续期")
    void shouldSkipRefreshWithoutRefreshToken() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        // 旧版本保存下来的凭据文件里没有该字段
        when(api.getCookies()).thenReturn(new Cookies("sess", "jct", "buvid"));

        assertFalse(service.refreshCookiesIfNeeded());
        verify(api, never()).checkCookieRefresh();
    }

    // ============ TV 端登录与 oauth2 续期 ============

    @Test
    @DisplayName("TV 端凭据未临近到期时不应续期")
    void shouldNotRefreshAppTokenLongBeforeExpiry() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);

        when(api.getCookies()).thenReturn(appCookies(Duration.ofDays(180)));

        assertFalse(service.refreshCookiesIfNeeded());
        verify(api, never()).refreshAppToken();
        // 走的是 oauth2 分支，不应触碰 Web 端续期链路
        verify(api, never()).checkCookieRefresh();
    }

    @Test
    @DisplayName("TV 端凭据临近到期时应经 oauth2 续期并保存新凭据")
    void shouldRefreshAppTokenNearExpiry() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");

        Cookies refreshed = appCookies(Duration.ofDays(180));
        when(api.getCookies()).thenReturn(appCookies(Duration.ofDays(3)));
        when(api.refreshAppToken()).thenReturn(refreshed);
        when(api.fetchLoginUid()).thenReturn(200000001L);

        assertTrue(service.refreshCookiesIfNeeded());
        verify(api).refreshAppToken();
        verify(store).save(refreshed);
        // oauth2 一次性换回全套凭据，没有「作废旧口令」这一步
        verify(api, never()).confirmCookieRefresh(anyString());
    }

    @Test
    @DisplayName("oauth2 续期失败时应保持原凭据不变")
    void shouldKeepOldCookiesWhenAppRefreshFails() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");

        when(api.getCookies()).thenReturn(appCookies(Duration.ofDays(3)));
        when(api.refreshAppToken()).thenThrow(new RuntimeException("接口不可用"));

        // login() 自身也会调 setCookies，先清掉调用记录，否则下面的断言会误判
        clearInvocations(api);

        assertFalse(service.refreshCookiesIfNeeded());
        // 续期请求失败在前，凭据自然维持原状
        verify(api, never()).setCookies(any());
        verify(store, never()).save(any());
    }

    @Test
    @DisplayName("oauth2 续期后的新凭据不可用时应回退至原凭据")
    void shouldRollBackWhenRefreshedAppCookiesInvalid() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");

        Cookies current = appCookies(Duration.ofDays(3));
        when(api.getCookies()).thenReturn(current);
        when(api.refreshAppToken()).thenReturn(appCookies(Duration.ofDays(180)));
        when(api.fetchLoginUid()).thenThrow(new RuntimeException("凭据无效"));

        assertFalse(service.refreshCookiesIfNeeded());
        verify(api).setCookies(current);
        verify(store, never()).save(any());
    }

    @Test
    @DisplayName("关闭自动续期后不应发起任何续期请求")
    void shouldSkipRefreshWhenDisabled() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getAccount().setAutoRefreshCookie(false);

        BilibiliAccountService service = new BilibiliAccountService(api, store, properties);

        assertFalse(service.refreshCookiesIfNeeded());
        verify(api, never()).checkCookieRefresh();
    }

    @Test
    @DisplayName("续期成功后应先保存新凭据再作废旧口令")
    void shouldPersistBeforeConfirming() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(refreshableCookies()));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login());
        // login 自身也会调用 setCookies，先清掉登录阶段的交互，避免污染下面的次数与顺序断言
        clearInvocations(api, store);

        Cookies refreshed = new Cookies("new-sess", "new-jct", "buvid", "new-token");
        when(api.getCookies()).thenReturn(refreshableCookies());
        when(api.checkCookieRefresh()).thenReturn(new BilibiliApiUtil.CookieRefreshHint(true, 1684466082562L));
        when(api.getRefreshCsrf(anyString())).thenReturn("csrf-token");
        when(api.refreshCookies("csrf-token", "old-token")).thenReturn(refreshed);
        when(api.fetchLoginUid()).thenReturn(200000001L);

        assertTrue(service.refreshCookiesIfNeeded());

        // 顺序反过来的话，一旦此刻进程退出，新凭据没存下、旧凭据又已失效，就只能重新扫码
        InOrder order = inOrder(api, store);
        order.verify(api).setCookies(refreshed);
        order.verify(store).save(refreshed);
        order.verify(api).confirmCookieRefresh("old-token");
    }

    @Test
    @DisplayName("新凭据验证失败时应回退且不作废旧口令")
    void shouldRollbackWhenRefreshedCredentialIsUnusable() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        Cookies current = refreshableCookies();
        when(store.load()).thenReturn(Optional.of(current));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login());
        clearInvocations(api, store);

        Cookies refreshed = new Cookies("new-sess", "new-jct", "buvid", "new-token");
        when(api.getCookies()).thenReturn(current);
        when(api.checkCookieRefresh()).thenReturn(new BilibiliApiUtil.CookieRefreshHint(true, 1684466082562L));
        when(api.getRefreshCsrf(anyString())).thenReturn("csrf-token");
        when(api.refreshCookies(anyString(), anyString())).thenReturn(refreshed);
        when(api.fetchLoginUid()).thenThrow(new NetworkException("新凭据不可用"));

        assertFalse(service.refreshCookiesIfNeeded());

        verify(api).setCookies(current);
        verify(store, never()).save(refreshed);
        // 旧口令没被作废，账号仍持有一份可用凭据
        verify(api, never()).confirmCookieRefresh(anyString());
    }

    @Test
    @DisplayName("续期链路中途失败时不应改动当前凭据")
    void shouldKeepCredentialWhenRefreshChainFails() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliAccountService service = loggedInService(api);
        clearInvocations(api);

        when(api.getCookies()).thenReturn(refreshableCookies());
        when(api.checkCookieRefresh()).thenReturn(new BilibiliApiUtil.CookieRefreshHint(true, 1684466082562L));
        when(api.getRefreshCsrf(anyString())).thenThrow(new NetworkException("correspond 页面返回 404"));

        assertFalse(service.refreshCookiesIfNeeded());

        verify(api, never()).setCookies(any());
        verify(api, never()).confirmCookieRefresh(anyString());
    }

    /**
     * 构造一份具备自动续期条件的凭据
     * @return 凭据
     */
    private Cookies refreshableCookies() {
        return new Cookies("sess", "jct", "buvid", "old-token");
    }

    /**
     * 构造账号服务，使用默认配置
     * @param api 接口工具
     * @param store 凭据存储
     * @return 账号服务
     */
    private BilibiliAccountService newService(BilibiliApiUtil api, BilibiliCredentialStore store) {
        return new BilibiliAccountService(api, store, new StarBotBilibiliProperties());
    }

    /**
     * 构造匿名模式的账号服务
     * @param api 接口工具
     * @param store 凭据存储
     * @return 账号服务
     */
    private BilibiliAccountService anonymousService(BilibiliApiUtil api, BilibiliCredentialStore store) {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getAccount().setAnonymous(true);
        return new BilibiliAccountService(api, store, properties);
    }

    @Test
    @DisplayName("⚠️ 匿名模式下连已保存的凭据都不该读")
    void anonymousShouldNotTouchSavedCredentials() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        // 机器上确实存着一份可用凭据，匿名模式也不许用它
        when(store.load()).thenReturn(Optional.of(refreshableCookies()));

        BilibiliAccountService service = anonymousService(api, store);

        assertFalse(service.login(), "匿名模式的 login 应返回 false");
        assertTrue(service.isAnonymous(), "应处于匿名模式");
        assertFalse(service.isLoggedIn(), "匿名模式不应被判定为已登录");

        // 读了凭据就不叫匿名了: 拿这个开关做的匿名对照实验测出来的就不是匿名
        verify(store, never()).load();
        verify(api, never()).setCookies(any());
        verify(api, never()).getTvQrCodeLoginInfo();
        verify(api, never()).getQrCodeLoginInfo();

        // 接口凭据（buvid、web 签名）仍要初始化, 否则匿名连接自己也取不到令牌
        verify(api).init();
    }

    @Test
    @DisplayName("匿名模式下界面发起的扫码请求应被挡住")
    void anonymousShouldRefuseQrCodeLogin() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);

        BilibiliAccountService service = anonymousService(api, store);

        // 界面上的「退出登录」会顺手发起新一轮扫码，挡不住的话点一下就凭空冒出个二维码
        assertFalse(service.loginByQrCode(), "匿名模式下扫码登录应直接返回 false");
        verify(api, never()).getTvQrCodeLoginInfo();
        assertNull(service.getPendingQrCodeContent(), "匿名模式下不该有待扫描的二维码");
    }

    /**
     * 构造一份 TV 端登录取得的凭据
     * @param remaining 距令牌到期还剩多久
     * @return 凭据
     */
    private Cookies appCookies(Duration remaining) {
        Cookies cookies = new Cookies("sess", "jct", "buvid", "refresh-token");
        cookies.setAccessToken("access-token");
        cookies.setAccessTokenExpiresAt(System.currentTimeMillis() + remaining.toMillis());
        return cookies;
    }

    /**
     * 构造一个已处于登录状态的账号服务
     * @param api 接口工具
     * @return 账号服务
     */
    private BilibiliAccountService loggedInService(BilibiliApiUtil api) {
        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        when(api.getLoginUid()).thenReturn(200000001L);

        BilibiliAccountService service = newService(api, store);
        assertTrue(service.login(), "前置条件: 应先处于已登录状态");
        return service;
    }

    /**
     * 等待登录流程进入扫码轮询状态
     * @param service 账号服务
     */
    private void waitUntilPolling(BilibiliAccountService service) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (service.getPendingQrCodeContent() == null) {
            if (Instant.now().isAfter(deadline)) {
                fail("登录流程未能在预期时间内进入扫码轮询状态");
            }
            Thread.sleep(20);
        }
    }
}
