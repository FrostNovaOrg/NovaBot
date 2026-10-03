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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 换版工具：能热交接就先起新版本等锁再停旧版本，不能就普通重启，失败把旧版本起回来。
 * <p>
 * 替身放在 PATH 前面：systemctl、chown。systemctl 有状态，start 时按场景往状态件写阶段。
 * /proc/locks 与 /proc/meminfo 用临时目录里的夹具。不需要 root。
 */
@DisplayName("换版工具")
class SwitchVersionToolTest {

    private static final String OLD = "5.7.9";

    private static final String NEW = "5.8.0";

    private static final String STUB_CHOWN = """
            #!/usr/bin/env python3
            import os, sys
            log_dir = os.environ.get("NOVABOT_STUB_LOG", "")
            follow = True
            path = ""
            for arg in sys.argv[1:]:
                if arg in ("-h", "--no-dereference"):
                    follow = False
                elif arg.startswith("-"):
                    continue
                else:
                    path = arg
            kind = "LITERAL"
            recorded = path
            if path and follow and os.path.islink(path):
                kind = "FOLLOWED"
                recorded = os.path.realpath(path)
            if log_dir:
                os.makedirs(log_dir, exist_ok=True)
                with open(os.path.join(log_dir, "chown.log"), "a", encoding="utf-8") as fh:
                    fh.write(kind + " " + recorded + "\\n")
            sys.exit(0)
            """;

