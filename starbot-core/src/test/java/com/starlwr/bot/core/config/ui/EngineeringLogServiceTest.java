package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工程日志尾读测试
 * <p>
 * 这一路把一份<b>本机文件</b>端到浏览器里，因此两件事逐格钉住：读多少、以及带不带凭据出去。
 * <p>
 * 打码这一格奔着一类具体的错去：日志里出现凭据从来不是有人故意写的，
 * 而是某个异常把整串地址、整个请求头裹进了 message。它不报错、不影响任何功能，
 * 只是在控制台上多显示了一串——<b>而看控制台的那一刻，人往往正在把屏幕分享给别人看</b>。
 */
@DisplayName("工程日志尾读")
class EngineeringLogServiceTest {
    @TempDir
    Path dir;

    private EngineeringLogService service;

    private Path file;

    @BeforeEach
    void setUp() {
        service = new EngineeringLogService();
        file = dir.resolve("starbot.log");
    }

    @Test
    @DisplayName("只读最后若干行, 并说清上面还有")
    void readsOnlyTheTail() throws IOException {
        write(line(1), line(2), line(3), line(4), line(5));

        EngineeringLogService.Tail tail = service.tail(file, 2);

        assertEquals(List.of(line(4), line(5)), tail.lines(), "尾巴在下面, 顺序照文件里的来");
        assertTrue(tail.more(), "上面还有三行, 得说");

        EngineeringLogService.Tail whole = service.tail(file, 100);
        assertEquals(5, whole.lines().size());
        assertFalse(whole.more(), "全给了就不该说还有");
    }

    @Test
    @DisplayName("条数上限有个天花板, 报出来的是真正生效的那个数")
    void limitIsCapped() throws IOException {
        write(line(1));

        assertEquals(EngineeringLogService.DEFAULT_LIMIT, service.effectiveLimit(0),
                "没给条数时用默认值");
        assertEquals(EngineeringLogService.DEFAULT_LIMIT, service.effectiveLimit(-5));
        assertEquals(EngineeringLogService.MAX_LIMIT,
                service.effectiveLimit(EngineeringLogService.MAX_LIMIT * 10),
                "要得再多也只给到上限, 而且要如实报出这个数");
        assertEquals(7, service.effectiveLimit(7));
    }

    @Test
    @DisplayName("文件还没建出来时应回空表, 而不是当成出错")
    void missingFileIsNotAnError() throws IOException {
        EngineeringLogService.Tail tail = service.tail(file, 10);

        assertTrue(tail.lines().isEmpty());
        assertFalse(tail.more());
        assertEquals(0, tail.size());
    }

    @Test
    @DisplayName("口令、令牌与 Cookie 应打码")
    void masksCredentials() {
        // 阳性对照。每一条都是真会落进这一份日志的形状：
        // 配置项原样打印、请求头整条打印、异常 message 裹着完整地址
        assertMasked("starbot.core.config-ui.auth.password: hunter2", "hunter2");
        assertMasked("napcat token=abcdef123456", "abcdef123456");
        assertMasked("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", "eyJhbGciOiJIUzI1NiJ9");
        assertMasked("Cookie: SESSDATA=xxxyyy; bili_jct=zzz", "xxxyyy");
        assertMasked("Cookie: SESSDATA=xxxyyy; bili_jct=zzz", "zzz");
        assertMasked("set-cookie: NOVABOT_SESSION=abc; Path=/config", "abc");
        assertMasked("I/O error on GET request for \"https://x.example/api?csrf=deadbeef\"", "deadbeef");
        assertMasked("已签发只读口令 secret=s3cr3t", "s3cr3t");
        assertMasked("客户端凭据 api_credential = zzz9", "zzz9");
        assertMasked("totp-secret: JBSWY3DPEHPK3PXP", "JBSWY3DPEHPK3PXP");
        // 带引号的那一形态照样要盖住：异常 message 里裹着一段 JSON 是常见形状，
        // 而「键紧跟着一个引号」在按 键: 值 找的判法眼里与「没有这个键」长得一样
        assertMasked("解析失败 {\"starbot.core.config-ui.auth.password\":\"hunter2\",\"port\":8080}",
                "hunter2");
        assertMasked("请求体 {\"token\": \"abcdef\"}", "abcdef");
    }

    @Test
    @DisplayName("普通行原样, 不许被打码顺手改掉")
    void leavesOrdinaryLinesAlone() {
        // 阴性对照。打码打过头一样是坏的：读日志的人会以为那一行本来就长这样，
        // 而排障恰恰要靠这些数字
        for (String line : List.of(
                line(1),
                "NovaBot -> QQ ([群] 12345) [7]: 开播了",
                "直播间 22345678 业务消息断流, 已重连 3 次",
                "推送队列 push#8812 第 1/3 次重投, 30 秒后再试",
                "GET https://x.example/api?room_id=22345678&platform=qq",
                "\tat com.starlwr.bot.core.StarBot.main(StarBot.java:42)",
                "监听地址 server.address=0.0.0.0 端口 8080",
                "解析失败 {\"room_id\":\"22345678\",\"port\":8080}",
                // 名字里带 key 三个字母、但并不是密钥的那些
                "keyword=开播 keyName=push.enabled monkey=1")) {
            assertEquals(line, EngineeringLogService.mask(line), "这一行不该被改：" + line);
        }
    }

    @Test
    @DisplayName("打码发生在读盘那一层, 尾读出来的每一行都过了它")
    void tailAppliesMasking() throws IOException {
        write("2026-09-04 20:07:03.221 ERROR 1 --- [main] x : 登录失败 password: hunter2");

        List<String> lines = service.tail(file, 10).lines();

        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("hunter2"), "打码不许只做在某一个调用点上");
        assertTrue(lines.get(0).contains("登录失败"), "该留的话要留住");
    }

    private void assertMasked(String line, String secret) {
        String masked = EngineeringLogService.mask(line);
        assertFalse(masked.contains(secret), "这一串不该出去：" + line + " → " + masked);
        assertTrue(masked.contains(EngineeringLogService.MASK), "打码要留下痕迹：" + masked);
    }

    private String line(int no) {
        return "2026-09-04 20:0" + no + ":01.100  INFO 1 --- [main] c.s.b.core.StarBot : 第 " + no + " 行";
    }

    private void write(String... lines) throws IOException {
        Files.writeString(file, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
