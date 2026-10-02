package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台要能列出内置插件和使用者自己放的插件各自的配置项。
 * <p>
 * 程序搬进版本目录之后，内置插件不在工作目录的 {@code plugins} 里。
 * 只读工作目录的话，这些配置项会从设置页上消失，而且不报错。
 * 扁平布局下工作目录就是程序目录，同一个 jar 不能列两遍。
 */
@DisplayName("控制台读两处插件目录的配置项")
class PluginMetadataFromBothDirectoriesTest {

    private static final String BUILTIN = "novabot.probe.splitLayout.builtin";

    private static final String THIRD = "novabot.probe.splitLayout.thirdParty";

    private static final String ONCE = "novabot.probe.splitLayout.once";

    private static final String LOADER_PATH = "loader.path";

    private final List<Path> created = new ArrayList<>();

    private String previousLoaderPath;

    private boolean hadLoaderPath;

    @AfterEach
    void cleanup() throws IOException {
        if (hadLoaderPath) {
            System.setProperty(LOADER_PATH, previousLoaderPath);
        } else {
            System.clearProperty(LOADER_PATH);
        }
        for (int i = created.size() - 1; i >= 0; i--) {
            Path path = created.get(i);
            if (Files.isDirectory(path)) {
                try (var listed = Files.list(path)) {
                    if (listed.findAny().isEmpty()) {
                        Files.deleteIfExists(path);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("内置插件在程序目录、第三方在数据目录时，两边的配置项都列出")
    void listsBuiltinAndThirdParty(@TempDir Path dir) throws Exception {
        Path programPlugins = dir.resolve("releases").resolve("9.9.9").resolve("plugins");
        Path dataPlugins = Path.of("plugins");
        boolean dataDirExisted = Files.isDirectory(dataPlugins);
        if (!dataDirExisted) {
            Files.createDirectories(dataPlugins);
            created.add(dataPlugins);
        }
        writePlugin(programPlugins.resolve("probe-builtin.jar"), BUILTIN);
        writePlugin(dataPlugins.resolve("probe-third-party.jar"), THIRD);
        previousLoaderPath = System.getProperty(LOADER_PATH);
        hadLoaderPath = previousLoaderPath != null;
        System.setProperty(LOADER_PATH, String.join(",",
                dir.resolve("releases").resolve("9.9.9").resolve("lib").toAbsolutePath().toString(),
                programPlugins.toAbsolutePath().toString(),
                dir.resolve("releases").resolve("9.9.9").resolve("plugins-lib").toAbsolutePath().toString(),
                dataPlugins.toAbsolutePath().toString(),
                Path.of("plugins-lib").toAbsolutePath().toString()));

        List<String> names = new ConfigurationMetadataService().getFields().stream()
                .map(ConfigurationMetadataService.ConfigurationField::name)
                .toList();

        assertTrue(names.contains(THIRD),
                "数据目录里的第三方插件配置项应列出。实际没有 " + THIRD);
        assertTrue(names.contains(BUILTIN),
                "程序目录里的内置插件配置项应列出，现码只看见数据目录那一处。实际没有 " + BUILTIN);
    }

    @Test
    @DisplayName("扁平布局下同一个插件 jar 只读一遍")
    void flatLayoutReadsEachJarOnce() throws Exception {
        Path dataPlugins = Path.of("plugins");
        boolean dataDirExisted = Files.isDirectory(dataPlugins);
        if (!dataDirExisted) {
            Files.createDirectories(dataPlugins);
            created.add(dataPlugins);
        }
        writePlugin(dataPlugins.resolve("probe-once.jar"), ONCE);
        previousLoaderPath = System.getProperty(LOADER_PATH);
        hadLoaderPath = previousLoaderPath != null;
        System.setProperty(LOADER_PATH, "lib,plugins,plugins-lib," + dataPlugins.toAbsolutePath());

        ConfigurationMetadataService service = new ConfigurationMetadataService();
        long jars = service.pluginJarsRead().stream()
                .filter(path -> path.getFileName().toString().equals("probe-once.jar"))
                .count();
        long fields = service.getFields().stream()
                .filter(field -> ONCE.equals(field.name()))
                .count();

        assertEquals(1, jars, "同一个 jar 被列了 " + jars + " 遍");
        assertEquals(1, fields, "同一个配置项被列了 " + fields + " 遍");
    }

    private void writePlugin(Path jar, String property) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/spring-configuration-metadata.json"));
            String json = "{\"properties\":[{\"name\":\"" + property
                    + "\",\"type\":\"java.lang.String\",\"description\":\"probe\"}]}";
            jarOut.write(json.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        created.add(jar);
    }
}
