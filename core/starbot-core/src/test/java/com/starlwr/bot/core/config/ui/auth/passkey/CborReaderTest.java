package com.starlwr.bot.core.config.ui.auth.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("CBOR 读取器")
class CborReaderTest {
    @Test
    @DisplayName("字典里重复的键按不合规范拒收，首值是 null 的重复也不例外")
    void rejectsDuplicateMapKeysEvenWhenFirstValueIsNull() {
        // a2 = 两键字典；01 f6 = 键 1 → null；01 02 = 又一个键 1 → 2。
        // 首值是 null 时，靠 put 的返回值判重看不见这第二份键 1，整个字典会被当成「后者覆盖前者」收下
        byte[] duplicated = new byte[]{(byte) 0xa2, 0x01, (byte) 0xf6, 0x01, 0x02};

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CborReader.readMap(duplicated));
        assertTrue(error.getMessage().contains("重复的键"), () -> "应当报重复的键，实际报的是: " + error.getMessage());

        // 键不重复的字典照常读出——拒收的是重复本身，不是字典
        Map<Object, Object> distinct = assertDoesNotThrow(() -> CborReader.readMap(new byte[]{(byte) 0xa2, 0x01, 0x02, 0x03, 0x04}));
        assertEquals(2L, distinct.get(1L));
        assertEquals(4L, distinct.get(3L));
    }

    @Test
    @DisplayName("嵌套到上限那层照读，再深一层拒收")
    void readsTheDeepestAllowedNestingAndRejectsOneMore() {
        int reds = 0;
        List<String> failures = new ArrayList<>();

        // 问零（常量卫）：上限在本格里写死 16，锚在 CborReader 的 MAX_DEPTH 上；
        // 主码哪天改了上限，这一问先红，免得这格跟着新上限静默换锚
        try {
            Field maxDepth = CborReader.class.getDeclaredField("MAX_DEPTH");
            maxDepth.setAccessible(true);
            assertEquals(16, maxDepth.getInt(null), "MAX_DEPTH 应仍为 16，本格的层数锚在它上面");
        } catch (AssertionError e) {
            reds++;
            failures.add("问零: " + e.getMessage());
        } catch (ReflectiveOperationException e) {
            reds++;
            failures.add("问零: 读不到 MAX_DEPTH: " + e);
        }

        // ① 阳性锚：16 层单元素数组，按 read(depth) 的计法叶子恰落在 depth 16＝上限，应一层不少地照读
        try {
            Object current = assertDoesNotThrow(() -> new CborReader(nestedArrays(16)).read(),
                    "16 层（叶子在 depth 16）应照读");
            for (int level = 0; level < 16; level++) {
                Object here = current;
                int at = level;
                assertTrue(here instanceof List, () -> "第 " + at + " 层应是数组，实际: " + here);
                current = ((List<?>) here).get(0);
            }
            assertEquals(1L, current, "16 层数组之后应原样读到叶子整数 1");
        } catch (AssertionError e) {
            reds++;
            failures.add("①: " + e.getMessage() + (e.getCause() != null ? "（因: " + e.getCause() + "）" : ""));
        }

        // ② 再深一层（叶子在 depth 17＝上限＋1）拒收，且报的就是嵌套过深，不是别的病
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new CborReader(nestedArrays(17)).read());
            assertTrue(error.getMessage().contains("嵌套过深"), () -> "应当报嵌套过深，实际报的是: " + error.getMessage());
        } catch (AssertionError e) {
            reds++;
            failures.add("②: " + e.getMessage());
        }

        if (!failures.isEmpty()) {
            fail("红 " + reds + " 格: " + String.join("; ", failures));
        }
    }

    /**
     * n 层单元素数组套一个整数 1：n 个 0x81（＝0x80|1，单元素数组头）之后接 0x01。
     * 按被测 read(depth) 的计法，顶层数组是 depth 0，叶子读于 depth n——n=16 恰在上限、n=17 越限一层。
     */
    private static byte[] nestedArrays(int levels) {
        byte[] data = new byte[levels + 1];
        Arrays.fill(data, 0, levels, (byte) 0x81);
        data[levels] = 0x01;
        return data;
    }
}
