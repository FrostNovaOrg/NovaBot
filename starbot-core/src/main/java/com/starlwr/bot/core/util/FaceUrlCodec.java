package com.starlwr.bot.core.util;

/**
 * 头像地址的存储编码
 * <p>
 * 头像地址随计分一并记录，一个活跃直播间累计下来可达十万条。实测哔哩哔哩的头像地址
 * 平均 69 字符，其中 {@code https://iN.hdslb.com/bfs/face/} 这段前缀就占了 30 字符——
 * 按十万用户算，光这段重复前缀在累计存储里就是约 3 MB／每个主播，而累计数据不会清零。
 * <p>
 * 因此存储时把前缀压成「CDN 编号 + 冒号」，读取时还原：
 * <pre>
 * https://i0.hdslb.com/bfs/face/abc.jpg  ←→  0:abc.jpg
 * </pre>
 * <b>CDN 编号必须保留</b>：哔哩哔哩实际会下发 i0/i1/i2 多个镜像，砍成固定前缀等于
 * 替服务端决定用哪个节点。
 * <p>
 * <b>这是一个「存进去的不是原值」的约定，容易在后续维护中踩坑</b>，所以：
 * 认不出的地址一律原样存放，且 {@link #expand} 对原样存放的值是幂等的——
 * 升级前已经存下的完整地址不必迁移，照样读得回来。
 */
public final class FaceUrlCodec {
    private static final String PREFIX = "https://i";

    private static final String INFIX = ".hdslb.com/bfs/face/";

    private FaceUrlCodec() {
    }

    /**
     * 压缩头像地址以便存储
     * @param url 原始地址
     * @return 压缩后的存储形态；不符合已知格式时原样返回
     */
    public static String compact(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return url;
        }

        int infixAt = url.indexOf(INFIX, PREFIX.length());
        // CDN 编号必须恰好是一位数字，否则说明地址格式与预期不符，不冒险改写
        if (infixAt != PREFIX.length() + 1 || !Character.isDigit(url.charAt(PREFIX.length()))) {
            return url;
        }

        return url.charAt(PREFIX.length()) + ":" + url.substring(infixAt + INFIX.length());
    }

    /**
     * 还原被压缩的头像地址
     * @param stored 存储形态
     * @return 可直接用于下载的完整地址；非压缩形态时原样返回
     */
    public static String expand(String stored) {
        if (stored == null || stored.length() < 2 || stored.charAt(1) != ':'
                || !Character.isDigit(stored.charAt(0))) {
            return stored;
        }

        return PREFIX + stored.charAt(0) + INFIX + stored.substring(2);
    }
}
