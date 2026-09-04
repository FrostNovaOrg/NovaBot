package com.starlwr.bot.core.web;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.service.EventStreamTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * 代签发只读口令（跨项目契约 §五①）
 * <p>
 * 面板要连事件流得有一把只读口令。渠道①是运营者在控制台签好、粘贴给主播；
 * <b>本端点是渠道②</b>：主播在面板里输控制台密码 + 动态验证码，由面板替他换一把回来。
 * 适用对象是<b>自托管单人场景</b>（管理员即主播本人）——运营者不是主播时走渠道①，
 * <b>管理员的凭据不该进别人的面板</b>。
 *
 * <h2>为什么不收「登录 token」</h2>
 * 契约初稿写的是拿登录 token 来换，但这边根本没有那种东西：只有控制台密码与启动令牌，
 * 而启动令牌是<b>正要拆掉的后门</b>。拿它当凭据等于给它续命，所以改收正门的那一套。
 *
 * <h2>路径为什么在 /nova 下而不在 /config 下</h2>
 * {@code /config} 是控制台的命名空间，靠会话 Cookie 把门，而那个 Cookie 的作用域就是
 * {@code /config}；本端点的凭据走请求体、根本不碰 Cookie，两者<b>约束方向相反</b>。
 * 另一半理由在反代上：它最后一条是「其余一律 404」，
 * <b>不在放行清单里的路径根本到不了这里</b>，而 404 与「地址填错」长得一模一样。
 */
@Slf4j
@RestController
public class ReadOnlyTokenController {
    /**
     * 对外路径。反代上要单独放行这一条
     */
    public static final String PATH = "/nova/readonly-token";

    /**
     * 对侧没给标签时的兜底
     * <p>
     * 契约里对侧会给「VRDash 面板」这类中性默认值，正常走不到这里；
     * 留着是<b>对侧改错时的安全网</b>——没有标签就只能一次全撤，等于把所有面板一起踢下线。
     * <p>
     * 🔴 <b>不许从 User-Agent 之类反推设备名来填它。</b>机器名常带真人姓名
     * （「某某的 MacBook」），而标签会原样出现在控制台清单里——
     * 那等于把一个能指认到人的字符串送出了那台机器。判据是<b>会不会建立关联</b>，
     * 不是公不公开可查。
     */
    static final String DEFAULT_LABEL = "面板自助";

    private final ObjectProvider<ConfigUiAuthService> authService;

    private final EventStreamTokenService tokens;

    public ReadOnlyTokenController(ObjectProvider<ConfigUiAuthService> authService, EventStreamTokenService tokens) {
        this.authService = authService;
        this.tokens = tokens;
    }

    /**
     * 用控制台密码 + 动态验证码换一把只读口令
     * <p>
     * 🔴 <b>响应里绝不能有 {@code Set-Cookie}</b>，这是契约的一位而非实现细节：
     * 有它就意味着只读通道被升级成了完整控制台权限，<b>而这种错在功能上完全看不出来</b>
     * （口令照样能用）。对侧收到响应时会主动检查，有即报实现错误。
     * 所以这里走的是「只校验、不签会话」的那条路，不是控制台的 {@code login}。
     * @param body 请求体：password、可选的 code、label
     * @param request 请求，取来源 IP 用
     * @return 成功时 {@code {token, expiresAt}}，失败时 {@code {reason}}
     */
    @PostMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> issue(@RequestBody(required = false) JSONObject body, HttpServletRequest request) {
        ConfigUiAuthService auth = authService.getIfAvailable();
        if (auth == null || !auth.isEnabled()) {
            // 没有校验对象时这条路必须不可用，否则它就是个人人可取的口令水龙头。
            // 注意不能用 404 表达：那一位在契约里已经归给「反代没放行这条路径」了
            return refuse(HttpStatus.BAD_REQUEST, ConfigUiAuthService.Verdict.AUTH_DISABLED, 0);
        }

        JSONObject payload = body == null ? new JSONObject() : body;
        // 反代必须为这条 location 转发真实来源，否则限流会退化成一个全局的桶
        String clientIp = request.getRemoteAddr();
        char[] password = Optional.ofNullable(payload.getString("password")).orElse("").toCharArray();

        ConfigUiAuthService.CredentialCheck check;
        try {
            // code 缺席即为 null：契约定的是「不传」而不是空串——两种写法就是两条路径
            check = auth.checkCredentials(password, payload.getString("code"), clientIp);
        } finally {
            // 明文口令用完即抹，不留在堆里等垃圾回收
            Arrays.fill(password, '\0');
        }

        if (!check.ok()) {
            // 只记判定，不记 password 也不记 code——验证码只有六位，记哈希与记明文没有区别
            log.warn("只读口令代签发被拒, 来源: {}, 原因: {}", clientIp, check.verdict().wire());
            return refuse(statusOf(check.verdict()), check.verdict(),
                    Math.max(1, check.retryAfter().toSeconds()));
        }

        String label = Optional.ofNullable(payload.getString("label"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(DEFAULT_LABEL);

        try {
            String token = tokens.issue(label);
            log.info("已凭控制台密码为「{}」代签发只读口令, 来源: {}", label, clientIp);

            JSONObject result = new JSONObject();
            result.put("token", token);
            // expiresAt 首版恒为 null（靠吊销兜底）。字段仍要发出去：
            // 对侧那条「口令过期了」的分支得留着，等它真发过来时才写，就等于让主播替我们撞第一次
            result.put("expiresAt", null);

            return json(HttpStatus.OK, result);
        } catch (UncheckedIOException e) {
            log.error("只读口令代签发写盘失败, 来源: {}", clientIp, e);
            JSONObject result = new JSONObject();
            result.put("message", "签发没能写进磁盘，请检查数据目录后重试");
            return json(HttpStatus.INTERNAL_SERVER_ERROR, result);
        }
    }

    /**
     * 判定对应的状态码
     * <p>
     * 分四种是为了让对侧分得出四句话。最要紧的是
     * <b>{@code locked_out} 不能与 {@code bad_credentials} 合并</b>：
     * 锁定期内输对的密码也会被拒，此时说「密码不对」会让人去重置一个没问题的密码。
     */
    private static HttpStatus statusOf(ConfigUiAuthService.Verdict verdict) {
        return switch (verdict) {
            case LOCKED_OUT -> HttpStatus.TOO_MANY_REQUESTS;
            case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            case AUTH_DISABLED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNAUTHORIZED;
        };
    }

    private static ResponseEntity<String> refuse(HttpStatus status, ConfigUiAuthService.Verdict verdict, long retryAfterSeconds) {
        JSONObject result = new JSONObject();
        result.put("reason", verdict.wire());
        if (verdict == ConfigUiAuthService.Verdict.LOCKED_OUT) {
            result.put("retryAfterSeconds", retryAfterSeconds);
        }

        return json(status, result);
    }

    /**
     * 自己序列化而不是直接返回对象
     * <p>
     * 因为 {@code expiresAt} 必须<b>作为一个值为 null 的字段出现在响应体里</b>，
     * 而默认的序列化会把 null 字段整个丢掉——那样对侧收到的就是「没有这个字段」，
     * 与契约不符，且这种偏差在联调时极难察觉（口令照样能用）。
     */
    private static ResponseEntity<String> json(HttpStatus status, JSONObject body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString(JSONWriter.Feature.WriteNulls));
    }
}
