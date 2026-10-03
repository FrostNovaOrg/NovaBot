package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    static final String VERSION_UNKNOWN = "取不到旧版本号";

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
            printf '%s\\n' "$*" >> "${NOVABOT_STUB_LOG}/chown.log"
            exit 0
            """;

    /**
     * 换版工具替身：只记账，不换版。
     * <p>
     * 件里留一行含 /opt/starbot 的 INSTALL_DIR，安装脚本照单元模板那样把缺省目录换成本次安装目录。
     * 运行时把这一行、自己被哪一份叫到、收到的参数记进替身日志，并把调用那一刻的 systemctl
     * 日志另存一份——「调工具之前装时有没有动过开机自启」只在那一刻看，事后补看会被工具自己的动作盖掉。
     */
    private static final String STUB_SWITCH_VERSION = """
            #!/bin/sh
            INSTALL_DIR="${NOVABOT_INSTALL_DIR:-/opt/starbot}"
            LOG="${NOVABOT_STUB_LOG}/switch-version.log"
            {
              echo "argv0=$0"
              grep '^INSTALL_DIR=' "$0"
              echo "args=$*"
            } >> "$LOG"
            if [ -f "${NOVABOT_STUB_LOG}/systemctl.log" ]; then
              cat "${NOVABOT_STUB_LOG}/systemctl.log" > "${NOVABOT_STUB_LOG}/systemctl-at-switch.log"
            fi
            echo "换版工具替身：目标 $*"
            exit "${NOVABOT_STUB_SWITCH_RC:-0}"
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

            def bare(unit):
                if unit.endswith(".service"):
                    return unit[: -len(".service")]
                return unit

            if "is-active" in args:
                target = args[-1]
                if version_of(target) in listed("NOVABOT_STUB_ACTIVE") or bare(target) in listed("NOVABOT_STUB_ACTIVE"):
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
    @DisplayName("分目录上再装一版：留下上一版和正在跑的，更旧的删掉，装完交给换版工具换过去")
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
    @DisplayName("扁平布局：旧程序搬进版本目录后再装，使用者插件与配置留下")
    void flatLayoutMovesProgramThenInstalls(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        Files.writeString(world.install.resolve("StarBotCore.jar"), "old-star", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        write(world.install.resolve("lib/other.jar"), "old-other");
        write(world.install.resolve("plugins/nova-demo-5.7.8.jar"), "old-builtin");
        write(world.install.resolve("plugins/my-extra.jar"), "user-plugin");
        write(world.install.resolve("plugins/nova-demo-extra-1.2.jar"), "prefix-user");
        write(world.install.resolve("plugins-lib/demo-lib-1.0.jar"), "old-builtin-lib");
        write(world.install.resolve("plugins-lib/my-dep.jar"), "user-lib");
        write(world.install.resolve("start.sh"), "old-start");
        write(world.install.resolve("tools/data-backup.sh"), "old-backup");
        write(world.install.resolve("LICENSE"), "old-license");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        write(world.install.resolve("state.json"), "state-body");
        write(world.install.resolve("sessions.jsonl"), "session-line\n");
        write(world.install.resolve("details/room.txt"), "detail-body");
        write(world.install.resolve("reports/r.txt"), "report-body");
        Map<String, byte[]> kept = readKept(world.install);
        byte[] state = Files.readAllBytes(world.install.resolve("state.json"));
        byte[] sessions = Files.readAllBytes(world.install.resolve("sessions.jsonl"));
        byte[] detail = Files.readAllBytes(world.install.resolve("details/room.txt"));
        byte[] report = Files.readAllBytes(world.install.resolve("reports/r.txt"));
        Run run = run(world, "", "");

        assertEquals(0, run.code, "扁平布局应先搬再装。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        Path oldRelease = world.install.resolve("releases/5.7.8");
        assertEquals("old-flat", read(oldRelease.resolve("NovaBot.jar")));
        assertEquals("old-star", read(oldRelease.resolve("StarBotCore.jar")));
        assertEquals("old-core", read(oldRelease.resolve("lib/novacore-5.7.8.jar")));
        assertEquals("old-other", read(oldRelease.resolve("lib/other.jar")));
        assertEquals("old-builtin", read(oldRelease.resolve("plugins/nova-demo-5.7.8.jar")));
        assertEquals("old-builtin-lib", read(oldRelease.resolve("plugins-lib/demo-lib-1.0.jar")));
        assertEquals("old-backup", read(oldRelease.resolve("tools/data-backup.sh")));
        assertEquals("old-license", read(oldRelease.resolve("LICENSE")));
        assertEquals(read(world.pkg.resolve("start.sh")), read(oldRelease.resolve("start.sh")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("StarBotCore.jar")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertFalse(Files.exists(world.install.resolve("plugins/nova-demo-5.7.8.jar")));
        assertFalse(Files.exists(world.install.resolve("plugins-lib/demo-lib-1.0.jar")));
        assertEquals("user-plugin", read(world.install.resolve("plugins/my-extra.jar")));
        assertEquals("prefix-user", read(world.install.resolve("plugins/nova-demo-extra-1.2.jar")));
        assertEquals("user-lib", read(world.install.resolve("plugins-lib/my-dep.jar")));
        assertKept(world.install, kept);
        assertArrayEquals(state, Files.readAllBytes(world.install.resolve("state.json")));
        assertArrayEquals(sessions, Files.readAllBytes(world.install.resolve("sessions.jsonl")));
        assertArrayEquals(detail, Files.readAllBytes(world.install.resolve("details/room.txt")));
        assertArrayEquals(report, Files.readAllBytes(world.install.resolve("reports/r.txt")));
        assertEquals("backup-sh", read(world.install.resolve("tools/data-backup.sh")));
        assertTrue(run.stdout.contains("my-extra.jar"), run.stdout);
        assertTrue(run.stdout.contains("nova-demo-extra-1.2.jar"), run.stdout);
        assertTrue(run.stdout.contains("my-dep.jar"), run.stdout);
        assertTrue(run.stdout.contains("旧程序若还在跑，先照你原来起它的办法停掉"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop novabot@"), run.stdout);
        assertTrue(run.stdout.contains("systemctl start novabot@5.8.0"), run.stdout);
    }

    @Test
    @DisplayName("扁平布局：jar 里的 build.version 优先于 lib 文件名")
    void flatLayoutPrefersBuildVersionInsideJar(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeJarWithBuildVersion(world.install.resolve("NovaBot.jar"), "5.7.6");
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "应认 jar 里的版本。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("old-core", read(world.install.resolve("releases/5.7.6/lib/novacore-5.7.8.jar")));
        assertTrue(Files.isRegularFile(world.install.resolve("releases/5.7.6/NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8")));
        assertFalse(Files.exists(world.install.resolve("NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
    }

    @Test
    @DisplayName("扁平布局只有 StarBotCore.jar 时，按 lib 里的版本搬走")
    void flatLayoutMovesStarBotCoreJar(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        Files.writeString(world.install.resolve("StarBotCore.jar"), "old-star", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "应搬走。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("old-star", read(world.install.resolve("releases/5.7.8/StarBotCore.jar")));
        assertFalse(Files.exists(world.install.resolve("StarBotCore.jar")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
    }

    @Test
    @DisplayName("旧版本号与要装的相同：搬完不另留，版本目录是新程序")
    void flatLayoutSameVersionIsReplacedNotKept(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.7.8");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Map<String, byte[]> kept = readKept(world.install);
        Run run = run(world, "", "");

        assertEquals(0, run.code, "同版本应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("new-jar", read(world.install.resolve("releases/5.7.8/NovaBot.jar")));
        assertEquals("new-lib", read(world.install.resolve("releases/5.7.8/lib/core.jar")));
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8/lib/novacore-5.7.8.jar")));
        assertFalse(Files.exists(world.install.resolve("NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertKept(world.install, kept);
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
        int handoverAt = build.indexOf("echo \"handover=1\"");
        int fetchAt = build.indexOf("help:evaluate -Dexpression=project.version");
        int fetches = build.split("help:evaluate -Dexpression=project.version", -1).length - 1;

        assertTrue(infoAt >= 0, "BUILD-INFO 的写出点不在了");
        assertTrue(fetchAt >= 0 && fetchAt < infoAt,
                "取版本应挪到写 BUILD-INFO 之前，并且就是原来那次 mvn help:evaluate");
        assertEquals(1, fetches, "取版本应只留一处，打包步共用同一个变量，实际 " + fetches + " 处");
        assertTrue(versionAt >= 0 && versionAt < infoAt,
                "BUILD-INFO 应写出 version=$VERSION，空版本不该写进去");
        assertTrue(handoverAt > versionAt && handoverAt < infoAt,
                "BUILD-INFO 应在 version 之后写出 handover=1");
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
        // 这一格要看的是「逐个问实例」那一段，而 B 支（有别的实例在跑）把整段跳过交给换版工具，
        // 所以这里取没有实例在跑那一种：自启仍由装的时候切，逐个问的那段照走
        Run run = run(world, "", "5.7.9");

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
        assertFalse(run.stdout.contains("systemctl stop novabot@5.7.9"),
                "没在跑的旧版本不该提示去停。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains("1. 启动新版本"),
                "没有在跑的实例，收尾就是「1. 启动新版本」。标准输出:\n" + run.stdout);
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8")),
                "更旧的版本目录仍应删掉");
    }

    @Test
    @DisplayName("取不到旧版本号时，在装 Java 之前停下且不改文件")
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
                "取不到旧版本号应在装 Java 和字体之前停下，实际已经调用包管理器:\n" + aptLog);
        assertTrue(diff(before, after).isEmpty(),
                "取不到旧版本号应一个文件都不改，实际:\n" + diff(before, after));
        assertNotEquals(0, run.code, "取不到旧版本号应停下。标准输出:\n" + run.stdout);
        assertTrue(run.stderr.contains(VERSION_UNKNOWN),
                "说明应写在标准错误。标准错误:\n" + run.stderr);
    }

    @Test
    @DisplayName("扁平布局：旧服务单元只关自启，在跑的留着，不在跑的删掉")
    void flatLayoutLegacyUnitDisableWithoutStopping(@TempDir Path dir) throws Exception {
        assertLegacyUnit(dir.resolve("running"), "novabot", true);
        assertLegacyUnit(dir.resolve("stopped"), "starbot", false);
    }

    @Test
    @DisplayName("扁平布局搬到一半再跑，接着搬完")
    void flatLayoutResumesPartialMove(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        write(world.install.resolve("releases/5.7.8/LICENSE"), "kept-license");
        write(world.install.resolve("releases/5.7.8/BUILD-INFO"), "kept-info");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        write(world.install.resolve("start.sh"), "old-start");
        write(world.install.resolve("NOTICE"), "old-notice");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "接着搬应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("kept-license", read(world.install.resolve("releases/5.7.8/LICENSE")));
        assertEquals("kept-info", read(world.install.resolve("releases/5.7.8/BUILD-INFO")));
        assertEquals("old-flat", read(world.install.resolve("releases/5.7.8/NovaBot.jar")));
        assertEquals("old-core", read(world.install.resolve("releases/5.7.8/lib/novacore-5.7.8.jar")));
        assertEquals("old-notice", read(world.install.resolve("releases/5.7.8/NOTICE")));
        assertEquals(read(world.pkg.resolve("start.sh")), read(world.install.resolve("releases/5.7.8/start.sh")));
        assertFalse(Files.exists(world.install.resolve("NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertFalse(Files.exists(world.install.resolve("NOTICE")));
        assertEquals("user-yml", read(world.install.resolve("application.yml")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
    }

    @Test
    @DisplayName("旧版本的 start.sh 换新时不原地覆写")
    void flatLayoutReplacesStartScriptWithoutSameInode(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        Path oldStart = world.install.resolve("start.sh");
        Files.writeString(oldStart, "old-start-body\n", StandardCharsets.UTF_8);
        String before = inode(oldStart);
        Run run = run(world, "", "");

        assertEquals(0, run.code, "应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        Path replaced = world.install.resolve("releases/5.7.8/start.sh");
        assertEquals(read(world.pkg.resolve("start.sh")), read(replaced));
        assertNotEquals(before, inode(replaced), "换 start.sh 应先写临时名再改名，不能原地覆写");
    }

    @Test
    @DisplayName("续搬：根上没有 lib、PATH 上没有 unzip，从版本目录里恰好一个 novacore 认出版本并搬完")
    void flatLayoutResumesFromReleaseNovacoreJar(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        write(world.install.resolve("releases/5.7.8/lib/novacore-5.7.8.jar"), "old-core");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("start.sh"), "old-start");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Path filtered = directoryWithoutUnzip(dir);
        String path = world.bin + ":" + filtered;
        ProcessBuilder probe = new ProcessBuilder("bash", "-c", "command -v unzip");
        probe.environment().put("PATH", path);
        probe.redirectErrorStream(true);
        Process looked = probe.start();
        assertTrue(looked.waitFor(10, TimeUnit.SECONDS), "查找 unzip 超时");
        assertNotEquals(0, looked.exitValue(), "这一格的 PATH 上不该找得到 unzip");
        Run run = run(world, "", "", Map.of("PATH", path));

        assertEquals(0, run.code, "lib 已在版本目录、读不出 jar 里的版本时，应接着搬完。标准错误:\n"
                + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals("old-flat", read(world.install.resolve("releases/5.7.8/NovaBot.jar")));
        assertEquals("old-core", read(world.install.resolve("releases/5.7.8/lib/novacore-5.7.8.jar")));
        assertEquals(read(world.pkg.resolve("start.sh")), read(world.install.resolve("releases/5.7.8/start.sh")));
        assertFalse(Files.exists(world.install.resolve("NovaBot.jar")));
        assertFalse(Files.exists(world.install.resolve("start.sh")));
        assertFalse(Files.exists(world.install.resolve("lib")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
        assertEquals("user-yml", read(world.install.resolve("application.yml")));
    }

    @Test
    @DisplayName("已分目录、版本目录里已有程序时，根上读不出版本的包不算搬到一半")
    void versionedReleaseWithRootJarIsNotHalfMoved(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.9.0");
        write(world.install.resolve("releases/5.8.0/NovaBot.jar"), "old-580");
        write(world.install.resolve("releases/5.8.0/lib/novacore-5.8.0.jar"), "old-core");
        write(world.install.resolve("releases/5.8.0/start.sh"), "start-580");
        Files.writeString(world.install.resolve("NovaBot.jar"), "not-a-zip", StandardCharsets.UTF_8);
        Path filtered = directoryWithoutUnzip(dir);
        String path = world.bin + ":" + filtered;
        ProcessBuilder probe = new ProcessBuilder("bash", "-c", "command -v unzip");
        probe.environment().put("PATH", path);
        probe.redirectErrorStream(true);
        Process looked = probe.start();
        assertTrue(looked.waitFor(10, TimeUnit.SECONDS), "查找 unzip 超时");
        assertNotEquals(0, looked.exitValue(), "这一格的 PATH 上不该找得到 unzip");
        Run run = run(world, "", "", Map.of("PATH", path));

        assertNotEquals(0, run.code, "应停下。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        assertTrue(run.stdout.contains(VERSION_UNKNOWN) || run.stderr.contains(VERSION_UNKNOWN),
                "应说明取不到旧版本号。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        assertEquals("start-580", read(world.install.resolve("releases/5.8.0/start.sh")));
        try (var listed = Files.list(world.install.resolve("releases"))) {
            List<String> names = listed.map(child -> child.getFileName().toString()).sorted().toList();
            assertEquals(List.of("5.8.0"), names, "releases 下应仍只有 5.8.0，实际 " + names);
        }
        assertTrue(Files.isRegularFile(world.install.resolve("NovaBot.jar")), "根上的 NovaBot.jar 应还在");
        assertEquals("not-a-zip", read(world.install.resolve("NovaBot.jar")));
    }

    @Test
    @DisplayName("已经分目录再装：旧服务还在跑时，提示先停旧服务再起新版本")
    void rerunWhileLegacyUnitRunningStopsOldBeforeStart(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.8", "old-578");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Path unitDir = world.etc.resolve("systemd/system");
        Files.createDirectories(unitDir);
        Files.writeString(unitDir.resolve("novabot.service"),
                "[Service]\nExecStart=/opt/starbot/start.sh\n", StandardCharsets.UTF_8);
        Run run = run(world, "novabot", "", Map.of(
                "NOVABOT_SYSTEMD_SYSTEM_DIR", unitDir.toString()));

        assertEquals(0, run.code, "再装应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        int stopAt = run.stdout.indexOf("sudo systemctl stop novabot\n");
        int startAt = run.stdout.indexOf("sudo systemctl start novabot@5.8.0");
        assertTrue(stopAt >= 0 && startAt > stopAt, "应先停 novabot 再起新版。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains("断一小会儿"), run.stdout);
        assertFalse(run.stdout.contains("确认新版本起来之后再停掉"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop novabot@5.7.8"), run.stdout);
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "老用户首升要手动先停后起，不该自动换版");
    }

    @Test
    @DisplayName("换启动脚本时临时名已在：删掉再拷，安装完成")
    void flatLayoutRemovesLeftoverStartTempThenCopies(@TempDir Path dir) throws Exception {
        World world = world(dir, "5.8.0");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        write(world.install.resolve("releases/5.7.8/start.sh.novabot-new"), "leftover-temp");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "临时名已在应删掉再拷，不应停下。标准错误:\n"
                + run.stderr + "\n标准输出:\n" + run.stdout);
        assertEquals(read(world.pkg.resolve("start.sh")), read(world.install.resolve("releases/5.7.8/start.sh")));
        assertFalse(Files.exists(world.install.resolve("releases/5.7.8/start.sh.novabot-new")));
        assertEquals("old-flat", read(world.install.resolve("releases/5.7.8/NovaBot.jar")));
        assertEquals("new-jar", read(world.install.resolve("releases/5.8.0/NovaBot.jar")));
    }

    @Test
    @DisplayName("install.sh 语法过、--help 里有 --no-switch")
    void installScriptParsesAndHelpListsNoSwitch() throws Exception {
        Path script = repoRoot().resolve("install.sh");
        ProcessBuilder parse = new ProcessBuilder("bash", "-n", script.toString());
        parse.redirectErrorStream(true);
        Process parsed = parse.start();
        String parseOut = new String(parsed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(parsed.waitFor(30, TimeUnit.SECONDS), "bash -n 超时:\n" + parseOut);
        assertEquals(0, parsed.exitValue(), "install.sh 语法应过。输出:\n" + parseOut);

        ProcessBuilder help = new ProcessBuilder("bash", script.toString(), "--help");
        help.redirectErrorStream(true);
        Process helped = help.start();
        String helpOut = new String(helped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(helped.waitFor(30, TimeUnit.SECONDS), "--help 超时:\n" + helpOut);
        assertTrue(helpOut.contains("--no-switch"), "用法里应有 --no-switch:\n" + helpOut);
    }

    @Test
    @DisplayName("装工具：装成 root 所有的系统命令，INSTALL_DIR 已换成本次安装目录")
    void switchToolInstalledAsRootOwnedSystemCommand(@TempDir Path dir) throws Exception {
        // 故障：root 跑的是服务用户改得动的脚本；或装出来的那份指着别的目录
        World world = world(dir, "5.8.0");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        Path tool = world.sbin.resolve("novabot-switch-version");
        assertTrue(Files.isRegularFile(tool), "换版工具应装到系统目录: " + tool);
        assertTrue(Files.isExecutable(tool), "换版工具应可执行: " + tool);
        assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE),
                Files.getPosixFilePermissions(tool), "权限位应恰 rwxr-xr-x");
        String body = read(tool);
        assertTrue(body.contains(world.install.toString()),
                "INSTALL_DIR 缺省值应换成本次安装目录，实际:\n" + body);
        assertFalse(body.contains("/opt/starbot"),
                "件里不该还留着缺省的 /opt/starbot，实际:\n" + body);
        String chownLog = readIfExists(world.logs.resolve("chown.log"));
        assertTrue(chownLog.contains("root:root " + world.sbin),
                "chown 应把工具记成 root 所有，实际:\n" + chownLog);
    }

    @Test
    @DisplayName("B 退 0：调的是系统目录那一份、一次、参数是本版，收尾没有停旧起新两步")
    void autoSwitchCallsSystemToolOnceWithThisVersion(@TempDir Path dir) throws Exception {
        // 故障：装完还要手敲停起，或装时就把自启切到还没起来的新版本
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "");

        assertEquals(0, run.code, "换版做成时 install 应退 0。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        Path tool = world.sbin.resolve("novabot-switch-version");
        String toolLog = readIfExists(world.logs.resolve("switch-version.log"));
        assertTrue(toolLog.contains("argv0=" + tool),
                "应调系统目录那一份，不是 releases 里那份:\n" + toolLog);
        assertEquals(1, countOccurrences(toolLog, "args="),
                "应只调一次:\n" + toolLog);
        assertTrue(toolLog.contains("args=5.8.0"),
                "参数应是本版:\n" + toolLog);
        String atSwitch = readIfExists(world.logs.resolve("systemctl-at-switch.log"));
        assertFalse(atSwitch.contains("enable novabot@"),
                "调工具之前装时不该动开机自启:\n" + atSwitch);
        assertFalse(atSwitch.contains("disable novabot@"),
                "调工具之前装时不该动开机自启:\n" + atSwitch);
        assertFalse(run.stdout.contains("先停旧版本"), run.stdout);
        assertFalse(run.stdout.contains("再启动新版本"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop"), run.stdout);
        assertFalse(run.stdout.contains("systemctl start"), run.stdout);
        assertTrue(run.stdout.contains("1. 查看启动日志"),
                "收尾应从「查看启动日志」起编号:\n" + run.stdout);
    }

    @Test
    @DisplayName("B 退非 0：install 退非 0，收尾写清没全做成并给重试命令")
    void autoSwitchFailureReportsStateAndRetry(@TempDir Path dir) throws Exception {
        // 故障：没换成却显示成功
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "", Map.of("NOVABOT_STUB_SWITCH_RC", "3"));

        assertNotEquals(0, run.code, "换版没全做成时 install 应退非 0。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains("换版没有全部做成"),
                "收尾应写清换版没全做成:\n" + run.stdout);
        Path tool = world.sbin.resolve("novabot-switch-version");
        assertTrue(run.stdout.contains(tool + " 5.8.0"),
                "收尾应给重试命令:\n" + run.stdout);
        assertTrue(run.stdout.contains("journalctl"),
                "收尾应给看日志的命令:\n" + run.stdout);
        String calls = readIfExists(world.logs.resolve("systemctl.log"));
        assertFalse(calls.contains("enable novabot@"),
                "安装脚本自己不该动开机自启:\n" + calls);
        assertFalse(calls.contains("disable novabot@"),
                "安装脚本自己不该动开机自启:\n" + calls);
    }

    @Test
    @DisplayName("C：--no-switch 只装不换，没调工具、没动开机自启")
    void noSwitchOnlyInstallsWithoutCallingTool(@TempDir Path dir) throws Exception {
        // 故障：说了只装却被换了
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "", Map.of(), "--no-switch");

        assertEquals(0, run.code, "只装应成功。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "--no-switch 不该调换版工具");
        String calls = readIfExists(world.logs.resolve("systemctl.log"));
        assertFalse(calls.contains("enable novabot@"),
                "--no-switch 不该动开机自启:\n" + calls);
        assertFalse(calls.contains("disable novabot@"),
                "--no-switch 不该动开机自启:\n" + calls);
        Path tool = world.sbin.resolve("novabot-switch-version");
        assertTrue(run.stdout.contains(tool + " 5.8.0"),
                "收尾应给那条换版命令:\n" + run.stdout);
        assertFalse(run.stdout.contains("先停旧版本"), run.stdout);
        assertFalse(run.stdout.contains("再启动新版本"), run.stdout);
    }

    @Test
    @DisplayName("D：旧版目录在、但没有在跑的，收尾「1. 启动新版本」、没有 stop 行")
    void oldReleaseDirPresentButNotRunningSkipsStopHint(@TempDir Path dir) throws Exception {
        // 故障：照提示去停一个根本没在跑的旧版本
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.9", "old-579");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "");

        assertEquals(0, run.code, "应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        String calls = readIfExists(world.logs.resolve("systemctl.log"));
        assertTrue(calls.contains("enable novabot@5.8.0"),
                "自启应装时切到本版:\n" + calls);
        assertTrue(run.stdout.contains("1. 启动新版本"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop"),
                "没在跑就不该提示停:\n" + run.stdout);
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "没有在跑的实例，不该调换版工具");
    }

    @Test
    @DisplayName("E：--no-service 不装工具，收尾没有 systemctl 字样、有 start.sh 那一行")
    void noServiceGivesNonSystemdStart(@TempDir Path dir) throws Exception {
        // 故障：照提示敲了不存在的服务
        World world = world(dir, "5.8.0");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "", "", Map.of(), "--no-service");

        assertEquals(0, run.code, "应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        assertFalse(Files.exists(world.sbin.resolve("novabot-switch-version")),
                "--no-service 不该装换版工具");
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "--no-service 不该调换版工具");
        assertFalse(run.stdout.contains("systemctl"),
                "收尾不该出 systemctl 字样:\n" + run.stdout);
        assertTrue(run.stdout.contains(world.install + "/releases/5.8.0/start.sh"),
                "收尾应给 start.sh 那一行:\n" + run.stdout);
    }

    @Test
    @DisplayName("E 支那条起法：版本目录里的 start.sh 自己 cd 回安装目录，java 拿到的是版本目录里的 jar")
    void startScriptInVersionDirLaunchesFromInstallDir(@TempDir Path dir) throws Exception {
        // 工作目录要落在安装目录（锁、配置、日志才不会各拿一份），-jar 要指着版本目录里的那份。
        Path install = Files.createDirectories(dir.resolve("install"));
        Path release = Files.createDirectories(install.resolve("releases/5.8.0"));
        Files.copy(repoRoot().resolve("dist/templates/start.sh"), release.resolve("start.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(release.resolve("NovaBot.jar"), "jar", StandardCharsets.UTF_8);
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path javaLog = dir.resolve("java.log");
        writeExecutable(bin.resolve("java"), "#!/bin/sh\n"
                + "{\n"
                + "  echo \"CWD=$(pwd)\"\n"
                + "  i=1\n"
                + "  for a in \"$@\"; do\n"
                + "    echo \"ARG$i=$a\"\n"
                + "    i=$((i + 1))\n"
                + "  done\n"
                + "} > " + javaLog + "\n"
                + "exit 0\n");

        // macOS 上 pwd 给的是 /private/... 实体路径，@TempDir 给的是 /var/... 符号路径，比之前先落到实体
        Path installReal = install.toRealPath();
        Path releaseReal = release.toRealPath();

        ProcessBuilder builder = new ProcessBuilder("bash", release.resolve("start.sh").toString());
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "start.sh 超时:\n" + output);
        assertEquals(0, process.exitValue(), "start.sh 应跑完。输出:\n" + output);

        String javaSaw = read(javaLog);
        assertTrue(javaSaw.contains("CWD=" + installReal),
                "start.sh 在分目录下应自己 cd 回安装目录，实际:\n" + javaSaw);
        assertTrue(javaSaw.contains("-jar"),
                "应调 java -jar，实际:\n" + javaSaw);
        assertTrue(javaSaw.contains(releaseReal.resolve("NovaBot.jar").toString()),
                "-jar 应指着版本目录里的 jar，实际:\n" + javaSaw);
    }

    @Test
    @DisplayName("安装目录带会坏替换的字符：当场拦下，没建目录、没碰机器")
    void installDirWithReplaceBreakingCharsStopsEarly(@TempDir Path dir) throws Exception {
        // 故障：安装目录里的特殊字符被替换进 root 每次换版都执行的系统命令
        List<String> badChars = List.of("&", "\\", "\"", "'", "$", "`", "}", "%", "\n");
        List<String> badSays = List.of(
                "不能包含 & 号", "不能包含反斜杠", "不能包含双引号",
                "不能包含单引号（生成的文件里引号会配不上对）",
                "不能包含 $ 号", "不能包含反引号", "不能包含 } 号", "不能包含 % 号", "不能包含换行");
        for (int i = 0; i < badChars.size(); i++) {
            String bad = badChars.get(i);
            String say = badSays.get(i);
            World world = world(dir.resolve("w" + i), "5.8.0");
            Path target = dir.resolve("target" + i + bad);
            Run run = runDir(world, target, "", "");

            assertNotEquals(0, run.code, "带「" + bad + "」时 install 应退非 0。标准输出:\n" + run.stdout);
            assertTrue(run.stderr.contains(say),
                    "带「" + bad + "」时标准错误应有那句，实际:\n" + run.stderr);
            assertFalse(Files.exists(target),
                    "带「" + bad + "」时不该建安装目录: " + target);
            String calls = readIfExists(world.logs.resolve("systemctl.log"));
            assertTrue(calls.isEmpty(),
                    "带「" + bad + "」时不该碰 systemctl，实际:\n" + calls);
            assertFalse(Files.exists(world.sbin.resolve("novabot-switch-version")),
                    "带「" + bad + "」时不该装换版工具");
        }
    }

    @Test
    @DisplayName("包里工具的 INSTALL_DIR 那一行改了写法：不装新件、不动原来那份、没调工具")
    void switchToolWithoutExpectedInstallDirLineKeepsExisting(@TempDir Path dir) throws Exception {
        // 故障：包里那一行改了写法，安装照旧装出一份指着 /opt/starbot 的系统命令
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Path tool = world.sbin.resolve("novabot-switch-version");
        writeExecutable(tool, "#!/bin/sh\necho old-tool\n");
        Set<PosixFilePermission> keptPerms = ownerGroupExecPerms();
        Files.setPosixFilePermissions(tool, keptPerms);
        byte[] oldBytes = Files.readAllBytes(tool);
        writeExecutable(world.pkg.resolve("tools/switch-version.sh"),
                "#!/bin/sh\nINSTALL_DIR=${NOVABOT_INSTALL_DIR:-/opt/starbot}\nexit 0\n");
        Run run = run(world, "5.7.7", "");

        assertNotEquals(0, run.code,
                "INSTALL_DIR 那一行不对时应退非 0。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        assertArrayEquals(oldBytes, Files.readAllBytes(tool),
                "原来那份应一字不动: " + tool);
        assertEquals(keptPerms, Files.getPosixFilePermissions(tool),
                "原来那份的权限应不变");
        assertEquals(List.of("novabot-switch-version"), fileNames(world.sbin),
                "临时件应删净，sbin 实际 " + fileNames(world.sbin));
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "不该调换版工具");
        assertMachineLeftWithoutSwitchTool(run);
    }

    @Test
    @DisplayName("包里换版工具有语法错误：原来那份与权限不动，临时件删净")
    void switchToolSyntaxErrorLeavesOldToolAndDeletesTemp(@TempDir Path dir) throws Exception {
        // 故障：包里的换版工具语法坏了，系统目录里留下半截临时件，或把原来那份换坏、权限改掉
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Path tool = world.sbin.resolve("novabot-switch-version");
        writeExecutable(tool, "#!/bin/sh\necho old-tool\n");
        Set<PosixFilePermission> keptPerms = ownerGroupExecPerms();
        Files.setPosixFilePermissions(tool, keptPerms);
        byte[] oldBytes = Files.readAllBytes(tool);
        writeExecutable(world.pkg.resolve("tools/switch-version.sh"), """
                #!/bin/sh
                INSTALL_DIR="${NOVABOT_INSTALL_DIR:-/opt/starbot}"
                if then
                fi
                """);
        Run run = run(world, "5.7.7", "");

        assertNotEquals(0, run.code,
                "语法错误时应退非 0。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains("装出的换版工具有语法错误"),
                "应说明是语法错误。标准错误:\n" + run.stderr);
        assertArrayEquals(oldBytes, Files.readAllBytes(tool),
                "原来那份应一字不动: " + tool);
        assertEquals(keptPerms, Files.getPosixFilePermissions(tool),
                "原来那份的权限应不变");
        assertEquals(List.of("novabot-switch-version"), fileNames(world.sbin),
                "临时件应删净，sbin 实际 " + fileNames(world.sbin));
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "不该调换版工具");
        assertMachineLeftWithoutSwitchTool(run);
    }

    @Test
    @DisplayName("安装目录带空格或制表符：当场拦下，没建目录、没碰机器")
    void installDirWithWhitespaceStopsEarly(@TempDir Path dir) throws Exception {
        // 故障：安装目录带空格或制表符时，服务单元按空白把路径拆开，服务起不来，安装却退 0
        List<String> badChars = List.of(" ", "\t");
        List<String> badSays = List.of("不能包含空格", "不能包含制表符");
        for (int i = 0; i < badChars.size(); i++) {
            String bad = badChars.get(i);
            String say = badSays.get(i);
            World world = world(dir.resolve("w" + i), "5.8.0");
            Path target = dir.resolve("place" + i + bad + "x");
            Run run = runDir(world, target, "", "");

            assertNotEquals(0, run.code, "带「" + bad + "」时 install 应退非 0。标准输出:\n" + run.stdout);
            assertTrue(run.stderr.contains(say),
                    "带「" + bad + "」时标准错误应有那句，实际:\n" + run.stderr);
            assertFalse(Files.exists(target),
                    "带「" + bad + "」时不该建安装目录: " + target);
            String calls = readIfExists(world.logs.resolve("systemctl.log"));
            assertTrue(calls.isEmpty(),
                    "带「" + bad + "」时不该碰 systemctl，实际:\n" + calls);
            assertFalse(Files.exists(world.sbin.resolve("novabot-switch-version")),
                    "带「" + bad + "」时不该装换版工具");
        }
    }

    @Test
    @DisplayName("B 退非 0 收尾：只给重试换版与看日志，不给配置与扫码两步")
    void switchFailureClosingGivesRetryAndLogOnly(@TempDir Path dir) throws Exception {
        // 故障：新版没换上却被引去配置与扫码
        World world = world(dir, "5.8.0");
        writeRelease(world.install, "5.7.7", "old-577");
        writeKept(world.install, "user-yml", "user-ds", "user-cookie", "user-key", "user-data");
        Run run = run(world, "5.7.7", "", Map.of("NOVABOT_STUB_SWITCH_RC", "3"));

        assertNotEquals(0, run.code, "换版没全做成时 install 应退非 0。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains("重试换版"), "收尾应有重试换版:\n" + run.stdout);
        assertTrue(run.stdout.contains("journalctl"), "收尾应有看日志:\n" + run.stdout);
        assertTrue(run.stdout.contains("novabot@5.8.0"), "看日志应看新版本那个单元:\n" + run.stdout);
        assertFalse(run.stdout.contains("在浏览器中打开"), "新版没换上，不该给打开配置界面:\n" + run.stdout);
        assertFalse(run.stdout.contains("完成登录"), "新版没换上，不该给扫码登录:\n" + run.stdout);
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
        assertFalse(Files.exists(world.logs.resolve("switch-version.log")),
                "新装没有在跑的实例，不该调换版工具");
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
        assertFalse(calls.contains("enable novabot@"),
                "B 交给换版工具，装时不动开机自启。systemctl:\n" + calls);
        assertFalse(calls.contains("disable novabot@"),
                "B 交给换版工具，装时不动开机自启。systemctl:\n" + calls);
        assertFalse(calls.contains("--now"), "换自启不该带 --now，那会把旧版本停掉。systemctl:\n" + calls);
        assertFalse(calls.contains("stop"), "安装不该停止正在跑的实例。systemctl:\n" + calls);
        assertFalse(calls.contains("start"), "安装不该启动新版本。systemctl:\n" + calls);
        String toolLog = readIfExists(world.logs.resolve("switch-version.log"));
        assertTrue(toolLog.contains("args=5.8.0"),
                "装完应调换版工具换到本版。工具日志:\n" + toolLog);
        assertFalse(run.stdout.contains("systemctl start novabot@5.8.0"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop novabot@5.7.9"), run.stdout);
        assertFalse(run.stdout.contains("systemctl stop novabot@5.7.7"), run.stdout);
        assertTrue(run.stdout.contains("1. 查看启动日志"),
                "B 退 0 时收尾应从「查看启动日志」起编号。标准输出:\n" + run.stdout);
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

    private static void assertLegacyUnit(Path root, String unit, boolean running) throws Exception {
        World world = world(root, "5.8.0");
        Files.writeString(world.install.resolve("NovaBot.jar"), "old-flat", StandardCharsets.UTF_8);
        write(world.install.resolve("lib/novacore-5.7.8.jar"), "old-core");
        Path unitDir = world.etc.resolve("systemd/system");
        Files.createDirectories(unitDir);
        Path unitFile = unitDir.resolve(unit + ".service");
        Files.writeString(unitFile, "[Service]\nExecStart=/opt/starbot/start.sh\n", StandardCharsets.UTF_8);
        Path drop = unitDir.resolve(unit + ".service.d");
        Files.createDirectories(drop);
        Files.writeString(drop.resolve("override.conf"), "[Service]\nMemoryMax=2G\n", StandardCharsets.UTF_8);
        Path control = world.etc.resolve("systemd/system.control").resolve(unit + ".service.d");
        Files.createDirectories(control);
        Files.writeString(control.resolve("override.conf"), "[Service]\nMemoryHigh=2G\n", StandardCharsets.UTF_8);
        Run run = run(world, running ? unit : "", "", Map.of(
                "NOVABOT_SYSTEMD_SYSTEM_DIR", unitDir.toString(),
                "NOVABOT_SYSTEMD_CONTROL_DIR", world.etc.resolve("systemd/system.control").toString()));

        assertEquals(0, run.code, unit + " 应装完。标准错误:\n" + run.stderr + "\n标准输出:\n" + run.stdout);
        String calls = read(world.logs.resolve("systemctl.log"));
        assertTrue(calls.contains("disable " + unit), "应关掉旧单元的开机自启。systemctl:\n" + calls);
        assertFalse(calls.contains("--now"), "disable 不该带 --now。systemctl:\n" + calls);
        assertFalse(calls.contains("stop"), "安装不该停止正在跑的旧单元。systemctl:\n" + calls);
        if (running) {
            assertTrue(Files.isRegularFile(unitFile), "在跑的单元文件应留下");
        } else {
            assertFalse(Files.exists(unitFile), "不在跑的单元文件应删掉");
        }
        assertTrue(Files.isDirectory(drop), "覆盖设置目录不该删");
        assertTrue(Files.isDirectory(control), "另一处覆盖设置目录不该删");
        assertTrue(run.stdout.contains("不沿用"), run.stdout);
        assertTrue(run.stdout.contains(drop.toString()), "应列出不沿用的覆盖设置。标准输出:\n" + run.stdout);
        assertTrue(run.stdout.contains(control.toString()), "应列出不沿用的覆盖设置。标准输出:\n" + run.stdout);
        int startAt = run.stdout.indexOf("systemctl start novabot@5.8.0");
        if (running) {
            int stopAt = run.stdout.indexOf("systemctl stop " + unit);
            assertTrue(stopAt >= 0 && startAt > stopAt, "应先停旧再起新。标准输出:\n" + run.stdout);
            assertTrue(run.stdout.contains("断一小会儿"), run.stdout);
            assertTrue(run.stdout.contains("正在跑的旧版本没有单实例锁"),
                    "应说明正在跑的旧版本没有单实例锁。标准输出:\n" + run.stdout);
        } else {
            // 认出了旧单元但没在跑：单元文件已在装的时候删掉，收尾再叫人 stop 它只会报未加载
            assertFalse(run.stdout.contains("systemctl stop " + unit),
                    "没在跑的旧单元不该再提示停它。标准输出:\n" + run.stdout);
            assertTrue(startAt >= 0, "收尾仍应提示起新版本。标准输出:\n" + run.stdout);
            assertTrue(run.stdout.contains("1. 启动新版本"), run.stdout);
        }
        assertFalse(run.stdout.contains("systemctl stop novabot@"), run.stdout);
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
        world.sbin = Files.createDirectories(dir.resolve("sbin"));
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
        // 发行包里那份带执行位（打包时 dist/templates/ 照目录整拷），替身也照写：不然「调了 releases 里
        // 那份」会先撞上没执行位，红在退码上，与「调错了那一份」这个成因分不开
        writeExecutable(world.pkg.resolve("tools/switch-version.sh"), STUB_SWITCH_VERSION);
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
        return run(world, active, enabled, Map.of());
    }

    private static Run runDir(World world, Path installDir, String active, String enabled)
            throws IOException, InterruptedException {
        return runDir(world, installDir, active, enabled, Map.of());
    }

    private static Run run(World world, String active, String enabled, Map<String, String> extra, String... moreArgs)
            throws IOException, InterruptedException {
        return runDir(world, world.install, active, enabled, extra, moreArgs);
    }

    private static Run runDir(World world, Path installDir, String active, String enabled, Map<String, String> extra,
            String... moreArgs) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("bash");
        command.add(world.pkg.resolve("install.sh").toString());
        command.add("--dir");
        command.add(installDir.toString());
        command.add("--user");
        command.add("starbot");
        command.addAll(Arrays.asList(moreArgs));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PATH", world.bin + ":/usr/bin:/bin");
        builder.environment().put("NOVABOT_STUB_ETC", world.etc.toString());
        builder.environment().put("NOVABOT_STUB_LOG", world.logs.toString());
        builder.environment().put("NOVABOT_STUB_ACTIVE", active);
        builder.environment().put("NOVABOT_STUB_ENABLED", enabled);
        builder.environment().put("NOVABOT_SYSTEM_SBIN_DIR", world.sbin.toString());
        extra.forEach(builder.environment()::put);
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

    private static void writeJarWithBuildVersion(Path jar, String version) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("META-INF/build-info.properties"));
            zip.write(("build.version=" + version + "\nbuild.name=NovaBot\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static String inode(Path path) throws IOException {
        return String.valueOf(Files.getAttribute(path, "unix:ino"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String readIfExists(Path file) throws IOException {
        return Files.isRegularFile(file) ? read(file) : "";
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (var listed = Files.list(dir)) {
            return listed.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** 0750：属主读写执行，同组读和执行。与装成的 0755 差在其他人那几位。 */
    private static Set<PosixFilePermission> ownerGroupExecPerms() {
        return Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE);
    }

    private static void assertMachineLeftWithoutSwitchTool(Run run) {
        assertTrue(run.stderr.contains("新版本已经写在"),
                "应说新版本已经写上。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains("开机自启已经关掉"),
                "应说旧单元开机自启已经关掉。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains("正在跑的实例没有停"),
                "应说正在跑的实例没有停。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains("原来的换版工具没有换掉"),
                "应说清原来的换版工具没有换掉。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains("下一步：修好包里的 tools/switch-version.sh"),
                "应给出下一步。标准错误:\n" + run.stderr);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static Path directoryWithoutUnzip(Path dir) throws IOException {
        Path filtered = Files.createDirectories(dir.resolve("no-unzip"));
        for (String name : List.of(
                "bash", "sed", "basename", "dirname", "mkdir", "mv", "cp", "chmod",
                "rm", "mktemp", "awk", "grep", "cat", "ln", "touch", "python3")) {
            Path source = null;
            for (String root : List.of("/bin", "/usr/bin")) {
                Path candidate = Path.of(root, name);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    source = candidate;
                    break;
                }
            }
            if (source == null) {
                throw new IOException("找不到 " + name);
            }
            Files.createSymbolicLink(filtered.resolve(name), source);
        }
        return filtered;
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
        Path sbin;
    }

    private static final class Run {
        int code;
        String stdout;
        String stderr;
    }
}
