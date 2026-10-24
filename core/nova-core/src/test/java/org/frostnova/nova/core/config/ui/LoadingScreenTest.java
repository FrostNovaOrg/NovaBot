package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LoadingScreenTest {
    // 不执行脚本时也必须有标志和状态文字，避免查询登录态期间只剩一片底色。
    @Test
    void loginFirstFrameHasVisibleLoadingContent() throws Exception {
        String html = ConfigUiLoginPage.html("");
        var loading = java.util.regex.Pattern.compile("<div id=\"login-startup\"[^>]*>[\\s\\S]*?</div>").matcher(html);
        assertTrue(loading.find(), "登录页首帧缺少加载内容");
        assertFalse(loading.group().substring(0, loading.group().indexOf('>')).contains("hidden"));
        assertTrue(loading.group().contains("#novabot-mark"));
        assertTrue(loading.group().contains("role=\"status\">正在检查登录状态…"));
        assertTrue(loading.group().contains("nova-loading-spinner"));
    }

    @Test
    void consoleFirstFrameIncludesOriginalPaletteBeforeExternalStyles() throws Exception {
        String html = ConfigUiPrefixPages.index("/novabot", "/forged");
        String palette = Files.readString(FrontendFixture.frontendDir().resolve("product-ui.css"));
        assertTrue(html.contains(palette), "外链样式未到时也应有完整色表");
        assertTrue(html.indexOf(palette) < html.indexOf("rel=\"stylesheet\""));
        assertTrue(html.contains("html { background: var(--ground)"));
        assertTrue(html.contains("<meta name=\"color-scheme\" content=\"light dark\">"));
        assertTrue(html.contains("id=\"startup\" class=\"nova-loading\""));
    }

    @Test
    void allLoadingScreensUseTheSameBackgroundInEveryTheme() throws Exception {
        String palette = Files.readString(FrontendFixture.frontendDir().resolve("product-ui.css"));
        String loadingCss = Files.readString(FrontendFixture.frontendDir().resolve("loading-ui.css"));
        var reference = new PaletteCss(palette);
        for (String html : new String[]{ConfigUiLoginPage.html(""), ConfigUiPrefixPages.index("", ""),
                ConfigUiLoadingPage.transition("/config")}) {
            assertTrue(html.contains(loadingCss), "加载画面必须使用同一份底色与减少动态效果样式");
            var style = java.util.regex.Pattern.compile("<style>([\\s\\S]*?)</style>").matcher(html);
            assertTrue(style.find());
            var actual = new PaletteCss(style.group(1).replace(loadingCss, ""));
            for (String name : PaletteCss.PALETTES) {
                for (var state : PaletteCss.modes(name).values()) {
                    assertEquals(reference.resolved(state).get("--ground"), actual.resolved(state).get("--ground"));
                }
            }
        }
    }

    @Test
    void loadingAndNavigationBehaveInBrowserScripts() throws Exception {
        Path login = Path.of("target", "loading-login.html").toAbsolutePath();
        Files.writeString(login, ConfigUiLoginPage.html("/novabot"));
        FrontendFixture.run(Path.of("src/test/resources/frontend/loading-screen-fixture.mjs").toAbsolutePath().toString(),
                "加载与跳转", login.toString());
    }
}
