package com.starlwr.bot.adapter.onebot.napcat;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.config.OneBotNapCatPropertiesBinder;
import com.starlwr.bot.core.config.ui.ConfigurationFileService;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NapCat 代登录
 */
@DisplayName("NapCat 代登录")
class NapCatCredentialServiceTest {
    /**
     * 独立算出来的对照值，不是从被测代码里抄的
     * <p>
     * {@code python3 -c "import hashlib; print(hashlib.sha256(b'test.napcat').hexdigest())"}
     * <b>用被测实现自己算一遍再断言，等于什么都没测</b>——那种测试在算法写错时同样是绿的。
     */
    private static final String HASH_OF_TEST = "5a0bb1dce4f71e9780db03560ce68b765bc524ee2ca7653df2633de291e68aca";

    private OneBotAdapterPluginProperties.NapCat props(String token, String hash, String secret) {
        OneBotAdapterPluginProperties.NapCat p = new OneBotAdapterPluginProperties.NapCat();
        p.setToken(token);
        p.setTokenHash(hash);
        p.setTotpSecret(secret);
        p.setAddress("http://127.0.0.1:6099");
        return p;
    }

    /**
     * 记下每次登录请求的请求体，好断言字段名
     */
    private static class Recorder {
        final List<String> bodies = new ArrayList<>();
        final RestTemplate template = mock(RestTemplate.class);

        Recorder(String... responses) {
            List<String> queue = new ArrayList<>(List.of(responses));
            when(template.postForEntity(anyString(), any(), eq(String.class))).thenAnswer(call -> {
                bodies.add(String.valueOf(((HttpEntity<?>) call.getArgument(1)).getBody()));
                // 依次给出，最后一条之后一直重复它
                return ResponseEntity.ok(queue.isEmpty() ? "{}"
                        : queue.size() > 1 ? queue.remove(0) : queue.get(0));
            });
        }
    }

    private static String ok(String credential) {
        return "{\"code\":0,\"data\":{\"Credential\":\"" + credential + "\"}}";
    }

    @Nested
    @DisplayName("登录哈希")
    class Hash {
        @Test
        @DisplayName("与 NapCat 的算法一致：sha256(token + \".napcat\")")
        void matchesNapCatAlgorithm() {
            assertEquals(HASH_OF_TEST, NapCatCredentialService.hash("test"));
        }

        @Test
        @DisplayName("配置里填了明文就换算并写回，明文一起清空")
        void plainTokenIsHashedAndCleared() throws Exception {
            ConfigurationFileService files = mock(ConfigurationFileService.class);
            Map<String, String> written = new HashMap<>();
            doAnswer(call -> {
                written.putAll(call.getArgument(0));
                return 2;
            }).when(files).write(any());

            NapCatCredentialService service = new NapCatCredentialService(
                    props("test", "", ""), files, mock(RestTemplate.class));

            assertTrue(service.isConfigured());
            assertEquals(HASH_OF_TEST, written.get(NapCatCredentialService.TOKEN_HASH_PROPERTY));
            // 只写哈希不清明文的话，配置里会同时躺着两份等价凭据
            assertEquals("", written.get(NapCatCredentialService.TOKEN_PROPERTY));
        }

        @Test
        @DisplayName("只写旧键代登录仍通")
        void legacyKeysStillAllowLogin() {
            List<String> red = new ArrayList<>();
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(OneBotNapCatPropertiesBinder.LEGACY_TOKEN, "test");
            environment.setProperty(OneBotNapCatPropertiesBinder.LEGACY_PREFIX + ".address",
                    "http://127.0.0.1:6099");

            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            OneBotNapCatPropertiesBinder.apply(environment, properties.getNapcat());

            try {
                assertEquals("test", properties.getNapcat().getToken(), "旧 token 应落到现行字段");
            } catch (AssertionError e) {
                red.add("①" + e.getMessage());
            }

            NapCatCredentialService service = new NapCatCredentialService(
                    properties.getNapcat(), mock(ConfigurationFileService.class), mock(RestTemplate.class));
            try {
                assertTrue(service.isConfigured(), "只写旧键时代登录仍应可用");
            } catch (AssertionError e) {
                red.add("②" + e.getMessage());
            }

            if (!red.isEmpty()) {
                fail("只写旧键代登录仍通两问中 " + red.size() + " 问未销: " + String.join("；", red));
            }
        }

