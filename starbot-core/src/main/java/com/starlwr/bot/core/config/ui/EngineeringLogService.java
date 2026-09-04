package com.starlwr.bot.core.config.ui;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.spi.AppenderAttachable;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 「日志」页答的是使用者关心的事（见 {@link com.starlwr.bot.core.timeline.TimelineStore}），
 * 这一份答的是程序自己干了什么——排障时要看的是后者，而它此前只能到服务器上去 tail。
 * 远程部署的人手里往往只有这个控制台。
 *
 * <h2>路径从 logback 那里问，不写死也不另配一个键</h2>
 * 日志落在哪由 {@code logback.xml} 与 {@link com.starlwr.bot.core.config.LogHomeDefiner} 共同决定，
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
     * {@code 键=值}、{@code 键: 值}，以及它们带引号的那一形态（{@code "键": "值"}）
     * <p>
     * 引号进分隔符那一段而不是被当成键或值的一部分：异常的 message 里裹着一段 JSON 是常见形状，
     * 而<b>「键紧跟着一个引号」在只认冒号的判法眼里与「没有这个键」长得一样</b>——
     * 那一路会安静地把整段 JSON 原样端出去。
     * <p>
     * 值不吃 {@code ? & /}：吃了的话，一整条地址会被当成 {@code https} 这一个键的值整段吞掉，
     * 而藏在它查询串里的 {@code csrf=…} 就再也轮不到自己被查一遍。
     */
    private static final Pattern PAIR = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.\\-]*)(\"?\\s*[:=]\\s*\"?)([^\\s,;\"'&?]+)");

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
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            // 换了别的日志实现（或者根本没装）时说不出路径。这不是错，但也不许悄悄回一份空的尾巴
            return Optional.empty();
        }
        return fileOf(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders());
    }

    /**
     * 在一串 appender 里找出写文件的那一个，含套在异步 appender 里的
     */
    private Optional<Path> fileOf(Iterator<? extends Appender<?>> appenders) {
        while (appenders.hasNext()) {
            Appender<?> appender = appenders.next();

            // 文件 appender 通常套在 AsyncAppender 里，不往里看就一个也找不到
            if (appender instanceof AppenderAttachable<?> nested) {
                Optional<Path> inner = fileOf(nested.iteratorForAppenders());
                if (inner.isPresent()) {
                    return inner;
                }
            }

            // 按天滚动时没有 <file> 元素，此刻在写哪一份得问滚动策略
            if (appender instanceof RollingFileAppender<?> rolling
                    && rolling.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) {
                String active = policy.getActiveFileName();
                if (active != null && !active.isBlank()) {
                    return Optional.of(Path.of(active));
                }
            }

            if (appender instanceof FileAppender<?> plain && plain.getFile() != null) {
                return Optional.of(Path.of(plain.getFile()));
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
            return new Tail(List.of(), false, 0L);
        }

        String text;
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
            text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }

        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        // 文件末尾那个换行会切出一个空串，它不是一行
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
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

        return new Tail(lines.stream().map(EngineeringLogService::mask).toList(), more, size);
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
     */
    public record Tail(List<String> lines, boolean more, long size) {
    }
}
