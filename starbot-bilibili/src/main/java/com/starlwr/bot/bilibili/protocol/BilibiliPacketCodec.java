package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import lombok.extern.slf4j.Slf4j;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.InflaterInputStream;

/**
 * 直播间长连接数据包编解码器
 * <p>
 * 数据包由 16 字节定长头部与负载构成，头部各字段均为大端序：
 * <pre>
 *   偏移  长度  含义
 *     0    4   整包长度，含头部
 *     4    2   头部长度，固定为 16
 *     6    2   协议版本，决定负载编码方式
 *     8    4   操作类型
 *    12    4   序列号
 * </pre>
 * 协议版本为压缩类型时，负载解压后又是一批完整的数据包，需要递归展开。
 */
@Slf4j
public final class BilibiliPacketCodec {
    /**
     * 头部长度
     */
    public static final int HEADER_LENGTH = 16;

    /**
     * zlib 压缩的协议版本
     * <p>
     * 服务端目前主要下发 brotli 压缩的数据包，但历史上也使用过 zlib，为兼容旧行为一并处理。
     */
    private static final int PROTOCOL_ZLIB = 2;

    /**
     * 序列号，服务端不校验其具体取值
     */
    private static final int SEQUENCE = 1;

    /**
     * 解压缓冲区大小
     */
    private static final int DECOMPRESS_BUFFER_SIZE = 8192;

    /**
     * 解压后允许的最大字节数，防御异常或恶意构造的数据包导致内存耗尽
     */
    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;

    /**
     * 递归展开压缩包的最大层数，正常数据不会超过一层
     */
    private static final int MAX_NESTING_DEPTH = 3;

    private BilibiliPacketCodec() {
    }

    /**
     * 编码一个数据包
     * @param operation 操作类型
     * @param body 负载
     * @return 编码后的字节
     */
    public static byte[] encode(DataPackType operation, String body) {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);

        return ByteBuffer.allocate(HEADER_LENGTH + payload.length)
                .putInt(HEADER_LENGTH + payload.length)
                .putShort((short) HEADER_LENGTH)
                // 客户端发出的包一律使用心跳协议版本
                .putShort((short) DataHeaderType.HEARTBEAT.getCode())
                .putInt(operation.getCode())
                .putInt(SEQUENCE)
                .put(payload)
                .array();
    }

    /**
     * 解码一段字节流，展开其中所有数据包
     * @param data 字节流
     * @return 数据包列表，数据非法时返回空列表
     */
    public static List<BilibiliPacket> decode(byte[] data) {
        List<BilibiliPacket> packets = new ArrayList<>();
        decodeInto(data, packets, 0);
        return packets;
    }

    /**
     * 递归解码
     * @param data 字节流
     * @param packets 结果收集器
     * @param depth 当前递归层数
     */
    private static void decodeInto(byte[] data, List<BilibiliPacket> packets, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            log.warn("直播间数据包嵌套层数超过 {} 层, 已停止解析", MAX_NESTING_DEPTH);
            return;
        }

        int offset = 0;
        while (offset + HEADER_LENGTH <= data.length) {
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, HEADER_LENGTH);

            int packetLength = buffer.getInt();
            int headerLength = buffer.getShort() & 0xFFFF;
            int protocolVersion = buffer.getShort() & 0xFFFF;
            int operation = buffer.getInt();

            // 长度字段不可信时立即停止，避免负数或越界长度导致死循环
            if (packetLength < HEADER_LENGTH || headerLength < HEADER_LENGTH || offset + packetLength > data.length) {
                log.warn("直播间数据包长度字段异常 (整包 {}, 头部 {}, 剩余 {}), 已停止解析", packetLength, headerLength, data.length - offset);
                return;
            }

            byte[] body = new byte[packetLength - headerLength];
            System.arraycopy(data, offset + headerLength, body, 0, body.length);

            if (protocolVersion == DataHeaderType.BROTLI_JSON.getCode()) {
                decompress(body, true).ifPresent(decompressed -> decodeInto(decompressed, packets, depth + 1));
            } else if (protocolVersion == PROTOCOL_ZLIB) {
                decompress(body, false).ifPresent(decompressed -> decodeInto(decompressed, packets, depth + 1));
            } else {
                packets.add(new BilibiliPacket(operation, protocolVersion, body));
            }

            offset += packetLength;
        }
    }

    /**
     * 解压负载
     * @param body 压缩后的负载
     * @param brotli 是否为 brotli 压缩，否则按 zlib 处理
     * @return 解压结果，失败时返回空
     */
    private static Optional<byte[]> decompress(byte[] body, boolean brotli) {
        try (ByteArrayInputStream source = new ByteArrayInputStream(body);
             InputStream input = brotli ? new BrotliInputStream(source) : new InflaterInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(body.length * 4, DECOMPRESS_BUFFER_SIZE))) {

            byte[] buffer = new byte[DECOMPRESS_BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    log.warn("直播间数据包解压后超过 {} 字节, 已放弃解析", MAX_DECOMPRESSED_BYTES);
                    return Optional.empty();
                }
                output.write(buffer, 0, read);
            }

            return Optional.of(output.toByteArray());
        } catch (IOException e) {
            log.warn("解压直播间数据包失败 ({})", brotli ? "brotli" : "zlib", e);
            return Optional.empty();
        }
    }
}