    private static final String STUB_SYSTEMCTL = """
            #!/usr/bin/env python3
            import os, sys, time
            args = sys.argv[1:]
            log = os.environ.get("NOVABOT_STUB_LOG", "")
            if log:
                os.makedirs(log, exist_ok=True)
                with open(os.path.join(log, "systemctl.log"), "a", encoding="utf-8") as out:
                    out.write(" ".join(args) + "\\n")
            state_dir = os.environ.get("NOVABOT_STUB_STATE", "")
            if not state_dir:
                sys.exit(0)
            os.makedirs(state_dir, exist_ok=True)

            def normalize(token):
                if token.endswith(".service"):
                    token = token[: -len(".service")]
                if token.startswith("novabot@"):
                    return token
                if token in ("novabot", "starbot"):
                    return token
                if token and all(c.isdigit() or c == "." for c in token):
                    return "novabot@" + token
                return token

            def units_path():
                return os.path.join(state_dir, "units")

            def save_units(rows):
                path = units_path()
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as fh:
                    for name, st in rows:
                        fh.write(name + " " + st + "\\n")
                os.replace(tmp, path)

            def load_units():
                path = units_path()
                if not os.path.exists(path):
                    rows = []
                    for item in os.environ.get("NOVABOT_STUB_ACTIVE", "").split(","):
                        item = item.strip()
                        if not item:
                            continue
                        if "=" in item:
                            name, st = item.split("=", 1)
                            rows.append((normalize(name), st.strip() or "active"))
                        else:
                            rows.append((normalize(item), "active"))
                    save_units(rows)
                rows = []
                with open(path, encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip()
                        if not line:
                            continue
                        name, _, st = line.partition(" ")
                        rows.append((name, st or "inactive"))
                return rows

            def get_state(unit):
                unit = normalize(unit)
                for name, st in load_units():
                    if name == unit:
                        return st
                return "inactive"

            def set_state(unit, st):
                unit = normalize(unit)
                rows = [(n, s) for n, s in load_units() if n != unit]
                rows.append((unit, st))
                save_units(rows)

            def verb_of(argv):
                known = {
                    "start", "stop", "is-active", "show", "enable", "disable",
                    "is-enabled", "daemon-reload", "list-units",
                }
                for item in argv:
                    if item in known:
                        return item
                return ""

            def last_unit(argv):
                skip = {
                    "start", "stop", "is-active", "show", "enable", "disable",
                    "is-enabled", "daemon-reload", "list-units",
                }
                for item in reversed(argv):
                    if item.startswith("-") or item in skip:
                        continue
                    return item
                return ""

            def read_dropin(unit):
                unit = normalize(unit)
                run = os.environ.get("NOVABOT_SYSTEMD_RUN_DIR", "")
                standby = False
                state_file = ""
                if not run:
                    return standby, state_file
                folder = os.path.join(run, unit + ".service.d")
                if not os.path.isdir(folder):
                    return standby, state_file
                for name in sorted(os.listdir(folder)):
                    path = os.path.join(folder, name)
                    if not os.path.isfile(path):
                        continue
                    with open(path, encoding="utf-8") as fh:
                        for line in fh:
                            line = line.strip()
                            if not line.startswith("Environment="):
                                continue
                            body = line[len("Environment="):]
                            if len(body) >= 2 and body[0] == '"' and body[-1] == '"':
                                body = body[1:-1]
                            if body.startswith("NOVABOT_STANDBY="):
                                standby = body.split("=", 1)[1].lower() == "true"
                            elif body.startswith("NOVABOT_STATE_FILE="):
                                state_file = body.split("=", 1)[1]
                return standby, state_file

            def write_phase(path, phase):
                if not path:
                    return
                parent = os.path.dirname(path)
                if parent:
                    os.makedirs(parent, exist_ok=True)
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as fh:
                    fh.write("phase=" + phase + "\\n")
                    fh.write("pid=4242\\n")
                os.replace(tmp, path)

            def remember(unit, state_file):
                path = os.path.join(state_dir, "remember")
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(normalize(unit) + "\\n")
                    fh.write(state_file + "\\n")

            def load_remember():
                path = os.path.join(state_dir, "remember")
                if not os.path.exists(path):
                    return "", ""
                with open(path, encoding="utf-8") as fh:
                    lines = fh.read().splitlines()
                if len(lines) < 2:
                    return "", ""
                return lines[0], lines[1]

            def enabled_path():
                return os.path.join(state_dir, "enabled")

            def save_enabled(names):
                path = enabled_path()
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as fh:
                    for name in names:
                        fh.write(name + "\\n")
                os.replace(tmp, path)

            def load_enabled():
                path = enabled_path()
                if not os.path.exists(path):
                    names = []
                    for item in os.environ.get("NOVABOT_STUB_ENABLED", "").split(","):
                        item = item.strip()
                        if item:
                            names.append(normalize(item))
                    save_enabled(names)
                with open(path, encoding="utf-8") as fh:
                    return [line.strip() for line in fh if line.strip()]

            def set_enabled(unit, on):
                unit = normalize(unit)
                names = [name for name in load_enabled() if name != unit]
                if on:
                    names.append(unit)
                save_enabled(names)

            scene = os.environ.get("NOVABOT_STUB_SCENE", "ordinary")
            verb = verb_of(args)
            if verb == "daemon-reload":
                if os.environ.get("NOVABOT_STUB_FAIL", "") == "daemon-reload":
                    sys.exit(1)
                sys.exit(0)
            if verb == "list-units":
                for name, st in load_units():
                    if not name.startswith("novabot@"):
                        continue
                    if st == "active":
                        print(name + ".service loaded active running")
                    elif st == "activating":
                        print(name + ".service loaded activating auto-restart")
                sys.exit(0)
            if verb == "show":
                prop = ""
                for i, item in enumerate(args):
                    if item == "-p" and i + 1 < len(args):
                        prop = args[i + 1]
                        break
                if prop == "MemoryHigh":
                    print(os.environ.get("NOVABOT_STUB_MEMORY_HIGH", "1.2G"))
                elif prop == "User":
                    print(os.environ.get("NOVABOT_STUB_USER", "starbot"))
                sys.exit(0)
            unit = last_unit(args)
            if verb == "is-active":
                st = get_state(unit)
                if "--quiet" not in args and "-q" not in args:
                    print(st)
                sys.exit(0 if st == "active" else 3)
            if verb == "is-enabled":
                on = normalize(unit) in load_enabled()
                if "--quiet" not in args:
                    print("enabled" if on else "disabled")
                sys.exit(0 if on else 1)
            if verb == "enable":
                if os.environ.get("NOVABOT_STUB_FAIL", "") == "enable":
                    sys.exit(1)
                set_enabled(unit, True)
                sys.exit(0)
            if verb == "disable":
                if os.environ.get("NOVABOT_STUB_FAIL", "") == "disable":
                    sys.exit(1)
                set_enabled(unit, False)
                sys.exit(0)
            if verb == "start":
                set_state(unit, "active")
                standby, state_file = read_dropin(unit)
                if scene == "die-before-wait" and standby:
                    set_state(unit, "failed")
                    sys.exit(0)
                if scene == "skip-wait" and standby:
                    write_phase(state_file, "ready")
                    sys.exit(0)
                if standby:
                    write_phase(state_file, "waiting")
                    remember(unit, state_file)
                    sys.exit(0)
                if state_file:
                    write_phase(state_file, "ready")
                sys.exit(0)
            if verb == "stop":
                slow = os.environ.get("NOVABOT_STUB_SLOW_STOP", "")
                if slow and normalize(unit) == normalize(slow):
                    flag = os.path.join(state_dir, "stop-entered")
                    with open(flag, "w", encoding="utf-8") as fh:
                        fh.write("1\\n")
                    time.sleep(3)
                set_state(unit, "inactive")
                remembered_unit, remembered_file = load_remember()
                if remembered_unit and remembered_unit != normalize(unit):
                    if scene == "safe-mode":
                        write_phase(remembered_file, "safe-mode")
                    elif scene == "hot-ok":
                        write_phase(remembered_file, "ready")
                sys.exit(0)
            sys.exit(0)
            """;

