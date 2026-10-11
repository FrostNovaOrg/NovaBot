package org.frostnova.nova.core.lang;

import java.util.*;

/**
 * 集合工具类
 */
public class CollectionUtil {
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
