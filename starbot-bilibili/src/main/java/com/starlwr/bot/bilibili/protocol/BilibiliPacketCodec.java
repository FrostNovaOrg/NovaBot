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
     * 解码限额的默认值
     * <p>
     * 32 MB 与 3 层是<b>防御性上限而非预期值</b>：正常数据包解压后是几十 KB、嵌套不超过一层。
     * 它们只在数据异常或被恶意构造时起作用。
     * <p>
     * <b>做成可配的理由是内存</b>：默认堆是 {@code -Xmx512m}，而这个上限允许单次解压
     * 吃掉 32 MB 连续字节数组——在小内存 VPS 上，防御上限自己就可能是那根稻草。
     * 1 GB 机器建议连同 {@code -Xmx} 一起调低，见 {@code docs/performance.md}。
     */
    public static final Limits DEFAULT_LIMITS = new Limits(32 * 1024 * 1024, 3);

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
        return decode(data, DEFAULT_LIMITS);
    }

    /**
     * 按给定限额解码一段字节流
     * <p>
     * 限额随调用传入而不是设成可变静态字段：这个类是无状态工具，
     * 一个可写的全局字段会让「这次解码用的是哪个上限」变得要靠时序去推。
     * @param data 字节流
     * @param limits 解码限额
     * @return 数据包列表，数据非法时返回空列表
     */
    public static List<BilibiliPacket> decode(byte[] data, Limits limits) {
        Limits effective = limits == null ? DEFAULT_LIMITS : limits;
        // 预算属于整次解码，不属于任何一层解压：按子包各算一份的话，
        // 一批「各自不超限」的兄弟子包就能把放大倍数藏在份数里
        DecompressBudget budget = new DecompressBudget(effective.maxDecompressedBytes());
        List<BilibiliPacket> packets = new ArrayList<>();
        decodeInto(data, packets, 0, effective, budget);
        if (budget.isBlown()) {
            return new ArrayList<>();
        }
        return packets;
    }

    /**
     * 解码限额
     * <p>
     * 非正数一律回退到默认值：把上限配成 0 会让所有压缩包都解不开，
     * 而那种「配错一个数就整条流静默消失」的失败方式最难查。
     * @param maxDecompressedBytes 解压后允许的最大字节数
     * @param maxNestingDepth 递归展开压缩包的最大层数
     */
    public record Limits(int maxDecompressedBytes, int maxNestingDepth) {
        public Limits {
            if (maxDecompressedBytes <= 0) {
                maxDecompressedBytes = 32 * 1024 * 1024;
            }
            if (maxNestingDepth <= 0) {
                maxNestingDepth = 3;
            }
        }
    }

    /**
     * 递归解码
     * @param data 字节流
     * @param packets 结果收集器
     * @param depth 当前递归层数
     * @param budget 这次解码全程共用的解压预算
     */
    private static void decodeInto(byte[] data, List<BilibiliPacket> packets, int depth, Limits limits, DecompressBudget budget) {
        if (depth > limits.maxNestingDepth()) {
            log.warn("直播间数据包嵌套层数超过 {} 层, 已停止解析", limits.maxNestingDepth());
            return;
        }

        int offset = 0;
        while (offset + HEADER_LENGTH <= data.length) {
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, HEADER_LENGTH);

            int packetLength = buffer.getInt();
            int headerLength = buffer.getShort() & 0xFFFF;
            int protocolVersion = buffer.getShort() & 0xFFFF;
            int operation = buffer.getInt();

            // 长度字段不可信时立即停止，避免负数或越界长度导致死循环。
            // 头长不得超过整包长，否则按整包长切负载会切出负长度；
            // 越界用减法判：offset + packetLength 在两者都接近上限时会溢出成负数而绕过加法判
            if (packetLength < HEADER_LENGTH || headerLength < HEADER_LENGTH || headerLength > packetLength
                    || packetLength > data.length - offset) {
                log.warn("直播间数据包长度字段异常 (整包 {}, 头部 {}, 剩余 {}), 已停止解析", packetLength, headerLength, data.length - offset);
                return;
            }

            byte[] body = new byte[packetLength - headerLength];
            System.arraycopy(data, offset + headerLength, body, 0, body.length);

            if (protocolVersion == DataHeaderType.BROTLI_JSON.getCode()) {
                decompress(body, true, budget).ifPresent(decompressed -> decodeInto(decompressed, packets, depth + 1, limits, budget));
            } else if (protocolVersion == PROTOCOL_ZLIB) {
                decompress(body, false, budget).ifPresent(decompressed -> decodeInto(decompressed, packets, depth + 1, limits, budget));
            } else {
                packets.add(new BilibiliPacket(operation, protocolVersion, body));
            }

            // 预算爆过一次，这批数据就不可信了：剩下的子包不再解压，免得每个都白打一条警告
            if (budget.isBlown()) {
                return;
            }

            offset += packetLength;
        }
    }

    /**
     * 解压负载
     * @param body 压缩后的负载
     * @param brotli 是否为 brotli 压缩，否则按 zlib 处理
     * @param budget 这次解码全程共用的解压预算
     * @return 解压结果，失败或预算超限时返回空
     */
    private static Optional<byte[]> decompress(byte[] body, boolean brotli, DecompressBudget budget) {
        try (ByteArrayInputStream source = new ByteArrayInputStream(body);
             InputStream input = brotli ? new BrotliInputStream(source) : new InflaterInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(body.length * 4, DECOMPRESS_BUFFER_SIZE))) {

            byte[] buffer = new byte[DECOMPRESS_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (!budget.charge(read)) {
                    log.warn("直播间数据包解压产出合计超过 {} 字节, 已放弃解析", budget.limit);
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

    /**
     * 一次 decode 调用内所有解压（含嵌套、含兄弟子包）共用的预算
     */
    private static final class DecompressBudget {
        private final int limit;

        private long used;

        private boolean blown;

        private DecompressBudget(int limit) {
            this.limit = limit;
        }

        /**
         * 记一笔解压产出
         * @return 预算内放行；超限或此前已爆则拒绝并记为已爆
         */
        private boolean charge(int bytes) {
            if (blown || bytes > limit - used) {
                blown = true;
                return false;
            }
            used += bytes;
            return true;
        }

        private boolean isBlown() {
            return blown;
        }
    }
}
