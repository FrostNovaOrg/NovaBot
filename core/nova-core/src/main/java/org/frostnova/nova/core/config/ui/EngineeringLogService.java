package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.FileNamePattern;
import ch.qos.logback.core.spi.AppenderAttachable;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工程日志：读本机那份日志文件的尾巴
 * <p>
 * 「日志」页答的是使用者关心的事（见 {@link org.frostnova.nova.core.timeline.TimelineStore}），
 * 这一份答的是程序自己干了什么——排障时要看的是后者，而它此前只能到服务器上去 tail。
 * 远程部署的人手里往往只有这个控制台。
 *
 * <h2>路径从 logback 那里问，不写死也不另配一个键</h2>
 * 日志落在哪由 {@code logback.xml} 与 {@link org.frostnova.nova.core.config.LogHomeDefiner} 共同决定，
 * 后者还会在从工作树里跑起来时改写落点。在这里再写一份路径推导，
 * 两份分叉的那天表现是<b>这一页安静地显示另一个文件的内容</b>——它照样有行、照样滚，
 * 只是与这台机器此刻在写的那一份无关。
 * <p>
 * 取的是<b>根 logger 上挂着的那个文件 appender</b>，不按 appender 的名字认：
 * 名字是随时会改的东西，按名字找的判据在改名那天静默失效。
 * 事件日志（{@code EventDebug}）与网络日志（{@code NetworkDebug}）挂在各自的 logger 上、
 * 不在根上，因此天然不在射程里——那两份里逐条堆着观众昵称与 uid，
 * <b>它们不该因为「顺手」而被端到浏览器里</b>。
 *
 * <h2>只读尾巴</h2>
 * 单日日志实测 2 MiB 上下，开着调试开关时到 75 MiB。整份读进内存再切尾巴，
 * 代价会随那个开关翻十倍，而这一页要的从来只是最后那几百行。
 */
@Slf4j
@Service
public class EngineeringLogService {
    /**
     * 没点名要多少行时给多少
     */
    public static final int DEFAULT_LIMIT = 300;

    /**
     * 一次最多给多少行
     */
    public static final int MAX_LIMIT = 2000;

    /**
     * 定位到某一分钟时，前后各给多少行
     * <p>
     * 点这一下的人要的是「那一刻前后发生了什么」，几十行装得下一次启动或一次推送的全过程；
     * 再多就得往回滚了，而那时他还不如直接看整份尾巴。
     */
    public static final int DEFAULT_SPAN = 60;

    /**
     * 前后各最多给多少行
     */
    public static final int MAX_SPAN = 500;

    /**
     * 打码后留下的痕迹
     * <p>
     * 与设置页遮机密项用的是<b>同一串</b>（见 {@link SensitiveFields#MASK}）：
     * 两处各写一串的话，使用者得先学会「界面上这两种星号不是一回事」才看得懂。
     */
    public static final String MASK = SensitiveFields.MASK;

    /**
     * 从文件尾部最多读多少字节
     * <p>
     * 与行数上限是两道各自独立的闸：行数管「给多少」，字节管「读多少」。
     * 只设行数的话，一行长得离谱的日志（整个响应体被打印出来那种）照样能把内存吃掉。
     */
    private static final int MAX_BYTES = 1024 * 1024;

    /**
     * 只看问题档时往回扫，最多翻这么多字节
     * <p>
     * 与 {@link #MAX_BYTES} 是两道各自的闸：那道管「尾读读多少」，这道管「往回翻翻多远」。
     * <b>这道闸要盖得住一整天</b>：开着调试开关写一整天，一天的日志可以到两百 MB，
     * 盖不住时散在上午的错误照旧会被当成「这一天没出过错」。定这个数之前回扫是每读一块
     * 就把已收的行从头重排一遍，翻得多就等多久；做成一趟线性扫之后，翻一整天与翻一角
     * 是同一笔开销的量级，这才放得开。撞到上限还没凑够条数时，
     * 由 {@link Scan#scannedTo()} 说清扫到几点几分。
     * <p>
     * 取 256 MiB 而不是刚好两百：上限等于那一天的大小时余量是零，日子跑热一点，
     * 早上那一段就又落回窗口外去了。实测翻满 256 MiB 一次一秒出头，多留这一截不心疼。
     */
    public static final int MAX_SCAN_BYTES = 256 * 1024 * 1024;

