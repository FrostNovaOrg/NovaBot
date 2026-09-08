package com.starlwr.bot.core.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络日志<b>落盘那份文本</b>里不许有凭据明文
 *
 * <h2>为什么不能只测打码函数的返回值</h2>
 * 上一版判据测的是 {@code UrlMasker.mask(...)} 返回什么，那条判据是绿的，
 * 而<b>真正落盘的文本仍然带着明文</b>：把原异常作为末参交给日志框架时，
 * 它会附上栈迹，栈迹首行就是 {@code throwable.toString()}——类名加原始未打码的 message。
 * Spring 的 {@link ResourceAccessException} 正是把完整地址裹在 message 里的。
 * <p>
 * 🔴 <b>尺子量的必须是落盘的那一份。</b>所以这里接一个真的 logback appender，
 * 用与生产同样的 {@code %msg%n%ex} 模式渲染，然后在渲染结果上断言。
 * <p>
 * 这个漏口在生产的前后对照里没暴露，只因为重启后恰好没有 I/O 失败——
 * 而网络抖动恰恰与「开着网络日志排障」同时发生。<b>没撞上不等于没有</b>。
 */
@DisplayName("网络日志落盘文本")
class NetworkLogRenderingTest {
    private static final String JCT = "abc123def456ghi789";

    /**
     * 照生产 logback.xml 里网络日志那个 appender 的模式，末尾的 %ex 就是栈迹
     */
    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- %msg%n%ex";

    /**
     * 把一条 error 事件按生产的模式渲染出来，返回落盘会长的样子
     */
    private String render(String message, Object argument, Throwable throwable) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(sink);
        appender.start();

        ch.qos.logback.classic.Logger logger =
                context.getLogger("NetworkLogRenderingTest-" + System.nanoTime());
        logger.setAdditive(false);
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        logger.error(message, argument, throwable);
        appender.stop();

        return sink.toString(StandardCharsets.UTF_8);
    }

    /**
     * 造一个与生产同形的异常：message 里裹着完整地址，地址里带着凭据
     */
    private static Throwable ioFailure() {
        return new ResourceAccessException(
                "I/O error on GET request for \"https://api.bilibili.com/x/info?csrf=" + JCT + "\": timeout",
                new IOException("connect timed out"));
    }

    /**
     * 🔴 阳性对照：先证明这把尺子确实能看见明文。
     * 少了这一条，下面那条判据在「渲染器根本没输出」时同样是绿的。
     */
    @Test
    @DisplayName("判据自己先能在未打码的写法上抓到明文")
    void theRulerSeesPlaintextWhenNothingIsMasked() {
        String rendered = render("请求失败: {}", "https://x.com/a?csrf=" + JCT, ioFailure());

        assertTrue(rendered.contains(JCT),
                "不打码时都看不见明文，说明这把尺子量错了地方:\n" + rendered);
    }

    @Test
    @DisplayName("按现在的写法渲染，落盘文本里没有凭据明文")
    void renderedTextCarriesNoPlaintext() {
        Throwable failure = ioFailure();
        String rendered = render("请求失败: {}", UrlMasker.mask("https://x.com/a?csrf=" + JCT),
                UrlMasker.sanitize(failure));

        assertFalse(rendered.contains(JCT), "落盘文本里仍有凭据明文:\n" + rendered);
        // 栈迹必须还在——把凭据连同排障价值一起删掉，等于逼人把网络日志整个关掉
        assertTrue(rendered.contains("NetworkLogRenderingTest"),
                "栈帧丢了，异常就没法定位了:\n" + rendered);
        assertTrue(rendered.contains("csrf=***"), "应当看得出这里原本有过一个 csrf 参数:\n" + rendered);
    }

    @Test
    @DisplayName("cause 链上的明文同样不许留下")
    void sanitisesTheWholeCauseChain() {
        Throwable nested = new IllegalStateException("wrapper",
                new ResourceAccessException("failed calling https://x.com/a?csrf=" + JCT));

        String rendered = render("请求失败: {}", "-", UrlMasker.sanitize(nested));

        assertFalse(rendered.contains(JCT), "Caused by 那几行漏了明文:\n" + rendered);
    }
}
