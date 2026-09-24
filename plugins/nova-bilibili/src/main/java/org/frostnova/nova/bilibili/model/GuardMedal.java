package org.frostnova.nova.bilibili.model;

import java.awt.Color;

/**
 * 大航海名单上的一枚粉丝牌
 *
 * @param name 牌名
 * @param level 牌子等级
 * @param lit 是否点亮；未点亮的牌子在报告里画成灰色
 * @param start 底色渐变的起始色，含透明度
 * @param end 底色渐变的结束色，含透明度
 * @param border 描边色，含透明度
 * @param text 牌名与等级的字色，含透明度
 * @param guardIcon 接口给的大航海标志地址；没给时为空，画的时候改用默认图
 */
public record GuardMedal(String name, int level, boolean lit,
                         Color start, Color end, Color border, Color text,
                         String guardIcon) {
}
