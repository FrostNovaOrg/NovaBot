package org.frostnova.nova.core.util;

import java.util.*;

/**
 * 固定大小的集合队列(滑动窗口)
 * <p>
 * 「这条推过了吗」用它来答：进得早的先被挤出去，超出窗口的旧记录自然遗忘，
 * 内存不会随运行时长一直涨。
 * <p>
 * 队列之外另记一张<b>计数表</b>，问「在不在」时查表而不是遍历队列——
 * 动态推送每收到一条都要问一次，窗口有上千格，逐格比对会把这一问变成热点。
 * 同一个值进过几次就计几次，挤出一次减一次，减到零才从表里去掉：
 * 少了这层计数，重复进过的值被挤掉第一份时就会被误判成「没推过」。
 * @param <T> 元素类型
 */
public class FixedSizeSetQueue<T> {
    private final int capacity;

    /** 按进入顺序排，队首是最老的那个 */
    private final Deque<T> queue = new ArrayDeque<>();

    /** 值 → 它当前在队列里出现了几次 */
    private final Map<T, Integer> occurrences = new HashMap<>();

    public FixedSizeSetQueue(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 向容器添加元素
     * @param element 元素
     * @return 是否添加成功
     */
    public synchronized boolean add(T element) {
        // 先腾位再放：位子是先腾出来的，所以窗口任何时刻都不会超过容量
        if (queue.size() >= capacity) {
            forget(queue.pollFirst());
        }

        queue.offerLast(element);
        remember(element);

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
        return occurrences.containsKey(element);
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
        // 计数表要一起清: 只清队列会留下一批永远答「在」的幽灵
        occurrences.clear();
    }

    @Override
    public String toString() {
        return queue.toString();
    }

    private void remember(T element) {
        occurrences.merge(element, 1, Integer::sum);
    }

    /**
     * 计数减一，减到零就从表里去掉——留着零会让表随运行时长一直长
     */
    private void forget(T element) {
        occurrences.merge(element, -1, (count, delta) -> count + delta == 0 ? null : count + delta);
    }
}
