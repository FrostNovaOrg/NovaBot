package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 只看警告、错误时整天那一份里找
 * <p>
 * 尾读只吃最后 1MB，而一天的日志可以有一两百 MB：散在一天里的错误落在 1MB 之外时，
 * 页面会说「没有」——看的人读成这天没出过错，那天其实有几十条。
 * <p>
 * 这里的几格现造几 MB 的日志，错误散开，且<b>尾部 1MB 内一条错误都没有</b>：
 * 「找得到最靠前那条」与「一条都找不到」在这一份上是两种读数，不是措辞差别。
 * <p>
 * 走接口而不是直接调服务：这一格要钉的是<b>请求里写什么，服务端从哪儿找</b>，
 * 而这件事只在查询串进门之后才成立。
 */
@DisplayName("工程日志整天找问题档")
class EngineeringLogScanTest {

    /** 每行（含换行）正好这么多字节，见 {@code fixed()} */
    private static final int LINE = 1999;

    /** 回扫每趟读这么宽，与服务里的同宽——跨块那几格要按它把边界钉在字中间 */
    private static final int SCAN_BLOCK = 256 * 1024;

    @TempDir
    Path dir;

    private Path log;

    @BeforeEach
    void setUp() {
        log = dir.resolve("starbot.log");
    }

    @Test
    @DisplayName("只看错误时整天那份里都找得到, 带堆栈的那条整条跟着")
    void findsErrorsScatteredThroughTheDay() throws Exception {
        writeScatteredDay();

        JSONObject found = call("?limit=200&d=2026-09-25&levels=error");
        String text = joined(found);

        assertTrue(text.contains("err-early"),
                "这一天最靠前那条错误要找得到；只读尾巴时它落在 1MB 之外，页面会说「没有」。得到：" + found.keySet());
        assertTrue(text.contains("at a.b.C.d") && text.contains("at e.f.G.h"),
                "带堆栈的那条要连它下面的堆栈一起回来，不许只回来头一行");
        assertTrue(text.contains("err-scattered-"), "散在一天里的那些也要找得到");
        assertTrue(text.contains("err-scattered-0") && text.contains("err-scattered-2850"),
                "最靠前与最靠后那两条散落的都该在：少回来一条，就是有半行被读块切开后丢掉了");
        assertEquals(21, levelHeads(text), "这一天 21 条错误，一条不多一条不少");
    }

    @Test
    @DisplayName("四档全勾照旧只读尾巴, 不去整天里翻")
    void fourOnStillReadsOnlyTheTail() throws Exception {
        writeScatteredDay();

        JSONObject found = call("?limit=200&d=2026-09-25");
        String text = joined(found);

        assertFalse(text.contains("err-early"),
                "四档全勾时行为照旧：只读最后那一小段，不该顺手把整天翻出来");
        assertTrue(text.contains("fill 5999"), "尾巴那一段确实读进来了，只是里面没有错误");
    }

    @Test
    @DisplayName("行被读块切开也不丢, 一条都不许少")
    void keepsEveryLineAcrossReadBlocks() throws Exception {
        writeAllErrors(2000);

        String text = joined(call("?limit=2000&d=2026-09-25&levels=error"));

        assertEquals(2000, levelHeads(text),
                "每行都是错误时，少一条就是计数短一条；得到：" + levelHeads(text));
    }

    @Test
    @DisplayName("一条日志连同它下面的堆栈算一条, 凑够条数就停")
    void countsAnEntryWithItsStackAsOne() throws Exception {
        writeTwoStackedErrors();

        JSONObject found = call("?limit=1&d=2026-09-25&levels=error");
        List<String> lines = lines(found);

        assertEquals(3, lines.size(), "头一行加两行堆栈算一条；凑够 1 条就该停，得到：" + lines);
        assertTrue(lines.get(0).contains("err-second"), "凑够的是最新的那一条");
        assertTrue(lines.get(1).contains("K.java") && lines.get(2).contains("O.java"),
                "堆栈要跟在它自己那一行后面");
        assertFalse(String.join("\n", lines).contains("err-first"), "更早那一条不该被撕进来");
    }