    @Test
    @DisplayName("a 热交接成：先起新再停旧，最后切自启")
    void hotHandoverSucceeds(@TempDir Path dir) throws Exception {
        // 故障：升级时两份一起连直播间，或停旧之后新版没接上。这一格要先起新、等到门口再停旧，成了才切自启。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        Run run = run(world);

        assertTrue(run.stdout.matches("(?s).*中间断了约 \\d+ 秒.*"),
                "a 输出应有中间断了约 N 秒，实际：" + run.stdout);
        assertEquals(0, run.code, "a 热交接应退 0");
        assertEquals(List.of(
                "start novabot@" + NEW,
                "stop novabot@" + OLD,
                "disable novabot@" + OLD,
                "enable novabot@" + NEW), mutations(world), "a 调用顺序");
        assertFalse(Files.exists(world.runDir.resolve("novabot@" + NEW + ".service.d")),
                "a 运行时覆盖设置最后应删掉");
    }

    @Test
    @DisplayName("b 内存不够就普通重启")
    void lowMemoryRestarts(@TempDir Path dir) throws Exception {
        // 故障：内存紧的机器上两份同时起，被内核杀掉。可用内存小于目标的 MemoryHigh 时先停旧再起新。
        World world = lay(dir, true, false, true, "ordinary", OLD);
        Run run = run(world);

        assertTrue(run.stdout.contains("内存不够"), "b 输出应说明内存不够，实际：" + run.stdout);
        assertEquals(0, run.code, "b 普通重启应退 0");
        assertTrue(before(world, "stop novabot@" + OLD, "start novabot@" + NEW),
                "b 应先停旧再起新，实际：" + calls(world));
    }

    @Test
    @DisplayName("c 旧版没拿锁就普通重启")
    void lockMissingRestarts(@TempDir Path dir) throws Exception {
        // 故障：旧版其实没拿锁，新版不等就过门，两份一起连直播间。锁列表对不上 novabot.lock 的 inode 时普通重启。
        World world = lay(dir, true, true, false, "ordinary", OLD);
        Run run = run(world);

        assertTrue(run.stdout.contains("旧版没拿着锁"), "c 输出应说明旧版没拿着锁，实际：" + run.stdout);
        assertEquals(0, run.code, "c 普通重启应退 0");
        assertTrue(before(world, "stop novabot@" + OLD, "start novabot@" + NEW),
                "c 应先停旧再起新，实际：" + calls(world));
    }

    @Test
    @DisplayName("d 目标不会热交接就普通重启")
    void noHandoverRestarts(@TempDir Path dir) throws Exception {
        // 故障：目标没有 handover=1（含旧版），不认候命。这时不能热交接，先停旧再起新。
        World world = lay(dir, false, true, true, "ordinary", OLD);
        Run run = run(world);

        assertTrue(run.stdout.contains("没有 handover=1"),
                "d 输出应说明没有 handover=1，实际：" + run.stdout);
        assertEquals(0, run.code, "d 普通重启应退 0");
        assertTrue(before(world, "stop novabot@" + OLD, "start novabot@" + NEW),
                "d 应先停旧再起新，实际：" + calls(world));
    }

    @Test
    @DisplayName("e 新版在门口前就没了")
    void diesBeforeWaiting(@TempDir Path dir) throws Exception {
        // 故障：新版在 waiting 之前就退出（门前失败）。要停掉新版，免得失败后又被拉起；旧版不能被停，自启不能动。
        World world = lay(dir, true, true, true, "die-before-wait", OLD);
        Run run = run(world);

        assertNotEquals(0, run.code, "e 应退非 0，实际输出：" + run.stdout);
        assertTrue(called(world, "stop novabot@" + NEW), "e 应 stop 新版，实际：" + calls(world));
        assertFalse(called(world, "stop novabot@" + OLD), "e 旧版不应被 stop，实际：" + calls(world));
        assertFalse(autostartTouched(world), "e 自启不应动，实际：" + calls(world));
    }

