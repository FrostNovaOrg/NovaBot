package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.secret.SecretLogMask;
import org.frostnova.nova.core.util.UrlMasker;
import org.frostnova.nova.core.util.UrlRedactor;
import org.frostnova.nova.core.web.ReadOnlyTokenController;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Masks credentials before log events reach any appender.
 * <p>
 * Values submitted on the current console request are masked on that request's thread; credentials
 * registered in {@link SecretLogMask} (console submissions, configuration, stored secrets, exchanged
 * tokens) are masked in every log event, on every thread, at every level.
 */
@Component
public class ConsoleCredentialLogFilter extends TurboFilter {
    private static final String REQUEST_CREDENTIALS = ConsoleCredentialLogFilter.class.getName();
    private static final String PARSE_ERROR = "JSON parse error:";
    private static final String WIRE_INPUT = "org.apache.coyote.http11.Http11InputBuffer";
    private static final String FORM_INPUT = "org.apache.tomcat.util.http.Parameters";
    private static final String CONFIG_SLOT = "config:";
    private final ThreadLocal<Boolean> replay = new ThreadLocal<>();
    private final Environment environment;
    private final ObjectProvider<ConfigurationMetadataService> metadata;
    private LoggerContext loggerContext;

    public ConsoleCredentialLogFilter(Environment environment, ObjectProvider<ConfigurationMetadataService> metadata) {
        this.environment = environment;
        this.metadata = metadata;
    }

