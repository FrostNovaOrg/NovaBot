package org.frostnova.nova.core.analytics;

/**
 * 本场收到的一种礼物
 *
 * @param id 礼物 id，缺失时为空，这时按名字归并
 * @param name 礼物名
 * @param price 单价，单位元
 * @param count 累计个数
 * @param url 图标地址，没有时为空
 */
public record LiveGiftTotal(Long id, String name, double price, int count, String url) {
}
