package com.starlwr.bot.core.config.ui.napcat;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NapCat 路由反向见证
 * <p>
 * 这组判据防的东西比它看起来的要窄一点，也要要紧一点：
 * <b>「识别层再也不触发」与「从没断过」长得一模一样</b>。
 * 所以每一条都要问「它认不出的时候会不会吭声」，而不是「它认得出的时候对不对」。
 */
@DisplayName("NapCat 路由反向见证")
class NapCatRouteWitnessTest {
    /**
     * 照生产真外壳抄的形状（2026-08-18 实测，1269 字节的那一份）
     * <p>
     * 三个要点都在里面，都是从真东西上量来的、不是想出来的：
     * <ul>
     *   <li>入口只有一条 {@code <script type="module" crossorigin src=...>}</li>
     *   <li>另有两条 {@code <link rel="modulepreload">} 指向别的分块 —— <b>它们不是入口</b></li>
     *   <li>资产名带内容哈希，NapCat 一重打包就全变</li>
     * </ul>
     */
    private static final String REAL_SHELL = """
            <!doctype html>
            <html lang="zh">
                <head>
                    <meta charset="UTF-8" />
                    <title>NapCat WebUI</title>
                  <script type="module" crossorigin src="/webui/assets/index-BfMm4PRv.js"></script>
                  <link rel="modulepreload" crossorigin href="/webui/assets/codemirror-core-vkcnXJgD.js">
                  <link rel="modulepreload" crossorigin href="/webui/assets/react-dom-CwaHFgt6.js">
                  <link rel="stylesheet" crossorigin href="/webui/assets/index-DJl4-wIN.css">
                </head>
                <body><div id="root"></div></body>
            </html>
            """;

    private static final String BASE = "http://127.0.0.1:6099";

    private static final String MAIN_BUNDLE = BASE + "/webui/assets/index-BfMm4PRv.js";

    /**
     * 主包的形状：压缩过，整包只有两行，而路由字面量在里面出现 4 处。
     * <b>这个 2 与 4 的差是照生产量的</b>（行数口径 2、处数口径 4），
     * 判据要盯的正是这个差 —— 见 {@link Caliber}。
     */
    private static final String REAL_BUNDLE_SHAPE =
            "var r=[{path:\"web_login\",element:x},{path:\"qq_login\",element:y}];function g(){"
                    + "return n.push(\"/web_login\")}\n"
                    + "var q=e(\"web_login\");t(\"/web_login\",q);";

    /**
     * 一个只发已排好的响应的桩。<b>不按真配置写的桩会把判据变成自说自话</b>，
     * 所以路径与真实回环的一模一样。
     */
    private static class Napcat {
        final Map<String, ResponseEntity<String>> answers = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final RestTemplate template = mock(RestTemplate.class);

        Napcat() {
            when(template.getForEntity(anyString(), eq(String.class))).thenAnswer(call -> {
                String url = call.getArgument(0);
                requested.add(url);
                ResponseEntity<String> answer = answers.get(url);
                if (answer == null) {
                    throw new RestClientException("桩里没有排这条: " + url);
                }
                return answer;
            });
        }

        Napcat serve(String url, String body) {
            answers.put(url, ResponseEntity.ok(body));
            return this;
        }

        Napcat status(String url, HttpStatus status) {
            answers.put(url, ResponseEntity.status(status).body(""));
            return this;
        }

        NapCatRouteWitness witness() {
            return new NapCatRouteWitness(BASE, template);
        }
    }

    private static Napcat healthy() {
        return new Napcat()
                .serve(BASE + "/webui/", REAL_SHELL)
                .serve(MAIN_BUNDLE, REAL_BUNDLE_SHAPE);
    }

    @Nested
    @DisplayName("认得出与认不出")
    class Recognition {
        @Test
        @DisplayName("主包里找得到登录路由时，判「认得出」")
        void recognisesTheRouteInTheMainBundle() {
            assertEquals(NapCatRouteWitness.Verdict.RECOGNISED, healthy().witness().witness());
        }

        /**
         * 🔴 这条是先失败一次的那一条：把要找的字面量从主包里拿掉，
         * 见证<b>必须</b>报「路由没了」。它绿着的时候什么都证明不了，
         * 只有它在这里红过，上面那条的绿才有意义。
         */
        @Test
        @DisplayName("主包里找不到时，必须报「路由没了」，不许安静地通过")
        void shoutsWhenTheRouteIsGone() {
            String renamed = REAL_BUNDLE_SHAPE.replace(NapCatRouteWitness.ROUTE_LITERAL, "web_signin");
            Napcat napcat = new Napcat()
                    .serve(BASE + "/webui/", REAL_SHELL)
                    .serve(MAIN_BUNDLE, renamed);

            assertEquals(NapCatRouteWitness.Verdict.ROUTE_GONE, napcat.witness().witness());
        }

