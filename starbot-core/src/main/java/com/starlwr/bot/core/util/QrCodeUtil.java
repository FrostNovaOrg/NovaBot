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
 */
@Slf4j
public class QrCodeUtil {
    /**
     * 生成二维码
     * @param content 二维码内容
     * @param size 二维码尺寸
     * @return 二维码矩阵
     */
    public static Optional<BitMatrix> generateQrCode(String content, int size) {
        if (size % 2 != 0) {
            throw new IllegalArgumentException("二维码尺寸必须为偶数");
        }

        HashMap<EncodeHintType, Serializable> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            return Optional.of(qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, size, size, hints));
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
        Optional<BitMatrix> optionalMatrix = generateQrCode(content, size);

        optionalMatrix.ifPresent(matrix -> {
            List<String> lines = new ArrayList<>();
            StringBuilder builder = new StringBuilder();

            for (int y = 0; y < size - 1; y += 2) {
                for (int x = 0; x < size; x++) {
                    boolean top = matrix.get(x, y);
                    boolean bottom = matrix.get(x, y + 1);

                    if (top && bottom) {
                        builder.append("█");
                    } else if (top) {
                        builder.append("▀");
                    } else if (bottom) {
                        builder.append("▄");
                    } else {
                        builder.append(" ");
                    }
                }

                lines.add(builder.toString());
                builder.setLength(0);
            }

            for (String line : lines) {
                log.info(line);
            }
        });
    }

    /**
     * 生成二维码并获取图片 Base64 编码
     * @param content 二维码内容
     * @param size 二维码尺寸
     * @return 二维码图片 Base64 编码
     */
    public static Optional<String> generateQrCodeAndGetBase64(String content, int size) {
        Optional<BitMatrix> optionalMatrix = generateQrCode(content, size);

        if (optionalMatrix.isPresent()) {
            BitMatrix matrix = optionalMatrix.get();

            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", outputStream);
                byte[] imageBytes = outputStream.toByteArray();

                return Optional.of(Base64.getEncoder().encodeToString(imageBytes));
            } catch (IOException e) {
                log.error("二维码转换为图片失败", e);
            }
        }

        return Optional.empty();
    }
}