    @PostConstruct
    void install() {
        rememberConfiguredCredentials();
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            loggerContext = context;
            setContext(context);
            start();
            context.addTurboFilter(this);
        }
    }

    @PreDestroy
    void uninstall() {
        if (loggerContext != null) {
            loggerContext.getTurboFilterList().remove(this);
            stop();
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] arguments, Throwable throwable) {
        if (format == null || Boolean.TRUE.equals(replay.get())) {
            return FilterReply.NEUTRAL;
        }
        String loggerName = logger.getName();
        // These diagnostics can contain partial credentials before a servlet request exists.
        if ((loggerName.equals(WIRE_INPUT) && format.startsWith("Received ["))
                || (loggerName.equals(FORM_INPUT) && level == Level.TRACE
                && !format.startsWith("Set encoding to ") && !format.startsWith("Set query string encoding to "))) {
            return FilterReply.DENY;
        }
        // Disabled events are dropped by the level check after this filter; nothing to mask.
        if (!level.isGreaterOrEqual(logger.getEffectiveLevel())) {
            return FilterReply.NEUTRAL;
        }

        var event = throwable == null ? MessageFormatter.arrayFormat(format, arguments)
                : MessageFormatter.arrayFormat(format, arguments, throwable);
        String message = event.getMessage();
        Throwable error = event.getThrowable();
        String safeMessage = message;
        Throwable safeError = error;
        var scope = currentRequest();
        if (scope != null) {
            if (loggerName.endsWith("DispatcherServlet") && message.contains("parameters={")
                    && !message.contains("parameters={masked}")) {
                scope.captureParameters(ConfigUiAccess.request());
            }
            if (loggerName.startsWith("org.springframework.web.") && message.contains(PARSE_ERROR)
                    && (message.startsWith("Could not resolve parameter")
                    || message.contains(HttpMessageNotReadableException.class.getName()))) {
                int detail = message.indexOf(PARSE_ERROR);
                safeMessage = message.substring(0, detail + PARSE_ERROR.length()) + " " + SensitiveFields.MASK;
            }
            safeMessage = UrlRedactor.maskLiterals(safeMessage, scope.values);
            safeError = scope.maskThrowable(error);
        }
        // Credentials handed to other threads (reconnects, scheduled checks, token refresh) are masked here.
        safeMessage = SecretLogMask.mask(safeMessage);
        safeError = SecretLogMask.mask(safeError);
        if (message.equals(safeMessage) && error == safeError) {
            return FilterReply.NEUTRAL;
        }
        // Emit the protected text at the original category and level, including with custom appenders.
        replay.set(true);
        try {
            logger.log(marker, ConsoleCredentialLogFilter.class.getName(), Level.toLocationAwareLoggerInteger(level),
                    safeMessage, null, safeError);
        } finally {
            replay.remove();
        }
        return FilterReply.DENY;
    }

    /** Protects credentials explicitly read from the servlet body by a controller. */
    public static void protect(HttpServletRequest request, String value) {
        var scope = credentials(request);
        if (scope != null) {
            scope.add(value);
            SecretLogMask.remember(scope.path() + "#protected", value);
        }
    }

    /** Registers configured credentials so that connections opened from configuration are masked too. */
    void rememberConfiguredCredentials() {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        var service = metadata.getIfAvailable();
        Map<String, String> types = service == null ? Map.of() : service.getKnownTypes();
        for (var source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> names)) {
                continue;
            }
            for (String name : names.getPropertyNames()) {
                String type = types.get(name);
                if (!isCredentialName(name, type) || (type != null && !isText(type))) {
                    continue;
                }
                Object value = names.getProperty(name);
                if (value instanceof CharSequence text && isCredentialValue(text.toString())) {
                    SecretLogMask.remember(CONFIG_SLOT + source.getName() + ":" + name, text.toString());
                }
            }
        }
    }

    private static boolean isText(String type) {
        return type.equals("java.lang.String") || type.startsWith("java.util.List<java.lang.String")
                || type.startsWith("java.util.Map<java.lang.String,java.lang.String");
    }

    /** Flags and plain numbers under credential-like names are settings such as limits, not credentials. */
    private static boolean isCredentialValue(String value) {
        String text = value.strip();
        return !text.isEmpty() && !text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")
                && !text.chars().allMatch(Character::isDigit) && !text.startsWith("${");
    }

    /** Name-based rule shared by console fields and configuration keys, independent of the request path. */
    static boolean isCredentialName(String name, String type) {
        if (name == null || ConfigurationMetadataService.ConfigurationField.isBoolean(type)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String leaf = lower.substring(lower.lastIndexOf('.') + 1);
        String canonical = ConfigUiAuthService.canonicalName(lower);
        return SensitiveFields.isSensitive(lower, type) || SensitiveFields.isSensitive(canonical, type)
                || leaf.endsWith("_key") || name.endsWith("Key") || RequestCredentials.CREDENTIAL_NAMES.contains(leaf)
                || canonical.equals("novabot.core.alert.webhookurl")
                || canonical.equals(RequestCredentials.WEBHOOK_HEADERS)
                || canonical.startsWith(RequestCredentials.WEBHOOK_HEADERS + ".");
    }

    static RequestCredentials currentRequest() {
        return credentials(ConfigUiAccess.request());
    }

    private static RequestCredentials credentials(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getAttribute(REQUEST_CREDENTIALS) instanceof RequestCredentials existing) {
            return existing;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.equals(ConfigUiController.BASE_PATH) && !path.startsWith(ConfigUiController.BASE_PATH + "/")
                && !path.equals(ReadOnlyTokenController.PATH)) {
            return null;
        }
        var scope = new RequestCredentials(path);
        request.setAttribute(REQUEST_CREDENTIALS, scope);
        scope.captureQuery(request.getQueryString());
        scope.captureHeaders(request);
        return scope;
    }

    static final class RequestCredentials {
        private static final String AUTH = "/config/api/auth/";
        private static final String WEBHOOK_HEADERS = "novabot.core.alert.webhookheaders";
        private static final Set<String> CREDENTIAL_NAMES = Set.of("apikey", "accesskey", "privatekey",
                "authorization", "proxy-authorization", "cookie", "set-cookie", "openid");
        private final String path;
        private final Set<String> values = new LinkedHashSet<>();

        RequestCredentials(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }

        boolean isSensitive(String name, String type) {
            if (ConfigurationMetadataService.ConfigurationField.isBoolean(type)) {
                return false;
            }
            if (isCredentialName(name, type)) {
                return true;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            String leaf = lower.substring(lower.lastIndexOf('.') + 1);
            return switch (leaf) {
                case "current" -> path.startsWith(AUTH + "password/") || path.startsWith(AUTH + "totp/")
                        || path.equals(AUTH + "passkey/register/options");
                case "next" -> path.startsWith(AUTH + "password/");
                case "code" -> path.equals(AUTH + "login") || path.startsWith(AUTH + "totp/")
                        || path.equals(ReadOnlyTokenController.PATH) || path.equals("/config/api/oidc/callback");
                case "state" -> path.equals("/config/api/oidc/callback");
                case "response" -> path.startsWith(AUTH + "passkey/") && path.endsWith("/verify");
                default -> false;
            };
        }

        private void captureQuery(String query) {
            if (query == null) {
                return;
            }
            for (String field : query.split("&")) {
                int equals = field.indexOf('=');
                if (equals > 0 && isSensitive(decode(field.substring(0, equals)), null)) {
                    String value = field.substring(equals + 1);
                    add(value);
                    add(decode(value));
                }
            }
        }

        private void captureHeaders(HttpServletRequest request) {
            var names = request.getHeaderNames();
            if (names != null) {
                for (String name : Collections.list(names)) {
                    if (isSensitive(name, null)) {
                        Collections.list(request.getHeaders(name)).forEach(this::add);
                    }
                }
            }
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.indexOf(' ') >= 0) {
                add(authorization.substring(authorization.indexOf(' ') + 1));
            }
            var cookies = request.getCookies();
            if (cookies != null) {
                Arrays.stream(cookies).forEach(cookie -> add(cookie.getValue()));
            }
            String referer = request.getHeader("Referer");
            if (referer != null && referer.indexOf('?') >= 0) {
                captureQuery(referer.substring(referer.indexOf('?') + 1));
            }
        }

        private void captureParameters(HttpServletRequest request) {
            request.getParameterMap().forEach((name, entries) -> {
                if (isSensitive(name, null)) {
                    Arrays.stream(entries).forEach(this::add);
                }
            });
        }

        /**
         * Adds a submitted body field. Fields that are credentials by name are kept beyond this request:
         * controllers hand them to connections that log on other threads.
         */
        void addField(String name, String type, Object value) {
            add(value);
            if (isCredentialName(name, type)) {
                List<String> texts = new ArrayList<>();
                collect(value, texts);
                for (int i = 0; i < texts.size(); i++) {
                    SecretLogMask.remember(path + "#" + name + (i == 0 ? "" : "#" + i), texts.get(i));
                }
            }
        }

        private static void collect(Object value, List<String> texts) {
            if (value instanceof Map<?, ?> fields) {
                fields.values().forEach(field -> collect(field, texts));
            } else if (value instanceof Iterable<?> fields) {
                fields.forEach(field -> collect(field, texts));
            } else if (value instanceof String || value instanceof Number) {
                texts.add(value.toString());
            }
        }

        void add(Object value) {
            if (value instanceof Map<?, ?> fields) {
                fields.values().forEach(this::add);
            } else if (value instanceof Iterable<?> fields) {
                fields.forEach(this::add);
            } else if (value instanceof String || value instanceof Number) {
                String text = value.toString();
                if (!text.isBlank()) {
                    values.add(text);
                    values.add(text.strip());
                    String quoted = JSON.toJSONString(text);
                    values.add(quoted.substring(1, quoted.length() - 1));
                }
            }
        }

        private Throwable maskThrowable(Throwable error) {
            if (error == null) {
                return null;
            }
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
                if (cause instanceof HttpMessageNotReadableException && cause.getCause() != null) {
                    var safe = new IllegalArgumentException("请求正文格式不正确");
                    safe.setStackTrace(error.getStackTrace());
                    return safe;
                }
                String original = cause.toString();
                if (!original.equals(UrlRedactor.maskLiterals(original, values))) {
                    return UrlMasker.sanitize(error, values);
                }
            }
            return error;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
