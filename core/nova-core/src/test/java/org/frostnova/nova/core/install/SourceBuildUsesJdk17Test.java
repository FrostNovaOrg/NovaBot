package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从源码安装时，PATH 上的 Java 高于 17 就另找一把 17 专供构建
 * <p>
 * 抓的用户故障：Linux 机器上装的已经是 21 这类更高的 Java，用源码目录跑 install.sh，
 * 装到构建那一步就停——构建只在 Java 17 上验过本工程，比它高的版本会被当场拦住，
 * 于是安装停在半路，用户得自己去装 17、设好再重跑。
 * <p>
 * 这里把假 java、假包管理器、假构建脚本摆到 PATH 最前面，把这台机器扮成那副样子，
 * 一路跑到构建那一步，看三件事：让包管理器装了什么、构建看到的主版本、构建看到的 JAVA_HOME。
 * 只跑现成产物那一路（目录里没有 build.sh）不在此列：那里 17 及以上照旧放行。
 */
@DisplayName("源码安装另找 Java 17 专供构建")
class SourceBuildUsesJdk17Test {

    /** 屏上那句说明的头几个字。install.sh 改了说法这里就红——这句话正是要钉住的东西 */
    private static final String EXPLAIN = "构建要另用 Java 17";

    /** 装 Java 时那句提示的头几个字，用来核说明赶没赶在安装之前 */
    private static final String INSTALL = "正在安装 JDK 17";

    /** 假包管理器落 17 的目录名，与 Debian 系上 openjdk-17-jdk-headless 的落点同形 */
    private static final String JDK17_DIR = "java-17-openjdk-amd64";

    /**
     * 与 17 并存那把 21 的目录名。名字故意排在 JDK17_DIR 前面（首字母 g 比 j 小）：
     * 版本检查一旦被删，查找就会先撞上这把 21，只挑 17 那格才会红
     */
    private static final String JDK21_DIR = "graalvm-21";

    /** PATH 上那个 java：按环境变量报主版本，好把这台机器扮成装着 21 或装着 17 */
    private static final String STUB_JAVA = """
            #!/bin/sh
            printf 'openjdk version "%s.0.0" 2026-01-01\\n' "${NOVABOT_STUB_JAVA_MAJOR:-21}" >&2
            """;

    /** 装到机器上的那把 Java 17：主版本写死 17，不受 PATH 上那把影响 */
    private static final String STUB_JAVA17 = """
            #!/bin/sh
            echo 'openjdk version "17.0.0" 2026-01-01' >&2
            """;

    /** 与 17 并存的那把 Java 21：主版本写死 21，不受 PATH 上那把影响 */
    private static final String STUB_JAVA21 = """
            #!/bin/sh
            echo 'openjdk version "21.0.0" 2026-01-01' >&2
            """;

    private static final String STUB_JAVAC = """
            #!/bin/sh
            exit 0
            """;

    private static final String STUB_MVN = """
            #!/bin/sh
            echo "mvn $*" >> "${NOVABOT_STUB_LOG}/mvn.log"
            exit 0
            """;

    private static final String STUB_UNAME = """
            #!/bin/sh
            echo Linux
            """;

    private static final String STUB_FC_LIST = """
            #!/bin/sh
            echo "Noto Sans CJK SC"
            """;

    private static final String STUB_SUDO = """
            #!/bin/sh
            exec "$@"
            """;

    /** 假包管理器：记下被要求装的包名；装 JDK 17 那个包时把 17 落进查找目录 */
    private static final String STUB_APT_GET = """
            #!/bin/sh
            echo "apt-get $*" >> "${NOVABOT_STUB_LOG}/pkg.log"
            case "$*" in
              *openjdk-17-jdk-headless*)
                dest="${NOVABOT_STUB_JVM_ROOT}/%s/bin"
                mkdir -p "${dest}"
                cp "${NOVABOT_STUB_TOOLS}/java17" "${dest}/java"
                cp "${NOVABOT_STUB_TOOLS}/javac" "${dest}/javac"
                chmod +x "${dest}/java" "${dest}/javac"
                ;;
            esac
            exit 0
            """.formatted(JDK17_DIR);

    /** 假包管理器（装完什么也不落）：只记下被要求装的包名，查找目录里仍没有 17 */
    private static final String STUB_APT_GET_NOOP = """
            #!/bin/sh
            echo "apt-get $*" >> "${NOVABOT_STUB_LOG}/pkg.log"
            exit 0
            """;

