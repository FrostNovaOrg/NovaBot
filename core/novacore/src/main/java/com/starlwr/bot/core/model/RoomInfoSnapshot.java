package com.starlwr.bot.core.model;

/**
 * 某一时刻的直播间标题与分区
 *
 * @param at 时刻（毫秒）
 * @param title 标题
 * @param area 分区描述，形如「娱乐 · 视频聊天」，取不到时为空字符串
 */
public record RoomInfoSnapshot(long at, String title, String area) {
}
