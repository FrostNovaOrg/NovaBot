package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("直播间数据包编解码")
class BilibiliPacketCodecTest {
    /**
     * 构造一个未压缩的数据包
     * @param operation 操作类型
     * @param protocolVersion 协议版本
     * @param body 负载
     * @return 数据包字节
     */
    private byte[] packet(int operation, int protocolVersion, byte[] body) {
        return ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) protocolVersion)
                .putInt(operation)
                .putInt(1)
                .put(body)
                .array();
    }

    private byte[] jsonPacket(String json) {
        return packet(DataPackType.NOTICE.getCode(), DataHeaderType.RAW_JSON.getCode(), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("编码结果的头部字段正确")
    void encodeHeader() {
        byte[] encoded = BilibiliPacketCodec.encode(DataPackType.VERIFY, "{\"roomid\":123}");

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        assertEquals(encoded.length, buffer.getInt(), "整包长度应等于实际字节数");
        assertEquals(BilibiliPacketCodec.HEADER_LENGTH, buffer.getShort() & 0xFFFF);
        assertEquals(DataHeaderType.HEARTBEAT.getCode(), buffer.getShort() & 0xFFFF);
        assertEquals(DataPackType.VERIFY.getCode(), buffer.getInt());

        buffer.getInt();
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        assertEquals("{\"roomid\":123}", new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("编码空负载的心跳包")
    void encodeEmptyBody() {
        byte[] encoded = BilibiliPacketCodec.encode(DataPackType.HEARTBEAT, null);

        assertEquals(BilibiliPacketCodec.HEADER_LENGTH, encoded.length);
    }

    @Test
    @DisplayName("解码单个未压缩数据包")
    void decodeSingle() {
        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(jsonPacket("{\"cmd\":\"DANMU_MSG\"}"));

        assertEquals(1, packets.size());
        assertEquals(DataPackType.NOTICE.getCode(), packets.get(0).getOperation());
        assertEquals("{\"cmd\":\"DANMU_MSG\"}", packets.get(0).getBodyAsText());
    }

    @Test
    @DisplayName("解码同一批次中连续的多个数据包")
    void decodeConsecutive() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(jsonPacket("{\"cmd\":\"A\"}"));
        stream.write(jsonPacket("{\"cmd\":\"B\"}"));
        stream.write(jsonPacket("{\"cmd\":\"C\"}"));

        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(stream.toByteArray());

        assertEquals(3, packets.size());
        assertEquals("{\"cmd\":\"A\"}", packets.get(0).getBodyAsText());
        assertEquals("{\"cmd\":\"C\"}", packets.get(2).getBodyAsText());
    }

    @Test
    @DisplayName("解码 zlib 压缩包并递归展开其中的数据包")
    void decodeZlibNested() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(jsonPacket("{\"cmd\":\"NESTED_1\"}"));
        inner.write(jsonPacket("{\"cmd\":\"NESTED_2\"}"));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(inner.toByteArray());
        }

        byte[] outer = packet(DataPackType.NOTICE.getCode(), 2, compressed.toByteArray());
        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(outer);

        assertEquals(2, packets.size());
        assertEquals("{\"cmd\":\"NESTED_1\"}", packets.get(0).getBodyAsText());
        assertEquals("{\"cmd\":\"NESTED_2\"}", packets.get(1).getBodyAsText());
    }

    @Test
    @DisplayName("人气值负载按大端序解析为整数")
    void decodePopularity() {
        byte[] body = ByteBuffer.allocate(4).putInt(12345).array();
        byte[] data = packet(DataPackType.HEARTBEAT_RESPONSE.getCode(), DataHeaderType.HEARTBEAT.getCode(), body);

        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(data);

        assertEquals(1, packets.size());
        assertEquals(12345, packets.get(0).getBodyAsInt());
    }

    @Test
    @DisplayName("负载长度不足时人气值解析为 0")
    void decodeShortPopularity() {
        BilibiliPacket packet = new BilibiliPacket(DataPackType.HEARTBEAT_RESPONSE.getCode(), 1, new byte[]{1, 2});

        assertEquals(0, packet.getBodyAsInt());
    }

    @Test
    @DisplayName("整包长度小于头部长度时停止解析而不死循环")
    void rejectsUnderlongPacket() {
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(4)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }

    @Test
    @DisplayName("整包长度超出实际数据时停止解析")
    void rejectsOverlongPacket() {
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(1024)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }

    @Test
    @DisplayName("长度字段为负数时停止解析")
    void rejectsNegativeLength() {
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(-1)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }

    @Test
    @DisplayName("空数据与不足一个头部的数据均安全返回")
    void handlesTruncatedInput() {
        assertNotNull(BilibiliPacketCodec.decode(new byte[0]));
        assertTrue(BilibiliPacketCodec.decode(new byte[0]).isEmpty());
        assertTrue(BilibiliPacketCodec.decode(new byte[]{0, 0, 0}).isEmpty());
    }

    @Test
    @DisplayName("压缩数据损坏时不影响整体流程")
    void handlesCorruptedCompressedBody() {
        byte[] data = packet(DataPackType.NOTICE.getCode(), 2, new byte[]{1, 2, 3, 4, 5});

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }

    @Test
    @DisplayName("头部长度大于整包长度时停止解析")
    void rejectsHeaderLongerThanPacket() {
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 8)
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }
}
