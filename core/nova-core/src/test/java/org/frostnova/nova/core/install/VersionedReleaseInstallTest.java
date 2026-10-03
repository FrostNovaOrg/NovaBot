package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安装脚本把程序放进 releases/版本/，服务按版本起，备份不带走程序目录。
 * <p>
 * 替身放在 PATH 前面：uname、id、java、fc-list、chown、tee、systemctl。
 * 不需要 root，也不往系统里装服务。安装脚本按自己所在目录认发布包，
 * 所以每格都把脚本拷进临时包里跑，仓库里的 build.sh 不会被带上。
 */
@DisplayName("安装脚本按版本分目录装程序")
class VersionedReleaseInstallTest {

    static final String FLAT_REFUSAL = "这一版的安装脚本还不能从旧布局升级";

    static final String RUNNING_REFUSAL = "正在运行，这一次没有改安装目录里的文件";

    private static final String UNIT_FIXTURE = """
            [Service]
            User=starbot
            Group=starbot
            WorkingDirectory=/opt/starbot
            ExecStart=/opt/starbot/releases/%i/start.sh
            Restart=on-failure
            ProtectSystem=strict
            ReadWritePaths=/opt/starbot
            MemoryHigh=1.2G
            MemoryMax=1.5G
            """;

    private static final String STUB_UNAME = """
            #!/bin/sh
            echo Linux
            """;

    private static final String STUB_ID = """
            #!/bin/sh
            if [ "$1" = "-u" ]; then
              echo 0
            fi
            exit 0
            """;

    private static final String STUB_JAVA = """
            #!/bin/sh
            echo 'openjdk version "17.0.0" 2026-01-01' >&2
            exit 0
            """;

    private static final String STUB_FC_LIST = """
            #!/bin/sh
            echo "Noto Sans CJK SC"
            """;

    private static final String STUB_CHOWN = """
            #!/bin/sh
            exit 0
            """;

    private static final String STUB_USERADD = """
            #!/bin/sh
            exit 0
            """;

    private static final String STUB_TEE = """
            #!/usr/bin/env python3
            import os, sys
            data = sys.stdin.buffer.read()
            path = ""
            for arg in sys.argv[1:]:
                if not arg.startswith("-"):
                    path = arg
                    break
            dest_root = os.environ.get("NOVABOT_STUB_ETC", "")
            if path.startswith("/etc/") and dest_root:
                dest = os.path.join(dest_root, path[len("/etc/"):])
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as out:
                    out.write(data)
            elif path:
                os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
                with open(path, "wb") as out:
                    out.write(data)
            sys.stdout.buffer.write(data)
            """;

    private static final String STUB_SYSTEMCTL = """
            #!/usr/bin/env python3
            import os, sys
            args = sys.argv[1:]
            log = os.environ.get("NOVABOT_STUB_LOG", "")
            if log:
                with open(os.path.join(log, "systemctl.log"), "a", encoding="utf-8") as out:
                    out.write(" ".join(args) + "\\n")

            def listed(name):
                raw = os.environ.get(name, "")
                return [item for item in raw.split(",") if item]

            def version_of(unit):
                if "@" not in unit:
                    return ""
                return unit.split("@", 1)[1].removesuffix(".service")

            if "is-active" in args:
                if version_of(args[-1]) in listed("NOVABOT_STUB_ACTIVE"):
                    sys.exit(0)
                sys.exit(3)
            if "is-enabled" in args:
                if version_of(args[-1]) in listed("NOVABOT_STUB_ENABLED"):
                    sys.exit(0)
                sys.exit(1)
            if "list-unit-files" in args:
                print("novabot@.service enabled enabled")
                sys.exit(0)
            sys.exit(0)
            """;

    @Test
    @DisplayName("新装：程序在 releases/版本 下，根上没有程序，也没有内置插件")
    void freshInstallPutsProgramUnderRelease(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.7.9");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> kept = readKept(world.install);
        Run run = run(world, "", "");

        assertFresh(world, run, kept);
    }

