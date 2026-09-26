package org.frostnova.nova.bilibili.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页「每行一项」的文本框交来的一份名单
 * <p>
 * 词云屏蔽名单与动态屏蔽词走的是同一个控件，读法也只有这一份：
 * 按行拆、去首尾空白、空行不算。两处各写一遍的话，改了一处的空行处理，
 * 另一处就会开始把空行当成一个空名字——而空名字在比对那头是「到处都命中」。
 */
final class LineLists {
    private LineLists() {
    }

    /**
     * 拆出名单
     * @param value 换行分隔的各项，空表示清空
     * @return 去掉空行后的名单
     */
    static List<String> parse(String value) {
        List<String> items = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return items;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }
}
