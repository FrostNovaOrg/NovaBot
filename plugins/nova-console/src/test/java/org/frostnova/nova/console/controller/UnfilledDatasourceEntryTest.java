package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.handler.StarBotEventHandlerPushMessageInitializer;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.frostnova.nova.core.datasource.DataSourceServiceConfig;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StarBotEventHandlerService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 未填完的推送配置在控制台上的表现
 * <p>
 * 发行包里的 {@code datasource.example.json} 是一份留空的样板：uid 与 num 都是空串，
 * 等着使用者填。照着做的人多半会把它直接复制成 {@code datasource.json} 再改——
 * 而改到一半就去开控制台看看，是再自然不过的事。
 * <p>
 * 此时「键都在、值没填」这一形不会被解析器拦下：必填字段只查了键在不在。
 * 于是空串解出来的 {@code null} 一路带进内存，直到控制台读运行状态时才炸，
 * 页面上只剩一句「载入运行状态失败」——<b>使用者既不知道是配置的问题，
 * 更不知道是哪一条、差哪个字段</b>。
 * <p>
 * 两向都要有用例：未填完的那份不许再让接口出错，且要说清楚缺什么；
 * 填好了的那份，接口的回答必须与改动之前逐字相同。
 */
@DisplayName("未填完的推送配置")
class UnfilledDatasourceEntryTest {
    /**
     * 发行包示例在核心测试资源中的副本
     * <p>
     * 不另抄一份：这份副本与发行包里那份逐字相同，由核心 {@code PushUserWireFormTest} 守着。
     * 就地重写一份「示例大概长这样」的字面量，守的就不再是真正发出去的那一份了。
     */
    private static final String EXAMPLE =
            "core/nova-core/src/test/resources/pushuser-wire/datasource-example.json";

    /**
     * 示例里那位主播所属的直播平台
     */
    private static final String PLATFORM = "bilibili";

    @Test
    @DisplayName("原样启用发行包示例时, 运行状态接口不应出错, 并应说明是哪一条缺哪个字段")
    void shouldExplainUnfilledEntryInsteadOfFailing(@TempDir Path directory) throws IOException {
        RuntimeStateController controller = controllerFor(directory, resource(EXAMPLE));

        JSONObject state = controller.state();

        assertTrue(state.getBooleanValue("success"), "接口本身要正常回答: " + state);

        JSONArray incomplete = state.getJSONArray("incomplete");
        assertNotNull(incomplete, "未填完的条目要报出来, 否则页面上只是一片空白");
        assertEquals(1, incomplete.size(), "示例里只有一条, 实际为: " + incomplete);

        JSONObject entry = incomplete.getJSONObject(0);
        assertEquals(1, entry.getIntValue("index"), "要说清是第几条");

        List<String> fields = entry.getJSONArray("fields").toList(String.class);
        assertTrue(fields.contains("uid"), "uid 还没填, 应当列出来: " + fields);
        assertTrue(fields.stream().anyMatch(field -> field.endsWith("num")),
                "推送目标的 num 还没填, 应当列出来: " + fields);

        String message = entry.getString("message");
        assertTrue(message.contains("1"), "说明里要带上序号: " + message);
        assertTrue(message.contains("uid"), "说明里要带上缺的字段: " + message);
    }

    @Test
    @DisplayName("填好的推送配置, 运行状态接口的回答应与改动之前逐字相同")
    void shouldKeepStateUnchangedForFilledConfiguration(@TempDir Path directory) throws IOException {
        RuntimeStateController controller = controllerFor(directory, filledExample());

        JSONObject state = controller.state();

        // 新增的那几栏摘掉之后，其余部分要与改动之前一模一样。
        // 基线是在改动之前的树上跑同一份配置抄下来的实值，不是改完之后回头补的——
        // 因此后来新增的栏一律在这里逐个摘名，而不是把它们追加进基线串：
        // 追加一次，这串就不再是「那次改动之前的实值」，而是「上次谁改完之后的样子」。
        // 撤掉的栏没有这条路可走：摘名摘的是「实际有、基线里没有」的那一侧，
        // 而撤栏正好相反，只能改基线串本身——见 FILLED_STATE_BEFORE 那一段
        // 深拷一份再摘：摘的是会话里那两栏，直接在 state 上动的话，下面那句读到的就不是接口原样了
        JSONObject rest = JSONObject.parseObject(state.toJSONString());
        rest.remove("incomplete");
        rest.remove("totalDataAvailable");

        // 会话里后添的两栏（本会话的菜单里不列哪几条、以及各自的说明）同样只摘名
        JSONArray sessions = rest.getJSONArray("sessions");
        for (int i = 0; i < sessions.size(); i++) {
            sessions.getJSONObject(i).remove("menuHidden");
            sessions.getJSONObject(i).remove("menuNotes");
        }

        assertEquals(FILLED_STATE_BEFORE, rest.toJSONString(),
                "填好的配置在控制台上的读数变了：这一改动本不该碰到它");

        assertEquals(0, state.getJSONArray("incomplete").size(), "填好了就不该有未完成的条目");
    }

