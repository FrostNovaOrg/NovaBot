package com.starlwr.bot.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.annotation.ImportCandidates;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 把「谁会被装载」按构建产物清点一遍
 *
 * <h2>这一格补的是什么洞</h2>
 * 插件被装进容器的唯一通道，是各模块 jar 里那份
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。
 * 各模块自己那份自报测试只看得见<b>本模块</b>的一份；「一共该有几份、有没有哪个模块整个漏掉」
 * 没有任何一格在问。漏掉的表现不是报错，而是那个插件安安静静地整个不见。
 *
 * <h2>为什么按产物量而不是按源码量</h2>
 * 源码目录里有 {@code .imports} 不等于它进了 jar：各插件模块的 pom 都收窄过
 * {@code maven-resources-plugin} 的资源范围，一处写漏就是「源码里有、产物里没有」。
 * 真正决定运行期行为的是 {@code target/classes} 下的那一份，所以量的是它。
 *
 * <h2>为什么住在这个模块</h2>
 * 它要同时看见五个插件模块的 {@code target/classes}，因此只能跑在 reactor 的最后一个模块上。
 * 换句话说，单独跑本类（而不是整盘跑）时若别的模块还没构建过，这一格会红在「产物不见了」，
 * 那是它该有的样子——没有产物就等于没有读数。
 *
 * <h2>分母不手写</h2>
 * 「哪些模块是插件模块」现算自 {@code build.sh} 里的 {@code PLUGIN_MODULES}——决定哪些 jar
 * 进 {@code plugins/} 的正是它。手写一份名单的话，日后新挂的第六个插件不在名单里，
 * 而「不在名单里」和「查过了」在读数上长得一模一样。
 */
@DisplayName("插件自报清点")
class PluginSelfDeclarationCensusTest {
    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Boot 4 把它挪进了 task 子包; 写全类名而不是 import, 是为了让「找不到」也成为一条读数。 */
    private static final String TASK_SCHEDULING =
            "org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration";

    private static final Pattern PLUGIN_MODULES =
            Pattern.compile("PLUGIN_MODULES=\\(([^)]*)\\)");

