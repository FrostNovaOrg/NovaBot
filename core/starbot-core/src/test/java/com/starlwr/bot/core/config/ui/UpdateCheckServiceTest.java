package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新版检查器
 * <p>
 * 来源是一个可注入的取数口，本组用例喂的全是假回包，不联真外网——
 * 联了的话，「检查失败该静默」这一条在断网机器上碰巧绿着，在网络正常的机器上根本没跑过。
 * <p>
 * 钉它的理由：检查器的三种失败（取不到、认不出、比错了）表现完全一样——
 * 侧栏那枚药丸不出现。没有任何报错、任何日志级别的红会替使用者发现它，
 * 「有新版却不提示」与「没有新版」在屏幕上长得一模一样，只有对着假来源逐态验证才分得开。
 * <p>
 * 「先不提醒」一并在此钉：跳过的判定走 {@link VersionOrder} 而不是字符串相等，
 * 跳过 5.0.1 之后再发布 5.0.2 要重新提示——那一半如果坏了，使用者会以为再也没有新版，
 * 而他唯一能做的「点一下别烦我」恰好把真正的更新永远关掉了。
 */
@DisplayName("新版检查器")
class UpdateCheckServiceTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private StarBotStateStore state;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        // 跳过要落盘，把状态文件引到临时目录里，别落在仓库树里
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        state = new StarBotStateStore(properties);
    }

    @Test
    @DisplayName("来源说有新版：版本、说明前三行、链接都在")
    void newerReleaseCarriesVersionNotesAndUrl() {
        UpdateCheckService service = service(buildOf("5.0.0"), release("v5.1.0",
                "修了开播误报\n\n第二行说明\n第三行说明\n第四行说明"));

        service.checkNow();

        var update = service.pendingUpdate();
        assertTrue(update.isPresent(), "5.1.0 比 5.0.0 新，应当提示");
        assertEquals("v5.1.0", update.get().version(), "版本号原样下发，带不带 v 前缀由发布仓决定，剥前缀是比较的事不是显示的事");
        assertEquals(List.of("修了开播误报", "第二行说明", "第三行说明"), update.get().notes(),
                "说明取前三行非空行，空行不算数，第四行不放");
        assertEquals("https://example.invalid/release", update.get().url());
    }

    @Test
    @DisplayName("来源说没新版：同版本与更旧都不提示")
    void sameOrOlderReleaseDoesNotPrompt() {
        UpdateCheckService same = service(buildOf("5.0.0"), release("v5.0.0", "内容"));
        same.checkNow();
        assertTrue(same.pendingUpdate().isEmpty(), "版本相同不是新版");

        UpdateCheckService older = service(buildOf("5.1.0-beta.1"), release("v5.0.0", "内容"));
        older.checkNow();
        assertTrue(older.pendingUpdate().isEmpty(), "来源最新的是 5.0.0、跑着的是 5.1.0-beta.1，没有谁比谁新");
    }

    @Test
    @DisplayName("取不到来源：安静返回，不抛也不提示")
    void unreachableSourceStaysSilent() {
        UpdateCheckService service = service(buildOf("5.0.0"), url -> {
            throw new IllegalStateException("连不上");
        });

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty(), "取不到就是不知道有没有新版，不知道不该被当成有");
    }

    @Test
    @DisplayName("取回的东西认不出版本号：与取不到同样对待")
    void unrecognizedBodyStaysSilent() {
        UpdateCheckService service = service(buildOf("5.0.0"), url -> new JSONObject());

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty());
    }

    @Test
    @DisplayName("功能关着：不取也不提示")
    void disabledCheckDoesNothing() {
        properties.getConfigUi().getUpdate().setEnabled(false);
        UpdateCheckService service = service(buildOf("5.0.0"), url -> {
            throw new IllegalStateException("关着就不该来取");
        });

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty());
    }

    @Test
    @DisplayName("跳过的版本不再提示")
    void skippedVersionStopsPrompting() {
        UpdateCheckService service = service(buildOf("5.0.0"), release("v5.1.0", "内容"));
        service.checkNow();
        assertTrue(service.pendingUpdate().isPresent());

        assertTrue(service.skip("v5.1.0"), "跳过的正是提示着的那个版本，应当记下");
        assertTrue(service.pendingUpdate().isEmpty(), "记下了就该消失，药丸与首页软待办都由这一个结果驱动");
    }

    @Test
    @DisplayName("跳过 5.1.0 之后来了 5.1.1：重新提示")
    void releaseNewerThanSkippedPromptsAgain() {
        UpdateCheckService first = service(buildOf("5.0.0"), release("v5.1.0", "内容"));
        first.checkNow();
        first.skip("v5.1.0");

        UpdateCheckService next = service(buildOf("5.0.0"), release("v5.1.1", "新内容"));
        next.checkNow();

        assertTrue(next.pendingUpdate().isPresent(), "跳过是「这版先不提醒」，不是「以后都别提醒」");
        assertEquals("v5.1.1", next.pendingUpdate().get().version());
    }

    /**
     * 跳过记录的落盘那一半
     * <p>
     * 上面那条与它共用同一个内存里的状态对象，验的是判定逻辑；真重启之后还认不认，
     * 只有换一个新 {@link StarBotStateStore}、让它从盘上读一遍才算验过——
     * 跳过若没落盘，「点一下别烦我」只活到下次重启，而那正是它唯一被点下的场合。
     */
    @Test
    @DisplayName("跳过记录随状态文件跨重启生效")
    void skipSurvivesRestart() {
        UpdateCheckService before = service(buildOf("5.0.0"), release("v5.1.0", "内容"));
        before.checkNow();
        before.skip("v5.1.0");

        StarBotStateStore reloaded = new StarBotStateStore(properties);
        reloaded.onApplicationReadyEvent();
        UpdateCheckService after = new UpdateCheckService(properties, reloaded, buildOf("5.0.0"), release("v5.1.0", "内容"));
        after.checkNow();
        assertTrue(after.pendingUpdate().isEmpty(), "重启之后同一版本的跳过仍然算数");
        reloaded.onContextClosedEvent();
    }

    @Test
    @DisplayName("不是当前提示着的版本，跳过不记")
    void skipRejectsForeignVersion() {
        UpdateCheckService service = service(buildOf("5.0.0"), release("v5.1.0", "内容"));
        service.checkNow();

        assertFalse(service.skip("9.9.9"), "隔了几天才送达的请求或乱填的版本号不该被记成使用者的选择");
        assertTrue(service.pendingUpdate().isPresent(), "没记下，提示照旧");
    }

    private UpdateCheckService service(ObjectProvider<BuildProperties> build, UpdateCheckService.SourceClient source) {
        return new UpdateCheckService(properties, state, build, source);
    }

    private static UpdateCheckService.SourceClient release(String tag, String body) {
        JSONObject json = new JSONObject();
        json.put("tag_name", tag);
        json.put("body", body);
        json.put("html_url", "https://example.invalid/release");
        return url -> json;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> buildOf(String version) {
        Properties entries = new Properties();
        entries.put("version", version);
        BuildProperties build = new BuildProperties(entries);

        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(build);
        return provider;
    }
}