    @Test
    @DisplayName("扫出来的行照旧打码")
    void stillMasksWhatItFinds() throws Exception {
        writeSecrets();

        String text = joined(call("?limit=50&d=2026-09-25&levels=error"));

        assertFalse(text.contains("abc123secret"), "Cookie 里的值不许带出来；得到：" + text);
        assertFalse(text.contains("qrcode_key_secret999"), "令牌值不许带出来；得到：" + text);
        assertTrue(text.contains("cookie") && text.contains("token"),
                "键名还在，看得出这两行本来带着凭据");
    }

    @Test
    @DisplayName("扫到上限还没凑够时说清扫到几点几分")
    void saysWhereTheScanStopped() throws Exception {
        writeBeyondScanCap();

        JSONObject found = call("?limit=50&d=2026-09-25&levels=error");

        assertFalse(joined(found).contains("err-outside-window"),
                "回扫撞上限就停，不该把上限之外那条捞出来");
        assertEquals("09:00", found.getString("scannedTo"),
                "扫到哪儿要写明，让看的人知道更早的没有扫");
        assertTrue(found.getBooleanValue("more"), "更早还有没扫到的，得说");
    }

    @Test
    @DisplayName("认不出的级别档名明说, 不当成不筛")
    void rejectsUnknownLevel() throws Exception {
        writeTwoStackedErrors();

        JSONObject found = call("?limit=50&d=2026-09-25&levels=oops");

        assertFalse(found.getBooleanValue("success"), "认不出的档名不该默默当成没筛；得到：" + found);
        assertTrue(String.valueOf(found.get("message")).contains("oops"), "要说清是哪一个认不出");
    }

    @Test
    @DisplayName("跨读块的中文错误行不许出乱码")
    void keepsChineseIntactAcrossReadBlocks() throws Exception {
        int count = 400;
        String marker = "失败中文内直播";
        writeChineseErrorLines(count, marker);

        JSONObject found = call("?limit=2000&d=2026-09-25&levels=error");
        List<String> scanned = lines(found);
        String text = joined(found);

        assertEquals(count, scanned.size(), "这一天 " + count + " 条错误，一条不多一条不少");
        for (String line : scanned) {
            assertFalse(line.contains("�"),
                    "读块边界落在字中间时，那个字不许被解成替代字符：" + line.substring(0, Math.min(120, line.length())));
            assertTrue(line.contains(marker), "这一行缺了字，复制出去就带着乱码：" + line.substring(0, Math.min(120, line.length())));
        }
        assertTrue(text.contains("错误编号-399-" + marker), "被钉在块边界那一条也要原样");

        // 阴性对照：同一份文件读尾部走的是一次解码，本来就该干净
        String tail = joined(call("?limit=2000&d=2026-09-25"));
        assertFalse(tail.contains("�"), "读尾部不许出乱码");
        assertTrue(tail.contains("错误编号-399-" + marker), "读尾部要认得出那条被钉过的行");
    }

    @Test
    @DisplayName("一天错误不到一页时一次回扫也要很快")
    void scansSparseErrorsQuickly() throws Exception {
        writeSparseDay();

        long start = System.nanoTime();
        JSONObject found = call("?limit=300&d=2026-09-25&levels=error");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String text = joined(found);
        assertTrue(text.contains("sparse-err-0") && text.contains("sparse-err-2"),
                "这一天只有 3 条错误，都该找得到；得到 " + levelHeads(text) + " 条");
        assertEquals(3, levelHeads(text), "就这 3 条，不多不少");
        System.out.println("整天回扫：几十万条短行里只夹 3 条错误，一次请求实测 " + elapsedMs + " 毫秒");
        // 门槛从宽：这一格盯的是「等上好几秒」，机器慢一截也不该误红
        assertTrue(elapsedMs < 2500, "整天翻一遍不该等上好几秒，实测 " + elapsedMs + " 毫秒");
    }

