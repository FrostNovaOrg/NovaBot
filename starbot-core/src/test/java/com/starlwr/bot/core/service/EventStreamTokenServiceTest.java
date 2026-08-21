package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流只读口令测试
 * <p>
 * 覆盖四条验收前置。<b>其中三条阳性对照不是形式</b>：
 * 「无效被拒」证明不了「已吊销被拒」，因为已吊销的<b>曾经有效</b>——
 * 一个只比对「在不在表里」的实现能通过前者、放行后者。
 */
@DisplayName("事件流只读口令")
class EventStreamTokenServiceTest {
    @TempDir
    Path dir;

    private EventStreamTokenService service;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        service = new EventStreamTokenService(properties);
    }

    @Test
    @DisplayName("① 有效口令应放行")
    void acceptsValidToken() {
        String token = service.issue("面板-甲");

        assertTrue(service.verify(token));
    }

    @Test
    @DisplayName("① 无效口令应拒绝")
    void rejectsInvalidToken() {
        service.issue("面板-甲");

        assertFalse(service.verify("这不是签发过的口令"));
        assertFalse(service.verify(""), "空口令同样是拒");
        assertFalse(service.verify(null), "没出示口令同样是拒");
    }

    @Test
    @DisplayName("⚠️ ① 已吊销的口令必须拒——它曾经有效，与「无效」不是同一件事")
    void rejectsRevokedToken() {
        String token = service.issue("面板-甲");
        assertTrue(service.verify(token), "吊销前应当是通的");

        EventStreamToken record = service.list().get(0);
        assertTrue(service.revoke(service.fingerprintOf(record)));

        assertFalse(service.verify(token),
                "吊销之后必须拒——只测「乱填一串被拒」证明不了吊销真的生效");
    }

    @Test
    @DisplayName("② 守卫：只认本服务签发的口令，别的一律拒")
    void rejectsAnythingNotIssuedHere() {
        service.issue("面板-甲");

        // 这条守卫防的是实现走样：若校验函数图省事加一句
        // 「口令表里没有就再比一次控制台令牌」，铁律当场破，而功能测试会全绿——
        // 面板照样连得上，没人看得出来。
        // 控制台令牌就是一个「没在这里签发过」的串，所以这条断言同时守住了铁律。
        String consoleTokenLike = com.starlwr.bot.core.util.SecureToken.generate();

        assertFalse(service.verify(consoleTokenLike),
                "没在这里签发过的串一律拒，控制台令牌也不例外");
    }

    @Test
    @DisplayName("③ 口令由服务器生成：足够长、每次不同、不含弱片段")
    void generatedTokensAreStrong() {
        String a = service.issue("面板-甲");
        String b = service.issue("面板-乙");

        assertTrue(a.length() >= 32, "长度须 ≥32，实际 " + a.length());
        assertNotEquals(a, b, "两次签发不能撞");
        String lower = a.toLowerCase();
        for (String weak : List.of("token", "password", "admin", "test", "123456")) {
            assertFalse(lower.contains(weak), "不该含弱片段 " + weak + ": " + a);
        }
    }

    @Test
    @DisplayName("③ 明文只在签发那一刻存在，库里只留哈希")
    void storesOnlyHash() {
        String token = service.issue("面板-甲");

        EventStreamToken record = service.list().get(0);

        assertNotEquals(token, record.hash(), "库里不能是明文");
        assertFalse(record.hash().contains(token), "哈希里也不能夹着明文");
        // 能再取回来的明文等于明文落盘——所以列表接口只给指纹，不给原文
        assertFalse(service.list().stream().anyMatch(t -> t.hash().equals(token)));
    }

    @Test
    @DisplayName("吊销是定向的：撤一把不影响另一把")
    void revocationIsTargeted() {
        String a = service.issue("面板-甲");
        String b = service.issue("面板-乙");

        EventStreamToken first = service.list().stream()
                .filter(t -> "面板-甲".equals(t.label())).findFirst().orElseThrow();
        service.revoke(service.fingerprintOf(first));

        assertFalse(service.verify(a), "被撤的那把该拒");
        assertTrue(service.verify(b), "没被撤的那把必须照旧能用——这正是独立口令相对复用的意义");
    }

    @Test
    @DisplayName("吊销不删行：已撤的记录仍在表里，那是审计事实")
    void revocationKeepsTheRow() {
        String token = service.issue("面板-甲");
        EventStreamToken record = service.list().get(0);
        service.revoke(service.fingerprintOf(record));

        List<EventStreamToken> all = service.list();

        assertEquals(1, all.size(), "行数不能变少——删了之后「这把曾经存在过」就查不到了");
        assertFalse(all.get(0).active());
        assertTrue(all.get(0).revokedAt() > 0);
        assertEquals("面板-甲", all.get(0).label(), "签给谁必须留着，否则事后不知道撤的是谁");
        assertFalse(service.verify(token));
    }

    @Test
    @DisplayName("重复吊销应当幂等，不报失败")
    void revokingTwiceIsIdempotent() {
        service.issue("面板-甲");
        String fingerprint = service.fingerprintOf(service.list().get(0));

        assertTrue(service.revoke(fingerprint));
        assertFalse(service.revoke(fingerprint), "第二次找不到「仍有效的那把」，返回未找到即可，不该抛");
        assertEquals(1, service.list().size());
    }

    @Test
    @DisplayName("尺子先过阳性对照：verify 真的分得出通与不通")
    void verifyIsDiscriminating() {
        // 下面几条用例都靠 assertFalse(verify(...)) 证明「拒住了」。
        // 若 verify 恒返回 false，那些断言会全部假绿——先证明它在该通的时候真的会通
        String token = service.issue("面板-甲");

        assertTrue(service.verify(token), "阳性对照：有效口令必须通，否则上面的「拒」说明不了问题");
        assertFalse(service.verify(token + "x"));
    }
}