        @Test
        @DisplayName("外壳取不到时报警，而不是当作通过")
        void shoutsWhenTheShellIsUnreachable() {
            Napcat napcat = new Napcat().status(BASE + "/webui/", HttpStatus.INTERNAL_SERVER_ERROR);
            assertEquals(NapCatRouteWitness.Verdict.SHELL_UNREACHABLE, napcat.witness().witness());
        }

        @Test
        @DisplayName("外壳里抽不出入口脚本时报警")
        void shoutsWhenTheShellHasNoEntryScript() {
            Napcat napcat = new Napcat()
                    .serve(BASE + "/webui/", "<html><head><title>x</title></head><body></body></html>");
            assertEquals(NapCatRouteWitness.Verdict.NO_ENTRY_SCRIPT, napcat.witness().witness());
        }

        /**
         * 「取不到资产」与「资产里没有那个字面量」必须分开报。
         * 前者该去查网络，后者该去查 NapCat 升级了没有 —— <b>合成一句会把人支去错的地方</b>。
         */
        @Test
        @DisplayName("入口脚本取不到时，报的是「取不到」而不是「路由没了」")
        void distinguishesUnreachableAssetFromMissingRoute() {
            Napcat napcat = new Napcat()
                    .serve(BASE + "/webui/", REAL_SHELL)
                    .status(MAIN_BUNDLE, HttpStatus.NOT_FOUND);
            assertEquals(NapCatRouteWitness.Verdict.ASSET_UNREACHABLE, napcat.witness().witness());
        }
    }

    @Nested
    @DisplayName("口径")
    class Caliber {
        /**
         * 🔴 处数不是行数。真主包压缩成两行、字面量在里面 4 处；
         * 按行数只数得出 2。<b>行数量的是压缩器的换行习惯，不是代码里有几处引用</b>
         * ——压缩器换一版行数就变，而处数不变。
         */
        @Test
        @DisplayName("数的是处数，不是行数")
        void countsOccurrencesNotLines() {
            int lines = (int) REAL_BUNDLE_SHAPE.lines()
                    .filter(line -> line.contains(NapCatRouteWitness.ROUTE_LITERAL)).count();
            int occurrences = NapCatRouteWitness.occurrences(REAL_BUNDLE_SHAPE, NapCatRouteWitness.ROUTE_LITERAL);

            assertEquals(2, lines, "样本的行数口径应当与生产实测一致");
            assertEquals(4, occurrences, "样本的处数口径应当与生产实测一致");
            assertTrue(occurrences > lines, "这个样本必须能把两把口径区分开，否则这条判据是恒真绿");
        }

        /**
         * 只取 {@code <script src>}。外壳里的 modulepreload 指向别的分块，
         * 抓进来只会让见证白下载几百 KB，而且其中一个真的含有那个字面量的旧副本时，
         * <b>会把「主包里已经没有了」盖过去</b>。
         */
        @Test
        @DisplayName("只认入口脚本，不认 modulepreload 与样式表")
        void takesOnlyTheEntryScript() {
            List<String> scripts = NapCatRouteWitness.entryScripts(REAL_SHELL);
            assertEquals(List.of("/webui/assets/index-BfMm4PRv.js"), scripts);
        }

        @Test
        @DisplayName("正常情形下只取外壳与主包两跳，不扫全部资产")
        void fetchesExactlyTwoThings() {
            Napcat napcat = healthy();
            napcat.witness().witness();
            assertEquals(List.of(BASE + "/webui/", MAIN_BUNDLE), napcat.requested);
        }
    }

    @Nested
    @DisplayName("挂在凭据签发上")
    class HookedOnMinting {
        private StarBotCoreProperties.ConfigUi.NapCat props() {
            StarBotCoreProperties.ConfigUi.NapCat p = new StarBotCoreProperties.ConfigUi.NapCat();
            p.setTokenHash("0".repeat(64));
            p.setAddress(BASE);
            return p;
        }