    @Test
    @DisplayName("分目录上再装一版：留下上一版和正在跑的，更旧的删掉，自启换到新版本")
    void upgradeKeepsPreviousAndSwitchesAutostart(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeRelease(world.install, "5.7.8", "old-578");
        writeRelease(world.install, "5.7.9", "old-579");
        Files.createDirectories(world.install.resolve("plugins"));
        Files.createDirectories(world.install.resolve("plugins-lib"));
        Files.writeString(world.install.resolve("plugins/my-extra.jar"), "user-plugin", StandardCharsets.UTF_8);
        Files.writeString(world.install.resolve("plugins-lib/my-dep.jar"), "user-lib", StandardCharsets.UTF_8);
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> kept = readKept(world.install);
        Run run = run(world, "5.7.7", "5.7.9");

        assertUpgrade(world, run, kept);
    }

    @Test
    @DisplayName("扁平布局：一个文件都不改，退非 0，说明在标准错误")
    void flatLayoutRefusesWithoutTouchingFiles(@TempDir Path dir) throws Exception {
        assertFlat(dir.resolve("by-nova"), "NovaBot.jar");
        assertFlat(dir.resolve("by-star"), "StarBotCore.jar");
    }

    @Test
    @DisplayName("备份不带走 releases")
    void backupSkipsReleases(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path dst = dir.resolve("dst");
        Files.createDirectories(src.resolve("releases/5.7.9"));
        Files.createDirectories(src.resolve("reports"));
        Files.createDirectories(dst);
        Files.writeString(src.resolve("sessions.jsonl"), "session-line\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("releases/5.7.9/NovaBot.jar"), "program", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("reports/x.png"), "PNG\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("notes.txt"), "keep-me\n", StandardCharsets.UTF_8);

        Path script = repoRoot().resolve("dist/templates/tools/data-backup.sh");
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString(), src.toString(), dst.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "备份脚本超时:\n" + output);

        assertEquals(0, process.exitValue(), "像数据目录的备份应成功。输出:\n" + output);
        assertEquals("session-line\n", Files.readString(dst.resolve("sessions.jsonl"), StandardCharsets.UTF_8));
        assertEquals("keep-me\n", Files.readString(dst.resolve("notes.txt"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dst.resolve("releases")),
                "releases 是程序目录，不该进备份。输出:\n" + output);
        assertFalse(Files.exists(dst.resolve("reports")), "reports 仍不该进备份");
    }

    @Test
    @DisplayName("要装的版本正在跑：停下说明，不改件")
    void runningTargetVersionStopsWithoutChanges(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.7.9");
        writeRelease(world.install, "5.7.9", "running-jar");
        Files.createDirectories(world.install.resolve("plugins"));
        Files.writeString(world.install.resolve("plugins/my-extra.jar"), "user-plugin", StandardCharsets.UTF_8);
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> before = snapshot(world.install);
        Run run = run(world, "5.7.9", "");

        assertRunningUntouched(world, run, before);
    }

    @Test
    @DisplayName("构建信息在写出版本那一行之前取到产品版本，打包共用这一次")
    void buildInfoRecordsProductVersion() throws IOException {
        String build = Files.readString(repoRoot().resolve("build.sh"), StandardCharsets.UTF_8);
        int infoAt = build.indexOf("> \"$OUT/BUILD-INFO\"");
        int versionAt = build.indexOf("echo \"version=$VERSION\"");
        int fetchAt = build.indexOf("help:evaluate -Dexpression=project.version");
        int fetches = build.split("help:evaluate -Dexpression=project.version", -1).length - 1;

        assertTrue(infoAt >= 0, "BUILD-INFO 的写出点不在了");
        assertTrue(fetchAt >= 0 && fetchAt < infoAt,
                "取版本应挪到写 BUILD-INFO 之前，并且就是原来那次 mvn help:evaluate");
        assertEquals(1, fetches, "取版本应只留一处，打包步共用同一个变量，实际 " + fetches + " 处");
        assertTrue(versionAt >= 0 && versionAt < infoAt,
                "BUILD-INFO 应写出 version=$VERSION，空版本不该写进去");
    }

