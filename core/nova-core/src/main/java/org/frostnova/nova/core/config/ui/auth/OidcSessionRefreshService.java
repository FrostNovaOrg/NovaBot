package org.frostnova.nova.core.config.ui.auth;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Rechecks server-side login context independently of browser activity. */
@Slf4j
@Service
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public final class OidcSessionRefreshService {
    // Leave room within five minutes for the scheduler and bounded provider I/O.
    static final Duration INTERVAL = Duration.ofMinutes(4);
    static final int MAX_CONCURRENT = 4;
    private final ConfigUiSessionStore sessions;
    private final ConfigUiAuthService auth;
    private final OidcClient client;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "oidc-session-check"));
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_CONCURRENT,
            runnable -> new Thread(runnable, "oidc-session-refresh"));

    @Autowired
    public OidcSessionRefreshService(ConfigUiSessionStore sessions, ConfigUiAuthService auth, OidcClient client) {
        this(sessions, auth, client, Clock.systemUTC());
    }

    OidcSessionRefreshService(ConfigUiSessionStore sessions, ConfigUiAuthService auth, OidcClient client, Clock clock) {
        this.sessions = sessions;
        this.auth = auth;
        this.client = client;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (client.isEnabled()) scheduler.scheduleAtFixedRate(this::checkNow, 1, 1, TimeUnit.SECONDS);
    }

    @EventListener(ContextClosedEvent.class)
    public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    public void checkNow() {
        for (ConfigUiSession session : sessions.oidcSessions(clock.instant())) {
            if (!auth.oidcEnabled()) {
                sessions.revokeOidcSession(session);
                continue;
            }
            if (clock.instant().isBefore(session.oidcState().nextCheck)) continue;
            if (!slots.tryAcquire()) return;
            var state = session.oidcState();
            if (!state.refreshing.compareAndSet(false, true)) {
                slots.release();
                continue;
            }
            try {
                workers.execute(() -> refresh(session));
            } catch (java.util.concurrent.RejectedExecutionException error) {
                state.refreshing.set(false);
                slots.release();
            }
        }
    }

    private void refresh(ConfigUiSession session) {
        var state = session.oidcState();
        try {
            state.login = client.refresh(state.login);
            if (state.failure != null) {
                log.info("统一登录复核恢复: {}, 原因: {}", actor(session), state.failure);
                state.failure = null;
            }
        } catch (OidcException error) {
            failure(session, error.kind());
            if (error.kind() == OidcException.Kind.TOKEN_REJECTED || error.kind() == OidcException.Kind.IDENTITY_CHANGED) {
                sessions.revokeOidcSession(session);
            }
        } catch (RuntimeException error) {
            // Never log exception text or causes: provider data may contain credentials.
            failure(session, OidcException.Kind.PROVIDER_ERROR);
        } finally {
            state.nextCheck = clock.instant().plus(INTERVAL);
            state.refreshing.set(false);
            slots.release();
        }
    }

    private void failure(ConfigUiSession session, OidcException.Kind kind) {
        var state = session.oidcState();
        if (state.failure != kind) {
            log.warn("统一登录复核失败: {}, 原因: {}", actor(session), kind);
            state.failure = kind;
        }
    }

    private String actor(ConfigUiSession session) {
        ConfigUiAccount account = auth.account(session);
        String name = account == null ? "未知账号" : account.actor();
        return name.contains(session.getAccountId()) ? name : name + " (" + session.getAccountId() + ")";
    }
}
