package com.starlwr.bot.core.service;

import com.starlwr.bot.core.analytics.LiveDetail;

import java.util.Optional;

/**
 * 从明细重画一场下播报告的扩展点
 * <p>
 * 报告图是缓存（见 {@link LiveReportArchive}），过期就删；而明细是原始数据，永久留着。
 * 缓存没了还要看得到那张图，靠的就是这一支：<b>把明细喂回画报告的那套代码，重画一张</b>。
 * <p>
 * <b>为什么是扩展点而不是核心自己画</b>：报告长什么样是平台的事——
 * 哪几条曲线、哪几张榜、金额怎么写，核心一概不知道，它只知道「这一场的数据在这儿」。
 * 与 {@link com.starlwr.bot.core.analytics.LiveMetricCatalog} 同一条分工。
 * <p>
 * 没有对应平台的实现时，过期的报告就只是看不到了——<b>不会画错，只会没有</b>。
 */
public interface LiveReportRedrawer {
    /**
     * 本实现适用的直播平台
     * @return 平台名，如 bilibili
     */
    String platform();

    /**
     * 从明细重画一张报告
     * <p>
     * ⚠️ <b>重画出来的图与当时发出去的那张并不逐像素相同</b>，实现须照此办：
     * 封面、头像、当前粉丝数这几样当时是向平台现取的，明细里没有，
     * 事后再取到的是<b>今天的</b>值而不是那一场的——
     * 与其画一张「数据是去年的、粉丝数是今天的」的图，不如那几块空着。
     * <p>
     * 数据面（曲线、榜单、词云、缺口、标题轨迹）则必须与当时一致，那正是明细留下来的东西。
     * @param detail 本场明细
     * @return PNG 字节，画不出来时为空
     */
    Optional<byte[]> redraw(LiveDetail detail);
}
