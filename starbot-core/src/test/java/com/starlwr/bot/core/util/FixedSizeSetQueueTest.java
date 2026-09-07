package com.starlwr.bot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑动窗口去重容器
 *
 * <h2>为什么会有这组判据</h2>
 * 动态推送靠它记「这条已经推过了」（{@code BilibiliDynamicService} 里那个 1000 格的窗口）。
 * 它答错一次的后果不是报错，是<b>同一条动态推第二遍</b>或者<b>该推的不推</b>——
 * 两种都不会有任何异常，只会出现在用户的群里。
 * <p>
 * 这里量的是「现在是什么样」，不是「应该是什么样」：包括那些明显是毛病的边角
 * （见「容量为零」一节），改写时必须原样留着。
 */
@DisplayName("滑动窗口去重容器")
class FixedSizeSetQueueTest {
    @Nested
    @DisplayName("窗口滑动")
    class Sliding {
        @Test
        @DisplayName("装满之前每个进去的都在")
        void keepsEverythingBelowCapacity() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.add("a");
            queue.add("b");

            assertEquals(2, queue.size());
            assertTrue(queue.contains("a"));
            assertTrue(queue.contains("b"));
            assertFalse(queue.contains("c"), "没进去过的不许说在");
        }

        @Test
        @DisplayName("装满后再进新的，最老的那个被挤出去")
        void evictsOldestOnceFull() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.addAll(List.of("a", "b", "c"));

            queue.add("d");

            assertEquals(3, queue.size(), "容量是上限, 不许越界");
            assertFalse(queue.contains("a"), "最先进来的应当被挤出");
            assertTrue(queue.contains("b"));
            assertTrue(queue.contains("c"));
            assertTrue(queue.contains("d"));
        }

        @Test
        @DisplayName("挤出去的是最先进来的那个，不是最先出现的那个值")
        void evictsByArrivalOrderNotByValue() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.addAll(Arrays.asList("a", "b", "a"));

            // 队列里现在是 a b a：挤掉的是队首那个 a，不是「第一个不重复的值」b
            queue.add("c");

            assertEquals(3, queue.size());
            assertTrue(queue.contains("b"), "b 比队首那个 a 后进来, 不该先走");
            assertTrue(queue.contains("a"), "同一个值进过两次, 挤掉一份之后它仍在窗口里");
            assertTrue(queue.contains("c"));
        }

        @Test
        @DisplayName("重复的值要挤够次数才算不在")
        void duplicateLeavesOnlyAfterAllCopiesEvicted() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.addAll(Arrays.asList("a", "b", "a"));

            queue.add("c");
            assertTrue(queue.contains("a"), "挤掉了第一份, 还剩一份, 仍算在");

            queue.add("d");
            assertTrue(queue.contains("a"), "这一次挤的是 b, 第二份 a 还在窗口里");

            queue.add("e");
            assertFalse(queue.contains("a"), "两份都被挤出后才算不在");
        }

        /**
         * 🔴 阳性对照：这把尺子分得出「在」和「不在」
         * <p>
         * 少了它，上面几条在 {@code contains} 恒真时全是绿的。
         */
        @Test
        @DisplayName("判据自己先能分出在与不在")
        void theRulerTellsPresentFromAbsent() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(2);
            assertFalse(queue.contains("a"), "空容器里什么都不在");
            queue.add("a");
            assertTrue(queue.contains("a"));
        }
    }

    @Nested
    @DisplayName("批量与清空")
    class BulkAndClear {
        @Test
        @DisplayName("批量进的比容量还多时，只留最后那一截")
        void bulkAddKeepsTail() {
            FixedSizeSetQueue<Integer> queue = new FixedSizeSetQueue<>(2);
            queue.addAll(List.of(1, 2, 3, 4, 5));

            assertEquals(2, queue.size());
            assertTrue(queue.contains(4));
            assertTrue(queue.contains(5));
            assertFalse(queue.contains(3));
        }

        @Test
        @DisplayName("add 与 addAll 一律答成功")
        void addAlwaysReportsSuccess() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(1);
            assertTrue(queue.add("a"));
            assertTrue(queue.add("a"), "重复的值也答成功, 返回值不是「有没有去重」的信号");
            assertTrue(queue.addAll(List.of()), "空集合也答成功");
        }

        @Test
        @DisplayName("清空之后什么都不在")
        void clearEmptiesBothSides() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.addAll(List.of("a", "b"));

            queue.clear();

            assertEquals(0, queue.size());
            assertFalse(queue.contains("a"), "清空要连索引一起清, 只清队列会留下永远说「在」的幽灵");
            assertFalse(queue.contains("b"));
        }

        @Test
        @DisplayName("toString 给出队列内容与顺序")
        void toStringShowsQueueContents() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
            queue.addAll(List.of("a", "b"));

            assertEquals("[a, b]", queue.toString());
        }
    }

    @Nested
    @DisplayName("容量为零")
    class ZeroCapacity {
        /**
         * 容量 0 并不等于「什么都不留」。现码是「先挤后进」，空队列上 pollFirst 得到 null，
         * 于是每次 add 都会先挤掉上一个、再放进新的——窗口实际是 1 而不是 0。
         * <p>
         * 这里把它钉住不是说它对，是因为改写时很容易顺手「修好」它而无人察觉。
         * 真要改，得先弄清有没有人用 0 当「关掉去重」的开关。
         */
        @Test
        @DisplayName("容量零时窗口实际是一，不是零")
        void zeroCapacityStillKeepsOne() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(0);

            queue.add("a");
            assertEquals(1, queue.size());
            assertTrue(queue.contains("a"));

            queue.add("b");
            assertEquals(1, queue.size(), "仍是 1, 因为挤的动作发生在放之前");
            assertFalse(queue.contains("a"));
            assertTrue(queue.contains("b"));
        }

        /**
         * 同一处的第二个副作用：空队列上挤出来的 null 也被记进了索引。
         * 与上一条一同钉住，改写时两者一起看。
         */
        @Test
        @DisplayName("容量零时索引里会留下一个 null 记号")
        void zeroCapacityRecordsNullKey() {
            FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(0);
            queue.add("a");

            assertTrue(queue.contains(null), "现码把挤出来的 null 也记了一笔, 这是现状");
        }
    }
}
