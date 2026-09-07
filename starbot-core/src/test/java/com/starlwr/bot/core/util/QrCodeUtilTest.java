package com.starlwr.bot.core.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二维码生成
 *
 * <h2>为什么会有这组判据</h2>
 * 登录扫码（账号扫码登录、控制台的两步验证绑定）全走这里。它出错的形态不是报错，
 * 是<b>扫不出来</b>或者<b>扫出来是别的东西</b>——使用者只会看到「一直不成功」。
 * <p>
 * 因此这里的主判据是<b>扫回来</b>：生成的码用解码器读一遍，要读回原文。
 * 只断言「生成了一张图」的判据，在码画错的那天照样是绿的。
 */
@DisplayName("二维码生成")
class QrCodeUtilTest {
    private static final String CONTENT = "https://example.invalid/login?token=abc123";

    /** 控制台打印用的四种半格字符，除了它们只应有空格 */
    private static final Set<Character> BLOCKS = Set.of('█', '▀', '▄', ' ');

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 把矩阵按现码的方式转成图再扫回来，量的是「这张码能不能用」
     */
    private static String scan(BitMatrix matrix) throws Exception {
        BufferedImage image = com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new QRCodeReader().decode(bitmap).getText();
    }

    @Nested
    @DisplayName("生成矩阵")
    class Generating {
        @Test
        @DisplayName("生成的码要能扫回原文")
        void generatedCodeScansBackToTheSameText() throws Exception {
            Optional<BitMatrix> matrix = QrCodeUtil.generateQrCode(CONTENT, 200);

            assertTrue(matrix.isPresent());
            assertEquals(CONTENT, scan(matrix.get()), "扫不回原文等于这张码是废的");
        }

        @Test
        @DisplayName("中文内容也要能扫回原文")
        void handlesNonAsciiContent() throws Exception {
            String chinese = "登录确认：星";

            Optional<BitMatrix> matrix = QrCodeUtil.generateQrCode(chinese, 200);

            assertTrue(matrix.isPresent());
            assertEquals(chinese, scan(matrix.get()), "编码指定的是 utf-8, 改掉会让中文内容扫出乱码");
        }

        @Test
        @DisplayName("矩阵是正方形，边长就是要的那个尺寸")
        void matrixIsSquareOfRequestedSize() {
            BitMatrix matrix = QrCodeUtil.generateQrCode(CONTENT, 200).orElseThrow();

            assertEquals(200, matrix.getWidth());
            assertEquals(200, matrix.getHeight());
        }

