package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配色选择记在本浏览器：选了当场生效，刷新、重新登录、另开标签都还是它；没选过的一律粉蓝
 * <p>
 * 读写与各页首帧前那段脚本由 node 夹具真跑（{@code palette-fixture.mjs}）；
 * 设置页上有没有那三个选项、点了交没交给 theme.js，在这里看源码。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("配色记在本浏览器")
class PaletteSwitchFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("palette-fixture.mjs");

    @Test
    @DisplayName("选了暖阳：当场换，刷新、重新登录后还是暖阳；没选过是粉蓝")
    void chosenPaletteSticksAndDefaultIsPinkBlue() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "配色·选了就记住");
    }

    /**
     * 设置页「界面」组里有「配色」：经典、粉蓝、暖阳三个选项，点了交给 applyPalette，
     * 别的标签换了配色由 subscribePalette 把钮改对
     * <p>
     * 只量读写那一手不够：入口没摆出来，人根本无从选起。
     */
    @Test
    @DisplayName("设置页「界面」组里摆着经典、粉蓝、暖阳三个配色")
    void settingsOffersThreePalettes() throws IOException {
        String settings = Files.readString(FrontendFixture.frontendDir().resolve("settings.js"), StandardCharsets.UTF_8);
        int at = settings.indexOf("function buildThemeGroup(");
        assertTrue(at >= 0, "settings.js 里找不到「界面」组");
        String group = settings.substring(at, settings.indexOf("\n}\n", at));
        for (String label : new String[]{"'配色'", "'经典'", "'粉蓝'", "'暖阳'"}) {
            assertTrue(group.contains(label), "「界面」组里没有 " + label);
        }
        assertTrue(Pattern.compile("applyPalette\\(").matcher(group).find(),
                "「界面」组的配色钮没把选择交给 applyPalette，点了不会生效");
        assertTrue(Pattern.compile("\\bsubscribePalette\\(\\s*[A-Za-z_$][\\w$]*\\s*\\)").matcher(settings).find(),
                "设置页没订阅配色变化，另一个标签换了配色，这里的钮不跟着亮");
        assertTrue(!group.contains("markDirty"), "「界面」组不许进改动条（markDirty）");
    }
}
