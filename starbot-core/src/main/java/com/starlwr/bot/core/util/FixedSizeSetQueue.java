package com.starlwr.bot.core.util;

import java.util.*;

/**
 * 固定大小的集合队列(滑动窗口)
 * @param <T> 元素类型
 */
public class FixedSizeSetQueue<T> {
    private final int capacity;
    private final Deque<T> queue = new ArrayDeque<>();
    private final Map<T, Integer> map = new HashMap<>();

    public FixedSizeSetQueue(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 向容器添加元素
     * @param element 元素
     * @return 是否添加成功
     */
    public synchronized boolean add(T element) {
        if (queue.size() >= capacity) {
            T oldest = queue.pollFirst();
            map.merge(oldest, -1, (oldVal, v) -> (oldVal + v == 0) ? null : oldVal + v);
        }

        queue.offerLast(element);
        map.merge(element, 1, Integer::sum);

        return true;
    }

    /**
     * 向容器批量添加元素
     * @param elements 元素集合
     * @return 是否添加成功
     */
    public synchronized boolean addAll(Collection<? extends T> elements) {
        for (T element : elements) {
            add(element);
        }

        return true;
    }

    /**
     * 检查容器中是否包含指定元素
     * @param element 元素
     * @return 容器中是否存在该元素
     */
    public boolean contains(T element) {
        return map.containsKey(element);
    }

    /**
     * 获取容器中的元素数量
     * @return 容器中的元素数量
     */
    public int size() {
        return queue.size();
    }

    /**
     * 清空容器
     */
    public synchronized void clear() {
        queue.clear();
        map.clear();
    }

    @Override
    public String toString() {
        return queue.toString();
    }
}
