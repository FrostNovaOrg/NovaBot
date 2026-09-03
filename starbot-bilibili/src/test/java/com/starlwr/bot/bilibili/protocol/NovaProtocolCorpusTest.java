package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliApiSupport;
import com.starlwr.bot.bilibili.service.BilibiliEventParser;
import com.starlwr.bot.bilibili.service.BilibiliGiftService;
import com.starlwr.bot.bilibili.service.BilibiliGuardReconciler;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.protocol.NovaProtocolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    @Test
    @DisplayName("每一条映射出来的信封都合规，序列化前后各校验一遍")
    void everyEnvelopePassesTheSchema() throws IOException {
        // 归并器发出的事件。GUARD_BUY 不由 parse 返回，只能从这里取。
        // 它由归并器的后台线程发出，因此这个表必须是并发安全的
        List<StarBotBaseLiveEvent> published = new CopyOnWriteArrayList<>();

        // 用公开构造器，即真定时器与真宽限期（5 秒）——GUARD_BUY 要等 toast，
        // 语料几毫秒就放完了，所以回放结束后必须再等一等把迟到的那批收进来
        BilibiliGuardReconciler reconciler =
                new BilibiliGuardReconciler(event -> published.add((StarBotBaseLiveEvent) event));

        // 事件补全默认关闭，解析过程不会碰任何接口——语料回放必须是纯离线的
        BilibiliEventParser parser = new BilibiliEventParser(
                new StarBotBilibiliProperties(), mock(BilibiliGiftService.class),
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
        // 不能边解析边收 GUARD_BUY——它要等满宽限期才发出，那时回放早结束了
        List<StarBotBaseLiveEvent> events = new ArrayList<>();
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

                Optional<StarBotBaseLiveEvent> parsed =
                        parser.parse(JSON.parseObject(entry.getString("msg")), source);
                if (parsed.isPresent()) {
                    events.add(parsed.get());
                } else {
                    unparsed++;
                }
            }
        }

        // 等归并器把压着的 GUARD_BUY 发完。**宁可多等也不能漏**：漏掉就会把
        // 「没映射出信封」错报成「不受支持的消息」，而这两件事的含义完全不同
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        int settled = -1;
        while (System.nanoTime() < deadline && settled != published.size()) {
            settled = published.size();
            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long lateFromReconciler = published.size();
        unparsed -= lateFromReconciler;      // 它们并不是「解析不出来」，只是迟到
        events.addAll(published);

        for (StarBotBaseLiveEvent event : events) {
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

        // 「不受支持」这一栏里混着 GUARD_BUY——它被归并器按 payflow_id 判重丢掉了，
        // 那是有意为之，不是解析不出来。栏目名如实写成三种情形，别让读数的人以为都是漏解析
        System.out.printf("语料 %d 条报文 → 解析出事件 %d 个（其中归并器迟发 %d 个）"
                        + "→ 信封 %d 条；不受支持/解析不出/被归并器判重丢弃 %d 条、"
                        + "解析出但映射器不输出 %d 个%n",
                messages, events.size(), lateFromReconciler, envelopes, unparsed, unmapped);
        System.out.println("按类型: " + byKind);
        System.out.println("解析出但映射器不输出的事件: " + unmappedByType);
        System.out.printf("整条表情弹幕 %d 条; 内联表情弹幕 %d 条、共 %d 项; "
                        + "count 与正文吻合 %d 项、不吻合 %d 项%n",
                wholeEmoji, inlineDanmaku, inlineItems, countMatched, countMismatched);
        if (!violations.isEmpty()) {
            System.out.println("违例前 20 条:");
            violations.stream().limit(20).forEach(v -> System.out.println("  " + v));
        }

        assertTrue(envelopes > 0, "语料一条信封都没映射出来，先确认语料路径与格式");
        assertEquals(List.of(), violations.stream().limit(20).toList(),
                "实抓语料映射出的信封违反协议，共 " + violations.size() + " 处");
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
