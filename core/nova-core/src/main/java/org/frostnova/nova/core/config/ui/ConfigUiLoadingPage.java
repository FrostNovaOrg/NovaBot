package org.frostnova.nova.core.config.ui;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 首帧与登录过渡页复用控制台色表，不依赖外部资源到达。 */
final class ConfigUiLoadingPage {
    private ConfigUiLoadingPage() {
    }

    static String withPalette(String page) throws IOException {
        if (!page.contains(ConfigUiLoginPage.PALETTE_MARKER)) {
            throw new IllegalStateException("页面缺少色表占位");
        }
        return withLoadingStyles(page.replace(ConfigUiLoginPage.PALETTE_MARKER, read("product-ui.css")));
    }

    static String withLoadingStyles(String page) throws IOException {
        String marker = "/*@@loading-ui@@*/";
        if (!page.contains(marker)) {
            throw new IllegalStateException("页面缺少加载样式占位");
        }
        return page.replace(marker, read("loading-ui.css"));
    }

    static String transition(String escapedTarget) throws IOException {
        return withPalette(read("oidc-transition.html")).replace("{{console-path}}", escapedTarget);
    }

    static String contentSecurityPolicy(String html) {
        return "default-src 'none'; base-uri 'none'; frame-ancestors 'none'"
                + "; style-src 'sha256-" + hashBlock(html, "style") + "'"
                + "; script-src 'sha256-" + hashBlock(html, "script") + "'";
    }

    private static String hashBlock(String html, String tag) {
        int start = html.indexOf("<" + tag + ">") + tag.length() + 2;
        int end = html.indexOf("</" + tag + ">", start);
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(html.substring(start, end).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String read(String name) throws IOException {
        try (var stream = new ClassPathResource("config-ui/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
