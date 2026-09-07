package com.starlwr.bot.core.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * 二维码工具类
 * <p>
 * 登录扫码与两步验证绑定都从这里出码。生成不出来时一律给空而不是抛：
 * 扫码那一路把「这一次没出上码」当成一种结果处理，重试即可，不该把整条登录流程掀翻。
 * <p>
 * ⚠️ 这个类的<b>全限定名写在 {@code logback.xml} 里</b>（一个不带时间戳、不带级别前缀的专用输出口）。
 * 搬类、改名，或者把 {@link #generateQrCodeAndPrint} 挪到别的类里去打，
 * 控制台里的码就会每行顶着一截前缀而扫不出来，且不报任何错。
 */
@Slf4j
public class QrCodeUtil {
    /** 上半格、下半格、整格：一行画两排点，控制台里的码才不会被拉成两倍高 */
    private static final char BOTH = '█';

    private static final char UPPER = '▀';

    private static final char LOWER = '▄';

    private static final char NEITHER = ' ';

    /**
     * 生成二维码
     * @param content 二维码内容
     * @param size 二维码尺寸
     * @return 二维码矩阵
     */
    public static Optional<BitMatrix> generateQrCode(String content, int size) {
        // 打到控制台时一行画两排点，边长是奇数就会剩下最后一排没地方画
        if (size % 2 != 0) {
            throw new IllegalArgumentException("二维码尺寸必须为偶数");
        }

        try {
            return Optional.of(new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, encodeHints()));
        } catch (Exception e) {
            log.error("生成二维码失败", e);
            return Optional.empty();
        }
    }

    /**
     * 生成二维码并打印到控制台
     * @param content 二维码内容
     * @param size 二维码尺寸
     */
    public static void generateQrCodeAndPrint(String content, int size) {
        generateQrCode(content, size).ifPresent(matrix -> renderToLines(matrix, size).forEach(log::info));
    }

    /**
     * 生成二维码并获取图片 Base64 编码
     * @param content 二维码内容
     * @param size 二维码尺寸
     * @return 二维码图片 Base64 编码
     */
    public static Optional<String> generateQrCodeAndGetBase64(String content, int size) {
        Optional<BitMatrix> matrix = generateQrCode(content, size);
        if (matrix.isEmpty()) {
            return Optional.empty();
        }

        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix.get());

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return Optional.of(Base64.getEncoder().encodeToString(outputStream.toByteArray()));
        } catch (IOException e) {
            log.error("二维码转换为图片失败", e);
        }

        return Optional.empty();
    }

    /**
     * 把矩阵画成一行两排点的字符画
     * <p>
     * 用半格块而不是一格一个方块：终端里的字符普遍高约为宽的两倍，
     * 一行一排画出来的码是竖着拉长的，多数扫码程序读不出来
     */
    private static List<String> renderToLines(BitMatrix matrix, int size) {
        List<String> lines = new ArrayList<>();

        for (int y = 0; y + 1 < size; y += 2) {
            StringBuilder line = new StringBuilder(size);
            for (int x = 0; x < size; x++) {
                line.append(halfBlock(matrix.get(x, y), matrix.get(x, y + 1)));
            }
            lines.add(line.toString());
        }

        return lines;
    }

    private static char halfBlock(boolean top, boolean bottom) {
        if (top && bottom) {
            return BOTH;
        }
        if (top) {
            return UPPER;
        }
        if (bottom) {
            return LOWER;
        }
        return NEITHER;
    }

    /**
     * 编码参数
     * <p>
     * 指定 utf-8 会让编码器带上一段字符集声明，中文内容才扫得回原样；
     * 容错级取中档，边距只留一格——控制台那一路本来就窄，留宽了整张码放不下一屏
     */
    private static Map<EncodeHintType, Serializable> encodeHints() {
        HashMap<EncodeHintType, Serializable> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        return hints;
    }
}
