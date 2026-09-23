package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感操作前再核密码：界面这三处
 * <p>
 * 登记通行密钥在取登记参数之前就要「现在的密码」；两处绑定验证器的确认框各加一格密码，
 * 请求体带 {@code current}。钉住的是源码：去掉请求体里的 {@code current} 就红。
 */
@DisplayName("再核密码的界面接线")
class SensitiveReauthFrontendTest {

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    /**
     * 沿树找 config-ui，不点名模块目录：写死模块路径的话，目录重排那天这一件会安静地失灵，
     * 而本件正是要拦这件事。父链按段名钉成 src/main/resources——构建会把资源拷进
     * target/classes，那份是陈货，量它等于量上一趟的字节。
     */
    private Path configUiDir() {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root, 7)) {
            return walk.filter(Files::isDirectory)
                    .filter(dir -> "config-ui".equals(dir.getFileName().toString()))
                    .filter(dir -> {
                        Path resources = dir.getParent();
                        Path main = resources != null ? resources.getParent() : null;
                        Path src = main != null ? main.getParent() : null;
                        Path module = src != null ? src.getParent() : null;
                        return resources != null && "resources".equals(resources.getFileName().toString())
                                && main != null && "main".equals(main.getFileName().toString())
                                && src != null && "src".equals(src.getFileName().toString())
                                && module != null && Files.isRegularFile(module.resolve("pom.xml"));
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("找不到 config-ui"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) throws IOException {
        return Files.readString(configUiDir().resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * 取到函数体（含签名），花括号配对；找不到时回空串
     */
    private String functionBody(String source, String name) {
        int start = source.indexOf("function " + name);
        if (start < 0) {
            start = source.indexOf("async function " + name);
        }
        if (start < 0) {
            return "";
        }
        int brace = source.indexOf('{', start);
        if (brace < 0) {
            return "";
        }
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    /**
     * 取到对象字面量（含外层花括号），花括号配对；找不到时回空串
     */
    private String objectLiteral(String source, int from) {
        int brace = source.indexOf('{', from);
        if (brace < 0) {
            return "";
        }
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        return "";
    }

    @Test
    @DisplayName("🔴 登记通行密钥：先要现在的密码，再取登记参数，请求体带 current")
    void passkeyRegisterAsksPasswordBeforeOptions() throws IOException {
        String register = functionBody(read("passkeys.js"), "registerPasskey");
        assertFalse(register.isBlank(), "找不到 registerPasskey");

        int askAt = register.indexOf("ask(");
        int optionsAt = register.indexOf("register/options");
        assertTrue(askAt >= 0, "registerPasskey 没有先要密码");
        assertTrue(optionsAt >= 0, "registerPasskey 没有取登记参数");
        assertTrue(askAt < optionsAt, "取登记参数之后才要密码：输错已经在认证器里留下一把没人认领的钥匙");

        String beforeOptions = register.substring(0, optionsAt);
        assertTrue(beforeOptions.contains("现在的密码"), "要的那一格不是「现在的密码」");
        assertTrue(beforeOptions.contains("type: 'password'") || beforeOptions.contains("type:\"password\""),
                "那一格不是密码框");

        // 断言落在取登记参数那次请求的请求体上。整函数子串会被同函数里
        // 密码框 id、变量名里的 current 顶成绿——删掉请求体字段照样绿。
        int stringifyAt = register.indexOf("JSON.stringify(", optionsAt);
        assertTrue(stringifyAt >= 0, "取登记参数那次请求没有请求体");
        String body = objectLiteral(register, stringifyAt + "JSON.stringify(".length());
        assertFalse(body.isBlank(), "请求体不是对象字面量");
        assertTrue(Pattern.compile("(?<![\\w-])current\\s*:").matcher(body).find(),
                "请求体里没有 current 字段，后端无从核密码: " + body);

        Matcher pwdField = Pattern.compile("id:\\s*['\"]([^'\"]+)['\"],\\s*type:\\s*['\"]password['\"]")
                .matcher(beforeOptions);
        assertTrue(pwdField.find(), "没有找到密码框 id");
        String field = pwdField.group(1);
        assertTrue(body.contains("filled['" + field + "']") || body.contains("filled[\"" + field + "\"]"),
                "current 不是取自那一格密码框 " + field + ": " + body);
    }

    @Test
    @DisplayName("🔴 设置页绑定验证器确认框带密码格，请求体带 current")
    void enrollFlowAsksPassword() throws IOException {
        String enroll = functionBody(read("settings-auth.js"), "enrollFlow");
        assertFalse(enroll.isBlank(), "找不到 enrollFlow");

        assertTrue(enroll.contains("type=\"password\"") || enroll.contains("type='password'"),
                "绑定确认框没有密码格");
        int enrollCall = enroll.indexOf("/auth/totp/enroll");
        assertTrue(enrollCall >= 0, "enrollFlow 没有提交绑定");
        String after = enroll.substring(enrollCall);
        assertTrue(after.contains("current"), "绑定请求体里没有 current，后端无从核密码");
    }

    @Test
    @DisplayName("🔴 初始设置引导卡片绑定确认框带密码格，请求体带 current")
    void totpSetupCardAsksPassword() throws IOException {
        String setup = functionBody(read("main.js"), "renderTotpSetup");
        assertFalse(setup.isBlank(), "找不到 renderTotpSetup");

        assertTrue(setup.contains("type=\"password\"") || setup.contains("type='password'"),
                "引导卡片的绑定确认框没有密码格");
        int enrollCall = setup.indexOf("/auth/totp/enroll");
        assertTrue(enrollCall >= 0, "引导卡片没有提交绑定");
        String after = setup.substring(enrollCall);
        assertTrue(after.contains("current"), "绑定请求体里没有 current，后端无从核密码");
    }
}
