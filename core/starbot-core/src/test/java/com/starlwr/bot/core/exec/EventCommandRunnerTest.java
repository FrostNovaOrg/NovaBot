package com.starlwr.bot.core.exec;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.event.live.common.SuperChatEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件命令测试
 * <p>
 * 这是整个项目里唯一一处能执行任意程序的地方，而喂给它的数据（昵称、弹幕正文）
 * <b>完全由陌生人决定</b>。首要的用例不是「功能能不能用」，而是
 * 「一个精心构造的昵称能不能让它多执行一条命令」。
 */
@DisplayName("事件触发外部命令")
class EventCommandRunnerTest {
    private static final String PLATFORM = "bilibili";

    /**
     * 一个企图逃逸成第二条命令的昵称
     */
    private static final String MALICIOUS = "测试主播; rm -rf ~ #";

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private EventCommandRunner runner;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        runner = new EventCommandRunner(properties);
    }

    private LiveOnEvent event(String uname) {
        return new LiveOnEvent(PLATFORM, new LiveStreamerInfo(19142561034510L, uname, 47121903112096L));
    }

    private NovaCoreProperties.ExecRule rule(String eventName, String... command) {
        NovaCoreProperties.ExecRule rule = new NovaCoreProperties.ExecRule();
        rule.setEvent(eventName);
        rule.setCommand(List.of(command));
        return rule;
    }

    @Test
    @DisplayName("恶意昵称替换后仍是一个参数，不会变成第二条命令")
    void maliciousNameStaysOneArgument() {
        List<String> command = runner.resolve(List.of("/bin/echo", "{uname}"), event(MALICIOUS));

        assertEquals(2, command.size(), "参数个数不该因昵称内容而变化");
        assertEquals(MALICIOUS, command.get(1), "昵称应原样成为一个参数");
    }

    @Test
    @DisplayName("占位符应逐个替换为事件内容")
    void substitutesPlaceholders() {
        List<String> command = runner.resolve(
                List.of("/bin/echo", "{event}", "{platform}", "{uid}", "{uname}", "{room_id}"), event("测试主播"));

        assertEquals(List.of("/bin/echo", "LiveOnEvent", "bilibili", "19142561034510", "测试主播", "47121903112096"), command);
    }

    @Test
    @DisplayName("按事件基类配置应能命中各平台的具体事件")
    void matchesAlongInheritanceChain() {
        assertTrue(runner.matches(rule("LiveOnEvent"), event("测试主播")));
        assertTrue(runner.matches(rule("NovaLiveStatusChangeEvent"), event("测试主播")), "配基类应命中子类");
        assertTrue(runner.matches(rule("com.starlwr.bot.core.event.live.common.LiveOnEvent"), event("测试主播")),
                "全限定名同样应命中");
        assertFalse(runner.matches(rule("LiveOffEvent"), event("测试主播")), "别的事件不该被命中");
        assertFalse(runner.matches(rule(""), event("测试主播")), "空事件名不该命中一切");
        assertFalse(runner.matches(rule(null), event("测试主播")));
    }

    @Test
    @DisplayName("默认关闭：没打开开关时不该执行任何命令")
    void disabledByDefault() throws Exception {
        Path marker = dir.resolve("should-not-exist");
        properties.getExec().getRules().add(rule("LiveOnEvent", touchScript().toString(), marker.toString()));

        runner.onEvent(event("测试主播"));

        Thread.sleep(300);
        assertFalse(Files.exists(marker), "开关未打开时命令不该执行");
    }

    @Test
    @DisplayName("单条规则被停用时不该执行")
    void disabledRuleIsSkipped() throws Exception {
        Path marker = dir.resolve("should-not-exist");
        NovaCoreProperties.ExecRule rule = rule("LiveOnEvent", touchScript().toString(), marker.toString());
        rule.setEnabled(false);
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule);

        runner.onEvent(event("测试主播"));

        Thread.sleep(300);
        assertFalse(Files.exists(marker));
    }

    @Test
    @DisplayName("端到端：恶意昵称原样作为一个参数抵达脚本，未被拆成命令")
    @DisabledOnOs(OS.WINDOWS)
    void maliciousNameReachesScriptAsSingleArgument() throws Exception {
        Path output = dir.resolve("args.txt");
        Path script = script("""
                #!/bin/sh
                out="$1"
                shift
                : > "$out"
                for arg in "$@"; do printf '%s\\n' "$arg" >> "$out"; done
                """);

        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(
                rule("LiveOnEvent", script.toString(), output.toString(), "{uname}", "尾参数"));

        runner.onEvent(event(MALICIOUS));

        waitForFile(output);
        assertEquals(List.of(MALICIOUS, "尾参数"), Files.readAllLines(output, StandardCharsets.UTF_8),
                "脚本收到的应当是两个参数，且第一个原样等于那个昵称");
    }

    @Test
    @DisplayName("超时的命令应被强制结束，而不是一直挂着")
    @DisabledOnOs(OS.WINDOWS)
    void killsOnTimeout() throws Exception {
        Path started = dir.resolve("started");
        Path finished = dir.resolve("finished");
        Path script = script("""
                #!/bin/sh
                : > "$1"
                sleep 30
                : > "$2"
                """);

        properties.getExec().setEnabled(true);
        properties.getExec().setTimeout(1);
        properties.getExec().getRules().add(
                rule("LiveOnEvent", script.toString(), started.toString(), finished.toString()));

        runner.onEvent(event("测试主播"));

        waitForFile(started);
        Thread.sleep(2500);
        assertFalse(Files.exists(finished), "超时后脚本应已被杀掉，不该跑到最后一行");
    }

    @Test
    @DisplayName("输出很大的脚本应能正常结束，不该被管道缓冲卡成超时")
    @DisabledOnOs(OS.WINDOWS)
    void survivesLargeOutput() throws Exception {
        Path done = dir.resolve("done");
        // 先刷出远超管道缓冲的输出，再落一个标记文件。
        // 若实现是「等进程结束再读输出」，脚本会阻塞在写操作上，标记永远不会出现
        Path script = script("""
                #!/bin/sh
                i=0
                while [ $i -lt 4000 ]; do
                  echo "0123456789012345678901234567890123456789012345678901234567890123456789"
                  i=$((i+1))
                done
                : > "$1"
                """);

        properties.getExec().setEnabled(true);
        properties.getExec().setTimeout(20);
        properties.getExec().getRules().add(rule("LiveOnEvent", script.toString(), done.toString()));

        runner.onEvent(event("测试主播"));

        waitForFile(done);
        assertTrue(Files.exists(done), "输出量大的脚本也应跑完");
    }

    @Test
    @DisplayName("不匹配的事件不该触发命令")
    void unmatchedEventDoesNothing() throws Exception {
        Path marker = dir.resolve("should-not-exist");
        properties.getExec().setEnabled(true);
        properties.getExec().getRules().add(rule("LiveOnEvent", touchScript().toString(), marker.toString()));

        runner.onEvent(new SuperChatEvent(PLATFORM, new LiveStreamerInfo(1L, "测试主播", 2L), null, "内容", 30.0));

        Thread.sleep(300);
        assertFalse(Files.exists(marker));
    }

    /**
     * 一个把第一个参数当作文件路径创建出来的脚本
     */
    private Path touchScript() throws Exception {
        return script("""
                #!/bin/sh
                : > "$1"
                """);
    }

    private Path script(String body) throws Exception {
        Path path = Files.createTempFile(dir, "cmd", ".sh");
        Files.writeString(path, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    /**
     * 等文件出现，最多等 10 秒
     */
    private void waitForFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                // 内容可能还没写完，稍等一下再读
                Thread.sleep(150);
                return;
            }
            Thread.sleep(50);
        }
    }
}
