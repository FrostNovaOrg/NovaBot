package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.*;
import org.frostnova.nova.console.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.config.ui.page.*;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemberListScopeTest {
    static final long A = 71001, B = 72002, SHARED = 81001, OTHER = 82002;
    static final ConfigUiAccount MEMBER = new ConfigUiAccount("member-a", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member A", null, null, null, List.of("bilibili:" + A), Instant.EPOCH, null, true);
    @TempDir Path dir;
    Path file;
    NovaCoreProperties props;
    AbstractDataSource source;
    DataSourceServiceRegistry platforms;
    ConfigUiController config;
    RuntimeStateController runtime;
    StreamerController streamers;
    AtAllQuotaController quota;
    PushController push;
    TimelineStore timeline;
    PushActivityRecorder recorder;
    Map<Class<?>, Object> dependencies;

    @BeforeEach void setup() throws Exception {
        props = new NovaCoreProperties();
        file = dir.resolve("datasource.json");
        props.getDatasource().setJsonPath(file.toString());
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var a = user(A, "Owner A", SHARED);
        var b = user(B, "Other B", SHARED, OTHER);
        JSONArray rawUsers = JSONArray.parseArray(JSON.toJSONString(List.of(a, b)));
        for (Object value : rawUsers) for (Object entry : ((JSONObject)value).getJSONArray("targets")) ((JSONObject)entry).put("type", 1);
        Files.writeString(file, rawUsers.toJSONString());
        platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of(a, b));
        timeline = new TimelineStore(props);
        recorder = new PushActivityRecorder(timeline);
        dependencies = new HashMap<>();
        dependencies.put(NovaCoreProperties.class, props);
        dependencies.put(AbstractDataSource.class, source);
        dependencies.put(TimelineStore.class, timeline);
        dependencies.put(PushActivityRecorder.class, recorder);
        dependencies.put(PushGate.class, new PushGate(props));
        dependencies.put(StreamerDirectory.class, new StreamerDirectory(props));
        var names = mock(StreamerNames.class);
        when(names.uname(any(PushUser.class))).thenAnswer(i -> ((PushUser)i.getArgument(0)).getUname());
        dependencies.put(StreamerNames.class, names);
        dependencies.put(ConfigurationValidator.class, new ConfigurationValidator(mock(NovaEventHandlerService.class)));
        config = create(ConfigUiController.class);
        runtime = create(RuntimeStateController.class);
        streamers = create(StreamerController.class);
        quota = create(AtAllQuotaController.class);
        when(((AtAllQuotaService)dependencies.get(AtAllQuotaService.class)).today()).thenReturn(LocalDate.now());
        push = create(PushController.class);
        for (var controller : List.of(runtime, quota)) {
            if (Arrays.stream(controller.getClass().getDeclaredFields()).anyMatch(field -> field.getName().equals("configured")))
                ReflectionTestUtils.setField(controller, "configured", new ConfigUiConfiguredUsers(props));
        }
        var live = (LiveDataService)dependencies.get(LiveDataService.class);
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(true));
        use(null);
    }

    @SuppressWarnings("unchecked") <T> T create(Class<T> type) throws Exception {
        var constructor = Arrays.stream(type.getConstructors()).min(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        return (T)constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                .map(t -> dependencies.computeIfAbsent(t, k -> mock(t))).toArray());
    }
    static PushUser user(long uid, String name, long... groups) {
        var u = new PushUser(); u.setPlatform("bilibili"); u.setUid(uid); u.setUname(name); u.setEnabled(true);
        var targets = new ArrayList<PushTarget>();
        for (long num : groups) {
            var t = new PushTarget(); t.setPlatform("qq"); t.setType(PushTargetType.GROUP); t.setNum(num); t.setEnabled(true); t.setUser(u);
            targets.add(t);
        }
        u.setTargets(targets); return u;
    }
    void use(ConfigUiAccount account) {
        var request = new MockHttpServletRequest();
        if (account != null) request.setAttribute(ConfigUiAccess.ACCOUNT, account);
        request.setAttribute(ConfigUiAccess.PLATFORMS, platforms);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    void concealed(JSONObject json) {
        String text = json.toJSONString();
        for (String secret : List.of(String.valueOf(B), "Other B", String.valueOf(OTHER)))
            assertFalse(text.contains(secret), "Unexpected other streamer data: " + secret + " in " + text);
    }
    JSONObject response(String endpoint) {
        return switch (endpoint) {
            case "status" -> config.status();
            case "datasource/users" -> config.datasourceUsers();
            case "streamers" -> streamers.streamers();
            case "state" -> runtime.state();
            case "timeline" -> new TimelineController(timeline).timeline(null, false, null, null, null, null, null, 1, null);
            case "push-history" -> push.pushHistory();
            case "at-all/quota" -> quota.quota();
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    // 停用主播仍在配置名单中，但不能在成员首页显示为在播。
    @Test void memberLiveExcludesDisabledStreamers() throws Exception {
        var raw = JSONArray.parseArray(Files.readString(file));
        raw.getJSONObject(0).put("enabled", false);
        Files.writeString(file, raw.toJSONString());
        when(source.getAllUsers()).thenReturn(List.of(user(B, "Other B", SHARED, OTHER)));
        use(MEMBER);
        assertEquals(1, config.status().getJSONArray("users").size());
        assertTrue(config.status().getJSONArray("live").isEmpty());
    }

    // 成员首页的推送目标数必须与管理员相同，停用通道不计入。
    @Test void memberTargetCountMatchesActiveAdminTargets() throws Exception {
        var raw = JSONArray.parseArray(Files.readString(file));
        var targets = raw.getJSONObject(0).getJSONArray("targets");
        targets.add(JSONObject.of("platform", "qq", "type", 1, "num", 89999, "enabled", false));
        Files.writeString(file, raw.toJSONString());
        use(ConfigUiAccount.local(Instant.EPOCH));
        int expected = config.status().getJSONArray("users").getJSONObject(0).getIntValue("targets");
        use(MEMBER);
        assertEquals(expected, config.status().getJSONArray("users").getJSONObject(0).getIntValue("targets"));
    }

    // 今日卡必须出现在成员 pages 清单中，否则名下推送计数没有展示入口。
    @Test @SuppressWarnings("unchecked") void todayCardIsIncludedForMembers() {
        ObjectProvider<ConsolePageProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(new TodayHomeCardProvider()));
        ReflectionTestUtils.setField(config, "pageProviders", providers);
        use(MEMBER);
        assertEquals(List.of("today"), config.pages().getJSONArray("pages").stream()
                .map(item -> ((JSONObject)item).getString("id")).toList());
    }

    // 管理员各列表逐字段对照原有响应，只归一化随运行变化的时刻和资源读数。
    @Test void adminListsKeepOriginalFields() throws Exception {
        recorder.recordSuccess("qq", "群 " + SHARED, "A push", 1);
        recorder.recordFailure("qq", "群 " + OTHER, "B push", "failure", 1);
        ReflectionTestUtils.setField(streamers, "clock", Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        use(ConfigUiAccount.local(Instant.EPOCH));
        JSONObject actual = new JSONObject();
        for (String endpoint : List.of("status", "datasource/users", "streamers", "state", "timeline", "push-history", "at-all/quota", "pages")) {
            JSONObject value = endpoint.equals("pages") ? config.pages() : response(endpoint);
            if (endpoint.equals("status")) value.getJSONObject("runtime").replaceAll((key, reading) -> null);
            normalizeTimes(value);
            actual.put(endpoint, value);
        }
        JSONObject expected = JSONObject.parseObject(Files.readString(Path.of("src/test/resources/member-list-admin.json")));
        normalizeTimes(expected);
        assertEquals(expected, JSONObject.parseObject(actual.toJSONString(JSONWriter.Feature.WriteMapNullValue)));
    }
    private void normalizeTimes(Object value) {
        if (value instanceof JSONObject json) json.replaceAll((key, item) -> {
            if (key.equals("at")) return "time";
            if (key.equals("nextCursor") && item instanceof String cursor) return "date" + cursor.substring(cursor.lastIndexOf(':'));
            if (key.equals("date") && item instanceof String) return "date";
            normalizeTimes(item); return item;
        });
        else if (value instanceof Iterable<?> items) items.forEach(this::normalizeTimes);
    }

    // 成员打开任意列表时，同群别的主播及其独占群都不能出现在回包中；管理员字段保持原样。
    @ParameterizedTest @ValueSource(strings = {"status", "datasource/users", "streamers", "state", "timeline", "push-history", "at-all/quota"})
    void listsConcealOtherStreamersAndPreserveAdmin(String endpoint) {
        timeline.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO).streamer("Other B")
                .channel("群 " + OTHER).detail("streamerPlatform", "bilibili").detail("streamerUid", String.valueOf(B)).build());
        recorder.recordSuccess("qq", "群 " + OTHER, "Other B", 1);
        JSONObject before = response(endpoint);
        use(MEMBER);
        JSONObject member = response(endpoint);
        assertTrue(member.getBooleanValue("success"));
        concealed(member);
        if (endpoint.equals("status")) for (String key : List.of("health", "runtime", "version", "update", "configPath", "streamerLimit", "restartPending", "senders", "queue", "alerts"))
            assertFalse(member.containsKey(key), key);
        if (endpoint.equals("streamers")) assertFalse(member.containsKey("notConfigured"));
        if (endpoint.equals("at-all/quota")) assertFalse(member.containsKey("bots"));
        if (endpoint.equals("state")) {
            assertEquals(1, member.getJSONArray("sessions").size());
            var row = member.getJSONArray("sessions").getJSONObject(0);
            assertEquals(1, row.getJSONArray("channels").size());
            for (String key : List.of("revenueVisible", "revenueExplicit", "quietMode", "quietStart", "quietEnd", "quietActive")) assertFalse(row.containsKey(key), key);
            for (String key : List.of("disabled", "menuHidden", "menuNotes")) assertTrue(row.containsKey(key), key);
        }
        use(ConfigUiAccount.local(Instant.EPOCH));
        JSONObject after = response(endpoint);
        if (endpoint.equals("status")) { before.remove("runtime"); after.remove("runtime"); }
        assertEquals(before, after, "Administrator response changed");
    }

    // 插件管理页与内建日志、连接、设置不能再成为成员导航入口。
    @Test @SuppressWarnings("unchecked") void pagesAdvertiseOnlyAccessibleNavigation() {
        ObjectProvider<ConsolePageProvider> pages = mock(ObjectProvider.class);
        var adminPage = mock(ConsolePageProvider.class);
        when(adminPage.id()).thenReturn("admin-tools"); when(adminPage.displayName()).thenReturn("Admin tools");
        when(adminPage.script()).thenReturn("admin-tools.js");
        when(adminPage.slot()).thenReturn(ConsolePageSlot.TOP); when(adminPage.assets()).thenReturn(List.of());
        when(pages.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(new StreamersConsolePageProvider(), new PushConsolePageProvider(), adminPage));
        ReflectionTestUtils.setField(config, "pageProviders", pages);
        var before = config.pages();
        assertTrue(before.getJSONArray("pages").stream().map(item -> ((JSONObject)item).getString("id")).toList().contains("admin-tools"));
        use(MEMBER);
        var result = config.pages();
        assertEquals(List.of("home", "push", "streamers"), result.getList("accessiblePages", String.class));
        assertFalse(result.toJSONString().contains("admin-tools"));
        use(ConfigUiAccount.local(Instant.EPOCH)); assertEquals(before, config.pages());
    }

    // 停用主播或通道仍应留在成员名单里，静音指示取各通道自己的时段。
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void disabledStreamersAndTargetsKeepTheirQuietState(boolean disableUser) throws Exception {
        var users = JSONArray.parseArray(Files.readString(file));
        users.getJSONObject(0).put("enabled", !disableUser);
        var target = users.getJSONObject(0).getJSONArray("targets").getJSONObject(0);
        target.put("enabled", disableUser);
        LocalTime now = LocalTime.now();
        target.put("quietHours", JSONObject.of("mode", "custom", "start", now.minusHours(2).toString().substring(0,5), "end", now.plusHours(2).toString().substring(0,5)));
        Files.writeString(file, users.toJSONString());
        var b = user(B, "Other B", SHARED, OTHER);
        when(source.getAllUsers()).thenReturn(disableUser ? List.of(b) : List.of(user(A, "Owner A"), b));
        for (ConfigUiAccount account : Arrays.asList(null, MEMBER)) {
            use(account);
            var channels = runtime.state().getJSONArray("sessions").getJSONObject(0).getJSONArray("channels");
            assertTrue(channels.stream().map(x -> (JSONObject)x).anyMatch(x -> x.getLongValue("streamerUid") == A && x.getBooleanValue("quietActive")));
        }
        use(MEMBER);
        assertEquals(A, config.status().getJSONArray("users").getJSONObject(0).getLongValue("uid"));
        assertEquals(disableUser ? List.of() : List.of(A), config.status().getJSONArray("live").stream()
                .map(item -> ((JSONObject)item).getLongValue("uid")).toList());
        assertEquals(A, config.datasourceUsers().getJSONArray("users").getJSONObject(0).getLongValue("uid"));
        assertEquals(A, streamers.streamers().getJSONArray("streamers").getJSONObject(0).getLongValue("uid"));
        assertEquals(1, quota.quota().getJSONArray("sessions").size());
    }

    // 首页只数名下主播的推送，旧记录不能算给成员，新事件还能按同一主播名筛选。
    @Test void todayHistoryAndTimelineUseStreamerIdentityBeforePagination() {
        var a = user(A, "Owner A", SHARED).getTargets().get(0);
        var b = user(B, "Other B", SHARED, OTHER).getTargets().get(1);
        recorder.recordSuccess("qq", "群 " + SHARED, "A one", 1, a);
        recorder.recordSuccess("qq", "群 " + SHARED, "A two", 1, a);
        for (int i = 0; i < 3; i++) recorder.recordSuccess("qq", "群 " + OTHER, "Other B", 1, b);
        use(MEMBER);
        assertEquals(2, config.status().getJSONObject("today").getIntValue("sent"));
        assertEquals(2, push.pushHistory().getJSONArray("records").size());
        var first = new TimelineController(timeline).timeline(LocalDate.now().toString(), false, null, null, null, null, null, 1, null);
        concealed(first);
        assertEquals(2, first.getIntValue("matched")); assertEquals(1, first.getIntValue("returned"));
        assertTrue(first.getBooleanValue("truncated"));
        assertEquals(List.of("Owner A"), first.getList("streamers", String.class));
        assertEquals(List.of("群 " + SHARED), first.getList("channels", String.class));
        var second = new TimelineController(timeline).timeline(null, false, null, null, "Owner A", null, null, 1, first.getString("nextCursor"));
        assertEquals(1, second.getJSONArray("events").size());
        assertFalse(second.getBooleanValue("truncated"));
        use(ConfigUiAccount.local(Instant.EPOCH));
        assertEquals(5, config.status().getJSONObject("today").getIntValue("sent"));
        assertEquals(5, push.pushHistory().getJSONArray("records").size());
        // Same display name and uid on another platform still cannot establish ownership.
        var collision = user(A, "Owner A", OTHER); collision.setPlatform("elsewhere");
        recorder.recordSuccess("qq", "群 " + OTHER, "foreign", 1, collision.getTargets().get(0));
        recorder.recordSuccess("qq", "群 " + OTHER, "legacy", 1);
        recorder.recordFailure("qq", "群 " + SHARED, "A failure", "error", 1, a);
        recorder.recordFailure("qq", "群 " + OTHER, "Other B", "error", 1, b);
        use(MEMBER);
        assertEquals(2, config.status().getJSONObject("today").getIntValue("sent"));
        assertEquals(1, config.status().getJSONObject("today").getIntValue("failed"));
        assertEquals(3, push.pushHistory().getJSONArray("records").size());
        concealed(push.pushHistory());
    }

    // 未填完配置的序号必须对回原主播；同群订阅也不能混入别人的名单。
    @Test void subscriptionsAndIncompleteEntriesFollowTheirStreamer() {
        var subscriptions = (AtSubscriptionService)dependencies.get(AtSubscriptionService.class);
        when(subscriptions.all()).thenReturn(List.of(new AtSubscriptionService.Subscription("qq", SHARED, A, "live", List.of(9L)),
                new AtSubscriptionService.Subscription("qq", SHARED, B, "live", List.of(8L)),
                new AtSubscriptionService.Subscription("qq", OTHER, B, "dynamic", List.of(7L))));
        when(source.getIncompleteEntries()).thenReturn(List.of(new AbstractDataSource.IncompleteEntry(1, List.of("targets")),
                new AbstractDataSource.IncompleteEntry(2, List.of("targets")), new AbstractDataSource.IncompleteEntry(99, List.of("uid"))));
        use(MEMBER);
        var result = runtime.state(); concealed(result);
        assertEquals(1, result.getJSONArray("subscriptions").size());
        assertEquals(A, result.getJSONArray("subscriptions").getJSONObject(0).getLongValue("streamerUid"));
        assertEquals(1, result.getJSONArray("incomplete").size());
        assertEquals(1, result.getJSONArray("incomplete").getJSONObject(0).getIntValue("index"));
        use(ConfigUiAccount.local(Instant.EPOCH));
        assertEquals(3, runtime.state().getJSONArray("incomplete").size());
        assertEquals(3, runtime.state().getJSONArray("subscriptions").size());
    }


    // 旧的非规范授权与未加载平台不能在列表中授权；规范授权在平台加载后才能看到主播。
    @ParameterizedTest @ValueSource(strings = {"status", "datasource/users", "streamers", "state", "timeline", "push-history", "at-all/quota"})
    void listOwnershipMatchesLoadedPlatformsAndCanonicalAssignments(String endpoint) throws Exception {
        var a = user(1, "Owner A", SHARED);
        var users = JSONArray.parseArray(Files.readString(file));
        users.getJSONObject(0).put("uid", 1);
        Files.writeString(file, users.toJSONString());
        when(source.getAllUsers()).thenReturn(List.of(a, user(B, "Other B", SHARED, OTHER)));
        recorder.recordSuccess("qq", "群 " + SHARED, "A push", 1, a.getTargets().get(0));
        var mvc = MockMvcBuilders.standaloneSetup(config, runtime, streamers, quota, push, new TimelineController(timeline))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), props, platforms)).build();
        var legacy = assigned("bilibili:01");
        var canonical = assigned("bilibili:01", "bilibili:1");
        assertScopedLists(mvc, endpoint, legacy, false);
        assertScopedLists(mvc, endpoint, canonical, true);
        assertScopedLists(mvc, endpoint, assigned("Bilibili:1"), false);
        when(platforms.platforms()).thenReturn(List.of());
        assertScopedLists(mvc, endpoint, canonical, false);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        assertScopedLists(mvc, endpoint, canonical, true);
    }

    private ConfigUiAccount assigned(String... ids) {
        return new ConfigUiAccount("member-a", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "Member A", null, null, null, List.of(ids), Instant.EPOCH, null, true);
    }

    private void assertScopedLists(org.springframework.test.web.servlet.MockMvc mvc, String endpoint,
                                   ConfigUiAccount account, boolean visible) throws Exception {
        var result = mvc.perform(get("/config/api/" + endpoint).requestAttr(ConfigUiAccess.ACCOUNT, account))
                .andExpect(status().isOk()).andReturn();
        var json = JSONObject.parseObject(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        concealed(json);
        String key = switch (endpoint) {
            case "status", "datasource/users" -> "users";
            case "streamers" -> "streamers";
            case "state", "at-all/quota" -> "sessions";
            case "timeline" -> "events";
            case "push-history" -> "records";
            default -> throw new IllegalArgumentException(endpoint);
        };
        assertEquals(visible ? 1 : 0, json.getJSONArray(key).size(), endpoint + " assignments=" + account.streamers() + " loaded=" + platforms.platforms());
        if (endpoint.equals("status")) {
            assertEquals(visible ? 1 : 0, json.getJSONArray("live").size());
            assertEquals(visible ? 1 : 0, json.getJSONObject("today").getIntValue("sent"));
        }
        if (endpoint.equals("timeline")) {
            assertEquals(visible ? List.of("Owner A") : List.of(), json.getList("streamers", String.class));
            assertEquals(visible ? List.of("群 " + SHARED) : List.of(), json.getList("channels", String.class));
        }
    }

    // 缺失请求平台注册表时不能把成员名下主播当成已加载平台的数据放行。
    @Test void missingRegistryConcealsMemberLists() {
        use(MEMBER);
        ConfigUiAccess.request().removeAttribute(ConfigUiAccess.PLATFORMS);
        for (String endpoint : List.of("status", "datasource/users", "streamers", "state", "timeline", "push-history", "at-all/quota")) {
            var result = response(endpoint);
            assertFalse(result.toJSONString().contains("Owner A"), endpoint);
        }
    }

    // 时间线大类计数只统计成员主播；别人的推送、直播和无主播旧事件不能混入计数。
    @Test void timelineCategoryCountsOnlyIncludeOwnedStreamerEvents() {
        var a = user(A, "Owner A", SHARED).getTargets().get(0);
        var b = user(B, "Other B", SHARED, OTHER).getTargets().get(1);
        for (int i = 0; i < 2; i++) recorder.recordSuccess("qq", "群 " + SHARED, "A push", 1, a);
        for (int i = 0; i < 3; i++) recorder.recordFailure("qq", "群 " + OTHER, "B push", "error", 1, b);
        recorder.recordSuccess("qq", "legacy", "legacy push", 1);
        timeline.record(TimelineEvent.of(TimelineEventType.LIVE_ON, TimelineEvent.Level.INFO).streamer("Other B")
                .detail("streamerPlatform", "bilibili").detail("streamerUid", String.valueOf(B)).build());
        var controller = new TimelineController(timeline);
        use(MEMBER);
        var member = controller.timeline(LocalDate.now().toString(), false, null, "PUSH_SENT", null, null, null, 1, null);
        assertCategoryCounts(member, Map.of("PUSH", 2));
        assertEquals(2, member.getIntValue("matched"));
        assertEquals(1, member.getIntValue("returned"));
        use(ConfigUiAccount.local(Instant.EPOCH));
        assertCategoryCounts(controller.timeline(LocalDate.now().toString(), false, null, "PUSH_SENT", null, null, null, 1, null),
                Map.of("PUSH", 6, "LIVE", 1));
    }

    private void assertCategoryCounts(JSONObject json, Map<String, Integer> expected) {
        for (Object value : json.getJSONArray("categories")) {
            var category = (JSONObject)value;
            assertEquals(expected.getOrDefault(category.getString("name"), 0), category.getInteger("count"), category.getString("name"));
        }
    }

    // 机器人已有的群和好友可以新增，校验不能把成员加通道一律挡住。
    @Test void memberCanAddVerifiedGroupAndFriend() throws Exception {
        var lookup = mock(PushTargetLookup.class);
        when(lookup.supports("qq")).thenReturn(true);
        when(lookup.lookup(eq("qq"), any(), eq(89999L), eq(MEMBER))).thenReturn(new PushTargetLookup.Result(true, "Target", null));
        @SuppressWarnings("unchecked") ObjectProvider<PushTargetLookup> lookups = mock(ObjectProvider.class);
        when(lookups.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(lookup));
        ReflectionTestUtils.setField(config, "targetLookups", lookups);
        var mvc = MockMvcBuilders.standaloneSetup(config)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), props, platforms)).build();
        use(MEMBER);
        for (int type : List.of(1, 0)) {
            var loaded = config.datasourceUser("bilibili", String.valueOf(A));
            loaded.getJSONObject("user").getJSONArray("targets").add(JSONObject.of("platform", "qq", "type", type, "num", 89999, "messages", new JSONArray()));
            mvc.perform(post("/config/api/datasource/users/bilibili/" + A)
                    .requestAttr(ConfigUiAccess.ACCOUNT, MEMBER).contentType("application/json")
                    .content(JSONObject.of("user", loaded.get("user"), "version", loaded.get("version")).toJSONString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        }
        verify(lookup).lookup("qq", PushTargetType.GROUP, 89999L, MEMBER);
        verify(lookup).lookup("qq", PushTargetType.FRIEND, 89999L, MEMBER);
    }

    // 成员新加机器人不在的群时应拒绝并保留文件，已退群的旧通道不阻止保存。
    @Test void memberCannotSaveUnknownNewTargetButCanKeepExisting() throws Exception {
        var directory = mock(org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory.class);
        when(directory.supports("qq")).thenReturn(true);
        var empty = new org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory.Snapshot("qq", List.of(), List.of(), Instant.now(), Instant.now(), false, null);
        when(directory.cachedSnapshot("qq")).thenReturn(empty);
        when(directory.snapshot("qq", true)).thenReturn(empty);
        var lookup = new org.frostnova.nova.adapter.onebot.service.OneBotTargetLookup(directory);
        @SuppressWarnings("unchecked") ObjectProvider<PushTargetLookup> lookups = mock(ObjectProvider.class);
        when(lookups.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(lookup));
        ReflectionTestUtils.setField(config, "targetLookups", lookups);
        var mvc = MockMvcBuilders.standaloneSetup(config)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), props, platforms)).build();
        use(MEMBER);
        var loaded = config.datasourceUser("bilibili", String.valueOf(A));
        var replacement = loaded.getJSONObject("user");
        replacement.getJSONArray("targets").add(JSONObject.of("platform", "qq", "type", 1, "num", 89999, "messages", new JSONArray()));
        String before = Files.readString(file);
        mvc.perform(post("/config/api/datasource/users/bilibili/" + A)
                .requestAttr(ConfigUiAccess.ACCOUNT, MEMBER).contentType("application/json")
                .content(JSONObject.of("user", replacement, "version", loaded.get("version")).toJSONString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("89999")));
        assertEquals(before, Files.readString(file));
        verify(directory).snapshot("qq", true);
        assertTrue(lookup.lookup("qq", PushTargetType.GROUP, 89998, MEMBER).message().contains("刚查过"));
        verify(directory, times(1)).snapshot("qq", true);
        loaded = config.datasourceUser("bilibili", String.valueOf(A));
        loaded.getJSONObject("user").put("enabled", false);
        assertTrue(config.saveDatasourceUser("bilibili", String.valueOf(A), JSONObject.of("user", loaded.get("user"), "version", loaded.get("version"))).getBody().getBooleanValue("success"));
        verify(directory, times(1)).snapshot("qq", true);
        use(ConfigUiAccount.local(Instant.EPOCH));
        loaded = config.datasourceUser("bilibili", String.valueOf(A));
        loaded.getJSONObject("user").getJSONArray("targets").add(JSONObject.of("platform", "qq", "type", 1, "num", 89999, "messages", new JSONArray()));
        assertTrue(config.saveDatasourceUser("bilibili", String.valueOf(A), JSONObject.of("user", loaded.get("user"), "version", loaded.get("version"))).getBody().getBooleanValue("success"));
    }
}
