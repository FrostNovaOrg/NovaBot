package com.starlwr.bot.core.config.ui.auth.passkey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 够用就好的 CBOR 解码器
 * <p>
 * 通行密钥这条路上只有两处需要读 CBOR：注册时那个 {@code attestationObject}，
 * 以及它里面装着的 COSE 公钥。两者都由认证器按 CTAP2 的<b>确定长度</b>规范编码，
 * 因此这里只实现规范里用得到的那几种类型，<b>不实现不定长编码、标签与浮点</b>——
 * 遇到就抛，而不是猜一个值继续往下走。
 * <p>
 * 🔴 为什么不引一个 CBOR 库：这一段读的是<b>攻击者能完全控制的字节</b>，
 * 而它离「拿这把公钥放人进来」只隔一次验签。多一个依赖就多一份要跟着盯的东西，
 * 而这里真正需要的解码能力不到两百行。宁可把射程写窄、写死，也不要一个什么都能解的通用件。
 * <p>
 * <b>越界一律抛异常。</b>读到一半没字节了就说没字节了，不返回一个「短了一截」的值——
 * 截断的公钥与完整的公钥在后面那几步里长得一模一样，直到验签才失败，
 * 而那时报出来的原因与真实原因毫无关系。
 */
final class CborReader {
    /**
     * 单个字符串／字节串／数组／字典的元素数上限
     * <p>
     * CBOR 的长度字段最长 8 字节，照着它分配内存等于让对方决定这台机器要吃多少内存。
     * 认证器给的那两样东西不过几百字节，取 64 KiB 已是数量级上的宽裕。
     */
    private static final int MAX_LENGTH = 64 * 1024;

    /**
     * 嵌套深度上限，挡住「一万层数组」这种把栈耗光的输入
     */
    private static final int MAX_DEPTH = 16;

    private final byte[] data;

    private int offset;

    CborReader(byte[] data) {
        this.data = data;
        this.offset = 0;
    }

    /**
     * 读出一个 CBOR 数据项
     * <p>
     * 返回值只有五种形态：{@link Long}、{@code byte[]}、{@link String}、{@link List}、{@link Map}，
     * 外加布尔与 null。调用方按自己期望的类型取，取不到就当输入不合法。
     * @return 解出来的值
     */
    Object read() {
        return read(0);
    }

    private Object read(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("CBOR 嵌套过深");
        }

        int initial = nextByte();
        int major = initial >> 5;
        int minor = initial & 0x1f;

        return switch (major) {
            case 0 -> Long.valueOf(readLength(minor));
            // 负整数编码的是 -1-n，按规范原样还原
            case 1 -> Long.valueOf(-1 - readLength(minor));
            case 2 -> readBytes((int) checkedLength(readLength(minor)));
            case 3 -> new String(readBytes((int) checkedLength(readLength(minor))), StandardCharsets.UTF_8);
            case 4 -> readArray((int) checkedLength(readLength(minor)), depth);
            case 5 -> readMap((int) checkedLength(readLength(minor)), depth);
            case 7 -> readSimple(minor);
            default -> throw new IllegalArgumentException("CBOR 中出现了不支持的类型: " + major);
        };
    }

    private List<Object> readArray(int size, int depth) {
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(read(depth + 1));
        }
        return list;
    }

    /**
     * 读一个字典
     * <p>
     * 用 {@link LinkedHashMap} 保住原顺序：COSE 公钥的键是有序的负整数，
     * 出问题时按原顺序打印出来才对得上认证器那一侧的编码。
     */
    private Map<Object, Object> readMap(int size, int depth) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Object key = read(depth + 1);
            Object value = read(depth + 1);
            // 重复键不是「后来的覆盖先来的」，而是这份编码本身不合规范。
            // 放过去的话，一个精心构造的 COSE 键表可以让「解析出来的算法」与「实际用的公钥」不是一对。
            // 先 containsKey 再放入：put 的返回值是「旧值」，旧值是 null 时它分不清「第一次放」与「重复放」
            if (map.containsKey(key)) {
                throw new IllegalArgumentException("CBOR 字典里出现了重复的键: " + key);
            }
            map.put(key, value);
        }
        return map;
    }

    private Object readSimple(int minor) {
        return switch (minor) {
            case 20 -> Boolean.FALSE;
            case 21 -> Boolean.TRUE;
            case 22 -> null;
            default -> throw new IllegalArgumentException("CBOR 中出现了不支持的简单值: " + minor);
        };
    }

    /**
     * 读出长度／数值字段
     * <p>
     * 24~27 表示随后 1、2、4、8 个字节才是真正的值；28~31 在规范里保留或表示不定长，
     * 认证器不该发过来，发过来就是输入不合法。
     */
    private long readLength(int minor) {
        if (minor < 24) {
            return minor;
        }

        int width = switch (minor) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new IllegalArgumentException("CBOR 中出现了不定长或保留的长度编码");
        };

        long value = 0;
        for (int i = 0; i < width; i++) {
            value = (value << 8) | nextByte();
        }

        if (value < 0) {
            throw new IllegalArgumentException("CBOR 长度字段溢出");
        }

        return value;
    }

    private long checkedLength(long length) {
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("CBOR 数据项过长: " + length);
        }
        return length;
    }

    private byte[] readBytes(int length) {
        if (offset + length > data.length) {
            throw new IllegalArgumentException("CBOR 数据在读取 " + length + " 字节时提前结束");
        }

        byte[] value = new byte[length];
        System.arraycopy(data, offset, value, 0, length);
        offset += length;
        return value;
    }

    private int nextByte() {
        if (offset >= data.length) {
            throw new IllegalArgumentException("CBOR 数据提前结束");
        }
        return data[offset++] & 0xff;
    }

    /**
     * 按 CBOR 读一个字典，读不出来就抛
     * <p>
     * 顶层是不是字典这件事各处都要判一次，集中在这里，免得每个调用点各写一遍 instanceof。
     * <p>
     * <b>解完之后必须一个字节不剩。</b>后面跟着的东西没有任何合法解释，而放过它就等于
     * 允许同一串输入携带两份内容——解析的这一侧只看前一份，别处若按别的方式再读一遍就会看到后一份。
     * @param data 待解析的字节
     * @return 顶层字典
     */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> readMap(byte[] data) {
        CborReader reader = new CborReader(data);
        Object value = reader.read();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("CBOR 顶层不是字典");
        }
        if (reader.offset != data.length) {
            throw new IllegalArgumentException("CBOR 字典之后还有 " + (data.length - reader.offset) + " 个多余字节");
        }
        return (Map<Object, Object>) value;
    }

    /**
     * 读开头那个字典，后面剩下的字节不管
     * <p>
     * 只给认证器数据里的公钥用：按规范，公钥之后可以跟着扩展数据，
     * 而<b>公钥自己的长度只有 CBOR 解码器知道</b>——外面那一层数不出来。
     * 与 {@link #readMap(byte[])} 分成两个名字，是为了让「这里为什么允许有剩字节」
     * 写在调用点上，而不是靠一个参数悄悄切换严格程度。
     * @param data 待解析的字节
     * @return 开头那个字典
     */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> readLeadingMap(byte[] data) {
        Object value = new CborReader(data).read();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("CBOR 顶层不是字典");
        }
        return (Map<Object, Object>) value;
    }
}
