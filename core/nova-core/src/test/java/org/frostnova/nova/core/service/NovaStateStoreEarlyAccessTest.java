package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.api.NovaApiTokenStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动后、就绪事件之前就有人来用运行状态
 * <p>
 * 网页服务先开始接请求，就绪事件后发。重启时恰好有人用口令登录，登录会记一次
 * 「本地管理员最近登录时间」并立即存盘；那一刻状态若还没读进来，存下去的就是一份
 * 只剩本地管理员的空壳，盖掉盘上原有的成员账号、访问令牌与订阅名单。
 */
@DisplayName("运行状态在就绪之前被用到")
class NovaStateStoreEarlyAccessTest {
    private static final String SUBSCRIPTIONS = "AtSubscriptions";

    private static NovaStateStore storeAt(Path dir) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return new NovaStateStore(properties);
    }

    @Test
    @DisplayName("重启时恰好有人登录：成员账号、访问令牌与订阅名单都还在，登录与令牌校验按原有状态判")
    void loginDuringRestartKeepsAccountsAndTokens(@TempDir Path dir) throws Exception {
        NovaStateStore first = storeAt(dir);
        first.onApplicationReadyEvent();
        ConfigUiAccountStore firstAccounts = new ConfigUiAccountStore(first);
        NovaApiTokenStore firstTokens = new NovaApiTokenStore(first);
        String memberId = firstAccounts.register(
                new OidcClient.Identity("https://id.example", "member-1", "成员甲", "a@example.com"),
                Instant.parse("2026-01-01T00:00:00Z")).id();
        firstAccounts.status(memberId, ConfigUiAccount.Status.ENABLED);
        String plaintext = firstTokens.issue(memberId, "脚本", List.of("status:read"), null).plaintext();
        first.write(SUBSCRIPTIONS, data -> data.put("qq:10001:20001:live", new JSONObject().fluentPut("30001", 1)));
        first.save();
        first.onContextClosedEvent();

        Path state = dir.resolve("state.json");
        JSONObject before = JSONObject.parseObject(Files.readString(state));

        NovaStateStore restarted = storeAt(dir);
        ConfigUiAccountStore accounts = new ConfigUiAccountStore(restarted);
        NovaApiTokenStore tokens = new NovaApiTokenStore(restarted);
        try {
            // 就绪事件还没来，请求已经到了
            assertTrue(accounts.find(memberId).isPresent(), "就绪之前查账号，已有的成员账号查不到");
            assertEquals(ConfigUiAccount.Status.ENABLED, accounts.require(memberId).status(),
                    "就绪之前查账号，成员账号的状态不对");
            assertTrue(tokens.match(plaintext).isPresent(), "就绪之前校验访问令牌，已签发的令牌不被认");
            assertTrue(tokens.match("nvb_not-a-real-token").isEmpty(), "就绪之前校验访问令牌，随便一串被认了");

            // 口令登录会记本地管理员的最近登录时间并立即存盘
            accounts.loggedIn(ConfigUiAccount.LOCAL_ID, Instant.parse("2026-02-01T00:00:00Z"));

            JSONObject onDisk = JSONObject.parseObject(Files.readString(state));
            assertNotNull(onDisk.getJSONObject(ConfigUiAccountStore.NAMESPACE).getJSONObject(memberId),
                    "就绪之前的一次保存盖掉了盘上的成员账号");
            assertEquals(before.getJSONObject(NovaApiTokenStore.NAMESPACE),
                    onDisk.getJSONObject(NovaApiTokenStore.NAMESPACE), "就绪之前的一次保存盖掉了盘上的访问令牌");
            assertEquals(before.getJSONObject(SUBSCRIPTIONS), onDisk.getJSONObject(SUBSCRIPTIONS),
                    "就绪之前的一次保存盖掉了盘上的订阅名单");

            restarted.onApplicationReadyEvent();

            assertTrue(accounts.find(memberId).isPresent(), "就绪之后成员账号没了");
            assertTrue(tokens.match(plaintext).isPresent(), "就绪之后访问令牌不被认");
            assertEquals(Instant.parse("2026-02-01T00:00:00Z"),
                    accounts.require(ConfigUiAccount.LOCAL_ID).lastLoginAt(), "就绪之前那次登录记下的时间被读入冲掉了");
            assertEquals(1, restarted.namespace(SUBSCRIPTIONS).getJSONObject("qq:10001:20001:live")
                    .getIntValue("30001"), "就绪之后订阅名单没了");
        } finally {
            restarted.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("就绪之前直接写一笔再存盘：盘上原有的内容一段不少，新写的那笔也在")
    void directSaveBeforeReadyKeepsExistingFile(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        String original = "{\"AtSubscriptions\":{\"qq:10001:20001:live\":{\"30001\":1}},"
                + "\"config-ui-accounts\":{\"m\":{\"role\":\"MEMBER\"}}}";
        Files.writeString(state, original);

        NovaStateStore store = storeAt(dir);
        try {
            store.save();
            assertEquals(JSONObject.parseObject(original), JSONObject.parseObject(Files.readString(state)),
                    "就绪之前什么也没写的一次保存改了盘上的状态文件");

            store.write("Marker", data -> data.put("seen", true));
            assertTrue(store.trySave(), "就绪之前的保存没有写进磁盘");

            JSONObject onDisk = JSONObject.parseObject(Files.readString(state));
            assertEquals(JSONObject.parseObject(original).getJSONObject(SUBSCRIPTIONS), onDisk.getJSONObject(SUBSCRIPTIONS),
                    "就绪之前的保存盖掉了盘上的订阅名单");
            assertNotNull(onDisk.getJSONObject("config-ui-accounts"), "就绪之前的保存盖掉了盘上的账号表");
            assertTrue(onDisk.getJSONObject("Marker").getBooleanValue("seen"), "就绪之前写的那一笔没存下");

            store.onApplicationReadyEvent();
            assertTrue(store.namespace("Marker").getBooleanValue("seen"), "就绪时的读入把之前写的那一笔冲掉了");
        } finally {
            store.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("就绪之前写入但未保存的订阅在就绪后仍可读取并保存")
    void unsavedSubscriptionSurvivesReadyEvent(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        String session = "qq:10001:20001:live";
        JSONObject original = JSONObject.of(SUBSCRIPTIONS, JSONObject.of(session, JSONObject.of("30001", 1)));
        Files.writeString(state, original.toJSONString());

        NovaStateStore store = storeAt(dir);
        try {
            store.write(SUBSCRIPTIONS, data -> data.getJSONObject(session).put("30002", 1));
            assertEquals(original, JSONObject.parseObject(Files.readString(state)), "就绪之前的写入应只改动内存");

            store.onApplicationReadyEvent();

            JSONObject expected = JSONObject.of(session, JSONObject.of("30001", 1, "30002", 1));
            assertEquals(expected, store.namespace(SUBSCRIPTIONS), "就绪后应保留原有订阅和未保存的新订阅");
            assertTrue(store.trySave(), "就绪之后保存订阅失败");
            assertEquals(JSONObject.of(SUBSCRIPTIONS, expected), JSONObject.parseObject(Files.readString(state)),
                    "保存后的状态文件应包含原有订阅和新订阅");
        } finally {
            store.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("全新安装：就绪之前写一笔照常建出状态文件，就绪之后照常可用")
    void freshInstallBeforeReadyStillCreatesFile(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        NovaStateStore store = storeAt(dir);
        try {
            assertTrue(new ConfigUiAccountStore(store).find("someone").isEmpty(), "全新安装凭空多出账号");
            store.write("Marker", data -> data.put("seen", true));
            store.save();
            assertTrue(Files.exists(state), "全新安装时就绪之前的保存没建出状态文件");

            store.onApplicationReadyEvent();
            assertTrue(store.namespace("Marker").getBooleanValue("seen"), "全新安装时就绪之前写的那一笔没了");
        } finally {
            store.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("状态文件坏了：就绪之前先被用到，照旧改名留底、以空状态继续，就绪时不再挪第二次")
    void corruptFileFirstTouchedBeforeReadyIsParkedOnce(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        byte[] broken = "{\"AtSubscriptions\":{\"qq:1:2:live\":".getBytes(StandardCharsets.UTF_8);
        Files.write(state, broken);

        NovaStateStore store = storeAt(dir);
        try {
            assertTrue(store.namespace(SUBSCRIPTIONS).isEmpty(), "坏文件读出了半份名单");
            store.onApplicationReadyEvent();
            try (Stream<Path> children = Files.list(dir)) {
                List<Path> parked = children.filter(p -> p.getFileName().toString().startsWith("state.json.bad-"))
                        .toList();
                assertEquals(1, parked.size(), "坏件留底的份数不对");
                assertArrayEquals(broken, Files.readAllBytes(parked.get(0)), "留底的坏件字节变了");
            }
        } finally {
            store.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("就绪之前就停机、期间没人用过：不读也不写状态文件")
    void closingBeforeReadyWithoutUseLeavesFileAlone(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        String original = "{\"AtSubscriptions\":{\"qq:10001:20001:live\":{\"30001\":1}}}";
        Files.writeString(state, original);

        storeAt(dir).onContextClosedEvent();

        assertEquals(original, Files.readString(state), "没读入就停机时动了状态文件");
    }
}
