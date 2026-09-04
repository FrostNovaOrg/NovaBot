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
            "handlerList", "senderList", "pushEnabled", "accountTimer",
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

        assertTrue(bad.isEmpty(), "设置页少了这几件事的落点:\n  " + String.join("\n  ", bad));
    }

    /**
     * 设置页上这几件事各自的落点，闭集
     * <p>
     * 搜索框、显示键名、只看改过的、计数、组目录药丸、组容器、配置文件路径与复制。
     */
    private static final List<String> SETTINGS_CONTROLS = List.of(
            "set-search", "show-keys", "only-changed", "set-count", "grp-nav", "groups",
            "cfg-path", "cfg-copy");

    /**
     * 「登录与安全」那一组里由脚本建出来的落点，闭集
     * <p>
     * 与 {@link #SETTINGS_CONTROLS} 分开是因为这几件事<b>不写在 index.html 里</b>：
     * 设置页会整体重绘（保存过一次、放弃一次改动都会），而重绘的第一步是把组容器清空——
     * 写死在页面里再搬进去的那一块会跟着一起没掉，此后按 id 取到的是 null，
     * 那一块就<b>安静地从页面上消失</b>了。通行密钥那一块正是这么搬过的。
     */
    private static final List<String> AUTH_CONTROLS = List.of(
            "auth-cards", "pwd-save", "totp-switch", "passkey-add", "setup-rerun");

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
            if (!scripts.contains("id=\"" + id + "\"") && !scripts.contains(".id = '" + id + "'")) {
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
     * 进度条、正文与「稍后再说」。五步的内容全部由脚本建出来，不写在页面里——
     * 同一件事在页面与脚本里各有一份的话，两份分叉时屏幕上不会有任何异常。
     */
    private static final List<String> SETUP_SHELL = List.of(
            "setup-steps", "setup-main", "setup-later");

    /**
     * 五步各自那几件事的落点，由 {@code setup.js} 建出来，闭集
     * <p>
     * 底下那一条（上一步／跳过／下一步／拦住的理由）、第 1 步的两遍口令与通行密钥、
     * 第 2 步的五格连接参数与测试、第 4 步的平台与 uid、找一下、推到哪，
     * 第 5 步的发给谁、发一条、收到了／没收到与那三条排查，以及初始值那一摊与「进控制台」。
     */
    private static final List<String> SETUP_CONTROLS = List.of(
            "setup-back", "setup-next", "setup-why", "setup-skip",
            "setup-lock", "setup-pwd", "setup-pwd2", "setup-passkey",
            "setup-test-bot", "setup-addr", "setup-hport", "setup-wport",
            "setup-htoken", "setup-wtoken",
            "setup-platform", "setup-uid", "setup-lookup", "setup-targets",
            "setup-send-target", "setup-send", "setup-got", "setup-not-got", "setup-tips",
            "setup-defaults", "setup-enter");

    /**
     * 五步各自要调的端点，闭集
     * <p>
     * 少接一条，那一步就变成一个点了没反应的按钮——而按钮本身看起来完全正常。
     * 这些端点别处也在用，因此只在 {@code setup.js} 里找：拿全部脚本找的话，
     * 这一页把某条丢了也照样绿，因为别的页还留着它。
     */
    private static final List<String> SETUP_ENDPOINTS = List.of(
            "/status", "/login", "/setup/state", "/setup/rerun/consumed", "/setup/test-sent",
            "/auth/password/set", "/setup/test-bot", "/setup/bot",
            "/streamer/lookup", "/onebot/targets?type=group", "/onebot/targets?type=friend",
            "/datasource", "/test-message");

    /**
     * 初始设置五步各有落点，且放行的判法只有 setup-model 一份
     * <p>
     * 与设置页、连接页、日志页那三条同理：元素与接线缺哪一半都不会报错。
     * <p>
     * 🔴 后半截奔着一类具体的退步去：<b>把「这一步放不放行」抄一份到渲染代码里</b>。
     * 那几条规则（第 1 步不许跳、第 4 步 0 主播不许过、第 3 步不登录必须先过确认）
     * 由 {@code setup-model.js} 现算，那一份有 node 夹具逐格在量；抄进渲染代码之后，
     * 夹具照样全绿——它量的还是那份没人调的判法，而屏幕上跑的是新抄的这一份。
     * 抄的那一下<b>不会让任何功能变坏</b>，因此靠人复查是拦不住的。
     * <p>
     * 同一条理由也管着「推到哪」与「发给谁」：目标只能从机器人自己给的名单里挑，
     * 这一条判在 {@code links-model.js} 的 resolveTarget 里，本页必须调它而不是自己认。
     */
    @Test
    @DisplayName("初始设置五步各有落点，放行的判法只有 setup-model 一份")
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
            bad.add("找不到 " + SETUP_MODEL + "，五步的判法没有落脚的地方");
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
            bad.add(SETUP_VIEW + " 自己又判了一遍五步。那几条规则只许有 " + SETUP_MODEL
                    + " 一份——抄一份进来之后，夹具量的还是没人调的那一份");
        }
        if (!view.contains("resolveTarget(")) {
            bad.add(SETUP_VIEW + " 没有经过 resolveTarget 认目标。手填时填错一位数不会有任何报错，"
                    + "消息只是发去了别处，而这两步存在的意义正是把那种错拦在配置阶段");
        }

        assertTrue(bad.isEmpty(), "初始设置页少了这几件事:\n  " + String.join("\n  ", bad));
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
            "templateAdoption(", "restoreDefaults(", "isDefault(");

    /**
     * 抄进渲染代码就算退步的那几条判法，闭集
     */
    private static final List<String> PUSH_MODEL_FUNCTIONS = List.of(
            "function pushTree", "function channelIndex", "function templateState",
            "function layoutState", "function commandGroups", "function commandSummary",
            "function recentPushes", "function atAllStatus");

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
        Map<String, String> sources = coreSources();
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
        String html = Files.readString(frontendDir().resolve("index.html"), StandardCharsets.UTF_8);
        Map<String, String> sources = coreSources();
        String scripts = String.join("\n", sources.values());
        String view = sources.getOrDefault(PUSH_VIEW, "");
        String settings = sources.getOrDefault(PUSH_SETTINGS, "");

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
            if (!html.contains("id=\"" + id + "\"")) {
                bad.add("index.html 上没有 #" + id);
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

        bad.addAll(pushPageHasNoFreeTextField(html));

        assertTrue(bad.isEmpty(), "推送页少了这几件事:\n  " + String.join("\n  ", bad));
    }

    /**
     * 推送页上不许有手填号码的格子
     * <p>
     * 与连接页那条同族，奔着同一类退步去：<b>把手填群号的格子加回来</b>。填错一位数不会有
     * 任何报错，消息只是发去了别处；而「配好了群里没动静」的另外几类错（Token 不对、
     * 机器人被踢出群、OneBot 没起）表现完全一样，混在一起就再也分不开。
     * <p>
     * 量的是 {@code index.html} 里推送页那一块：挑选面板由脚本建出来，里面那个
     * 「uid 或个人空间链接」是主播的账号，不是推送目标的号码，两者不是一回事。
     * @param html index.html 全文
     * @return 问题，没有则为空
     */
    private List<String> pushPageHasNoFreeTextField(String html) {
        List<String> bad = new ArrayList<>();

        int from = html.indexOf("id=\"page-push\"");
        int to = html.indexOf("id=\"page-streamers\"", Math.max(from, 0));
        if (from < 0 || to < 0) {
            // 找不到那一块时判红而不是跳过
            bad.add("index.html 里找不到推送页那一块（#page-push 到 #page-streamers 之间）");
            return bad;
        }

        String block = html.substring(from, to);
        if (block.contains("<input")) {
            bad.add("推送页上出现了输入框。推送目标只能从机器人自己给的名单里挑——"
                    + "手填时填错一位数不会有任何报错，消息只是发去了别处");
        }
        // 阴性对照：这一格得能分辨。没有这一句的话，那一块整个被删掉也照样「不含输入框」
        if (!block.contains("<select")) {
            bad.add("推送页上没有窄屏那个下拉，上面那条「不含输入框」因此不作数");
        }

        return bad;
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
        Map<String, String> sources = coreSources();
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
}
