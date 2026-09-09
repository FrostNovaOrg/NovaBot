package org.frostnova.nova.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像地址存储编码测试
 * <p>
 * 这是个「存进去的不是原值」的约定，一旦压缩与还原对不上，排行榜上的头像就会
 * 集体消失而没有任何报错——因此边界情形要逐个钉死。
 */
@DisplayName("头像地址存储编码")
class FaceUrlCodecTest {
    @Test
    @DisplayName("常见地址应压掉前缀，且能原样还原")
    void roundTrip() {
        String url = "https://i0.hdslb.com/bfs/face/00112233445566778899aabbccddeeff00112233.jpg";

        String compact = FaceUrlCodec.compact(url);

        assertEquals("0:00112233445566778899aabbccddeeff00112233.jpg", compact);
        assertEquals(url, FaceUrlCodec.expand(compact));
    }

    @Test
    @DisplayName("CDN 编号必须保留，不能统一成某一个节点")
    void keepsCdnIndex() {
        // i0/i1/i2 是不同镜像，砍成固定前缀等于替服务端决定用哪个节点
        for (String host : new String[]{"i0", "i1", "i2"}) {
            String url = "https://" + host + ".hdslb.com/bfs/face/abc.jpg";
            assertEquals(url, FaceUrlCodec.expand(FaceUrlCodec.compact(url)));
        }
        assertEquals("1:abc.jpg", FaceUrlCodec.compact("https://i1.hdslb.com/bfs/face/abc.jpg"));
    }

    @Test
    @DisplayName("每条固定省下 28 个字符，即那段前缀的长度")
    void savesFixedPrefixLength() {
        // 省下的是恒定值：30 字符的前缀换成 2 字符的编号。
        // 折算成比例则随路径长短而变——哈希型地址约省 38%，短路径可达 60%，
        // 别把某一个样本的比例当成普遍结论
        for (String url : new String[]{
                "https://i0.hdslb.com/bfs/face/00112233445566778899aabbccddeeff00112233.jpg",
                "https://i0.hdslb.com/bfs/face/member/noface.jpg"}) {
            assertEquals(28, url.length() - FaceUrlCodec.compact(url).length(), url);
        }
    }

    @Test
    @DisplayName("不认识的地址原样存放")
    void leavesUnknownUrlsAlone() {
        for (String url : new String[]{
                "https://example.com/avatar.png",
                "http://i0.hdslb.com/bfs/face/abc.jpg",      // 非 https
                "https://ix.hdslb.com/bfs/face/abc.jpg",     // 编号不是数字
                "https://i10.hdslb.com/bfs/face/abc.jpg",    // 编号不止一位
                "https://i0.hdslb.com/bfs/other/abc.jpg"}) { // 路径不同
            assertEquals(url, FaceUrlCodec.compact(url), url);
        }
    }

    @Test
    @DisplayName("还原对未压缩的值是幂等的，升级前存下的完整地址不必迁移")
    void expandIsIdempotentForRawUrls() {
        String url = "https://i0.hdslb.com/bfs/face/abc.jpg";

        assertEquals(url, FaceUrlCodec.expand(url));
        assertEquals("https://example.com/a.png", FaceUrlCodec.expand("https://example.com/a.png"));
    }

    @Test
    @DisplayName("空值与畸形输入不应抛异常")
    void handlesNullAndShortInput() {
        assertNull(FaceUrlCodec.compact(null));
        assertNull(FaceUrlCodec.expand(null));
        assertEquals("", FaceUrlCodec.expand(""));
        assertEquals("0", FaceUrlCodec.expand("0"));
        // 「0:」是合法的压缩态，只是路径为空，还原成裸前缀即可，不该崩
        assertEquals("https://i0.hdslb.com/bfs/face/", FaceUrlCodec.expand("0:"));
    }
}
