package com.starlwr.bot.bilibili.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 最小 protobuf wire-format 读取器
 * <p>
 * 平台把部分长连接消息的正文换成了 protobuf（如 {@code INTERACT_WORD_V2} 的 {@code pb} 字段）。
 * <b>本项目不引入 protobuf 运行时</b>：官方消息没有公开的 {@code .proto}，字段语义只能靠反推，
 * 生成代码带来的强类型是假的——它把「我们猜的字段名」固化成了「平台承诺的接口」。
 * 而 wire format 本身只有四种编码，读起来不需要 schema。
 * <p>
 * 因此这里只做 wire 层的事：把字节流切成「字段号 → 值」，值只有两类——
 * 整数（varint / fixed32 / fixed64）与字节串（length-delimited）。
 * <b>字段号的含义一概不管</b>，由调用方按自己反推出的字段表去取。
 * <p>
 * <b>未知字段自动跳过</b>：读取时不需要知道有哪些字段，平台新增字段不会影响已有字段的读取。
 * <p>
 * <b>畸形与截断的输入不抛异常</b>，而是在读不下去的位置停住，保留已经读到的字段并把
 * {@link #isTruncated()} 置为真。长连接上的一条消息读坏了不应影响整个直播间，
 * 而半条消息里已经读到的字段往往仍然可用（uid、msg_type 都在报文开头）。
 * 调用方若在意，可以据此留一行日志。
 * <p>
 * <b>本类不做 zigzag 解码</b>：{@code sint32}/{@code sint64} 需要额外的还原步骤，
 * 而 wire 层分辨不出一个 varint 到底是 {@code int64} 还是 {@code sint64}。
 * 目前反推出的字段里没有负数，遇到时再按需处理，不预先猜。
 */
public final class BilibiliProtobufReader {
    /**
     * 64 位 varint 最多占 10 字节（每字节 7 个有效位）
     */
    private static final int MAX_VARINT_BYTES = 10;

    /**
     * 字段号上界：key 的低 3 位是 wire type，字段号在 64 位 key 里最多占 29 位
     */
    private static final int MAX_FIELD_NUMBER = (1 << 29) - 1;

    private static final int WIRE_VARINT = 0;

    private static final int WIRE_FIXED64 = 1;

    private static final int WIRE_LENGTH_DELIMITED = 2;

    private static final int WIRE_FIXED32 = 5;

    /**
     * 读不下去时用来跳出读取循环。不记录栈——它是正常的控制流而非故障，
     * 每条畸形报文都去抓一次栈太贵
     */
    private static final Malformed MALFORMED = new Malformed();

    /**
     * 字段号到值的映射。值为 {@link Long}（varint / fixed32 / fixed64）
     * 或 {@code byte[]}（length-delimited）
     * <p>
     * 同一字段重复出现时<b>只保留最后一个</b>，与 proto3 对单值字段的规定一致。
     * 需要读 repeated 字段时再扩展，目前反推出的字段里没有重复出现的。
     */
    private final Map<Integer, Object> fields;

    private final boolean truncated;

    private BilibiliProtobufReader(Map<Integer, Object> fields, boolean truncated) {
        this.fields = fields;
        this.truncated = truncated;
    }

    /**
     * 读取一条 protobuf 消息
     * @param data 消息字节，为空时返回一条空消息
     * @return 读取结果，畸形或截断时为读到的部分且 {@link #isTruncated()} 为真
     */
    public static BilibiliProtobufReader parse(byte[] data) {
        Map<Integer, Object> fields = new HashMap<>();
        if (data == null || data.length == 0) {
            return new BilibiliProtobufReader(fields, false);
        }

        Cursor cursor = new Cursor(data);
        try {
            while (cursor.hasRemaining()) {
                long key = cursor.readVarint();
                long fieldNumber = key >>> 3;
                int wire = (int) (key & 7);

                // 字段号 0 不存在、上界是 2^29-1（key 的低 3 位归 wire type）。
                // 越过任一边界都说明位置已经错了，继续读只会读出更多垃圾字段
                if (fieldNumber <= 0 || fieldNumber > MAX_FIELD_NUMBER) {
                    throw MALFORMED;
                }
                int field = (int) fieldNumber;

                switch (wire) {
                    case WIRE_VARINT -> fields.put(field, cursor.readVarint());
                    case WIRE_FIXED64 -> fields.put(field, cursor.readFixed(8));
                    case WIRE_LENGTH_DELIMITED -> fields.put(field, cursor.readLengthDelimited());
                    case WIRE_FIXED32 -> fields.put(field, cursor.readFixed(4));
                    // 3 与 4 是已废弃的 group，6 与 7 从未定义。
                    // 它们都没有长度信息，无法跳过，只能就此停下
                    default -> throw MALFORMED;
                }
            }
        } catch (Malformed e) {
            return new BilibiliProtobufReader(fields, true);
        }

        return new BilibiliProtobufReader(fields, false);
    }

    /**
     * 判断字段是否存在
     * <p>
     * 注意 proto3 <b>不序列化零值</b>：数字 0、空字符串、false 都不会出现在字节流里。
     * 因此「字段不存在」与「字段值为零值」在 wire 层无法区分。
     */
    public boolean has(int field) {
        return fields.containsKey(field);
    }

    /**
     * 取整数字段（varint / fixed32 / fixed64）
     * <p>
     * 超过 {@link Long#MAX_VALUE} 的无符号 64 位值会以负数形式返回，这是 Java 的
     * {@code long} 只有有符号形式所致，不是读取错误。
     * @return 字段值，字段不存在或不是整数时为 {@code null}
     */
    public Long number(int field) {
        return fields.get(field) instanceof Long value ? value : null;
    }

    /**
     * 取字节串字段
     * @return 字段值，字段不存在或不是字节串时为 {@code null}
     */
    public byte[] bytes(int field) {
        return fields.get(field) instanceof byte[] value ? value : null;
    }

    /**
     * 取字符串字段，按 UTF-8 解码
     * <p>
     * 非法字节序列会被替换为 {@code U+FFFD} 而不是抛出异常：昵称里出现一个坏字节
     * 不该让整条消息作废。
     * @return 字段值，字段不存在或不是字节串时为 {@code null}
     */
    public String string(int field) {
        byte[] value = bytes(field);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * 取嵌套消息字段
     * <p>
     * wire 层分辨不出一个字节串到底是嵌套消息、字符串还是 packed 数组，
     * <b>只应对已确认是嵌套消息的字段调用</b>。对字符串字段调用会得到一堆无意义的字段号，
     * 或是一条 {@link #isTruncated()} 为真的空消息。
     * @return 嵌套消息，字段不存在或不是字节串时为 {@code null}；
     *         字段存在但为空时是一条空消息，而不是 {@code null}
     */
    public BilibiliProtobufReader message(int field) {
        byte[] value = bytes(field);
        return value == null ? null : parse(value);
    }

    /**
     * 是否在读到末尾前就停下了
     * <p>
     * 为真说明报文畸形或被截断，已读到的字段仍然可用，但后面的字段全部缺失。
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 已读到的字段号个数，仅用于排障与测试
     */
    public int size() {
        return fields.size();
    }

    /**
     * 本条报文读到的字段号里，不在已知集内的那些（升序）
     * <p>
     * <b>只读</b>：不改变取值语义，也不影响 {@link #has}／{@link #number} 等任何一个取值口。
     * 未知字段照旧被跳过，这个方法只是把「跳过了哪些」说出来——平台在 pb 里新增字段
     * 是今天唯一一种<b>一点痕迹都不留</b>的接口变化，读取器不说就没有任何地方会知道。
     * <p>
     * 全部字段都在已知集里时返回<b>共享的空表</b>，不分配、不排序：调用点是每秒数十条的
     * 长连接热路径，绝大多数报文一个未知字段都没有，这条路径上不该有任何多余开销。
     * <p>
     * 已知集由调用方给出——本类不认识任何字段号的含义（见类注释）。传 {@code null}
     * 等于「什么都不认识」，此时读到的字段号会被整个报出来。
     * @param known 已知字段号集合
     * @return 未知字段号，升序；没有时为空表
     */
    public List<Integer> unknownFields(Set<Integer> known) {
        List<Integer> unknown = null;
        for (Integer field : fields.keySet()) {
            if (known != null && known.contains(field)) {
                continue;
            }
            if (unknown == null) {
                unknown = new ArrayList<>(2);
            }
            unknown.add(field);
        }

        if (unknown == null) {
            return List.of();
        }
        Collections.sort(unknown);
        return unknown;
    }

    /**
     * 带位置的字节游标。读越界一律抛 {@link #MALFORMED}，由 {@link #parse} 统一收口
     */
    private static final class Cursor {
        private final byte[] data;

        private int index;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private boolean hasRemaining() {
            return index < data.length;
        }

        /**
         * 读一个 varint
         * <p>
         * 第 10 字节仍带续读位说明这不是合法的 64 位 varint。<b>必须设这个上限</b>，
         * 否则一串 {@code 0xFF} 会让循环一直读到缓冲区末尾，把后面所有字段的位置全带偏。
         * <p>
         * 第 10 字节本身只有最低一位（2^63）有定义——9×7+1 恰好装满 64 位，
         * 高出的 6 位只能是垃圾，宽容地截掉会把一条位置已错的报文继续往后读。
         */
        private long readVarint() {
            long value = 0;
            for (int shift = 0; shift < MAX_VARINT_BYTES * 7; shift += 7) {
                if (index >= data.length) {
                    throw MALFORMED;
                }

                int current = data[index++];
                // 第 10 字节（shift == 63）只认最低一位，其余 6 位有值即判畸形
                if (shift == (MAX_VARINT_BYTES - 1) * 7 && (current & 0x7E) != 0) {
                    throw MALFORMED;
                }
                value |= (long) (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw MALFORMED;
        }

        /**
         * 读一段以长度打头的字节串
         */
        private byte[] readLengthDelimited() {
            long length = readVarint();
            // 长度用 long 比较：报文里的长度字段可能大得离谱，转成 int 会溢出成负数而躲过检查
            if (length < 0 || length > data.length - index) {
                throw MALFORMED;
            }

            byte[] value = new byte[(int) length];
            System.arraycopy(data, index, value, 0, value.length);
            index += value.length;
            return value;
        }

        /**
         * 读定长整数，小端序
         * @param length 字节数，4 或 8
         */
        private long readFixed(int length) {
            if (data.length - index < length) {
                throw MALFORMED;
            }

            long value = 0;
            for (int offset = 0; offset < length; offset++) {
                value |= (long) (data[index + offset] & 0xFF) << (offset * 8);
            }
            index += length;
            return value;
        }
    }

    /**
     * 读不下去的信号。不对外暴露，也不带栈信息
     */
    private static final class Malformed extends RuntimeException {
        private Malformed() {
            super(null, null, false, false);
        }
    }
}