    @Test
    @DisplayName("堆栈长过上限时留住开头与结尾两截, 并写明中间省略了几行")
    void capsContinuationLinesPerEntry() throws Exception {
        // 异常真正抛出的地方在紧跟着头一行的那几帧；中间深不见底的重复帧对排障几乎无用。
        // 不设上限的话，一份认不出几处行首的日志会把它们全攒在内存里
        StringBuilder text = new StringBuilder(at(6, 10, "ERROR", "深堆栈 err-deep") + "\n");
        for (int i = 1; i <= 6000; i++) {
            text.append("\tat deep.Frame").append(i).append("(Frame.java:").append(i).append(")\n");
        }
        Files.writeString(log, text.toString(), StandardCharsets.UTF_8);

        List<String> lines = lines(call("?limit=50&d=2026-09-25&levels=error"));

        assertEquals(EngineeringLogService.MAX_CONT_LINES + 2, lines.size(),
                "头一行加两截续行加一行说明；得到 " + lines.size());
        assertTrue(lines.get(0).contains("err-deep"), "头一行在最前");
        String note = lines.stream().filter(l -> l.contains("省略")).findFirst().orElse("");
        assertTrue(note.contains("5500"), "省略了几行要写明；得到说明行：" + note);
        String kept = String.join("\n", lines);
        assertTrue(kept.contains("at deep.Frame1("), "紧跟着头一行的那几帧要留住");
        assertTrue(kept.contains("at deep.Frame200("), "开头那一截收到第 200 帧");
        assertFalse(kept.contains("at deep.Frame201("), "中间的帧该省");
        assertTrue(kept.contains("at deep.Frame5701("), "结尾那一截从第 5701 帧起");
        assertTrue(kept.contains("at deep.Frame6000("), "最后一帧要在");
    }

