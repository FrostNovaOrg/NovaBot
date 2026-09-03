package com.starlwr.bot.core.util;

import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 集合工具类
 */
public class CollectionUtil {
    /**
     * 将集合拆分为多个子列表，每个子列表最多含有 size 个元素
     *
     * @param collection 原始集合
     * @param size 每个子列表的最大元素数量
     * @return 拆分后的子列表
     */
    public static <T> List<List<T>> splitCollection(Collection<T> collection, int size) {
        if (CollectionUtils.isEmpty(collection)) {
            return Collections.emptyList();
        }

        if (size <= 0) {
            throw new IllegalArgumentException("子列表的最大元素数量必须大于 0");
        }

        AtomicInteger counter = new AtomicInteger(0);
        return new ArrayList<>(collection.stream()
                .collect(Collectors.groupingBy(
                        item -> counter.getAndIncrement() / size,
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .values());
    }

    /**
     * 比较两个集合的差异
     * @param oldCollection 原集合
     * @param newCollection 新集合
     * @param added 输出变量，新增的元素
     * @param removed 输出变量，删除的元素
     * @param updated 输出变量，更新的元素
     */
    public static <T> void compareCollectionDiff(Collection<T> oldCollection, Collection<T> newCollection, Collection<T> added, Collection<T> removed, Collection<T> updated) {
        Set<T> oldSet = new HashSet<>(oldCollection);
        Set<T> newSet = new HashSet<>(newCollection);

        for (T item : newSet) {
            if (!oldSet.contains(item)) {
                added.add(item);
            } else {
                updated.add(item);
            }
        }

        for (T item : oldSet) {
            if (!newSet.contains(item)) {
                removed.add(item);
            }
        }
    }
}
