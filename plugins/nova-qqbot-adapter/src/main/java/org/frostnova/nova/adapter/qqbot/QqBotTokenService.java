package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@NovaComponent
class QqBotTokenService {
    private static final URI TOKEN_URI = QqBotHttpTransport.ORIGIN.resolve("/app/getAppAccessToken");
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");
    private static final long REFRESH_AHEAD_SECONDS = 55;
    private static final long DEFAULT_EXPIRY_SECONDS = 7200;

    private static final HealthStatus UNCONFIGURED = HealthStatus.down("尚未配置 QQ 官方机器人凭据",
            "请填写 AppID 和 AppSecret", "unconfigured");
    private static final HealthStatus UNREADABLE_SETTINGS = HealthStatus.down("QQ 官方机器人设置文件无法读取",
            "请到连接页重新填写 AppID 和 AppSecret");
    private static final HealthStatus UNCHECKED = HealthStatus.down("QQ 官方机器人凭据尚待验证",
            "请稍后查看凭据验证结果");
    private static final HealthStatus EXPIRED = HealthStatus.down("QQ 官方机器人访问令牌已失效",
            "请在下次调用后查看凭据验证结果");
    private static final HealthStatus UNREACHABLE = HealthStatus.down("连不上 QQ 开放平台，请检查本机网络",
            "检查网络能否访问 QQ 开放平台，恢复后再试", "unreachable");

    private final QqBotSettings settings;
    private final QqBotHttpTransport transport;
    private final Clock clock;

    private volatile Outcome latest = new Outcome(null, null, UNCHECKED);
    private volatile CompletableFuture<Token> attempt;

    @Autowired
    QqBotTokenService(QqBotSettings settings, QqBotHttpTransport transport) {
        this(settings, transport, Clock.systemUTC());
    }

    QqBotTokenService(QqBotSettings settings, QqBotHttpTransport transport, Clock clock) {
        this.settings = settings;
        this.transport = transport;
        this.clock = clock;
    }

    QqBotSettings.Snapshot settings() {
        return settings.snapshot();
    }

    HealthStatus health() {
        var currentSettings = settings();
        if (currentSettings.unreadable()) {
            return UNREADABLE_SETTINGS;
        }
        if (!currentSettings.configured()) {
            return UNCONFIGURED;
        }
        Outcome outcome = latest;
        if (outcome.token() != null && !clock.instant().isBefore(outcome.token().expiresAt())) {
            return EXPIRED;
        }
        return outcome.health();
    }

    synchronized void save(String appId, String secret) {
        settings.save(appId, secret);
        latest = new Outcome(null, null, UNCHECKED);
        attempt = null;
        verifyConfigured();
    }

    void verifyConfigured() {
        if (settings().configured()) {
            try {
                token();
            } catch (QqBotApiException e) {
                // 失败结果已写入探针读取的状态。
            }
        }
    }

    Token token() {
        CompletableFuture<Token> observed = attempt;
        if (observed != null && !observed.isDone()) {
            return await(observed);
        }
        synchronized (this) {
            if (attempt != null && attempt != observed) {
                return await(attempt);
            }
            Token cached = latest.token();
            if (cached != null && !clock.instant().isAfter(cached.expiresAt().minusSeconds(REFRESH_AHEAD_SECONDS))) {
                return cached;
            }
            if (!settings().configured()) {
                throw new QqBotApiException(0, UNCONFIGURED.summary(), "", 0);
            }
            return exchange();
        }
    }

    synchronized void invalidate(Token rejected) {
        // 并发调用可能拿到了同一个旧令牌，只作废产生这个回包的令牌。
        if (latest.token() == rejected) {
            latest = new Outcome(null, null, EXPIRED);
            attempt = null;
        }
    }

