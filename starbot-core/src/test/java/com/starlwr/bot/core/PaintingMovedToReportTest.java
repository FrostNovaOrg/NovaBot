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
 */
@DisplayName("画图已迁出核心")
class PaintingMovedToReportTest {
    /** 核心主码的包根 */
    private static final String CORE_MAIN = "starbot-core/src/main/java/com/starlwr/bot/core";

    /** 报告图插件主码的包根 */
    private static final String REPORT_MAIN = "starbot-report/src/main/java/com/starlwr/bot/report";

    /** 这两个包目录整个不该再出现在核心主码下 */
    private static final List<String> GONE_PACKAGES = List.of("painter", "factory");

    /** 阳性锚：核心主码下确实还留着的一个包，用来证明本格当真找对了源码根 */
    private static final String LIVE_PACKAGE = "service";

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

        // 问①：两个包目录不在核心主码下
        try {
            assertTrue(Files.isDirectory(repoRoot().resolve(CORE_MAIN).resolve(LIVE_PACKAGE)),
                    "阳性锚: 核心主码下该看得见 " + LIVE_PACKAGE + " 包, 看不见说明本格找错了源码根");

            List<String> left = new ArrayList<>();
            for (String pkg : GONE_PACKAGES) {
                if (Files.exists(repoRoot().resolve(CORE_MAIN).resolve(pkg))) {
                    left.add(pkg);
                }
            }
            assertTrue(left.isEmpty(), "核心主码下仍留着画图的包: " + String.join("、", left));
        } catch (AssertionError e) {
            unresolved.add("问① " + e.getMessage());
        }

        // 问②：核心主码零处引用那两个包
        try {
            List<String> coreSources = javaSourcesUnder(repoRoot().resolve(CORE_MAIN));
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
            assertTrue(Files.isDirectory(repoRoot().resolve(REPORT_MAIN)),
                    "阳性锚: 报告图插件的包根须在, 不在说明本问的路径整个解析错了");

            List<String> missing = new ArrayList<>();
            for (String relative : LANDED) {
                Path landed = repoRoot().resolve(REPORT_MAIN).resolve(relative + ".java");
                if (!Files.isRegularFile(landed)) {
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
            Path coreResources = repoRoot().resolve("starbot-core/src/main/resources");
            assertTrue(Files.isDirectory(coreResources.resolve("config-ui")),
                    "阳性锚: 核心的界面资源目录须在, 不在说明本问看的不是核心的资源根");

            assertTrue(Files.isRegularFile(
                            repoRoot().resolve("starbot-report/src/main/resources").resolve(BUNDLED_FONT)),
                    "内置字体没跟着 FontUtil 走: 报告图插件的 jar 里没有 " + BUNDLED_FONT
                            + ", classpath 读不到它, 画第一张图时才会炸");
            assertTrue(!Files.exists(coreResources.resolve(BUNDLED_FONT)),
                    "核心的 jar 里还留着一份 " + BUNDLED_FONT + ", 已经没有谁读它了");
        } catch (AssertionError e) {
            unresolved.add("问④ " + e.getMessage());
        }

        assertTrue(unresolved.isEmpty(),
                () -> "四问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
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