        /**
         * 🔴 <b>阳性对照，这组里最要紧的一条。</b>
         * 少了它，「见证根本没被调用」与「见证通过了」长得一模一样 ——
         * 同一族刚踩过一次（桶实现得很对，但没人调它）。
         * 上面那五条全绿、而这一条红，说明写对了一个没人用的类。
         */
        @Test
        @DisplayName("签发一把凭据时，见证确实被跑过一次")
        void mintingActuallyRunsTheWitness() {
            Napcat napcat = healthy();
            when(napcat.template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":0,\"data\":{\"Credential\":\"c\"}}"));

            NapCatCredentialService service =
                    new NapCatCredentialService(props(), null, napcat.template);

            assertNotNull(service.issue(false).credential(), "前提：这一把应当签发成功");
            assertEquals(NapCatRouteWitness.Verdict.RECOGNISED, service.routeWitness().lastVerdict(),
                    "签发过一把凭据之后，见证应当已经跑过 —— 为 null 说明它根本没被调用");
        }

        /**
         * 🔴 这一条是把守卫改成抛出去之后逼出来的。
         * 原先 {@code witness()} 自己兜了一层 try/catch，看起来很稳妥——
         * 但把那层改成「抛出去」，35 条判据<b>一条都没红</b>：
         * {@code fetch} 早把网络那一族异常全吞了，没有任何路径到得了那层 catch。
         * <b>一段到不了的守卫不是保险，是一句没人验过的承诺。</b>
         * 现在守卫只有一处（签发那一处），而这条判据够得着它。
         */
        @Test
        @DisplayName("见证自己抛异常时，凭据照样签得出来")
        void aThrowingWitnessNeverBlocksMinting() {
            Napcat napcat = healthy();
            when(napcat.template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":0,\"data\":{\"Credential\":\"c\"}}"));

            NapCatRouteWitness exploding = new NapCatRouteWitness(BASE, napcat.template) {
                @Override
                public Verdict witness() {
                    throw new IllegalStateException("见证炸了");
                }
            };

            NapCatCredentialService service = new NapCatCredentialService(
                    props(), null, napcat.template, java.time.Instant::now, exploding);

            assertEquals("c", service.issue(false).credential(),
                    "见证垮了不该把凭据签发一起带下水（边界②：失败回落到现状）");
        }

        @Test
        @DisplayName("见证失败不影响凭据签发")
        void aFailingWitnessNeverBlocksMinting() {
            Napcat napcat = new Napcat().status(BASE + "/webui/", HttpStatus.INTERNAL_SERVER_ERROR);
            when(napcat.template.postForEntity(anyString(), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"code\":0,\"data\":{\"Credential\":\"c\"}}"));

            NapCatCredentialService service =
                    new NapCatCredentialService(props(), null, napcat.template);

            assertEquals("c", service.issue(false).credential(),
                    "见证是来报信的，不是来把门关上的（边界②：失败回落到现状）");
            assertEquals(NapCatRouteWitness.Verdict.SHELL_UNREACHABLE, service.routeWitness().lastVerdict());
        }
    }

    /**
     * 🔴 两边必须是同一个值。
     * 服务端见证盯的字面量若与页面里识别用的那个不一样，
     * <b>见证会永远绿，而识别照样在下一次升级时悄悄失效</b>
     * —— 那时这套东西看起来完好无损，实际已经全废了。
     */
    @Nested
    @DisplayName("与续登层同值")
    class SameLiteralAsTheBrowser {
        private Path repositoryRoot() {
            Path current = Path.of("").toAbsolutePath();
            while (current != null) {
                if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new IllegalStateException("未能定位仓库根目录");
        }

        @Test
        @DisplayName("服务端见证与页面识别用的是同一个路由字面量")
        void theLiteralMatchesTheOneInTheResumeScript() {
            Path script = repositoryRoot()
                    .resolve("starbot-core/src/main/resources/config-ui/napcat-resume.js");
            assertTrue(Files.exists(script), "续登层脚本不在了？");

            String text;
            try {
                text = Files.readString(script, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("var\\s+LOGIN_ROUTE\\s*=\\s*'([^']+)'").matcher(text);
            assertTrue(matcher.find(), "在 napcat-resume.js 里找不到 LOGIN_ROUTE 的定义 —— "
                    + "它改名了的话这条判据也要跟着改，否则它会变成恒真绿");
            assertEquals(NapCatRouteWitness.ROUTE_LITERAL, matcher.group(1),
                    "服务端见证盯的字面量与页面识别用的不一致");
        }
    }
}
