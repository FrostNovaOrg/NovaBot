package org.frostnova.nova.adapter.onebot.napcat;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把使用者送进 NapCat WebUI，路上替他把凭据办了
 *
 * <h2>为什么是一个页面而不是一次跳转</h2>
 * NapCat 的 WebUI 认的是 {@code localStorage["token"]}（存的是它的 Credential），
 * 而 localStorage 只有<b>同源的脚本</b>写得进去。反代之后 WebUI 与本控制台同源，
 * 所以这件事必须由一个跑在这个源上的页面来做——服务端下发再多头也写不进浏览器的 localStorage。
 *
 * <h2>为什么不用 {@code ?webui_token=}</h2>
 * NapCat 的中间件确实也收查询参数，那样一次跳转就完事。
 * 但凭据会因此进反向代理的访问日志，与我们刚关掉「地址栏启动令牌」的理由是同一条。
 * <b>这条是硬性判据，不是风格选择</b>，有测试盯着。
 *
 * <h2>路径为什么不带尾斜杠</h2>
 * 反代上那条 {@code location /config/napcat/} 是带尾斜杠的前缀匹配，
 * 因此 {@code /config/napcat-bootstrap} 落不进它、会回到本进程；
 * 又因为它以 {@code /config} 开头，<b>自动落在控制台安全过滤器的保护范围内</b>——
 * 换句话说，这个会发凭据的页面天生就在那道门后面，不必也不该另建一套鉴权。
 */
@Slf4j
@RestController
@NovaComponent
public class NapCatBootstrapController {
    public static final String PAGE_PATH = ConfigUiController.BASE_PATH + "/napcat-bootstrap";

    public static final String CONSOLE_PATH = ConfigUiController.BASE_PATH + "/api/bot/console";

    public static final String CREDENTIAL_PATH = ConfigUiController.BASE_PATH + "/api/napcat/credential";

    private final NapCatCredentialService credentials;

    public NapCatBootstrapController(NapCatCredentialService credentials) {
        this.credentials = credentials;
    }

    /**
     * 配没配好
     * <p>
     * 界面据此决定要不要显示入口。<b>没配好时不显示，而不是显示了点进去报错</b>——
     * 一个点了才知道不能用的按钮，比没有那个按钮更费解。
     * 回包多给 {@code href}，指向 {@link #PAGE_PATH}。
     */
    @GetMapping(value = CONSOLE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject console() {
        JSONObject result = stateBody();
        result.put("href", PAGE_PATH);
        return result;
    }

    private JSONObject stateBody() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("configured", credentials.isConfigured());
        return result;
    }

    /**
     * 引导页
     */
    @GetMapping(value = PAGE_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() throws IOException {
        try (var stream = new ClassPathResource("config-ui-pages/napcat-bootstrap.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 续登层脚本
     * <p>
     * 这段脚本不是控制台登记页，核心 {@code /config/assets} 的回落只端
     * {@code ConsolePages.byScript} 登记过的文件名，搬走之后再走那条路会 404。
     * 由本控制器自己端，路径挂在引导页下面，与页同属 {@code /config} 那道闸。
     */
    @GetMapping(PAGE_PATH + "/napcat-resume.js")
    public ResponseEntity<byte[]> resumeScript() {
        ClassPathResource resource = new ClassPathResource("config-ui-pages/napcat-resume.js");
        if (!resource.exists()) {
            log.error("NapCat 续登脚本不在适配器资源里");
            return ResponseEntity.notFound().build();
        }
        try (var stream = resource.getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/javascript;charset=UTF-8"))
                    .cacheControl(CacheControl.noCache())
                    .body(stream.readAllBytes());
        } catch (IOException e) {
            log.error("读取 NapCat 续登脚本失败", e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 取一把 NapCat 凭据
     * <p>
     * 用 POST 而不是 GET：这是<b>签发</b>不是查询，而且过滤器只对非安全方法查同源与 CSRF——
     * 一个 GET 就能取到凭据的出口，等于把它降级成「浏览器里有枚 Cookie 就行」。
     * <p>
     * 🔴 {@code renew} 只许由页面在「这把不管用」时带一次。
     * 服务端不替调用方兜底重试：换不出来的原因几乎总是配置不对，
     * 而循环重试换来的不是成功，是把 NapCat 的登录限流打满。
     * @param renew 是否强制换一把新的
     * @return {@code {success, credential}}，未配置或换不到时 {@code success=false} 并带原因
     */
    @PostMapping(value = CREDENTIAL_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject credential(@RequestParam(defaultValue = "false") boolean renew) {
        JSONObject result = new JSONObject();
        NapCatCredentialService.Issued issued = credentials.issue(renew);

        switch (issued.outcome()) {
            case NOT_CONFIGURED -> {
                result.put("success", false);
                result.put("reason", "not_configured");
                result.put("message", "尚未在 novabot.adapter.onebot.napcat 下配置 NapCat 的 token");
            }
            case THROTTLED -> {
                // 🔴 这一支必须与 mint_failed 分开。两者的下一步动作完全相反：
                // 一个是「等一会儿再来」，一个是「去改配置」。
                // 合成一句「换不出来」，等于把使用者支去查一个没毛病的地方
                //
                // 🔴 但只说「稍等再试」也不够：撞闸与「配置本来就不对」在使用者眼里一模一样，
                // 而后者等到天荒地老也换不出来——那时这句「稍等」就是在把人往错的方向支。
                // 所以两层都说：等多久有数，等够了还不行时知道该去查哪儿。
                // 🔴 回满速率<b>算出来，不写字面量</b>：MINT_WINDOW ÷ MINT_BURST。
                // 这里原先写的是字面量 75，注释还写着「有判据盯着」——
                // 而当时盯着它的判据只查 message 里有没有「75」这三个字，
                // 文案与常量各写各的，改了闸两边都不动，那条判据一样绿。
                // 🔴 注释说「有判据盯着」时要指名是哪一条，指不出来就别写这句话：
                // 现在盯着它的是 NapCatBootstrapControllerTest
                // .throttledCopyPointsAtTheConfigAndStatesTheRefillRate
                result.put("success", false);
                result.put("reason", "throttled");
                result.put("message", "换取凭据过于频繁，请稍等一会儿再试（额度约每 "
                        + NapCatCredentialService.refillSeconds() + " 秒回一次）。"
                        + "若一直换不出来，多半是 NapCat 的 token 或二次验证密钥配得不对");
            }
            case FAILED -> {
                result.put("success", false);
                result.put("reason", "mint_failed");
                // 具体原因在服务端日志里（token 不对／2FA 密钥缺失／连不上），
                // 不往浏览器送细节：这个页面的读者不一定是配置它的那个人
                result.put("message", "无法替你登录 NapCat，请检查 NovaBot 侧的 NapCat 凭据配置");
            }
            case OK -> {
                result.put("success", true);
                result.put("credential", issued.credential());
            }
        }
        return result;
    }
}
