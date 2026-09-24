package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面主题三态：深色／浅色／跟随系统，选择记在当前浏览器
 * <p>
 * 三类用户会碰到的故障，一类一格：
 * <ul>
 *   <li>选了深色，系统是浅色时页面仍是浅色，或刷新后又变回去；</li>
 *   <li>选回跟随系统后仍锁在深色；</li>
 *   <li>选了深色的人每次打开页面先闪一下浅色（主题脚本不在 {@code <head>} 或是 module）。</li>
 * </ul>
 * 读写那一手由 node 夹具真跑 {@code theme.js}；首帧那一手是静态的——
 * 脚本在不在 {@code <head>}、是不是同步脚本，源码里看得见。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("界面主题三态")
class ThemeSwitchFrontendTest {
    private static final String APPLY_FIXTURE = FrontendFixture.fixture("theme-apply-fixture.mjs");
    private static final String AUTO_FIXTURE = FrontendFixture.fixture("theme-auto-fixture.mjs");

    /** 偏好在浏览器本地的键。与 theme.js 的 THEME_KEY 同一把 */
    private static final String THEME_KEY = "novabot-theme";

    private static String read(String name) throws IOException {
        return Files.readString(FrontendFixture.frontendDir().resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * 首帧那一下：主题脚本必须在 {@code <head>} 里、同步、非 module
     * <p>
     * 入口是 module，延迟执行——在它里面设主题，首帧会先按系统色闪一下再切。
     * 选了深色的人每次打开都看见一次浅色，就是这么来的。
     */
    @Test
    @DisplayName("主题脚本在 head 里且是同步脚本，首帧前就设好 data-theme")
    void themeBootScriptSitsInHeadBeforeFirstFrame() throws IOException {
        String html = read("index.html");

        int headEnd = html.indexOf("</head>");
        assertTrue(headEnd > 0, "index.html 里找不到 </head>");
        String head = html.substring(0, headEnd);

        Matcher scripts = Pattern
                .compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL)
                .matcher(head);
        boolean found = false;
        while (scripts.find()) {
            String attrs = scripts.group(1);
            String body = scripts.group(2);
            if (attrs.contains("src=") || attrs.contains("type=\"module\"")) continue;
            if (!body.contains(THEME_KEY) && !body.contains("data-theme")) continue;
            found = true;
            assertTrue(body.contains("data-theme"),
                    "head 里那段主题脚本没碰 data-theme:\n" + body.strip());
            assertTrue(body.contains("localStorage") || body.contains(THEME_KEY),
                    "head 里那段主题脚本没读本浏览器的偏好:\n" + body.strip());
            // 读不到、读出错一律按跟随系统：不许猜一个主题锁住页面
            Matcher locked = Pattern
                    .compile("setAttribute\\s*\\(\\s*['\"]data-theme['\"]\\s*,\\s*['\"](?:dark|light)['\"]\\s*\\)")
                    .matcher(body);
            while (locked.find()) {
                assertTrue(body.contains("getItem") || body.contains("readTheme"),
                        "head 里写死了 data-theme，没先读偏好:\n" + body.strip());
            }
        }
        assertTrue(found,
                "head 里没有一段同步脚本在首帧前按偏好设 data-theme；"
                        + "选了深色的人每次打开会先闪一下浅色");
    }

    /**
     * 选了要当场生效，并且刷新之后还在
     */
    @Test
    @DisplayName("选了深色或浅色：当场换肤，写进本浏览器，刷新后还在")
    void choosingDarkSticksAcrossReload() throws IOException, InterruptedException {
        FrontendFixture.run(APPLY_FIXTURE, "界面主题·选了就记住");
        assertSettingsOffersThreeChoices();
    }

    /**
     * 选回跟随系统要解锁，不能仍锁在深色
     */
    @Test
    @DisplayName("选回跟随系统：去掉 data-theme，本地那份一起清掉")
    void choosingAutoUnlocksFromDark() throws IOException, InterruptedException {
        FrontendFixture.run(AUTO_FIXTURE, "界面主题·选回跟随系统");
    }

    /**
     * 设置页组目录最上方有一个纯前端的「界面」组，里面是三态切换
     * <p>
     * 只量读写那一手不够：入口没摆出来的话，人根本无从选起——而那三类故障一个都不会出现。
     */
    private void assertSettingsOffersThreeChoices() throws IOException {
        String settings = read("settings.js");
        String model = read("theme.js");

        assertTrue(settings.contains("界面"),
                "设置页组目录最上方没有「界面」组");
        for (String label : new String[]{"跟随系统", "浅色", "深色"}) {
            assertTrue(settings.contains(label),
                    "「界面」组里没有「" + label + "」这一档");
        }
        assertTrue(settings.contains("applyTheme"),
                "设置页没把选择交到 applyTheme，点了不会生效");
        assertTrue(model.contains(THEME_KEY),
                "theme.js 里的键名对不上 head 脚本那一把");
        // 外观偏好不是这台机器的配置，不该进改动条
        assertTrue(!settings.contains("markDirty") || !themeBlock(settings).contains("markDirty"),
                "「界面」组不许进改动条（markDirty）");
    }

    /** 设置页里「界面」那一块的正文，从标题起截到下一个函数声明 */
    private String themeBlock(String settings) {
        int at = settings.indexOf("界面");
        if (at < 0) return "";
        int next = settings.indexOf("\nfunction ", at);
        int nextExport = settings.indexOf("\nexport function ", at);
        int end = settings.length();
        if (next > at) end = Math.min(end, next);
        if (nextExport > at) end = Math.min(end, nextExport);
        return settings.substring(at, end);
    }
}