    private Token exchange() {
        var current = new CompletableFuture<Token>();
        attempt = current;
        try {
            try {
                latest = settings.useSecret(this::exchangeUsingSecret);
            } catch (RuntimeException e) {
                latest = failure(0, 0, HealthStatus.down("QQ 官方机器人密钥无法读取或解密",
                        "请检查工作目录权限，或重新填写 AppSecret", "account"));
            }
            if (latest.error() != null) {
                current.completeExceptionally(latest.error());
                throw latest.error();
            }
            Token token = latest.token();
            current.complete(token);
            return token;
        } finally {
            if (!current.isDone()) {
                latest = tokenError(0, 0);
                current.completeExceptionally(latest.error());
            }
        }
    }

    private Outcome exchangeUsingSecret(String secret) {
        Instant started = clock.instant();
        String appId = settings().appId();
        try {
            String body = JSONObject.of("appId", appId, "clientSecret", secret).toJSONString();
            QqBotResponse response = QqBotResponse.parse(transport.send("POST", TOKEN_URI, JSON_HEADERS, body));
            if (!response.successful()) {
                return tokenError(response.code(), response.status());
            }
            String value = response.text("access_token");
            Object expiry = response.field("expires_in");
            long seconds = expiry == null ? DEFAULT_EXPIRY_SECONDS : new BigDecimal(expiry.toString()).longValueExact();
            if (seconds <= 0 || value.isEmpty() || value.equals(secret)
                    || value.chars().anyMatch(c -> c < 33 || c > 126)) {
                return tokenError(0, response.status());
            }
            return new Outcome(new Token(value, started.plusSeconds(seconds), new QqBotSecretFingerprint(secret), appId), null,
                    HealthStatus.ok("凭据有效"));
        } catch (IOException e) {
            return failure(0, 0, UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(0, 0, UNREACHABLE);
        } catch (RuntimeException e) {
            return tokenError(0, 0);
        }
    }

    private static Token await(CompletableFuture<Token> pending) {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqBotApiException(0, "等待 QQ 官方机器人访问令牌时被中断", "", 0);
        } catch (ExecutionException e) {
            throw (QqBotApiException) e.getCause();
        }
    }

    private static Outcome tokenError(int code, int httpStatus) {
        int diagnostic = code == 0 && httpStatus >= 300 ? httpStatus : code;
        HealthStatus health = switch (diagnostic) {
            case 100016 -> HealthStatus.down("AppID 或 AppSecret 不对，请到连接页重填",
                    "核对 QQ 开放平台上的 AppID 和 AppSecret 后重新保存", "account");
            case 100007 -> HealthStatus.down("机器人被封禁或已删除",
                    "到 QQ 开放平台核对机器人状态", "account");
            case 10004 -> HealthStatus.down("这个 AppID 下没有机器人，请核对 AppID",
                    "到 QQ 开放平台复制机器人的 AppID 后重新保存", "account");
            case 100001 -> HealthStatus.down("取令牌太频繁，稍后自动再试",
                    "稍后再查看凭据验证结果");
            default -> HealthStatus.down("取令牌失败（错误码 " + diagnostic + "）",
                    "稍后再试，若仍失败请在 QQ 开放平台查询错误码");
        };
        return failure(diagnostic, httpStatus, health);
    }

    private static Outcome failure(int code, int httpStatus, HealthStatus health) {
        return new Outcome(null, new QqBotApiException(code, health.summary(), "", httpStatus), health);
    }

    record Token(String value, Instant expiresAt, QqBotSecretFingerprint secret, String appId) {
        boolean echoedIn(String text) {
            return text.contains(value)
                    || ((value.indexOf('"') >= 0 || value.indexOf('\\') >= 0)
                    && text.contains(QqBotSecretFingerprint.jsonStringContents(value)))
                    || secret.appearsIn(text);
        }

        @Override
        public String toString() {
            return "QQ 官方机器人访问令牌";
        }
    }

    private record Outcome(Token token, QqBotApiException error, HealthStatus health) {
    }
}
