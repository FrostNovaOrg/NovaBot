package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private static final String LATEST = "https://api.github.com/repos/FrostNovaOrg/NovaBot/releases/latest";
    private static final String RELEASES = "https://api.github.com/repos/FrostNovaOrg/NovaBot/releases";

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private NovaStateStore state;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        // 跳过要落盘，把状态文件引到临时目录里，别落在仓库树里
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        state = new NovaStateStore(properties);
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

        for (String custom : List.of("https://mirror.example.invalid/releases/latest",
                "https://api.github.com/repos/example/other/releases/latest")) {
            properties.getConfigUi().getUpdate().setSource(custom);
            HttpUtil http = mock(HttpUtil.class);
            when(http.get(custom)).thenReturn("{\"tag_name\":\"v6.0.0-beta.2\"}");
            UpdateCheckService mirror = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));
            mirror.checkNow();
            assertEquals("v6.0.0-beta.2", mirror.pendingUpdate().orElseThrow().version(),
                    "自定义来源仍读取原地址的单个发布对象");
            verify(http).get(custom);
            verify(http, never()).get(RELEASES);
        }
    }

    @Test
    @DisplayName("测试版用户收到更新的测试版")
    void prereleaseUserSeesNewerPrerelease() {
        HttpUtil http = responses("""
                [{"tag_name":"v6.0.0-beta.2","prerelease":true,"body":"测试版说明","html_url":"https://example.invalid/beta2"},
                 {"tag_name":"v5.9.2"}]
                """);
        UpdateCheckService service = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));

        service.checkNow();

        assertEquals("v6.0.0-beta.2", service.pendingUpdate().map(UpdateCheckService.UpdateInfo::version).orElse(""),
                "测试版用户应收到更新的测试版");
        assertEquals(List.of("测试版说明"), service.pendingUpdate().orElseThrow().notes());
        assertEquals("https://example.invalid/beta2", service.pendingUpdate().orElseThrow().url());
        verify(http, never()).get(LATEST);
    }

    @Test
    @DisplayName("测试版用户收到正式版，取版本最大的发布")
    void prereleaseUserSeesStableRelease() {
        HttpUtil http = responses("""
                [{"tag_name":"v6.0.0-beta.2","prerelease":true},
                 {"tag_name":"v6.0.0","body":"正式版说明","html_url":"https://example.invalid/stable"},
                 {"tag_name":"v6.0.0-beta.10","prerelease":true}]
                """);
        UpdateCheckService service = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));

        service.checkNow();

        assertEquals("v6.0.0", service.pendingUpdate().map(UpdateCheckService.UpdateInfo::version).orElse(""),
                "测试版用户应收到正式版，不能取列表首项或最后一项");
        assertEquals(List.of("正式版说明"), service.pendingUpdate().orElseThrow().notes());
        assertEquals("https://example.invalid/stable", service.pendingUpdate().orElseThrow().url());
    }

    @Test
    @DisplayName("测试版用户不被提示当前版本或更旧的正式版")
    void prereleaseUserDoesNotSeeOlderStableRelease() {
        HttpUtil http = responses("""
                [{"tag_name":"v6.0.0-beta.1","prerelease":true},{"tag_name":"v5.9.2"}]
                """);
        UpdateCheckService service = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty(), "当前版本与更旧的正式版都不能提示");
        verify(http).get(RELEASES);
    }

    @Test
    @DisplayName("正式版用户不被推送测试版")
    void stableUserDoesNotSeePrerelease() {
        HttpUtil http = responses("""
                [{"tag_name":"v6.0.0-beta.2","prerelease":true}]
                """);
        for (String current : List.of("5.9.2", "5.9.2+build-test")) {
            UpdateCheckService service = new UpdateCheckService(properties, http, state, buildOf(current));

            service.checkNow();

            assertTrue(service.pendingUpdate().isEmpty(), "正式版用户不能被推送测试版: " + current);
        }
        verify(http, never()).get(RELEASES);
    }

    @Test
    @DisplayName("草稿不被当成新版提示")
    void draftsAreNotUpdates() {
        HttpUtil http = responses("""
                [{"tag_name":"v7.0.0","draft":true},
                 {"tag_name":"v6.0.0-beta.2","draft":true,"prerelease":true}]
                """);
        UpdateCheckService service = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty(), "草稿不能当成新版提示");
        verify(http).get(RELEASES);
    }

    @Test
    @DisplayName("取不到来源：安静返回，不抛也不提示")
    void unreachableSourceStaysSilent() {
        for (String current : List.of("5.0.0", "6.0.0-beta.1")) {
            UpdateCheckService service = service(buildOf(current), url -> {
                throw new IllegalStateException("连不上");
            });

            service.checkNow();

            assertTrue(service.pendingUpdate().isEmpty(), "取不到就是不知道有没有新版，不知道不该被当成有");
        }
    }

    @Test
    @DisplayName("取回的东西认不出版本号：与取不到同样对待")
    void unrecognizedBodyStaysSilent() {
        UpdateCheckService service = service(buildOf("5.0.0"), url -> new JSONObject());

        service.checkNow();

        assertTrue(service.pendingUpdate().isEmpty());

        HttpUtil http = responses("[]");
        UpdateCheckService beta = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));
        for (String body : new String[]{null, "not json", "{}", "[]", "[null,1,{}, {\"tag_name\":\" \"}]"}) {
            when(http.get(RELEASES)).thenReturn(body);
            beta.checkNow();
            assertTrue(beta.pendingUpdate().isEmpty(), "列表取不到或认不出时不提示: " + body);
        }
        when(http.get(RELEASES)).thenReturn("[{\"tag_name\":\"v6.0.0-beta.2\"}]");
        beta.checkNow();
        assertTrue(beta.pendingUpdate().isPresent());
        when(http.get(RELEASES)).thenThrow(new IllegalStateException("连不上"));
        beta.checkNow();
        assertEquals("v6.0.0-beta.2", beta.pendingUpdate().orElseThrow().version(), "后续取数失败保留已有提示");
        verify(http, never()).get(LATEST);
    }

    /**
     * 开关关着时取数口连一次都不该被调
     * <p>
     * 桩记下调用次数、回一个比当前新的版本：开关判断一旦被忽略，桩会被调到、也会出待提示，
     * 两条断言都会红。桩不能靠抛错来记这次调用——取数那步抛的异常会被当成取不到吃掉，
     * 抛了也看不出来，这一格会替漏掉开关的改动一直绿着。
     */
    @Test
    @DisplayName("功能关着：一次都不去取，也不提示")
    void disabledCheckDoesNothing() {
        properties.getConfigUi().getUpdate().setEnabled(false);
        AtomicInteger fetches = new AtomicInteger();
        UpdateCheckService.SourceClient newer = release("v5.1.0", "修了开播误报");
        UpdateCheckService service = service(buildOf("5.0.0"), url -> {
            fetches.incrementAndGet();
            return newer.fetch(url);
        });

        service.checkNow();

        assertAll(
                () -> assertEquals(0, fetches.get(), "开关关着就不该去取版本"),
                () -> assertTrue(service.pendingUpdate().isEmpty(), "没取过就没有可提示的新版"));
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

        HttpUtil http = responses("[{\"tag_name\":\"v6.0.0-beta.2\"}]");
        UpdateCheckService beta = new UpdateCheckService(properties, http, state, buildOf("6.0.0-beta.1"));
        beta.checkNow();
        assertTrue(beta.skip("v6.0.0-beta.2"));
        when(http.get(RELEASES)).thenReturn("[{\"tag_name\":\"6.0.0-beta.2\"}]");
        beta.checkNow();
        assertTrue(beta.pendingUpdate().isEmpty(), "跳过版本的比较忽略 v 前缀");
        when(http.get(RELEASES)).thenReturn("""
                [{"tag_name":"v6.0.0-beta.2"},{"tag_name":"v6.0.0-beta.10"},{"tag_name":"v6.0.0-beta.3"}]
                """);
        beta.checkNow();
        assertEquals("v6.0.0-beta.10", beta.pendingUpdate().orElseThrow().version(), "取数值最大的测试版并恢复提示");
    }

    /**
     * 跳过记录的落盘那一半
     * <p>
     * 上面那条与它共用同一个内存里的状态对象，验的是判定逻辑；真重启之后还认不认，
     * 只有换一个新 {@link NovaStateStore}、让它从盘上读一遍才算验过——
     * 跳过若没落盘，「点一下别烦我」只活到下次重启，而那正是它唯一被点下的场合。
     */
    @Test
    @DisplayName("跳过记录随状态文件跨重启生效")
    void skipSurvivesRestart() {
        UpdateCheckService before = service(buildOf("5.0.0"), release("v5.1.0", "内容"));
        before.checkNow();
        before.skip("v5.1.0");

        NovaStateStore reloaded = new NovaStateStore(properties);
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

    /**
     * 来源被换成别人的镜像、或发布页被篡改时，说明地址可能写成一段脚本。
     * 原样交给控制台的话，管理员点「看完整说明」，那段脚本就在已登录的控制台里跑。
     */
    @Test
    @DisplayName("说明地址是 javascript: 一类：不交出链接，版本照常提示")
    void scriptUrlIsDropped() {
        for (String url : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)")) {
            UpdateCheckService service = service(buildOf("5.0.0"), release("v5.1.0", "内容", url));

            service.checkNow();

            var update = service.pendingUpdate();
            assertTrue(update.isPresent(), "地址不能用不影响「有新版」这件事: " + url);
            assertEquals("v5.1.0", update.get().version());
            assertEquals("", update.get().url(), "不是 http(s) 的说明地址不该交给页面: " + url);
        }
    }

    @Test
    @DisplayName("说明地址协议大小写混写的 https：照常交出")
    void mixedCaseHttpsUrlIsKept() {
        UpdateCheckService service = service(buildOf("5.0.0"), release("v5.1.0", "内容", "HTTPS://example.invalid/r"));

        service.checkNow();

        assertEquals("HTTPS://example.invalid/r", service.pendingUpdate().orElseThrow().url());
    }

    private UpdateCheckService service(ObjectProvider<BuildProperties> build, UpdateCheckService.SourceClient source) {
        return new UpdateCheckService(properties, state, build, source);
    }

    private static HttpUtil responses(String releases) {
        HttpUtil http = mock(HttpUtil.class);
        when(http.get(LATEST)).thenReturn("{\"tag_name\":\"v5.9.2\"}");
        when(http.get(RELEASES)).thenReturn(releases);
        return http;
    }

    private static UpdateCheckService.SourceClient release(String tag, String body) {
        return release(tag, body, "https://example.invalid/release");
    }

    private static UpdateCheckService.SourceClient release(String tag, String body, String htmlUrl) {
        JSONObject json = new JSONObject();
        json.put("tag_name", tag);
        json.put("body", body);
        json.put("html_url", htmlUrl);
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
