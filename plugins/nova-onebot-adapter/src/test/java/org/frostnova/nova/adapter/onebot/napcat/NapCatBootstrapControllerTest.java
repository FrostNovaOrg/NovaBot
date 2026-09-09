package org.frostnova.nova.adapter.onebot.napcat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 引导页那几支回复的文案
 *
 * <h2>为什么文案要有判据</h2>
 * 这几支的<b>下一步动作互相相反</b>：撞闸是「等一会儿再来」，换取失败是「去改配置」。
 * 合成一句「换不出来」，等于把使用者支去查一个没毛病的地方。
 * 而文案是最容易在后续改动里被顺手合并掉的东西——没有判据盯着，
 * 它退化成一句含糊话的那天不会有任何人发现。
 */
@DisplayName("NapCat 引导页的回复文案")
class NapCatBootstrapControllerTest {

    private NapCatBootstrapController controllerReturning(NapCatCredentialService.Outcome outcome) {
        NapCatCredentialService credentials = mock(NapCatCredentialService.class);
        when(credentials.issue(anyBoolean()))
                .thenReturn(new NapCatCredentialService.Issued(outcome, null));
        return new NapCatBootstrapController(credentials);
    }

    private String messageFor(NapCatCredentialService.Outcome outcome) {
        JSONObject result = controllerReturning(outcome).credential(true);
        return result.getString("message");
    }

    @Nested
    @DisplayName("撞闸这一支")
    class Throttled {

        @Test
        @DisplayName("说清是「太频繁」，与「配置不对」分开报")
        void throttledIsReportedSeparatelyFromMintFailure() {
            JSONObject result = controllerReturning(
                    NapCatCredentialService.Outcome.THROTTLED).credential(true);

            assertEquals(false, result.getBoolean("success"));
            assertEquals("throttled", result.getString("reason"));

            String throttled = result.getString("message");
            assertTrue(throttled.contains("频繁"), "撞闸这一支要说清是太频繁：" + throttled);

            // 🔴 两支的下一步动作相反，文案一旦相等就等于没分开报
            assertNotEquals(messageFor(NapCatCredentialService.Outcome.FAILED), throttled,
                    "撞闸与换取失败必须是两句不同的话");
        }

        /**
         * 🔴 <b>「一直撞闸」与「配置本来就不对」在使用者眼里长得一模一样。</b>
         * 桶会自己回满，所以「等一会儿再试」对前者管用；
         * 但如果 token 或二次验证密钥配错了，等到天荒地老也换不出来——
         * 那时这句「稍等一会儿再试」就是在把人往错的方向支。
         * 文案必须把第二种可能点破，否则使用者只会一直等。
         */
        @Test
        @DisplayName("点破「一直换不出来多半是配置不对」，并照实写回满速率")
        void throttledCopyPointsAtTheConfigAndStatesTheRefillRate() {
            String message = messageFor(NapCatCredentialService.Outcome.THROTTLED);

            assertTrue(message.contains("token") || message.contains("密钥"),
                    "要点破「多半是 token 或二次验证密钥配得不对」：" + message);

            // 🔴 这里原先只有下面那一行字面量断言，注释却写着「改了闸不改文案就会红」——
            //    那是假的：文案里的 75 也是字面量，改闸时两边都不动，它照样绿。
            //    真要盯住，得盯<b>换算关系</b>，所以有了上面 refillSeconds() 这一行。
            assertTrue(message.contains(String.valueOf(NapCatCredentialService.refillSeconds())),
                    "文案里的回满速率必须等于 MINT_WINDOW ÷ MINT_BURST 算出来的那个数：" + message);

            // 🔴 字面量这一行是<b>故意</b>留的：重新标定这道闸时它会红。
            //    红了不是坏事——那是在要求人去看一眼新数字下这句话还通不通顺
            //    （BURST=3 那会儿这里是 100 秒）。改标定时连同这一行一起改，是应做的动作
            assertTrue(message.contains("75"),
                    "今天的标定下回满速率是 75 秒（5 分钟 ÷ 4）：" + message);
        }
    }

    @Test
    @DisplayName("换取失败这一支只说去查配置，不往浏览器送细节")
    void mintFailureTellsTheUserToCheckTheConfiguration() {
        JSONObject result = controllerReturning(
                NapCatCredentialService.Outcome.FAILED).credential(true);

        assertEquals("mint_failed", result.getString("reason"));
        assertTrue(result.getString("message").contains("配置"));
    }

    @Test
    @DisplayName("引导页改引适配器自己端的续登脚本")
    void pageHtmlPointsAtAdapterResumeScript() {
        List<String> red = new ArrayList<>();
        try {
            ResponseEntity<String> response = controllerReturning(
                    NapCatCredentialService.Outcome.OK).page();
            String html = response.getBody() == null ? "" : response.getBody();
            String src = "src=\"" + NapCatBootstrapController.PAGE_PATH + "/napcat-resume.js\"";
            try {
                assertTrue(html.contains(src), "引导页须含 " + src + "，实为：" + snippet(html, "napcat-resume"));
            } catch (AssertionError e) {
                red.add(e.getMessage());
            }
            try {
                assertFalse(html.contains("/config/assets/napcat-resume.js"),
                        "不得再引核心 /config/assets/napcat-resume.js");
            } catch (AssertionError e) {
                red.add(e.getMessage());
            }
        } catch (Exception e) {
            red.add(e.toString());
        }
        if (!red.isEmpty()) {
            fail("红格 " + red.size() + "：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("适配器端出续登脚本，字节与资源件相同")
    void resumeScriptMatchesTheResourceFile() {
        List<String> red = new ArrayList<>();
        try {
            ResponseEntity<byte[]> response = controllerReturning(
                    NapCatCredentialService.Outcome.OK).resumeScript();
            try {
                assertEquals(200, response.getStatusCode().value(),
                        "新路由须 200，实为 " + response.getStatusCode());
            } catch (AssertionError e) {
                red.add(e.getMessage());
            }
            try {
                String type = String.valueOf(response.getHeaders().getContentType());
                assertTrue(type.toLowerCase().contains("javascript"),
                        "Content-Type 须含 javascript，实为 " + type);
            } catch (AssertionError e) {
                red.add(e.getMessage());
            }
            try {
                Path file = repositoryRoot().resolve(
                        "plugins/nova-onebot-adapter/src/main/resources/config-ui-pages/napcat-resume.js");
                byte[] expected = Files.readAllBytes(file);
                byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
                assertTrue(Arrays.equals(expected, body),
                        "body 须与资源件逐字节同（期望 " + expected.length + " 字节，实为 " + body.length + "）");
            } catch (AssertionError e) {
                red.add(e.getMessage());
            }
        } catch (Exception e) {
            red.add(e.toString());
        }
        if (!red.isEmpty()) {
            fail("红格 " + red.size() + "：" + String.join("；", red));
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private static String snippet(String html, String needle) {
        int at = html.indexOf(needle);
        if (at < 0) {
            return "（正文不含 " + needle + "）";
        }
        int from = Math.max(0, at - 40);
        int to = Math.min(html.length(), at + needle.length() + 40);
        return html.substring(from, to);
    }
}
