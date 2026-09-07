package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    /**
     * 拼一批总长约 totalBytes 的合法未压缩数据包，用来把解压产出撑到想要的大小
     */
    private byte[] jsonPacketStream(int totalBytes) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        String filler = "x".repeat(1024);
        while (stream.size() < totalBytes) {
            stream.writeBytes(jsonPacket(filler));
        }
        return stream.toByteArray();
    }

    /**
     * zlib 压缩
     */
    private byte[] zlib(byte[] data) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(data);
        }
        return compressed.toByteArray();
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
    @DisplayName("解压超过限额时放弃解析，不把限额撑爆内存")
    void refusesWhenOverDecompressedLimit() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(jsonPacket("{\"cmd\":\"NESTED_1\"}"));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(inner.toByteArray());
        }
        byte[] outer = packet(DataPackType.NOTICE.getCode(), 2, compressed.toByteArray());

        // 把上限压到 1 字节：解压刚开始就超额，应当整包放弃而不是抛异常
        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(
                outer, new BilibiliPacketCodec.Limits(1, 3));

        assertTrue(packets.isEmpty());
        // 默认限额下同一份数据是解得开的——证明上面为空是限额起了作用，不是数据本身坏了
        assertEquals(1, BilibiliPacketCodec.decode(outer).size());
    }

    @Test
    @DisplayName("嵌套层数限额为 1 时一层展开仍放行")
    void allowsExpansionUpToNestingLimit() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(jsonPacket("{\"cmd\":\"NESTED_1\"}"));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(inner.toByteArray());
        }
        byte[] outer = packet(DataPackType.NOTICE.getCode(), 2, compressed.toByteArray());

        // 外层是第 0 层、展开一次到第 1 层，限额 1 仍应放行；这条锁的是边界不被改窄
        assertEquals(1, BilibiliPacketCodec.decode(outer, new BilibiliPacketCodec.Limits(1024 * 1024, 1)).size());
    }

    @Test
    @DisplayName("限额配成非正数时回退到默认值，而不是让整条流静默消失")
    void nonPositiveLimitsFallBackToDefaults() {
        BilibiliPacketCodec.Limits zero = new BilibiliPacketCodec.Limits(0, 0);

        assertEquals(BilibiliPacketCodec.DEFAULT_LIMITS.maxDecompressedBytes(), zero.maxDecompressedBytes());
        assertEquals(BilibiliPacketCodec.DEFAULT_LIMITS.maxNestingDepth(), zero.maxNestingDepth());

        BilibiliPacketCodec.Limits negative = new BilibiliPacketCodec.Limits(-1, -5);
        assertEquals(BilibiliPacketCodec.DEFAULT_LIMITS.maxDecompressedBytes(), negative.maxDecompressedBytes());
        assertEquals(BilibiliPacketCodec.DEFAULT_LIMITS.maxNestingDepth(), negative.maxNestingDepth());
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
    @DisplayName("头部长度小于最小头长时停止解析")
    void rejectsHeaderShorterThanMinimum() {
        // 头长写成 8，小于固定的 16：走「头长不足」这条拒绝分支（头长盖过整包的格另在下方）
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 8)
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        assertTrue(BilibiliPacketCodec.decode(data).isEmpty());
    }

    @Test
    @DisplayName("头部长度盖过整包长度时按坏包拒收，不再抛数组负长度")
    void rejectsHeaderExceedingWholePacket() {
        // 整包 16 字节、头部长度却写 17：两个长度都各自合法，合在一起才矛盾，
        // 按整包长切负载会切出 new byte[-1]
        byte[] data = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) (BilibiliPacketCodec.HEADER_LENGTH + 1))
                .putShort((short) 0)
                .putInt(5)
                .putInt(1)
                .array();

        List<BilibiliPacket> packets = assertDoesNotThrow(() -> BilibiliPacketCodec.decode(data));
        assertTrue(packets.isEmpty());
    }

    @Test
    @DisplayName("同一批兄弟子包的解压产出合计超限时整批拒收")
    void siblingPacketsShareOneDecompressedBudget() throws Exception {
        // 每半各自解压约 600 KB：单看谁都不超 1,000,000，合起来约 1.2 MB 超了。
        // 预算若按子包各算一份，这种「每个都守规、合起来越界」的批次会被整体放过
        byte[] half = jsonPacketStream(600 * 1024);

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(half)));
        inner.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(half)));
        byte[] outer = packet(DataPackType.NOTICE.getCode(), 2, zlib(inner.toByteArray()));

        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(outer, new BilibiliPacketCodec.Limits(1_000_000, 3));
        assertTrue(packets.isEmpty(), "两半合计约 1.2 MB，超过 1,000,000 的预算应整批拒收，实际放行 " + packets.size() + " 个包");

        // 默认预算（32 MB）下同一份数据能全解开——证明上面拒收是因为合计超限，不是数据本身坏了
        assertTrue(BilibiliPacketCodec.decode(outer).size() > 0);
    }

    @Test
    @DisplayName("预算爆掉后同批剩余子包不再进解压，解压账目停在第 k 个子包上")
    void stopsDecompressingSiblingsAfterBudgetBlown() throws Exception {
        // 一批四个压缩子包：第 1 个解压通过、第 2 个把预算撑爆、其后两个本可正常解压。
        // 产出列表上这分不出「没再解压」与「解压完再整批丢」（外层兜底都返空），
        // 解压尝试次数才分得开：提前返回在的话它停在第 2 个上
        byte[] first = jsonPacketStream(4 * 1024);
        byte[] second = jsonPacketStream(4 * 1024);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(first)));
        stream.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(second)));
        for (int i = 0; i < 2; i++) {
            stream.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(jsonPacketStream(1024))));
        }

        // 预算放得过第 1 个子包、放不过第 2 个
        BilibiliPacketCodec.Limits limits = new BilibiliPacketCodec.Limits(first.length + second.length / 2, 3);
        BilibiliPacketCodec.DecompressBudget budget = new BilibiliPacketCodec.DecompressBudget(limits.maxDecompressedBytes());
        List<BilibiliPacket> packets = BilibiliPacketCodec.decode(stream.toByteArray(), limits, budget);

        assertTrue(packets.isEmpty(), "预算爆过一次应整批拒收，实际返回 " + packets.size() + " 个包");
        assertEquals(2, budget.decompressAttempts(),
                "解压尝试应停在第 2 个子包上，其后两个不得再进解压，实际 " + budget.decompressAttempts() + " 次");
        assertEquals(first.length, budget.usedBytes(), "已消耗字节应停在第 1 个子包的产出上");

        // 后两个子包在宽预算下都能正常解出——证明上面断言卡的是提前返回，不是数据本身坏了
        ByteArrayOutputStream remaining = new ByteArrayOutputStream();
        for (int i = 0; i < 2; i++) {
            remaining.write(packet(DataPackType.NOTICE.getCode(), 2, zlib(jsonPacketStream(1024))));
        }
        assertEquals(2, BilibiliPacketCodec.decode(remaining.toByteArray(),
                new BilibiliPacketCodec.Limits(1024 * 1024, 3)).size(), "后两个子包本应各自可解压");
    }

    @Test
    @DisplayName("未知协议版本上报 sink：未知 ver 调、已知 ver 不调、返回仍当裸负载")
    void unknownVersionReportedToSink() {
        List<String> reds = new ArrayList<>();
        byte[] unknown = packet(DataPackType.NOTICE.getCode(), 5, "{\"cmd\":\"X\"}".getBytes(StandardCharsets.UTF_8));
        byte[] known = jsonPacket("{\"cmd\":\"DANMU_MSG\"}");

        try {
            List<Integer> seen = new ArrayList<>();
            BilibiliPacketCodec.decode(unknown, null, seen::add);
            assertEquals(List.of(5), seen, "未知 ver 应原样上报给 sink，实际: " + seen);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            List<Integer> seen = new ArrayList<>();
            BilibiliPacketCodec.decode(known, null, seen::add);
            assertEquals(List.of(), seen, "已知 ver 不应上报，实际: " + seen);
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            List<BilibiliPacket> withSink = BilibiliPacketCodec.decode(unknown, null, version -> { });
            assertEquals(1, withSink.size(), "sink 不得改变返回的包数");
            assertEquals(5, withSink.get(0).getProtocolVersion(), "未知 ver 仍应作为裸负载原样入包");
            assertEquals(DataPackType.NOTICE.getCode(), withSink.get(0).getOperation());
            assertEquals("{\"cmd\":\"X\"}", withSink.get(0).getBodyAsText(), "负载字节不得因上报而改变");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
