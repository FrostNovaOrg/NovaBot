package com.starlwr.bot.bilibili.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("最小 protobuf 读取器")
class BilibiliProtobufReaderTest {
    /**
     * 按 wire format 拼一条消息，用来构造测试输入
     * <p>
     * 测试<b>不复用被测类的写法</b>：这里从头按规范拼字节，读取器读错了才能被发现。
     * 若两边共用同一套编码代码，双方一起写错时测试会通过。
     */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private Writer varint(int field, long value) {
            return key(field, 0).raw(value);
        }

        private Writer bytes(int field, byte[] value) {
            key(field, 2).raw(value.length);
            out.writeBytes(value);
            return this;
        }

        private Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        private Writer fixed64(int field, long value) {
            key(field, 1);
            for (int offset = 0; offset < 8; offset++) {
                out.write((int) (value >>> (offset * 8)) & 0xFF);
            }
            return this;
        }

        private Writer fixed32(int field, int value) {
            key(field, 5);
            for (int offset = 0; offset < 4; offset++) {
                out.write((value >>> (offset * 8)) & 0xFF);
            }
            return this;
        }

        private Writer key(int field, int wire) {
            return raw(((long) field << 3) | wire);
        }

        /**
         * 直接写一个 varint，不带字段头
         */
        private Writer raw(long value) {
            while (true) {
                int current = (int) (value & 0x7F);
                value >>>= 7;
                if (value == 0) {
                    out.write(current);
                    return this;
                }
                out.write(current | 0x80);
            }
        }

