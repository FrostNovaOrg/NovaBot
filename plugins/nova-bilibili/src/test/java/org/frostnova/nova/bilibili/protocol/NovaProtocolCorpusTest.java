package org.frostnova.nova.bilibili.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.service.BilibiliApiSupport;
import org.frostnova.nova.bilibili.service.BilibiliEventParser;
import org.frostnova.nova.bilibili.service.BilibiliGiftService;
import org.frostnova.nova.bilibili.service.BilibiliGuardReconciler;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.protocol.NovaProtocolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用实抓语料跑一遍「解析 → 映射 → 协议校验」
 * <p>
 * <b>默认不跑</b>：语料里带着真实观众的 uid 与昵称，不进仓库，因此这条测试靠
 * {@code -Dnovabot.corpus=<语料路径>} 显式开启。语料格式是每行一条
 * {@code {"room": <房间号>, "msg": "<平台原始报文>"}}。
 * <p>
 * 之所以要有它：单测喂的是构造好的报文，覆盖的是我们想到的形状。协议校验的价值恰恰在
 * <b>没想到的形状</b>——某个房间某类消息缺一个字段、某个字段偶尔是字符串而不是数字。
 * 这类问题只有拿真语料整批过一遍才会露出来。
 * <p>
 * 上一份语料（4360 条）随临时目录一起删了，于是更新日志里那个「0 违例」再也复现不出来。
 * 这条测试连同 {@code docs/runbook-protocol-corpus.md} 就是为了让那个数字可复现：
 * <b>写进对外文档的数字，得有人能重新跑出来。</b>
 */
@DisplayName("实抓语料整批过协议校验")
@EnabledIfSystemProperty(named = "novabot.corpus", matches = ".+")
class NovaProtocolCorpusTest {

    /** SEND_GIFT_V2 正文里礼物块的字段号，及块内 id、名、数量、币种的字段号 */
    private static final int GIFT_V2_BLOCK = 10;
    private static final int BLOCK_ID = 1;
    private static final int BLOCK_NAME = 2;
    private static final int BLOCK_NUM = 3;
    private static final int BLOCK_COIN_TYPE = 8;

    /** 一包报文与它解析出的全部事件 */
    private record Replayed(JSONObject msg, List<NovaBaseLiveEvent> events) {
    }

    /** 归并器交给调度器的一件延时活，以及它有没有被撤 */
    private record Held(Runnable task, AtomicBoolean cancelled) {
    }

