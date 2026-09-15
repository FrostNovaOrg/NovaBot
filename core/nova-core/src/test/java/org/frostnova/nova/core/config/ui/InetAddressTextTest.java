package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监听地址斜杠形态的收法
 * <p>
 * 例表与前端夹具共用：Java {@link InetAddressText#fromFile}
 * 与 JS {@code canonicalValue} 必须逐条给出同一答案，分叉即红。
 */
@DisplayName("监听地址文本归一")
class InetAddressTextTest {
    private static final String TABLE = "/inet-address-text.json";

    @Test
    @DisplayName("fromFile 与共用例表逐条相同")
    void fromFileMatchesSharedTable() throws Exception {
        JSONArray table;
        try (InputStream in = InetAddressTextTest.class.getResourceAsStream(TABLE)) {
            assertNotNull(in, "例表不见了: " + TABLE);
            table = JSON.parseArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < table.size(); i++) {
            JSONObject row = table.getJSONObject(i);
            String input = row.getString("in");
            String want = row.getString("out");
            try {
                assertEquals(want, InetAddressText.fromFile(input), input);
            } catch (AssertionError e) {
                bad.add(input + " → " + want + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), "例表未销 " + bad.size() + " 条: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("掩码形态直呼 fromFile 原样")
    void fromFileDoesNotRewriteMask() {
        assertEquals("10.0.0.0/255.255.255.0",
                InetAddressText.fromFile("10.0.0.0/255.255.255.0"));
    }

    @Test
    @DisplayName("后处理器与读口共用同一份地址键")
    void addressKeysSharedWithReadPath() {
        assertEquals(Set.of("server.address", "management.server.address"),
                InetAddressText.ADDRESS_KEYS);
        for (String key : InetAddressText.ADDRESS_KEYS) {
            assertTrue(ConfigurationFileService.isAddressKey(key), key);
        }
    }
}
