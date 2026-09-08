package com.starlwr.bot.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画图这件事已经整个搬去报告图插件，核心不再自己画
 * <p>
 * 核心侧原本摆着一个绘图器与一个绘图器工厂，而核心自己从不画图——主码里唯一的消费方
 * 是报告图插件。一个只有插件用得着的类留在核心里，会让「核心是什么」这条线越来越糊，
 * 也让第三方插件误以为绘图器是核心承诺的一部分。
 * <p>
 * 搬走涉及两件事，只做其一都不会有人报错，因此分四问：
 * <ul>
 *   <li>① <b>核心侧没了</b>：主码里不再有 {@code painter}／{@code factory} 这两个包目录。</li>
 *   <li>② <b>核心侧不再引</b>：主码里零处 {@code import ...core.painter}／{@code ...core.factory}。
 *       目录搬空而某处还写着旧全类名的话，编译会红，但那时先炸的是别的模块。</li>
 *   <li>③ <b>是搬走不是删掉</b>：四个类当真落在报告图插件的源码树里。缺了这一问，
 *       把它们整个删掉也一样绿——问①②量的都是「核心侧没有」，答不了「那它在哪」。</li>
 *   <li>④ <b>字体跟着走了</b>：内置字体是 {@code FontUtil} 用 {@code classpath:fonts/font.ttf}
 *       读的，类搬了而资源留在核心 jar 里，编译与单测全绿，要到一台没装中文字体的机器上
 *       画第一张图时才炸。</li>
 * </ul>
 * 四问各自捕获、末尾汇总，先红时一次看清还差哪几处；每问各带一个阳性锚，
 * 免得路径整个解析错时四问一起「绿」。
 *
 * <h2>为什么按包路径现找模块，而不写模块目录名</h2>
 * 本格问的是「类落在哪个<b>包</b>里」，模块目录叫什么名字与这个问题无关。写死目录名的代价
 * 是安静的：目录重排的那一天，写着旧名的路径解析成一个不存在的目录，问①②量到的是空——
 * 而空目录里当然没有 {@code painter} 包，四问会一起变绿。所以模块根一律现找：仓根下
 * 摆着 {@code pom.xml} 的直接子目录就是模块，再看它的主码里有没有那个包路径。
 * <p>
 * 核心那个包根<b>横跨两个模块</b>（里层与运行壳各一份），故问①②对找到的每一个都问一遍；
 * 只挑一个的话，画图的包被挪进另一个模块时本格不会说话。
 */
@DisplayName("画图已迁出核心")
class PaintingMovedToReportTest {
    /** Maven 布局里主码与主资源的位置，与模块叫什么名字无关 */
    private static final String MAIN_JAVA = "src/main/java";

    private static final String MAIN_RESOURCES = "src/main/resources";

    /** 核心的包根，相对主码目录 */
    private static final String CORE_PACKAGE = "com/starlwr/bot/core";

    /** 报告图插件的包根，相对主码目录 */
    private static final String REPORT_PACKAGE = "com/starlwr/bot/report";

    /** 这两个包目录整个不该再出现在核心主码下 */
    private static final List<String> GONE_PACKAGES = List.of("painter", "factory");

    /**
     * 阳性锚：核心主码下确实还留着的包，用来证明本格当真找对了源码根
     * <p>
     * 写成候选表而不是一个包名，是因为核心那个包根横跨的两个模块，底下留着的包并不同名：
     * 里层剩 {@code datasource}，运行壳剩 {@code service}，两边的包集眼下没有交集。写死单个
     * 名字的话，填哪一个都会在另一个模块上判红——所以每个模块命中表里任意一个即算找对。
     * <p>
     * 表里刻意避开 {@code util} 与 {@code config}：这两个名字当下两个模块都有，看着最像锚，
     * 可它们正是接下来要从里层搬走的包，拿来当锚等于把本格钉在一次搬家的中途。
     */
    private static final List<String> LIVE_PACKAGES = List.of("datasource", "service");

    /** 阳性锚：核心的主资源里确实还留着的一个目录，用来证明问④看的是核心的资源根 */
    private static final String LIVE_RESOURCE_DIR = "config-ui";

    /** 搬过去的四个类，相对报告图插件的包根 */
    private static final List<String> LANDED = List.of(
            "painter/CommonPainter",
            "factory/StarBotCommonPainterFactory",
            "util/FontUtil",
            "util/ImageUtil");

    /** 内置字体资源，相对各模块的 {@code src/main/resources} */
    private static final String BUNDLED_FONT = "fonts/font.ttf";

