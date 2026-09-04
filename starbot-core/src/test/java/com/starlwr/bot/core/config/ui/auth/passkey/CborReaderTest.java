package com.starlwr.bot.core.config.ui.auth.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
