package com.starlwr.bot.core.config.ui.napcat;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigurationFileService;
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

    private StarBotCoreProperties.ConfigUi.NapCat props(String token, String hash, String secret) {
        StarBotCoreProperties.ConfigUi.NapCat p = new StarBotCoreProperties.ConfigUi.NapCat();
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
}