        @Test
        @DisplayName("尺寸必须是偶数，奇数当场报错")
        void oddSizeIsRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> QrCodeUtil.generateQrCode(CONTENT, 201));
            assertTrue(e.getMessage().contains("偶数"), "报错要说清是尺寸的问题: " + e.getMessage());
        }

        /**
         * 尺寸检查在最前面，先于内容——奇数尺寸配上一个本来也会失败的内容，
         * 报的仍是尺寸的错。改写时若把检查挪到后面，这里会红。
         */
        @Test
        @DisplayName("尺寸不对时先报尺寸，不管内容")
        void sizeIsCheckedBeforeContent() {
            assertThrows(IllegalArgumentException.class, () -> QrCodeUtil.generateQrCode(null, 201));
            assertThrows(IllegalArgumentException.class, () -> QrCodeUtil.generateQrCode("", 201));
        }
    }

    @Nested
    @DisplayName("生成不出来的时候")
    class Failures {
        @Test
        @DisplayName("内容为空给空，不抛异常")
        void emptyContentYieldsEmpty() {
            assertTrue(QrCodeUtil.generateQrCode("", 200).isEmpty());
        }

        @Test
        @DisplayName("内容为 null 给空，不抛异常")
        void nullContentYieldsEmpty() {
            assertTrue(QrCodeUtil.generateQrCode(null, 200).isEmpty(),
                    "扫码那一路把「生成不出来」当成一种结果处理, 不是当场炸掉");
        }

        @Test
        @DisplayName("内容长到装不下时给空")
        void oversizedContentYieldsEmpty() {
            String tooLong = "x".repeat(5000);

            assertTrue(QrCodeUtil.generateQrCode(tooLong, 200).isEmpty());
        }

        /**
         * 要的尺寸比这段内容需要的最小边长还小时，得到的矩阵<b>比要的大</b>——
         * 编码器不会缩，只会顶到最小边长。调用方若拿自己要的那个尺寸去遍历矩阵，
         * 会只读到左上角一块。
         */
        @Test
        @DisplayName("尺寸小于最小边长时，给回来的矩阵比要的大")
        void tooSmallSizeGrowsToTheMinimum() {
            BitMatrix matrix = QrCodeUtil.generateQrCode(CONTENT, 2).orElseThrow();

            assertTrue(matrix.getWidth() > 2,
                    "编码器顶到了最小边长, 实际给回 " + matrix.getWidth() + " 而不是 2");
        }
    }

    @Nested
    @DisplayName("转成图片")
    class AsImage {
        @Test
        @DisplayName("给出的是能解码的 PNG，尺寸与要的一致")
        void base64IsAPngOfRequestedSize() throws IOException {
            String base64 = QrCodeUtil.generateQrCodeAndGetBase64(CONTENT, 200).orElseThrow();

            byte[] bytes = Base64.getDecoder().decode(base64);
            assertEquals((byte) 0x89, bytes[0], "PNG 的头一个字节");
            assertEquals('P', (char) bytes[1]);

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertNotNull(image, "解不出图说明写出来的不是 PNG");
            assertEquals(200, image.getWidth());
            assertEquals(200, image.getHeight());
        }

        @Test
        @DisplayName("图片扫回来还是原文")
        void imageScansBackToTheSameText() throws Exception {
            String base64 = QrCodeUtil.generateQrCodeAndGetBase64(CONTENT, 200).orElseThrow();

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));

            assertEquals(CONTENT, new QRCodeReader().decode(bitmap).getText());
        }

        @Test
        @DisplayName("生成不出来时给空")
        void yieldsEmptyWhenGenerationFails() {
            assertTrue(QrCodeUtil.generateQrCodeAndGetBase64("", 200).isEmpty());
        }
    }

    @Nested
    @DisplayName("打印到控制台")
    class Printing {
        private List<String> printedLines(String content, int size) {
            Logger logger = (Logger) LoggerFactory.getLogger(QrCodeUtil.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                assertDoesNotThrow(() -> QrCodeUtil.generateQrCodeAndPrint(content, size));

                return appender.list.stream()
                        .filter(event -> event.getLevel() == Level.INFO)
                        .map(ILoggingEvent::getFormattedMessage)
                        .collect(Collectors.toList());
            } finally {
                logger.detachAppender(appender);
            }
        }

        /**
         * 一行画两排点（上半格／下半格），所以行数是边长的一半。
         * 画成一行一排的话，控制台里的码会被拉成两倍高而扫不出来。
         */
        @Test
        @DisplayName("一行画两排点，行数是边长的一半")
        void printsHalfAsManyLinesAsRows() {
            List<String> lines = printedLines(CONTENT, 60);

            assertEquals(30, lines.size());
        }

        @Test
        @DisplayName("每行的字符数就是边长")
        void eachLineIsAsWideAsTheCode() {
            List<String> lines = printedLines(CONTENT, 60);

            for (String line : lines) {
                assertEquals(60, line.length(), "行宽与边长对不上, 码会被挤扁: " + line);
            }
        }

        @Test
        @DisplayName("只用四种半格字符画")
        void usesOnlyHalfBlockCharacters() {
            for (String line : printedLines(CONTENT, 60)) {
                for (char c : line.toCharArray()) {
                    assertTrue(BLOCKS.contains(c), "出现了画不出码的字符: " + c);
                }
            }
        }

        /**
         * 🔴 阳性对照：画出来的确实是一张有内容的码。
         * <p>
         * 少了它，上面三条在「整片都是空格」时全是绿的。
         */
        @Test
        @DisplayName("画出来的不是一片空白")
        void printsSomethingOtherThanBlanks() {
            String all = String.join("", printedLines(CONTENT, 60));

            assertTrue(all.chars().anyMatch(c -> c == '█'), "整片空白说明矩阵根本没被读到");
        }

        @Test
        @DisplayName("生成不出来时一行都不打")
        void printsNothingWhenGenerationFails() {
            assertEquals(List.of(), printedLines("", 60));
        }

        /**
         * 🔴 这个类的全限定名写死在 {@code logback.xml} 里：控制台的码要用一个不带时间戳、
         * 不带级别前缀的专用输出口打，否则每行前面顶着一截前缀，码就扫不出来了。
         * <p>
         * 于是「谁来打这几行」不是实现细节——搬类、改名、或者把打印挪到另一个类里，
         * 都会让码在控制台里变成扫不出来的样子，而且不报任何错。
         * <p>
         * 从源码目录读而不是从类路径读：{@code install} 那档构建把 {@code logback.xml}
         * 排除在产物之外（它是运行期配置，不该塞进给插件用的库里），类路径上取不到。
         */
        @Test
        @DisplayName("打印必须出自日志配置点名的那个记录器")
        void printingLoggerIsTheOneNamedInLogbackConfig() throws IOException {
            Path config = Path.of("src/main/resources/logback.xml");
            assertTrue(Files.isRegularFile(config), "找不到日志配置: " + config.toAbsolutePath());

            assertTrue(Files.readString(config).contains("\"" + QrCodeUtil.class.getName() + "\""),
                    "日志配置里点名的记录器与这个类对不上, 控制台里的码会带上前缀而扫不出来");
        }
    }
}
