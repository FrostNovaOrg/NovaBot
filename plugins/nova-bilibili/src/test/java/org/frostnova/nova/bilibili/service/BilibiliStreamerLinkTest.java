package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.StreamerReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 认本平台的主播链接
 * <p>
 * 这两条模式原先写在控制台的查主播口里，那等于核心承认自己长在这个平台上。搬到这里之后
 * 行为须与原先一字不差：两种链接各指一种编号，空间链接给 uid、直播间链接给直播间号；
 * 链接里常跟着 spm_id_from 之类含数字的参数，取「第一串数字」会取到那个参数上去。
 * <p>
 * 认链接不打接口，因此这里的接口工具是个空壳：一旦谁在这条路上加了一趟网络请求，
 * 它会当场炸出来，而不是等到没网的机器上才发现。
 */
@DisplayName("认本平台的主播链接")
class BilibiliStreamerLinkTest {
    private final BilibiliDataSourceService service = new BilibiliDataSourceService(mock(BilibiliApiUtil.class));

    @Test
    @DisplayName("直播间链接给的是直播间号")
    void liveLinkGivesRoomId() {
        assertEquals(StreamerReference.roomId(222L), parse("https://live.bilibili.com/222"));
        assertEquals(StreamerReference.roomId(222L), parse("live.bilibili.com/222"));
    }

    @Test
    @DisplayName("空间链接给的是 uid")
    void spaceLinkGivesUid() {
        assertEquals(StreamerReference.uid(999L), parse("https://space.bilibili.com/999"));
    }

    @Test
    @DisplayName("链接尾巴上的参数不当那串数字：取的是路径里那一段")
    void trailingParametersAreNotTheId() {
        assertEquals(StreamerReference.uid(999L), parse("https://space.bilibili.com/999?spm_id_from=333.1007.0.0"));
        assertEquals(StreamerReference.roomId(222L), parse("https://live.bilibili.com/222?spm_id_from=333.1007"));
    }

    @Test
    @DisplayName("首尾空白不算数：粘贴时常带着")
    void surroundingBlanksAreTrimmed() {
        assertEquals(StreamerReference.roomId(222L), parse("  https://live.bilibili.com/222\n"));
    }

    @Test
    @DisplayName("不是本平台的链接、纯数字、空输入，一律认不出")
    void unrelatedInputIsEmpty() {
        assertTrue(service.parseStreamerLink("https://live.example.com/222").isEmpty());
        assertTrue(service.parseStreamerLink("1001").isEmpty(), "纯数字不是链接，由查主播口统一处理");
        assertTrue(service.parseStreamerLink("").isEmpty());
        assertTrue(service.parseStreamerLink("   ").isEmpty());
        assertTrue(service.parseStreamerLink(null).isEmpty(), "认不出应是空，而不是抛");
    }

    private StreamerReference parse(String text) {
        Optional<StreamerReference> parsed = service.parseStreamerLink(text);
        assertTrue(parsed.isPresent(), "应认得出: " + text);
        return parsed.get();
    }
}