    @Test
    @DisplayName("模板单元：工作目录和可写路径是安装目录，启动的是版本目录里的脚本")
    void serviceTemplateStartsVersionedScript() throws IOException {
        String unit = Files.readString(repoRoot().resolve("dist/templates/novabot@.service"), StandardCharsets.UTF_8);
        assertTrue(unit.contains("WorkingDirectory=/opt/starbot"), unit);
        assertTrue(unit.contains("ReadWritePaths=/opt/starbot"), unit);
        assertTrue(unit.contains("ExecStart=/opt/starbot/releases/%i/start.sh"), unit);
        assertFalse(unit.contains("ExecStart=/opt/starbot/start.sh"), unit);
        assertTrue(unit.contains("Restart=on-failure"), unit);
        assertTrue(unit.contains("ProtectSystem=strict"), unit);
        assertTrue(unit.contains("MemoryHigh=1.2G"), unit);
        assertTrue(unit.contains("MemoryMax=1.5G"), unit);
    }

    @Test
    @DisplayName("再升级：单元文件列表只有模板时，仍关掉旧版本的开机自启")
    void upgradeDisablesOldInstanceWhenUnitListShowsOnlyTheTemplate(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeRelease(world.install, "5.7.8", "old-578");
        writeRelease(world.install, "5.7.9", "old-579");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "5.7.9");

        assertEquals(0, run.code, "分目录上再装一版应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        String calls = read(world.logs.resolve("systemctl.log"));
        assertTrue(calls.contains("disable novabot@5.7.9"),
                "旧版本开着开机自启时应关掉，否则重启后新旧两版一起起来。systemctl:\n" + calls);
        assertTrue(calls.contains("is-enabled --quiet novabot@5.7.9"),
                "应逐个问实例是否开着自启。systemctl:\n" + calls);
        assertFalse(calls.contains("disable novabot@5.8.0"),
                "新版本自己的开机自启不该被关掉。systemctl:\n" + calls);
    }

    @Test
    @DisplayName("版本号是两点、带加号或以点开头时，不改任何文件")
    void illegalVersionStopsBeforeTouchingFiles(@TempDir Path dir) throws Exception {
        assertAll(
                () -> assertIllegalVersion(dir.resolve("dotdot"), ".."),
                () -> assertIllegalVersion(dir.resolve("plus"), "1.0+1"),
                () -> assertIllegalVersion(dir.resolve("dotlead"), ".5"));
    }

    @Test
    @DisplayName("再升级留下 releases 下不像版本号的目录")
    void upgradeKeepsUnversionedDirectoryUnderReleases(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeRelease(world.install, "5.7.8", "old-578");
        writeRelease(world.install, "5.7.9", "old-579");
        write(world.install.resolve("releases/notes/readme.txt"), "keep-notes");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "5.7.9");