    @Test
    @DisplayName("每个插件模块的产物里恰有一份自报, 指的是本模块自己的类, ImportCandidates 现算出的候选与它们逐名相同")
    void everyPluginModuleDeclaresItselfInItsArtifact() {
        List<String> red = new ArrayList<>();

        List<String> modules = List.of();
        try {
            modules = pluginModules();
            assertTrue(modules.size() >= 2,
                    "从 build.sh 只读出 " + modules.size() + " 个插件模块, 分母不对, 这一格此刻几乎什么都没量");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        // 模块名 → 它产物里自报的那个类名
        Map<String, String> declared = new LinkedHashMap<>();
        for (String module : modules) {
            try {
                Path classes = repoRoot().resolve(module).resolve("target/classes");
                Path imports = classes.resolve(IMPORTS);
                assertTrue(Files.isRegularFile(imports),
                        module + " 的产物里没有自报文件, 装上它也不会被看见: " + imports);

                List<String> names = readNames(imports);
                assertEquals(1, names.size(),
                        module + " 的自报文件写了 " + names.size() + " 个类, 期望恰一个: " + names);

                String name = names.get(0);
                Path classFile = classes.resolve(name.replace('.', '/') + ".class");
                assertTrue(Files.isRegularFile(classFile),
                        module + " 自报的 " + name + " 不在本模块产物里, 自报指着的是别人家的类: " + classFile);

                declared.put(module, name);
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }
        }

        try {
            assertEquals(new TreeSet<>(declared.values()), candidatesFromArtifacts(modules),
                    "ImportCandidates 从五个模块的产物里现算出来的候选, 与逐个文件读到的那一份对不上"
                            + "；逐文件读到：" + declared);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * <h2>这一格补的是什么洞</h2>
     * 插件改由 Spring Boot 自动配置装载之后, 装载顺序变成了按类名排序,
     * {@code com.starlwr.bot.*} 排在 {@code TaskSchedulingAutoConfiguration} 前面,
     * 于是哔哩哔哩插件那台 {@code bilibiliTaskScheduler} 先进场,
     * Boot 那台通用 {@code taskScheduler} 的「没有别人时才建」不再成立、整个不存在;
     * 核心的两处 {@code @Scheduled} 与 OneBot 两处不带限定名的注入, 于是全都落到插件那台上——
     * 而那台线程池单开的理由, 正是不要和别人共用。
     * <p>
     * 把顺序推回去的是每个自报类上的 {@code @AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)}。
     * 它是一行看不出用处的注解, 删掉编译照过、启动照常、界面照开,
     * 坏掉的表现只是定时任务悄悄换了个线程池——所以它需要一格钉着。
     *
     * <h2>为什么不写死一个数字去比</h2>
     * 光断言值等于 {@code Integer.MAX_VALUE} 只是把源码抄一遍。这里比的是
     * {@code TaskSchedulingAutoConfiguration} <b>此刻在类路径上那一份</b>的实际次序值
     * （没有注解就是 {@code AutoConfigureOrder.DEFAULT_ORDER}）:
     * 哪天 Spring Boot 自己也把它调到最低, 这一条会红, 而它本该红——那时这套顺序不再成立。
     */
    @Test
    @DisplayName("每个插件模块的自报类都排在 TaskSchedulingAutoConfiguration 之后, 次序值现算自类路径上的那一份")
    void everySelfDeclarationSortsAfterTaskScheduling() {
        List<String> red = new ArrayList<>();

        int taskSchedulingOrder = Integer.MIN_VALUE;
        try {
            Class<?> taskScheduling = Class.forName(TASK_SCHEDULING);
            taskSchedulingOrder = orderOf(taskScheduling);
        } catch (ClassNotFoundException e) {
            red.add("① 类路径上找不到 " + TASK_SCHEDULING
                    + "; 它换过包名的话, 插件排在它之后这件事得重新量一遍, 不能照旧当成立");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        Map<String, Integer> orders = new LinkedHashMap<>();
        try {
            List<String> modules = pluginModules();
            assertTrue(modules.size() >= 2,
                    "从 build.sh 只读出 " + modules.size() + " 个插件模块, 分母不对, 这一格此刻几乎什么都没量");
            try (URLClassLoader loader = artifactLoader(modules)) {
                for (String module : modules) {
                    Path imports = repoRoot().resolve(module).resolve("target/classes").resolve(IMPORTS);
                    assertTrue(Files.isRegularFile(imports),
                            module + " 的产物里没有自报文件, 取不到要查的类名: " + imports);
                    for (String name : readNames(imports)) {
                        orders.put(name, orderOf(Class.forName(name, false, loader)));
                    }
                }
            }
            assertTrue(orders.size() >= 2,
                    "只查到 " + orders.size() + " 个自报类, 分母不对: " + orders.keySet());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        for (Map.Entry<String, Integer> entry : orders.entrySet()) {
            try {
                assertTrue(entry.getValue() > taskSchedulingOrder,
                        entry.getKey() + " 的 @AutoConfigureOrder 次序值 " + entry.getValue()
                                + " 不比 TaskSchedulingAutoConfiguration 的 " + taskSchedulingOrder
                                + " 大, 它会排在 Boot 的 taskScheduler 之前, 核心的定时任务会落到插件的线程池上");
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 一个自动配置类的实际次序值
     * <p>
     * 没写 {@code @AutoConfigureOrder} 不等于「最低」, 而是 {@code DEFAULT_ORDER}——
     * 把「没写」读成最低的话, 本格对五个都没加注解的树也会绿。
     */
    private static int orderOf(Class<?> type) {
        AutoConfigureOrder annotation = type.getAnnotation(AutoConfigureOrder.class);
        return annotation == null ? AutoConfigureOrder.DEFAULT_ORDER : annotation.value();
    }

    /**
     * 只摆五个模块产物、父加载器为本测试自己的加载器
     * <p>
     * 与下面那支候选清点用的加载器不同, 这支要的是「把类真加载起来、读它身上的注解」,
     * 所以 Spring 那几个注解类必须解析得到, 父加载器不能给 {@code null}。
     */
    private static URLClassLoader artifactLoader(List<String> modules) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String module : modules) {
            urls.add(repoRoot().resolve(module).resolve("target/classes").toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]),
                PluginSelfDeclarationCensusTest.class.getClassLoader());
    }

    /**
     * 让 Spring 自己那套加载器在只摆着五个模块产物的类路径上跑一遍
     * <p>
     * 父加载器给 {@code null}（引导加载器）而不是本测试的加载器：不这样的话，
     * Spring Boot 自己那十几份同名自报文件也会一起被收进来，量到的就不是「插件有几个」了。
     */
    private static java.util.Set<String> candidatesFromArtifacts(List<String> modules) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String module : modules) {
            urls.add(repoRoot().resolve(module).resolve("target/classes").toUri().toURL());
        }

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            java.util.Set<String> found = new TreeSet<>();
            for (String candidate : ImportCandidates.load(AutoConfiguration.class, loader)) {
                found.add(candidate);
            }
            return found;
        }
    }

    /**
     * 现算插件模块名单：谁的 jar 会被放进 plugins 目录，谁就必须自报
     */
    private static List<String> pluginModules() throws IOException {
        String script = Files.readString(repoRoot().resolve("build.sh"), StandardCharsets.UTF_8);
        Matcher matcher = PLUGIN_MODULES.matcher(script);
        if (!matcher.find()) {
            return fail("build.sh 里找不到 PLUGIN_MODULES, 这一格取不到分母");
        }

        List<String> modules = new ArrayList<>();
        for (String token : matcher.group(1).trim().split("\\s+")) {
            if (!token.isBlank()) {
                modules.add(token);
            }
        }
        return List.copyOf(modules);
    }

    private static List<String> readNames(Path imports) throws IOException {
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(imports, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }
}