    /**
     * 往回扫时一次读多少
     * <p>
     * 定成 2 的幂，而日志行宽是个会变的东西：两者互不整除时读块边界永远落在某一行中间，
     * 「半行被读块切开」这条路在每一次读块上都走一遍。
     */
    private static final int SCAN_BLOCK = 256 * 1024;

    /**
     * 一行日志的行首级别，与 {@code logback.xml} 里那个 pattern 对应
     * <p>
     * 与界面那一侧的行首判定同形（{@code log-model.js} 的 {@code HEAD}）：两头各自认的话，
     * 同一份日志在服务端与浏览器上会被切成两种段，而两边都报绿。
     */
    private static final Pattern LEVEL_HEAD = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\b");

    /**
     * 值属于凭据、整条打掉的请求头
     * <p>
     * 这几个头的值<b>整条都是凭据</b>，而它落进日志的形态无法预先知道
     * （{@code SESSDATA=…; bili_jct=…} 是两截，Bearer 是一截）。
     * 因此这一档不逐项拆，直接把该头后面的部分整段换掉——多抹掉半行的代价，
     * 比漏出半个 Cookie 小得多。
     */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b(set-cookie|cookie|proxy-authorization|authorization)(\\s*[:=]\\s*)\\S.*");

    /**
     * 一行日志的行首时刻，与 {@code logback.xml} 里那个 pattern 对应
     * <p>
     * 认不出来只意味着这一行没有自己的时刻（堆栈那几行就是），不影响它显示——
     * 换了 pattern 之后表现是「看这一刻」定位不准，而每一行都还在。
     * 界面那一侧另有一份同形的判法（{@code log-model.js} 的 HEAD），它认的是级别，这里认的是时刻。
     */
    private static final Pattern HEAD = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} (\\d{2}):(\\d{2}):(\\d{2})\\.\\d{3}\\b");

    /**
     * {@code 键=值}、{@code 键: 值}，以及它们带引号的那一形态（{@code "键": "值"}）
     * <p>
     * 引号进分隔符那一段而不是被当成键或值的一部分：异常的 message 里裹着一段 JSON 是常见形状，
     * 而<b>「键紧跟着一个引号」在只认冒号的判法眼里与「没有这个键」长得一样</b>——
     * 那一路会安静地把整段 JSON 原样端出去。
     * <p>
     * 值不吃 {@code ? & /}：吃了的话，一整条地址会被当成 {@code https} 这一个键的值整段吞掉，
     * 而藏在它查询串里的 {@code csrf=…} 就再也轮不到自己被查一遍。
     * <p>
     * 键名那段写成「最多这么多字符」，再配一句「前一个字符不是字母」，两条合起来才不回溯。
     * 一长串字母数字中间没有分隔符时（调试日志里的图片编码就是这种形状），每个起头都想当一回
     * 键名：接在字母后头的起头看一眼前一个字符就走开；拦不住的那些（数字后头那个起头）各自
     * 最多赔上这么长一段才发现找不到分隔符，于是整行是线性的，几万字一行一眨眼就过。
     * {@code _token=} 这类以非字母开头的键不受影响：要遮的本来就是它后半截那个键，
     * 前头那一个下划线不是字母，那个键照样起得来。
     * <p>
     * 键名比 255 个字符还长的形状不在这份日志里出现。真出现的话它那一对连分隔符都够不着，
     * 于是整对当它不是键值对、不遮——这一档宁可放着，也不为它把整行重新做成平方级。
     */
    private static final Pattern PAIR = Pattern.compile(
            "(?<![A-Za-z])([A-Za-z][A-Za-z0-9_.\\-]{0,255}+)(\"?\\s*[:=]\\s*\"?)([^\\s,;\"'&?]+)");

    /**
     * 名字判不出、但值确实是凭据的那几个
     * <p>
     * {@link SensitiveFields} 按名字判的是<b>配置项</b>，它认得 password/token/secret/credential；
     * 而日志里还会出现另一族名字——浏览器与直播平台的 Cookie 名。
     * 两族合起来才盖得住这一路，因此这里在那条规则之外补一张表，而不是把那条规则改宽：
     * 改宽了会连带影响设置页遮不遮，而那是另一件事。
     */
    private static final Set<String> CREDENTIAL_NAMES = Set.of(
            "sessdata", "bili_jct", "csrf", "qrcode_key", "access_key",
            "jsessionid", "sessionid", "refresh_token", "auth");

    /**
     * 这台机器此刻在写的那份日志文件
     * @return 文件路径；日志没有落到文件上时为空
     */
    public Optional<Path> file() {
        return appender().flatMap(appender -> {
            // 按天滚动时没有 <file> 元素，此刻在写哪一份得问滚动策略
            if (appender instanceof RollingFileAppender<?> rolling
                    && rolling.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) {
                String active = policy.getActiveFileName();
                if (active != null && !active.isBlank()) {
                    return Optional.of(Path.of(active));
                }
            }
            return appender.getFile() == null ? Optional.empty() : Optional.of(Path.of(appender.getFile()));
        });
    }

    /**
     * 某一天那份日志文件
     * <p>
     * 「翻别的日子」要的那一份。落点由 {@code logback.xml} 里那条 {@code fileNamePattern} 定，
     * 这里<b>拿它自己去算</b>而不是在这边再拼一遍路径：拼一遍的那份迟早与模板分叉，
     * 而分叉的表现是这一页安静地显示另一个文件的内容——它照样有行、照样能滚。
     * <p>
     * 今天那一份仍走 {@link #file()}：配了 {@code <file>} 元素时，此刻在写的那一份
     * 与模板算出来的不是同一个名字。
     * @param day 哪一天，为空即今天
     * @return 文件路径；算不出来时为空。<b>文件在不在不由这里答</b>——那一天没有记录是个正当状态
     */
    public Optional<Path> file(LocalDate day) {
        if (day == null || day.equals(LocalDate.now())) {
            return file();
        }
        return fileOn(pattern().orElse(null), day);
    }

    /**
     * 按 logback 的落点模板算出某一天那份文件
     * <p>
     * 交给 logback 自己的 {@link FileNamePattern} 去算，不在这边解析 {@code %d{...}}：
     * 模板里那两处日期（月份目录与文件名）用的是同一个时刻，而它们的格式各写各的。
     * <p>
     * ⚠️ 模板带压缩后缀（{@code .gz}）时算出来的是压缩包的路径，这一页读不了它——
     * 表现是那一天显示「没有记录」。现行 {@code logback.xml} 不压缩，改成压缩的那天要连这里一起改。
     * @param pattern 落点模板，即 {@code fileNamePattern} 的原文
     * @param day 哪一天
     * @return 文件路径；模板为空或算不出来时为空
     */
    static Optional<Path> fileOn(String pattern, LocalDate day) {
        if (pattern == null || pattern.isBlank() || day == null) {
            return Optional.empty();
        }

        try {
            FileNamePattern compiled = new FileNamePattern(pattern, new LoggerContext());
            String name = compiled.convert(
                    Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(Path.of(name));
        } catch (RuntimeException e) {
            log.warn("按落点模板 {} 算 {} 那一天的日志文件失败: {}", pattern, day, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 根 logger 上那个文件 appender 的落点模板
     * <p>
     * 不按天滚动时<b>没有模板</b>：那样的部署只有一份日志文件，「翻别的日子」这件事
     * 在它身上根本不成立。此时回空，让调用方说出「翻不了」——把今天那一份端出去
     * 当成别的日子，屏幕上会是一份内容与日期对不上的日志。
     */
    private Optional<String> pattern() {
        return appender()
                .filter(appender -> appender instanceof RollingFileAppender<?> rolling
                        && rolling.getRollingPolicy() instanceof TimeBasedRollingPolicy<?>)
                .map(appender -> ((TimeBasedRollingPolicy<?>) ((RollingFileAppender<?>) appender)
                        .getRollingPolicy()).getFileNamePattern());
    }

    /**
     * 根 logger 上写文件的那个 appender
     */
    private Optional<FileAppender<?>> appender() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            // 换了别的日志实现（或者根本没装）时说不出路径。这不是错，但也不许悄悄回一份空的尾巴
            return Optional.empty();
        }
        return appenderIn(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders());
    }

    /**
     * 在一串 appender 里找出写文件的那一个，含套在异步 appender 里的
     */
    private Optional<FileAppender<?>> appenderIn(Iterator<? extends Appender<?>> appenders) {
        while (appenders.hasNext()) {
            Appender<?> appender = appenders.next();

            // 文件 appender 通常套在 AsyncAppender 里，不往里看就一个也找不到
            if (appender instanceof AppenderAttachable<?> nested) {
                Optional<FileAppender<?>> inner = appenderIn(nested.iteratorForAppenders());
                if (inner.isPresent()) {
                    return inner;
                }
            }

            if (appender instanceof FileAppender<?> plain) {
                return Optional.of(plain);
            }
        }
        return Optional.empty();
    }

    /**
     * 本次真正生效的行数上限
     * <p>
     * 单列出来是为了让接口能<b>如实报出这个数</b>：要了 100000 行只给到 2000 时，
     * 界面上那句「共 N 行」若照着请求的数写，看起来就像日志只有这么多。
     * @param limit 要多少行，{@code <= 0} 表示没点名
     * @return 生效的行数
     */
    public int effectiveLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * 读文件尾部若干行，逐行打码
     * <p>
     * 打码做在这一层而不是各个调用点：做在调用点的话，下一个想读这份文件的人
     * 得先知道有这回事——而<b>他忘了这件事的表现是功能一切正常</b>。
     * @param file 日志文件
     * @param limit 最多几行
     * @return 尾巴，文件还没建出来时是空表
     * @throws IOException 读不了时抛出，由调用方明说；<b>不许当成「没有日志」</b>
     */
    public Tail tail(Path file, int limit) throws IOException {
        int want = effectiveLimit(limit);
        if (file == null || !Files.isRegularFile(file)) {
            // 刚起来还没写第一行时文件确实不存在，这是正常状态，不是故障
            return new Tail(List.of(), false, 0L, 0L);
        }

        byte[] bytes;
        int length;
        long size;
        long from;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            size = channel.size();
            from = Math.max(0L, size - MAX_BYTES);
            channel.position(from);

            ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读满为止
            }
            bytes = buffer.array();
            length = buffer.position();
        }

        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        // 末尾那一段总是要去掉：文件以换行结尾时它是个空串，不以换行结尾时它是<b>写了一半的行</b>。
        // 半行不给出去，是因为「跟随最新」随后会把整行再送来一次——两次之间那半行里的凭据
        // 各自都躲得过按整行判的打码
        if (!lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        boolean more = from > 0;
        // 从文件中间切进来时，第一行是半截——它还可能被从一个多字节字符的中间切开
        if (more && !lines.isEmpty()) {
            lines.remove(0);
        }
        if (lines.size() > want) {
            lines = lines.subList(lines.size() - want, lines.size());
            more = true;
        }

        return new Tail(lines.stream().map(EngineeringLogService::mask).toList(), more, size,
                lastLineEnd(bytes, length, from));
    }

    /**
     * 缓冲区里最后一个完整行的末尾落在文件的哪个字节上
     * <p>
     * 「跟随最新」下一次就从这里接着读。取行末而不是文件末，是因为文件末可能停在一行的中间。
     */
    private static long lastLineEnd(byte[] bytes, int length, long from) {
        for (int i = length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return from + i + 1;
            }
        }
        return from;
    }

    /**
     * 只看问题档时，从这一天整份日志里往回找
     * <p>
     * 尾读只吃最后 {@link #MAX_BYTES}，而一天的日志可以有一两百 MB：散在一天里的错误
     * 落在那一段之外时，页面会说「没有」——看的人读成这天没出过错，那天其实有几十条。
     * <p>
     * <b>一条日志连同它下面的堆栈算一条</b>：凑条数时按段算，不按行算。
     * <p>
     * 行横跨读块时把上一块开头那半行接到下一块末尾重装：读块宽与行宽互不整除时，
     * 边界永远落在某一行中间，不重装的话少回来的正好是那几条被切开的。
     * <b>重装按字节做，接好之后才解码</b>：先各块各解一遍再拼字符串的话，被切开那个
     * 多字节字符两半各自解成替代字符，中文、表情跨了读块拼回去也好不了，一屏乱码。
     * 只对最终返回的那几段打码，不对扫过的每一行打码——扫过就丢的那几行
     * 从来不会送到浏览器上。
     *
     * @param file 日志文件
     * @param limit 最多几段
     * @param levels 只要哪几档，取 {@code error}/{@code warn}/{@code info}/{@code debug}
     * @return 找到的段，最旧的在前；文件不在时是空表
     * @throws IOException 读不了时抛出，由调用方明说；<b>不许当成「没有日志」</b>
     */
    public Scan scan(Path file, int limit, Set<String> levels) throws IOException {
        int want = effectiveLimit(limit);
        if (file == null || !Files.isRegularFile(file) || levels == null || levels.isEmpty()) {
            return new Scan(List.of(), false, 0L, 0L, null);
        }

        long size = Files.size(file);
        long floor = Math.max(0L, size - (long) MAX_SCAN_BYTES);

        // 从文件尾一块块往回读；边读边组段，最新的在前喂进去。
        // 不再每读一块就把已收的行从头重排一遍——那样一趟要按行数×块数走，
        // 一天的错误凑不满一页时每趟都要扫满整个窗，行数越多越等不起
        Segmenter segments = new Segmenter(levels, want);
        byte[] frag = new byte[0];
        boolean firstBlock = true;
        long pos = size;
        byte[] tailBytes = new byte[0];
        long tailFrom = size;

        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            while (pos > floor) {
                long start = Math.max(floor, pos - SCAN_BLOCK);
                byte[] raw = readBlock(channel, start, (int) (pos - start));
                if (firstBlock) {
                    tailBytes = raw;
                    tailFrom = start;
                }
                pos = start;

                if (firstBlock) {
                    // 文件末那一段是写了一半的行，与 tail() 同形地整段丢掉：
                    // 半行不给出去，「跟随最新」随后会把整行再送来一次
                    int lastNl = lastIndexOf(raw, (byte) '\n');
                    if (lastNl < 0) {
                        // 整块都落在那半行里，还没撞到它的换行，整块丢掉继续往回找
                        continue;
                    }
                    raw = Arrays.copyOf(raw, lastNl + 1);
                    firstBlock = false;
                }

                // 接上：raw 的末尾是被读块切开那行的前半截，frag 是它落在新一块里的后半截，
                // 按 raw 在前拼起来才是一整行。拼的是字节不是字符串——被切开那个多字节字符
                // 的两半只有回到同一段字节里才认得出它是什么字
                byte[] text = join(raw, frag);
                int firstNl = indexOf(text, (byte) '\n');
                if (firstNl < 0) {
                    frag = text;
                    continue;
                }
                frag = Arrays.copyOfRange(text, 0, firstNl);
                segments.feedNewestFirst(text, firstNl + 1);

                if (segments.filled()) {
                    break;
                }
            }
        }

        // 扫到文件开头时，手里那半截不是半行：它就是这份日志的第一行，得算进来。
        // 只有撞了回扫上限才丢它——那时更旧的半截在上限之外，压根没读过
        if (pos <= 0 && frag.length > 0) {
            segments.add(decodeLine(frag, 0, frag.length));
        }

        List<List<String>> found = segments.segments();
        boolean hitCap = floor > 0;
        boolean filled = found.size() >= want;
        boolean exhausted = pos <= floor;

        List<String> out = new ArrayList<>();
        List<List<String>> shown = found.size() > want ? found.subList(0, want) : found;
        for (int i = shown.size() - 1; i >= 0; i--) {
            for (String line : shown.get(i)) {
                out.add(mask(line));
            }
        }

        // 「更早还有」有三种来路：回扫窗外还有、这一趟没读到底、读到底了但比要的多
        boolean more = hitCap || !exhausted || found.size() > want;
        String scannedTo = hitCap && !filled ? segments.oldest() : null;
        return new Scan(out, more, size, lastLineEnd(tailBytes, tailBytes.length, tailFrom), scannedTo);
    }

    private static byte[] readBlock(SeekableByteChannel channel, long start, int count) throws IOException {
        byte[] bytes = new byte[count];
        channel.position(start);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // 读满为止
        }
        return bytes;
    }

    /**
     * 边读边组段：每来一行就认一次，不再每读一块把已收的行从头重排一遍
     * <p>
     * 喂的顺序是<b>最新的在前</b>，与读块的顺序同向。段的级别取头一行的，堆栈跟着它那一行走。
     * 开头那段没有头一行（从文件中间读进来时常见）不认：认不出它属于哪一档，留着就躲得过级别筛。
     * <p>
     * 「扫到几点几分」要的是<b>这一窗里最旧那条认得出时刻的行</b>，而它未必落在命中的段里：
     * 一整天没出过错时每一段都不对档，这个数照旧要报得出来。因此每来一行都顺手认一次时刻，
     * 新的在前喂，最后一个认出来的也就是最旧那个。
     */
    private static final class Segmenter {
        private final Set<String> levels;
        private final int want;
        private final List<List<String>> newestFirst = new ArrayList<>();
        private final List<String> cont = new ArrayList<>();
        private String oldest;

        Segmenter(Set<String> levels, int want) {
            this.levels = levels;
            this.want = want;
        }

        /**
         * 把这一段字节里的完整行按最新的在前喂进来
         * <p>
         * 从 {@code from} 起按换行切，行末那个回车跟着换行一起吃掉。末尾那一段哪怕不是
         * 换行结尾也算完整：它缺的那半截已经按字节接回在它前头了，只留最靠左那半行到下一块去接。
         */
        void feedNewestFirst(byte[] text, int from) {
            List<String> block = new ArrayList<>();
            int lineStart = from;
            for (int i = from; i < text.length; i++) {
                if (text[i] == '\n') {
                    block.add(decodeLine(text, lineStart, i));
                    lineStart = i + 1;
                }
            }
            if (lineStart < text.length) {
                block.add(decodeLine(text, lineStart, text.length));
            }
            for (int i = block.size() - 1; i >= 0; i--) {
                add(block.get(i));
            }
        }

        void add(String line) {
            LocalTime at = timeOf(line);
            if (at != null) {
                oldest = format(at);
            }
            Matcher head = LEVEL_HEAD.matcher(line);
            if (head.find()) {
                List<String> segment = new ArrayList<>();
                segment.add(line);
                for (int i = cont.size() - 1; i >= 0; i--) {
                    segment.add(cont.get(i));
                }
                cont.clear();
                if (levels.contains(levelName(head.group(1)))) {
                    newestFirst.add(segment);
                }
            } else {
                cont.add(line);
            }
        }

        /** 凑够要的条数了，更旧的不必再读 */
        boolean filled() {
            return newestFirst.size() >= want;
        }

        /** 命中的段，最新的在前；每段内部是文件顺序 */
        List<List<String>> segments() {
            return newestFirst;
        }

        /** 这一窗里最旧那条认得出时刻的行的时刻；一行都认不出时为 {@code null} */
        String oldest() {
            return oldest;
        }
    }

    /**
     * 这几字节是一行日志的原文；行末那个回车去掉——换行是 CRLF 时它跟着换行一起当换行看
     */
    private static String decodeLine(byte[] text, int from, int to) {
        int end = to;
        if (end > from && text[end - 1] == '\r') {
            end--;
        }
        return new String(text, from, end - from, StandardCharsets.UTF_8);
    }

    /**
     * 两截字节按序拼起来：被读块切开的那个多字节字符就落在这条缝上，只有回到同一段字节里才认得出
     */
    private static byte[] join(byte[] older, byte[] newer) {
        if (older.length == 0) {
            return newer;
        }
        if (newer.length == 0) {
            return older;
        }
        byte[] both = Arrays.copyOf(older, older.length + newer.length);
        System.arraycopy(newer, 0, both, older.length, newer.length);
        return both;
    }

    private static int indexOf(byte[] bytes, byte needle) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] bytes, byte needle) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 行首级别归到药丸那四档里：{@code TRACE} 并进调试——药丸只有四个，
     * 多出来的一档没有开关管得着它
     */
    private static String levelName(String level) {
        return "TRACE".equals(level) ? "debug" : level.toLowerCase(Locale.ROOT);
    }

    /**
     * 那个位置之后新写进去的行，供「跟随最新」用
     * <p>
     * 只取新写的那几行，不是每 3 秒把几百行重取一遍：后者在一台开着控制台过夜的机器上
     * 是几十兆的无谓流量，而它给出的画面与这一份一模一样。
     * <p>
     * <b>写了一半的行先不给</b>：给了的话，剩下的半行随后会作为另一行出现——
     * 两个半行拼起来才是一句话，而按整行判的打码在任何一半上都认不出那是个凭据。
     * @param file 日志文件
     * @param offset 上一次读到哪个字节
     * @return 新写的行；文件被换掉或落下太多时 {@code reset} 为真，此时调用方应重取整段尾巴
     * @throws IOException 读不了时抛出，由调用方明说
     */
    public Appended since(Path file, long offset) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return new Appended(List.of(), 0L, false);
        }

        long size = Files.size(file);
        // 位置比文件还长：这已经不是刚才那一份了（滚动、清空、换了一台机器的日志）。
        // 不说出来的话，屏幕上会从头再长出一整份日志，而看的人以为那是刚发生的事
        if (offset < 0 || offset > size) {
            return new Appended(List.of(), size, true);
        }
        // 落下太多时同样交回去重取尾巴：这一路的上限与尾读是同一道闸
        if (size - offset > MAX_BYTES) {
            return new Appended(List.of(), size, true);
        }
        if (size == offset) {
            return new Appended(List.of(), offset, false);
        }

        byte[] bytes = new byte[(int) (size - offset)];
        int length;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读满为止
            }
            length = buffer.position();
        }

        long end = lastLineEnd(bytes, length, offset);
        if (end == offset) {
            // 一个换行都没有：新写进去的只是半行，等它写完
            return new Appended(List.of(), offset, false);
        }

        String text = new String(bytes, 0, (int) (end - offset), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        lines.remove(lines.size() - 1);

        return new Appended(lines.stream().map(EngineeringLogService::mask).toList(), end, false);
    }

    /**
     * 定位到某一分钟，给出那一行与它前后各若干行
     * <p>
     * 日志页上一条事件旁边那个「在工程日志里看这一刻 →」要的就是这一份。
     * 定位做在服务端而不是把整天的日志端到浏览器里再找：那一份开着调试开关时有几十兆，
     * 而这一次要看的只是那前后几十行。
     * <p>
     * <b>那一分钟没有记录时不许假装定位到了</b>：取最近的一行，并由 {@link Window#exact()}
     * 说明这不是点名的那一刻。假装定位的表现是屏幕上高亮着一行毫不相干的日志，
     * 而页面上那句「已定位到 20:07」照常显示。
     * @param file 日志文件
     * @param minute 点名的那一分钟
     * @param span 前后各给多少行
     * @return 那一段；文件不在或一行都没有时是空窗
     * @throws IOException 读不了时抛出，由调用方明说
     */
    public Window around(Path file, LocalTime minute, int span) throws IOException {
        if (file == null || !Files.isRegularFile(file) || minute == null) {
            return new Window(List.of(), -1, false, null);
        }

        int want = span <= 0 ? DEFAULT_SPAN : Math.min(span, MAX_SPAN);
        Anchor anchor = locate(file, minute.truncatedTo(ChronoUnit.MINUTES));
        if (anchor.index() < 0) {
            return new Window(List.of(), -1, false, null);
        }

        int from = Math.max(0, anchor.index() - want);
        int to = anchor.index() + want;
        List<String> lines = new ArrayList<>();
        int index = 0;
        try (BufferedReader reader = reader(file)) {
            for (String line = reader.readLine(); line != null && index <= to; line = reader.readLine()) {
                if (index >= from) {
                    lines.add(mask(line));
                }
                index++;
            }
        }

        return new Window(lines, anchor.index() - from, anchor.exact(), anchor.at());
    }

    /**
     * 点名那一分钟落在第几行
     * <p>
     * 单独走一趟只数行号、不留内容：留内容就得把定位点之前的整段都攒在内存里，
     * 而定位点可能在一份几十兆的文件的末尾。
     */
    private Anchor locate(Path file, LocalTime minute) throws IOException {
        int index = 0;
        int best = -1;
        long nearest = Long.MAX_VALUE;
        LocalTime bestAt = null;

        try (BufferedReader reader = reader(file)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                LocalTime at = timeOf(line);
                if (at != null) {
                    if (at.truncatedTo(ChronoUnit.MINUTES).equals(minute)) {
                        // 那一分钟里的第一行就是要跳过去的位置
                        return new Anchor(index, true, format(at));
                    }

                    long distance = Math.abs(Duration.between(minute, at).getSeconds());
                    if (distance < nearest) {
                        nearest = distance;
                        best = index;
                        bestAt = at;
                    } else if (at.isAfter(minute)) {
                        // 行是按时间写下去的，越往后离点名那一刻只会越远，不必读完整份文件
                        break;
                    }
                }
                index++;
            }
        }

        if (best < 0 && index > 0) {
            // 一行都认不出时刻（整份都是堆栈）时仍给出内容，只是说不出定位到了哪一刻
            return new Anchor(0, false, null);
        }
        return new Anchor(best, false, bestAt == null ? null : format(bestAt));
    }

    /**
     * 一行日志的时刻，行首认不出时为 {@code null}
     */
    private static LocalTime timeOf(String line) {
        Matcher found = HEAD.matcher(line);
        if (!found.find()) {
            return null;
        }
        try {
            return LocalTime.of(Integer.parseInt(found.group(1)), Integer.parseInt(found.group(2)),
                    Integer.parseInt(found.group(3)));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static String format(LocalTime at) {
        return String.format("%02d:%02d", at.getHour(), at.getMinute());
    }

    /**
     * 逐行读一份日志文件
     * <p>
     * 坏字节换成替代字符而不是抛出：这份文件正被另一头写着，读到一个被切开的多字节字符
     * 是常事，而那一刻整页都读不出来的代价，比一行里多一个问号大得多。
     */
    private static BufferedReader reader(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder));
    }

    /**
     * 定位结果：落在第几行、是不是点名那一分钟、那一行是几点
     */
    private record Anchor(int index, boolean exact, String at) {
    }

    /**
     * 把一行里的凭据换成掩码
     * <p>
     * 这是<b>第二道防线</b>：第一道是不把凭据写进日志。日志里出现凭据从来不是有人故意写的，
     * 而是某个异常把整串地址、整个请求头裹进了 {@code message}——那种行不报错、不影响功能，
     * 只是在控制台上多显示了一串，<b>而看控制台的那一刻人往往正在把屏幕给别人看</b>。
     * @param line 原文，可为 {@code null}
     * @return 打码后的行；{@code null} 原样返回
     */
    public static String mask(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }

        String masked = HEADER.matcher(line).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + match.group(2) + MASK));

        return PAIR.matcher(masked).replaceAll(match -> Matcher.quoteReplacement(
                secret(match.group(1))
                        ? match.group(1) + match.group(2) + MASK
                        : match.group()));
    }

    /**
     * 这个键的值算不算凭据
     */
    private static boolean secret(String key) {
        String leaf = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CREDENTIAL_NAMES.contains(leaf) || SensitiveFields.isSensitive(key, null);
    }

    /**
     * 文件尾巴
     *
     * @param lines 已打码的行，最旧的在前，与文件里的顺序一致
     * @param more 上面还有没给出来的行。<b>不说的话，「这就是全部」与「只给了尾巴」在屏幕上长得一样</b>
     * @param size 文件字节数
     * @param offset 最后一个完整行的末尾落在哪个字节上，「跟随最新」下一次从这里接着读
     */
    public record Tail(List<String> lines, boolean more, long size, long offset) {
    }

    /**
     * 只看问题档时整天里找出来的那几段
     *
     * @param lines 已打码的行，最旧的在前，段与段按时间正序
     * @param more 更早还有没给出来的段。<b>不说的话，「找全了」与「只扫了一截」在屏幕上长得一样</b>
     * @param size 文件字节数
     * @param offset 最后一个完整行的末尾落在哪个字节上
     * @param scannedTo 扫到几点几分（{@code HH:mm}）。只在撞了回扫上限还没凑够条数时给；
     *                 为空表示这一天找全了
     */
    public record Scan(List<String> lines, boolean more, long size, long offset, String scannedTo) {
    }

    /**
     * 上一次读过之后新写进去的那几行
     *
     * @param lines 已打码的行，最旧的在前
     * @param offset 这一次读到哪个字节，下一次从这里接着读
     * @param reset 文件已经不是上一次那一份了（滚动、清空），调用方应重取整段尾巴
     */
    public record Appended(List<String> lines, long offset, boolean reset) {
    }

    /**
     * 某一刻前后那一段
     *
     * @param lines 已打码的行，最旧的在前
     * @param highlight 点名那一刻落在 {@code lines} 的第几行，没有可高亮的行时为 {@code -1}
     * @param exact 是不是真定位到了点名的那一分钟。<b>假不了</b>：假装定位到的表现是
     *              屏幕上高亮着一行毫不相干的日志，而「已定位到」那句话照常显示
     * @param nearest 高亮那一行是几点（{@code HH:mm}），认不出时刻时为空
     */
    public record Window(List<String> lines, int highlight, boolean exact, String nearest) {
    }
}
