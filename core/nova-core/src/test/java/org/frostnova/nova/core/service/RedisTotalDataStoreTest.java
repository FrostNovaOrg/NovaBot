package org.frostnova.nova.core.service;

import org.frostnova.nova.core.model.UserScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.zset.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisTotalDataStore 的累计、键形与一场并入的原子性
 * <p>
 * 累计的正确性（两场叠成一份、没并过的答零）与键形（带 {@code nb:} 前缀、按平台与主播分格）
 * 都只跟本类怎么用 Redis 有关，跟某个具体的 Redis 没关系——因此不装 Redis，
 * 用一个记在内存表里的假连接工厂。这套判据此前不存在：键形是 5.0 兼容的契约
 * （换存储实现不换键），而契约没有尺子时，改坏它不会红。
 * <p>
 * 键形直接对假 Redis 里的<b>原始键</b>下断言：经本类读回来只能证「读得到」，
 * 证不了「键长什么样」——键要是串了台，读的那条路跟写的那条路一起串。
 * <p>
 * 原子性的尺子造在假连接上：MULTI 之后写命令只排队不落表，EXEC 才整批生效。
 * 假的这一侧若只会立即生效，「排进去了但没提交」这种半份就造不出来，
 * 而半份正是要抓的用户故障——合并途中断一下，重并时前几项被多算一遍。
 */
@DisplayName("累计存储：两场叠成一份，一场要么全进要么全不进，键按平台与主播分格")
class RedisTotalDataStoreTest {
    /**
     * 假 Redis 的哈希表：键 → 字段 → 值（值统一按字符串存，与模板的序列化形态一致）
     */
    private final Map<String, Map<String, String>> hashes = new HashMap<>();

    /**
     * 假 Redis 的有序集合：键 → 成员 → 分值
     */
    private final Map<String, Map<String, Double>> zsets = new HashMap<>();

    /**
     * MULTI 之后、EXEC 之前排队的写命令：到 EXEC 才整批落表
     */
    private final List<Supplier<Object>> pendingWrites = new ArrayList<>();

    /**
     * 假连接当前是否在事务里（已收到 MULTI、还没收到 EXEC 或 DISCARD）
     */
    private boolean inTransaction = false;

    /**
     * 排到这个成员的得分写入时连接断开（空串表示不断）——「合并途中出错」一格的开关
     */
    private String failWhenWritingUser = "";

    private RedisTotalDataStore store;