        private byte[] build() {
            return out.toByteArray();
        }
    }

    private static Writer writer() {
        return new Writer();
    }

    @Nested
    @DisplayName("varint 边界")
    class Varints {
        @Test
        @DisplayName("跨字节边界的取值都能读回原值")
        void readsBoundaryValues() {
            // 0 与 1 字节的分界在 127/128，之后每 7 位一个坎
            long[] values = {0L, 1L, 127L, 128L, 129L, 255L, 300L, 16383L, 16384L,
                    2097151L, 2097152L, Integer.MAX_VALUE, 1L << 31, 1L << 55, Long.MAX_VALUE};

            for (long value : values) {
                BilibiliProtobufReader message = BilibiliProtobufReader.parse(writer().varint(1, value).build());
                assertFalse(message.isTruncated(), "值 " + value + " 应当能完整读出");
                assertEquals(value, message.number(1), "值 " + value + " 读回后应当不变");
            }
        }

        @Test
        @DisplayName("无符号 64 位上限以负数形式读出，而不是读坏")
        void readsUnsignedMaximum() {
            // 实抓报文里出现过 18446744073709551615（uinfo 的一个子字段），占满 10 字节。
            // Java 的 long 只有有符号形式，读回来必然是 -1，重要的是<b>后面的字段不受影响</b>
            byte[] data = writer().varint(1, -1L).varint(2, 42L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertFalse(message.isTruncated());
            assertEquals(-1L, message.number(1));
            assertEquals(42L, message.number(2), "10 字节的 varint 之后位置应当仍然正确");
        }

        @Test
        @DisplayName("超过 10 字节的 varint 视为畸形，不会一路读到缓冲区末尾")
        void rejectsOverlongVarint() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x08);
            // 11 个带续读位的字节。没有上限的实现会把后面的内容全部吞掉
            for (int index = 0; index < 11; index++) {
                out.write(0xFF);
            }
            out.write(0x01);

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(out.toByteArray());
            assertTrue(message.isTruncated(), "11 字节的 varint 应当被判为畸形");
            assertNull(message.number(1));
        }
    }

    @Nested
    @DisplayName("嵌套消息")
    class NestedMessages {
        @Test
        @DisplayName("逐层读取嵌套消息")
        void readsNestedLevels() {
            byte[] innermost = writer().string(1, "最里层").varint(2, 7L).build();
            byte[] middle = writer().bytes(3, innermost).varint(4, 8L).build();
            byte[] data = writer().varint(1, 100L).bytes(22, middle).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertEquals(100L, message.number(1));

            BilibiliProtobufReader level2 = message.message(22);
            assertNotNull(level2);
            assertEquals(8L, level2.number(4));

            BilibiliProtobufReader level3 = level2.message(3);
            assertNotNull(level3);
            assertEquals("最里层", level3.string(1));
            assertEquals(7L, level3.number(2));
        }

        @Test
        @DisplayName("空的嵌套消息是一条空消息，不是缺失")
        void emptyNestedMessageIsNotAbsent() {
            // 实抓语料里「没有粉丝勋章」下发的就是一条空子消息，而不是省略字段。
            // 两者必须能区分：前者要当作「确实没有勋章」，后者要当作「这条消息没说」
            BilibiliProtobufReader message = BilibiliProtobufReader.parse(writer().bytes(9, new byte[0]).build());

            assertTrue(message.has(9));
            BilibiliProtobufReader medal = message.message(9);
            assertNotNull(medal, "字段存在时不应返回 null");
            assertEquals(0, medal.size());
            assertFalse(medal.isTruncated(), "空消息是合法的，不该被判为截断");
            assertNull(medal.number(1));

            assertNull(BilibiliProtobufReader.parse(new byte[0]).message(9), "字段不存在时才返回 null");
        }
    }

    @Nested
    @DisplayName("未知字段")
    class UnknownFields {
        @Test
        @DisplayName("四种 wire type 的未知字段都能正确跳过")
        void skipsAllWireTypes() {
            // 中间四个字段假设我们都不认识，关键是它们之后的字段 9 仍能读到正确的值
            byte[] data = writer()
                    .varint(1, 5L)
                    .varint(2, 1L << 40)
                    .fixed64(3, 0x1122334455667788L)
                    .string(4, "一段不认识的文本")
                    .fixed32(5, 0x11223344)
                    .varint(9, 999L)
                    .build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertFalse(message.isTruncated());
            assertEquals(5L, message.number(1));
            assertEquals(999L, message.number(9), "跳过未知字段后位置应当仍然正确");
        }

        @Test
        @DisplayName("定长字段按小端序读出")
        void readsFixedLittleEndian() {
            byte[] data = writer().fixed32(1, 0x11223344).fixed64(2, 0x1122334455667788L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertEquals(0x11223344L, message.number(1));
            assertEquals(0x1122334455667788L, message.number(2));
        }

        @Test
        @DisplayName("字段号很大的未知字段不影响其余字段")
        void handlesLargeFieldNumbers() {
            byte[] data = writer().varint(1, 1L).varint(536870911, 2L).varint(3, 3L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertFalse(message.isTruncated());
            assertEquals(3L, message.number(3));
            assertEquals(2L, message.number(536870911));
        }
    }

    @Nested
    @DisplayName("畸形与截断的输入")
    class Malformed {
        @Test
        @DisplayName("空输入与 null 得到一条空消息")
        void handlesEmptyInput() {
            for (byte[] data : new byte[][]{null, new byte[0]}) {
                BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
                assertEquals(0, message.size());
                assertFalse(message.isTruncated(), "没有内容不等于读坏了");
                assertNull(message.number(1));
            }
        }

        @Test
        @DisplayName("从任意位置截断都不抛异常，且保留已读到的字段")
        void keepsFieldsReadBeforeTruncation() {
            int uidLength = writer().varint(1, 500000005L).build().length;
            byte[] full = writer()
                    .varint(1, 500000005L)
                    .string(2, "观众昵称")
                    .varint(5, 1L)
                    .varint(7, 1786294335L)
                    .bytes(22, writer().varint(1, 500000005L).string(2, "嵌套").build())
                    .build();

            // 逐字节截断，一处都不许抛出
            for (int length = 0; length <= full.length; length++) {
                byte[] cut = Arrays.copyOf(full, length);
                BilibiliProtobufReader message = assertDoesNotThrow(
                        () -> BilibiliProtobufReader.parse(cut), "截断到 " + length + " 字节时抛了异常");

                // uid 在报文开头。只要它那一段读完了，后面截在哪里都不该影响它——
                // 这正是「截断也照常出事件」的依据
                if (length >= uidLength) {
                    assertEquals(500000005L, message.number(1), "截断到 " + length + " 字节时 uid 应当仍可读");
                }
            }

            // 完整输入不应被判为截断
            assertFalse(BilibiliProtobufReader.parse(full).isTruncated());
        }

        @Test
        @DisplayName("长度字段声称的长度超出剩余字节时判为截断")
        void rejectsLengthBeyondBuffer() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x08);
            out.write(0x01);
            // 字段 2，wire type 2，声称长度 100，但后面只有 3 个字节
            out.write(0x12);
            out.write(100);
            out.writeBytes(new byte[]{1, 2, 3});

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(out.toByteArray());
            assertTrue(message.isTruncated());
            assertEquals(1L, message.number(1), "越界之前读到的字段应当保留");
            assertNull(message.bytes(2));
        }

        @Test
        @DisplayName("长度字段为巨大值时不会因为转 int 溢出而躲过检查")
        void rejectsHugeLength() {
            byte[] data = writer().varint(1, 1L).key(2, 2).raw(0x1_0000_0001L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertTrue(message.isTruncated(), "长度 4294967297 截成 int 会变成 1，必须用 long 比较才拦得住");
            assertEquals(1L, message.number(1));
        }

        @Test
        @DisplayName("已废弃与未定义的 wire type 就此停下")
        void stopsOnUnsupportedWireType() {
            // 3 与 4 是已废弃的 group，6 与 7 从未定义。它们都没有长度信息，无法跳过
            for (int wire : new int[]{3, 4, 6, 7}) {
                byte[] data = writer().varint(1, 11L).key(2, wire).varint(3, 33L).build();

                BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
                assertTrue(message.isTruncated(), "wire type " + wire + " 应当被判为畸形");
                assertEquals(11L, message.number(1), "wire type " + wire + " 之前的字段应当保留");
                assertNull(message.number(3));
            }
        }

        @Test
        @DisplayName("字段号 0 就此停下")
        void stopsOnZeroFieldNumber() {
            byte[] data = writer().varint(1, 11L).key(0, 0).varint(3, 33L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertTrue(message.isTruncated());
            assertEquals(11L, message.number(1));
        }

        @Test
        @DisplayName("把字符串字段当嵌套消息读不会抛异常")
        void treatingStringAsMessageDoesNotThrow() {
            byte[] data = writer().string(2, "这不是一条嵌套消息").build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertDoesNotThrow(() -> message.message(2));
        }
    }

    @Nested
    @DisplayName("取值")
    class Accessors {
        @Test
        @DisplayName("类型不符时返回空而不是抛异常")
        void mismatchedTypesReturnNull() {
            byte[] data = writer().varint(1, 5L).string(2, "文本").build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertNull(message.number(2), "字节串不是整数");
            assertNull(message.string(1), "整数不是字符串");
            assertNull(message.bytes(1));
            assertNull(message.message(1));
            assertNull(message.number(99));
            assertNull(message.string(99));
            assertFalse(message.has(99));
        }

        @Test
        @DisplayName("重复出现的字段保留最后一个")
        void repeatedFieldKeepsLast() {
            byte[] data = writer().varint(1, 1L).varint(1, 2L).varint(1, 3L).build();

            assertEquals(3L, BilibiliProtobufReader.parse(data).number(1));
        }

        @Test
        @DisplayName("非法 UTF-8 字节被替换而不是让整条消息作废")
        void invalidUtf8IsReplaced() {
            byte[] data = writer().bytes(2, new byte[]{(byte) 0xC3, (byte) 0x28}).varint(5, 1L).build();

            BilibiliProtobufReader message = BilibiliProtobufReader.parse(data);
            assertNotNull(message.string(2));
            assertEquals(1L, message.number(5), "昵称里有坏字节不该影响其余字段");
        }
    }
}
