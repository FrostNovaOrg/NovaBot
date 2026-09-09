package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.config.ui.page.ConsolePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
     * <p>
     * 推送那三项（{@code pushData}／{@code pushSaved}／{@code pushEnabled}）不在这张表里：
     * 它们已随推送页搬进控制台插件，成了那一页自己的模块变量——在那里裸着出现是正当写法。
     * 「核心的界面文件里不许再出现它们」由 {@link ChangeBarSourceTest} 守着，那一格连
     * 「开机还取不取 /datasource」一起量。
     */
    private static final List<String> SHARED = List.of(
            "schema", "values", "legacy", "dirty", "tab", "csrfToken",
            "handlerList", "senderList", "accountTimer",
            "platforms", "totpRequired", "vocab");

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
     * <p>
     * 现在一条也没有。唯一那条豁免属于 {@code analytics.js}，那一页已经并进主播页——
     * <b>空着比留着一条指向不存在的文件的豁免好</b>：后者会在有人恰好把新文件叫回那个名字时
     * 悄悄生效，而没有任何东西会提起它。
     */
    private static final Set<String> ALLOWED_LOCALS = Set.of();

    /**
     * 邮件服务商示例，不是推送平台名，扫描时放行
     */
    private static final String MAIL_SMTP_EXAMPLE =
            "邮件告警的 SMTP 服务器地址，如 smtp.qq.com。不用邮件告警时留空";

    private static final Pattern JAVA_STRING_LITERAL =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern STARBOT_KEY_LITERAL =
            Pattern.compile("starbot\\.[a-z0-9.-]+");

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
     * <p>
     * {@code push} 已经不在这张表里：底部改动条从前写着 {@code store.tab === 'push'}，
     * 现在它按登记在册的供数方认（见 core.js 的 registerChangeSource），核心因此
     * 一个平台页的页签名也不认识了。
     */
    private static final Set<String> CORE_TABS = Set.of(
            "overview", "bot", "sessions", "tokens", "settings", "log", "setup");

    /**
     * 核心自己的六页导航与初始设置页，闭集
     * <p>
     * 与 {@link #CORE_TABS} 是同一条边界的另一面：<b>核心的界面文件里不许出现这几条以外的路由</b>。
     * 插件页挂在设置页「高级」下，地址是 {@code #/settings/<页标识>}，那一段由注册清单在运行时拼出，
     * 界面文件里一个平台的名字也没有。
     */
    private static final Set<String> CORE_ROUTES = Set.of(
            "home", "log", "links", "settings", "setup");

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
     * 页面上摆着的元素 id：{@code id="x"}。既认 index.html 里写死的，
     * 也认脚本拼进 innerHTML 的那些——后者同样是「页面上真有这个元素」
     */
    private static final Pattern ELEMENT_ID = Pattern.compile("id=\"([A-Za-z0-9_-]+)\"");

    /** 脚本建出节点后直接赋的 id：{@code node.id = 'x'} */
    private static final Pattern ID_ASSIGNMENT = Pattern.compile("\\.id\\s*=\\s*'([A-Za-z0-9_-]+)'");

    /**
     * 脚本按字面 id 去取元素：{@code $('#x')}
     * <p>
     * 只认字面量。拼出来的（{@code $('#' + p + '-addr')}）由调用方在运行时定，
     * 静态判不出来，判据不假装自己看得见它。
     */
    private static final Pattern ID_REFERENCE = Pattern.compile("\\$\\('#([A-Za-z0-9_-]+)'\\)");

    /**
     * 注释与普通字符串。重命名和引用检查都不该看这里面
     */
    private static final Pattern LITERAL = Pattern.compile(
            "'(?:[^'\\\\\\n]|\\\\.)*'|\"(?:[^\"\\\\\\n]|\\\\.)*\"|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 模板字符串。整块跳过是不行的——{@code ${}} 里面是真代码
     * <p>
     * 写成「非反引号／反斜杠的一段，夹逃脱」而不是 {@code (?:x|\\\\.)* }：
     * 后一种在几百行的样式常量上会把 Java 正则的栈撑爆。
     */
    private static final Pattern TEMPLATE = Pattern.compile("`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`", Pattern.DOTALL);

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
        return repoRoot().resolve("core/nova-core/src/main/resources/config-ui");
    }

    /**
     * 各插件自带的页面脚本目录
     * <p>
     * 它们与核心的界面文件跑在同一张页面、同一棵 DOM 上，因此下面几条判据一条都不能少查。
     * 只查核心那一份的话，把口令写进 localStorage 这种事只要挪进插件就查不出来了。
     */
    private List<Path> pageDirs() {
        List<Path> dirs = new ArrayList<>();
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root, 2)) {
            walk.filter(Files::isDirectory)
                    .filter(module -> !module.equals(root))
                    .filter(module -> Files.isRegularFile(module.resolve("pom.xml")))
                    .sorted()
                    .forEach(module -> {
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
     * 脚本按 id 去取的每个元素，页面上确实有
     * <p>
     * 这一条奔着改版时最常见的那类错去：把 {@code index.html} 里某块的结构换掉，
     * 而渲染它的脚本还照着旧 id 去取。表现是 <b>那一块空着</b>，或者
     * {@code $('#x').innerHTML} 在 null 上抛——两者都要等那一页被打开、
     * 那一段被渲染到才发生，而首页恰恰是出事时第一眼看的那一页。
     * <p>
     * 「有」分两种，都算数：写在 {@code index.html} 里的，与某个脚本自己建出来的
     * （登录页那张验证器卡片、只读口令签发后那一块都属于后者）。
     * 只认前者的话，这条判据得为后者开一串豁免，而豁免多了它就形同虚设。
     */
    @Test
    @DisplayName("脚本按 id 取的元素，页面上确实有")
    void everyReferencedElementIdExists() throws IOException {
        Map<String, String> sources = sources();

        Set<String> available = new LinkedHashSet<>();
        Matcher inHtml = ELEMENT_ID.matcher(
                Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8));
        while (inHtml.find()) {
            available.add(inHtml.group(1));
        }
        sources.values().forEach(text -> {
            Matcher built = ELEMENT_ID.matcher(text);
            while (built.find()) {
                available.add(built.group(1));
            }
            Matcher assigned = ID_ASSIGNMENT.matcher(text);
            while (assigned.find()) {
                available.add(assigned.group(1));
            }
        });

        List<String> bad = new ArrayList<>();
        sources.forEach((name, text) -> {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                // 注释里的旧 id 不算引用：注掉的那段代码不会去取任何东西
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;
                }
                Matcher m = ID_REFERENCE.matcher(lines[i]);
                while (m.find()) {
                    if (!available.contains(m.group(1))) {
                        bad.add(name + ":" + (i + 1) + "  #" + m.group(1));
                    }
                }
            }
        });

        assertTrue(bad.isEmpty(), "以下位置按 id 去取一个页面上没有的元素，渲染到那一块时会空着或抛:\n  "
                + String.join("\n  ", bad));
    }

    /**
     * 设置页那几件事各自要有落点
     * <p>
     * 搜索、显示键名、只看改过的、组目录药丸、配置文件路径——每一件都由
     * {@code index.html} 里的一个元素与 {@code settings.js} 里的一段代码合起来完成，
     * <b>缺哪一半都不会报错</b>：元素没了，脚本按 id 取到 null（那一条由
     * {@link #everyReferencedElementIdExists} 管）；脚本没接上，元素就静静地立在那里，
     * 点它什么也不发生——而后者在任何一次「打开页面看一眼」里都看不出来。
     * <p>
     * 这一格量的是「落点都在」，量不到的是「渲染出来的组真的按那个顺序排」——
     * 后者要在跑起来的界面上看，组的先后本身由 {@code ConfigurationGroupsTest} 管。
     */
    @Test
    @DisplayName("设置页的搜索、显示键名、只看改过、组目录、路径各有落点")
    void settingsPageControlsAreWiredUp() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        Map<String, String> sources = coreSources();
        String scripts = String.join("\n", sources.values());

        List<String> bad = new ArrayList<>();
        for (String id : SETTINGS_CONTROLS) {
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }
        if (html.contains("id=\"cfg-copy\"")) {
            bad.add("index.html 上仍有 #cfg-copy。复制路径按钮应已撤掉，改由路径本身点一下复制");
        }
        if (scripts.contains("$('#cfg-copy')")) {
            bad.add("仍有脚本按 #cfg-copy 去取。接线应挂在 #cfg-path 的点击上");
        }
        if (!scripts.contains("$('#cfg-path').addEventListener('click'")) {
            bad.add("#cfg-path 没有挂点击接线，点路径不会复制");
        }
        if (!html.contains("id=\"cfg-path\"") || !html.contains("title=\"点一下复制\"")) {
            bad.add("#cfg-path 没有 title「点一下复制」");
        }
        if (html.contains("nv-card cfgpath") || html.contains("cfgpath nv-card")) {
            bad.add("配置文件路径仍套着 .nv-card，页脚不应再是一张卡片");
        }

        assertTrue(bad.isEmpty(), "设置页少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 源码地址。与界面里那一份是同一个串，两份分叉时这一格会红
     */
    private static final String SOURCE_URL = "https://github.com/FrostNovaOrg/NovaBot";

    /**
     * 写死的版本号长什么样：{@code 5.3.0} 这种三段数字
     * <p>
     * {@code AGPL-3.0} 只有两段，不落进来。
     */
    private static final Pattern HARDCODED_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /**
     * 设置页页底那一行许可与源码地址
     * <p>
     * AGPL-3.0 要求使用者拿得到这个程序对应的源码，而使用者常常只见得到这张控制台——
     * 只写在仓库的 README 里等于只告诉了已经找到仓库的那些人。因此这一行是发布义务的落点，
     * 不是一句装饰，得由机器守着。
     * <p>
     * 版本那一半奔着一类具体的退步去：<b>图省事在这一行里写死一个版本号</b>。
     * 写死的那个与产物脱节的那一天屏幕上不会有任何异常——它照样显示一个像模像样的版本，
     * 而使用者正是照着它判断该不该换 jar。所以这里查三样：函数体里没有三段式的版本字面量、
     * 那个值确实来自参数，以及<b>那个参数确实接在 {@code /api/status} 的 version 上</b>。
     * 少了末一样，把 {@code renderAbout} 接到任何一个别的字符串上都照样绿。
     */
    @Test
    @DisplayName("设置页页底有许可与源码地址一行，版本取自 /api/status 而非写死")
    void aboutLineShowsLicenseAndSource() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        String overview = coreSources().getOrDefault("overview.js", "");
        // 只截 renderAbout 自己那一段。functionBodyAny 截到「下一个未导出的函数」，
        // 中间夹着的几个 export function 会一并落进来——那样「没有写死的版本号」
        // 量的就是别人家的正文，而别人家添一个三段数字这一格就假红
        int head = overview.indexOf("function renderAbout(");
        int tail = head < 0 ? -1 : overview.indexOf("\n}", head);
        String about = head < 0 ? ""
                : overview.substring(head, tail < 0 ? overview.length() : tail);

        List<String> bad = new ArrayList<>();

        // 落点必须在设置页那一段里。摆在别处的话，判据说「页面上有」而设置页上读不到
        int from = html.indexOf("id=\"page-settings\"");
        int to = html.indexOf("id=\"page-setup\"", Math.max(from, 0));
        if (from < 0 || to < 0) {
            // 找不到那一段时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
            bad.add("index.html 里找不到设置页那一段（#page-settings 到 #page-setup 之间）");
        } else if (!html.substring(from, to).contains("id=\"about-line\"")) {
            bad.add("设置页那一段里没有 #about-line，页底读不到许可与源码地址");
        }

        if (about.isBlank()) {
            bad.add("overview.js 里找不到 renderAbout，下面几条无从量起");
        } else {
            if (!about.contains("AGPL-3.0")) {
                bad.add("那一行没有写出许可证 AGPL-3.0");
            }
            if (!overview.contains(SOURCE_URL)) {
                bad.add("overview.js 里没有源码地址 " + SOURCE_URL);
            }
            if (!about.contains("<a href=")) {
                bad.add("源码地址不是可点的链接，使用者得自己把它抄进地址栏");
            }
            if (!about.contains("$('#about-line')")) {
                bad.add("renderAbout 没有往 #about-line 上写，那一行永远空着");
            }
            if (HARDCODED_VERSION.matcher(about).find()) {
                bad.add("renderAbout 里写死了版本号。写死的那个与产物脱节时屏幕上不会有任何异常: "
                        + about.strip());
            }
            if (!about.contains("version")) {
                bad.add("renderAbout 没有用上传进来的版本");
            }
        }

        // 接线：那个参数确实一路接到 /api/status 的 version 上。
        // 在 renderVersion 的正文里找而不是在整份文件里找——整份文件里
        // `function renderAbout(version) {` 这个声明本身就含着那个串，那样查是恒真的
        int callerHead = overview.indexOf("function renderVersion(");
        int callerTail = callerHead < 0 ? -1 : overview.indexOf("\n}", callerHead);
        String caller = callerHead < 0 ? ""
                : overview.substring(callerHead, callerTail < 0 ? overview.length() : callerTail);
        if (caller.isBlank()) {
            bad.add("overview.js 里找不到 renderVersion，接线无从量起");
        } else if (!caller.contains("renderAbout(version)")) {
            bad.add("renderVersion 没有把自己收到的 version 传给 renderAbout，"
                    + "那一行的版本与侧栏版本位不同源: " + caller.strip());
        }
        if (!overview.contains("renderVersion(data.version)")) {
            bad.add("renderStatus 没有把 /api/status 的 version 传给 renderVersion，"
                    + "上面那条「取自参数」因此不作数");
        }

        assertTrue(bad.isEmpty(), "设置页那一行许可与源码地址有问题:\n  " + String.join("\n  ", bad));
    }

    /**
     * 布尔行的生效标记必须和开关在同一行、垂直居中。
     * <p>
     * 全局 {@code .badge} 带 {@code margin-top:8px}，是给文本／数字／下拉那些
     * 「标记在输入框下方」的行用的。布尔行如果走同一份，标记会被压到开关下缘之下。
     * 这一格钉的是布尔行另有一份居中规则，且不改全局 {@code .badge}——改全局会把
     * 非布尔行的标记也拽上去。
     */
    @Test
    @DisplayName("布尔行生效标记与开关同一行垂直居中，全局徽章规则不动")
    void booleanRowBadgeSitsBesideTheSwitch() throws IOException {
        String css = Files.readString(frontendDir().resolve("app.css"), StandardCharsets.UTF_8);
        String settings = coreSources().getOrDefault("settings.js", "");

        List<String> bad = new ArrayList<>();
        String cell = cssBlock(css, ".boolcell");
        if (cell.isBlank()) {
            bad.add("app.css 没有 .boolcell，布尔行的开关与标记会各走一块");
        } else {
            if (!cell.contains("inline-flex")) {
                bad.add(".boolcell 不是 inline-flex，开关与标记不在一行: " + cell.strip());
            }
            if (!cell.contains("align-items:center") && !cell.contains("align-items: center")) {
                bad.add(".boolcell 没有垂直居中: " + cell.strip());
            }
            if (!cell.contains("gap:10px") && !cell.contains("gap: 10px")) {
                bad.add(".boolcell 间距不是约 10px: " + cell.strip());
            }
        }
        String beside = cssBlock(css, ".boolcell .badge");
        if (beside.isBlank()) {
            bad.add("app.css 没有 .boolcell .badge，布尔行仍吃全局 margin-top:8px");
        } else if (!beside.contains("margin-top:0") && !beside.contains("margin-top: 0")) {
            bad.add(".boolcell .badge 没有去掉 margin-top: " + beside.strip());
        }
        String global = cssBlock(css, ".badge");
        if (!global.contains("margin-top:8px") && !global.contains("margin-top: 8px")) {
            bad.add("全局 .badge 的 margin-top:8px 被改掉了，非布尔行的标记会贴上输入框: "
                    + global.strip());
        }
        if (!settings.contains("'boolcell'") && !settings.contains("\"boolcell\"")) {
            bad.add("settings.js 没有给布尔行加上 boolcell");
        }

        assertTrue(bad.isEmpty(), "布尔行生效标记与开关没对齐:\n  " + String.join("\n  ", bad));
    }

    /**
     * 二次验证开关必须和设置页布尔行同一套构造，且不能套进 {@code .al-fld}。
     * <p>
     * {@code .al-fld label} 是 {@code display:block}，{@code .al-fld input} 带输入框的
     * padding 与边框。开关是 {@code label.switch > input}，套进去之后滑块 {@code ::after}
     * 仍按 38×22、无内边距定位，看起来错位。两处各写一份 DOM 的话，修一处另一处还会漂。
     */
    @Test
    @DisplayName("二次验证开关与设置页布尔行同一套构造，不套进告警字段容器")
    void totpSwitchMatchesSettingsBooleanSwitch() {
        Map<String, String> sources = coreSources();
        String core = sources.getOrDefault("core.js", "");
        String settings = sources.getOrDefault("settings.js", "");
        String auth = sources.getOrDefault("settings-auth.js", "");
        String totp = functionBodyAny(auth, "totpCard");

        List<String> bad = new ArrayList<>();
        if (!core.contains("export function switchControl")) {
            bad.add("core.js 没有导出 switchControl，两处开关会再各写一份");
        }
        if (!settings.contains("switchControl(")) {
            bad.add("settings.js 布尔行没有走 switchControl");
        }
        if (!auth.contains("switchControl(")) {
            bad.add("settings-auth.js 二次验证开关没有走 switchControl");
        }
        if (totp.isBlank()) {
            bad.add("找不到 totpCard，下面两条无从量起");
        } else {
            if (totp.contains("'al-fld'") || totp.contains("\"al-fld\"")) {
                bad.add("totpCard 仍把开关放进 .al-fld：那一组规则是给输入框的，会把滑块撑歪");
            }
            if (!totp.contains("switchControl('totp-switch'") && !totp.contains("input.id = 'totp-switch'")) {
                bad.add("totpCard 没有建出 #totp-switch");
            }
            if (!totp.contains("'二次验证'")) {
                bad.add("totpCard 丢了 aria-label「二次验证」");
            }
            if (!totp.contains("input.checked") || !totp.contains("text.textContent")) {
                bad.add("totpCard 的 settle 不再更新 input.checked / text.textContent");
            }
        }

        assertTrue(bad.isEmpty(), "二次验证开关与别处不是同一套:\n  " + String.join("\n  ", bad));
    }

    /**
     * 设置页上这几件事各自的落点，闭集
     * <p>
     * 搜索框、显示键名、只看改过的、计数、组目录药丸、组容器、配置文件路径。
     * 路径本身点一下复制，不再另立复制按钮。
     */
    private static final List<String> SETTINGS_CONTROLS = List.of(
            "set-search", "show-keys", "only-changed", "set-count", "grp-nav", "groups",
            "cfg-path");

    /**
     * 「登录与安全」那一组里由脚本建出来的落点，闭集
     * <p>
     * 与 {@link #SETTINGS_CONTROLS} 分开是因为这几件事<b>不写在 index.html 里</b>：
     * 设置页会整体重绘（保存过一次、放弃一次改动都会），而重绘的第一步是把组容器清空——
     * 写死在页面里再搬进去的那一块会跟着一起没掉，此后按 id 取到的是 null，
     * 那一块就<b>安静地从页面上消失</b>了。通行密钥那一块正是这么搬过的。
     */
    private static final List<String> AUTH_CONTROLS = List.of(
            "auth-cards", "pwd-save", "pwd-current-reveal", "pwd-next-reveal", "pwd-again-reveal",
            "totp-switch", "passkey-add", "setup-rerun");

    /**
     * 「登录与安全」那一组要调的端点，闭集
     * <p>
     * 每一条背后都是一道门：改口令要旧口令、重设只认令牌会话、关二次验证要现在的码。
     * 界面上少接一条，那件事就变成一个点了没反应的按钮——而按钮本身看起来完全正常。
     */
    private static final List<String> AUTH_ENDPOINTS = List.of(
            "/auth/password/change", "/auth/password/reset", "/auth/totp/disable",
            "/auth/totp/enroll", "/auth/passkeys", "/setup/rerun");

    /**
     * 「登录与安全」那一组的四件事各有落点，且各自接到了自己那条端点
     * <p>
     * 元素与接线缺哪一半都不会报错：元素没建出来，脚本按 id 取到 null（那一条由
     * {@link #everyReferencedElementIdExists} 管）；脚本没接上，按钮就静静地立在那里，
     * 点它什么也不发生——而后者在任何一次「打开页面看一眼」里都看不出来。
     */
    @Test
    @DisplayName("登录与安全组的改口令、二次验证、通行密钥、重跑初始设置各有落点")
    void authGroupControlsAreWiredUp() throws IOException {
        Map<String, String> sources = coreSources();
        String scripts = String.join("\n", sources.values());
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);

        List<String> bad = new ArrayList<>();
        for (String id : AUTH_CONTROLS) {
            if (!scripts.contains("id=\"" + id + "\"")
                    && !scripts.contains(".id = '" + id + "'")
                    && !scripts.contains("switchControl('" + id + "'")) {
                bad.add("没有任何脚本建出 #" + id);
            }
        }

        for (String endpoint : AUTH_ENDPOINTS) {
            if (!scripts.contains("'" + endpoint + "'")) {
                bad.add("没有任何脚本调用 " + endpoint + "，那一件事此刻点了不管用");
            }
        }

        // 从后门进来时的常驻提醒是页面自带的一块，不随设置页重绘，因此查的是 index.html
        if (!html.contains("id=\"op-banner\"")) {
            bad.add("index.html 上没有 #op-banner");
        }
        if (!scripts.contains("$('#op-banner')")) {
            bad.add("没有任何脚本用到 #op-banner，从启动令牌进来时那条提醒不会出现");
        }

        assertTrue(bad.isEmpty(), "登录与安全那一组少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 通行密钥列表必须按调用方传入的容器画，不能按 id 从文档里取
     * <p>
     * 设置页那张卡是游离节点：建出来时还没挂进文档。按 id 从 document 取会静默拿不到，
     * 卡片里既没有已登记的设备，也没有「还没有登记过通行密钥」那句提示——
     * <b>登记后列表空白</b>。登记成功那一刻列表出现过、刷新页面又没了，就是这个原因：
     * 成功回调时卡片已经在文档里，刷新后再画时还不在。
     * <p>
     * 登记按钮那一路已经写明「按钮由调用方传进来，不在这里按 id 取」，列表这一路漏了同样的处理。
     */
    @Test
    @DisplayName("通行密钥列表按传入的容器画，不按 id 从文档里取")
    void passkeyListLoadsFromPassedContainer() {
        Map<String, String> sources = coreSources();
        String settingsAuth = sources.getOrDefault("settings-auth.js", "");
        String passkeys = sources.getOrDefault("passkeys.js", "");
        List<String> bad = new ArrayList<>();

        if (settingsAuth.isBlank()) {
            bad.add("找不到 settings-auth.js");
        }
        if (passkeys.isBlank()) {
            bad.add("找不到 passkeys.js");
        }

        Matcher calls = Pattern.compile("loadPasskeys\\s*\\(([^)]*)\\)").matcher(codeOnly(settingsAuth));
        int hits = 0;
        while (calls.find()) {
            hits++;
            if (calls.group(1).strip().isEmpty()) {
                bad.add("settings-auth.js 调 loadPasskeys 没传容器。"
                        + "卡片建出来时还没挂进文档，按 id 从文档里取会静默拿不到，登记后列表空白。");
            }
        }
        if (!settingsAuth.isBlank() && hits == 0) {
            bad.add("settings-auth.js 没有调 loadPasskeys，通行密钥列表不会画");
        }

        if (!passkeys.contains("function loadPasskeys")) {
            bad.add("passkeys.js 没有 loadPasskeys，通行密钥列表不会画");
        } else {
            Pattern mainPath = Pattern.compile("(?:const|let|var)\\s+box\\s*=\\s*\\$\\('#passkey-list'\\)");
            String[] lines = passkeys.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;
                }
                if (mainPath.matcher(lines[i]).find()) {
                    bad.add("passkeys.js:" + (i + 1)
                            + " 的 loadPasskeys 主路径仍按 id 取 #passkey-list。"
                            + "卡片还没挂进文档时取不到，登记后列表空白。");
                }
            }
        }

        assertTrue(bad.isEmpty(), "通行密钥列表少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 连接页上由 index.html 摆着、由脚本接线的那几处落点，闭集
     * <p>
     * 机器人那张卡里的连接表单与「打开 NapCat 界面」、发一条试试那一块的目标下拉、
     * 刷新名单、发送、结果与提示、以及外部面板卡里的签发表单与清单。
     */
    private static final List<String> LINK_CONTROLS = List.of(
            "link-cards", "bot-form", "napcat-entry", "napcat-open",
            "test-target", "test-refresh", "test-send", "test-result", "test-hint",
            "token-issue", "token-list");

    /**
     * 视图模型给卡片的标识，闭集
     * <p>
     * 它们同时是页面上那两张卡的 id（{@code id="card-napcat"}）。平台卡的标识由插件的页签标识拼出，
     * 运行期才知道，因此不在这张表里。
     */
    private static final List<String> LINK_CARD_KEYS = List.of("napcat", "panel");

    private static final String LINKS_MODEL = "links-model.js";

    /**
     * 连接页三张卡各有落点，且「发一条试试」里没有手填号码的格子
     * <p>
     * 前一半与设置页那两条同理：元素与接线缺哪一半都不会报错，而后者在任何一次
     * 「打开页面看一眼」里都看不出来。
     * <p>
     * 🔴 后一半奔着一类具体的退步去：<b>把手填号码的格子加回来</b>。「发一条试试」存在的
     * 全部意义是把「群号填错」与「Token 不对 / 机器人不在群里 / OneBot 没起」这三类分开——
     * 它们的表现完全一样，都是什么都不发生。有了手填的格子，第一类错就又混了回去，
     * 而且是在使用者最相信这一步的时候。加回来的那个改动<b>不会让任何功能变坏</b>，
     * 因此靠人复查是拦不住的。
     * <p>
     * 目标必须来自名单这件事本身由 {@code links-model.js} 的 resolveTarget 判，
     * 那一格在 tools/links-model-check.sh 里（含四例手填的阳性对照）。这里守的是它的另一半：
     * 界面上根本没有可以手填的地方。
     */
    @Test
    @DisplayName("连接页三张卡各有落点，发一条试试里没有手填号码的格子")
    void linksPageIsWiredUp() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        Map<String, String> sources = coreSources();
        String scripts = String.join("\n", sources.values());

        List<String> bad = new ArrayList<>();
        for (String id : LINK_CONTROLS) {
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }

        // 卡的 id 是拼出来的（$('#card-' + card.key)），上面那种字面量比对看不见它。
        // 因此改判两头：页面上有这张卡，而模型里确实有一个同名的标识——
        // 两头对不上时卡永远不换色，而页面本身完全正常
        String model = sources.getOrDefault(LINKS_MODEL, "");
        for (String key : LINK_CARD_KEYS) {
            if (!html.contains("id=\"card-" + key + "\"")) {
                bad.add("index.html 上没有 #card-" + key);
            }
            if (!model.contains("'" + key + "'")) {
                bad.add(LINKS_MODEL + " 里没有卡片标识 " + key + "，那张卡永远不会换色");
            }
        }
        if (!scripts.contains("$('#card-' + card.key)")) {
            bad.add("没有任何脚本按模型给的标识去取那张卡");
        }

        bad.addAll(testMessageHasNoFreeTextField(html));

        assertTrue(bad.isEmpty(), "连接页少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 「发一条试试」那一块里只许有下拉框
     * @param html index.html 全文
     * @return 问题，没有则为空
     */
    private List<String> testMessageHasNoFreeTextField(String html) {
        List<String> bad = new ArrayList<>();

        int from = html.indexOf("id=\"card-test\"");
        int to = html.indexOf("id=\"tokens\"", Math.max(from, 0));
        if (from < 0 || to < 0) {
            // 找不到那一块时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
            bad.add("index.html 里找不到「发一条试试」那一块（#card-test 到 #tokens 之间）");
            return bad;
        }

        String block = html.substring(from, to);
        if (block.contains("<input")) {
            bad.add("「发一条试试」里出现了输入框。目标只能从机器人自己给的名单里挑——"
                    + "手填时填错一位数不会有任何报错，消息只是发去了别处");
        }
        // 阴性对照：这一格得能分辨。没有这一句的话，那一块整个被删掉也照样「不含输入框」
        if (!block.contains("<select")) {
            bad.add("「发一条试试」里没有目标下拉框，上面那条「不含输入框」因此不作数");
        }

        return bad;
    }

    /**
     * 日志页两半上那几件事各自的落点，闭集
     * <p>
     * 上一半（时间线）：日期条、只看问题、大类药丸、主播与通道两栏、搜索、清掉筛选、
     * 列表与页脚、保留期那句话；下一半（工程日志）：级别药丸、搜索、翻日子、行数、跟随最新、
     * 复制这一段、重新读、正文、页脚、从日志页跳过来那条横幅。
     */
    private static final List<String> LOG_CONTROLS = List.of(
            "log-daybar", "log-only", "log-cats", "log-streamer", "log-channel", "log-q",
            "log-clear", "log-list", "log-more", "log-retention", "log-eng-open", "log-back",
            "eng-levels", "eng-q", "eng-date", "eng-limit", "eng-follow", "eng-copy",
            "eng-reload", "eng-body", "eng-foot", "eng-jump", "eng-napcat");

    /**
     * 日志页那几件事各有落点
     * <p>
     * 与设置页、连接页那两条同理：元素与接线缺哪一半都不会报错——元素没了，脚本按 id 取到 null；
     * 脚本没接上，控件就静静地立在那里，点它什么也不发生。后者在任何一次「打开页面看一眼」里
     * 都看不出来，而这一页恰恰是出事时来看的那一页。
     * <p>
     * 这一格量的是「落点都在」，量不到的是「点下去筛得对不对」——那几件事是纯函数，
     * 由 {@link LogModelTest} 喂值跑。
     */
    @Test
    @DisplayName("日志页的日期条、大类药丸、三筛、跟随最新、复制这一段各有落点")
    void logPageControlsAreWiredUp() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        String scripts = String.join("\n", coreSources().values());

        List<String> bad = new ArrayList<>();
        for (String id : LOG_CONTROLS) {
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }

        assertTrue(bad.isEmpty(), "日志页少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 初始设置页那一份渲染
     */
    private static final String SETUP_VIEW = "setup.js";

    /**
     * 初始设置页那份判法
     */
    private static final String SETUP_MODEL = "setup-model.js";

    /**
     * 初始设置页的外壳，写在 {@code index.html} 里，闭集
     * <p>
     * 进度条、正文与「稍后再说」。各步的内容全部由脚本建出来，不写在页面里——
     * 同一件事在页面与脚本里各有一份的话，两份分叉时屏幕上不会有任何异常。
     */
    private static final List<String> SETUP_SHELL = List.of(
            "setup-steps", "setup-main", "setup-later");

    /**
     * 各步各自那几件事的落点，由 {@code setup.js} 建出来，闭集
     * <p>
     * 底下那一条（上一步／跳过／下一步／拦住的理由）、第 1 步的两遍口令与通行密钥、
     * 第 2 步的五格连接参数与测试、末步的发给谁、发一条、收到了／没收到与那三条排查，
     * 以及初始值那一摊与「进控制台」。
     * <p>
     * 主播那一步的落点（平台、uid、找一下、推到哪、走完那个「去主播页看看」）不在这里：
     * 那一步已随控制台插件走，它自己那份落点由 console 模块的判据量。
     */
    private static final List<String> SETUP_CONTROLS = List.of(
            "setup-back", "setup-next", "setup-why", "setup-skip",
            "setup-lock", "setup-pwd", "setup-pwd2", "setup-passkey",
            "setup-test-bot", "setup-addr", "setup-hport", "setup-wport",
            "setup-htoken", "setup-wtoken",
            "setup-send-target", "setup-send", "setup-got", "setup-not-got", "setup-tips",
            "setup-defaults", "setup-enter");

    /**
     * 各步各自要调的端点，闭集
     * <p>
     * 少接一条，那一步就变成一个点了没反应的按钮——而按钮本身看起来完全正常。
     * 这些端点别处也在用，因此只在 {@code setup.js} 里找：拿全部脚本找的话，
     * 这一页把某条丢了也照样绿，因为别的页还留着它。
     */
    private static final List<String> SETUP_ENDPOINTS = List.of(
            "/status", "/login", "/setup/state", "/setup/rerun/consumed", "/setup/test-sent",
            "/auth/password/set", "/setup/test-bot", "/setup/bot",
            "/onebot/targets?type=group", "/onebot/targets?type=friend", "/test-message");

    /**
     * 初始设置各步各有落点，且放行的判法只有 setup-model 一份
     * <p>
     * 与设置页、连接页、日志页那三条同理：元素与接线缺哪一半都不会报错。
     * <p>
     * 🔴 后半截奔着一类具体的退步去：<b>把「这一步放不放行」抄一份到渲染代码里</b>。
     * 那几条规则（第 1 步不许跳、第 3 步不登录必须先过确认、末步没发过不算完）
     * 由 {@code setup-model.js} 现算，那一份有 node 夹具逐格在量；抄进渲染代码之后，
     * 夹具照样全绿——它量的还是那份没人调的判法，而屏幕上跑的是新抄的这一份。
     * 抄的那一下<b>不会让任何功能变坏</b>，因此靠人复查是拦不住的。
     * <p>
     * 同一条理由也管着「推到哪」与「发给谁」：目标只能从机器人自己给的名单里挑，
     * 这一条判在 {@code links-model.js} 的 resolveTarget 里，本页必须调它而不是自己认。
     */
    @Test
    @DisplayName("初始设置各步各有落点，放行的判法只有 setup-model 一份")
    void setupPageIsWiredUp() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        Map<String, String> sources = coreSources();
        String scripts = String.join("\n", sources.values());
        String view = sources.getOrDefault(SETUP_VIEW, "");

        List<String> bad = new ArrayList<>();
        // 找不到那份渲染时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
        if (view.isBlank()) {
            bad.add("找不到 " + SETUP_VIEW + "，下面每一格都无从量起");
        }
        if (!sources.containsKey(SETUP_MODEL)) {
            bad.add("找不到 " + SETUP_MODEL + "，各步的判法没有落脚的地方");
        }

        for (String id : SETUP_SHELL) {
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }

        for (String id : SETUP_CONTROLS) {
            if (!view.contains("'" + id + "'")) {
                bad.add(SETUP_VIEW + " 里没有 #" + id + "，那一步少了这件事的落点");
            }
        }

        for (String endpoint : SETUP_ENDPOINTS) {
            if (!view.contains("'" + endpoint + "'")) {
                bad.add(SETUP_VIEW + " 没有调用 " + endpoint + "，那一步此刻点了不管用");
            }
        }

        if (!view.contains("canAdvance(")) {
            bad.add(SETUP_VIEW + " 没有问过 canAdvance，「下一步」此刻谁都拦不住");
        }
        if (view.contains("function canAdvance") || view.contains("function stepFacts")) {
            bad.add(SETUP_VIEW + " 自己又判了一遍那几步。那几条规则只许有 " + SETUP_MODEL
                    + " 一份——抄一份进来之后，夹具量的还是没人调的那一份");
        }
        if (!view.contains("resolveTarget(")) {
            bad.add(SETUP_VIEW + " 没有经过 resolveTarget 认目标。手填时填错一位数不会有任何报错，"
                    + "消息只是发去了别处，而这两步存在的意义正是把那种错拦在配置阶段");
        }

        // 主播步已随控制台插件走：内置步只剩四步，插件步的锚点跟着挪到「登录直播平台」之后。
        // 锚点还指着 streamer 的话，装了插件的机器上 findIndex 落空、插件步一律挤到最后一步之后，
        // 而无插件的机器上一切正常——这种错只在装了插件的那台机器上现形
        String home = sources.getOrDefault("home-model.js", "");
        if (home.isBlank()) {
            bad.add("找不到 home-model.js，步骤表与插件步锚点无从量起");
        }
        if (home.contains("key: 'streamer'")) {
            bad.add("home-model.js 的 SETUP_STEPS 仍含 streamer，那一步已随控制台插件走");
        }
        if (home.contains("'streamer'")) {
            bad.add("home-model.js 仍按名字认得 streamer，插件步的键不该写进核心");
        }
        if (!home.contains("afterKey || 'account'")) {
            bad.add("withPluginSteps 的缺省锚不是 'account'，主播步搬走后它指着一个不存在的键");
        }
        if (ConsolePages.valid(List.of(setupStepPage("streamer", "setup-streamer.js"))).isEmpty()) {
            bad.add("ConsolePages 的内置向导步闭集仍拦着 streamer，控制台插件那一步登记不上");
        }
        // 阴性对照：仍是内置步的那几个照旧拦住，免得上一条靠「闭集整个空掉」蒙混过关
        if (!ConsolePages.valid(List.of(setupStepPage("lock", "setup-lock.js"))).isEmpty()) {
            bad.add("内置向导步闭集把 lock 也放行了，它整个失效了");
        }

        assertTrue(bad.isEmpty(), "初始设置页少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 一个只报 id 与脚本名的向导步注册项，用来问登记关口收不收
     */
    private static ConsolePageProvider setupStepPage(String id, String script) {
        return new ConsolePageProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String script() {
                return script;
            }

            @Override
            public ConsolePageSlot slot() {
                return ConsolePageSlot.SETUP_STEP;
            }
        };
    }

    @Test
    @DisplayName("登录页口令框占满余宽，「显示」钮不吃整行")
    void loginSecretFieldDoesNotCollapse() throws IOException {
        String html = Files.readString(frontendDir().resolve("login.html"), StandardCharsets.UTF_8);
        String button = cssBlock(html, ".secret button");
        String input = cssBlock(html, ".secret input");

        assertFalse(button.isBlank(), "login.html 里找不到 .secret button");
        assertFalse(input.isBlank(), "login.html 里找不到 .secret input");
        assertTrue(button.contains("width:") && !button.contains("width: 100%") && !button.contains("width:100%"),
                ".secret button 须有自己的 width（非 100%），否则通用 button{width:100%} 把它撑满整行: " + button);
        assertTrue(input.contains("min-width:0") || input.contains("min-width: 0"),
                ".secret input 须含 min-width:0，否则口令框被挤到最小: " + input);
    }

    @Test
    @DisplayName("登录后经落点助手进控制台：带着原来的 hash，同址带片段时整页重载")
    void loginRedirectKeepsTheHash() throws IOException {
        String html = Files.readString(frontendDir().resolve("login.html"), StandardCharsets.UTF_8);
        Matcher bare = Pattern.compile("location\\.replace\\('/config'\\)").matcher(html);
        assertFalse(bare.find(),
                "任何 location.replace 不得写成 '/config' 丢掉 hash，否则从 #/settings 进来登录完会落到首页");
        assertFalse(html.contains("location.replace('/config' + location.hash)"),
                "登录成功的落点须经落点助手走。裸写 replace 的话，地址栏已是 /config#/… 时目标与当前"
                        + "完全相同，浏览器只做片段导航不重取页面——登录成功了也进不了控制台，刷新才进得去");
        int uses = html.split("enterConsole\\(", -1).length - 1;
        assertTrue(uses >= 5,
                "enterConsole 应有定义一处加四处登录成功调用，此刻只有 " + uses + " 处");
        assertTrue(html.contains("location.reload()"),
                "落点与当前同址（带片段）时必须整页重载，否则片段导航进不了控制台");
    }

    /**
     * 两处功能照常、只是长得不对的退步，机器也得拦
     * <ul>
     *   <li>登录页「用口令登录」折叠头：折着时它是这条路的入口，得是个按钮的形，
     *       而不是一行没边没底的小字。摊开之后它退居小字标题。</li>
     *   <li>通道一览六列全部 nowrap，表格布局里 td 的 max-width 不生效，卡片又没有 overflow——
     *       列一多整张表把卡片顶破。外包一层 .tblwrap 让它横向滚，做法与主播页历史表一致。</li>
     * </ul>
     * 三问各自记下，末尾一起红。
     */
    @Test
    @DisplayName("登录页口令折叠头有按钮形、通道一览表格有横向滚动容器")
    void loginFoldHeaderIsAButtonAndPushIndexTableScrolls() throws IOException {
        List<String> reds = new ArrayList<>();

        try {
            String html = Files.readString(frontendDir().resolve("login.html"), StandardCharsets.UTF_8);
            String summary = cssBlock(html, "details > summary");
            assertFalse(summary.isBlank(), "login.html 里找不到 details > summary 这条规则");
            assertTrue(summary.contains("border:"),
                    "「用口令登录」折着时是这条路的入口，须有按钮形（border），此刻: " + summary);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            String push = pageSources().getOrDefault("push.js", "");
            String render = functionBodyAny(push, "renderIndex");
            assertFalse(render.isBlank(), "push.js 里找不到 renderIndex");
            assertTrue(render.contains("'tblwrap'"),
                    "通道一览的表须包进 el('div','tblwrap')，否则六列 nowrap 会把卡片顶破");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            String css = Files.readString(frontendDir().resolve("app.css"), StandardCharsets.UTF_8);
            assertTrue(css.contains(".tblwrap{overflow-x:auto"),
                    "app.css 须有 .tblwrap{overflow-x:auto}，横向滚动容器是全站通用类");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 推送页那份判法
     */
    private static final String PUSH_MODEL = "push-model.js";

    /**
     * 推送页那份渲染
     */
    private static final String PUSH_VIEW = "push.js";

    /**
     * 通道页第 4 段「本群设置」那份渲染
     */
    private static final String PUSH_SETTINGS = "sessions.js";

    /**
     * 推送页的外壳，写在 {@code index.html} 里，闭集
     * <p>
     * 左树的两个入口与树本身、窄屏那个下拉、右区、挑选面板那一摊，以及「哪几条推送配置没填完」。
     * 树上的节点与右区四段全部由脚本建出来，不写在页面里——同一件事在页面与脚本里各有一份的话，
     * 两份分叉时屏幕上不会有任何异常。
     */
    private static final List<String> PUSH_SHELL = List.of(
            "push-tree", "push-right", "push-nav", "push-default-tpl", "push-add-streamer",
            "push-drawer", "push-drawer-title", "push-drawer-lead", "push-drawer-body",
            "push-drawer-close", "sess-incomplete");

    /**
     * 上面那些里必须被脚本接上线的，闭集
     * <p>
     * {@code push-default-tpl} 不在其中：它是一个普通链接，地址写在标签上，没有脚本可接。
     */
    private static final List<String> PUSH_WIRED = List.of(
            "push-tree", "push-right", "push-nav", "push-add-streamer",
            "push-drawer", "push-drawer-title", "push-drawer-lead", "push-drawer-body",
            "push-drawer-close", "sess-incomplete");

    /**
     * 推送页自己要调的端点，闭集
     * <p>
     * 少接一条，那一块就变成一片说不出为什么空着的地方。这些端点别处也在用，
     * 因此只在 {@code push.js} 里找：拿全部脚本找的话，这一页把某条丢了也照样绿。
     * <p>
     * 后两条属于模板编辑器：改默认模板要写回服务端（{@code /templates}）并重新取一遍
     * 处理器清单（{@code /handlers}，否则树上「模板：默认／自定义」那一列还是旧的）。
     * 报告版式那张图另走一支（见 {@link #REPORT_PREVIEW_PATH}），它回的是 PNG 不是 JSON。
     */
    private static final List<String> PUSH_ENDPOINTS = List.of(
            "/state", "/push-history", "/at-all/quota",
            "/onebot/targets?type=group", "/onebot/targets?type=friend", "/onebot/targets/refresh",
            "/streamer/lookup", "/templates", "/handlers");

    /**
     * 报告版式那张图的来路
     * <p>
     * 单列一条而不是并进上面那张表：它不经 {@code api()}（那一支解 JSON，而这里回的是 PNG），
     * 因此写的是完整路径。少了它，「报告长什么样」那一段就只剩一排开关，
     * 而所见即所得的全部意义正是那张图。
     */
    private static final String REPORT_PREVIEW_PATH = "/config/api/report/preview";

    /**
     * 「本群设置」那一段要调的端点，闭集
     * <p>
     * 单条开关、成批开关、金额可见与移除订阅。成批那一支是「组开关」与「一键恢复」按下去的那一下：
     * 少了它，界面只能自己循环调单条，而中途失败会留下一半开一半关的局面。
     */
    private static final List<String> PUSH_SETTINGS_ENDPOINTS = List.of(
            "/state/command", "/state/commands", "/state/revenue", "/state/subscription");

    /**
     * 渲染那一层必须问过判法的那几件事，闭集
     */
    private static final List<String> PUSH_MODEL_CALLS = List.of(
            "pushTree(", "channelIndex(", "templateState(", "layoutState(", "noticeSwitches(",
            "commandGroups(", "commandSummary(", "recentPushes(", "atAllStatus(",
            "subscriptionSummary(", "revenueSummary(", "strandedSessions(", "channelName(",
            "templateAdoption(", "restoreDefaults(", "isDefault(",
            "previewRequestBody(", "previewRevenueCaption(");

    /**
     * 抄进渲染代码就算退步的那几条判法，闭集
     */
    private static final List<String> PUSH_MODEL_FUNCTIONS = List.of(
            "function pushTree", "function channelIndex", "function templateState",
            "function layoutState", "function commandGroups", "function commandSummary",
            "function recentPushes", "function atAllStatus",
            "function previewRequestBody", "function previewRevenueCaption");

    /**
     * 模板编辑器那份渲染
     */
    private static final String TEMPLATE_VIEW = "template.js";

    /**
     * 模板编辑器那份判法
     */
    private static final String TEMPLATE_MODEL = "template-model.js";

    /**
     * 编辑器渲染那一层必须问过判法的那几件事，闭集
     * <p>
     * 一整串模板与一张张卡之间的换算、块的增删排序、落点、能不能放、@ 那一档、
     * 以及预览里 @ 落在哪。
     */
    private static final List<String> TEMPLATE_MODEL_CALLS = List.of(
            "parseTemplate(", "toTemplateText(", "normalizeCards(", "addCard(", "removeCard(",
            "moveCard(", "insertBlock(", "moveBlock(", "splitText(", "dropIndex(",
            "blockRefusal(", "blockSpec(", "atModeOf(", "atPlan(", "previewBubbles(", "applyEdit(");

    /**
     * 抄进渲染代码就算退步的那几条判法，闭集
     */
    private static final List<String> TEMPLATE_MODEL_FUNCTIONS = List.of(
            "function parseTemplate", "function toTemplateText", "function normalizeCards",
            "function moveBlock", "function splitText", "function dropIndex",
            "function blockRefusal", "function atPlan", "function previewBubbles",
            "function applyEdit");

    /**
     * 模板编辑器上那几件事的落点，由 {@code template.js} 建出来，闭集
     * <p>
     * 通知分栏、说明、调色板、卡片列表、@ 那一档的下拉、文本形式、气泡预览与条数，
     * 以及报告版式的开关与那张图。
     */
    private static final List<String> TEMPLATE_CONTROLS = List.of(
            "tpl-tabs", "tpl-note", "tpl-palette", "tpl-cards", "tpl-at", "tpl-raw",
            "tpl-preview", "tpl-count", "rep-options", "rep-preview");

    /**
     * 模板编辑器各有落点，且块表那几条判法只有 template-model 一份
     * <p>
     * 与推送页那条同理，另外奔着三类具体的退步去：
     * <ul>
     *   <li><b>把换算与落点抄一份到渲染代码里。</b>它们由 {@code template-model.js} 现算，
     *   那一份有 node 夹具逐格在量；抄进渲染代码之后夹具照样全绿——它量的还是那份没人调的判法。</li>
     *   <li><b>把 {next} 当成一个块摆回调色板。</b>那是这一版明确撤掉的东西：分条改由
     *   「再加一条消息」表达，摆回去之后一份模板里会同时有两种分条的说法。</li>
     *   <li><b>自己判「@ 会落在哪」。</b>那条规则在发送那一侧只有一份实现
     *   （{@code PushHandlerSupport.withAtBlock}），预览必须照着它算；
     *   各判各的话，预览上少一个或多一个 @，而两种错都要等真发到群里才看得见。</li>
     * </ul>
     */
    @Test
    @DisplayName("模板编辑器各有落点，块表与 @ 的判法只有 template-model 一份")
    void templateEditorIsWiredUp() {
        Map<String, String> sources = pageSources();
        String view = sources.getOrDefault(TEMPLATE_VIEW, "");
        String model = sources.getOrDefault(TEMPLATE_MODEL, "");

        List<String> bad = new ArrayList<>();
        // 找不到那两份时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
        if (view.isBlank()) {
            bad.add("找不到 " + TEMPLATE_VIEW + "，下面每一格都无从量起");
        }
        if (model.isBlank()) {
            bad.add("找不到 " + TEMPLATE_MODEL + "，块表的判法没有落脚的地方");
        }

        for (String id : TEMPLATE_CONTROLS) {
            if (!view.contains("'" + id + "'")) {
                bad.add(TEMPLATE_VIEW + " 里没有 #" + id + "，编辑器少了这件事的落点");
            }
        }

        // 引进来的名字与用到的名字要对得上。少 import 一个不是加载期的错，
        // 是<b>那一段跑到时才抛</b>的 ReferenceError——而「保存模板」那一段恰恰是
        // 使用者改完之后才碰到的。这一条正是本笔自己踩过的那一脚
        String imported = importedFrom(view, TEMPLATE_MODEL);
        for (String call : TEMPLATE_MODEL_CALLS) {
            if (!view.contains(call)) {
                bad.add(TEMPLATE_VIEW + " 没有问过 " + call + "，那一块算的是别处的账");
                continue;
            }
            String name = call.substring(0, call.length() - 1);
            if (!imported.contains(name)) {
                bad.add(TEMPLATE_VIEW + " 用了 " + name + " 却没从 " + TEMPLATE_MODEL
                        + " 引进来，那一段跑到时会抛 ReferenceError");
            }
        }
        for (String function : TEMPLATE_MODEL_FUNCTIONS) {
            if (view.contains(function)) {
                bad.add("渲染代码里又判了一遍 " + function + "。那几条规则只许有 " + TEMPLATE_MODEL
                        + " 一份——抄一份进来之后，夹具量的还是没人调的那一份");
            }
        }

        // 分条不再是一个块：调色板由 blockSpec 现算，而它把 {next} 与两种手写 @ 挡在外面
        if (view.contains("'{next}'")) {
            bad.add(TEMPLATE_VIEW + " 里出现了 {next}。这一版把它撤了——分条改由「再加一条消息」表达，"
                    + "摆回调色板之后一份模板里会同时有两种分条的说法");
        }
        if (!model.contains("NEXT")) {
            bad.add(TEMPLATE_MODEL + " 里没有分条占位符，上面那条「渲染代码里没有」因此不作数");
        }

        assertTrue(bad.isEmpty(), "模板编辑器少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 某个文件从某个模块引进来的那一串名字
     * @param text 文件全文
     * @param module 被引的模块文件名
     * @return 花括号里那一串，没引过时为空串
     */
    private String importedFrom(String text, String module) {
        Matcher m = IMPORT.matcher(text);
        while (m.find()) {
            if (module.equals(m.group(2))) {
                return m.group(1);
            }
        }
        return "";
    }

    /**
     * 推送页各有落点，且树与摘要的判法只有 push-model 一份
     * <p>
     * 与设置页、连接页、日志页那几条同理：元素与接线缺哪一半都不会报错——元素没了，
     * 脚本按 id 取到 null；脚本没接上，控件就静静地立在那里，点它什么也不发生。
     * <p>
     * 🔴 后半截奔着两类具体的退步去：
     * <ul>
     *   <li><b>把树与摘要那几条判法抄一份到渲染代码里。</b>它们由 {@code push-model.js} 现算，
     *   那一份有 node 夹具逐档在量；抄进渲染代码之后，夹具照样全绿——它量的还是那份没人调的判法，
     *   而屏幕上跑的是新抄的这一份。</li>
     *   <li><b>自己判「这个会话的菜单里列不列这条命令」。</b>那条规则在命令那一侧只有一份实现
     *   （{@code availableIn}），结论由 {@code /api/state} 的 {@code menuHidden} 带过来。
     *   在界面上再判一遍的话，改了那一份的那天控制台仍按旧规矩画，而两边的代码看起来都对。</li>
     * </ul>
     * 两种改动<b>都不会让任何功能变坏</b>，因此靠人复查是拦不住的。
     */
    @Test
    @DisplayName("推送页左树、四段与本群设置各有落点，树与摘要的判法只有 push-model 一份")
    void pushPageIsWiredUp() throws IOException {
        Map<String, String> pages = pageSources();
        Map<String, String> sources = sources();
        String scripts = String.join("\n", sources.values());
        String view = pages.getOrDefault(PUSH_VIEW, "");
        String settings = pages.getOrDefault(PUSH_SETTINGS, "");

        List<String> bad = new ArrayList<>();
        // 找不到那两份渲染时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
        if (view.isBlank()) {
            bad.add("找不到 " + PUSH_VIEW + "，下面每一格都无从量起");
        }
        if (settings.isBlank()) {
            bad.add("找不到 " + PUSH_SETTINGS + "，「本群设置」那一段无从量起");
        }
        if (!sources.containsKey(PUSH_MODEL)) {
            bad.add("找不到 " + PUSH_MODEL + "，树与摘要的判法没有落脚的地方");
        }

        for (String id : PUSH_SHELL) {
            if (!view.contains("id=\"" + id + "\"")) {
                bad.add(PUSH_VIEW + " 没有建出 #" + id);
            }
        }
        for (String id : PUSH_WIRED) {
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }

        for (String endpoint : PUSH_ENDPOINTS) {
            if (!view.contains("'" + endpoint + "'")) {
                bad.add(PUSH_VIEW + " 没有调用 " + endpoint + "，那一块此刻空着而不说为什么");
            }
        }
        if (!view.contains("'" + REPORT_PREVIEW_PATH + "'")) {
            bad.add(PUSH_VIEW + " 没有调用 " + REPORT_PREVIEW_PATH
                    + "，「报告长什么样」那一段就只剩一排开关，而所见即所得的意义正是那张图");
        }
        for (String endpoint : PUSH_SETTINGS_ENDPOINTS) {
            if (!settings.contains("'" + endpoint + "'")) {
                bad.add(PUSH_SETTINGS + " 没有调用 " + endpoint + "，那一项此刻改了不生效");
            }
        }

        for (String call : PUSH_MODEL_CALLS) {
            if (!view.contains(call)) {
                bad.add(PUSH_VIEW + " 没有问过 " + call + "，那一块画的是别处算的");
            }
        }
        for (String function : PUSH_MODEL_FUNCTIONS) {
            if (view.contains(function) || settings.contains(function)) {
                bad.add("渲染代码里又判了一遍 " + function + "。那几条规则只许有 " + PUSH_MODEL
                        + " 一份——抄一份进来之后，夹具量的还是没人调的那一份");
            }
        }

        // 「菜单里列不列」的结论只许被判法读一次：出现在渲染代码里，就是又判了一遍
        for (String name : List.of(PUSH_VIEW, PUSH_SETTINGS)) {
            if (sources.getOrDefault(name, "").contains("menuHidden")) {
                bad.add(name + " 自己读了 menuHidden。这个会话的菜单里列哪几条由命令那一侧算，"
                        + "结论经 " + PUSH_MODEL + " 一处消费");
            }
        }
        if (!sources.getOrDefault(PUSH_MODEL, "").contains("menuHidden")) {
            bad.add(PUSH_MODEL + " 没有读 menuHidden，上面那条「渲染代码里没有」因此不作数");
        }

        if (!view.contains("resolveTarget(")) {
            bad.add(PUSH_VIEW + " 没有经过 resolveTarget 认目标。填错一位数不会有任何报错，"
                    + "消息只是发去了别处，而挑选面板存在的意义正是把那种错拦在配置阶段");
        }

        bad.addAll(pushPageHasNoFreeTextField(view));

        assertTrue(bad.isEmpty(), "推送页少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 推送页上不许有手填号码的格子
     * <p>
     * 与连接页那条同族，奔着同一类退步去：<b>把手填群号的格子加回来</b>。填错一位数不会有
     * 任何报错，消息只是发去了别处；而「配好了群里没动静」的另外几类错（Token 不对、
     * 机器人被踢出群、OneBot 没起）表现完全一样，混在一起就再也分不开。
     * <p>
     * 量的是 {@code push.js} 里 {@code render} 建出的外壳：挑选面板由脚本另建，里面那个
     * 「uid、直播间号或链接」是主播的账号，不是推送目标的号码，两者不是一回事。
     * @param view push.js 全文
     * @return 问题，没有则为空
     */
    private List<String> pushPageHasNoFreeTextField(String view) {
        List<String> bad = new ArrayList<>();

        String block = functionBody(view, "render");
        if (block.isBlank()) {
            bad.add(PUSH_VIEW + " 里找不到 render，推送页外壳无从量起");
            return bad;
        }
        if (block.contains("<input")) {
            bad.add("推送页外壳上出现了输入框。推送目标只能从机器人自己给的名单里挑——"
                    + "手填时填错一位数不会有任何报错，消息只是发去了别处");
        }
        // 阴性对照：这一格得能分辨。没有这一句的话，那一块整个被删掉也照样「不含输入框」
        if (!block.contains("<select")) {
            bad.add("推送页上没有窄屏那个下拉，上面那条「不含输入框」因此不作数");
        }

        return bad;
    }

    /**
     * 主播页那份判法
     */
    private static final String STREAMERS_MODEL = "streamers-model.js";

    /**
     * 主播页那份渲染
     */
    private static final String STREAMERS_VIEW = "streamers.js";

    /**
     * 主播页三块子视图的外壳，由页脚本自己建，闭集
     * <p>
     * 列表、详情、场次详情三块共用一个页容器 {@code #page-streamers}——它们是同一页的三种样子，
     * 地址都是 {@code #/streamers} 底下的。分成三个 {@code .page} 容器的话，路由那一侧
     * 认页只看第一段，后两块永远不会被显示出来，而三处的代码看起来都对。
     * <p>
     * 外壳与每一块里的内容都由脚本建出来，不写在核心的 {@code index.html} 里。
     */
    private static final List<String> STREAMERS_SHELL = List.of(
            "sv-list", "st-all", "st-list", "st-unlisted",
            "sv-detail", "sd-back", "sd-head", "sd-tabs", "sd-body",
            "sv-session", "sx-back", "sx-title", "sx-body");

    /**
     * 主播页自己要调的端点，闭集
     * <p>
     * 少接一条，那一块就变成一片说不出为什么空着的地方。这些端点别处也在用，
     * 因此只在 {@code streamers.js} 里找：拿全部脚本找的话，这一页把某条丢了也照样绿。
     */
    private static final List<String> STREAMERS_ENDPOINTS = List.of("/streamers", "/status");

    /**
     * 渲染那一层必须问过判法的那几件事，闭集
     */
    private static final List<String> STREAMERS_MODEL_CALLS = List.of(
            "parseStreamersHash(", "statusChip(", "sparkline(", "seriesValues(", "peakCell(",
            "gapCells(", "totalDataBanner(", "snapshotRows(", "detailHash(", "sessionHash(",
            "reportPath(", "reportView(", "pageBar(", "barGeometry(");

    /**
     * 抄进渲染代码就算退步的那几条判法，闭集
     */
    private static final List<String> STREAMERS_MODEL_FUNCTIONS = List.of(
            "function parseStreamersHash", "function statusChip", "function sparkline",
            "function peakCell", "function gapCells", "function totalDataBanner",
            "function snapshotRows", "function barGeometry");

    /**
     * 主播页三块各有落点，且状态标、折线、人气峰那几条判法只有 streamers-model 一份
     * <p>
     * 与设置页、连接页、日志页、推送页那几条同理：元素与接线缺哪一半都不会报错——元素没了，
     * 脚本按 id 取到 null；脚本没接上，那一块就静静地空着。
     * <p>
     * 🔴 后半截奔着两类具体的退步去：
     * <ul>
     *   <li><b>把地址解析、状态标、人气峰那几条判法抄一份到渲染代码里。</b>它们由
     *   {@code streamers-model.js} 现算，那一份有 node 夹具逐格在量；抄进渲染代码之后，
     *   夹具照样全绿——它量的还是那份没人调的判法，而屏幕上跑的是新抄的这一份。</li>
     *   <li><b>自己判「这台机器开没开累计数据」。</b>那一条与首页那条软待办问的是同一件事，
     *   判定只许有 {@code home-model.js} 的 {@code totalDataOff} 一份：各判各的那天，
     *   首页说没开而这一页说开着，两边的代码看起来都对。</li>
     * </ul>
     * 两种改动<b>都不会让任何功能变坏</b>，因此靠人复查是拦不住的。
     */
    @Test
    @DisplayName("主播页列表、详情、场次三块各有落点，状态标与折线的判法只有 streamers-model 一份")
    void streamersPageIsWiredUp() throws IOException {
        Map<String, String> pages = pageSources();
        Map<String, String> sources = sources();
        String scripts = String.join("\n", sources.values());
        String view = pages.getOrDefault(STREAMERS_VIEW, "");
        String model = pages.getOrDefault(STREAMERS_MODEL, "");

        List<String> bad = new ArrayList<>();
        // 找不到那两份时判红而不是跳过：一把量不动却报绿的判据，比没有这把判据更糟
        if (view.isBlank()) {
            bad.add("找不到 " + STREAMERS_VIEW + "，下面每一格都无从量起");
        }
        if (model.isBlank()) {
            bad.add("找不到 " + STREAMERS_MODEL + "，状态标与折线的判法没有落脚的地方");
        }

        for (String id : STREAMERS_SHELL) {
            if (!view.contains("id=\"" + id + "\"")) {
                bad.add(STREAMERS_VIEW + " 没有建出 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }

        for (String endpoint : STREAMERS_ENDPOINTS) {
            if (!view.contains("'" + endpoint + "'")) {
                bad.add(STREAMERS_VIEW + " 没有调用 " + endpoint + "，那一块此刻空着而不说为什么");
            }
        }

        for (String call : STREAMERS_MODEL_CALLS) {
            if (!view.contains(call)) {
                bad.add(STREAMERS_VIEW + " 没有问过 " + call + "，那一块画的是别处算的");
            }
        }
        for (String function : STREAMERS_MODEL_FUNCTIONS) {
            if (view.contains(function)) {
                bad.add("渲染代码里又判了一遍 " + function + "。那几条规则只许有 " + STREAMERS_MODEL
                        + " 一份——抄一份进来之后，夹具量的还是没人调的那一份");
            }
        }

        // 「这台机器开没开累计数据」的判定只许有一份，且必须是首页那一份
        if (view.contains("totalDataAvailable")) {
            bad.add(STREAMERS_VIEW + " 自己读了 totalDataAvailable。这一条与首页那条软待办"
                    + "问的是同一件事，判定经 home-model.js 的 totalDataOff 一处消费");
        }
        if (!model.contains("totalDataOff")) {
            bad.add(STREAMERS_MODEL + " 没有用 totalDataOff，上面那条「渲染代码里没有」因此不作数");
        }

        if (!view.contains("detailHash(where.platform, where.uid, 'sessions')")) {
            bad.add("场次详情的返回必须用 detailHash 指回该主播的场次页签；"
                    + "写死 #/streamers 会回到总列表，人找不到刚才那一场属于谁");
        }

        assertTrue(bad.isEmpty(), "主播页少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 主播页已随控制台插件走，核心界面目录里不许再留那两份脚本
     * <p>
     * 留着的话，{@code /assets/{name}} 会先取核心那一份，插件申报同名脚本就会被登记关口丢掉，
     * 侧栏入口也永远是核心写死的那一条——插件卸掉之后入口还在，点开是空的。
     */
    @Test
    @DisplayName("核心 config-ui 目录不得再有 streamers.js／streamers-model.js")
    void coreConfigUiMustNotShipStreamersPage() {
        Path ui = frontendDir();
        List<String> leftover = new ArrayList<>();
        for (String name : List.of("streamers.js", "streamers-model.js")) {
            if (Files.exists(ui.resolve(name))) {
                leftover.add(name);
            }
        }
        assertTrue(leftover.isEmpty(),
                "主播页已随控制台插件走，核心 config-ui 里不该再有: " + leftover);
    }

    /**
     * 推送整套已随控制台插件走，核心界面目录里不许再留那五份脚本
     * <p>
     * 留着的话，{@code /assets/{name}} 会先取核心那一份，插件申报同名脚本就会被登记关口丢掉，
     * 侧栏入口也永远是核心写死的那一条——插件卸掉之后入口还在，点开是空的。
     */
    @Test
    @DisplayName("核心 config-ui 目录不得再有 push.js／push-model.js／sessions.js／template.js／template-model.js")
    void coreConfigUiMustNotShipPushPages() {
        Path ui = frontendDir();
        List<String> leftover = new ArrayList<>();
        for (String name : List.of("push.js", "push-model.js", "sessions.js", "template.js", "template-model.js")) {
            if (Files.exists(ui.resolve(name))) {
                leftover.add(name);
            }
        }
        assertTrue(leftover.isEmpty(),
                "推送页已随控制台插件走，核心 config-ui 里不该再有: " + leftover);
    }

    /**
     * 向导「主播」步已随控制台插件走，核心那份渲染里不许再留它
     * <p>
     * 这一步要 uid、要查主播、要往 {@code /datasource} 写一位主播——三件都是产品形态，
     * 不是壳。留在核心的表现不是报错：它照常跑，只是<b>核心得先认识推送页那几个导出</b>
     * （{@code renderStreamers}／{@code serializePush}／{@code STREAMER_INPUT_HINT}），
     * 于是卸掉控制台插件之后这一页在 import 那一行就断了，屏幕上只剩一句「载入失败」。
     * <p>
     * 连着量六样：两句 import、那一步本身、三个借来的符号、以及它独用的两条端点。
     * 只量 import 的话，把 import 改成动态取而正文照抄一份仍然绿。
     */
    @Test
    @DisplayName("核心 setup.js 不再引推送页，也不再自带主播步")
    void coreSetupViewNoLongerCarriesStreamerStep() throws IOException {
        String setup = Files.readString(frontendDir().resolve("setup.js"), StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();

        for (String imported : List.of("./push.js", "./streamers-model.js")) {
            if (setup.contains("from '" + imported + "'")) {
                bad.add("setup.js 仍 import " + imported + "，那是控制台插件的页，核心卸得掉它才算搬走");
            }
        }
        if (setup.contains("stepStreamer")) {
            bad.add("setup.js 里仍有 stepStreamer，主播步该由 SETUP_STEP 槽的插件页画");
        }
        for (String symbol : List.of("renderStreamers", "serializePush", "STREAMER_INPUT_HINT", "detailHash")) {
            if (setup.contains(symbol)) {
                bad.add("setup.js 仍用着插件页的 " + symbol + "，那一步没搬干净");
            }
        }
        for (String endpoint : List.of("/streamer/lookup", "/datasource")) {
            if (setup.contains("'" + endpoint + "'")) {
                bad.add("setup.js 仍直接调 " + endpoint + "，这两条是主播步独用的，随它走");
            }
        }

        assertTrue(bad.isEmpty(), "向导主播步没搬干净:\n  " + String.join("\n  ", bad));
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
                String imported = m.group(2);
                if (pages.contains(imported)) {
                    // 豁免一条也没有了：向导主播步搬进控制台插件之后，
                    // 核心的界面文件里不再有任何一句指向插件页的 import
                    bad.add(name + " 静态引用了插件页 " + imported);
                }
            }
        });

        assertTrue(bad.isEmpty(), "插件页只能在运行时按注册清单装载:\n  " + String.join("\n  ", bad));
    }

    /**
     * 首页链路「本机」站的去处，闭集
     * <p>
     * 它必须落到本页健康自检那一张卡，且连接页<b>不加</b>一张与它对应的卡。
     * 写成去连接页的话，地址栏变了、屏幕没动，看起来像页面卡住了——
     * 而那种错在任何一次「打开页面看一眼」里都看不出来，因为连接页本身完全正常。
     */
    @Test
    @DisplayName("本机站落到首页探针区，连接页不加卡")
    void selfStationGoesToHomeProbes() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        Map<String, String> sources = coreSources();
        String home = sources.getOrDefault("home-model.js", "");
        String overview = sources.getOrDefault("overview.js", "");
        String links = sources.getOrDefault("links-model.js", "");
        String main = sources.getOrDefault("main.js", "");

        List<String> bad = new ArrayList<>();
        if (!html.contains("id=\"home-probes\"")) {
            bad.add("index.html 上没有 #home-probes，本机站没有可滚到的那一块");
        }
        if (!home.contains("'#/home?card=probes'")) {
            bad.add("home-model.js 没有本机站的首页探针落点");
        }
        if (!home.contains("PROBE_ANCHOR")) {
            bad.add("home-model.js 没有探针区锚的名字，上面那条落点没有东西可对");
        }
        if (!overview.contains("stationHref(")) {
            bad.add("overview.js 没有问过 stationHref，链路图上点下去走的是别处的账");
        }
        if (!main.contains("PROBE_ANCHOR")) {
            bad.add("main.js 没有按探针区锚去滚，从收藏夹打开 #/home?card=probes 就停在页顶");
        }

        String probeScroll = "$('#' + PROBE_ANCHOR)";
        int probeScrolls = 0;
        for (String text : sources.values()) {
            int from = 0;
            while ((from = text.indexOf(probeScroll, from)) >= 0) {
                probeScrolls++;
                from += probeScroll.length();
            }
        }
        if (probeScrolls != 1) {
            bad.add("探针区锚的滚写应只在 focusCard 一处，现在 " + probeScrolls + " 处");
        }
        if (overview.contains(probeScroll)) {
            bad.add("overview.js 仍在滚探针区锚，与 focusCard 重复");
        }

        Matcher stationCard = Pattern.compile("const STATION_CARD\\s*=\\s*\\{([^}]*)}").matcher(links);
        if (!stationCard.find()) {
            bad.add("links-model.js 里找不到 STATION_CARD，下面那条「连接页不加卡」无从量起");
        } else if (stationCard.group(1).contains("self")) {
            bad.add("连接页给本机站加了卡。本机讲的是这台机器自己的状况，不是一条对外连接");
        }

        assertTrue(bad.isEmpty(), "本机站的去处不对:\n  " + String.join("\n  ", bad));
    }

    /**
     * 设置页、推送页与通道页的危险确认走自绘弹层，不再调用原生 confirm()
     * <p>
     * 原生那一句没有标题、没有后果、也没有取消／确认的颜色区分。
     * 改回去的那一下<b>不会让任何功能变坏</b>，因此靠人复查是拦不住的。
     * <p>
     * 自绘之后浏览器不再代劳两件事：按 Esc 取消，以及关掉以后把焦点还回刚才那颗按钮。
     * 少写这两行，键盘和读屏都回不到原点，而点鼠标走主路的人看不出任何变化。
     */
    @Test
    @DisplayName("设置页、推送页与通道页的危险确认走自绘弹层")
    void settingsPushAndSessionsUsePaintedConfirm() {
        Map<String, String> sources = sources();
        List<String> bad = new ArrayList<>();

        for (String name : List.of("settings.js", "push.js", "sessions.js")) {
            String code = codeOnly(sources.getOrDefault(name, ""));
            if (code.contains("confirm(")) {
                bad.add(name + " 仍在调用原生 confirm()");
            }
            if (!code.contains("ask(")) {
                bad.add(name + " 没有问过 ask，危险确认此刻点了不弹");
            }
        }

        String dialog = sources.getOrDefault("confirm.js", "");
        String model = sources.getOrDefault("confirm-model.js", "");
        if (dialog.isBlank()) {
            bad.add("找不到 confirm.js，自绘弹层没有落脚的地方");
        }
        if (model.isBlank()) {
            bad.add("找不到 confirm-model.js，打开／取消／确认没有可测的一份");
        }
        if (!dialog.contains("export function ask") && !dialog.contains("export function ask(")) {
            // export function ask 已在上面的 EXPORT 扫描里；这里再钉调用方引的就是这个名字
            if (!Pattern.compile("^export\\s+function\\s+ask\\b", Pattern.MULTILINE).matcher(dialog).find()) {
                bad.add("confirm.js 没有 export ask");
            }
        }
        if (!dialog.contains("Escape")) {
            bad.add("confirm.js 没有 Esc＝取消");
        }
        if (!dialog.contains("document.activeElement")) {
            bad.add("confirm.js 打开时没有记下触发钮，关闭后焦点回不去");
        }
        if (!model.contains("export function keydown")) {
            bad.add("confirm-model.js 没有 keydown，Esc＝取消没有可测的一份");
        }

        for (String name : List.of("settings.js", "push.js", "sessions.js")) {
            String imported = importedFrom(sources.getOrDefault(name, ""), "confirm.js");
            if (!imported.contains("ask")) {
                bad.add(name + " 用了 ask 却没从 confirm.js 引进来");
            }
        }

        assertTrue(bad.isEmpty(), "危险确认弹层少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 其余六处危险确认同样走自绘弹层，核心与插件页里原生 confirm() 的调用钉在 0
     * <p>
     * 改回去同样不会让任何功能变坏。六处是接线不是新判法，所以这一格走静态扫描，
     * 不另起一份纯模型夹具——打开／取消／确认那一份已经在量弹层本身。
     */
    @Test
    @DisplayName("其余危险确认也不再调用原生 confirm")
    void remainingDangerConfirmsUsePaintedDialog() {
        Map<String, String> sources = sources();
        List<String> bad = new ArrayList<>();

        for (String name : List.of("main.js", "settings-auth.js", "setup.js", "passkeys.js", "tokens.js")) {
            String text = sources.getOrDefault(name, "");
            if (text.isBlank()) {
                bad.add(name + " 找不到");
                continue;
            }
            String code = codeOnly(text);
            if (code.contains("confirm(")) {
                bad.add(name + " 仍在调用原生 confirm()");
            }
            if (!code.contains("ask(")) {
                bad.add(name + " 没有问过 ask，危险确认此刻点了不弹");
            }
            if (!importedFrom(text, "confirm.js").contains("ask")) {
                bad.add(name + " 用了 ask 却没从 confirm.js 引进来");
            }
        }

        sources.forEach((name, text) -> {
            if (codeOnly(text).contains("confirm(")) {
                bad.add(name + " 仍有原生 confirm() 调用");
            }
        });

        assertTrue(bad.isEmpty(), "其余危险确认少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 刚装好的机器打开控制台应落到初始设置，判法是五步全没做
     * <p>
     * 旧写法看 {@code /auth/state} 的 {@code setupDone}（配置文件在不在）。同意使用协议
     * 会把 {@code agreement:} 写进 application.yml，文件一旦存在这一位就变成 true，
     * 人就停在空首页。改回去同样不会让任何功能变坏——首页照样能打开，只是刚装好的
     * 机器少被领到该去的那一页。
     */
    @Test
    @DisplayName("从没配过的机器进首页转到初始设置，判法是五步全没做")
    void blankMachineHomeRedirectsToSetupUsingSetupSteps() {
        Map<String, String> sources = coreSources();
        String main = sources.getOrDefault("main.js", "");
        String model = sources.getOrDefault("home-model.js", "");
        List<String> bad = new ArrayList<>();

        if (model.isBlank()) {
            bad.add("找不到 home-model.js，五步的判法没有落脚的地方");
        } else if (!model.contains("function shouldOpenSetup")) {
            bad.add("home-model.js 没有 shouldOpenSetup。进首页该不该转到初始设置必须问这一份");
        }

        if (main.isBlank()) {
            bad.add("找不到 main.js，进首页的跳转没有落脚的地方");
        } else {
            if (!importedFrom(main, "home-model.js").contains("shouldOpenSetup")) {
                bad.add("main.js 没有从 home-model.js 引进 shouldOpenSetup");
            }
            if (!codeOnly(main).contains("shouldOpenSetup(")) {
                bad.add("main.js 没有问过 shouldOpenSetup，进首页的跳转此刻仍看配置文件在不在");
            }
            if (codeOnly(main).contains("store.setupDone === false")) {
                bad.add("main.js 仍用 store.setupDone === false 决定跳转。"
                        + "同意协议会写出配置文件，这一位变成 true，人就停在首页");
            }
        }

        assertTrue(bad.isEmpty(), "首装进首页的跳转少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 首页三份回包与 Webhook 待办的落点，闭集
     * <p>
     * 告警已配没配的判定只许有 {@code home-model.js} 一份。渲染那一层再判一遍的话，
     * 夹具照样全绿——它量的还是没人调的那一份，而屏幕上跑的是新抄的这一份。
     * <p>
     * 额度那一条不在这张表上：「今日」卡与它背后的 {@code /at-all/quota} 已随控制台插件走，
     * 核心这一侧再列它就成了「核心必须调一条插件的端点」，而那正是搬家要去掉的东西。
     * 反过来量：额度那两个判法<b>不许</b>再出现在核心界面里。
     */
    private static final List<String> HOME_ENDPOINTS = List.of(
            "/status", "/login", "/timeline?date=");

    private static final List<String> HOME_MODEL_FUNCTIONS = List.of("function alertConfigured");

    /**
     * 随「今日」卡搬走的那几位，核心界面里一处也不许再有
     */
    private static final List<String> MOVED_TO_TODAY_CARD = List.of(
            "atAllTile", "todayAtAllMarkup", "at-all/quota", "toggle-push", "push-hint", "today-stats");

    @Test
    @DisplayName("首页三份回包与告警待办落到设置页告警段，判法只有 home-model 一份；今日卡那几位已搬空")
    void homeTodayTileAndWebhookTodoAreWired() throws IOException {
        Map<String, String> sources = coreSources();
        String view = sources.getOrDefault("overview.js", "");
        String model = sources.getOrDefault("home-model.js", "");
        String main = sources.getOrDefault("main.js", "");
        String settings = sources.getOrDefault("settings.js", "");

        List<String> bad = new ArrayList<>();
        if (view.isBlank()) {
            bad.add("找不到 overview.js，下面每一格都无从量起");
        }
        if (model.isBlank()) {
            bad.add("找不到 home-model.js，额度格与告警待办的判法没有落脚的地方");
        }

        for (String endpoint : HOME_ENDPOINTS) {
            if (!view.contains("'" + endpoint + "'") && !view.contains("\"" + endpoint + "\"")) {
                // timeline 那一支带 today()，字面量是 '/timeline?date='
                if (!view.contains(endpoint)) {
                    bad.add("overview.js 没有调用 " + endpoint + "，那一格此刻空着而不说为什么");
                }
            }
        }

        if (!model.contains("'#/settings?card=alert'")) {
            bad.add("home-model.js 没有设置页告警段的落点");
        }
        if (!main.contains("focusGroup(")) {
            bad.add("main.js 没有问过 focusGroup，从待办点进设置页会停在页顶");
        }
        if (!settings.contains("export function focusGroup")
                && !Pattern.compile("^export\\s+function\\s+focusGroup\\b", Pattern.MULTILINE)
                .matcher(settings).find()) {
            bad.add("settings.js 没有 export focusGroup，上面那条「main.js 问过」因此不作数");
        }

        for (String function : HOME_MODEL_FUNCTIONS) {
            if (!model.contains(function)) {
                bad.add("home-model.js 没有 " + function + "，夹具量的那一份不存在");
            }
            if (view.contains(function)) {
                bad.add("渲染代码里又判了一遍 " + function + "。那几条规则只许有 home-model.js 一份");
            }
        }

        // 「今日」卡随控制台插件走：核心这三份界面件里一处也不该再提它。
        // 留一处的表现不是报错——那一段代码照常跑，只是它取的元素已经不在核心的页面上了
        for (String name : List.of("home-model.js", "overview.js", "main.js")) {
            String text = sources.getOrDefault(name, "");
            for (String moved : MOVED_TO_TODAY_CARD) {
                if (text.contains(moved)) {
                    bad.add(name + " 里仍有「今日」卡的 " + moved + "，那张卡已随控制台插件走");
                }
            }
        }

        assertTrue(bad.isEmpty(), "首页那几份回包与 Webhook 待办少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 视图模型一律不写死推送平台标识
     * <p>
     * 显示名由适配器在运行期自报、经接口下发。界面文件里写死一份映射，
     * 装第二套推送平台的那天前缀就会对不上，而对不上的方向是把接口标识直接画到屏幕上。
     * <p>
     * 量的是全部 {@code *-model.js}，不点名某一份：会写死这种映射的那一份，
     * 正是此刻还没搬到、或者明天才新写的那一份。「今日」卡的平台前缀就从核心
     * 搬去了控制台插件——按名字点的判据会跟着它一起变成空跑，而空跑与全绿长得一样。
     */
    @Test
    @DisplayName("视图模型不写死推送平台标识")
    void viewModelsDoNotHardcodePushPlatformId() {
        Map<String, String> models = new LinkedHashMap<>();
        sources().forEach((name, text) -> {
            if (name.endsWith("-model.js")) {
                models.put(name, text);
            }
        });
        assertFalse(models.isEmpty(), "一份 *-model.js 也没找到，这条规矩没有落脚的地方");

        List<String> hits = new ArrayList<>();
        models.forEach((name, text) -> {
            if (text.contains("qq-onebot")) {
                hits.add(name);
            }
        });
        assertEquals(List.of(), hits, "视图模型源码不得出现 qq-onebot，显示名由接口下发；命中: " + hits);
    }

    /**
     * 新版提示写在 index.html 里的那两处落点，闭集
     * <p>
     * 侧栏那枚药丸与它点开的小面板的壳。面板里的内容不写在页面里：每次拿到新的
     * 运行状态都整块重画，写死的那份会在下一次重画时悄悄变成上一版的信息。
     */
    private static final List<String> UPDATE_NOTICE_IN_HTML = List.of("side-update", "update-pop");

    /**
     * 新版提示由脚本建出来的那一处落点，闭集
     * <p>
     * 「知道了，这版先不提醒」。它跟着面板内容一起重画，因此不写在 index.html 里。
     */
    private static final List<String> UPDATE_NOTICE_BUILT = List.of("update-skip");

    /**
     * 新版提示要调的端点，闭集
     * <p>
     * 「先不提醒」记在服务器上、按版本记。少接这一条，那个键就是点了没反应，
     * 而按钮本身看起来完全正常——使用者会以为提醒已经关掉，第二天药丸照样出现。
     */
    private static final List<String> UPDATE_NOTICE_ENDPOINTS = List.of("/version/skip");

    /**
     * 新版药丸与小面板各有落点，「先不提醒」接到了服务器
     * <p>
     * 与设置页、连接页那几条同理：元素与接线缺哪一半都不会报错——元素没了，脚本按
     * id 取到 null（那一条由 {@link #everyReferencedElementIdExists} 管）；脚本没接上，
     * 药丸就静静地立在那里，点了什么都不发生。
     */
    @Test
    @DisplayName("新版药丸、小面板与先不提醒各有落点")
    void updateNoticeIsWiredUp() throws IOException {
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        String scripts = String.join("\n", coreSources().values());

        List<String> bad = new ArrayList<>();
        for (String id : UPDATE_NOTICE_IN_HTML) {
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本用到 #" + id + "，它立在那里但点了不管用");
            }
        }
        for (String id : UPDATE_NOTICE_BUILT) {
            if (!scripts.contains("id=\"" + id + "\"")) {
                bad.add("没有任何脚本建出 #" + id);
            }
            if (!scripts.contains("$('#" + id + "')")) {
                bad.add("没有任何脚本取过 #" + id + "，那个键点了没有任何东西接");
            }
        }
        for (String endpoint : UPDATE_NOTICE_ENDPOINTS) {
            if (!scripts.contains("'" + endpoint + "'")) {
                bad.add("没有任何脚本调用 " + endpoint + "，「先不提醒」此刻点了不管用");
            }
        }

        assertTrue(bad.isEmpty(), "新版提示少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 主播页两级子路由与日志页工程日志子路由，闭集
     * <p>
     * 顶层路由由 {@link #CORE_ROUTES} 管。子路由是同一页容器底下的第 2/3 段：
     * 主播页 {@code /streamers/{平台}/{uid}} 与 {@code /{开播时刻}}、日志页 {@code #/log/eng}。
     * 解析从模型里那份表认，界面文件里写出的子路径也必须在表上——各写各的话，
     * 新开一条子路由只改了地址拼法、解析仍退回列表或时间线，屏幕上像页面卡住了。
     * 主播侧闭集由 {@code STREAMER_VIEWS} 与 {@code parseStreamersHash}／
     * {@code detailHash}／{@code sessionHash} 同表守：地址第二段是平台名，
     * 不能拿它去对子视图表。
     */
    @Test
    @DisplayName("主播页与日志页的子路由是闭集，解析从同一份表认")
    void subRoutesAreClosedSet() throws IOException {
        Map<String, String> sources = sources();
        String streamers = sources.getOrDefault(STREAMERS_MODEL, "");
        String logs = sources.getOrDefault("log-model.js", "");
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);

        List<String> bad = new ArrayList<>();
        List<String> views = exportedStringArray(streamers, "STREAMER_VIEWS");
        List<String> logSubs = exportedStringArray(logs, "LOG_SUBROUTES");

        if (views.isEmpty()) {
            bad.add(STREAMERS_MODEL + " 没有导出 STREAMER_VIEWS，子视图闭集没有落脚的地方");
        } else {
            for (String need : List.of("list", "detail", "session")) {
                if (!views.contains(need)) {
                    bad.add("STREAMER_VIEWS 里没有 " + need);
                }
            }
        }
        if (logSubs.isEmpty()) {
            bad.add("log-model.js 没有导出 LOG_SUBROUTES，工程日志这条子路由没有落脚的地方");
        } else if (!logSubs.contains("eng")) {
            bad.add("LOG_SUBROUTES 里没有 eng");
        }

        String parseStreamers = functionBody(streamers, "parseStreamersHash");
        if (!parseStreamers.contains("STREAMER_VIEWS")) {
            bad.add("parseStreamersHash 没有问过 STREAMER_VIEWS，解析与闭集不是同一份");
        }
        String parseLog = functionBody(logs, "parseLogHash");
        if (!parseLog.contains("LOG_SUBROUTES")) {
            bad.add("parseLogHash 没有问过 LOG_SUBROUTES，解析与闭集不是同一份");
        }
        String writeLog = functionBody(logs, "logHash");
        if (!writeLog.contains("LOG_SUBROUTES")) {
            bad.add("logHash 没有问过 LOG_SUBROUTES，写出的地址与闭集不是同一份");
        }

        String detail = functionBody(streamers, "detailHash");
        if (!detail.contains("'#/streamers/'") || !detail.contains("encodeURIComponent(platform)")
                || !detail.contains("encodeURIComponent(uid)")) {
            bad.add("detailHash 没有拼出 /streamers/{平台}/{uid}");
        }
        String session = functionBody(streamers, "sessionHash");
        if (!session.contains("'#/streamers/'") || !session.contains("encodeURIComponent(start)")) {
            bad.add("sessionHash 没有拼出 /{开播时刻} 这一段");
        }

        String haystack = html + "\n" + String.join("\n", sources.values());
        Matcher named = Pattern.compile("#/log/([A-Za-z][A-Za-z0-9_-]*)").matcher(haystack);
        while (named.find()) {
            if (!logSubs.contains(named.group(1))) {
                bad.add("界面里出现了未登记的日志子路由 #/log/" + named.group(1));
            }
        }

        Set<String> boundViews = new LinkedHashSet<>();
        Matcher destructure = Pattern.compile("\\[([^]]+)]\\s*=\\s*STREAMER_VIEWS").matcher(parseStreamers);
        if (destructure.find()) {
            for (String part : destructure.group(1).split(",")) {
                String ident = part.trim();
                if (!ident.isEmpty()) {
                    boundViews.add(ident);
                }
            }
        }
        int viewLitHits = 0;
        Matcher viewLit = Pattern.compile("(?:view:\\s*|\\.view\\s*=\\s*)(?:'([^']+)'|(view[A-Z][A-Za-z0-9]*))").matcher(parseStreamers);
        while (viewLit.find()) {
            viewLitHits++;
            String lit = viewLit.group(1);
            String ident = viewLit.group(2);
            if (lit != null && !views.contains(lit)) {
                bad.add("parseStreamersHash 写出了未登记的子视图 " + lit);
            }
            if (ident != null && !boundViews.contains(ident)) {
                bad.add("parseStreamersHash 写出了未登记的子视图 " + ident);
            }
        }
        if (viewLitHits == 0) {
            bad.add("parseStreamersHash 的子视图扫描 0 命中，写法已变而扫描没跟上");
        }

        assertTrue(bad.isEmpty(), "主播页与日志页的子路由闭集对不上:\n  " + String.join("\n  ", bad));
        assertFalse(views.isEmpty() || logSubs.isEmpty(), "上面那条「闭集在」因此不作数");
    }

    /**
     * 工程日志「画上去」与「复制走」问的是同一份可见性判定
     * <p>
     * 屏幕上显示哪几段是一条判定：分段之后按四档开关与搜索词筛。这条判定<b>只许有一份</b>——
     * 画一处、复制时再自己筛一遍的话，两份会各自漂，而漂开的表现是
     * <b>剪贴板里那一份从来没在屏幕上出现过</b>：使用者把它贴给别人排障，
     * 两边看的不是同一份日志，且没有任何一侧会报错。
     * <p>
     * 判定本身算得对不对由 {@link LogModelTest} 拉起的夹具喂值跑；这一格只钉接线——
     * 那把夹具量的是 {@code engSegments} 这个函数，而调用点改回自己拼时它照样全绿。
     */
    @Test
    @DisplayName("工程日志画与复制走同一份可见性判定")
    void engineeringLogCopyPaintsWhatIsOnScreen() {
        String source = codeOnly(coreSources().getOrDefault("log.js", ""));
        assertFalse(source.isBlank(), "log.js 没读到，下面几条量的是空的");

        List<String> bad = new ArrayList<>();
        for (String fn : List.of("renderEngList", "copyEng")) {
            String body = functionBodyAny(source, fn);
            if (body.isBlank()) {
                bad.add("log.js 里找不到 " + fn + "，这一格的射程已经不在了");
            } else if (!body.contains("engSegments(")) {
                bad.add(fn + " 没有问过 engSegments，屏幕与剪贴板可以是两份");
            }
        }
        // 问过了还不够：engSegments 给的是两半，复制的必须是筛完那一半。
        // 只查「调用过」的话，改成复制 all 照样绿，而那正是这一格要防的事
        if (!functionBodyAny(source, "copyEng").contains("engCopyText(shown)")) {
            bad.add("copyEng 复制的不是 engSegments 给的 shown，剪贴板里会多出屏幕上没有的段");
        }
        // 调用点自己再拼一遍等于把判定又变回两份：那时上面几条照样绿
        for (String own : List.of("groupEngLines(", "engVisible(")) {
            if (source.contains(own)) {
                bad.add("log.js 仍自己拼 " + own + "，可见性判定又成了两份");
            }
        }

        assertTrue(bad.isEmpty(), "工程日志的可见性判定不止一份:\n  " + String.join("\n  ", bad));
    }

    /**
     * 底部那条的显隐只由一处决定，页底那截留白跟着它切
     * <p>
     * 条出现当且仅当三件事之一成立：本页有未保存的改动、有保存过等重启的项、状态栏正说着话。
     * 这条判定是纯函数 {@code barVisible}，由 {@link ChangeBarVisibilityTest} 拉起的夹具逐档喂值跑。
     * 这一格钉的是它的另一半——<b>调用点确实问过它</b>：抄一份进渲染代码之后那把夹具照样全绿，
     * 它量的还是那份没人调的判法，而屏幕上跑的是新抄的这一份。
     * <p>
     * 「改动变了」与「说了话」是两条各自成立的路，因此 {@code markDirty} 与 {@code say} 都得走一遍：
     * 少接 {@code say} 那一路，一句话说出来时条不出现，状态栏就成了个看不见的东西；
     * 少接 {@code markDirty} 那一路，改了东西条不出现，保存这条路直接没了。
     * <p>
     * 后半截查那截留白：{@code .bar} 是固定在底的，页底得空出它那么高才不会盖住最后一行。
     * 藏了条却还空着一截的话，屏幕最下面留着一条无缘无故的空白——因此两处必须同一个开关。
     * 这里比的是「藏起来时那截留白确实更小」而不是钉住某个像素值：钉值会让日后调条高的人
     * 为一个数字回来改判据。
     */
    @Test
    @DisplayName("底部改动条的显隐只由一处决定，页底留白跟着切")
    void changeBarVisibilityHasSingleDecisionPoint() throws IOException {
        String source = coreSources().getOrDefault("core.js", "");
        assertFalse(source.isBlank(), "core.js 没读到，下面几条量的是空的");
        String css = Files.readString(frontendDir().resolve("app.css"), StandardCharsets.UTF_8);

        List<String> bad = new ArrayList<>();

        if (!source.contains("export function barVisible(")) {
            bad.add("core.js 没有导出 barVisible，藏不藏的判定没有单独的一份");
        }

        // 挂类那一手只许有一处。第二处一出现，「谁说了算」就有了两个答案，
        // 而分叉时的表现是条在某些操作之后再也不出来了。
        // 这里数的是原文不是 codeOnly：那一步把字符串挖成空白，而 'nobar' 正是要数的东西
        int toggles = countOccurrences(source, "classList.toggle('nobar'");
        if (toggles != 1) {
            bad.add("core.js 里挂 nobar 那一手有 " + toggles + " 处，应恰 1 处");
        }

        String paint = functionBodyAny(source, "paintBar");
        if (paint.isBlank()) {
            bad.add("core.js 里找不到 paintBar，这一格的射程已经不在了");
        } else if (!paint.contains("barVisible(")) {
            bad.add("paintBar 没有问过 barVisible，条藏不藏又成了两份判定");
        }

        for (String caller : List.of("markDirty", "say")) {
            String body = functionBody(source, caller);
            if (body.isBlank()) {
                bad.add("core.js 里找不到 " + caller + "，这一格的射程已经不在了");
            } else if (!body.contains("paintBar(")) {
                bad.add(caller + " 没有调 paintBar，那一路上条不会跟着变");
            }
        }

        if (cssBlock(css, "html.nobar .bar").isBlank()) {
            bad.add("app.css 没有 html.nobar .bar，挂上类之后条照样在屏幕上");
        }
        // 阴性对照：条本来就是固定在底、要占位的。它哪天不占位了，
        // 上面那条「藏起来」量的就不是原来那件事，而这一格照样绿
        String bar = cssBlock(css, ".bar");
        if (!bar.contains("position:fixed")) {
            bad.add(".bar 已经不是固定在底的了，「藏起来省出一截」这件事无从谈起: " + bar.strip());
        }

        int roomy = paddingBottomOf(cssBlock(css, "#main"));
        int tight = paddingBottomOf(cssBlock(css, "html.nobar #main"));
        if (roomy <= 0) {
            bad.add("#main 读不出页底留白，下面那条比不了");
        } else if (tight < 0) {
            bad.add("app.css 没有 html.nobar #main：条藏了，页底却还空着条那么高的一截");
        } else if (tight >= roomy) {
            bad.add("藏起来时页底留白 " + tight + "px，不比出条时的 " + roomy + "px 小");
        }

        assertTrue(bad.isEmpty(), "底部改动条的显隐接线有问题:\n  " + String.join("\n  ", bad));
    }

    /**
     * 一段文本里某个片段出现了几次
     */
    private int countOccurrences(String text, String piece) {
        int count = 0;
        int at = text.indexOf(piece);
        while (at >= 0) {
            count++;
            at = text.indexOf(piece, at + piece.length());
        }
        return count;
    }

    /**
     * 一份声明块的下内边距，单位 px。读不出来时为 -1
     * <p>
     * {@code padding-bottom} 与 {@code padding} 简写都认：只认前者的话，
     * 写成简写的那一份读出来是「没有」，而屏幕上它明明留着一截。
     */
    private int paddingBottomOf(String block) {
        if (block == null || block.isBlank()) {
            return -1;
        }
        Matcher own = Pattern.compile("padding-bottom\\s*:\\s*(\\d+)px").matcher(block);
        if (own.find()) {
            return Integer.parseInt(own.group(1));
        }
        Matcher shorthand = Pattern.compile("(?<![\\w-])padding\\s*:\\s*([^;}]+)").matcher(block);
        if (!shorthand.find()) {
            return -1;
        }
        String[] parts = shorthand.group(1).strip().split("\\s+");
        // 一值四边同、两值上下与左右、三值上／左右／下、四值上右下左
        String bottom = switch (parts.length) {
            case 1, 2 -> parts[0];
            case 3, 4 -> parts[2];
            default -> "";
        };
        Matcher px = Pattern.compile("^(\\d+)px$").matcher(bottom);
        return px.matches() ? Integer.parseInt(px.group(1)) : -1;
    }

    /**
     * 搭车走别人语法循环、不单独立尺的视图模型
     * <p>
     * 现在一条也没有。告警、确认、只读口令三份已经各自有 {@code *-model-check.sh}。
     * 空着比留着一条已经有专尺的名字好：后者会让「有专尺」那份名单对不上，
     * 而专尺自己又量不到这件事。
     */
    private static final List<String> VIEW_MODELS_WITHOUT_OWN_CHECKER = List.of();

    /**
     * 视图模型文件、专尺脚本、build.sh 名单三向闭集
     * <p>
     * 新增一份 {@code *-model.js} 不加尺，或加了尺不写进 {@code MODEL_CHECKERS}，
     * 构建仍全绿——测试不执行构建脚本，只读它和 {@code tools/} 的文本。
     * 三份集合对不上就红。
     */
    @Test
    @DisplayName("视图模型、专尺、build.sh 名单三向闭集")
    void viewModelsCheckersAndBuildListAreClosedSet() throws IOException {
        Path root = repoRoot();
        Path ui = frontendDir();
        Path tools = root.resolve("tools");

        Set<String> models = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(ui)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("-model.js"))
                    .sorted()
                    .forEach(models::add);
        }
        for (Path dir : pageDirs()) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith("-model.js"))
                        .sorted()
                        .forEach(models::add);
            }
        }

        Set<String> checkers = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(tools)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("-model-check.sh"))
                    .sorted()
                    .forEach(checkers::add);
        }

        String build = Files.readString(root.resolve("build.sh"), StandardCharsets.UTF_8);
        Matcher array = Pattern.compile("MODEL_CHECKERS=\\(([^)]*)\\)", Pattern.DOTALL).matcher(build);
        assertTrue(array.find(), "build.sh 里找不到 MODEL_CHECKERS=(…) 数组，闭集没有落脚的地方");
        List<String> listed = new ArrayList<>();
        for (String line : array.group(1).split("\n")) {
            String stripped = line.replaceFirst("#.*", "").trim();
            if (stripped.isEmpty()) {
                continue;
            }
            for (String token : stripped.split("\\s+")) {
                if (!token.isEmpty()) {
                    listed.add(token);
                }
            }
        }
        Set<String> listedSet = new LinkedHashSet<>(listed);

        List<String> bad = new ArrayList<>();
        for (String name : checkers) {
            if (!listedSet.contains(name)) {
                bad.add("尺 " + name + " 没接进 build.sh 的 MODEL_CHECKERS，构建不会跑它");
            }
        }
        for (String name : listed) {
            if (!checkers.contains(name)) {
                bad.add("build.sh 的 MODEL_CHECKERS 写了 " + name + "，tools/ 里没有这把尺");
            }
        }

        Set<String> exempt = new LinkedHashSet<>(VIEW_MODELS_WITHOUT_OWN_CHECKER);
        Set<String> expectedStems = new LinkedHashSet<>();
        for (String model : models) {
            if (!exempt.contains(model)) {
                expectedStems.add(model.substring(0, model.length() - ".js".length()));
            }
        }
        Set<String> checkerStems = new LinkedHashSet<>();
        for (String checker : checkers) {
            if (checker.endsWith("-check.sh")) {
                checkerStems.add(checker.substring(0, checker.length() - "-check.sh".length()));
            }
        }
        for (String stem : expectedStems) {
            if (!checkerStems.contains(stem)) {
                bad.add("模型 " + stem + ".js 没有专尺 " + stem + "-check.sh");
            }
        }
        for (String stem : checkerStems) {
            if (!expectedStems.contains(stem)) {
                bad.add("尺 " + stem + "-check.sh 对不上「有专尺」那份模型名单");
            }
        }

        Map<String, String> checkerTexts = new LinkedHashMap<>();
        for (String checker : checkers) {
            checkerTexts.put(checker, Files.readString(tools.resolve(checker), StandardCharsets.UTF_8));
        }
        Set<String> looped = new LinkedHashSet<>();
        for (String text : checkerTexts.values()) {
            looped.addAll(jsNamedInSyntaxLoops(text));
        }
        for (String model : VIEW_MODELS_WITHOUT_OWN_CHECKER) {
            if (!models.contains(model)) {
                bad.add("豁免名单里的 " + model + " 在界面目录里已经没有了，名单该更新");
                continue;
            }
            if (!syntaxLoopCoversExemptModel(model, looped)) {
                bad.add("豁免件 " + model + " 没有任何一把尺的语法循环代码行收进它，构建连语法都不量");
            }
        }

        assertTrue(bad.isEmpty(), "视图模型三向闭集对不上:\n  " + String.join("\n  ", bad));
        assertFalse(models.isEmpty() || checkers.isEmpty() || listed.isEmpty(),
                "上面那条「闭集在」因此不作数");
    }

    /**
     * 上锁成功后必须回灌登录态
     * <p>
     * {@code authState} 只在载入时由 {@code /auth/state} 下发一次。上锁成功若只
     * {@code refreshFacts} 再 {@code render}，设置页「登录与安全」仍画「还没设口令」，
     * 顶栏「退出登录」也不出——整页刷新才正。回灌必须是一次点名调用，写在成功分支里：
     * 写在别处，这一步成功时仍不会走。
     */
    @Test
    @DisplayName("上锁成功后回灌登录态")
    void lockSuccessRefreshesAuthState() throws IOException {
        String setup = Files.readString(frontendDir().resolve("setup.js"), StandardCharsets.UTF_8);
        String lock = functionBodyAny(setup, "stepLock");
        assertFalse(lock.isBlank(), "找不到 stepLock，本格无从量起");

        int success = lock.indexOf("res.success");
        assertTrue(success >= 0, "stepLock 里没有 res.success 分支");
        String branch = bracedBlockAfter(lock, success).replaceAll("//[^\\n]*", "");
        assertFalse(branch.isBlank(), "res.success 所在 if 没有配平的花括号，成功分支无从量起");
        assertTrue(Pattern.compile("\\brefreshAuthState\\s*\\(").matcher(branch).find(),
                "上锁成功分支没有调用 refreshAuthState");

        String refresh = functionBodyAny(setup, "refreshAuthState");
        assertFalse(refresh.isBlank(), "找不到 refreshAuthState");
        assertTrue(refresh.contains("/auth/state"),
                "refreshAuthState 没有取 /auth/state，回灌没有数据源");
        assertTrue(refresh.contains("setAuthState("),
                "refreshAuthState 没有交给 setAuthState，设置页仍按载入时那一份画");
    }

    @Test
    @DisplayName("豁免件按完整相对路径认，同名异目录不能互抵")
    void exemptModelMatchUsesFullRelativePath(@TempDir Path tmp) throws IOException {
        List<String> red = new ArrayList<>();
        Path ui = tmp.resolve("config-ui");
        Path other = tmp.resolve("elsewhere");
        Files.createDirectories(ui);
        Files.createDirectories(other);
        String model = "alert-model.js";
        Files.writeString(ui.resolve(model), "export const fromUi = 1;\n");
        Files.writeString(other.resolve(model), "export const fromElsewhere = 2;\n");
        String uiRel = tmp.relativize(ui.resolve(model)).toString().replace('\\', '/');
        String otherRel = tmp.relativize(other.resolve(model)).toString().replace('\\', '/');

        try {
            boolean oldWouldCover = false;
            for (String path : Set.of(otherRel)) {
                if (path.equals(model) || path.endsWith("/" + model)) {
                    oldWouldCover = true;
                    break;
                }
            }
            assertTrue(oldWouldCover, "旧尺应把同名异目录互抵成绿：异目录 " + otherRel + " 对豁免件 " + model);
            assertEquals("config-ui/" + model, uiRel, "临时目录里界面副本的相对路径应对上 config-ui/ 前缀");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertFalse(syntaxLoopCoversExemptModel(model, Set.of(otherRel)),
                    "尺只点了异目录同名件 " + otherRel + "，豁免件仍算收进");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertTrue(syntaxLoopCoversExemptModel(model, Set.of(uiRel)),
                    "尺点了 " + uiRel + "，豁免件应收进");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("无花括号成功分支不得截到后面的 catch")
    void bracedBlockAfterDoesNotTakeFollowingCatch() {
        List<String> red = new ArrayList<>();
        String snippet = "try {\n  if (res.success)\n    doSuccess();\n} catch (e) { refreshAuthState(); }\n";
        int from = snippet.indexOf("res.success");

        try {
            int open = snippet.indexOf('{', from);
            assertTrue(open >= 0, "旧尺前提：from 之后应有花括号");
            int depth = 0;
            int close = -1;
            for (int i = open; i < snippet.length(); i++) {
                char c = snippet.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            assertTrue(close > open, "旧尺前提：花括号应配平");
            String oldBranch = snippet.substring(open, close + 1);
            assertTrue(oldBranch.contains("refreshAuthState"),
                    "旧尺应截到 catch 而假绿，实际: " + oldBranch);
            assertFalse(oldBranch.contains("doSuccess"),
                    "旧尺前提坏了：截到的块不应含成功句，实际: " + oldBranch);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        String branch = bracedBlockAfter(snippet, from);
        try {
            assertTrue(branch.contains("doSuccess"), "成功分支应收进无花括号那句，实际: " + branch);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertFalse(branch.contains("refreshAuthState"),
                    "不得把 catch 算进成功分支，实际: " + branch);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("无花括号单语句含对象字面量不得截断")
    void bracedBlockAfterKeepsObjectLiteralInUnbracedStatement() {
        List<String> red = new ArrayList<>();
        String snippet = "if (ok) return { a: 1 };\n} catch (e) {";
        int from = snippet.indexOf("ok");

        try {
            int closeParen = -1;
            int depth = 1;
            for (int i = from; i < snippet.length(); i++) {
                char c = snippet.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        closeParen = i;
                        break;
                    }
                }
            }
            int i = closeParen >= 0 ? closeParen + 1 : from;
            while (i < snippet.length() && Character.isWhitespace(snippet.charAt(i))) {
                i++;
            }
            String oldBranch = "";
            if (i < snippet.length() && snippet.charAt(i) == '{') {
                int braceDepth = 0;
                int close = i;
                for (int j = i; j < snippet.length(); j++) {
                    char c = snippet.charAt(j);
                    if (c == '{') {
                        braceDepth++;
                    } else if (c == '}') {
                        braceDepth--;
                        if (braceDepth == 0) {
                            close = j;
                            break;
                        }
                    }
                }
                oldBranch = snippet.substring(i, close + 1);
            } else {
                int end = i;
                while (end < snippet.length()) {
                    char c = snippet.charAt(end);
                    if (c == ';') {
                        oldBranch = snippet.substring(i, end + 1);
                        break;
                    }
                    if (c == '{' || c == '}') {
                        oldBranch = snippet.substring(i, end).trim();
                        break;
                    }
                    end++;
                    if (end == snippet.length()) {
                        oldBranch = snippet.substring(i).trim();
                    }
                }
            }
            boolean truncatedAtObject = "return".equals(oldBranch.trim())
                    || (oldBranch.contains("{ a: 1 }") && !oldBranch.contains("return { a: 1 };"));
            boolean tookCatch = oldBranch.contains("catch");
            assertTrue(truncatedAtObject || tookCatch,
                    "旧尺应截在 { a: 1 } 或误入 catch，实际: " + oldBranch);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        String branch = bracedBlockAfter(snippet, from);
        try {
            assertTrue(branch.contains("return { a: 1 };"),
                    "应收进整句含对象字面量，实际: " + branch);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertFalse(branch.contains("catch"),
                    "不得把 catch 算进该句，实际: " + branch);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 界面目录与插件页目录里的每一份 .js 都要被至少一把尺的语法循环收进
     * <p>
     * 视图模型那 12 份另有专尺闭集。其余脚本同样没有构建步骤，语法错同样要等页面加载才炸。
     * 插件页 {@code config-ui-pages/*.js} 若漏掉，核心那一把尺全绿也看不见。
     * 只认 {@code for f in} 的代码行：注释里写到文件名不算收进。
     */
    @Test
    @DisplayName("界面与插件页全部 .js 都在某把尺的语法循环代码行里")
    void allFrontendScriptsAreCoveredBySyntaxLoops() throws IOException {
        Set<String> scripts = frontendScriptKeys();
        Set<String> covered = jsNamedInAllSyntaxLoops();

        List<String> bad = new ArrayList<>();
        for (String name : scripts) {
            if (!covered.contains(name)) {
                bad.add(name + " 没有任何一把尺的语法循环代码行收进它");
            }
        }
        assertTrue(bad.isEmpty(), "这些脚本构建连语法都不量:\n  " + String.join("\n  ", bad));
        assertFalse(scripts.isEmpty() || covered.isEmpty(), "上面那条「有名单」因此不作数");
        assertTrue(scripts.contains("config-ui-pages/bilibili.js"),
                "插件页目录里找不到 config-ui-pages/bilibili.js，本格对它的覆盖因此不作数");
    }

    /**
     * 界面目录与插件页目录里的每一份 .js 都要恰好被一把尺的语法循环收进
     * <p>
     * {@link #allFrontendScriptsAreCoveredBySyntaxLoops()} 只判至少一把。两把都点同一份
     * 不添判力，只添两边清单不同步的空当。次数按尺文件计。本格在内存里拼两把都点
     * {@code $UI/x.js} 的假尺，盘上的尺不改。
     */
    @Test
    @DisplayName("界面每份 .js 恰在一把尺的语法循环里")
    void eachFrontendScriptIsInExactlyOneSyntaxLoop() throws IOException {
        List<String> red = new ArrayList<>();
        Set<String> scripts = frontendScriptKeys();
        Map<String, List<String>> byScript = syntaxLoopRulersByScript(diskSyntaxLoopRulerTexts());

        try {
            List<String> doubled = new ArrayList<>();
            for (String script : scripts) {
                List<String> rulers = byScript.getOrDefault(script, List.of());
                if (rulers.size() >= 2) {
                    String base = script.substring(script.lastIndexOf('/') + 1);
                    doubled.add(base + " → " + String.join("、", rulers)
                            + "（" + rulers.size() + " 把尺）");
                }
            }
            assertTrue(doubled.isEmpty(), String.join("\n", doubled));
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            List<String> missing = new ArrayList<>();
            for (String script : scripts) {
                List<String> rulers = byScript.getOrDefault(script, List.of());
                if (rulers.isEmpty()) {
                    missing.add(script + " 没有任何一把尺的语法循环代码行收进它");
                }
            }
            assertTrue(missing.isEmpty(),
                    "这些脚本构建连语法都不量:\n  " + String.join("\n  ", missing));
            assertFalse(scripts.isEmpty() || byScript.isEmpty(), "上面那条「有名单」因此不作数");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            Map<String, String> fake = new LinkedHashMap<>();
            fake.put("a-check.sh", "for f in \"$UI\"/x.js; do :; done\n");
            fake.put("b-check.sh", "for f in \"$UI\"/other.js \"$UI\"/x.js; do :; done\n");
            Map<String, List<String>> fakeHits = syntaxLoopRulersByScript(fake);
            List<String> rulers = fakeHits.getOrDefault("config-ui/x.js", List.of());
            assertEquals(2, rulers.size(),
                    "两把假尺都点 $UI/x.js 应判出重量，实际: " + rulers);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 把 {@code tokens-model.js} 从 for 行挪进注释必须让覆盖判法认不出来
     * <p>
     * 旧判法是全文 {@code contains}，注释里提到也算收进。本格在内存里做一次突变，
     * 不改盘上的尺。
     */
    @Test
    @DisplayName("语法循环覆盖不认注释里的文件名")
    void syntaxLoopCoverageIgnoresCommentedFileNames() throws IOException {
        String original = Files.readString(
                repoRoot().resolve("tools/tokens-model-check.sh"), StandardCharsets.UTF_8);
        assertTrue(jsNamedInSyntaxLoops(original).contains("config-ui/tokens-model.js"),
                "本格的阴性对照要先有阳性：原尺代码行里必须有 config-ui/tokens-model.js");
        assertTrue(original.contains(" \"$UI\"/tokens-model.js"),
                "原尺没有「 $UI/tokens-model.js」这一段，突变无从做起");

        String mutated = original.replaceFirst("(?m)^(for f in )", "# tokens-model.js\n$1")
                .replace(" \"$UI\"/tokens-model.js", "");
        assertTrue(mutated.contains("# tokens-model.js"), "突变没把文件名写进注释");
        assertFalse(jsNamedInSyntaxLoops(mutated).contains("config-ui/tokens-model.js"),
                "把 tokens-model.js 从 for 行挪进注释后仍算收进，这一格量不动");
    }

    /**
     * 尺只点 {@code $UI/setup.js} 时，同名的插件页脚本不得算收进
     * <p>
     * 旧判法只比文件名：名单里再放一份 {@code config-ui-pages/setup.js}，尺没点它也绿。
     * 本格在内存里构造这一对，不改盘上的尺。
     */
    @Test
    @DisplayName("语法循环覆盖按相对路径区分同名异目录")
    void syntaxLoopCoverageDistinguishesSameNameInDifferentDirs() {
        String ruler = "for f in \"$UI\"/setup.js; do :; done\n";
        Set<String> covered = jsNamedInSyntaxLoops(ruler);

        Set<String> oldBasenames = new LinkedHashSet<>();
        Matcher bare = Pattern.compile("([A-Za-z0-9._-]+\\.js)").matcher(ruler);
        while (bare.find()) {
            oldBasenames.add(bare.group(1));
        }
        Set<String> listed = new LinkedHashSet<>();
        listed.add("config-ui/setup.js");
        listed.add("config-ui-pages/setup.js");

        assertTrue(oldBasenames.contains("setup.js"),
                "旧对照坏了：尺文本里应有 setup.js 这个文件名");
        boolean oldWouldCoverBoth = true;
        for (String name : listed) {
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (!oldBasenames.contains(base)) {
                oldWouldCoverBoth = false;
                break;
            }
        }
        assertTrue(oldWouldCoverBoth,
                "旧对照坏了：按文件名会把两个目录的 setup.js 都算收进");

        assertTrue(covered.contains("config-ui/setup.js"),
                "尺点了 $UI/setup.js，应按相对路径收进 config-ui/setup.js");
        assertFalse(covered.contains("config-ui-pages/setup.js"),
                "尺只点 $UI/setup.js 却把 config-ui-pages/setup.js 也算收进");
    }

    /**
     * 界面件非注释行不得出现平台词
     * <p>
     * 核心界面不自带平台名：带名字的那一版由插件在运行时填回来。注释里提到也不算——
     * 量的是会画到屏幕上、或会作为标识符跑起来的那些行。
     */
    @Test
    @DisplayName("界面件非注释行零平台词")
    void connectionSurfaceHasNoPlatformWords() throws IOException {
        List<String> files = List.of("index.html", "links-model.js", "links.js", "log.js",
                "setup.js", "setup-model.js", "home-model.js",
                "settings-alert.js", "bot.js", "tokens-model.js",
                "settings-auth.js", "login.html");
        List<String> words = List.of("QQ", "NapCat", "OneBot");
        Path dir = frontendDir();
        List<String> hits = new ArrayList<>();

        List<Path> toScan = new ArrayList<>();
        for (String name : files) {
            toScan.add(dir.resolve(name));
        }
        for (Path pageDir : pageDirs()) {
            for (String name : List.of("streamers.js", "push.js", "template-model.js")) {
                Path page = pageDir.resolve(name);
                if (Files.exists(page)) {
                    toScan.add(page);
                }
            }
        }

        for (Path file : toScan) {
            String name = file.getFileName().toString();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String trimmed = raw.strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")
                        || trimmed.startsWith("/*") || trimmed.startsWith("<!--")) {
                    continue;
                }
                if (raw.contains("smtp.qq.com")) {
                    continue;
                }
                for (String word : words) {
                    if (raw.contains(word)) {
                        hits.add(name + ":" + (i + 1));
                        break;
                    }
                }
                if (!hits.isEmpty() && hits.get(hits.size() - 1).startsWith(name + ":")) {
                    break;
                }
            }
        }

        assertTrue(hits.isEmpty(),
                "界面件非注释行仍有平台词，各件首个命中: " + String.join("；", hits));
    }

    /**
     * 核心 Java 侧给人看的句子不得带平台名
     * <p>
     * 校验器与探针里的句子不该去 Bean 取词，因此这里只量源码里的双引号字面量。
     * 注释和 Javadoc 不算；配置键名 {@code starbot.*} 与邮件服务商示例
     * {@code smtp.qq.com} 也不是推送平台名。
     */
    @Test
    @DisplayName("Java 侧用户文案零平台词")
    void javaUserFacingCopyHasNoPlatformWords() throws IOException {
        List<String> reds = new ArrayList<>();
        Path javaRoot = repoRoot().resolve("core/nova-core/src/main/java");
        List<String> hits = platformWordsInJavaSources(javaRoot);

        try {
            assertTrue(hits.isEmpty(),
                    "Java 侧用户文案仍有平台词: " + String.join("；", hits));
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            String mail = Files.readString(
                    javaRoot.resolve("org/frostnova/nova/core/config/ui/ExternalConfigurationFields.java"),
                    StandardCharsets.UTF_8);
            assertTrue(mail.contains(MAIL_SMTP_EXAMPLE),
                    "ExternalConfigurationFields 的 smtp.qq.com 示例句应仍在");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            List<String> sample = List.of(
                    "    // 注释里写 QQ 或 OneBot 不算",
                    "    String key = \"starbot.core.alert.qq-num\";",
                    "    String bad = \"常见原因：群号或 QQ 号填错\";",
                    "    String mail = \"" + MAIL_SMTP_EXAMPLE + "\";");
            assertEquals(List.of("probe.java:3"), platformWordsInLines(sample, "probe.java"),
                    "尺应对含 QQ 的字面量红，并放过注释、键名与邮件示例");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 词表从 /api/vocab 接到 store，再经 term() 读
     */
    @Test
    @DisplayName("词表接线")
    void vocabIsWired() throws IOException {
        List<String> reds = new ArrayList<>();
        Path dir = frontendDir();

        try {
            assertTrue(nonCommentContains(dir.resolve("main.js"), "api('/vocab')"),
                    "main.js 代码行应含 api('/vocab')");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }
        try {
            assertTrue(nonCommentContains(dir.resolve("core.js"), "export function term("),
                    "core.js 代码行应含 export function term(");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }
        try {
            assertTrue(nonCommentContains(dir.resolve("store.js"), "vocab"),
                    "store.js 代码行应含 vocab");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 顶级页 refresh 要把地址栏已经解析好的子路径带给插件
     * <p>
     * 三问各自记下，末尾一起红：只看 applyRoute 取数段里含 topPage 的那一支，
     * 避免把推送页的 {@code showPush(sub, tail)} 算进来。
     */
    @Test
    @DisplayName("顶级页 refresh 把子路由带给插件")
    void topPageRefreshPassesSubAndTail() throws IOException {
        List<String> red = new ArrayList<>();
        String main = Files.readString(frontendDir().resolve("main.js"), StandardCharsets.UTF_8);
        String body = functionBodyAny(main, "applyRoute");
        int data = body.indexOf("if (!withData) return");
        String load = data >= 0 ? body.substring(data) : body;

        try {
            assertTrue(load.contains("topPage"),
                    "applyRoute 取数段应对顶级页走分支");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            int call = load.indexOf("callPage");
            assertTrue(call >= 0, "applyRoute 取数段应 callPage");
            String invocation = load.substring(call, Math.min(load.length(), call + 96));
            assertTrue(invocation.contains("refresh"),
                    "顶级页分支应调 refresh: " + invocation.strip());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            int call = load.indexOf("callPage");
            assertTrue(call >= 0, "applyRoute 取数段应 callPage");
            String invocation = load.substring(call, Math.min(load.length(), call + 96));
            assertTrue(invocation.contains("sub"),
                    "顶级页 refresh 应带 sub: " + invocation.strip());
            assertTrue(invocation.contains("tail"),
                    "顶级页 refresh 应带 tail: " + invocation.strip());
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 「登录与安全」四项必须和其他设置项同一套横行，不能再走告警那套卡片。
     * <p>
     * 卡片最窄 260px，通行密钥四列表会撑出组边。改成 {@code .setitem} 之后，
     * 右侧只留一颗控件，列表另起整行。三问各自记下，末尾一起红。
     */
    @Test
    @DisplayName("登录与安全四项走 .setitem 行、settings-auth.js 不再有 alcard")
    void authGroupUsesSetitemRowsNotAlcard() throws IOException {
        Map<String, String> sources = coreSources();
        String auth = sources.getOrDefault("settings-auth.js", "");
        List<String> reds = new ArrayList<>();

        try {
            assertFalse(auth.isBlank(), "找不到 settings-auth.js");
            assertTrue(auth.contains("setitem"),
                    "settings-auth.js 没有 .setitem，四项还不是和其他设置同一套横行");
            String cards = functionBodyAny(auth, "authCards");
            assertTrue(cards.contains("passwordCard(") && cards.contains("totpCard(")
                            && cards.contains("passkeyCard(") && cards.contains("rerunCard("),
                    "authCards 应仍组装四项");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            assertFalse(auth.contains("alcard"),
                    "settings-auth.js 仍有 alcard，登录与安全还在走卡片");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            String filter = functionBodyAny(auth, "filterAuthCards");
            assertFalse(filter.isBlank(), "找不到 filterAuthCards");
            assertTrue(filter.contains(".setitem"),
                    "filterAuthCards 没有按 .setitem 筛，搜「密码」时这一组筛不到");
            assertFalse(filter.contains(".alcard"),
                    "filterAuthCards 仍按 .alcard 筛");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 通行密钥起名必须走自绘弹层，不能再调浏览器原生 prompt()。
     * <p>
     * 原生那一句没有标题、也没有和确认框同一套取消／确认。改回去不会让登记变坏，
     * 因此靠人复查是拦不住的。形制照 {@link #settingsPushAndSessionsUsePaintedConfirm}。
     */
    @Test
    @DisplayName("passkeys.js 不调原生 prompt")
    void passkeysDoNotCallNativePrompt() {
        Map<String, String> sources = coreSources();
        String passkeys = sources.getOrDefault("passkeys.js", "");
        String register = functionBodyAny(passkeys, "registerPasskey");
        List<String> reds = new ArrayList<>();

        try {
            assertFalse(passkeys.isBlank(), "找不到 passkeys.js");
            assertFalse(codeOnly(passkeys).contains("prompt("),
                    "passkeys.js 仍在调用原生 prompt()");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            assertFalse(register.isBlank(), "找不到 registerPasskey");
            assertTrue(register.contains("fields"),
                    "registerPasskey 没有走 ask 的 fields 槽，设备名仍会落到原生输入框");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            assertTrue(register.contains("value: '我的设备'") || register.contains("value:'我的设备'"),
                    "设备名默认值应经 fields.value 带上，而不是 prompt 的第二参");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 通行密钥列表必须外包横向滚动容器，表本身按内容宽、且不窄于一行。
     * <p>
     * 四列（名字／登记时间／上次使用／删除）并排时最小宽大约 450px，
     * 不包 {@code .tblwrap} 就会撑出设置组。三问各自记下，末尾一起红。
     */
    @Test
    @DisplayName("通行密钥列表外有 tblwrap")
    void passkeyListIsWrappedForOverflow() throws IOException {
        Map<String, String> sources = coreSources();
        String auth = sources.getOrDefault("settings-auth.js", "");
        String passkeys = sources.getOrDefault("passkeys.js", "");
        String css = Files.readString(frontendDir().resolve("app.css"), StandardCharsets.UTF_8);
        List<String> reds = new ArrayList<>();

        try {
            String card = functionBodyAny(auth, "passkeyCard");
            assertFalse(card.isBlank(), "找不到 passkeyCard");
            assertTrue(card.contains("tblwrap"),
                    "passkeyCard 没有外包 tblwrap，列表过宽时会撑出设置组");
            assertTrue(card.contains("passkey-list"),
                    "passkeyCard 应仍建出 #passkey-list");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            String block = cssBlock(css, ".tblwrap");
            assertFalse(block.isBlank(), "app.css 没有 .tblwrap");
            assertTrue(block.contains("overflow-x:auto") || block.contains("overflow-x: auto"),
                    ".tblwrap 没有 overflow-x:auto: " + block.strip());
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            assertTrue(passkeys.contains("max-content"),
                    "passkeys.js 的表没有 width max-content，过宽时仍会挤列");
            assertTrue(passkeys.contains("minWidth") || passkeys.contains("min-width"),
                    "passkeys.js 的表没有 min-width 100%，窄屏时表会缩得比一行还窄");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    /**
     * 扫核心 Java 源：非注释、非 Javadoc 行上的双引号字面量
     */
    private List<String> platformWordsInJavaSources(Path javaRoot) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            List<Path> files = walk
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                hits.addAll(platformWordsInLines(
                        Files.readAllLines(file, StandardCharsets.UTF_8),
                        file.getFileName().toString()));
            }
        }
        return hits;
    }

    /**
     * 一行里抽双引号字面量，命中平台词则记 文件:行
     * <p>
     * 白名单两条：邮件 SMTP 示例整句、以及整段匹配 {@code starbot.[a-z0-9.-]+} 的键名。
     */
    private List<String> platformWordsInLines(List<String> lines, String fileName) {
        List<String> words = List.of("QQ", "NapCat", "OneBot");
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (isCommentOrJavadocLine(raw)) {
                continue;
            }
            Matcher m = JAVA_STRING_LITERAL.matcher(raw);
            while (m.find()) {
                String content = m.group().substring(1, m.group().length() - 1);
                if (MAIL_SMTP_EXAMPLE.equals(content)
                        || STARBOT_KEY_LITERAL.matcher(content).matches()) {
                    continue;
                }
                for (String word : words) {
                    if (content.contains(word)) {
                        hits.add(fileName + ":" + (i + 1));
                        break;
                    }
                }
            }
        }
        return hits;
    }

    /**
     * 整行是注释或 Javadoc 续行
     */
    private boolean isCommentOrJavadocLine(String raw) {
        String trimmed = raw.strip();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /**
     * 去掉注释行后，文件是否含这一串
     * @param file 界面文件
     * @param needle 要找的字面
     * @return 非注释行里找得到时为真
     */
    private boolean nonCommentContains(Path file, String needle) throws IOException {
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = raw.strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("/*") || trimmed.startsWith("<!--")) {
                continue;
            }
            if (raw.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一把尺脚本里，语法循环 {@code for f in …} 的代码行点到的 .js 相对路径
     * <p>
     * 注释行不算：把文件名从 for 行挪进注释之后，全文 contains 仍绿，这一格就量不动了。
     * 路径按尺里的目录变量映射：{@code $UI} → {@code config-ui/}，
     * {@code $PAGES} → {@code config-ui-pages/}。只比文件名的话，
     * {@code config-ui/x.js} 与 {@code config-ui-pages/x.js} 同名时收进一份也判两份都在。
     */
    private Set<String> jsNamedInSyntaxLoops(String script) {
        StringBuilder code = new StringBuilder();
        for (String raw : script.split("\n", -1)) {
            String line = raw.replaceFirst("#.*", "");
            if (line.endsWith("\\")) {
                code.append(line, 0, line.length() - 1).append(' ');
            } else {
                code.append(line).append('\n');
            }
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher loop = Pattern.compile("for\\s+f\\s+in\\s+([^;]+)").matcher(code);
        while (loop.find()) {
            Matcher js = Pattern.compile("\"\\$(UI|PAGES)\"/([A-Za-z0-9._-]+\\.js)").matcher(loop.group(1));
            while (js.find()) {
                String dir = "UI".equals(js.group(1)) ? "config-ui/" : "config-ui-pages/";
                names.add(dir + js.group(2));
            }
        }
        return names;
    }

    /**
     * 界面目录与插件页目录里每一份 .js 的相对路径，相对 {@link #frontendDir()} 的上一级
     * <p>
     * 插件页不在那一层下面，键仍用 {@code config-ui-pages/} 前缀，与尺里 {@code $PAGES} 对齐。
     */
    private Set<String> frontendScriptKeys() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(frontendDir())) {
            files.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted()
                    .forEach(p -> names.add("config-ui/" + p.getFileName()));
        }
        for (Path dir : pageDirs()) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".js"))
                        .sorted()
                        .forEach(p -> names.add("config-ui-pages/" + p.getFileName()));
            }
        }
        return names;
    }

    /**
     * {@code tools/*-check.sh} 里所有语法循环代码行点到的 .js 相对路径
     */
    private Set<String> jsNamedInAllSyntaxLoops() throws IOException {
        Path tools = repoRoot().resolve("tools");
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(tools)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("-check.sh"))
                    .sorted()
                    .forEach(name -> {
                        try {
                            names.addAll(jsNamedInSyntaxLoops(
                                    Files.readString(tools.resolve(name), StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return names;
    }

    /**
     * {@code tools/*-check.sh} 文件名到正文
     */
    private Map<String, String> diskSyntaxLoopRulerTexts() throws IOException {
        Path tools = repoRoot().resolve("tools");
        Map<String, String> texts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(tools)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("-check.sh"))
                    .sorted()
                    .forEach(name -> {
                        try {
                            texts.put(name, Files.readString(tools.resolve(name), StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return texts;
    }

    /**
     * 每份被语法循环点到的 .js 相对路径 → 点到它的尺文件名
     */
    private Map<String, List<String>> syntaxLoopRulersByScript(Map<String, String> rulerTexts) {
        Map<String, List<String>> byScript = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rulerTexts.entrySet()) {
            for (String js : jsNamedInSyntaxLoops(e.getValue())) {
                byScript.computeIfAbsent(js, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        return byScript;
    }

    /**
     * 模型文件里导出的字符串数组
     */
    private List<String> exportedStringArray(String text, String name) {
        Matcher m = Pattern.compile("export const " + name + "\\s*=\\s*\\[([^]]*)]").matcher(text);
        if (!m.find()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (item.find()) {
            out.add(item.group(1));
        }
        return out;
    }

    /**
     * 某条 CSS 选择器的第一份声明块
     */
    private String cssBlock(String css, String selector) {
        Matcher m = Pattern.compile("(?:^|[\\n}])\\s*" + Pattern.quote(selector) + "\\s*\\{([^}]+)\\}")
                .matcher(css);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 某个 export function 到下一个 export 之间的正文
     */
    private String functionBody(String text, String name) {
        Matcher m = Pattern.compile("export function " + name + "\\s*\\(").matcher(text);
        if (!m.find()) {
            return "";
        }
        Matcher next = Pattern.compile("\\nexport ").matcher(text);
        next.region(m.end(), text.length());
        int to = next.find() ? next.start() : text.length();
        return text.substring(m.start(), to);
    }

    /**
     * 某个 function（含未导出的）到下一个 function 之间的正文
     */
    private String functionBodyAny(String text, String name) {
        Matcher m = Pattern.compile("(?:async\\s+)?function " + name + "\\s*\\(").matcher(text);
        if (!m.find()) {
            return "";
        }
        Matcher next = Pattern.compile("\\n(?:async\\s+)?function ").matcher(text);
        next.region(m.end(), text.length());
        int to = next.find() ? next.start() : text.length();
        return text.substring(m.start(), to);
    }

    /**
     * 豁免件是否被某把尺的语法循环收进
     * <p>
     * 只认界面目录下的完整相对路径。按文件名 {@code endsWith} 的话，
     * 插件页里一份同名件就能把没被收进的豁免件抵成绿。
     */
    private boolean syntaxLoopCoversExemptModel(String model, Set<String> looped) {
        return looped.contains("config-ui/" + model);
    }

    /**
     * 从 {@code from} 所在条件之后截出那一块语法块
     * <p>
     * 用来取 {@code if (res.success) { … }} 的成功分支：从条件截到函数尾会把
     * {@code else}／{@code catch} 也算进去，调用写在失败路径里照样绿。
     * 成功分支若是无花括号单语句，不能再扫后面第一对花括号——那会截到 {@code catch}。
     * 无花括号单语句里的对象字面量是该句的一部分，截到第一个左花括号会把返回值截断。
     */
    private String bracedBlockAfter(String text, int from) {
        int closeParen = -1;
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    closeParen = i;
                    break;
                }
            }
        }
        int i = closeParen >= 0 ? closeParen + 1 : from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return "";
        }
        if (text.charAt(i) == '{') {
            int braceDepth = 0;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0) {
                        return text.substring(i, j + 1);
                    }
                }
            }
            return "";
        }
        int end = i;
        int braceDepth = 0;
        char quote = 0;
        while (end < text.length()) {
            char c = text.charAt(end);
            char prev = end > 0 ? text.charAt(end - 1) : 0;
            if (quote != 0) {
                if (c == quote && prev != '\\') {
                    quote = 0;
                }
                end++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                end++;
                continue;
            }
            if (c == '/' && end + 1 < text.length() && text.charAt(end + 1) == '/') {
                int nl = text.indexOf('\n', end);
                end = nl < 0 ? text.length() : nl;
                continue;
            }
            if (c == '/' && end + 1 < text.length() && text.charAt(end + 1) == '*') {
                int closeComment = text.indexOf("*/", end + 2);
                end = closeComment < 0 ? text.length() : closeComment + 2;
                continue;
            }
            if (c == '{') {
                braceDepth++;
                end++;
                continue;
            }
            if (c == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                    end++;
                    continue;
                }
                return text.substring(i, end).trim();
            }
            if (c == ';' && braceDepth == 0) {
                return text.substring(i, end + 1);
            }
            end++;
        }
        return text.substring(i).trim();
    }
}