    /**
     * 填好了的示例配置在改动之前的运行状态读数
     * <p>
     * 取法：在改动之前的树上跑 {@link #shouldKeepStateUnchangedForFilledConfiguration}
     * 并把 {@code state()} 的返回抄下来。改完之后这一串必须原样成立。
     * <p>
     * <b>动过一次</b>：{@code "bindings":[]} 那一栏随「账号绑定整族停用」一并从接口面撤掉，
     * 因此这串里也删掉了它——撤栏是唯一能改这串的理由，且只许删掉被撤的那一栏、其余一个字节不动。
     * 别的差异一律照旧不许往这串里补。
     */
    private static final String FILLED_STATE_BEFORE = "{\"success\":true,\"commands\":[],"
            + "\"sessions\":[{\"platform\":\"qq-onebot\",\"num\":987654321,\"type\":\"群\",\"configured\":true,"
            + "\"streamers\":[\"19466979697833\"],\"disabled\":[],\"revenueVisible\":false,"
            + "\"revenueExplicit\":false}],\"subscriptions\":[]}";

    /**
     * 把示例里留空的两处填上，其余一字不动
     * <p>
     * 与示例只差这两个值，正是为了让两条用例的差别只有「填没填」这一项
     * @return 填好的推送配置
     */
    private static String filledExample() throws IOException {
        return resource(EXAMPLE)
                .replace("\"uid\": \"\"", "\"uid\": 19466979697833")
                .replace("\"num\": \"\"", "\"num\": 987654321");
    }

    /**
     * 按给定的推送配置装好一个运行状态接口
     * <p>
     * 数据源是真的：解析、加载、平台服务补全这几步都要真走一遍，问题正出在这条路上。
     * 金额可见性服务也是真的——出错的那一处就在它的入参校验上，换成替身等于把要查的东西藏起来。
     * @param directory 放推送配置的目录
     * @param datasource 推送配置内容
     * @return 运行状态接口
     */
    private static RuntimeStateController controllerFor(Path directory, String datasource) throws IOException {
        Path file = directory.resolve("datasource.json");
        Files.writeString(file, datasource, StandardCharsets.UTF_8);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(file.toString());
        // 监听线程与本组用例无关，开着只会在临时目录被删掉之后继续刷日志
        properties.getDatasource().setJsonAutoReload(false);

        // 平台服务必须登记：数据源对没有对应插件的平台是整位拒收的，
        // 少了这一步，示例里那位主播根本进不了内存，问题也就无从复现
        JsonDataSource dataSource = new JsonDataSource(
                mock(ApplicationEventPublisher.class),
                new DataSourceServiceRegistry(List.of(new NoopDataSourceService())),
                new StarBotEventHandlerPushMessageInitializer(mock(StarBotEventHandlerService.class),
                        new PushTemplateDefaults(new NovaCoreProperties())),
                properties.getDatasource());
        dataSource.load();

        NovaStateStore store = new NovaStateStore(properties);
        return new RuntimeStateController(
                mock(CommandDispatcher.class),
                mock(CommandSettingsService.class),
                mock(AtSubscriptionService.class),
                store,
                dataSource,
                new RevenueVisibilityService(store),
                mock(LiveDataService.class));
    }

    /**
     * 读取核心测试资源树上的那一份，不另抄
     * @param name 相对仓库根的路径
     * @return 内容
     */
    private static String resource(String name) throws IOException {
        Path path = FrontendFixture.repoRoot().resolve(name);
        assertTrue(Files.exists(path), "测试资源缺席: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 模拟平台插件提供的数据源服务
     * <p>
     * 什么都不补：真插件在 uid 为空时也是原样返回的，补全与否与本组用例无关
     */
    @DataSourceServiceConfig(name = PLATFORM)
    private static class NoopDataSourceService implements DataSourceService {
        @Override
        public void completePushUser(PushUser user) {
            // 示例里连 uid 都没填，没有可补的东西
        }
    }
}