    /** 假构建脚本：记下它看到的主版本、JAVA_HOME 与 java 的落点，随后就地停下 */
    private static final String STUB_BUILD = """
            #!/bin/sh
            out="${NOVABOT_STUB_LOG}/build-env.txt"
            line="$(java -version 2>&1)"
            major="$(printf '%s' "$line" | cut -d'"' -f2 | cut -d'.' -f1)"
            {
              echo "java_major=${major}"
              echo "java_home=${JAVA_HOME:-}"
              echo "java_path=$(command -v java)"
              echo "args=$*"
            } >> "${out}"
            exit 42
            """;

    @Test
    @DisplayName("PATH 上是 21：装上 17，构建那一趟看到 17 与它的目录")
    void pathJava21InstallsJdk17AndBuildsWithIt(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        int exit = runInstall(sandbox, true, "21");

        String output = readLog(sandbox, "out.txt");
        String pkg = readLog(sandbox, "pkg.log");
        String buildEnv = readLog(sandbox, "build-env.txt");
        Path jdk17 = sandbox.resolve("jvmroot").resolve(JDK17_DIR);

        assertEquals(42, exit, "应一路跑到构建那一步（构建桩在那儿停下），输出:\n" + output);
        assertTrue(pkg.contains("openjdk-17-jdk-headless"),
                "包管理器没被要求装 JDK 17，它记下的是:\n" + pkg);
        assertEquals("17", value(buildEnv, "java_major"),
                "构建看到的 Java 主版本不是 17，构建记录:\n" + buildEnv);
        assertEquals(jdk17.toString(), value(buildEnv, "java_home"),
                "构建看到的 JAVA_HOME 不是那把 17 的目录，构建记录:\n" + buildEnv);
        assertEquals(jdk17.resolve("bin").resolve("java").toString(), value(buildEnv, "java_path"),
                "构建那一趟 PATH 没指向 17，构建记录:\n" + buildEnv);

        int explain = output.indexOf(EXPLAIN);
        int install = output.indexOf(INSTALL);
        assertTrue(explain >= 0, "屏幕上没说为什么要另用 Java 17，输出:\n" + output);
        assertTrue(install > explain, "说明没赶在安装之前，输出:\n" + output);

        String installSh = Files.readString(repoRoot().resolve("install.sh"), StandardCharsets.UTF_8);
        assertFalse(installSh.contains("update-alternatives"),
                "install.sh 动了机器上默认的 java（update-alternatives）；17 只该给构建那一趟用: install.sh");
    }

    @Test
    @DisplayName("机器上已有 17：不另装，构建直接用它")
    void jdk17AlreadyOnDiskIsUsedWithoutInstalling(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        Path jdk17 = plantJdk17(sandbox);
        int exit = runInstall(sandbox, true, "21");

        String output = readLog(sandbox, "out.txt");
        String pkg = readLog(sandbox, "pkg.log");
        String buildEnv = readLog(sandbox, "build-env.txt");

        assertEquals(42, exit, "应一路跑到构建那一步（构建桩在那儿停下），输出:\n" + output);
        assertTrue(pkg.isEmpty(), "机器上已有 17，不该再叫包管理器装东西，它记下的是:\n" + pkg);
        assertEquals("17", value(buildEnv, "java_major"),
                "构建看到的 Java 主版本不是 17，构建记录:\n" + buildEnv);
        assertEquals(jdk17.toString(), value(buildEnv, "java_home"),
                "构建该用机器上那把 17，构建记录:\n" + buildEnv);
    }

    @Test
    @DisplayName("PATH 上就是 17：不另装")
    void machineOn17DoesNotInstallAnother(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        int exit = runInstall(sandbox, true, "17");

        String output = readLog(sandbox, "out.txt");
        String pkg = readLog(sandbox, "pkg.log");
        String buildEnv = readLog(sandbox, "build-env.txt");

        assertEquals(42, exit, "应一路跑到构建那一步（构建桩在那儿停下），输出:\n" + output);
        assertTrue(pkg.isEmpty(), "PATH 上就是 17，不该另装，包管理器记下的是:\n" + pkg);
        assertEquals("17", value(buildEnv, "java_major"),
                "构建看到的 Java 主版本不是 17，构建记录:\n" + buildEnv);
        assertEquals("", value(buildEnv, "java_home"),
                "PATH 上就是 17 时不该另指定 JAVA_HOME，构建记录:\n" + buildEnv);
        assertFalse(output.contains(EXPLAIN), "这副样子不用说另用 17，输出:\n" + output);
    }