    @Test
    @DisplayName("堆栈长过上限时, 最底下的 Caused by 根因要留住")
    void keepsTheBottomCausedByOfAnOverlongStack() throws Exception {
        // Java 的根因链「Caused by: …」印在堆栈最底下；只留开头那一截会把根因丢掉
        StringBuilder text = new StringBuilder(at(6, 10, "ERROR", "深堆栈 err-root-stack") + "\n");
        for (int i = 1; i <= 799; i++) {
            text.append("\tat deep.Frame").append(i).append("(Frame.java:").append(i).append(")\n");
        }
        text.append("Caused by: java.net.ConnectException: 连不上\n");
        Files.writeString(log, text.toString(), StandardCharsets.UTF_8);

        List<String> lines = lines(call("?limit=50&d=2026-09-25&levels=error"));

        assertEquals(EngineeringLogService.MAX_CONT_LINES + 2, lines.size(),
                "头一行加两截续行加一行说明；得到 " + lines.size());
        assertTrue(lines.get(0).contains("err-root-stack"), "头一行在最前");
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("Caused by"), "最底下的根因要留住；得到末行：" + last);
        String kept = String.join("\n", lines);
        assertTrue(kept.contains("省略") && kept.contains("300"), "中间省略了几行要写明");
        assertTrue(kept.contains("at deep.Frame1("), "紧跟着头一行的那几帧要留住");
        assertTrue(kept.contains("at deep.Frame200("), "开头那一截收到第 200 帧");
        assertFalse(kept.contains("at deep.Frame201("), "中间的帧该省");
    }

    @Test
    @DisplayName("回扫翻到一行超长的, 只带回开头一段")
    void truncatesAHugeLineWhileScanning() throws Exception {
        try (OutputStream out = Files.newOutputStream(log)) {
            out.write(fixed(LINE, 6, 5, "INFO", "fill").getBytes(StandardCharsets.UTF_8));
            out.write((at(6, 10, "ERROR", "err-huge ") + "#".repeat(2_000_000) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        long start = System.nanoTime();
        List<String> lines = lines(call("?limit=50&d=2026-09-25&levels=error"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, levelHeads(String.join("\n", lines)), "就那一条错误");
        String clipped = lines.get(0);
        assertTrue(clipped.contains("err-huge"), "开头那一段要留住");
        assertTrue(clipped.length() <= EngineeringLogService.MAX_LINE_BYTES + 40,
                "只留开头一段；得到 " + clipped.length() + " 字");
        assertTrue(clipped.contains("已截去"), "截掉了多少要写明");
        assertTrue(elapsedMs < 10_000, "两 MB 的一行不该等很久，实测 " + elapsedMs + " 毫秒");
    }

    // ---- 造日志 ----

    /**
     * 一份几 MB 的日志：错误散在前面那一大段里，最后这一整块只有信息行
     * <p>
     * 尾读只取最后 1MB，在里面筛「错误」一条都不会有，而这一天其实出过事。
     */
    private void writeScatteredDay() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            if (i == 5) {
                text.append(fixed(LINE, 6, 10, "ERROR", "conn failed err-early"));
                text.append("\tat a.b.C.d(C.java:1)").append('\n');
                text.append("\tat e.f.G.h(G.java:2)").append('\n');
            } else if (i == 900) {
                text.append(fixed(LINE, 6, 30, "ERROR", "req failed err-cookie cookie: SESSDATA=abc123secret"));
            } else if (i % 150 == 0) {
                text.append(fixed(LINE, 6, 20, "ERROR", "again err-scattered-" + i));
            }
            text.append(fixed(LINE, 6, 40, "INFO", "fill " + i));
        }
        for (int i = 3000; i < 6000; i++) {
            text.append(fixed(LINE, 6, 50, "INFO", "fill " + i));
        }
        Files.writeString(log, text.toString(), StandardCharsets.UTF_8);
    }

    /** 两条错误各带两行堆栈，凑不够 2 条就停时看得出条数按条算、不按行算 */
    private void writeTwoStackedErrors() throws IOException {
        String text = at(6, 10, "ERROR", "早的那条 err-first")
                + "\n\tat a.b.C.d(C.java:1)"
                + "\n\tat e.f.G.h(G.java:2)"
                + "\n" + at(6, 20, "ERROR", "晚的那条 err-second")
                + "\n\tat i.j.K.l(K.java:3)"
                + "\n\tat m.n.O.p(O.java:4)"
                + "\n";
        Files.writeString(log, text, StandardCharsets.UTF_8);
    }

    /** 两条错误各带着凭据；扫出来时值要盖住、键名要留下 */
    private void writeSecrets() throws IOException {
        String text = at(6, 10, "ERROR", "带 Cookie cookie: SESSDATA=abc123secret")
                + "\n" + at(6, 20, "ERROR", "带令牌 token=qrcode_key_secret999")
                + "\n";
        Files.writeString(log, text, StandardCharsets.UTF_8);
    }

    /**
     * 一份刚越过回扫上限的日志：那条错误在最开头，落在上限之外
     * <p>
     * 夹具的大小按服务里的上限现算，不写死：上限一改，这一格自动跟着走。
     * <b>不按上限量就测不出「撞上限就停」</b>——上限放大的那一趟，小夹具会一路扫到底，
     * 于是「扫到几点几分」回的是空，而那正是上限没生效的形状。
     */
    private void writeBeyondScanCap() throws IOException {
        byte[] head = fixed(LINE, 8, 0, "ERROR", "err-outside-window").getBytes(StandardCharsets.UTF_8);
        byte[] block = fixed(LINE, 9, 0, "INFO", "fill").getBytes(StandardCharsets.UTF_8);
        long target = (long) EngineeringLogService.MAX_SCAN_BYTES + block.length;
        try (OutputStream out = Files.newOutputStream(log)) {
            out.write(head);
            long written = head.length;
            while (written < target) {
                out.write(block);
                written += block.length;
            }
        }
    }

    /**
     * 几百条中文错误行，另有一条的读块边界正好切在「失」字中间
     * <p>
     * 中文一个字三个字节，块边界落在字中间是常态：这一份把边界<b>钉</b>在「失」字
     * 第一字节之后，于是被切开那条就是它。按块各自解码再拼字符串时，两半各解成
     * 替代字符，拼回去也好不了——复制出去那行就带着「�」。
     * 其余每条也带同一个词：万一别的边界也切在字中间，一并被同一格盖住。
     * 行尾填充用 {@code #} 而不用字母，理由同 {@code fixed()}：不给打码那条键名正则喂词干。
     */
    private void writeChineseErrorLines(int count, String marker) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            body.append(at(7, i / 60, "ERROR", "错误编号-" + i + "-" + marker + "的现场记录"))
                    .append("#".repeat(2800)).append('\n');
        }
        String special = at(7, 59, "ERROR", "错误编号-" + (count - 1) + "-" + marker);
        byte[] specialBytes = special.getBytes(StandardCharsets.UTF_8);
        int m = indexOfBytes(specialBytes, marker.getBytes(StandardCharsets.UTF_8));
        if (m < 0) {
            throw new IllegalStateException("对齐用的那个词没落进这一行");
        }
        // 「失」第一字节之后正好一个读块：块边界就落在「失」字中间
        int pad = SCAN_BLOCK - (specialBytes.length - m);
        Files.writeString(log, body + special + "#".repeat(pad) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 几十 MB 的短行，一天里只夹 3 条错误
     * <p>
     * 「只看错误」在这一天的常见形状：错误条数凑不满一页，于是整天那一段都要翻一遍。
     * 行压到只剩行首是刻意的——行数越多，按块反复重排那条路越吃亏。
     */
    private void writeSparseDay() throws IOException {
        byte[] filler = fixed(60, 9, 0, "INFO", "fill").getBytes(StandardCharsets.UTF_8);
        byte[] chunk = new byte[filler.length * 4000];
        for (int i = 0; i < 4000; i++) {
            System.arraycopy(filler, 0, chunk, i * filler.length, filler.length);
        }
        try (OutputStream out = Files.newOutputStream(log)) {
            for (int i = 0; i < 220; i++) {
                out.write(chunk);
            }
            for (int i = 0; i < 3; i++) {
                out.write(fixed(80, 10, i, "ERROR", "sparse-err-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 每一行都是错误：少回来一条就是有半行被读块丢掉了，而页面看着只是「少了一条」 */
    private void writeAllErrors(int count) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(fixed(LINE, 7, 0, "ERROR", "all-err-" + i));
        }
        Files.writeString(log, text.toString(), StandardCharsets.UTF_8);
    }

    private static String at(int hour, int minute, String level, String text) {
        return String.format("2026-09-25 %02d:%02d:00.000 %s 1 --- [main] o.f.n.demo %s", hour, minute, level, text);
    }

    /**
     * 每行（含行尾换行）正好 {@link #LINE} 字节
     * <p>
     * 1999 是质数，而读块大小是 2 的幂：读块边界因此<b>永远</b>落在某一行中间，
     * 「半行被读块切开」这条路在每一次读块上都走一遍。行宽取成读块的约数时，
     * 边界正好落在行与行之间，那一格一次也没跑过——而它照样全绿。
     * 全是 ASCII，字节长才等于字符串长。
     * <p>
     * 填充用 {@code #} 而不用字母：打码那条键名正则在「一长串词字符」上是平方级的，
     * 填充若也是词字符，每一行都会喂给它一条上千字节的词干，整份夹具跑几分钟起步。
     * {@code #} 不属于键名那一串的字符，词干在正文那儿就断了。
     */
    private static String fixed(int width, int hour, int minute, String level, String text) {
        String head = String.format("2026-09-25 %02d:%02d:00.000 %s 1 --- [main] o.f.n.demo ",
                hour, minute, level);
        int pad = width - 1 - head.length() - text.length();
        if (pad < 0) {
            throw new IllegalArgumentException("这一行装不进 " + width + " 字节：" + text);
        }
        return head + text + "#".repeat(pad) + "\n";
    }

    /** 带堆栈的行数只认头一行，堆栈里的 {@code at ...} 不算一条 */
    private static int levelHeads(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (line.contains(" ERROR ")) {
                count++;
            }
        }
        return count;
    }

    // ---- 走接口 ----

    private MockMvc mockMvc() {
        // 这一格只关心「按什么条件找」，文件落哪儿不重要，所以把取文件那一步指到临时目录
        EngineeringLogService service = new EngineeringLogService() {
            @Override
            public Optional<Path> file() {
                return Optional.of(log);
            }

            @Override
            public Optional<Path> file(LocalDate day) {
                return Optional.of(log);
            }
        };
        return MockMvcBuilders.standaloneSetup(new EngineeringLogController(service)).build();
    }

    private JSONObject call(String query) throws Exception {
        MvcResult result = mockMvc()
                .perform(get(ConfigUiController.BASE_PATH + "/api/engineering-log" + query))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(result.getResponse().getStatus() == 200 && body != null && !body.isBlank(),
                "接口要回一段 JSON；状态码 " + result.getResponse().getStatus() + "，正文 " + body);
        return JSON.parseObject(body);
    }

    private static List<String> lines(JSONObject found) {
        JSONArray items = found.getJSONArray("lines");
        return items == null ? List.of() : items.toJavaList(String.class);
    }

    private static String joined(JSONObject found) {
        return String.join("\n", lines(found));
    }
}
