package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 初始设置那两道标记
 * <p>
 * 五步里有四步各有服务端事实可查（上锁没有、探针红不红、平台登录没有、有没有主播），
 * 只有两件事没有任何现象、只能记：<b>有没有人要求重来一遍</b>，以及<b>第 5 步发过没有</b>。
 * 这两位一旦记错，屏幕上不会有任何异常——进度条照样画得出来，只是画的是另一台机器。
 * <p>
 * 每一格都配阴性对照：没记过的时候必须说没记过。缺了那一半的话，
 * 一个「恒说记过了」的实现与一个记得对的实现，在阳性那一格上长得一样。
 */
@DisplayName("初始设置的标记")
class ConfigUiSetupStateTest {
    @TempDir
    Path dir;

    private ConfigUiSetupController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        // 状态文件与直播数据同目录，因此指到临时目录里去：不指的话它会写到跑测试那个目录下，
        // 而下一跑读到的是上一跑留下的标记
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        controller = new ConfigUiSetupController(new NovaStateStore(properties));
    }

    @Test
    @DisplayName("阴性 —— 什么都没记过时，两位都说没有")
    void freshMachineHasNeitherMark() {
        JSONObject state = controller.state();

        assertTrue(state.getBooleanValue("success"));
        assertFalse(state.getBooleanValue("rerunRequested"), "没人按过「重新跑一遍」，却说有人按过");
        assertFalse(state.getBooleanValue("testSent"), "没发过「发一条试试」，却说发过");
        assertNull(state.getString("rerunRequestedAt"));
        assertNull(state.getString("testSentAt"));
    }

    @Test
    @DisplayName("阳性 —— 按下「重新跑一遍」之后，标记连时刻一起在")
    void rerunLeavesATimestamp() {
        JSONObject result = controller.rerun();

        assertTrue(result.getBooleanValue("success"));
        JSONObject state = controller.state();
        assertTrue(state.getBooleanValue("rerunRequested"), "标记落了，读回来却说没有");
        assertNotNull(state.getString("rerunRequestedAt"), "只记一个布尔的话，日后问不出「什么时候重来的」");
        assertFalse(state.getBooleanValue("testSent"), "重来一遍不该顺手把第五步说成做过了");
    }

    /**
     * 🔴 这一格守的是「重新跑一遍」这个按钮按第二次还管不管用
     * <p>
     * 标记只落不收的话，这台机器此后<b>每一次</b>打开初始设置页都被拉回第一步，
     * 而使用者只按过那一次按钮——那不是「重新跑一遍」，那是「从此只能从头走」。
     */
    @Test
    @DisplayName("标记收下之后就不再等着人接，再按一次又等着")
    void theMarkIsConsumedOnce() {
        controller.rerun();
        assertTrue(controller.state().getBooleanValue("rerunRequested"));

        controller.rerunConsumed();
        assertFalse(controller.state().getBooleanValue("rerunRequested"),
                "🔴 收下了还说等着人接，那么这一页此后每次打开都被拉回第一步");
        assertNotNull(controller.state().getString("rerunRequestedAt"),
                "收下不等于抹掉：「什么时候有人要求重来过」正是日后要查的那一条");

        controller.rerun();
        assertTrue(controller.state().getBooleanValue("rerunRequested"),
                "再按一次却不认了，那这个按钮只有第一次管用");
    }

    @Test
    @DisplayName("阳性 —— 第五步确认收到之后，标记连时刻一起在")
    void testSentLeavesATimestamp() {
        assertFalse(controller.state().getBooleanValue("testSent"), "阳性对照：这一位起点是假");

        JSONObject result = controller.testSent();

        assertTrue(result.getBooleanValue("success"));
        assertEquals(result.getString("testSentAt"), controller.state().getString("testSentAt"),
                "记下的时刻与读回来的不是同一个");
        assertTrue(controller.state().getBooleanValue("testSent"));
        assertFalse(controller.state().getBooleanValue("rerunRequested"),
                "第五步做完不该顺手把「要求重来」也点亮");
    }

    /**
     * 两位互不牵连：合成一位的话，重来一遍会把第 5 步一起抹成没做过，
     * 而那台机器上那条消息明明通过了
     */
    @Test
    @DisplayName("重来一遍不清掉第五步那一位")
    void rerunKeepsTheTestMark() {
        controller.testSent();
        String sentAt = controller.state().getString("testSentAt");

        controller.rerun();

        assertEquals(sentAt, controller.state().getString("testSentAt"),
                "「已经配好的东西不会被清掉」——这一位也在其中");
        assertTrue(controller.state().getBooleanValue("testSent"));
    }
}