    @Test
    @DisplayName("只跑现成产物那一路遇 21 照旧放行")
    void releaseOnlyPathStillPassesAtJava21(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        int exit = runInstall(sandbox, false, "21");

        String output = readLog(sandbox, "out.txt");
        String pkg = readLog(sandbox, "pkg.log");

        assertEquals(1, exit, "该走到「未找到构建产物」那一步停下，输出:\n" + output);
        assertTrue(pkg.isEmpty(), "只跑现成产物那一路不该装 Java，包管理器记下的是:\n" + pkg);
        assertTrue(output.contains("Java 版本："), "没走到印 Java 版本那一行，输出:\n" + output);
        assertTrue(output.contains("未找到构建产物"), "没走到构建产物那一关，输出:\n" + output);
        assertFalse(output.contains(EXPLAIN), "只跑现成产物那一路不该说要另用 17，输出:\n" + output);
    }

    /**
     * 抓的用户故障：包管理器说装好了，jvm 查找目录里却仍没有 17——这时该停下问人，
     * 不是拿 PATH 上那把高版本继续构建。
     */
    @Test
    @DisplayName("装完仍找不到 17：停下，不拿高版本构建")
    void jdk17MissingAfterInstallStopsWithoutBuilding(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        writeExecutable(sandbox.resolve("bin").resolve("apt-get"), STUB_APT_GET_NOOP);
        int exit = runInstall(sandbox, true, "21");

        String output = readLog(sandbox, "out.txt");
        String pkg = readLog(sandbox, "pkg.log");
        String buildEnv = readLog(sandbox, "build-env.txt");

        assertTrue(pkg.contains("openjdk-17-jdk-headless"),
                "没走到装 JDK 17 那一步，包管理器记下的是:\n" + pkg);
        assertTrue(exit != 0, "装完仍找不到 17 应当停下（退码非 0），实际退码 " + exit + "，输出:\n" + output);
        assertTrue(buildEnv.isEmpty(), "构建桩不该被调用，构建记录:\n" + buildEnv);
        assertTrue(output.contains("装好 JDK 17 后仍没找到它"),
                "屏幕上没说出装完仍找不到 17，输出:\n" + output);
        assertTrue(output.contains("不会改用现在的 Java"),
                "屏幕上没说不拿高版本继续构建，输出:\n" + output);
    }

    /**
     * 抓的用户故障：机器上 17 与 21 并存，查找时不看主版本就会把 21 的目录交给构建。
     * 两把都带 javac，只有问过主版本才分得出。
     */
    @Test
    @DisplayName("17 与 21 并存：只挑 17")
    void coexistingJdk21And17PicksOnly17(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        plantJdk21(sandbox);
        Path jdk17 = plantJdk17(sandbox);
        int exit = runInstall(sandbox, true, "21");

        String output = readLog(sandbox, "out.txt");
        String buildEnv = readLog(sandbox, "build-env.txt");

        assertEquals(42, exit, "应一路跑到构建那一步（构建桩在那儿停下），输出:\n" + output);
        assertEquals("17", value(buildEnv, "java_major"),
                "并存时该只挑 17，构建记录:\n" + buildEnv);
        assertEquals(jdk17.toString(), value(buildEnv, "java_home"),
                "并存时该挑 17 那个目录，构建记录:\n" + buildEnv);
    }

    /**
     * 抓的用户故障：源码构建遇上 11 这类低版本，包管理器装了 17 却没把它设成默认 java——
     * 该停下报「安装后仍不可用」，不能当成装好了继续，服务会起在旧 java 上。
     */
    @Test
    @DisplayName("低于 17：装完默认 Java 仍是 11 就停")
    void below17StopsWhenDefaultJavaStillOldAfterInstall(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox);
        int exit = runInstall(sandbox, true, "11");

        String output = readLog(sandbox, "out.txt");
        String buildEnv = readLog(sandbox, "build-env.txt");

