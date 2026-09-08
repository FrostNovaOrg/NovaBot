package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.core.health.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 哔哩哔哩登录态健康探针测试
 * <p>
 * 除登录态本身外，还覆盖「已登录但拿不到刷新口令」这一实测存在的情况：
 * 此时自动续期会一直静默跳过，凭据到期后表现为「某天突然掉登录」，必须让使用者看得见。
 */
@DisplayName("哔哩哔哩登录健康探针")
class BilibiliLoginHealthProbeTest {
    @Test
    @DisplayName("未登录时应判定为不可用并给出扫码指引")
    void shouldReportDownWhenLoggedOut() {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(false);
        when(account.getPendingQrCodeContent()).thenReturn("https://example.invalid/qr");

        HealthStatus status = probe(account, new NovaBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertEquals("等待扫码登录", status.summary());
        assertEquals(BilibiliLoginHealthProbe.ADVICE_WITH_QR, status.advice());
        assertFalse(status.advice().contains("启动日志"), "卡上有二维码时不应再指去启动日志");
    }

    @Test
    @DisplayName("未登录且没有二维码时应仍指引去启动日志")
    void shouldPointToStartupLogWhenQrMissing() {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(false);
        when(account.getPendingQrCodeContent()).thenReturn(null);

        HealthStatus status = probe(account, new NovaBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertEquals("未登录", status.summary());
        assertEquals(BilibiliLoginHealthProbe.ADVICE_WITHOUT_QR, status.advice());
        assertTrue(status.advice().contains("启动日志"), "卡上没有二维码时启动日志是退路");
    }

    @Test
    @DisplayName("已登录且可自动续期时应判定为正常且不额外提示")
    void shouldReportPlainOkWhenRefreshable() {
        BilibiliAccountService account = loggedIn();
        when(account.isRefreshable()).thenReturn(true);

        HealthStatus status = probe(account, new NovaBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertEquals("正常（uid 19805387116684）", status.summary());
        assertTrue(status.advice().isBlank());
    }

    @Test
    @DisplayName("已登录但拿不到刷新口令时应说明无法自动续期")
    void shouldExplainWhenNotRefreshable() {
        BilibiliAccountService account = loggedIn();
        // 实测服务端会把扫码登录的 refresh_token 返回为空串，此时续期会一直静默跳过
        when(account.isRefreshable()).thenReturn(false);

        HealthStatus status = probe(account, new NovaBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.OK, status.level(), "不影响当前推送, 不应报成异常而稀释告警");
        assertTrue(status.summary().contains("无法自动续期"), "实际为: " + status.summary());
        assertTrue(status.advice().contains("重新扫码"), "应说明后果与应对方式");
    }

    @Test
    @DisplayName("关闭自动续期后不应再提示刷新口令的事")
    void shouldStaySilentWhenAutoRefreshDisabled() {
        BilibiliAccountService account = loggedIn();
        when(account.isRefreshable()).thenReturn(false);

        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setAutoRefreshCookie(false);

        HealthStatus status = probe(account, properties).check();

        assertEquals("正常（uid 19805387116684）", status.summary(), "使用者主动关掉的功能不该反复提醒");
    }

    @Test
    @DisplayName("⚠️ 匿名模式必须在状态页如实标注，且不许记成正常")
    void shouldLabelAnonymousHonestly() {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isAnonymous()).thenReturn(true);
        when(account.isLoggedIn()).thenReturn(false);

        HealthStatus status = probe(account, new NovaBilibiliProperties()).check();

        // 不是故障，是配置选出来的，所以不该是 DOWN；但它确实拿不全数据，
        // 记 OK 就等于在界面上说「一切正常」，而那不是真的
        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertEquals("匿名模式（未登录）", status.summary());

        // 与启动日志、用户手册共用同一份文本。分开写三遍迟早会各说各话
        assertEquals(BilibiliAccountService.ANONYMOUS_NOTICE, status.advice());

        // 不许把匿名模式描述成「功能一致的免登录版」: 这四件事必须说出来。
        // 措辞在 2026-08-10 改过一次：原先是「部分房间的弹幕可能被服务端限制下发（实测已证实）」，
        // 「部分房间」「可能」太软，读起来像小概率事件，而实测是个人房普遍只有一成。
        // 断言跟着改成「必须给出那个数字」——比断言某个具体词更贴近这条测试真正要守的东西
        assertTrue(status.advice().contains("约一成"), "要说清个人房只能拿到约一成，不能只说「可能收不全」");
        assertTrue(status.advice().contains("实测"), "要说清这是实测结论而非推测");
        assertTrue(status.advice().matches("(?s).*\\d+(\\.\\d+)?%.*"), "要带上实测区间，让人能自己判断值不值得登录");
        assertTrue(status.advice().contains("uid"), "要说清发送者 uid 被抹成 0");
        assertTrue(status.advice().contains("配置登录"), "要给出拿到完整数据的办法");
    }

    @Test
    @DisplayName("⚠️ 本探针必须自报量的是登录态：时间线据此把登录失效单列一类")
    void shouldDeclareItselfAsLoginState() {
        // 这一项默认是 false，也就是说漏了这行覆写不会有任何报错——
        // 表现只是「登录掉了的那一次在日志页上混进了普通状态变化」，
        // 而那种事一年不出一次，没人会因为它没单独出现而起疑
        assertTrue(probe(loggedIn(), new NovaBilibiliProperties()).loginState());
    }

    /**
     * 构造一个已登录的账号服务
     * @return 账号服务
     */
    private BilibiliAccountService loggedIn() {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(19805387116684L);
        return account;
    }

    /**
     * 构造被测探针
     * @param account 账号服务
     * @param properties 配置
     * @return 探针
     */
    private BilibiliLoginHealthProbe probe(BilibiliAccountService account, NovaBilibiliProperties properties) {
        return new BilibiliLoginHealthProbe(account, properties);
    }
}
