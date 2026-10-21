package org.frostnova.nova.core.config.ui.auth.oidc;

/** A classified failure. Messages and causes never contain token response bodies or credentials. */
public final class OidcException extends RuntimeException {
    public enum Kind {
        DISABLED, CONFIGURATION, INVALID_LOGIN, INVALID_TOKEN, PROVIDER_ERROR,
        TOKEN_REJECTED, PROVIDER_UNAVAILABLE
    }

    private final Kind kind;
    private final String oauthError;

    OidcException(Kind kind, String message) {
        this(kind, message, null);
    }

    OidcException(Kind kind, String message, String oauthError) {
        super(message);
        this.kind = kind;
        this.oauthError = oauthError;
    }

    public Kind kind() { return kind; }
    public String oauthError() { return oauthError; }
}