        assertTrue(output.contains("安装后仍不可用"),
                "装完默认 Java 仍不够时该停下报这句，输出:\n" + output);
        assertTrue(buildEnv.isEmpty(), "构建桩不该被调用，构建记录:\n" + buildEnv);
        assertTrue(exit != 0, "应当停下（退码非 0），实际退码 " + exit + "，输出:\n" + output);
    }

    private static void writeSandbox(Path sandbox) throws IOException {
        Path bin = Files.createDirectories(sandbox.resolve("bin"));
        Path tools = Files.createDirectories(sandbox.resolve("tools"));
        Files.createDirectories(sandbox.resolve("logs"));
        Files.createDirectories(sandbox.resolve("jvmroot"));
        Files.createDirectories(sandbox.resolve("work"));

        writeExecutable(bin.resolve("java"), STUB_JAVA);
        writeExecutable(bin.resolve("javac"), STUB_JAVAC);
        writeExecutable(bin.resolve("mvn"), STUB_MVN);
        writeExecutable(bin.resolve("uname"), STUB_UNAME);
        writeExecutable(bin.resolve("fc-list"), STUB_FC_LIST);
        writeExecutable(bin.resolve("sudo"), STUB_SUDO);
        writeExecutable(bin.resolve("apt-get"), STUB_APT_GET);
        writeExecutable(tools.resolve("java17"), STUB_JAVA17);
        writeExecutable(tools.resolve("javac"), STUB_JAVAC);
    }

    /** 事先在查找目录里放一把装好的 17，扮成「机器上已经有 17」 */
    private static Path plantJdk17(Path sandbox) throws IOException {
        Path bin = Files.createDirectories(sandbox.resolve("jvmroot").resolve(JDK17_DIR).resolve("bin"));
        writeExecutable(bin.resolve("java"), STUB_JAVA17);
        writeExecutable(bin.resolve("javac"), STUB_JAVAC);
        return bin.getParent();
    }

    /** 事先在查找目录里再放一把装好的 21，扮成「17 与 21 并存」里那把 21 */
    private static Path plantJdk21(Path sandbox) throws IOException {
        Path bin = Files.createDirectories(sandbox.resolve("jvmroot").resolve(JDK21_DIR).resolve("bin"));
        writeExecutable(bin.resolve("java"), STUB_JAVA21);
        writeExecutable(bin.resolve("javac"), STUB_JAVAC);
        return bin.getParent();
    }

    /**
     * 把 install.sh 拷进沙箱跑一遍：脚本按自己所在目录认安装源，不拷一份的话
     * 会跑到仓库里那个 build.sh 上去，真把整个工程构建起来。
     */
    private static int runInstall(Path sandbox, boolean withBuildScript, String pathJavaMajor)
            throws IOException, InterruptedException {
        Path work = sandbox.resolve("work");
        Files.copy(repoRoot().resolve("install.sh"), work.resolve("install.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        if (withBuildScript) {
            writeExecutable(work.resolve("build.sh"), STUB_BUILD);
        } else {
            Files.deleteIfExists(work.resolve("build.sh"));
        }

        ProcessBuilder builder = new ProcessBuilder("bash", work.resolve("install.sh").toString());
        Map<String, String> env = builder.environment();
        env.put("PATH", sandbox.resolve("bin") + File.pathSeparator + env.getOrDefault("PATH", ""));
        env.remove("JAVA_HOME");
        env.put("NOVABOT_STUB_JAVA_MAJOR", pathJavaMajor);
        env.put("NOVABOT_STUB_LOG", sandbox.resolve("logs").toString());
        env.put("NOVABOT_STUB_JVM_ROOT", sandbox.resolve("jvmroot").toString());
        env.put("NOVABOT_STUB_TOOLS", sandbox.resolve("tools").toString());
        env.put("NOVABOT_JVM_ROOTS", sandbox.resolve("jvmroot").toString());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "install.sh 跑了 60 秒还没结束:\n" + output);
        Files.writeString(sandbox.resolve("logs").resolve("out.txt"), output, StandardCharsets.UTF_8);
        return process.exitValue();
    }

    private static String readLog(Path sandbox, String name) throws IOException {
        Path file = sandbox.resolve("logs").resolve(name);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /** 取记录里某个键的值；没有这个键时返回 null，好让断言说清是缺了哪一栏 */
    private static String value(String record, String key) {
        for (String line : record.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(file));
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(file, perms);
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
