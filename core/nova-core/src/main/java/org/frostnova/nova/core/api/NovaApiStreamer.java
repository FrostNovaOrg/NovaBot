package org.frostnova.nova.core.api;

/**
 * 一位在册主播，{@code /me} 列出的就是这几项
 *
 * @param platform 直播平台
 * @param uid      主播 uid
 * @param name     名字，取不到时为 null
 */
public record NovaApiStreamer(String platform, long uid, String name) {
}
