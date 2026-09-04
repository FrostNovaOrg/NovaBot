package com.starlwr.bot.bilibili;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.console.BilibiliConsolePageProvider;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.service.BilibiliDataSourceService;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.DataSourceServiceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 平台标识串的线上形态回归
 * <p>
 * 平台标识串是<b>存出去的值</b>：使用者的 datasource.json 里写着它，数据库里按它分键存着
 * 每一场的直播数据与绑定关系，控制台按它认出这一页属于谁。它改一个字母，既有部署的那些数据
 * 不会报错，只会<b>整片读不回来</b>——看着像数据没了，其实是存在另一个键下面。
 * <p>
 * 因此这一串在本仓里<b>不是可以重构的实现细节</b>。本类把它按字面钉住：
 * 对照样本取自随发行包一同交付的 {@code dist/templates/datasource.example.json}，
 * 那里写的是 {@code "platform": "bilibili"} —— 使用者照着它填出来的配置，必须一直读得回来。
 */
@DisplayName("平台标识串线上形态")
class BilibiliPlatformWireFormTest {
    /**
     * 对照样本：随发行包交付的示例数据源里那个 platform 值，一字不改
     */
    private static final String WIRE_ID = "bilibili";

    private static LiveStreamerInfo source() {
        return new LiveStreamerInfo(10001L, "测试主播", 20002L);
    }

    @Test
    @DisplayName("平台标识串与发行包示例里的值一字不差")
    void idMatchesShippedSample() {
        assertEquals(WIRE_ID, BilibiliPlatform.BILIBILI.id());
    }

    @Test
    @DisplayName("显示名给人看，不参与存取")
    void displayNameIsForHumansOnly() {
        assertEquals("哔哩哔哩", BilibiliPlatform.BILIBILI.displayName());
        assertEquals(WIRE_ID, BilibiliPlatform.BILIBILI.id());
    }

    @Test
    @DisplayName("事件上的平台字段是标识串")
    void eventCarriesWireId() {
        BilibiliLiveOffEvent event = new BilibiliLiveOffEvent(source(), Instant.parse("2026-09-03T00:00:00Z"));

        assertEquals(WIRE_ID, event.getPlatform());
    }

    @Test
    @DisplayName("事件序列化后 platform 字段仍是标识串")
    void eventSerializationCarriesWireId() {
        BilibiliLiveOffEvent event = new BilibiliLiveOffEvent(source(), Instant.parse("2026-09-03T00:00:00Z"));

        JSONObject parsed = JSON.parseObject(JSON.toJSONString(event));

        assertEquals(WIRE_ID, parsed.getString("platform"));
    }

    @Test
    @DisplayName("使用者配置里的推送用户按标识串读得回来")
    void pushUserRoundTripsByWireId() {
        String stored = "{\"uid\":10001,\"platform\":\"" + WIRE_ID + "\",\"enabled\":true}";

        PushUser user = JSON.parseObject(stored, PushUser.class);

        assertEquals(WIRE_ID, user.getPlatform());
        assertEquals(WIRE_ID, JSON.parseObject(JSON.toJSONString(user)).getString("platform"));
    }

    @Test
    @DisplayName("数据源服务申报的平台名与标识串一致")
    void dataSourceServiceDeclaresWireId() {
        DataSourceServiceConfig config = BilibiliDataSourceService.class.getAnnotation(DataSourceServiceConfig.class);

        assertEquals(WIRE_ID, config.name());
    }

    @Test
    @DisplayName("控制台页面申报的平台名与标识串一致")
    void consolePageDeclaresWireId() {
        BilibiliConsolePageProvider page = new BilibiliConsolePageProvider();

        assertEquals(WIRE_ID, page.id());
        assertEquals("哔哩哔哩", page.displayName());
    }

    /**
     * 这一页要落在连接页上，而落位这件事只有插件自己申报得出来
     * <p>
     * 核心界面里一个平台的名字都没有，因此它无从判断某一页该摆在哪儿。申报错了不会有任何报错——
     * 那张卡会安静地折进设置页「高级」里，而连接页上少了一段，看起来像是这个平台不需要连接。
     */
    @Test
    @DisplayName("控制台页面落在连接页上")
    void consolePageSitsOnLinksPage() {
        assertEquals(ConsolePageSlot.LINKS, new BilibiliConsolePageProvider().slot());
    }
}
