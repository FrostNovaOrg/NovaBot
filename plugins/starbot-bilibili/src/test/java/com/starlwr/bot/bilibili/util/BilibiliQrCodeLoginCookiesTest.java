package com.starlwr.bot.bilibili.util;

import com.starlwr.bot.bilibili.model.Cookies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 扫码登录凭据提取测试
 * <p>
 * 这组用例的存在源于一次真实故障：哔哩哔哩把扫码登录的凭据下发方式从「拼在跳转地址的查询串里」
 * 改成了「放在轮询响应的 Set-Cookie 响应头里」，而当时的实现只认前者，
 * 表现为**扫码明明成功、程序却报「未能解析出登录凭据」**。
 * <p>
 * 现在两种形态都要能取到，且两者都必须有用例钉住——只覆盖现行形态的话，
 * 服务端哪天改回去或灰度并存时同样会翻车。
 */
@DisplayName("扫码登录凭据提取")
class BilibiliQrCodeLoginCookiesTest {
    /**
     * 现行形态：轮询响应的 Set-Cookie 头
     * <p>
     * 取自真实响应的字段构成，取值已替换为占位内容。
     */
    private static final List<String> REAL_SET_COOKIE = List.of(
            "SESSDATA=fake%2Csess%2Cdata; Path=/; Domain=bilibili.com; Expires=...; HttpOnly; Secure; SameSite=None",
            "bili_jct=fakebilijct0123456789abcdef; Path=/; Domain=bilibili.com; Expires=...; Secure; SameSite=None",
            "DedeUserID=1234567; Path=/; Domain=bilibili.com; Expires=...",
            "DedeUserID__ckMd5=fakeckmd5; Path=/; Domain=bilibili.com; Expires=...",
            "sid=fakesid; Path=/; Domain=bilibili.com; Expires=...");

    @Test
    @DisplayName("能从轮询响应的 Set-Cookie 中取出凭据")
    void shouldExtractFromSetCookieHeaders() {
        Cookies cookies = BilibiliCookieRefreshUtil.applySetCookies(new Cookies(), REAL_SET_COOKIE);

        assertEquals("fake%2Csess%2Cdata", cookies.getSessData(), "SESSDATA 应原样取出, 不做解码");
        assertEquals("fakebilijct0123456789abcdef", cookies.getBiliJct());
    }

    @Test
    @DisplayName("响应头中不含凭据时应得到空值, 以便调用方退回解析跳转地址")
    void shouldReturnBlankWhenHeadersCarryNoCredential() {
        // 现行的 crossDomain 地址只有 ticket / gourl / first_domain，其中没有凭据；
        // 若此时响应头也没有，才是真的取不到
        Cookies cookies = BilibiliCookieRefreshUtil.applySetCookies(new Cookies(),
                List.of("buvid3=fakebuvid; Path=/", "b_nut=1700000000; Path=/"));

        assertNull(cookies.getSessData());
        assertNull(cookies.getBiliJct());
        assertEquals("fakebuvid", cookies.getBuvid3(), "顺带下发的设备标识仍应取到");
    }

    @Test
    @DisplayName("SESSDATA 中的百分号编码不应被破坏")
    void shouldKeepPercentEncodingIntact() {
        // SESSDATA 里含 %2C（逗号）等编码，请求时要原样回填，解码后服务端不认
        Cookies cookies = BilibiliCookieRefreshUtil.applySetCookies(new Cookies(),
                List.of("SESSDATA=abc%2Cdef%2Aghi; Path=/; HttpOnly"));

        assertEquals("abc%2Cdef%2Aghi", cookies.getSessData());
    }

    @Test
    @DisplayName("Set-Cookie 中的属性不应被当成凭据值")
    void shouldStopAtFirstSemicolon() {
        Cookies cookies = BilibiliCookieRefreshUtil.applySetCookies(new Cookies(),
                List.of("bili_jct=value; Path=/; Domain=bilibili.com; Secure"));

        assertEquals("value", cookies.getBiliJct(), "只应取到第一段, 不含 Path 等属性");
    }
}