    @BeforeEach
    void setUp() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);

        // 模板在 spring-data-redis 4.0 里不再从 hashCommands()/zSetCommands() 那两扇侧门走，
        // 而是直接调连接本体上的命令方法（DefaultedRedisConnection 的默认方法一族），
        // 所以内存表也挂在连接本体上——挂错地方的话，桩一次都不会被碰到，而模板不报错
        when(connection.hIncrBy(any(byte[].class), any(byte[].class), anyDouble()))
                .thenAnswer(invocation -> writeOrQueue(() -> {
                    Map<String, String> fields =
                            hashes.computeIfAbsent(str(invocation.getArgument(0)), k -> new HashMap<>());
                    String field = str(invocation.getArgument(1));
                    double before = Double.parseDouble(fields.getOrDefault(field, "0"));
                    double after = before + (Double) invocation.getArgument(2);
                    fields.put(field, String.valueOf(after));
                    return after;
                }));
        // hMSet 在 spring-data-redis 4.0 里是 void，只能用 doAnswer 这一支挂行为
        doAnswer(invocation -> {
            writeOrQueue(() -> {
                Map<String, String> fields =
                        hashes.computeIfAbsent(str(invocation.getArgument(0)), k -> new HashMap<>());
                invocation.<Map<byte[], byte[]>>getArgument(1)
                        .forEach((k, v) -> fields.put(str(k), str(v)));
                return null;
            });
            return null;
        }).when(connection).hMSet(any(byte[].class), any());
        // hSet/zAdd 也是真实的写口（单字段写、按分值放成员）：假 Redis 若只接 hIncrBy 一族，
        // 生产的写法哪天换成它们，写入会静默消失——读数照样是 0，而 0 与「没写过」长得一样
        when(connection.hSet(any(byte[].class), any(byte[].class), any(byte[].class))).thenAnswer(invocation ->
                writeOrQueue(() -> {
                    Map<String, String> fields =
                            hashes.computeIfAbsent(str(invocation.getArgument(0)), k -> new HashMap<>());
                    return fields.put(str(invocation.getArgument(1)), str(invocation.getArgument(2))) == null;
                }));
        when(connection.zAdd(any(byte[].class), anyDouble(), any(byte[].class))).thenAnswer(invocation ->
                writeOrQueue(() -> {
                    Map<String, Double> members =
                            zsets.computeIfAbsent(str(invocation.getArgument(0)), k -> new LinkedHashMap<>());
                    return members.put(str(invocation.getArgument(2)), (Double) invocation.getArgument(1)) == null;
                }));
        // 事务三口照真实 Redis 的语义挂：MULTI 之后写命令只排队，EXEC 才整批落表，DISCARD 全丢
        doAnswer(invocation -> {
            inTransaction = true;
            pendingWrites.clear();
            return null;
        }).when(connection).multi();
        doAnswer(invocation -> {
            inTransaction = false;
            List<Object> replies = new ArrayList<>();
            pendingWrites.forEach(write -> {
                write.get();
                replies.add(null);
            });
            pendingWrites.clear();
            return replies;
        }).when(connection).exec();
        doAnswer(invocation -> {
            inTransaction = false;
            pendingWrites.clear();
            return null;
        }).when(connection).discard();
        when(connection.hGet(any(byte[].class), any(byte[].class))).thenAnswer(invocation -> {
            // 桩的返回形态得跟接口一致（byte[]）：答成字符串的话 Mockito 在调用期抛类型不符，
            // 生产的读法把异常吞成 0——红的原因就成了另一个，键形那把尺还看不出来
            String value = hashes.getOrDefault(str(invocation.getArgument(0)), Map.of())
                    .get(str(invocation.getArgument(1)));
            return value == null ? null : bytes(value);
        });

        when(connection.zIncrBy(any(byte[].class), anyDouble(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    // 中途断开造在发出命令的那一刻：事务里是排队失败，直写时是前几笔已落表——
                    // 生产代码有没有把整场写进一个事务，两条路在这一步分岔
                    if (failWhenWritingUser.equals(str(invocation.getArgument(2)))) {
                        throw new IllegalStateException("假 Redis：写 " + failWhenWritingUser + " 的得分时连接断开");
                    }
                    return writeOrQueue(() -> {
                        Map<String, Double> members =
                                zsets.computeIfAbsent(str(invocation.getArgument(0)), k -> new LinkedHashMap<>());
                        double after = members.getOrDefault(str(invocation.getArgument(2)), 0.0)
                                + (Double) invocation.getArgument(1);
                        members.put(str(invocation.getArgument(2)), after);
                        return after;
                    });
                });
        when(connection.zScore(any(byte[].class), any(byte[].class))).thenAnswer(invocation ->
                zsets.getOrDefault(str(invocation.getArgument(0)), Map.of())
                        .get(str(invocation.getArgument(1))));
        when(connection.zCard(any(byte[].class))).thenAnswer(invocation ->
                (long) zsets.getOrDefault(str(invocation.getArgument(0)), Map.of()).size());
        when(connection.zRevRank(any(byte[].class), any(byte[].class))).thenAnswer(invocation -> {
            int index = descending(str(invocation.getArgument(0))).indexOf(str(invocation.getArgument(1)));
            return index < 0 ? null : (long) index;
        });
        when(connection.zRevRangeWithScores(any(byte[].class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String key = str(invocation.getArgument(0));
                    List<String> ordered = descending(key);
                    long start = invocation.getArgument(1);
                    long end = invocation.getArgument(2);
                    Set<Tuple> tuples = new LinkedHashSet<>();
                    for (long i = start; i <= Math.min(end, ordered.size() - 1L); i++) {
                        String member = ordered.get((int) i);
                        tuples.add(Tuple.of(bytes(member), zsets.get(key).get(member)));
                    }
                    return tuples;
                });

        store = new RedisTotalDataStore(factory);
    }

    /**
     * 同一键按分值降序排好的成员表，名次类读法都以它为准
     */
    private List<String> descending(String key) {
        return zsets.getOrDefault(key, Map.of()).entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 写命令走真实 Redis 的两条路：事务外立即生效，MULTI 之后先排队、EXEC 才生效
     */
    private Object writeOrQueue(Supplier<Object> write) {
        if (inTransaction) {
            pendingWrites.add(write);
            // 真实 Redis 在 MULTI 里对写命令只答 QUEUED，得数要等 EXEC 回包才有意义
            return null;
        }
        return write.get();
    }

    private RedisTotalDataStore.LiveSnapshot snapshot(Map<String, Double> metrics,
                                                      Map<String, Map<Long, Double>> userMetrics,
                                                      Map<Long, String> names,
                                                      Map<Long, String> faces) {
        return new RedisTotalDataStore.LiveSnapshot(metrics, userMetrics, names, faces);
    }

    /**
     * 原子性三格共用的那一份差量：三个指标、两个用户得分、两条昵称与头像。
     * 用户得分的顺序用 LinkedHashMap 钉死——先 7 后 8，「第二个用户得分」就是 8 那笔。
     */
    private RedisTotalDataStore.LiveSnapshot sessionDelta() {
        Map<Long, Double> danmuScores = new LinkedHashMap<>();
        danmuScores.put(7L, 1.5);
        danmuScores.put(8L, 4.0);
        return snapshot(Map.of("danmu", 10.0, "gift", 100.0, "enter", 3.0),
                Map.of("danmu", danmuScores),
                Map.of(7L, "观众甲", 8L, "观众乙"),
                Map.of(7L, "https://face/7", 8L, "https://face/8"));
    }

    @Test
    @DisplayName("⚠️ 两场并入同一份累计：读数是叠加，不是覆盖")
    void mergesAccumulateAcrossSessions() {
        store.merge("bilibili", 42L, snapshot(Map.of("danmu", 10.0),
                Map.of("danmu", Map.of(7L, 1.5)), Map.of(7L, "观众甲"), Map.of()));
        store.merge("bilibili", 42L, snapshot(Map.of("danmu", 3.0, "gift", 100.0),
                Map.of("danmu", Map.of(7L, 2.5, 8L, 4.0)), Map.of(), Map.of()));

        assertEquals(13.0, store.getTotalMetric("bilibili", 42L, "danmu"),
                "第二场的量该叠上去——累计是历次直播叠出来的，盖掉一场就少一场");
        assertEquals(100.0, store.getTotalMetric("bilibili", 42L, "gift"));
        assertEquals(4.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 7L),
                "同一用户两场的得分也该是累计，而不是只记最后一场");
        assertEquals(2, store.getTotalMetricUserCount("bilibili", 42L, "danmu"),
                "来了两个不同的用户，参与人数该是 2 而不是 3");
    }

    @Test
    @DisplayName("阴性：没并过的不编数——指标、得分、名次、榜都答零")
    void neverMergedReadsAsZero() {
        store.merge("bilibili", 42L, snapshot(Map.of("danmu", 10.0),
                Map.of("danmu", Map.of(7L, 1.5)), Map.of(), Map.of()));

        assertEquals(0.0, store.getTotalMetric("bilibili", 42L, "gift"),
                "没并过的指标答零——零与「没人送礼」长得一样，不能是 null 或异常");
        assertEquals(0.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 999L));
        assertEquals(0, store.getTotalUserRank("bilibili", 42L, "danmu", 999L),
                "没上榜名次答 0，不能与第 1 名（也是从这附近数出来的）混淆");
        assertEquals(List.of(), store.getTotalUserRanking("bilibili", 42L, "gift", 10));
    }

    @Test
    @DisplayName("⚠️ 键形钉死：四类键都带 nb: 前缀、按平台与主播分格")
    void keysAreShapedAsDocumented() {
        store.merge("bilibili", 42L, snapshot(Map.of("danmu", 1.0),
                Map.of("danmu", Map.of(7L, 2.0)), Map.of(7L, "观众甲"), Map.of(7L, "https://face/7")));

        assertEquals(Set.of("nb:total:bilibili:42", "nb:name:bilibili:42", "nb:face:bilibili:42"),
                hashes.keySet(), "哈希类键的形状——与 5.0 相同，换了实现也不许换键");
        assertEquals(Set.of("nb:total:user:bilibili:42:danmu"),
                zsets.keySet(), "有序集合类键的形状，指标名在最后一段");
    }

    @Test
    @DisplayName("阴性：同号主播换平台、同平台换主播号，累计互不串台")
    void noLeakAcrossPlatformsOrStreamers() {
        store.merge("bilibili", 42L, snapshot(Map.of("danmu", 10.0),
                Map.of("danmu", Map.of(7L, 5.0)), Map.of(7L, "观众甲"), Map.of()));
        store.merge("douyin", 42L, snapshot(Map.of("danmu", 99.0),
                Map.of("danmu", Map.of(7L, 50.0)), Map.of(7L, "抖音观众"), Map.of()));
        store.merge("bilibili", 99L, snapshot(Map.of("danmu", 77.0),
                Map.of(), Map.of(), Map.of()));

        assertEquals(10.0, store.getTotalMetric("bilibili", 42L, "danmu"),
                "隔壁平台的同号主播不该把量叠进来");
        assertEquals(99.0, store.getTotalMetric("douyin", 42L, "danmu"));
        assertEquals(77.0, store.getTotalMetric("bilibili", 99L, "danmu"));

        List<UserScore> ranking = store.getTotalUserRanking("bilibili", 42L, "danmu", 10);
        assertEquals(1, ranking.size(), "B 站那场的榜上不该出现抖音那场的同一个用户");
        assertEquals(Long.valueOf(7L), ranking.get(0).userUid());
    }

    @Test
    @DisplayName("⚠️ 合并途中断开：Redis 里一项也不留，返回失败")
    void brokenMergeLeavesNothingBehind() {
        failWhenWritingUser = "8";

        boolean merged = store.merge("bilibili", 42L, sessionDelta());

        assertFalse(merged, "中途断开的那次要报告失败——调用方要凭它决定整场重并");
        assertEquals(Map.of(), hashes,
                "没并完的一场不该在哈希里留下半份（指标、昵称、头像一项都没有）"
                        + "——留了前几项，重并时它们就被多算一遍");
        assertEquals(Map.of(), zsets, "有序集合里也不该有半份，断开之前排进去的得分同样不算数");
        assertEquals(0.0, store.getTotalMetric("bilibili", 42L, "danmu"),
                "指标读回零，与「这一场从没并过」长得一样，之后的重并才有整场可并");
        assertEquals(0.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 7L));
        assertEquals(0.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 8L));
    }

    @Test
    @DisplayName("⚠️ 断开恢复后重并：每一项正好一份，没有哪项是两倍")
    void remergeAfterBrokenOneCountsEachItemOnce() {
        failWhenWritingUser = "8";
        assertFalse(store.merge("bilibili", 42L, sessionDelta()),
                "先照上一格那样断一次：这一场一项也没并进去");
        failWhenWritingUser = "";

        boolean merged = store.merge("bilibili", 42L, sessionDelta());

        assertTrue(merged, "恢复后的重并该成功");
        assertEquals(10.0, store.getTotalMetric("bilibili", 42L, "danmu"),
                "重并后正好是差量的值，不是两倍——断掉的那次一项都不该留在里面");
        assertEquals(100.0, store.getTotalMetric("bilibili", 42L, "gift"));
        assertEquals(3.0, store.getTotalMetric("bilibili", 42L, "enter"));
        assertEquals(1.5, store.getTotalUserMetric("bilibili", 42L, "danmu", 7L),
                "断开之前先写的那个人也正好一份");
        assertEquals(4.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 8L));
    }

    @Test
    @DisplayName("对照：一切正常时一次并入，各项等于差量，排行与名次照旧")
    void normalMergeCountsDeltaOnceAndReadsBack() {
        boolean merged = store.merge("bilibili", 42L, sessionDelta());

        assertTrue(merged, "一次正常的合并要报成功");
        assertEquals(10.0, store.getTotalMetric("bilibili", 42L, "danmu"));
        assertEquals(100.0, store.getTotalMetric("bilibili", 42L, "gift"));
        assertEquals(3.0, store.getTotalMetric("bilibili", 42L, "enter"));
        assertEquals(1.5, store.getTotalUserMetric("bilibili", 42L, "danmu", 7L));
        assertEquals(4.0, store.getTotalUserMetric("bilibili", 42L, "danmu", 8L));

        List<UserScore> ranking = store.getTotalUserRanking("bilibili", 42L, "danmu", 10);
        assertEquals(2, ranking.size(), "排行照旧读得回：4 分在前、1.5 分在后");
        assertEquals(Long.valueOf(8L), ranking.get(0).userUid());
        assertEquals(4.0, ranking.get(0).score());
        assertEquals(Long.valueOf(7L), ranking.get(1).userUid());
        assertEquals("观众甲", ranking.get(1).userName(), "昵称随排行一起读得回来");
        assertEquals(1, store.getTotalUserRank("bilibili", 42L, "danmu", 8L), "名次照旧：8 分是第 1 名");
        assertEquals(2, store.getTotalUserRank("bilibili", 42L, "danmu", 7L));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