        assertEquals(0, run.code, "分目录上再装一版应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertTrue(Files.isRegularFile(world.install.resolve("releases/notes/readme.txt")),
                "releases 下不像版本号的目录应原样留下，notes 被删了");
        assertEquals("keep-notes", read(world.install.resolve("releases/notes/readme.txt")));
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8")),
                "更旧的版本目录仍应删掉");
    }

    @Test
    @DisplayName("再升级：releases 下以较大数字开头的使用者目录不当成上一版")
    void upgradeKeepsPreviousWhenUserDirectoryLooksNewer(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.8", "old-578");
        writeRelease(world.install, "5.7.9", "old-579");
        write(world.install.resolve("releases/6.0-backup/keep.txt"), "user-backup");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "分目录上再装一版应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertTrue(Files.isRegularFile(world.install.resolve("releases/5.7.9/NovaBot.jar")),
                "上一版 5.7.9 应留下，不该被当成更旧的删掉");
        assertEquals("old-579", read(world.install.resolve("releases/5.7.9/NovaBot.jar")),
                "上一版 5.7.9 应原样留下");
        assertTrue(Files.isRegularFile(world.install.resolve("releases/6.0-backup/keep.txt")),
                "使用者放在 releases 下的目录应原样留下");
        assertEquals("user-backup", read(world.install.resolve("releases/6.0-backup/keep.txt")),
                "使用者放在 releases 下的目录应原样留下");
        assertFalse(run.stdout.contains("systemctl stop novabot@6.0-backup"),
                "收尾不该提示停掉不像版本号的目录。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains("systemctl stop novabot@5.7.9"),
                "收尾仍应提示停上一版。标准输出:\n" + run.stdout);
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8")),
                "更旧的版本目录仍应删掉");
    }

    @Test
    @DisplayName("扁平布局在装 Java 和字体之前停下")
    void flatLayoutStopsBeforeInstallingRuntime(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeExecutable(world.bin.resolve("java"), """
                #!/bin/sh
                if [ -f "${NOVABOT_STUB_LOG}/java-installed" ]; then
                  echo 'openjdk version "17.0.0" 2026-01-01' >&2
                else
                  echo 'openjdk version "11.0.0" 2026-01-01' >&2
                fi
                exit 0
                """);
        writeExecutable(world.bin.resolve("fc-list"), """
                #!/bin/sh
                echo "DejaVu Sans"
                exit 0
                """);
        writeExecutable(world.bin.resolve("apt-get"), """
                #!/bin/sh
                touch "${NOVABOT_STUB_LOG}/java-installed"
                printf '%s\\n' "$*" >> "${NOVABOT_STUB_LOG}/apt.log"
                exit 0
                """);
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/old.jar"), "old-lib");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> before = snapshot(world.install);
        Run run = run(world, "", "");
        String aptLog = Files.isRegularFile(world.logs.resolve("apt.log"))
                ? read(world.logs.resolve("apt.log")) : "";
        Map<String, byte[]> after = snapshot(world.install);

        assertEquals("", aptLog,
                "扁平布局应在装 Java 和字体之前停下，实际已经调用包管理器:\n" + aptLog);
        assertTrue(diff(before, after).isEmpty(),
                "扁平布局应一个文件都不改，实际:\n" + diff(before, after));
        assertNotEquals(0, run.code, "扁平布局应停下。标准输出:\n" + run.stdout);
        assertTrue(run.stderr.contains(FLAT_REFUSAL),
                "说明应写在标准错误。标准错误:\n" + run.stderr);
    }

    private static void assertFresh(World world, Run run, Map<String, byte[]> kept) throws IOException {
        Path install = world.install;
        Path release = install.resolve("releases/5.7.9");
        assertFalse(Files.exists(install.resolve("NovaBot.jar")),
                "新装后安装目录根上不该有 NovaBot.jar，程序应在 releases/5.7.9/");
        assertFalse(Files.exists(install.resolve("lib")),
                "新装后安装目录根上不该有 lib/");
        assertEquals(0, run.code, "新装应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("new-jar", read(release.resolve("NovaBot.jar")),
                "新装后程序应在 releases/5.7.9/");
        assertEquals("new-lib", read(release.resolve("lib/core.jar")));
        assertEquals("builtin-plugin", read(release.resolve("plugins/nova-demo-5.7.9.jar")),
                "内置插件应在版本目录里");
        assertFalse(Files.exists(install.resolve("start.sh")),
                "启动脚本应在版本目录里，不在根上");
        assertFalse(Files.exists(install.resolve("plugins/nova-demo-5.7.9.jar")),
                "根上的 plugins 不该混进内置插件");
        assertTrue(Files.isDirectory(install.resolve("plugins")), "新装应在根上建空的 plugins/");
        assertTrue(Files.isDirectory(install.resolve("plugins-lib")), "新装应在根上建空的 plugins-lib/");
        try (var listed = Files.list(install.resolve("plugins"))) {
            List<String> names = listed.map(path -> path.getFileName().toString()).sorted().toList();
            assertTrue(names.isEmpty(), "新装根上的 plugins 应是空的，实际 " + names);
        }
        assertEquals("example-yml", read(install.resolve("application.example.yml")));
        assertEquals("backup-sh", read(install.resolve("tools/data-backup.sh")),
                "安装目录根上应留一份备份脚本，已有的备份单元还指着这里");
        assertEquals("backup-sh", read(release.resolve("tools/data-backup.sh")));
        assertKept(install, kept);
        String unit = read(world.etc.resolve("systemd/system/novabot@.service"));
        assertTrue(unit.contains("WorkingDirectory=" + install), "工作目录应是安装目录:\n" + unit);
        assertTrue(unit.contains("ReadWritePaths=" + install), "可写路径应是安装目录:\n" + unit);
        assertTrue(unit.contains("ExecStart=" + install + "/releases/%i/start.sh"),
                "应启动版本目录里的脚本:\n" + unit);
        String calls = read(world.logs.resolve("systemctl.log"));
        assertTrue(calls.contains("enable novabot@5.7.9"), "应把开机自启设到新版本。systemctl:\n" + calls);
        assertFalse(calls.contains("start"), "安装不该启动服务。systemctl:\n" + calls);
        assertFalse(calls.contains("stop"), "安装不该停止服务。systemctl:\n" + calls);
        assertTrue(run.stdout.contains("systemctl start novabot@5.7.9"),
                "收尾应提示怎么起新版本。标准输出:\n" + run.stdout);
        assertFalse(run.stdout.contains("systemctl stop"),
                "新装没有旧版本可停。标准输出:\n" + run.stdout);
    }

    private static void assertUpgrade(World world, Run run, Map<String, byte[]> kept) throws IOException {
        Path install = world.install;
        assertEquals(0, run.code, "分目录上再装一版应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("new-jar", read(install.resolve("releases/5.8.0/NovaBot.jar")),
                "应多出 releases/5.8.0/");
        assertTrue(Files.isRegularFile(install.resolve("releases/5.7.9/NovaBot.jar")),
                "应保留上一版 5.7.9");
        assertEquals("old-579", read(install.resolve("releases/5.7.9/NovaBot.jar")),
                "应保留上一版 5.7.9");
        assertEquals("old-577", read(install.resolve("releases/5.7.7/NovaBot.jar")),
                "正在跑的 5.7.7 不许删");
        assertFalse(Files.exists(install.resolve("releases/5.7.8")),
                "更旧的 5.7.8 应删掉");
        assertFalse(Files.exists(install.resolve("NovaBot.jar")), "根上不该出现程序");
        assertFalse(Files.exists(install.resolve("lib")), "根上不该出现 lib/");
        assertEquals("user-plugin", read(install.resolve("plugins/my-extra.jar")),
                "根上使用者自己的插件应原样");
        assertEquals("user-lib", read(install.resolve("plugins-lib/my-dep.jar")),
                "根上使用者自己的依赖应原样");
        assertFalse(Files.exists(install.resolve("plugins/nova-demo-5.8.0.jar")),
                "内置插件不该写进根上的 plugins");
        assertKept(install, kept);
        String calls = read(world.logs.resolve("systemctl.log"));
        assertTrue(calls.contains("disable novabot@5.7.9"),
                "已有别的版本开着自启，应换成新版本。systemctl:\n" + calls);
        assertTrue(calls.contains("enable novabot@5.8.0"), "自启应换到新版本。systemctl:\n" + calls);
        assertFalse(calls.contains("--now"), "换自启不该带 --now，那会把旧版本停掉。systemctl:\n" + calls);
        assertFalse(calls.contains("stop"), "安装不该停止正在跑的实例。systemctl:\n" + calls);
        assertFalse(calls.contains("start"), "安装不该启动新版本。systemctl:\n" + calls);
        assertTrue(run.stdout.contains("systemctl start novabot@5.8.0"), run.stdout);
        assertTrue(run.stdout.contains("systemctl stop novabot@5.7.9"), run.stdout);
        assertTrue(run.stdout.contains("systemctl stop novabot@5.7.7"), run.stdout);
    }

    private static void assertIllegalVersion(Path root, String version) throws Exception {
        World world = world(root, version);
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> before = snapshot(world.install);
        Run run = run(world, "", "");
        String diff = diff(before, snapshot(world.install));

        assertTrue(diff.isEmpty(),
                "版本号「" + version + "」应在改任何文件之前停下，实际:\n" + diff);
        assertNotEquals(0, run.code,
                "版本号「" + version + "」应停下。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
    }

    private static void assertFlat(Path root, String jarName) throws Exception {
        assertFlat(world(root, "5.8.0"), jarName);
    }

    private static void assertFlat(World world, String jarName) throws Exception {
        Files.writeString(world.install.resolve(jarName), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/old.jar"), "old-lib");
        write(world.install.resolve("plugins/old.jar"), "old-plugin");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Files.writeString(world.install.resolve("keep.txt"), "do-not-touch", StandardCharsets.UTF_8);
        Map<String, byte[]> before = snapshot(world.install);
        Run run = run(world, "", "");
        Map<String, byte[]> after = snapshot(world.install);
        String diff = diff(before, after);

        assertTrue(diff.isEmpty(), jarName + " 这种扁平布局应一个文件都不改，实际:\n" + diff);
        assertNotEquals(0, run.code, jarName + " 这种扁平布局应退非 0。标准输出:\n" + run.stdout);
        assertTrue(run.stderr.contains(FLAT_REFUSAL),
                "说明应写在标准错误。标准错误:\n" + run.stderr);
    }

    private static void assertRunningUntouched(World world, Run run, Map<String, byte[]> before) throws IOException {
        Map<String, byte[]> after = snapshot(world.install);
        String diff = diff(before, after);
        assertTrue(diff.isEmpty(), "要装的版本正在跑时，一个文件都不该改，实际:\n" + diff);
        assertNotEquals(0, run.code, "应停下。标准输出:\n" + run.stdout);
        assertTrue(run.stderr.contains("要装的 5.7.9 " + RUNNING_REFUSAL),
                "说明应写在标准错误。标准错误:\n" + run.stderr);
        assertEquals("running-jar", read(world.install.resolve("releases/5.7.9/NovaBot.jar")));
    }

    private static void assertKept(Path install, Map<String, byte[]> kept) throws IOException {
        for (Map.Entry<String, byte[]> entry : kept.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(install.resolve(entry.getKey())),
                    entry.getKey() + " 应逐字节保持原样");
        }
    }

    private static World world(Path dir, String version) throws IOException {
        World world = new World();
        world.pkg = Files.createDirectories(dir.resolve("pkg"));
        world.install = Files.createDirectories(dir.resolve("install"));
        world.bin = Files.createDirectories(dir.resolve("bin"));
        world.etc = Files.createDirectories(dir.resolve("etc"));
        world.logs = Files.createDirectories(dir.resolve("logs"));
        Files.copy(repoRoot().resolve("install.sh"), world.pkg.resolve("install.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        write(world.pkg.resolve("NovaBot.jar"), "new-jar");
        write(world.pkg.resolve("lib/core.jar"), "new-lib");
        write(world.pkg.resolve("plugins/nova-demo-" + version + ".jar"), "builtin-plugin");
        write(world.pkg.resolve("plugins-lib/demo-lib-1.jar"), "builtin-lib");
        write(world.pkg.resolve("start.sh"), "#!/bin/sh\necho start\n");
        write(world.pkg.resolve("start.bat"), "start");
        write(world.pkg.resolve("docker-entrypoint.sh"), "entry");
        write(world.pkg.resolve("Dockerfile"), "FROM scratch\n");
        write(world.pkg.resolve("tools/data-backup.sh"), "backup-sh");
        write(world.pkg.resolve("BUILD-INFO"), "commit=abc\nversion=" + version + "\nbuilt_at=2026-10-03T00:00:00Z\n");
        write(world.pkg.resolve("LICENSE"), "license");
        write(world.pkg.resolve("NOTICE"), "notice");
        write(world.pkg.resolve("application.example.yml"), "example-yml");
        write(world.pkg.resolve("datasource.example.json"), "example-ds");
        write(world.pkg.resolve("Caddyfile"), "caddy");
        write(world.pkg.resolve("novabot-backup.service"), "backup-unit");
        write(world.pkg.resolve("novabot-backup.timer"), "backup-timer");
        write(world.pkg.resolve("novabot@.service"), UNIT_FIXTURE);
        write(world.pkg.resolve("application.yml"), "package-yml-must-not-win");
        writeExecutable(world.bin.resolve("uname"), STUB_UNAME);
        writeExecutable(world.bin.resolve("id"), STUB_ID);
        writeExecutable(world.bin.resolve("java"), STUB_JAVA);
        writeExecutable(world.bin.resolve("fc-list"), STUB_FC_LIST);
        writeExecutable(world.bin.resolve("chown"), STUB_CHOWN);
        writeExecutable(world.bin.resolve("useradd"), STUB_USERADD);
        writeExecutable(world.bin.resolve("tee"), STUB_TEE);
        writeExecutable(world.bin.resolve("systemctl"), STUB_SYSTEMCTL);
        return world;
    }

    private static void writeRelease(Path install, String version, String jarText) throws IOException {
        write(install.resolve("releases/" + version + "/NovaBot.jar"), jarText);
    }

    private static void writeKept(Path install, String yml, String datasource, String cookies, String key, String data)
            throws IOException {
        write(install.resolve("application.yml"), yml);
        write(install.resolve("datasource.json"), datasource);
        write(install.resolve("cookies.json"), cookies);
        write(install.resolve("cookies.key"), key);
        write(install.resolve("data.json"), data);
    }

    private static Map<String, byte[]> readKept(Path install) throws IOException {
        Map<String, byte[]> kept = new TreeMap<>();
        for (String name : List.of("application.yml", "datasource.json", "cookies.json", "cookies.key", "data.json")) {
            kept.put(name, Files.readAllBytes(install.resolve(name)));
        }
        return kept;
    }

    private static Run run(World world, String active, String enabled) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", world.pkg.resolve("install.sh").toString(),
                "--dir", world.install.toString(),
                "--user", "starbot");
        builder.environment().put("PATH", world.bin + ":/usr/bin:/bin");
        builder.environment().put("NOVABOT_STUB_ETC", world.etc.toString());
        builder.environment().put("NOVABOT_STUB_LOG", world.logs.toString());
        builder.environment().put("NOVABOT_STUB_ACTIVE", active);
        builder.environment().put("NOVABOT_STUB_ENABLED", enabled);
        builder.redirectOutput(world.logs.resolve("stdout.txt").toFile());
        builder.redirectError(world.logs.resolve("stderr.txt").toFile());
        Process process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                "install.sh 跑了 60 秒还没结束");
        Run run = new Run();
        run.code = process.exitValue();
        run.stdout = read(world.logs.resolve("stdout.txt"));
        run.stderr = read(world.logs.resolve("stderr.txt"));
        return run;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> map = new TreeMap<>();
        if (!Files.exists(root)) {
            return map;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                map.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return map;
    }

    private static String diff(Map<String, byte[]> before, Map<String, byte[]> after) {
        StringBuilder text = new StringBuilder();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            byte[] left = before.get(key);
            byte[] right = after.get(key);
            if (left == null) {
                text.append("多了 ").append(key).append('\n');
            } else if (right == null) {
                text.append("少了 ").append(key).append('\n');
            } else if (!Arrays.equals(left, right)) {
                text.append("改了 ").append(key).append('\n');
            }
        }
        return text.toString();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
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

    private static final class World {
        Path pkg;
        Path install;
        Path bin;
        Path etc;
        Path logs;
    }

    private static final class Run {
        int code;
        String stdout;
        String stderr;
    }
}
