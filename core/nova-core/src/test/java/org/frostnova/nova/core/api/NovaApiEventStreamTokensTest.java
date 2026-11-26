package org.frostnova.nova.core.api;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.protocol.EventStreamAccessTokens;
import org.frostnova.nova.core.protocol.EventStreamTokenService.Verdict;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外壳这一侧：一把访问令牌能不能连事件流、此刻还能不能接着连
 */
class NovaApiEventStreamTokensTest {
    @TempDir
    Path directory;

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private NovaApiTokenStore tokens;

    private ConfigUiAccountStore accounts;

    private NovaApiEventStreamTokens events;

    private NovaStateStore state;

    @BeforeEach
    void stores() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        state = new NovaStateStore(properties);
        tokens = new NovaApiTokenStore(state, clock);
        accounts = new ConfigUiAccountStore(state);
        events = new NovaApiEventStreamTokens(tokens, accounts);
    }

    private ConfigUiAccount account(String subject, String name, ConfigUiAccount.Role role) {
        ConfigUiAccount registered = accounts.register(new OidcClient.Identity("https://accounts.example.com", subject,
                name, null), Instant.now());
        accounts.status(registered.id(), ConfigUiAccount.Status.ENABLED);
        return accounts.role(registered.id(), role);
    }

    private NovaApiTokenStore.Issued issue(String accountId, String name, List<String> scopes, Duration validity) {
        return tokens.issue(accountId, name, scopes, validity);
    }

    // 抓的故障：范围登记成了人人可勾，成员也能签出一把读全部主播事件的令牌。
    @Test
    void scopeIsAdminOnlyAndNamedForTheSigningPage() {
        assertEquals("events:read", NovaApiEventStreamTokens.SCOPE.id());
        assertEquals("读取事件流", NovaApiEventStreamTokens.SCOPE.name());
        assertTrue(NovaApiEventStreamTokens.SCOPE.adminOnly());
        assertTrue(NovaApiEventStreamTokens.SCOPE.description().contains("本机"),
                "说明里要讲清事件流只接受本机连接：" + NovaApiEventStreamTokens.SCOPE.description());
    }

    // 抓的故障：只读口令被当成访问令牌去查表（升级后老面板连不上），或者访问令牌没被认出来。
    @Test
    void claimsOnlyTheAccessTokenPrefix() {
        String plaintext = issue(ConfigUiAccount.LOCAL_ID, "面板", List.of(NovaApiEventStreamTokens.READ), null).plaintext();
        assertTrue(events.claims(plaintext));
        assertTrue(events.claims("nvb_anything"));
        assertFalse(events.claims(null));
        assertFalse(events.claims(""));
        assertFalse(events.claims("Q2hlY2tPbmx5VGhlUHJlZml4LW5vdC1hLXRva2Vu"));
    }

    // 抓的故障：管理员签了勾了「读取事件流」的令牌，事件流却不认；或者日志里记的说明带出了令牌本身。
    @Test
    void adminTokenWithTheScopeIsGrantedAndNamedWithoutTheSecret() {
        var issued = issue(ConfigUiAccount.LOCAL_ID, "客厅的面板", List.of(NovaApiEventStreamTokens.READ), null);

        EventStreamAccessTokens.Check check = events.check(issued.plaintext());

        assertEquals(Verdict.OK, check.verdict());
        assertTrue(check.mine());
        assertEquals(issued.token().id(), check.grant().id());
        String label = check.grant().label();
        assertTrue(label.contains("本地管理员") && label.contains("客厅的面板") && label.contains(issued.token().id()), label);
        assertFalse(label.contains(issued.plaintext()));
        assertFalse(label.contains(issued.plaintext().substring(NovaApiTokenStore.PREFIX.length())));
        assertFalse(label.contains(issued.token().digest().substring(0, 16)), "说明里出现了摘要");
        assertNotNull(tokens.find(issued.token().id()).orElseThrow().lastUsedAt(), "连上了要记一次最后使用");
    }

    // 抓的故障：只勾了外发箱范围、只有推送范围、什么都没勾的令牌也能连事件流；
    // 或者它们得到的回应与乱填一串不同，拿着的人看得出令牌存在。
    @Test
    void tokensWithoutTheScopeAreNotEventCredentialsAtAll() {
        for (List<String> scopes : List.of(List.of("outbox:read", "outbox:deliver"), List.of("push:send"), List.<String>of())) {
            String plaintext = issue(ConfigUiAccount.LOCAL_ID, "没勾事件流", scopes, null).plaintext();
            assertEquals(EventStreamAccessTokens.Check.notMine(), events.check(plaintext), "范围 " + scopes);
        }
        assertEquals(EventStreamAccessTokens.Check.notMine(), events.check("nvb_" + "x".repeat(43)));
        assertEquals(EventStreamAccessTokens.Check.notMine(), events.check("nvb_"));
    }

    // 抓的故障：一把从没勾过事件流的令牌吊销或过期之后，事件流回 revoked／expired，等于告诉对方「这把存在过」。
    @Test
    void revokedOrExpiredTokensWithoutTheScopeStillLookUnknown() {
        var revoked = issue(ConfigUiAccount.LOCAL_ID, "推送脚本", List.of("push:send"), null);
        tokens.revoke(revoked.token().id());
        var expiring = issue(ConfigUiAccount.LOCAL_ID, "外发脚本", List.of("outbox:read"), Duration.ofDays(30));
        clock.now = clock.now.plus(Duration.ofDays(31));

        assertEquals(EventStreamAccessTokens.Check.notMine(), events.check(revoked.plaintext()));
        assertEquals(EventStreamAccessTokens.Check.notMine(), events.check(expiring.plaintext()));
    }

    // 抓的故障：吊销了、过期了的事件流令牌照样连得上，或回的原因与实情对不上。
    @Test
    void revokedAndExpiredEventTokensSayWhich() {
        var revoked = issue(ConfigUiAccount.LOCAL_ID, "旧面板", List.of(NovaApiEventStreamTokens.READ), null);
        tokens.revoke(revoked.token().id());
        var expiring = issue(ConfigUiAccount.LOCAL_ID, "临时面板", List.of(NovaApiEventStreamTokens.READ), Duration.ofDays(30));
        clock.now = clock.now.plus(Duration.ofDays(30));

        assertEquals(EventStreamAccessTokens.Check.refused(Verdict.REVOKED), events.check(revoked.plaintext()));
        assertEquals(EventStreamAccessTokens.Check.refused(Verdict.EXPIRED), events.check(expiring.plaintext()));
    }

    // 抓的故障：成员拿自己的令牌连上事件流，看到了别人主播的全部弹幕（令牌是绕过签发页直接写进表里的）。
    @Test
    void memberTokensNeverPassEvenIfTheScopeWasForged() {
        ConfigUiAccount member = account("subject-m", "成员甲", ConfigUiAccount.Role.MEMBER);
        var forged = issue(member.id(), "伪造的", List.of(NovaApiEventStreamTokens.READ), null);

        assertEquals(EventStreamAccessTokens.Check.refused(Verdict.BAD_TOKEN), events.check(forged.plaintext()));
    }

    // 抓的故障：账号停用、删除或被降成成员之后，名下勾了事件流的令牌还能连。
    @Test
    void disabledDeletedOrDemotedOwnersAreRefused() {
        ConfigUiAccount disabled = account("subject-d", "停用的管理员", ConfigUiAccount.Role.ADMIN);
        var disabledToken = issue(disabled.id(), "面板", List.of(NovaApiEventStreamTokens.READ), null);
        ConfigUiAccount demoted = account("subject-r", "被降的管理员", ConfigUiAccount.Role.ADMIN);
        var demotedToken = issue(demoted.id(), "面板", List.of(NovaApiEventStreamTokens.READ), null);
        ConfigUiAccount deleted = account("subject-x", "删掉的", ConfigUiAccount.Role.ADMIN);
        var deletedToken = issue(deleted.id(), "面板", List.of(NovaApiEventStreamTokens.READ), null);
        for (var issued : List.of(disabledToken, demotedToken, deletedToken)) {
            assertEquals(Verdict.OK, events.check(issued.plaintext()).verdict(), "阳性对照：改之前是认的");
        }

        // 停用直接落在账号表上、不经控制台（控制台那一步会把令牌一并吊销，那时回的是 revoked）
        accounts.status(disabled.id(), ConfigUiAccount.Status.DISABLED);
        accounts.role(demoted.id(), ConfigUiAccount.Role.MEMBER);
        accounts.role(deleted.id(), ConfigUiAccount.Role.MEMBER);
        accounts.status(deleted.id(), ConfigUiAccount.Status.DISABLED);
        accounts.delete(deleted.id());

        for (var issued : List.of(disabledToken, demotedToken, deletedToken)) {
            assertEquals(EventStreamAccessTokens.Check.refused(Verdict.BAD_TOKEN), events.check(issued.plaintext()));
        }
    }

    // 抓的故障：面板连上之后，令牌被吊销、到期、所属账号被降为成员或停用，复核时却仍说「还能用」。
    @Test
    void recheckFollowsTheCurrentState() {
        ConfigUiAccount admin = account("subject-a", "管理员乙", ConfigUiAccount.Role.ADMIN);
        var revoked = issue(ConfigUiAccount.LOCAL_ID, "会被吊销的", List.of(NovaApiEventStreamTokens.READ), null);
        var expiring = issue(ConfigUiAccount.LOCAL_ID, "会到期的", List.of(NovaApiEventStreamTokens.READ), Duration.ofDays(30));
        var demoted = issue(admin.id(), "会被降的", List.of(NovaApiEventStreamTokens.READ), null);
        var steady = issue(ConfigUiAccount.LOCAL_ID, "一直好的", List.of(NovaApiEventStreamTokens.READ), null);
        EventStreamAccessTokens.Grant revokedGrant = events.check(revoked.plaintext()).grant();
        EventStreamAccessTokens.Grant expiringGrant = events.check(expiring.plaintext()).grant();
        EventStreamAccessTokens.Grant demotedGrant = events.check(demoted.plaintext()).grant();
        EventStreamAccessTokens.Grant steadyGrant = events.check(steady.plaintext()).grant();
        for (var grant : List.of(revokedGrant, expiringGrant, demotedGrant, steadyGrant)) {
            assertEquals(Verdict.OK, events.recheck(grant), "阳性对照：刚连上时复核是通过的");
        }

        tokens.revoke(revoked.token().id());
        clock.now = clock.now.plus(Duration.ofDays(30));
        accounts.role(admin.id(), ConfigUiAccount.Role.MEMBER);

        assertEquals(Verdict.REVOKED, events.recheck(revokedGrant));
        assertEquals(Verdict.EXPIRED, events.recheck(expiringGrant));
        assertEquals(Verdict.BAD_TOKEN, events.recheck(demotedGrant));
        assertEquals(Verdict.OK, events.recheck(steadyGrant), "别的令牌不受影响");
        assertEquals(Verdict.BAD_TOKEN, events.recheck(new EventStreamAccessTokens.Grant("0000000000000000", "表里没有")),
                "表里已经没有这一把（例如被清掉了）也不能接着连");
    }

    // 抓的故障：控制台改不了令牌的范围，但有人手改了运行状态文件、把这把令牌的「读取事件流」去掉了，面板还一直连着。
    @Test
    void recheckNoticesTheScopeIsGone() {
        var issued = issue(ConfigUiAccount.LOCAL_ID, "面板", List.of(NovaApiEventStreamTokens.READ, "push:send"), null);
        EventStreamAccessTokens.Grant grant = events.check(issued.plaintext()).grant();
        assertEquals(Verdict.OK, events.recheck(grant));

        state.write(NovaApiTokenStore.NAMESPACE, data -> data.getJSONObject(issued.token().id())
                .put("scopes", new com.alibaba.fastjson2.JSONArray(List.of("push:send"))));

        assertEquals(Verdict.BAD_TOKEN, events.recheck(grant));
        assertEquals(EventStreamAccessTokens.Check.notMine(), events.check(issued.plaintext()));
    }

    // 抓的故障：控制台里吊销了令牌、停用或删除了账号、改了角色，事件流要等下一次定时复核才知道。
    @Test
    void everyRelevantChangeNotifiesRightAway() {
        AtomicInteger calls = new AtomicInteger();
        Runnable listener = calls::incrementAndGet;
        events.addChangeListener(listener);
        ConfigUiAccount admin = account("subject-n", "管理员丙", ConfigUiAccount.Role.ADMIN);
        var issued = issue(admin.id(), "面板", List.of(NovaApiEventStreamTokens.READ), null);
        var second = issue(admin.id(), "另一块", List.of(NovaApiEventStreamTokens.READ), null);

        int before = calls.get();
        tokens.revoke(issued.token().id());
        assertTrue(calls.get() > before, "吊销一把要通知");

        before = calls.get();
        accounts.role(admin.id(), ConfigUiAccount.Role.MEMBER);
        assertTrue(calls.get() > before, "改角色要通知");

        before = calls.get();
        accounts.status(admin.id(), ConfigUiAccount.Status.DISABLED);
        assertTrue(calls.get() > before, "停用要通知");

        before = calls.get();
        assertEquals(1, tokens.revokeAccount(admin.id()));
        assertTrue(calls.get() > before, "一并吊销账号名下的要通知");

        before = calls.get();
        accounts.delete(admin.id());
        assertTrue(calls.get() > before, "删账号要通知");

        events.removeChangeListener(listener);
        before = calls.get();
        tokens.revoke(second.token().id());
        accounts.register(new OidcClient.Identity("https://accounts.example.com", "subject-z", "新来的", null), Instant.now());
        assertEquals(before, calls.get(), "撤掉之后不该再通知");
    }

    // 抓的故障：通知那一头出了错，吊销本身跟着失败，令牌留在「能用」的状态。
    @Test
    void aFailingListenerDoesNotBreakTheChange() {
        events.addChangeListener(() -> {
            throw new IllegalStateException("listener failed");
        });
        var issued = issue(ConfigUiAccount.LOCAL_ID, "面板", List.of(NovaApiEventStreamTokens.READ), null);

        assertTrue(tokens.revoke(issued.token().id()).orElseThrow().changed());
        assertTrue(tokens.find(issued.token().id()).orElseThrow().revoked());
        assertEquals(Verdict.REVOKED, events.check(issued.plaintext()).verdict());
    }

    // 抓的故障：读取事件流被做成了一组接口，出现在「从别的机器访问」的开关里，
    // 管理员以为打开它就能从外面连事件流（事件流只接受本机）。
    @Test
    void theScopeIsBuiltInAndNotAGroupThatCanBeOpened() {
        NovaApiProvider outbox = new NovaApiProvider() {
            @Override
            public String segment() {
                return "outbox";
            }

            @Override
            public List<NovaApiScope> scopes() {
                return List.of(new NovaApiScope("outbox:read", "读取外发箱", ""));
            }

            @Override
            public List<NovaApiRoute> routes() {
                return List.of(NovaApiRoute.get("messages", "outbox:read", request -> null));
            }
        };
        NovaApiProvider impostor = new NovaApiProvider() {
            @Override
            public String segment() {
                return "events";
            }

            @Override
            public List<NovaApiScope> scopes() {
                return List.of(new NovaApiScope("events:read", "冒名的", ""));
            }

            @Override
            public List<NovaApiRoute> routes() {
                return List.of(NovaApiRoute.get("all", "events:read", request -> null));
            }
        };
        NovaApiRegistry registry = new NovaApiRegistry(List.of(NovaApiEventStreamTokens.SCOPE), List.of(outbox, impostor));

        assertTrue(registry.adminOnly("events:read"));
        assertEquals("读取事件流", registry.scope("events:read").orElseThrow().name(), "插件不能顶掉底座的这一项");
        assertEquals(List.of("events:read", "outbox:read"), registry.scopes().stream().map(NovaApiScope::id).toList());
        assertEquals(List.of("outbox"), registry.groups().stream().map(NovaApiRegistry.Group::segment).toList(),
                "事件流不是一组接口；冒名登记 events:read 的那一家整个不予采用");
        NovaApiOpenGroups open = new NovaApiOpenGroups(() -> List.of("events", "outbox"), registry);
        assertFalse(open.opens("events"));
        assertEquals(List.of("outbox"), List.copyOf(open.effective()));
    }
}
