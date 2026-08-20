package com.starlwr.bot.core.config.ui.napcat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

            // 🔴 回满速率照实写：MINT_WINDOW 5 分钟 ÷ MINT_BURST 4 = 75 秒回一个。
            //    这个数跟着 MINT_BURST 走——改了闸不改文案，这条判据就会红，
            //    那正是它存在的理由（旧文案写的是 BURST=3 时的 100 秒）
            assertTrue(message.contains("75"),
                    "回满速率要照实写成 75 秒回一个（5 分钟 ÷ 4）：" + message);
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
}
