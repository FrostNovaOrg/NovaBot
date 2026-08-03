package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
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

        HealthStatus status = probe(account, new StarBotBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertEquals("等待扫码登录", status.summary());
        assertFalse(status.advice().isBlank(), "异常时必须给出下一步能做什么");
    }

    @Test
    @DisplayName("已登录且可自动续期时应判定为正常且不额外提示")
    void shouldReportPlainOkWhenRefreshable() {
        BilibiliAccountService account = loggedIn();
        when(account.isRefreshable()).thenReturn(true);

        HealthStatus status = probe(account, new StarBotBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertEquals("正常（uid 200000001）", status.summary());
        assertTrue(status.advice().isBlank());
    }

    @Test
    @DisplayName("已登录但拿不到刷新口令时应说明无法自动续期")
    void shouldExplainWhenNotRefreshable() {
        BilibiliAccountService account = loggedIn();
        // 实测服务端会把扫码登录的 refresh_token 返回为空串，此时续期会一直静默跳过
        when(account.isRefreshable()).thenReturn(false);

        HealthStatus status = probe(account, new StarBotBilibiliProperties()).check();

        assertEquals(HealthStatus.Level.OK, status.level(), "不影响当前推送, 不应报成异常而稀释告警");
        assertTrue(status.summary().contains("无法自动续期"), "实际为: " + status.summary());
        assertTrue(status.advice().contains("重新扫码"), "应说明后果与应对方式");
    }

    @Test
    @DisplayName("关闭自动续期后不应再提示刷新口令的事")
    void shouldStaySilentWhenAutoRefreshDisabled() {
        BilibiliAccountService account = loggedIn();
        when(account.isRefreshable()).thenReturn(false);

        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getAccount().setAutoRefreshCookie(false);

        HealthStatus status = probe(account, properties).check();

        assertEquals("正常（uid 200000001）", status.summary(), "使用者主动关掉的功能不该反复提醒");
    }

    /**
     * 构造一个已登录的账号服务
     * @return 账号服务
     */
    private BilibiliAccountService loggedIn() {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(200000001L);
        return account;
    }

    /**
     * 构造被测探针
     * @param account 账号服务
     * @param properties 配置
     * @return 探针
     */
    private BilibiliLoginHealthProbe probe(BilibiliAccountService account, StarBotBilibiliProperties properties) {
        return new BilibiliLoginHealthProbe(account, properties);
    }
}
