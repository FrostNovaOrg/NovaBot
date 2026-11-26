package org.frostnova.nova.core.api;

import java.util.List;

/**
 * 列表类接口的一页
 * <p>
 * 序列化为 {@code {"items":[...],"next":"<游标>"}}；没有下一页时 {@code next} 为 null，
 * 而且这个字段一定在，不会因为是 null 就被省掉。
 *
 * @param items 这一页的条目
 * @param next  取下一页用的游标，没有下一页为 null
 */
public record NovaApiPage(List<?> items, String next) {
    public NovaApiPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static NovaApiPage of(List<?> items, String next) {
        return new NovaApiPage(items, next);
    }

    public static NovaApiPage last(List<?> items) {
        return new NovaApiPage(items, null);
    }
}
