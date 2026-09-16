package org.frostnova.nova.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各模块 pom 的项目显示名、简介与项目地址不含上游产品名
 * <p>
 * 作者块与许可证块里可以出现上游作者署名或许可证地址，那是署名义务，不在本格射程。
 * 本格先把 {@code <developers>} 与 {@code <licenses>} 整块剥掉，再看剩下的
 * {@code <name>}／{@code <description>}／{@code <url>} 行——那三栏才是项目自己对外的身份。
 * 小写 b 的 {@code Starbot} 与 {@code starlwr} 域名曾经躲过按 {@code StarBot} 的清点，
 * 故匹配不分大小写。
 * <p>
 * 四问各自捕获、末尾汇总，一次看清还差哪一处；问③钉住列件与读件当真扫到了仓根与处理器模块，
 * 免得路径解析错时问①对着空集「绿」。问④钉住列件进目录前就跳过构建产物目录与隐藏目录，
 * 且这些目录不可读时也不抛。
 */
@DisplayName("各 pom 项目元数据不含上游名")
class PomMetadataHasNoUpstreamNameTest {

    private static final String POM_FILENAME = "pom.xml";

    private static final String PROCESSOR_POM = "build-tools/nova-plugin-processor/pom.xml";

    private static final String REPO_URL = "https://github.com/FrostNovaOrg/NovaBot";

    private static final Pattern META_TAG = Pattern.compile("(?i)<(name|description|url)\\b");

    @TempDir
    Path isolatedRoot;

