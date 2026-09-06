package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录页的装配
 * <p>
 * 登录页那几个判定（摆什么、折什么、禁什么）由服务端在发出页面时拼进去，
 * 页面里不留第二份副本。这一格守的是那条拼接：<b>拼不上时页面照样渲染得出来</b>，
 * 只是点什么都不动——而那与「服务器没起来」在使用者眼里长得一样，
 * 谁也不会想到去看少了一段脚本。
 * <p>
 * {@link LoginModelTest} 量的是那段判定<b>算得对不对</b>，这一格量的是它<b>到底在不在页面上</b>。
 * 少了这一格，夹具会一直绿着，量的却是一份没人跑的字节。
 */
@DisplayName("登录页装配")
class ConfigUiLoginPageTest {
    @Test
    @DisplayName("真发出去的那张页面里带着判定脚本，且占位不留残迹")
    void realPageCarriesTheModel() throws IOException {
        String html = ConfigUiLoginPage.html();

        assertTrue(html.contains("export function loginView"),
                "拼好的登录页里应当带着 loginView，缺了它页面上点什么都不动");
        assertTrue(html.contains("export function lockText"),
                "锁定文案那一支同样要在页面上，否则锁定横条永远是空的");
        assertTrue(html.contains("export function passwordReveal"),
                "口令框显示／隐藏那一份也要拼进去，否则登录页眼睛点了不动");
        assertTrue(html.contains("id=\"password-reveal\""),
                "登录页口令框旁边应当有显示／隐藏按钮");
        assertFalse(html.contains(ConfigUiLoginPage.MODEL_MARKER),
                "占位应当已被顶替，留着它说明这一趟根本没拼进去");
        assertFalse(html.contains(ConfigUiLoginPage.REVEAL_MARKER),
                "显示／隐藏那一处占位应当已被顶替");
        // 判定脚本必须落在 module 脚本里：拼进普通 <script> 的话，那几个 export 是语法错误，
        // 整段脚本一行都不会跑，而页面照样显示得出来
        assertTrue(html.contains("<script type=\"module\">"),
                "承载判定的必须是 module 脚本");
    }

    @Test
    @DisplayName("拼好的登录页带着锁定控件表")
    void realPageCarriesControlState() throws IOException {
        String html = ConfigUiLoginPage.html();
        assertTrue(html.contains("export function loginControlState"),
                "锁定时禁哪几颗由 loginControlState 算，缺了它 paint 遍历无从问起");
        assertTrue(html.contains("'password-reveal'"),
                "控件表里须有眼睛那一颗，漏了锁定期仍能揭开口令");
    }

    @Test
    @DisplayName("🔴 页面里没有占位时当场抛，不静默发一张点不动的页出去")
    void missingMarkerThrows() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ConfigUiLoginPage.compose("<html><body>无占位</body></html>", "export function loginView(){}"));

        assertTrue(error.getMessage().contains(ConfigUiLoginPage.MODEL_MARKER),
                "报错要说清缺的是哪一处占位: " + error.getMessage());
    }

    /**
     * 拼出来的那段脚本，语法得过得去
     * <p>
     * 登录页的脚本<b>不经任何构建步骤</b>，也没有别的东西替我们解析它一遍。
     * 拼接又恰恰是最容易拼出语法错的地方：占位所在的位置若落在一段字符串或注释里，
     * 拼进去的整段代码就成了那段字符串的一部分，而<b>页面照样显示得出来</b>——
     * 只是点什么都不动，与「服务器没起来」在使用者眼里长得一样。
     */
    @Test
    @DisplayName("拼好的那段 module 脚本过得了 node --check")
    void composedScriptParses() throws IOException, InterruptedException {
        String html = ConfigUiLoginPage.html();

        int start = html.indexOf("<script type=\"module\">");
        assertTrue(start >= 0, "登录页里应当有一段 module 脚本");
        start += "<script type=\"module\">".length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "那段 module 脚本没有收尾");

        Path scratch = Path.of("target", "login-page-check");
        Files.createDirectories(scratch);
        // 扩展名用 .mjs：node --check 对 .js 在旧一些的版本上按 CommonJS 解析，
        // 那样每一个 export 都会报错，而红的原因与真实缺陷毫无关系
        Path script = scratch.resolve("login-inline.mjs");
        Files.writeString(script, html.substring(start, end), StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("node", "--check", script.toString());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 起不来就红，不是跳过：缺了 node 这一格什么也没量，
            // 而「量不到」不许读成「没问题」
            throw new IOException("起不动 node，登录页那段脚本这一跑一格没量", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node --check 跑了 60 秒还没结束:\n" + output);
        assertEquals(0, process.exitValue(), "拼好的登录页脚本语法不过:\n" + output);
    }

    @Test
    @DisplayName("脚本里的 $ 与反斜杠原样拼进去，不被当成替换里的记号")
    void modelIsInsertedVerbatim() {
        String model = "const a = '$1'; const b = /\\d/;";
        String composed = ConfigUiLoginPage.compose("A" + ConfigUiLoginPage.MODEL_MARKER + "B", model);

        // 这一格奔着一类具体的错去：拿 replaceAll 拼的话，脚本里的 $1 会被当成分组引用、
        // \d 会被当成转义，拼出来的是另一段代码——而它照样是合法的 JavaScript
        assertTrue(composed.equals("A" + model + "B"), "拼出来的应当逐字相同: " + composed);
    }
}
