package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接卡上的昵称：走既有平台接口一次，失败只显 uid、不重试
 */
@DisplayName("哔哩哔哩登录账号昵称")
class BilibiliAccountLoginProviderTest {
    @Test
    @DisplayName("接口有值时给出昵称，且只问一次")
    void nameFromApiShownOnce() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(1L)).thenReturn(new Up(1L, "柚子", 2L, "x"));
        BilibiliAccountLoginProvider provider = provider(loggedIn(1L), api);

        assertEquals("柚子", provider.accountName().orElse(null));
        assertEquals("柚子", provider.accountName().orElse(null));
        verify(api, times(1)).getUpInfoByUid(1L);
    }

    @Test
    @DisplayName("接口失败时为空，且不重试")
    void failureStaysEmptyWithoutRetry() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(1L)).thenThrow(new RuntimeException("down"));
        BilibiliAccountLoginProvider provider = provider(loggedIn(1L), api);

        assertTrue(provider.accountName().isEmpty());
        assertTrue(provider.accountName().isEmpty());
        verify(api, times(1)).getUpInfoByUid(1L);
    }

    private BilibiliAccountService loggedIn(long uid) {
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(uid);
        return account;
    }

    private BilibiliAccountLoginProvider provider(BilibiliAccountService account, BilibiliApiUtil api) {
        return new BilibiliAccountLoginProvider(account, mock(TaskScheduler.class), api);
    }
}
