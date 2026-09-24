package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录页跟随所选主题，并显示产品图标
 * <p>
 * 登录页由安全过滤器在 {@code /config/assets} 开门之前直接吐出来，因此它自成一张孤页：
 * 样式与图标都得自己带。控制台那边的 {@code theme.js} 与 {@code app.css} 这张页面引不到，
 * 主题那一手只能在 {@code <head>} 里用一小段同步脚本、按同一把偏好键设 {@code data-theme}，
 * token 也照三态再写一份。
 * <p>
 * 三类使用者会碰到的故障，一类一格：
 * <ul>
 *   <li>在控制台选了深色，退出到登录页仍是浅色，或先闪一下浅色；</li>
 *   <li>登录页左上还是字母「N」，标签页没有图标；</li>
 *   <li>图标去引登录前取不到的 {@code /config/assets}，未登录时裂图。</li>
 * </ul>
 */
@DisplayName("登录页跟随主题并显示产品图标")
class LoginThemeBrandTest {

    /** 偏好键。与 theme.js 的 THEME_KEY 同一把，一字不差 */
    private static final String THEME_KEY = "novabot-theme";

    private static String read(String name) throws IOException {
        return Files.readString(FrontendFixture.frontendDir().resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * 选了深色的人退出到登录页，要跟着是深色，而且不能先闪一下浅色
     * <p>
     * 首帧那一下与控制台同一道理：脚本不在 {@code <head>}、或是 module，就会先按系统色画一帧再切。
     */
    @Test
    @DisplayName("登录页按同一把键读偏好，手动选了深色就不再跟系统色")
    void loginPageFollowsChosenTheme() throws IOException {
        String html = read("login.html");

        int headEnd = html.indexOf("</head>");
        assertTrue(headEnd > 0, "login.html 里找不到 </head>");
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
                    "登录页 head 里那段主题脚本没碰 data-theme:\n" + body.strip());
            assertTrue(body.contains("localStorage"),
                    "登录页 head 里那段主题脚本没读本浏览器的偏好:\n" + body.strip());
            assertTrue(body.contains("'" + THEME_KEY + "'") || body.contains("\"" + THEME_KEY + "\""),
                    "登录页读的偏好键要与控制台主题那一把一字不差:\n" + body.strip());
        }
        assertTrue(found,
                "登录页 head 里没有一段同步脚本在首帧前按偏好设 data-theme；"
                        + "选了深色的人到登录页仍是浅色");

        // 三态缺一不可：手动深色、手动浅色时别被系统暗色盖掉、原生控件跟色
        assertTrue(html.contains(":root[data-theme=\"dark\"]") || html.contains(":root[data-theme='dark']"),
                "登录页没有手动深色的 token 块，选了深色仍按系统色");
        assertTrue(html.contains(":root:not([data-theme=\"light\"])")
                        || html.contains(":root:not([data-theme='light'])"),
                "登录页跟随系统的暗色块没排除手动浅色，系统暗色时选了浅色会被盖掉");
        assertTrue(html.contains("color-scheme"),
                "登录页没写 color-scheme，表单控件的原生配色会与页面脱节");
    }

    /**
     * 三屏的品牌位换产品图标，标签页也带上同一枚
     * <p>
     * 图形以 {@code icon.svg} 为准：轮廓一致靠那几条 {@code d=} 当场对，
     * 只量「不是字母 N」的话，换成任何一张图都能过。
     */
    @Test
    @DisplayName("三屏品牌位是 NovaBot 图标不是字母 N，标签页也有图标")
    void loginPageShowsProductIconAndFavicon() throws IOException {
        String html = read("login.html");
        String icon = read("icon.svg");

        Matcher marks = Pattern
                .compile("<span class=\"brand-mark\"[^>]*>(.*?)</span>", Pattern.DOTALL)
                .matcher(html);
        int markCount = 0;
        while (marks.find()) {
            markCount++;
            String inner = marks.group(1).strip();
            assertFalse(inner.equals("N"),
                    "品牌位还是字母 N，没换成产品图标");
            assertTrue(inner.contains("<svg") || inner.contains("data:image/svg+xml"),
                    "品牌位不是内联 SVG 或 data: URI 图标: " + inner);
        }
        assertTrue(markCount >= 3,
                "登录／协议／忘记密码三屏的品牌位应当都在，实见 " + markCount);

        assertTrue(Pattern.compile("<link[^>]+rel=[\"']icon[\"']", Pattern.CASE_INSENSITIVE)
                        .matcher(html).find(),
                "登录页没有标签页图标");

        Matcher paths = Pattern.compile("d=\"([^\"]+)\"").matcher(icon);
        int pathCount = 0;
        while (paths.find()) {
            pathCount++;
            String d = paths.group(1);
            assertTrue(html.contains(d),
                    "登录页图标与 icon.svg 图形不一致，缺了这条轮廓: "
                            + d.substring(0, Math.min(48, d.length())));
        }
        assertTrue(pathCount >= 3, "icon.svg 应当至少三条轮廓，实见 " + pathCount);
    }

    /**
     * 图标自包含：未登录时 {@code /config/assets} 还关着门，引它必裂图
     * <p>
     * 认的是资源引用（{@code src=}／{@code href=}／{@code url()}），不是原行找词——
     * 页头那句「不取 {@code /config/assets}」的说明也会被按词扫认成引用。
     * 顺带盯住「图标确实摆上了」：没图标与引了登录前取不到的资源，
     * 在使用者那里是同一种裂法。
     */
    @Test
    @DisplayName("登录页图标自包含，不引登录前取不到的 /config/assets")
    void loginIconsAreSelfContained() throws IOException {
        String html = read("login.html");
        String facing = resourceFacing(html);

        assertFalse(facing.contains("/config/assets"),
                "登录页引了 /config/assets 下的资源，未登录时安全过滤器还关着门，会裂图");

        Matcher marks = Pattern
                .compile("<span class=\"brand-mark\"[^>]*>(.*?)</span>", Pattern.DOTALL)
                .matcher(facing);
        int selfContained = 0;
        while (marks.find()) {
            String inner = marks.group(1).strip();
            assertFalse(inner.contains("<img") && !inner.contains("data:image/svg+xml"),
                    "品牌位的图不是 data: URI，登录前可能取不到: " + inner);
            if (inner.contains("<svg") || inner.contains("data:image/svg+xml")) {
                selfContained++;
            }
        }
        assertTrue(selfContained >= 3,
                "三屏品牌位都该是自包含图标（内联 SVG 或 data: URI），实见 " + selfContained);

        Matcher icons = Pattern
                .compile("<link[^>]+rel=[\"']icon[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
                .matcher(facing);
        assertTrue(icons.find(), "登录页没有标签页图标");
        do {
            String tag = icons.group();
            assertTrue(tag.contains("data:image/svg+xml"),
                    "标签页图标不是内联 data: URI，登录前可能取不到: " + tag);
        } while (icons.find());
    }

    /**
     * 注释挖掉，只留真正会被当成资源引用的那部分
     */
    private static String resourceFacing(String html) {
        return html
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ");
    }
}