    @Test
    @DisplayName("剥块后 name、description、url 不含 starbot／starlwr；判定、列件与不可读目录自证")
    void projectMetadataOmitsUpstreamNames() throws Exception {
        List<String> unresolved = new ArrayList<>();
        Path root = repoRoot();

        // 问① 真扫：仓内全部 pom，剥块后再看三栏
        try {
            List<String> hits = new ArrayList<>();
            for (Path pom : listPoms(root)) {
                String rel = relative(root, pom);
                hits.addAll(metadataHits(stripBlocks(readPom(pom)), rel));
            }
            assertTrue(hits.isEmpty(), "项目元数据仍含上游名: " + String.join("、", hits));
        } catch (Throwable t) {
            unresolved.add("问① " + formatCaught(t));
        }

        // 问② 判定自证：与问①同一判定函数、同一剥块函数
        try {
            List<String> nameHit = metadataHits(stripBlocks("<name>StarbotPluginProcessor</name>"), "feed");
            assertTrue(!nameHit.isEmpty(), "判定应认出 name 行里的上游名，实际未中: " + nameHit);

            List<String> urlMiss = metadataHits(stripBlocks("<url>" + REPO_URL + "</url>"), "feed");
            assertTrue(urlMiss.isEmpty(), "本仓地址不应被当成上游名: " + urlMiss);

            List<String> afterStrip = metadataHits(
                    stripBlocks("<developers><developer><name>Starbot</name></developer></developers>"),
                    "feed");
            assertTrue(afterStrip.isEmpty(), "developers 块剥掉后不应再命中: " + afterStrip);
        } catch (Throwable t) {
            unresolved.add("问② " + formatCaught(t));
        }

        // 问③ 阳性锚：与问①同一列件函数、同一读件函数
        try {
            List<Path> poms = listPoms(root);
            List<String> rels = new ArrayList<>();
            for (Path pom : poms) {
                rels.add(relative(root, pom));
            }
            assertTrue(rels.size() >= 10, "列到的 pom 应 ≥10 只，实际 " + rels.size() + ": " + rels);
            assertTrue(rels.contains(POM_FILENAME), "列件须含根 " + POM_FILENAME + "，实际: " + rels);
            assertTrue(rels.contains(PROCESSOR_POM), "列件须含 " + PROCESSOR_POM + "，实际: " + rels);
            String rootText = readPom(root.resolve(POM_FILENAME));
            assertTrue(rootText.contains(REPO_URL), "根 pom 正文须含 " + REPO_URL);
        } catch (Throwable t) {
            unresolved.add("问③ " + formatCaught(t));
        }

        // 问④ 列件跳过构建产物目录与隐藏目录；目录不可读时不抛
        try {
            Path visible = isolatedRoot.resolve("a").resolve(POM_FILENAME);
            Path targets = isolatedRoot.resolve("targets").resolve(POM_FILENAME);
            Path targetPom = isolatedRoot.resolve("target").resolve(POM_FILENAME);
            Path hiddenPom = isolatedRoot.resolve(".hidden").resolve(POM_FILENAME);
            Files.createDirectories(visible.getParent());
            Files.createDirectories(targets.getParent());
            Files.createDirectories(targetPom.getParent());
            Files.createDirectories(hiddenPom.getParent());
            String stub = "<project></project>\n";
            Files.writeString(visible, stub, StandardCharsets.UTF_8);
            Files.writeString(targets, stub, StandardCharsets.UTF_8);
            Files.writeString(targetPom, stub, StandardCharsets.UTF_8);
            Files.writeString(hiddenPom, stub, StandardCharsets.UTF_8);

            Path targetDir = targetPom.getParent();
            Path hiddenDir = hiddenPom.getParent();
            Set<PosixFilePermission> oldTarget = Files.getPosixFilePermissions(targetDir);
            Set<PosixFilePermission> oldHidden = Files.getPosixFilePermissions(hiddenDir);
            try {
                Files.setPosixFilePermissions(targetDir, EnumSet.noneOf(PosixFilePermission.class));
                Files.setPosixFilePermissions(hiddenDir, EnumSet.noneOf(PosixFilePermission.class));
                boolean stillReadable = directoryIsReadable(targetDir) || directoryIsReadable(hiddenDir);
                List<Path> found = listPoms(isolatedRoot);
                List<String> rels = new ArrayList<>();
                for (Path pom : found) {
                    rels.add(relative(isolatedRoot, pom));
                }
                assertEquals(List.of("a/pom.xml", "targets/pom.xml"), rels, "列到的 pom 应为可见两件，实际: " + rels);
                Assumptions.assumeFalse(stillReadable, "000 后目录仍可读，跳过权限这半边");
            } finally {
                Files.setPosixFilePermissions(targetDir, oldTarget);
                Files.setPosixFilePermissions(hiddenDir, oldHidden);
            }
        } catch (TestAbortedException e) {
            // 权限半边跳过；列件断言已跑完
        } catch (Throwable t) {
            unresolved.add("问④ " + formatCaught(t));
        }

        assertTrue(unresolved.isEmpty(),
                () -> "四问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    static List<Path> listPoms(Path root) {
        try {
            List<Path> out = new ArrayList<>();
            Files.walkFileTree(root, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (shouldSkip(root, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && POM_FILENAME.equals(file.getFileName().toString())
                            && !shouldSkip(root, file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // walkFileTree 先打开目录再调 preVisitDirectory；不可读的跳过目录到这里
                    if (exc instanceof NoSuchFileException || shouldSkip(root, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw exc;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (exc != null) {
                        throw exc;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            out.sort(Comparator.comparing(path -> relative(root, path)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("列 pom 失败: " + root, e);
        }
    }

    static String readPom(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 " + pom, e);
        }
    }

    static String stripBlocks(String text) {
        return stripTag(stripTag(text, "developers"), "licenses");
    }

    static List<String> metadataHits(String stripped, String file) {
        List<String> hits = new ArrayList<>();
        String[] lines = stripped.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!META_TAG.matcher(line).find()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("starbot") || lower.contains("starlwr")) {
                hits.add(file + ":" + (i + 1));
            }
        }
        return hits;
    }

    private static String stripTag(String text, String tag) {
        Pattern pattern = Pattern.compile("(?is)<" + tag + ">.*?</" + tag + ">");
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String block = matcher.group();
            for (int i = 0; i < block.length(); i++) {
                if (block.charAt(i) == '\n' || block.charAt(i) == '\r') {
                    out.append(block.charAt(i));
                }
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static boolean shouldSkip(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if ("target".equals(name) || name.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.sh"))
                    && Files.isRegularFile(current.resolve(POM_FILENAME))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private static String formatCaught(Throwable t) {
        StringBuilder names = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (names.length() > 0) {
                names.append('/');
            }
            names.append(cur.getClass().getSimpleName());
            cur = cur.getCause();
        }
        names.append(": ").append(t.getMessage());
        return names.toString();
    }

    private static boolean directoryIsReadable(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.iterator();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