        @Test
        @DisplayName("写回落新键且旧位置明文不留")
        void writeBackLandsOnNewKeysAndClearsLegacyPlaintext() throws Exception {
            List<String> red = new ArrayList<>();
            ConfigurationFileService files = mock(ConfigurationFileService.class);
            Map<String, String> written = new HashMap<>();
            doAnswer(call -> {
                written.putAll(call.getArgument(0));
                return 2;
            }).when(files).write(any());

            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(OneBotNapCatPropertiesBinder.LEGACY_TOKEN, "test");
            OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
            OneBotNapCatPropertiesBinder.OneBotNapCatKeyBinding binding =
                    OneBotNapCatPropertiesBinder.apply(environment, properties.getNapcat());

            new NapCatCredentialService(properties.getNapcat(), files, mock(RestTemplate.class),
                    java.time.Instant::now, null,
                    binding.legacyTokenPresent()
                            ? List.of(OneBotNapCatPropertiesBinder.LEGACY_TOKEN)
                            : List.of());

            try {
                assertEquals(HASH_OF_TEST, written.get(NapCatCredentialService.TOKEN_HASH_PROPERTY),
                        "哈希须落新键");
            } catch (AssertionError e) {
                red.add("①" + e.getMessage());
            }
            try {
                assertEquals("", written.get(NapCatCredentialService.TOKEN_PROPERTY),
                        "新位置明文须清空");
            } catch (AssertionError e) {
                red.add("②" + e.getMessage());
            }
            try {
                assertEquals("", written.get(OneBotNapCatPropertiesBinder.LEGACY_TOKEN),
                        "旧位置明文须清空");
            } catch (AssertionError e) {
                red.add("③" + e.getMessage());
            }

            if (!red.isEmpty()) {
                fail("写回落新键且旧位置明文不留三问中 " + red.size() + " 问未销: "
                        + String.join("；", red));
            }
        }

        @Test
        @DisplayName("已经是哈希就不再动配置文件")
        void existingHashIsNotRewritten() throws Exception {
            ConfigurationFileService files = mock(ConfigurationFileService.class);
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), files, mock(RestTemplate.class));

