package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
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

    /**
     * 翻别的日子读的是<b>那一天那份文件</b>
     * <p>
     * 落点由 {@code logback.xml} 里那条 {@code fileNamePattern} 定，因此这里量的是
     * 「按模板算得对不对」，而不是「今天这台机器的日志在哪」——后者随 LOG_HOME 变，
     * 拿它当判据就是把此刻的盘面钉进判据里。
     */
    @Test
    @DisplayName("按模板算得出某一天那份文件")
    void resolvesTheFileForAGivenDay() {
        String pattern = "/tmp/nova/logs/%d{yyyy-MM,aux}/starbot-%d{yyyy-MM-dd}.log";

        assertEquals(Path.of("/tmp/nova/logs/2026-09/starbot-2026-09-01.log"),
                EngineeringLogService.fileOn(pattern, LocalDate.of(2026, 9, 1)).orElse(null),
                "月份那一段与日期那一段都得跟着换：只换文件名的话，跨月那几天读到的是不存在的路径");
        assertEquals(Path.of("/tmp/nova/logs/2026-08/starbot-2026-08-31.log"),
                EngineeringLogService.fileOn(pattern, LocalDate.of(2026, 8, 31)).orElse(null));

        // 模板里没有日期占位符时算出来的是个常量。这一路在现行 logback.xml 下走不到
        // （不按天滚动的部署根本不给模板，见 pattern()），此处钉的是这个纯函数自己的口径
        assertEquals(Path.of("/tmp/nova/starbot.log"),
                EngineeringLogService.fileOn("/tmp/nova/starbot.log", LocalDate.of(2026, 9, 1))
                        .orElse(null));
        assertTrue(EngineeringLogService.fileOn(null, LocalDate.of(2026, 9, 1)).isEmpty(),
                "没有模板时说不出路径, 不许猜一个出来");
    }

    /**
     * 三天各一份、每份多行的夹具：读的必须是点名那一天那一份
     */
    @Test
    @DisplayName("定位到点名的那一分钟, 并给出它前后各若干行")
    void locatesTheRequestedMinute() throws IOException {
        Path second = threeDays();

        EngineeringLogService.Window window = service.around(second, LocalTime.of(20, 7), 2);

        assertTrue(window.exact(), "那一分钟确实有记录");
        assertEquals("20:07", window.nearest());
        assertEquals(5, window.lines().size(), "前后各 2 行加它自己");
        assertEquals(2, window.highlight(), "高亮的是点名那一行");
        assertTrue(window.lines().get(2).contains("第 3 行"));
        // 读的是点名那一天那一份：串到别的日子上去的话，屏幕上那一刻是对的、内容是别天的
        for (String line : window.lines()) {
            assertTrue(line.contains("2026-09-02") || line.startsWith("\t"),
                    "串进了别的日子那一份: " + line);
        }
    }

    @Test
    @DisplayName("那一分钟没有记录时给最近的一行, 并说明它不是点名的那一刻")
    void fallsBackToTheNearestLine() throws IOException {
        Path second = threeDays();

        // 阴性一：点名的时刻在这一天所有记录之后
        EngineeringLogService.Window after = service.around(second, LocalTime.of(21, 30), 2);
        assertFalse(after.exact(), "没有那一分钟就得说, 不许假装定位到了");
        assertEquals("20:09", after.nearest(), "取最近的那一行");
        assertTrue(after.lines().get(after.highlight()).contains("第 6 行"));

        // 阴性二：点名的时刻在这一天所有记录之前
        EngineeringLogService.Window before = service.around(second, LocalTime.of(0, 1), 2);
        assertFalse(before.exact());
        assertEquals("20:05", before.nearest());
        assertTrue(before.lines().get(before.highlight()).contains("第 1 行"));

        // 阴性三：那一天一行都没有
        EngineeringLogService.Window empty = service.around(dir.resolve("没有这一天.log"),
                LocalTime.of(20, 7), 2);
        assertTrue(empty.lines().isEmpty());
        assertFalse(empty.exact());
        assertEquals(-1, empty.highlight(), "没有可高亮的行时不许指到第 0 行上");
    }

    @Test
    @DisplayName("跟随最新只取那个位置之后新写进去的, 写了一半的行不算")
    void followReadsOnlyWhatWasAppended() throws IOException {
        write(line(1), line(2));
        long offset = service.tail(file, 10).offset();

        // 阴性：一个字节都没新写时给空表，而不是把已经显示过的行再发一遍
        assertTrue(service.since(file, offset).lines().isEmpty(), "没有新行时不该重复给旧行");

        append(line(3) + System.lineSeparator());
        EngineeringLogService.Appended one = service.since(file, offset);
        assertEquals(List.of(line(3)), one.lines(), "只给新写的那一行");
        assertFalse(one.reset());

        // 写了一半的行先不给：给了的话，剩下半行随后会作为另一行出现，
        // 而两个半行里的凭据各自都躲得过按整行判的打码
        append("2026-09-04 20:08:01.100  INFO 1 --- [main] c.s.b.core.StarBot : 半");
        EngineeringLogService.Appended half = service.since(file, one.offset());
        assertTrue(half.lines().isEmpty(), "半行先不给");
        assertEquals(one.offset(), half.offset(), "位置停在最后一个完整行的末尾");

        append("行" + System.lineSeparator());
        EngineeringLogService.Appended whole = service.since(file, half.offset());
        assertEquals(1, whole.lines().size());
        assertTrue(whole.lines().get(0).endsWith("半行"), "写完了整行才给出去");

        // 文件被换掉（滚动、清空）时说出来：从头当成新行的话，屏幕上会突然多出一整份日志
        assertTrue(service.since(file, whole.offset() + 10_000).reset(),
                "位置比文件还长, 说明这已经不是刚才那一份了");
    }

    @Test
    @DisplayName("定位与跟随两条路上的行同样打码")
    void newReadPathsAlsoMask() throws IOException {
        write("2026-09-04 20:05:01.100  INFO 1 --- [main] x : 起来了",
                "2026-09-04 20:07:03.221 ERROR 1 --- [main] x : 登录失败 password: hunter2");

        EngineeringLogService.Window window = service.around(file, LocalTime.of(20, 7), 2);
        assertFalse(String.join("\n", window.lines()).contains("hunter2"),
                "打码不许只做在尾读那一个调用点上");

        long offset = 0L;
        EngineeringLogService.Appended appended = service.since(file, offset);
        assertFalse(String.join("\n", appended.lines()).contains("hunter2"));
    }

    /**
     * 三天各一份，中间那一天六行（含一行堆栈）
     * @return 中间那一天的文件
     */
    private Path threeDays() throws IOException {
        Files.writeString(dir.resolve("starbot-2026-09-01.log"),
                "2026-09-01 20:07:01.100  INFO 1 --- [main] x : 前一天也有 20:07"
                        + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("starbot-2026-09-03.log"),
                "2026-09-03 20:07:01.100  INFO 1 --- [main] x : 后一天也有 20:07"
                        + System.lineSeparator(), StandardCharsets.UTF_8);

        Path second = dir.resolve("starbot-2026-09-02.log");
        Files.writeString(second, String.join(System.lineSeparator(),
                "2026-09-02 20:05:01.100  INFO 1 --- [main] x : 第 1 行",
                "2026-09-02 20:06:01.100  INFO 1 --- [main] x : 第 2 行",
                "2026-09-02 20:07:03.221 ERROR 1 --- [main] x : 第 3 行",
                "\tat a.b.C.d(C.java:1)",
                "2026-09-02 20:08:01.100  INFO 1 --- [main] x : 第 5 行",
                "2026-09-02 20:09:01.100  INFO 1 --- [main] x : 第 6 行") + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return second;
    }

    private void append(String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
