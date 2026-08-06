package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置界面前端的静态检查
 * <p>
 * 前端是 ES module，没有构建步骤，因而也没有任何东西在打包时替我们检查引用。
 * 而这里的错误有个共同特征：<b>加载时不报错，要等某个页签被点开、某段界面被渲染到才抛</b>。
 * 手点一遍页签测不出来——漏改的那处 {@code senderList} 就是这么进到线上的：
 * 它只在「推送规则页有配好目标的主播」时才渲染，而测试实例的 datasource.json 是空的。
 * <p>
 * 所以这两条必须由机器查，而且要在构建时查。
 */
@DisplayName("配置界面前端")
class ConfigUiFrontendTest {
    /**
     * 跨页签共享的可变状态，全部挂在 store 上。裸着出现即为 ReferenceError
     */
    private static final List<String> SHARED = List.of(
            "schema", "values", "dirty", "tab", "csrfToken", "pushData", "handlerList",
            "senderList", "advancedMode", "wizardTouched", "pushEnabled", "accountTimer");

    /**
     * 已知的合法同名局部变量：函数内部自己声明的，与 store 无关
     */
    private static final Set<String> ALLOWED_LOCALS = Set.of("analytics.js:values");

    /**
     * 注释与普通字符串。重命名和引用检查都不该看这里面
     */
    private static final Pattern LITERAL = Pattern.compile(
            "'(?:[^'\\\\\\n]|\\\\.)*'|\"(?:[^\"\\\\\\n]|\\\\.)*\"|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 模板字符串。整块跳过是不行的——{@code ${}} 里面是真代码
     */
    private static final Pattern TEMPLATE = Pattern.compile("`(?:[^`\\\\]|\\\\.)*`", Pattern.DOTALL);

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s*\\{([^}]*)}\\s*from\\s*'\\./([^']+)';", Pattern.MULTILINE);

    private static final Pattern EXPORT = Pattern.compile(
            "^export\\s+(?:async\\s+)?(?:function\\s+|const\\s+|let\\s+|class\\s+)([A-Za-z_$][\\w$]*)",
            Pattern.MULTILINE);

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
    private Path frontendDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current.resolve("starbot-core/src/main/resources/config-ui");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private Map<String, String> sources() {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(frontendDir())) {
            files.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * 把注释与字符串挖成空白，模板字符串只保留其中的 {@code ${}} 表达式
     * <p>
     * 挖成等长空白而不是删掉，行号才不会错位——报错要能指到具体哪一行。
     */
    private String codeOnly(String text) {
        StringBuilder afterTemplate = new StringBuilder();
        Matcher t = TEMPLATE.matcher(text);
        int last = 0;
        while (t.find()) {
            afterTemplate.append(text, last, t.start());
            String literal = t.group();
            // 模板串内部：${...} 原样留下，其余挖空
            Matcher expr = Pattern.compile("\\$\\{[^}]*}").matcher(literal);
            int inner = 0;
            while (expr.find()) {
                afterTemplate.append(" ".repeat(expr.start() - inner)).append(expr.group());
                inner = expr.end();
            }
            afterTemplate.append(" ".repeat(literal.length() - inner));
            last = t.end();
        }
        afterTemplate.append(text.substring(last));

        return LITERAL.matcher(afterTemplate).replaceAll(m -> " ".repeat(m.group().length()));
    }

    @Test
    @DisplayName("共享状态一律经 store 访问，没有裸引用")
    void sharedStateIsAlwaysAccessedThroughStore() {
        List<String> bad = new ArrayList<>();

        sources().forEach((name, text) -> {
            if (name.equals("store.js")) {
                return;
            }

            String[] lines = codeOnly(text).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].stripLeading().startsWith("import ")) {
                    continue;
                }
                for (String var : SHARED) {
                    if (ALLOWED_LOCALS.contains(name + ":" + var)) {
                        continue;
                    }
                    // 前面不是 . 也不是标识符字符，后面不是标识符字符
                    if (Pattern.compile("(?<![\\w$.])" + var + "(?![\\w$])").matcher(lines[i]).find()) {
                        bad.add(name + ":" + (i + 1) + "  " + lines[i].strip());
                    }
                }
            }
        });

        assertTrue(bad.isEmpty(),
                "以下位置裸用了共享状态，会在对应界面渲染时抛 ReferenceError:\n  " + String.join("\n  ", bad));
    }

    @Test
    @DisplayName("import 进来的每个名字都确实被对方 export 了")
    void everyImportIsActuallyExported() {
        Map<String, String> sources = sources();

        Map<String, Set<String>> exports = new LinkedHashMap<>();
        sources.forEach((name, text) -> {
            Set<String> names = new LinkedHashSet<>();
            Matcher m = EXPORT.matcher(codeOnly(text));
            while (m.find()) {
                names.add(m.group(1));
            }
            exports.put(name, names);
        });

        List<String> bad = new ArrayList<>();
        sources.forEach((name, text) -> {
            Matcher m = IMPORT.matcher(text);
            while (m.find()) {
                String target = m.group(2);
                if (!sources.containsKey(target)) {
                    bad.add(name + " 引用了不存在的文件 " + target);
                    continue;
                }
                for (String imported : m.group(1).split(",")) {
                    String wanted = imported.strip();
                    if (!wanted.isEmpty() && !exports.get(target).contains(wanted)) {
                        bad.add(name + " 从 " + target + " 引入了 " + wanted + "，但那边没有 export 它");
                    }
                }
            }
        });

        assertTrue(bad.isEmpty(), "以下 import 找不到对应的 export，加载时就会失败:\n  " + String.join("\n  ", bad));
    }

    @Test
    @DisplayName("入口只有 main.js 一个，其余靠 import 拉起")
    void indexLoadsSingleModuleEntry() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);

        List<String> scripts = new ArrayList<>();
        Matcher m = Pattern.compile("<script[^>]*src=\"([^\"]+)\"[^>]*>").matcher(html);
        while (m.find()) {
            scripts.add(m.group(0));
        }

        assertTrue(scripts.size() == 1, "入口应当只有一个，实际 " + scripts.size() + " 个: " + scripts);
        assertTrue(scripts.get(0).contains("type=\"module\""), "入口必须是 module: " + scripts.get(0));
        assertTrue(scripts.get(0).contains("main.js"), "入口应当是 main.js: " + scripts.get(0));
    }
}