            assertTrue(service.isConfigured());
            org.mockito.Mockito.verify(files, org.mockito.Mockito.never()).write(any());
        }

        @Test
        @DisplayName("什么都没配就是没配，不假装可用")
        void notConfigured() {
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", "", ""), mock(ConfigurationFileService.class), mock(RestTemplate.class));

            assertFalse(service.isConfigured());
            assertTrue(service.credential().isEmpty());
            assertTrue(service.renew().isEmpty());
        }
    }

    @Nested
    @DisplayName("换凭据")
    class Mint {
        @Test
        @DisplayName("请求体的字段是 hash，不是 token")
        void bodyFieldIsHashNotToken() {
            Recorder recorder = new Recorder(ok("credential-1"));
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), recorder.template);

            assertEquals(Optional.of("credential-1"), service.credential());
            assertEquals(1, recorder.bodies.size());
            String body = recorder.bodies.get(0);
            // NapCat 缺这个字段时报的是「token is empty」——报错文本不是接口文档，
            // 按它猜字段名会永远猜错，所以这一条要钉死
            assertTrue(body.contains("\"hash\""), "请求体里应当有 hash 字段: " + body);
            assertFalse(body.contains("\"token\""), "请求体里不该有 token 字段: " + body);
        }

        @Test
        @DisplayName("配了 2FA 密钥就带上 totpCode")
        void sendsTotpCodeWhenSecretConfigured() {
            Recorder recorder = new Recorder(ok("credential-1"));
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, "JBSWY3DPEHPK3PXP"), mock(ConfigurationFileService.class), recorder.template);

            assertTrue(service.credential().isPresent());
            assertTrue(recorder.bodies.get(0).contains("\"totpCode\""), recorder.bodies.get(0));
        }

        @Test
        @DisplayName("没配 2FA 密钥就不带那一格")
        void omitsTotpCodeWhenNoSecret() {
            Recorder recorder = new Recorder(ok("credential-1"));
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), recorder.template);

            assertTrue(service.credential().isPresent());
            assertFalse(recorder.bodies.get(0).contains("totpCode"), recorder.bodies.get(0));
        }

        @Test
        @DisplayName("第二次要凭据时用缓存，不再打 NapCat")
        void secondCallUsesCache() {
            Recorder recorder = new Recorder(ok("credential-1"));
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), recorder.template);

            assertEquals(Optional.of("credential-1"), service.credential());
            assertEquals(Optional.of("credential-1"), service.credential());
            assertEquals(1, recorder.bodies.size(), "第二次不该再发登录请求");
        }

        @Test
        @DisplayName("renew 强制重换：缓存作废，重新登录一次")
        void renewForcesFreshLogin() {
            Recorder recorder = new Recorder(ok("credential-1"));
            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), recorder.template);

            assertTrue(service.credential().isPresent());
            assertTrue(service.renew().isPresent());
            assertEquals(2, recorder.bodies.size(), "renew 必须真的重新登录一次");
        }
    }

    @Nested
    @DisplayName("失败形态")
    class Failures {
        /**
         * 🔴 NapCat 鉴权失败也回 HTTP 200，错误只在响应体里。
         * 只看状态码的实现会把每一次失败都读成成功——这条是这组测试存在的全部理由。
         */
        @Test
        @DisplayName("HTTP 200 但响应体是错误：不当成成功")
        void errorInsideHttp200IsNotSuccess() {
            RestTemplate template = mock(RestTemplate.class);
            when(template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":-1,\"message\":\"token is invalid\"}"));

            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), template);

            assertTrue(service.credential().isEmpty());
        }

        @Test
        @DisplayName("require2FA 是「还差验证码」，不是拿到了凭据")
        void require2FaIsNotACredential() {
            RestTemplate template = mock(RestTemplate.class);
            when(template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":0,\"data\":{\"require2FA\":true,"
                            + "\"message\":\"Please enter your authenticator code\"}}"));

            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), template);

            assertTrue(service.credential().isEmpty());
        }

        @Test
        @DisplayName("失败不写缓存：下一次仍然会重新去换")
        void failureIsNotCached() {
            RestTemplate template = mock(RestTemplate.class);
            when(template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":-1}"))
                    .thenReturn(ResponseEntity.ok(ok("credential-1")));

            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), template);

            assertTrue(service.credential().isEmpty());
            assertEquals(Optional.of("credential-1"), service.credential());
        }

        @Test
        @DisplayName("连不上不抛异常，只是换不到")
        void connectionFailureIsHandled() {
            RestTemplate template = mock(RestTemplate.class);
            when(template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenThrow(new RestClientException("connection refused"));

            NapCatCredentialService service = new NapCatCredentialService(
                    props("", HASH_OF_TEST, ""), mock(ConfigurationFileService.class), template);

            assertNotNull(service.credential());
            assertTrue(service.credential().isEmpty());
        }
    }

    /**
     * 换取速率的上限
     *
     * <h2>🔴 为什么这道闸必须在服务端</h2>
     * 边界⑤ 说的害处是「把 NapCat 的登录限流打满」——那是一个<b>全局</b>资源。
     * 而页面里的计数器<b>一刷新就清零、多开一个标签页就各算各的</b>：
     * 一个刷新就能重置的上限，对它要保护的东西不构成任何上限。
     * 页面里那道管的是交互（一次不成就停手回落），这道管的是流量。
     */
    @Nested
    @DisplayName("换取速率的上限")
    class MintRate {
        private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-08-18T00:00:00Z"));

        private NapCatCredentialService service(Recorder recorder) {
            return new NapCatCredentialService(props(null, "0".repeat(64), null), null,
                    recorder.template, now::get);
        }

        private void advance(java.time.Duration by) {
            now.updateAndGet(at -> at.plus(by));
        }

        /**
         * 阳性对照：不撞闸的时候它是通的。
         * <b>「一直被拦」与「压根换不出来」长得一模一样</b>，所以先证这一条。
         *
         * <h2>🔴 正常上界是三次，不是两次</h2>
         * 续登层上线之后这条路上有了<b>第三个</b>触发源：引导页取一把（{@code issue(false)}）、
         * 那把不管用时重换一把（{@code issue(true)}），再加内层凭据过期时续登层换的那一把。
         * 桶必须容得下正常上界，否则正常用法自己就会撞闸。
         */
        @Test
        @DisplayName("正常用法不碰这道闸")
        void normalUseNeverHitsTheGate() {
            NapCatCredentialService service = service(new Recorder(ok("c")));

            // 引导页最多用掉两次：取一把，那把不管用时重换一把
            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(false).outcome());
            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome());
            // 第三次＝续登层：内层凭据过期时外层替它换一把，这也是正常用法
            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                    "续登层是第三个触发源，它也属于正常用法");

            // 🔴 光证「正常上界用得完」是不够的——桶恰好等于正常上界时这一条<b>照样绿</b>，
            //    而那时余量为零：任何一次多出来的换取都会撞闸，闸就退化成了「正常用法的天花板」。
            //    这一行量的是<b>余量</b>，也就是 MINT_BURST 那句「正常上界 +1」里的那个 +1。
            //    桶等于正常上界时它会红，这正是它存在的理由
            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                    "正常上界用满之后仍须留有余量 —— 没有余量说明这道闸卡在正常用法上");
        }

        @Test
        @DisplayName("短时间内换得太多次会被拦下，且与「换取失败」分开报")
        void refusesWhenHammered() {
            NapCatCredentialService service = service(new Recorder(ok("c")));

            for (int i = 0; i < 4; i++) {
                assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                        "第 " + (i + 1) + " 次应当放行");
            }

            NapCatCredentialService.Issued fifth = service.issue(true);
            assertEquals(NapCatCredentialService.Outcome.THROTTLED, fifth.outcome());
            assertTrue(fifth.asOptional().isEmpty());
        }

        /**
         * 🔴 <b>做的是速率限制，不是锁定。</b>
         * 锁定是状态：触发之后即使循环停了也照样把人关在门外若干分钟，
         * 那等于给了一个「一键把使用者锁出去」的按钮。
         * 速率限制是流量：桶随时间自己回满，循环一停，等一会儿就能正常用，<b>不留惩罚</b>。
         */
        @Test
        @DisplayName("桶会自己回满：循环停了之后立刻能正常用")
        void theBucketRefillsSoThereIsNoLockout() {
            NapCatCredentialService service = service(new Recorder(ok("c")));

            // 🔴 与 MINT_BURST 写死同一个数，不许改成「跑到红为止」：
            //    那样写它永远绿，回满这件事就再也测不出来了
            // 🔴 循环里必须接住结果：吞掉它，桶容量掉到 3 时第 4 次静默被拦，
            //    下面几行照样成立 —— 那样这个循环就只挡得住容量变大，挡不住变小
            for (int i = 0; i < 4; i++) {
                assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                        "把桶排空的第 " + (i + 1) + " 次不该被拦：额度本该有 4 次");
            }
            assertEquals(NapCatCredentialService.Outcome.THROTTLED, service.issue(true).outcome(),
                    "前提：这时确实撞上闸了");

            advance(java.time.Duration.ofMinutes(5));

            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                    "等桶回满之后应当照常放行 —— 不放行说明这是锁定不是限流");
        }

        /**
         * 🔴 <b>回满是按速率线性回的，不是到点把桶重置成满。</b>
         * <p>
         * 上面那条拨的是整整一个窗口，而<b>线性回填与整窗重置在那个读数下一模一样</b>——
         * 拨满一窗，两种实现都放行。所以它证得了「不是锁定」，<b>证不了「按速率回」</b>。
         * 分辨的办法是拨<b>不满一窗</b>，再看回来的是<b>一个额度</b>还是<b>一整桶</b>。
         * <p>
         * 一个额度 = {@code MINT_WINDOW} ÷ {@code MINT_BURST} = 300 秒 ÷ 4 = <b>75 秒</b>，
         * 也就是撞闸文案里写给使用者的那个数。
         */
        @Test
        @DisplayName("桶按速率线性回满：75 秒回一个，不是到点整桶重置")
        void theBucketRefillsLinearlyNotAllAtOnce() {
            NapCatCredentialService service = service(new Recorder(ok("c")));

            for (int i = 0; i < 4; i++) {
                assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                        "把桶排空的第 " + (i + 1) + " 次不该被拦：额度本该有 4 次");
            }
            assertEquals(NapCatCredentialService.Outcome.THROTTLED, service.issue(true).outcome(),
                    "前提：这时确实撞上闸了");

            // 🔴 这里不许加容差。75 × 4 ÷ 300 在双精度下<b>恰好</b>是 1.0，74 秒是 0.98666…，
            //    两边都精确，用不着容差；加了容差，下面这一行就挡不住「差一点也放行」
            advance(java.time.Duration.ofSeconds(74));
            assertEquals(NapCatCredentialService.Outcome.THROTTLED, service.issue(true).outcome(),
                    "还差一秒才够一个额度 —— 这时放行说明回填算多了");

            advance(java.time.Duration.ofSeconds(1));
            assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                    "满 75 秒该回来一个额度");

            // 🔴 这一行才是这条判据的核心。若实现是「到点把桶重置成满」，
            //    上一行放行之后桶里还剩 3 个，这一次照样放行 —— 只有线性回填拦得住它。
            //    去掉这一行，本条判据就退化成上面那条，什么新东西都证不了
            assertEquals(NapCatCredentialService.Outcome.THROTTLED, service.issue(true).outcome(),
                    "回来的应当只有一个额度、不是一整桶 —— 放行说明这是到点重置不是线性回填");
        }

        /**
         * 被拦下时不许对 NapCat 发请求。<b>拦下来却照发，等于没拦。</b>
         */
        @Test
        @DisplayName("被拦下的那一次不会向 NapCat 发请求")
        void aRefusedAttemptSendsNothing() {
            Recorder recorder = new Recorder(ok("c"));
            NapCatCredentialService service = service(recorder);

            // 🔴 与 MINT_BURST 写死同一个数：把桶恰好用完，下一次才是「被拦下的那一次」。
            //    不许改成「跑到红为止」——那样写它永远绿，也就再也证不出「拦下了就不发」
            // 🔴 同上：循环里不接住结果，容量变小时这条判据是瞎的
            for (int i = 0; i < 4; i++) {
                assertEquals(NapCatCredentialService.Outcome.OK, service.issue(true).outcome(),
                        "把桶排空的第 " + (i + 1) + " 次不该被拦：额度本该有 4 次");
            }
            int sentBeforeTheRefusal = recorder.bodies.size();

            service.issue(true);

            assertEquals(sentBeforeTheRefusal, recorder.bodies.size(),
                    "被闸拦下之后仍然发了请求");
        }
    }
}