    @Test
    @DisplayName("核心主码里没了这两个包、不再引它们, 四个类落在报告图插件里, 内置字体跟着走")
    void paintingNoLongerLivesInCore() {
        List<String> unresolved = new ArrayList<>();
        List<Path> coreModules = modulesHolding(CORE_PACKAGE);
        List<Path> reportModules = modulesHolding(REPORT_PACKAGE);

        // 问①：两个包目录不在核心主码下
        try {
            assertTrue(!coreModules.isEmpty(),
                    "阳性锚: 该有模块的主码带着 " + CORE_PACKAGE + " 包根, 一个都找不到说明本格没找对源码根");
            for (Path module : coreModules) {
                Path packageRoot = mainPackage(module, CORE_PACKAGE);
                assertTrue(LIVE_PACKAGES.stream().anyMatch(pkg -> Files.isDirectory(packageRoot.resolve(pkg))),
                        "阳性锚: " + relative(module) + " 的核心主码下该看得见 "
                                + String.join("、", LIVE_PACKAGES) + " 里的至少一个包");
            }

            List<String> left = new ArrayList<>();
            for (Path module : coreModules) {
                for (String pkg : GONE_PACKAGES) {
                    if (Files.exists(mainPackage(module, CORE_PACKAGE).resolve(pkg))) {
                        left.add(relative(module) + " 的 " + pkg);
                    }
                }
            }
            assertTrue(left.isEmpty(), "核心主码下仍留着画图的包: " + String.join("、", left));
        } catch (AssertionError e) {
            unresolved.add("问① " + e.getMessage());
        }

        // 问②：核心主码零处引用那两个包
        try {
            List<String> coreSources = new ArrayList<>();
            coreModules.forEach(module -> coreSources.addAll(javaSourcesUnder(mainPackage(module, CORE_PACKAGE))));
            assertTrue(coreSources.size() > 100,
                    "阳性锚: 核心主码该有上百个 java 件, 只数出 " + coreSources.size() + " 个说明本问什么也没扫到");

            List<String> offenders = new ArrayList<>();
            for (String source : coreSources) {
                for (String pkg : GONE_PACKAGES) {
                    if (source.contains("import com.starlwr.bot.core." + pkg + ".")) {
                        offenders.add(pkg);
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "核心主码里仍有件 import 了已迁走的包: " + String.join("、", offenders));
        } catch (AssertionError e) {
            unresolved.add("问② " + e.getMessage());
        }

        // 问③：四个类当真在报告图插件里（答「那它在哪」）
        try {
            assertTrue(!reportModules.isEmpty(),
                    "阳性锚: 该有模块的主码带着 " + REPORT_PACKAGE + " 包根, 找不到说明本问的路径整个解析错了");

            List<String> missing = new ArrayList<>();
            for (String relative : LANDED) {
                boolean landed = reportModules.stream().anyMatch(module ->
                        Files.isRegularFile(mainPackage(module, REPORT_PACKAGE).resolve(relative + ".java")));
                if (!landed) {
                    missing.add(relative);
                }
            }
            assertTrue(missing.isEmpty(),
                    "这几个类没落在报告图插件里, 它们是被删掉了而不是被搬走: " + String.join("、", missing));
        } catch (AssertionError e) {
            unresolved.add("问③ " + e.getMessage());
        }

        // 问④：内置字体跟着 FontUtil 走
        try {
            assertTrue(coreModules.stream().anyMatch(module ->
                            Files.isDirectory(module.resolve(MAIN_RESOURCES).resolve(LIVE_RESOURCE_DIR))),
                    "阳性锚: 核心的界面资源目录须在, 不在说明本问看的不是核心的资源根");

            assertTrue(reportModules.stream().anyMatch(module ->
                            Files.isRegularFile(module.resolve(MAIN_RESOURCES).resolve(BUNDLED_FONT))),
                    "内置字体没跟着 FontUtil 走: 报告图插件的 jar 里没有 " + BUNDLED_FONT
                            + ", classpath 读不到它, 画第一张图时才会炸");

            List<String> stale = coreModules.stream()
                    .filter(module -> Files.exists(module.resolve(MAIN_RESOURCES).resolve(BUNDLED_FONT)))
                    .map(PaintingMovedToReportTest::relative)
                    .toList();
            assertTrue(stale.isEmpty(),
                    "核心的 jar 里还留着一份 " + BUNDLED_FONT + ", 已经没有谁读它了: " + String.join("、", stale));
        } catch (AssertionError e) {
            unresolved.add("问④ " + e.getMessage());
        }

        assertTrue(unresolved.isEmpty(),
                () -> "四问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    /** 主码里带着这个包路径的模块根；模块叫什么名字本格不认 */
    private static List<Path> modulesHolding(String packagePath) {
        return moduleRoots().stream()
                .filter(module -> Files.isDirectory(mainPackage(module, packagePath)))
                .toList();
    }

    /** 仓根下的模块根：直接子目录里摆着 {@code pom.xml} 的那些 */
    private static List<Path> moduleRoots() {
        try (Stream<Path> children = Files.list(repoRoot())) {
            return children.filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("pom.xml")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不到仓库根目录下的模块", e);
        }
    }

    private static Path mainPackage(Path module, String packagePath) {
        return module.resolve(MAIN_JAVA).resolve(packagePath);
    }

    /** 报错时给人看的位置：相对仓根，这样说得出是哪个模块，而源码里不必写死它的名字 */
    private static String relative(Path module) {
        return repoRoot().relativize(module).toString();
    }

    /** 目录下所有 java 件的正文；目录不在时回空表，让阳性锚去报这件事 */
    private static List<String> javaSourcesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> sources = new ArrayList<>();
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().endsWith(".java")) {
                    sources.add(Files.readString(path, StandardCharsets.UTF_8));
                }
            }
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 " + root, e);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.sh")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }
}