    @Test
    @DisplayName("每一条映射出来的信封都合规，序列化前后各校验一遍")
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void everyEnvelopePassesTheSchema() throws Exception {
        // 归并器发出的事件。GUARD_BUY 不由解析直接返回，只能从这里取
        List<NovaBaseLiveEvent> published = new CopyOnWriteArrayList<>();

        // 归并器的延时活交给手里的调度器，回放完由这边逐件跑完：宽限确定地走完，
        // 不靠睡眠，也不会因为机器慢漏收 GUARD_BUY
        List<Held> held = new ArrayList<>();
        BilibiliGuardReconciler reconciler = reconciler(
                event -> published.add((NovaBaseLiveEvent) event), heldScheduler(held));

        // 事件补全默认关闭，解析过程不会碰任何接口——语料回放必须是纯离线的
        BilibiliEventParser parser = new BilibiliEventParser(
                new NovaBilibiliProperties(), mock(BilibiliGiftService.class),
                mock(BilibiliApiSupport.class), reconciler);

        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> unmappedByType = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        long messages = 0;
        long unparsed = 0;
        long unmapped = 0;
        long envelopes = 0;
        long seq = 0;

        // v2 的两处语义，用真语料而不是构造样本来验
        long wholeEmoji = 0;        // emoji 非空（整条就是一张表情图）
        long inlineDanmaku = 0;     // inlineEmojis 非空
        long inlineItems = 0;
        long countMatched = 0;
        long countMismatched = 0;

        // 分两趟：先整批解析，再统一映射校验。
        // 不能边解析边收 GUARD_BUY——它要等满宽限期才发出，那时回放早结束了。
        // 解析走生产的 parseMessage：一包可以出几个事件（SEND_GIFT_V2 一包几块礼物），每个都要进回放
        List<Replayed> replayed = new ArrayList<>();
        List<NovaBaseLiveEvent> events = new ArrayList<>();
        Path corpus = Path.of(System.getProperty("novabot.corpus"));
        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                messages++;
                JSONObject entry = JSON.parseObject(line);
                long roomId = entry.getLongValue("room");
                LiveStreamerInfo source = new LiveStreamerInfo(1L, "主播", roomId);

                JSONObject msg = JSON.parseObject(entry.getString("msg"));
                List<NovaBaseLiveEvent> parsed = parser.parseMessage(msg, source).events();
                replayed.add(new Replayed(msg, parsed));
                if (parsed.isEmpty()) {
                    unparsed++;
                }
                events.addAll(parsed);
            }
        }

        // 回放完，把归并器压着的 GUARD_BUY 发完：等到 toast 的已被撤掉，其余的照宽限到期发出
        int drained = 0;
        for (Held task : held) {
            if (!task.cancelled().get()) {
                task.task().run();
                drained++;
            }
        }
        long lateFromReconciler = published.size();
        unparsed -= lateFromReconciler;      // 它们并不是「解析不出来」，只是迟到
        events.addAll(published);

        Map<NovaBaseLiveEvent, JSONObject> envelopeOf = new IdentityHashMap<>();
        for (NovaBaseLiveEvent event : events) {
            JSONObject envelope = NovaEventMapper.map(event);
            if (envelope == null) {
                // 映射器不输出的事件按类名记下来。这一栏有 4000 多个，
                // 「解析出来了但没送出去」如果不摊开看，漏送和有意不送长得一模一样
                unmapped++;
                unmappedByType.merge(event.getClass().getSimpleName(), 1, Integer::sum);
                continue;
            }
            envelopes++;
            // seq 由输出端点盖，映射器不管；校验要求它是正数，因此这里补上
            envelope.put("seq", ++seq);
            envelopeOf.put(event, envelope);
            byKind.merge(envelope.getString("kind"), 1, Integer::sum);

            collect(violations, envelope, "映射后");
            // 序列化再解回来一遍：数字类型、null、空表在这一步最容易变形。
            // **必须与真实出口用同一组 Feature**：fastjson2 默认丢弃空值，
            // 而协议要求「允许为 null，不允许不存在」。第一次跑这条测试时我图省事写了
            // toJSONString()，于是 17093 处「缺少 medal/emoji/replyTo」——
            // 那是测试自己漏了 WriteNulls，出口（NovaEventStream、NovaEventEndpoint）一直是带的
            collect(violations, JSON.parseObject(envelope.toString(JSONWriter.Feature.WriteNulls)),
                    "序列化后");

            if (!"danmaku".equals(envelope.getString("kind"))) {
                continue;
            }
            JSONObject data = envelope.getJSONObject("data");
            if (data.get("emoji") != null) {
                wholeEmoji++;
            }
            JSONArray inline = data.getJSONArray("inlineEmojis");
            if (inline == null || inline.isEmpty()) {
                continue;
            }
            inlineDanmaku++;
            inlineItems += inline.size();
            String text = data.getString("text");
            for (int i = 0; i < inline.size(); i++) {
                JSONObject item = inline.getJSONObject(i);
                String placeholder = item.getString("placeholder");
                int declared = item.getIntValue("count");
                int actual = occurrences(text, placeholder);
                if (declared == actual) {
                    countMatched++;
                } else {
                    countMismatched++;
                    violations.add("count 与正文出现次数不符: " + placeholder
                            + " 声明 " + declared + " 实际 " + actual);
                }
            }
        }

        // 礼物逐块核对：块从原始 pb 里另行读出，不借解析器的结果
        List<String> giftMismatches = new ArrayList<>();
        long giftV2Packets = 0;
        long multiBlockPackets = 0;
        long blocksChecked = 0;
        // 上舰核对：每一包 GUARD_BUY 的「谁 + 等级」都得有一条 guard 信封（自己发出的，或撤掉它的 toast 发出的）
        List<String> guardMisses = new ArrayList<>();
        long guardBuyPackets = 0;
        List<JSONObject> guardEnvelopes = new ArrayList<>();
        for (NovaBaseLiveEvent event : events) {
            JSONObject envelope = envelopeOf.get(event);
            if (envelope != null && "guard".equals(envelope.getString("kind"))) {
                guardEnvelopes.add(envelope);
            }
        }
        for (NovaBaseLiveEvent late : published) {
            JSONObject envelope = envelopeOf.get(late);
            if (envelope == null || !"guard".equals(envelope.getString("kind"))) {
                guardMisses.add("归并器发出的 " + late.getClass().getSimpleName() + " 没映射成 guard 信封");
            }
        }

        for (int index = 0; index < replayed.size(); index++) {
            Replayed packet = replayed.get(index);
            String cmd = cmdOf(packet.msg());
            if ("SEND_GIFT_V2".equals(cmd)) {
                giftV2Packets++;
                List<JSONObject> blocks = giftBlocks(packet.msg());
                if (blocks.size() > 1) {
                    multiBlockPackets++;
                }
                List<JSONObject> expected = blocks.stream()
                        .filter(block -> "gold".equals(block.getString("coin"))
                                || "silver".equals(block.getString("coin")))
                        .toList();
                List<JSONObject> actual = packet.events().stream().map(envelopeOf::get).toList();
                if (expected.size() != actual.size()) {
                    giftMismatches.add("第 " + (index + 1) + " 包 SEND_GIFT_V2 有 " + expected.size()
                            + " 块礼物，信封 " + actual.size() + " 条");
                    continue;
                }
                for (int i = 0; i < expected.size(); i++) {
                    blocksChecked++;
                    String mismatch = giftMismatch(expected.get(i), actual.get(i));
                    if (mismatch != null) {
                        giftMismatches.add("第 " + (index + 1) + " 包第 " + (i + 1) + " 块: " + mismatch);
                    }
                }
            } else if ("GUARD_BUY".equals(cmd)) {
                JSONObject data = packet.msg().getJSONObject("data");
                Long uid = data == null ? null : data.getLong("uid");
                Integer level = data == null ? null : data.getInteger("guard_level");
                if (uid == null || level == null) {
                    // 认不出是谁、什么等级的那几包，归并器按设计直接发或不发，这里不核
                    continue;
                }
                guardBuyPackets++;
                boolean found = guardEnvelopes.stream().anyMatch(envelope ->
                        String.valueOf(uid).equals(envelope.getJSONObject("user").getString("uid"))
                                && level.equals(envelope.getJSONObject("data").getInteger("level")));
                if (!found) {
                    guardMisses.add("第 " + (index + 1) + " 包 GUARD_BUY（等级 " + level + "）没有对应的 guard 信封");
                }
            }
        }

        // 「不受支持」这一栏里混着 GUARD_BUY——它被归并器按 payflow_id 判重丢掉了，
        // 那是有意为之，不是解析不出来。栏目名如实写成三种情形，别让读数的人以为都是漏解析
        System.out.printf("语料 %d 条报文 → 解析出事件 %d 个（其中归并器迟发 %d 个，压着 %d 件、到期发出 %d 件）"
                        + "→ 信封 %d 条；不受支持/解析不出/被归并器判重丢弃 %d 条、"
                        + "解析出但映射器不输出 %d 个%n",
                messages, events.size(), lateFromReconciler, held.size(), drained, envelopes, unparsed, unmapped);
        System.out.println("按类型: " + byKind);
        System.out.println("解析出但映射器不输出的事件: " + unmappedByType);
        System.out.printf("SEND_GIFT_V2 %d 包（其中多块 %d 包），逐块核对 %d 块、不符 %d 处; "
                        + "GUARD_BUY %d 包、guard 信封 %d 条、缺 %d 处%n",
                giftV2Packets, multiBlockPackets, blocksChecked, giftMismatches.size(),
                guardBuyPackets, guardEnvelopes.size(), guardMisses.size());
        System.out.printf("整条表情弹幕 %d 条; 内联表情弹幕 %d 条、共 %d 项; "
                        + "count 与正文吻合 %d 项、不吻合 %d 项%n",
                wholeEmoji, inlineDanmaku, inlineItems, countMatched, countMismatched);
        if (!violations.isEmpty()) {
            System.out.println("违例前 20 条:");
            violations.stream().limit(20).forEach(v -> System.out.println("  " + v));
        }

        assertTrue(envelopes > 0, "语料一条信封都没映射出来，先确认语料路径与格式");
        assertEquals(List.of(), giftMismatches.stream().limit(20).toList(),
                "SEND_GIFT_V2 的礼物块与信封对不上，共 " + giftMismatches.size() + " 处");
        assertEquals(List.of(), guardMisses.stream().limit(20).toList(),
                "上舰没有对应的 guard 信封，共 " + guardMisses.size() + " 处");
        assertEquals(List.of(), violations.stream().limit(20).toList(),
                "实抓语料映射出的信封违反协议，共 " + violations.size() + " 处");
    }

    /**
     * 带着手里调度器的归并器
     * <p>
     * 接调度器与宽限的那个构造器只对同包开放，这里按参数类型取来用；
     * 公开构造器配的是真定时器，回放时只能靠睡眠去等宽限期走完。
     */
    private static BilibiliGuardReconciler reconciler(ApplicationEventPublisher publisher,
                                                      ScheduledExecutorService scheduler) throws Exception {
        Constructor<BilibiliGuardReconciler> constructor = BilibiliGuardReconciler.class.getDeclaredConstructor(
                ApplicationEventPublisher.class, ScheduledExecutorService.class, Duration.class);
        constructor.setAccessible(true);
        return constructor.newInstance(publisher, scheduler, Duration.ofSeconds(5));
    }

    /**
     * 只收不跑的调度器：交来的延时活记进 {@code held}，撤销时记一笔，由测试决定何时跑
     */
    private static ScheduledExecutorService heldScheduler(List<Held> held) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            held.add(new Held(invocation.getArgument(0), cancelled));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            when(future.cancel(anyBoolean())).thenAnswer(cancel -> !cancelled.getAndSet(true));
            when(future.isCancelled()).thenAnswer(ask -> cancelled.get());
            return future;
        });
        return scheduler;
    }

    private static String cmdOf(JSONObject msg) {
        String cmd = msg == null ? null : msg.getString("cmd");
        if (cmd == null) {
            return null;
        }
        int colon = cmd.indexOf(':');
        return colon > 0 ? cmd.substring(0, colon) : cmd;
    }

    /**
     * 一块礼物与它的信封对不对得上：id、名、数量
     *
     * @return 对得上时为 null，否则写明哪一栏不符
     */
    private static String giftMismatch(JSONObject block, JSONObject envelope) {
        if (envelope == null) {
            return "没有信封";
        }
        if (!"gift".equals(envelope.getString("kind"))) {
            return "信封 kind 为 " + envelope.getString("kind");
        }
        JSONObject data = envelope.getJSONObject("data");
        if (!Objects.equals(block.getLong("id"), data.getLong("giftId"))) {
            return "giftId 块里 " + block.getLong("id") + " 信封 " + data.getLong("giftId");
        }
        if (!Objects.equals(block.getString("name"), data.getString("giftName"))) {
            return "giftName 与块里不符";
        }
        if (!Objects.equals(block.getLong("num"), data.getLong("num"))) {
            return "num 块里 " + block.getLong("num") + " 信封 " + data.getLong("num");
        }
        return null;
    }

    /**
     * 从 SEND_GIFT_V2 的原始 pb 里读出每一块礼物的 id、名、数量、币种
     * <p>
     * 自己逐字节读，不用解析器那一套：核对的对象不能拿被核对的代码去读。
     */
    private static List<JSONObject> giftBlocks(JSONObject msg) {
        JSONObject data = msg.getJSONObject("data");
        String pb = data == null ? null : data.getString("pb");
        if (pb == null || pb.isBlank()) {
            return List.of();
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(pb);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        List<JSONObject> blocks = new ArrayList<>();
        for (Object[] field : fields(payload)) {
            if ((int) field[0] != GIFT_V2_BLOCK || !(field[1] instanceof byte[] raw)) {
                continue;
            }
            JSONObject block = new JSONObject();
            for (Object[] inner : fields(raw)) {
                int number = (int) inner[0];
                if (number == BLOCK_ID && inner[1] instanceof Long id) {
                    block.put("id", id);
                } else if (number == BLOCK_NAME && inner[1] instanceof byte[] name) {
                    block.put("name", new String(name, StandardCharsets.UTF_8));
                } else if (number == BLOCK_NUM && inner[1] instanceof Long num) {
                    block.put("num", num);
                } else if (number == BLOCK_COIN_TYPE && inner[1] instanceof byte[] coin) {
                    block.put("coin", new String(coin, StandardCharsets.UTF_8));
                }
            }
            blocks.add(block);
        }
        return blocks;
    }

    /**
     * protobuf 一层的字段：{@code [字段号, 值]}，值为 varint 的 Long 或长度定界的 byte[]；
     * 定长 32/64 位的只跳过。读坏了就停在那里，已读到的照给
     */
    private static List<Object[]> fields(byte[] bytes) {
        List<Object[]> fields = new ArrayList<>();
        int[] at = {0};
        while (at[0] < bytes.length) {
            Long key = varint(bytes, at);
            if (key == null) {
                break;
            }
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                Long value = varint(bytes, at);
                if (value == null) {
                    break;
                }
                fields.add(new Object[]{number, value});
            } else if (wire == 2) {
                Long length = varint(bytes, at);
                if (length == null || length < 0 || at[0] + length > bytes.length) {
                    break;
                }
                byte[] value = new byte[(int) (long) length];
                System.arraycopy(bytes, at[0], value, 0, value.length);
                at[0] += value.length;
                fields.add(new Object[]{number, value});
            } else if (wire == 1) {
                at[0] += 8;
            } else if (wire == 5) {
                at[0] += 4;
            } else {
                break;
            }
        }
        return fields;
    }

    private static Long varint(byte[] bytes, int[] at) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (at[0] >= bytes.length) {
                return null;
            }
            byte b = bytes[at[0]++];
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return null;
    }

    private static void collect(List<String> sink, JSONObject envelope, String stage) {
        for (String violation : NovaProtocolSchema.violations(envelope)) {
            sink.add(stage + " " + envelope.getString("kind") + ": " + violation);
        }
    }

    /**
     * 占位符在正文里出现的次数。{@code String.indexOf} 逐个数，不用正则——占位符里带 {@code [] }
     */
    private static int occurrences(String text, String placeholder) {
        if (text == null || placeholder == null || placeholder.isEmpty()) {
            return 0;
        }
        int count = 0;
        int at = text.indexOf(placeholder);
        while (at >= 0) {
            count++;
            at = text.indexOf(placeholder, at + placeholder.length());
        }
        return count;
    }
}
