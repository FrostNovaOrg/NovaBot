package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ui.page.ConsolePages;
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
            "schema", "values", "legacy", "dirty", "tab", "csrfToken", "pushData", "pushSaved",
            "handlerList", "senderList", "wizardTouched", "pushEnabled", "accountTimer",
            "platforms", "totpRequired");

    /**
     * 凭据绝不能流进去的地方
     * <p>
     * 只读口令的明文<b>只在这一页的内存里存在</b>：一旦落进浏览器存储或控制台日志，
     * 它就从「这一次显示」变成了「一直存着」，而界面上那句「离开本页后无法再次查看」
     * 会照样显示——<b>这种错在功能上完全看不出来，口令照样能用</b>。
     * <p>
     * 范围是整个 config-ui 而不只是签发那个模块：这些文件跑在同一张页面上，
     * 口令就在同一棵 DOM 里，谁都够得着。
     */
    private static final List<String> FORBIDDEN_SINKS = List.of(
            "localStorage", "sessionStorage", "console.");

    /**
     * 已知的合法同名局部变量：函数内部自己声明的，与 store 无关
     */
    private static final Set<String> ALLOWED_LOCALS = Set.of("analytics.js:values");

    /**
     * 核心自己的页签，闭集，也就是 {@code store.tab} 的取值域
     * <p>
     * 这一条是边界：<b>核心的界面文件里不许出现这几个以外的页签</b>。平台页由对应插件带进来，
     * 核心只在运行时按注册清单把它挂上去。写死一个平台的页签，界面就替一件可能没装的东西
     * 立了个入口——点开是空的，而使用者无从知道是插件没装还是坏了；
     * 想加第二个平台时，又得回头改核心的界面文件。
     * <p>
     * 六页导航之后这仍是页签名而不是路由名：{@code store.tab} 是插件页读得到的东西，
     * 换成路由名等于改了对插件的约定。路由那一侧另有 {@link #CORE_ROUTES}。
     */
    private static final Set<String> CORE_TABS = Set.of(
            "overview", "push", "bot", "sessions", "analytics", "tokens", "settings", "log", "setup");

    /**
     * 核心自己的六页导航与初始设置页，闭集
     * <p>
     * 与 {@link #CORE_TABS} 是同一条边界的另一面：<b>核心的界面文件里不许出现这几条以外的路由</b>。
     * 插件页挂在设置页「高级」下，地址是 {@code #/settings/<页标识>}，那一段由注册清单在运行时拼出，
     * 界面文件里一个平台的名字也没有。
     */
    private static final Set<String> CORE_ROUTES = Set.of(
            "home", "push", "streamers", "log", "links", "settings", "setup");

    /**
     * 插件放页面脚本的资源目录名，与服务端取资源时用的是同一个常量
     */
    private static final String PAGE_DIR = ConsolePages.SCRIPT_ROOT;

    /**
     * 界面文件里出现的页签标识：{@code data-tab="x"}、{@code <section id="x">} 与 {@code store.tab === 'x'}
     */
    private static final Pattern TAB_ATTRIBUTE = Pattern.compile("data-tab=\"([^\"]+)\"");

    private static final Pattern TAB_SECTION = Pattern.compile("<section[^>]*\\sid=\"([^\"]+)\"");

    /**
     * 侧栏上的路由入口：{@code <a href="#/x" data-page="x">}
     */
    private static final Pattern NAV_ROUTE = Pattern.compile("data-page=\"([^\"]+)\"");

    /**
     * 页容器 id 的前缀。六页各一个 {@code <section class="page" id="page-<路由>">}
     */
    private static final String PAGE_PREFIX = "page-";

    private static final Pattern TAB_COMPARISON = Pattern.compile("store\\.tab\\s*[!=]==\\s*'([^']+)'");

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

    private Path frontendDir() {
        return repoRoot().resolve("starbot-core/src/main/resources/config-ui");
    }

    /**
     * 各插件自带的页面脚本目录
     * <p>
     * 它们与核心的界面文件跑在同一张页面、同一棵 DOM 上，因此下面几条判据一条都不能少查。
     * 只查核心那一份的话，把口令写进 localStorage 这种事只要挪进插件就查不出来了。
     */
    private List<Path> pageDirs() {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            modules.sorted().forEach(module -> {
                Path dir = module.resolve("src/main/resources").resolve(PAGE_DIR);
                if (Files.isDirectory(dir)) {
                    dirs.add(dir);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dirs;
    }

    private void readInto(Map<String, String> out, Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
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
    }

    /**
     * 核心自己的界面文件
     */
    private Map<String, String> coreSources() {
        Map<String, String> out = new LinkedHashMap<>();
        readInto(out, frontendDir());
        return out;
    }

    /**
     * 插件带来的页面脚本
     */
    private Map<String, String> pageSources() {
        Map<String, String> out = new LinkedHashMap<>();
        pageDirs().forEach(dir -> readInto(out, dir));
        return out;
    }

    /**
     * 页面上真正会被加载的全部脚本
     */
    private Map<String, String> sources() {
        Map<String, String> out = coreSources();
        out.putAll(pageSources());
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

    /**
     * 把「口令只在内存里」从一句承诺变成每次构建都被检查的事实
     * <p>
     * 这条判据是奔着一类具体的错去的：有人为了「刷新后还能看到」把口令写进 localStorage，
     * 或调试时留下一行打印。两种改动都不会让任何功能变坏，因此靠人复查是拦不住的。
     */
    @Test
    @DisplayName("口令明文不进浏览器存储、地址栏与日志")
    void issuedTokenNeverLeavesMemory() {
        List<String> bad = new ArrayList<>();

        sources().forEach((name, text) -> {
            String[] lines = codeOnly(text).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (String sink : FORBIDDEN_SINKS) {
                    if (lines[i].contains(sink)) {
                        bad.add(name + ":" + (i + 1) + "  " + lines[i].strip());
                    }
                }

                // 地址栏那一条只查持有明文的那个模块：别处的 location.reload() 是正当用法，
                // 一刀切会逼出一串豁免，而豁免多了这条判据就形同虚设
                if (name.equals("tokens.js")
                        && Pattern.compile("(?<![\\w$.])(location|history)(?![\\w$])").matcher(lines[i]).find()) {
                    bad.add(name + ":" + (i + 1) + "  " + lines[i].strip());
                }
            }
        });

        assertTrue(bad.isEmpty(), "只读口令的明文只许留在内存里，以下位置会把它带出去（或为此开了口子）:\n  "
                + String.join("\n  ", bad));
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

    /**
     * 核心的页签与路由都是闭集，平台页不在其中
     * <p>
     * 四处一起查，因为写死一个平台要同时改这四处，只查一处就会剩下其余三处的残迹：
     * 侧栏上的路由入口、旧标签条上的按钮、页面里的容器、以及按名字分派时那串判断。
     */
    @Test
    @DisplayName("核心界面里只有核心自己的页签与路由，平台页由插件带")
    void coreTabsAreClosedSet() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();

        Matcher route = NAV_ROUTE.matcher(html);
        while (route.find()) {
            if (!CORE_ROUTES.contains(route.group(1))) {
                bad.add("index.html 的侧栏上写死了路由 " + route.group(1));
            }
        }

        Matcher tab = TAB_ATTRIBUTE.matcher(html);
        while (tab.find()) {
            if (!CORE_TABS.contains(tab.group(1))) {
                bad.add("index.html 的标签条上写死了页签 " + tab.group(1));
            }
        }

        Matcher section = TAB_SECTION.matcher(html);
        while (section.find()) {
            String id = section.group(1);
            // 页容器叫 page-<路由>，容器里那层旧页签容器仍叫页签名。两种命名各按各的闭集比，
            // 一律拿页签集比的话，六个页容器会被当成六处平台残迹
            boolean known = id.startsWith(PAGE_PREFIX)
                    ? CORE_ROUTES.contains(id.substring(PAGE_PREFIX.length()))
                    : CORE_TABS.contains(id);
            if (!known) {
                bad.add("index.html 里写死了页面容器 " + id);
            }
        }

        coreSources().forEach((name, text) -> {
            Matcher m = TAB_COMPARISON.matcher(text);
            while (m.find()) {
                if (!CORE_TABS.contains(m.group(1))) {
                    bad.add(name + " 按名字认出了页签 " + m.group(1));
                }
            }
        });

        assertTrue(bad.isEmpty(), "核心只该认得自己的路由 " + CORE_ROUTES + " 与页签 " + CORE_TABS
                + "，以下是写死的平台页残迹:\n  " + String.join("\n  ", bad));
    }

    /**
     * 插件页是运行时装上来的，不是编译期定死的
     * <p>
     * 静态 {@code import} 一写，那个平台就成了核心的一部分：没装插件时页面加载不了，
     * 而这件事在源码里看不出来——它长得和其余 import 一模一样。
     */
    @Test
    @DisplayName("核心不静态引用任何插件页脚本")
    void coreNeverImportsPluginPages() {
        Set<String> pages = pageSources().keySet();
        List<String> bad = new ArrayList<>();

        coreSources().forEach((name, text) -> {
            if (pages.contains(name)) {
                bad.add(name + " 同时存在于核心与插件的资源目录里");
            }

            Matcher m = IMPORT.matcher(text);
            while (m.find()) {
                if (pages.contains(m.group(2))) {
                    bad.add(name + " 静态引用了插件页 " + m.group(2));
                }
            }
        });

        assertTrue(bad.isEmpty(), "插件页只能在运行时按注册清单装载:\n  " + String.join("\n  ", bad));
    }
}