    @Test
    @DisplayName("f 新版过门后进了安全模式")
    void safeModeRollsBack(@TempDir Path dir) throws Exception {
        // 故障：新版过门后进安全模式，锁还在它手里，旧版起不来。要先停新版再起旧版，自启不动。
        World world = lay(dir, true, true, true, "safe-mode", OLD);
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "f 应退非 0，实际输出：" + run.stdout);
        assertTrue(before(world, "stop novabot@" + NEW, "start novabot@" + OLD),
                "f 应先停新再起旧，实际：" + calls(world));
        assertFalse(autostartTouched(world), "f 自启不应动，实际：" + calls(world));
        assertTrue(last.contains("旧版") && last.contains(OLD),
                "f 最后一行应说机器上跑的是旧版，实际：" + last);
    }

    @Test
    @DisplayName("g 没经过等待就就绪")
    void readyWithoutWaiting(@TempDir Path dir) throws Exception {
        // 故障：没经过 waiting 就到 ready，锁其实空着，两份会一起连直播间。当场停新版，旧版不动。
        World world = lay(dir, true, true, true, "skip-wait", OLD);
        Run run = run(world);

        assertNotEquals(0, run.code, "g 应退非 0，实际输出：" + run.stdout);
        assertTrue(called(world, "stop novabot@" + NEW), "g 应 stop 新版，实际：" + calls(world));
        assertFalse(called(world, "stop novabot@" + OLD), "g 旧版不应被 stop，实际：" + calls(world));
        assertFalse(called(world, "start novabot@" + OLD), "g 旧版不应被再 start，实际：" + calls(world));
    }

    @Test
    @DisplayName("h 旧单元还在跑")
    void legacyUnitRefuses(@TempDir Path dir) throws Exception {
        // 故障：老的 novabot.service 还在跑。这时不能自动换，也不能 start 或 stop。
        World world = lay(dir, true, true, true, "ordinary", "novabot");
        Run run = run(world);

        assertNotEquals(0, run.code, "h 应退非 0，实际输出：" + run.stdout);
        assertFalse(calledPrefix(world, "start "), "h 不应 start，实际：" + calls(world));
        assertFalse(calledPrefix(world, "stop "), "h 不应 stop，实际：" + calls(world));
    }

    @Test
    @DisplayName("i 停旧途中被打断")
    void interruptDuringOldStop(@TempDir Path dir) throws Exception {
        // 故障：换版被打断后一份都没在跑。停旧还没返回时终端断了，要停掉新版、删掉覆盖、把旧版起回来。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        world.slowStop = "novabot@" + OLD;
        Run run = runTermDuringOldStop(world);
        String last = lastLine(run.stdout);

        assertEquals("active", unitState(world, OLD), "i 旧版应仍在跑，实际：" + unitState(world, OLD));
        assertEquals("inactive", unitState(world, NEW), "i 目标应停着，实际：" + unitState(world, NEW));
        assertFalse(Files.exists(world.runDir.resolve("novabot@" + NEW + ".service.d")),
                "i 目标的覆盖应删掉");
        assertTrue(last.contains("旧版") && last.contains(OLD), "i 最后一行应说旧版在跑，实际：" + last);
    }

    @Test
    @DisplayName("j 状态目录是符号链接")
    void stateDirSymlink(@TempDir Path dir) throws Exception {
        // 故障：服务用户把状态目录换成链接，换版会把链接所指那个件的属主改成服务用户。
        // 旧版还在跑，最后一行却写成没有版本在跑。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        Path real = dir.resolve("real-state");
        Files.createDirectories(real);
        Files.writeString(real.resolve("secret"), "keep", StandardCharsets.UTF_8);
        Files.createSymbolicLink(world.stateDir, real);
        Run run = run(world);
        String chownLog = read(world.logs.resolve("chown.log"));
        String last = lastLine(run.stdout);

        assertFalse(chownLog.contains("FOLLOWED"), "j 链接所指那个件的属主不应被改，实际：" + chownLog);
        assertNotEquals(0, run.code, "j 应退非 0，实际输出：" + run.stdout);
        assertTrue(last.contains("旧版") && last.contains(OLD),
                "j 最后一行应说在跑的是旧版，实际：" + last);
    }

    @Test
    @DisplayName("k 空参数")
    void emptyVersionRejected(@TempDir Path dir) throws Exception {
        // 故障：一个空参数把正在跑的实例停一次。空版本号应直接拒绝，不能去停。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        Run run = run(world, "");

        assertNotEquals(0, run.code, "k 应退非 0，实际输出：" + run.stdout);
        assertFalse(calledPrefix(world, "stop "), "k 不应 stop，实际：" + calls(world));
    }

    @Test
    @DisplayName("l 自动重启中的旧版")
    void activatingOldIsRunning(@TempDir Path dir) throws Exception {
        // 故障：旧版正在自动重启（activating）时被当成没在跑，停不掉，开机自启也还开着。
        World world = lay(dir, true, true, true, "hot-ok", OLD + "=activating");
        Run run = run(world);

        assertEquals(0, run.code, "l 应换成，实际输出：" + run.stdout);
        assertTrue(called(world, "stop novabot@" + OLD), "l 应先停旧版，实际：" + calls(world));
        assertTrue(called(world, "disable novabot@" + OLD), "l 应关掉旧版开机自启，实际：" + calls(world));
    }

    @Test
    @DisplayName("m 切自启失败")
    void autostartSwitchFails(@TempDir Path dir) throws Exception {
        // 故障：新版已经在跑，关掉旧版开机自启没成功，机器重启后起来的仍是旧版，而且当时没有说清。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        world.fail = "disable";
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "m 应退非 0，实际输出：" + run.stdout);
        assertTrue(last.contains(NEW) && last.contains(OLD) && last.contains("开机自启"),
                "m 最后一行应说清哪一版在跑、自启在哪一版，实际：" + last);
        assertTrue(run.stdout.contains("systemctl disable novabot@" + OLD),
                "m 应印出要手敲的命令，实际：" + run.stdout);
    }

    @Test
    @DisplayName("n 带减号后缀的版本号")
    void hyphenatedVersionSwitches(@TempDir Path dir) throws Exception {
        // 故障：装得上的 5.8.0-rc1 这类版本，换版工具不认，换不过去。
        String target = "5.8.0-rc1";
        World world = lay(dir, true, true, true, "hot-ok", OLD, target);
        Run run = run(world);

        assertEquals(0, run.code, "n 应换过去，实际输出：" + run.stdout);
        assertTrue(called(world, "start novabot@" + target), "n 应启动带减号的版本，实际：" + calls(world));
        assertTrue(called(world, "stop novabot@" + OLD), "n 应停掉旧版，实际：" + calls(world));
    }

    @Test
    @DisplayName("o 普通重启路上状态目录是符号链接")
    void ordinaryRestartStateDirSymlink(@TempDir Path dir) throws Exception {
        // 故障：一个链接让换版把在跑的停掉、起不回来。旧版还在跑时，最后一行应写旧版。
        World world = lay(dir, true, false, true, "ordinary", OLD);
        Path real = dir.resolve("real-state");
        Files.createDirectories(real);
        Files.writeString(real.resolve("marker"), "keep", StandardCharsets.UTF_8);
        Files.createSymbolicLink(world.stateDir, real);
        String owner = Files.getOwner(real).getName();
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "o 应退非 0，实际输出：" + run.stdout);
        assertFalse(calledPrefix(world, "stop "), "o 不应 stop，实际：" + calls(world));
        assertFalse(calledPrefix(world, "start "), "o 不应 start，实际：" + calls(world));
        assertEquals("active", unitState(world, OLD), "o 旧版应仍在跑，实际：" + unitState(world, OLD));
        assertEquals(owner, Files.getOwner(real).getName(), "o 链接所指那个件属主应不变");
        assertTrue(last.contains("旧版") && last.contains(OLD),
                "o 最后一行应说在跑的是旧版，实际：" + last);
    }

    @Test
    @DisplayName("p 写覆盖件失败")
    void dropinWriteFails(@TempDir Path dir) throws Exception {
        // 故障：留下的覆盖件让下回手动起目标时卡在门口。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        Path dropin = world.runDir.resolve("novabot@" + NEW + ".service.d");
        Files.createDirectories(dropin);
        assertTrue(dropin.toFile().setWritable(false), "p 覆盖目录应收掉写权限");
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "p 应退非 0，实际输出：" + run.stdout);
        assertEquals("active", unitState(world, OLD), "p 旧版应仍在跑，实际：" + unitState(world, OLD));
        assertTrue(last.contains("旧版") && last.contains(OLD),
                "p 最后一行应说旧版在跑，实际：" + last);
        assertFalse(Files.exists(dropin.resolve("novabot-switch.conf")), "p 不应留下覆盖件");
        assertFalse(Files.exists(dropin), "p 不应留下覆盖目录");
    }

    @Test
    @DisplayName("q 目标正在自动重启")
    void activatingTargetRefused(@TempDir Path dir) throws Exception {
        // 故障：目标在崩溃循环，最后一行却说它在跑。应写出正在反复重启，不说它在跑。
        World world = lay(dir, true, true, true, "ordinary", NEW + "=activating");
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "q 应退非 0，实际输出：" + run.stdout);
        assertFalse(calledPrefix(world, "stop "), "q 不应 stop，实际：" + calls(world));
        assertFalse(calledPrefix(world, "start "), "q 不应 start，实际：" + calls(world));
        assertTrue(run.stdout.contains("反复重启"), "q 应说明在反复重启，实际：" + run.stdout);
        assertTrue(run.stdout.contains("journalctl -u novabot@" + NEW),
                "q 应给出看日志的命令，实际：" + run.stdout);
        assertEquals("机器上没有版本在正常跑；" + NEW + " 正在反复重启。", last,
                "q 最后一行应写出目标正在反复重启、不说它在跑，实际：" + last);
    }

    @Test
    @DisplayName("r 工具在安装目录之外")
    void toolOutsideInstallDir(@TempDir Path dir) throws Exception {
        // 故障：工具改回按所在位置推安装目录，装到系统目录后找错地方。
        World world = lay(dir, true, true, true, "ordinary", NEW);
        Run run = run(world);

        assertFalse(Files.exists(world.install.resolve("releases/" + OLD + "/tools/switch-version.sh")),
                "r 工具不应放在安装目录里");
        assertTrue(Files.isRegularFile(world.bin.resolve("switch-version.sh")),
                "r 工具应在安装目录之外");
        assertEquals(0, run.code, "r 应认出安装目录，实际输出：" + run.stdout);
        assertTrue(run.stdout.contains(NEW + " 已经在跑"),
                "r 应说明目标已在跑，实际：" + run.stdout);
    }

    @Test
    @DisplayName("s 两份在跑")
    void twoCopiesRunning(@TempDir Path dir) throws Exception {
        // 故障：两份都在跑时最后一行写成没有版本在跑，使用者以为服务停了。
        String other = "5.7.7";
        World world = lay(dir, true, true, true, "ordinary", other + "," + OLD);
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "s 应退非 0，实际输出：" + run.stdout);
        assertTrue(last.contains(other) && last.contains(OLD),
                "s 最后一行应写出两份的版本号，实际：" + last);
        assertFalse(calledPrefix(world, "start "), "s 不应 start，实际：" + calls(world));
        assertFalse(calledPrefix(world, "stop "), "s 不应 stop，实际：" + calls(world));
        assertFalse(calledPrefix(world, "enable "), "s 不应 enable，实际：" + calls(world));
        assertFalse(calledPrefix(world, "disable "), "s 不应 disable，实际：" + calls(world));
    }

    @Test
    @DisplayName("t 状态目录是符号链接且旧版正在自动重启")
    void stateDirSymlinkWhileActivating(@TempDir Path dir) throws Exception {
        // 故障：状态目录被拒，又碰上旧版正在自动重启。最后一行若写成没有版本在跑，使用者会以为服务全停了。
        World world = lay(dir, true, true, true, "hot-ok", OLD + "=activating");
        Path real = dir.resolve("real-state");
        Files.createDirectories(real);
        Files.writeString(real.resolve("secret"), "keep", StandardCharsets.UTF_8);
        Files.createSymbolicLink(world.stateDir, real);
        Run run = run(world);
        String chownLog = read(world.logs.resolve("chown.log"));
        String last = lastLine(run.stdout);

        assertFalse(chownLog.contains("FOLLOWED"), "t 链接所指那个件的属主不应被改，实际：" + chownLog);
        assertNotEquals(0, run.code, "t 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上没有版本在正常跑；旧版 " + OLD + " 正在反复重启。", last,
                "t 最后一行应写旧版正在反复重启，不写成没有版本在跑，实际：" + last);
    }

    @Test
    @DisplayName("u 旧服务与分目录同时在跑")
    void legacyBesideCopy(@TempDir Path dir) throws Exception {
        // 故障：旧服务 novabot 与分目录旧版同时在跑，最后一行只报旧服务。停掉一个，另一个还占着端口。
        World world = lay(dir, true, true, true, "ordinary", "novabot," + OLD);
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "u 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上跑的是 " + OLD + "、旧服务 novabot。", last,
                "u 最后一行应把旧服务和分目录都写出来，实际：" + last);
        assertFalse(calledPrefix(world, "start "), "u 不应 start，实际：" + calls(world));
        assertFalse(calledPrefix(world, "stop "), "u 不应 stop，实际：" + calls(world));
    }

    @Test
    @DisplayName("v 一份在跑一份正在重启")
    void oneActiveOneActivating(@TempDir Path dir) throws Exception {
        // 故障：一份正常在跑、一份正在自动重启，最后一行把正在重启的也写成在跑。
        String other = "5.7.7";
        World world = lay(dir, true, true, true, "ordinary", other + "," + OLD + "=activating");
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "v 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上跑的是 " + other + "；" + OLD + " 正在反复重启。", last,
                "v 最后一行应写成有的在跑、有的正在反复重启，实际：" + last);
        assertFalse(calledPrefix(world, "start "), "v 不应 start，实际：" + calls(world));
        assertFalse(calledPrefix(world, "stop "), "v 不应 stop，实际：" + calls(world));
    }

    @Test
    @DisplayName("w 旧服务 starbot 在跑")
    void earlierUnitStarbotInLastLine(@TempDir Path dir) throws Exception {
        // 故障：更早的 starbot 服务还在跑，最后一行不写它，使用者以为端口空着。
        World world = lay(dir, true, true, true, "ordinary", "starbot");
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "w 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上跑的是旧服务 starbot。", last,
                "w 最后一行应写出旧服务 starbot，实际：" + last);
        assertFalse(calledPrefix(world, "start "), "w 不应 start，实际：" + calls(world));
        assertFalse(calledPrefix(world, "stop "), "w 不应 stop，实际：" + calls(world));
    }

    @Test
    @DisplayName("x 首项是旧版时「是」后不留空格")
    void oldVersionFirstHasNoSpace(@TempDir Path dir) throws Exception {
        // 故障：最后一行写成「机器上跑的是 旧版 …」，是字后面多一个空格。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        Path real = dir.resolve("real-state");
        Files.createDirectories(real);
        Files.writeString(real.resolve("secret"), "keep", StandardCharsets.UTF_8);
        Files.createSymbolicLink(world.stateDir, real);
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "x 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上跑的是旧版 " + OLD + "。", last,
                "x 最后一行「是」后应紧跟旧版，实际：" + last);
    }

    @Test
    @DisplayName("y 开机自启一个都没有")
    void noVersionEnabledForBoot(@TempDir Path dir) throws Exception {
        // 故障：开机自启一个都没有时，最后一行写成「开机自启在 没有」，读不成一句。
        World world = lay(dir, true, true, true, "hot-ok", OLD);
        world.fail = "enable";
        Run run = run(world);
        String last = lastLine(run.stdout);

        assertNotEquals(0, run.code, "y 应退非 0，实际输出：" + run.stdout);
        assertEquals("机器上跑的是 " + NEW + "，没有版本设了开机自启。", last,
                "y 最后一行应说没有版本设了开机自启，实际：" + last);
    }

    private static World lay(Path dir, boolean handover, boolean memoryEnough, boolean lockHeld,
                             String scene, String active) throws Exception {
        return lay(dir, handover, memoryEnough, lockHeld, scene, active, NEW);
    }

    private static World lay(Path dir, boolean handover, boolean memoryEnough, boolean lockHeld,
                             String scene, String active, String target) throws Exception {
        World world = new World();
        world.install = dir.resolve("install");
        world.bin = dir.resolve("bin");
        world.runDir = dir.resolve("run");
        world.stateDir = dir.resolve("state");
        world.stubState = dir.resolve("stub-state");
        world.logs = dir.resolve("logs");
        world.locks = dir.resolve("proc-locks");
        world.meminfo = dir.resolve("proc-meminfo");
        Files.createDirectories(world.bin);
        Files.createDirectories(world.install.resolve("releases/" + OLD));
        Files.createDirectories(world.logs);
        Files.createDirectories(world.stubState);
        write(world.install.resolve("releases/" + OLD + "/BUILD-INFO"),
                "version=" + OLD + "\nhandover=1\n");
        String targetInfo = "version=" + target + "\n";
        if (handover) {
            targetInfo += "handover=1\n";
        }
        write(world.install.resolve("releases/" + target + "/BUILD-INFO"), targetInfo);
        Path tool = world.bin.resolve("switch-version.sh");
        Files.copy(repoRoot().resolve("dist/templates/tools/switch-version.sh"), tool,
                StandardCopyOption.COPY_ATTRIBUTES);
        Path lock = world.install.resolve("novabot.lock");
        Files.writeString(lock, "", StandardCharsets.UTF_8);
        String inode = inode(lock);
        String other = "1".equals(inode) ? "2" : "1";
        String held = lockHeld ? inode : other;
        write(world.locks, "1: POSIX  ADVISORY  WRITE 100 00:01:" + held + " 0 EOF\n");
        String avail = memoryEnough ? "8000000" : "100000";
        write(world.meminfo, "MemTotal:       8000000 kB\nMemAvailable:   " + avail + " kB\n");
        writeExecutable(world.bin.resolve("chown"), STUB_CHOWN);
        writeExecutable(world.bin.resolve("systemctl"), STUB_SYSTEMCTL);
        world.scene = scene;
        world.active = active;
        world.target = target;
        world.slowStop = "";
        world.fail = "";
        return world;
    }

    private static Run run(World world) throws Exception {
        return run(world, world.target);
    }

    private static Run run(World world, String target) throws Exception {
        Process process = command(world, target).start();
        return finish(world, process);
    }

    private static Run runTermDuringOldStop(World world) throws Exception {
        Process process = command(world, world.target).start();
        Path flag = world.stubState.resolve("stop-entered");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!Files.isRegularFile(flag)) {
            if (!process.isAlive() || System.nanoTime() > deadline) {
                process.destroyForcibly();
                Run early = finish(world, process);
                fail("停旧没有开始。输出：" + early.stdout + early.stderr);
            }
            Thread.sleep(50);
        }
        process.destroy();
        return finish(world, process);
    }

    private static ProcessBuilder command(World world, String target) {
        Path tool = world.bin.resolve("switch-version.sh");
        ProcessBuilder builder = new ProcessBuilder(tool.toString(), target);
        builder.environment().put("PATH", world.bin + ":/usr/bin:/bin");
        builder.environment().put("NOVABOT_INSTALL_DIR", world.install.toString());
        builder.environment().put("NOVABOT_PROC_LOCKS", world.locks.toString());
        builder.environment().put("NOVABOT_PROC_MEMINFO", world.meminfo.toString());
        builder.environment().put("NOVABOT_SYSTEMD_RUN_DIR", world.runDir.toString());
        builder.environment().put("NOVABOT_STATE_DIR", world.stateDir.toString());
        builder.environment().put("NOVABOT_STUB_LOG", world.logs.toString());
        builder.environment().put("NOVABOT_STUB_STATE", world.stubState.toString());
        builder.environment().put("NOVABOT_STUB_ACTIVE", world.active);
        builder.environment().put("NOVABOT_STUB_ENABLED", OLD);
        builder.environment().put("NOVABOT_STUB_SCENE", world.scene);
        builder.environment().put("NOVABOT_STUB_MEMORY_HIGH", "1.2G");
        builder.environment().put("NOVABOT_STUB_USER", "starbot");
        builder.environment().put("NOVABOT_STUB_SLOW_STOP", world.slowStop);
        builder.environment().put("NOVABOT_STUB_FAIL", world.fail);
        builder.redirectOutput(world.logs.resolve("stdout.txt").toFile());
        builder.redirectError(world.logs.resolve("stderr.txt").toFile());
        return builder;
    }

    private static Run finish(World world, Process process) throws Exception {
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        Run run = new Run();
        run.code = finished ? process.exitValue() : -1;
        run.stdout = read(world.logs.resolve("stdout.txt"));
        run.stderr = read(world.logs.resolve("stderr.txt"));
        assertTrue(finished, "换版工具 180 秒还没结束。输出：" + run.stdout + run.stderr);
        return run;
    }

    private static String unitState(World world, String version) throws IOException {
        Path units = world.stubState.resolve("units");
        if (!Files.isRegularFile(units)) {
            return "missing";
        }
        String want = "novabot@" + version;
        for (String line : Files.readAllLines(units, StandardCharsets.UTF_8)) {
            int sp = line.indexOf(' ');
            if (sp > 0 && line.substring(0, sp).equals(want)) {
                return line.substring(sp + 1).trim();
            }
        }
        return "missing";
    }

    private static List<String> calls(World world) throws IOException {
        Path log = world.logs.resolve("systemctl.log");
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        return Files.readAllLines(log, StandardCharsets.UTF_8);
    }

    private static List<String> mutations(World world) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : calls(world)) {
            if (line.startsWith("start ") || line.startsWith("stop ")
                    || line.startsWith("disable ") || line.startsWith("enable ")) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean called(World world, String exact) throws IOException {
        return calls(world).contains(exact);
    }

    private static boolean calledPrefix(World world, String prefix) throws IOException {
        for (String line : calls(world)) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean before(World world, String earlier, String later) throws IOException {
        int left = -1;
        int right = -1;
        List<String> lines = calls(world);
        for (int i = 0; i < lines.size(); i++) {
            if (left < 0 && lines.get(i).equals(earlier)) {
                left = i;
            }
            if (lines.get(i).equals(later)) {
                right = i;
            }
        }
        return left >= 0 && right > left;
    }

    private static boolean autostartTouched(World world) throws IOException {
        for (String line : calls(world)) {
            if (line.startsWith("enable ") || line.startsWith("disable ")) {
                return true;
            }
        }
        return false;
    }

    private static String lastLine(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        int nl = trimmed.lastIndexOf('\n');
        return nl < 0 ? trimmed : trimmed.substring(nl + 1);
    }

    private static String inode(Path path) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ls", "-di", path.toString()).start();
        assertEquals(0, process.waitFor(), "ls -di 应退 0");
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return text.split("\\s+")[0];
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
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
        Path install;
        Path bin;
        Path runDir;
        Path stateDir;
        Path stubState;
        Path logs;
        Path locks;
        Path meminfo;
        String scene;
        String active;
        String target;
        String slowStop;
        String fail;
    }

    private static final class Run {
        int code;
        String stdout;
        String stderr;
    }
}
