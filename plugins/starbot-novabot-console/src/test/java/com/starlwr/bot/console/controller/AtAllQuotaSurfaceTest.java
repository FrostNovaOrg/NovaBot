package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.StarBotSenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @全体成员 额度用量接口
 * <p>
 * 首页上「@全体成员 已用 3/10」那一格的数据来源。这两个数在此之前<b>只存在于日志里</b>：
 * 额度用尽那一次会 WARN 一行，而在那之前谁也说不出今天还剩几次——
 * 等看见 WARN 的时候，那条通知已经没 @ 到人了。
 * <p>
 * 用的是<b>真的配额服务</b>：假的额度表只能证明控制器会转述，证明不了它转述的是
 * 真正在拦人的那一份账。两个维度各有各的上限（账号每天 10 次全部群共享、每群每天 20 次），
 * 因此两向都要量——只量一个的话，另一个维度的读数错了没有任何现象。
 */
@DisplayName("@全体成员 额度用量接口")
class AtAllQuotaSurfaceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long ANOTHER_GROUP = 30004L;

    private static final long FRIEND = 20001L;

    /**
     * 没配过推送、却真花过额度的群：只有它才说得出「各群那一列圈的是谁」
     */
    private static final long UNCONFIGURED_GROUP = 39999L;

    @Test
    @DisplayName("账号维度：全部群共享的那份额度，用了几次就报几次")
    void reportsBotDimension() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        AtAllQuotaService quota = new AtAllQuotaService(properties);
        quota.tryConsume(PLATFORM, GROUP);
        quota.tryConsume(PLATFORM, ANOTHER_GROUP);

        JSONObject bot = only(controller(quota, properties).quota().getJSONArray("bots"));

        assertEquals(PLATFORM, bot.getString("platform"));
        assertEquals(2, bot.getIntValue("used"), "两个群各用一次，账号那份额度就少了两次");
        assertEquals(properties.getPush().getAtAllDailyLimit(), bot.getIntValue("limit"));
        assertTrue(bot.getBooleanValue("limited"));
    }

    @Test
    @DisplayName("会话维度：各群各算各的，没用过的群报 0 而不是不出现")
    void reportsSessionDimension() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        AtAllQuotaService quota = new AtAllQuotaService(properties);
        quota.tryConsume(PLATFORM, GROUP);
        quota.tryConsume(PLATFORM, GROUP);

        JSONArray sessions = controller(quota, properties).quota().getJSONArray("sessions");

        assertEquals(List.of(GROUP, ANOTHER_GROUP),
                sessions.stream().map(item -> ((JSONObject) item).getLong("num")).toList(),
                "私聊不该在里面：那里没有 @全体成员 这回事，报个 0/20 只会让人以为它也有额度");
        assertEquals(2, sessions.getJSONObject(0).getIntValue("used"));
        assertEquals(0, sessions.getJSONObject(1).getIntValue("used"), "没用过要报 0，不是缺席");
        assertEquals(properties.getPush().getAtAllSessionDailyLimit(),
                sessions.getJSONObject(0).getIntValue("limit"));
    }

    @Test
    @DisplayName("上限配成不限：limited 为假，界面据此不画分母")
    void tellsUnlimitedApart() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(0);
        AtAllQuotaService quota = new AtAllQuotaService(properties);

        JSONObject bot = only(controller(quota, properties).quota().getJSONArray("bots"));

        assertEquals(0, bot.getIntValue("limit"));
        assertTrue(!bot.getBooleanValue("limited"), "0 与负数在配额服务里都是「不限」，接口要把这一档说出来");
    }

    @Test
    @DisplayName("各群那一列圈的是已配推送的群：账号已用比各群之和大，正说明有群被撤了配置")
    void sessionColumnIsScopedToConfiguredGroups() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        AtAllQuotaService quota = new AtAllQuotaService(properties);
        quota.tryConsume(PLATFORM, UNCONFIGURED_GROUP);

        JSONObject state = controller(quota, properties).quota();

        assertEquals(1, only(state.getJSONArray("bots")).getIntValue("used"),
                "账号那一行是全量：额度是真被这一次吃掉的");
        assertEquals(0, state.getJSONArray("sessions").stream()
                        .mapToInt(item -> ((JSONObject) item).getIntValue("used")).sum(),
                "各群那一列只圈已配推送的群，撤了配置的那个群不在其中——"
                        + "两个数对不上正是它要传达的信息，别把它抹平成一样");
    }

    @Test
    @DisplayName("bots 与 sessions 都带非空 platformName；未配显示名时等于 platform，且 platform 原值不变")
    void reportsPlatformNameFallingBackToPlatformId() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        AtAllQuotaService quota = new AtAllQuotaService(properties);

        JSONObject state = controller(quota, properties).quota();
        JSONObject bot = only(state.getJSONArray("bots"));
        JSONObject session = state.getJSONArray("sessions").getJSONObject(0);

        assertEquals(PLATFORM, bot.getString("platform"), "platform 原值不能改");
        assertEquals(PLATFORM, bot.getString("platformName"), "未配显示名时回落等于 platform");
        assertTrue(bot.getString("platformName") != null && !bot.getString("platformName").isBlank(),
                "platformName 不得空或 null");

        assertEquals(PLATFORM, session.getString("platform"), "会话行 platform 原值不能改");
        assertEquals(PLATFORM, session.getString("platformName"), "会话行未配显示名时回落等于 platform");
        assertTrue(session.getString("platformName") != null && !session.getString("platformName").isBlank(),
                "会话行 platformName 不得空或 null");
    }

    @Test
    @DisplayName("适配器自报过显示名时，bots 与 sessions 用人话名，platform 仍是标识串")
    void reportsAdapterDisplayNameWithoutChangingPlatformId() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        AtAllQuotaService quota = new AtAllQuotaService(properties);
        StarBotSenderService senders = new StarBotSenderService(properties);
        senders.addSender(new Sender(PLATFORM, "http://127.0.0.1/onebot/send"), "QQ");

        JSONObject state = controller(quota, properties, senders).quota();
        JSONObject bot = only(state.getJSONArray("bots"));
        JSONObject session = state.getJSONArray("sessions").getJSONObject(0);

        assertEquals(PLATFORM, bot.getString("platform"), "platform 仍是登记用的标识串");
        assertEquals("QQ", bot.getString("platformName"), "账号行用人话名");
        assertEquals(PLATFORM, session.getString("platform"), "会话行 platform 仍是标识串");
        assertEquals("QQ", session.getString("platformName"), "会话行用人话名");
    }

    /**
     * 配好推送的两个群与一个好友会话，共用一个真的配额服务
     */
    private AtAllQuotaController controller(AtAllQuotaService quota, StarBotCoreProperties properties) {
        return controller(quota, properties, new StarBotSenderService(properties));
    }

    private AtAllQuotaController controller(AtAllQuotaService quota, StarBotCoreProperties properties,
                                            StarBotSenderService senders) {
        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setPlatform("bilibili");
        user.setTargets(List.of(target(PushTargetType.GROUP, GROUP), target(PushTargetType.GROUP, ANOTHER_GROUP),
                target(PushTargetType.FRIEND, FRIEND)));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        return new AtAllQuotaController(quota, dataSource, properties, senders);
    }

    private PushTarget target(PushTargetType type, long num) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(num);
        target.setMessages(new ArrayList<>());
        return target;
    }

    private JSONObject only(JSONArray items) {
        assertEquals(1, items.size(), "本表只配了一个推送平台: " + items);
        return items.getJSONObject(0);
    }
}
