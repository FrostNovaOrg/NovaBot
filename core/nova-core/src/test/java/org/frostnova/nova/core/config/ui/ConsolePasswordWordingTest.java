package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台登录用的那一把，对人说的一律叫「密码」
 * <p>
 * 登录页的输入框、设置页「修改密码」那一栏早就写「密码」，接口回来的提示句与页上另一些句子却还写「口令」——
 * 同一样东西两个叫法，人会以为是两样：「现在的口令不对」说的是哪一把？
 * <p>
 * 「口令」留给另外两样东西：机器人程序界面那一把（与控制台互不相干，页上正是靠这两个词把它们分开），
 * 以及事件流的只读口令。查的只是给人看的字：Java 里的字符串字面量（日志除外），
 * 页面与脚本里注释之外的字。注释、日志、标识符不管。
 */
@DisplayName("控制台登录的提示一律叫密码")
class ConsolePasswordWordingTest {
    private static final String WORD = "口令";

    /**
     * 登录、上锁、改密码、二次验证与通行密钥几个接口回给人看的句子出自这几件
     */
    private static final List<String> BACKEND = List.of(
            "src/main/java/org/frostnova/nova/core/config/ui/ConfigUiAuthController.java",
            "src/main/java/org/frostnova/nova/core/config/ui/ConfigUiPasskeyController.java",
            "src/main/java/org/frostnova/nova/core/config/ui/auth/ConfigUiAuthService.java",
            "src/main/java/org/frostnova/nova/core/config/ui/auth/passkey/PasskeyService.java");

    /**
     * 同几件事摆在页上的句子在这几件里
     */
    private static final List<String> FRONTEND = List.of(
            "login.html", "index.html", "setup.js", "settings-auth.js", "passkeys.js",
            "main.js", "home-model.js", "login-model.js", "tokens-model.js");

    /**
     * 注释之外仍可以写「口令」的说法：机器人程序界面那一把、事件流的只读口令、日志页里「口令与 Cookie」那一类串
     */
    private static final List<String> NOT_THE_CONSOLE = List.of(
            "界面的口令", "界面口令", "WebUI 的口令", "只读口令", "签发一把新口令", "已签发的口令", "口令与 Cookie");

    private static final Pattern LOG_CALL = Pattern.compile("(^|[^\\w.])log\\.(trace|debug|info|warn|error)\\s*\\(");

    @Test
    @DisplayName("🔴 登录与安全几个接口回给人看的句子不写「口令」")
    void backendMessagesSayPassword() throws IOException {
        List<String> found = new ArrayList<>();
        int seenPassword = 0;
        for (String file : BACKEND) {
            String src = Files.readString(module().resolve(file), StandardCharsets.UTF_8);
            for (Literal literal : javaLiterals(src)) {
                if (literal.inLog()) {
                    continue;
                }
                seenPassword += literal.text().contains("密码") ? 1 : 0;
                if (literal.text().contains(WORD)) {
                    found.add(file.substring(file.lastIndexOf('/') + 1) + ":" + literal.line() + " " + literal.text());
                }
            }
        }

        assertTrue(seenPassword > 0, "阳性对照：一句带「密码」的都没读到，说明扫的不是回给人看的那些句子");
        assertTrue(found.isEmpty(), "这几句还写着「口令」，页上说的却是「密码」:\n" + String.join("\n", found));
    }

    @Test
    @DisplayName("🔴 登录、上锁、改密码、二次验证与通行密钥的页上字不写「口令」，机器人界面与只读口令那几处除外")
    void pagesSayPassword() throws IOException {
        Path ui = module().resolve("src/main/resources/config-ui");
        List<String> found = new ArrayList<>();
        int seenPassword = 0;
        for (String file : FRONTEND) {
            String src = Files.readString(ui.resolve(file), StandardCharsets.UTF_8);
            String[] lines = (file.endsWith(".html") ? htmlWithoutComments(src) : jsWithoutComments(src)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                seenPassword += lines[i].contains("密码") ? 1 : 0;
                String rest = lines[i];
                for (String allowed : NOT_THE_CONSOLE) {
                    rest = rest.replace(allowed, "");
                }
                if (rest.contains(WORD)) {
                    found.add(file + ":" + (i + 1) + " " + lines[i].strip());
                }
            }
        }

        assertTrue(seenPassword > 0, "阳性对照：一处「密码」都没读到，说明扫的不是页上那些字");
        assertTrue(found.isEmpty(), "这几处还写着「口令」，同一页别处说的却是「密码」:\n" + String.join("\n", found));
    }

    /**
     * 模块根：构建工具跑测试时的工作目录
     */
    private static Path module() {
        return Path.of("").toAbsolutePath();
    }

    private record Literal(int line, String text, boolean inLog) {
    }

    /**
     * Java 源码里的字符串字面量，注释与字符字面量跳过
     * <p>
     * 是不是日志看它所在的那一句：从上一个分号或花括号起，句中出现 log.xxx( 即算
     */
    private static List<Literal> javaLiterals(String src) {
        List<Literal> literals = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                literals.add(new Literal(lineOf(src, i), src.substring(i, end), LOG_CALL.matcher(statement).find()));
                statement.append("\"\"");
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                if (c == '"') {
                    literals.add(new Literal(lineOf(src, i), src.substring(i, Math.min(j + 1, n)),
                            LOG_CALL.matcher(statement).find()));
                }
                statement.append(c).append(c);
                i = j + 1;
            } else {
                if (c == ';' || c == '{' || c == '}') {
                    statement.setLength(0);
                } else {
                    statement.append(c);
                }
                i++;
            }
        }
        return literals;
    }

    private static int lineOf(String src, int index) {
        int line = 1;
        for (int k = 0; k < index; k++) {
            line += src.charAt(k) == '\n' ? 1 : 0;
        }
        return line;
    }

    /**
     * 脚本去掉注释，其余原样；注释换成等长空白，行号不动
     */
    private static String jsWithoutComments(String src) {
        StringBuilder out = new StringBuilder(src);
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 页面去掉 HTML 注释、内联脚本里的注释与样式里的注释，其余原样；行号不动
     */
    private static String htmlWithoutComments(String src) {
        StringBuilder out = new StringBuilder(src);
        Matcher script = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(src);
        while (script.find()) {
            out.replace(script.start(1), script.end(1), jsWithoutComments(script.group(1)));
        }
        Matcher style = Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(src);
        while (style.find()) {
            Matcher css = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(style.group(1));
            while (css.find()) {
                blank(out, style.start(1) + css.start(), style.start(1) + css.end());
            }
        }
        Matcher comment = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(src);
        while (comment.find()) {
            blank(out, comment.start(), comment.end());
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out.charAt(k) != '\n') {
                out.setCharAt(k, ' ');
            }
        }
    }
}
