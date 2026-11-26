package org.frostnova.nova.core.api;

/**
 * 接口处理时要回一个错误
 * <p>
 * 插件的处理器直接抛它，底座把它包成
 * {@code {"ok":false,"error":{"code":"...","message":"..."}}} 并带上对应的 HTTP 状态码。
 * {@code code} 是给程序分辨用的稳定英文代码，{@code message} 是给人看的中文说明，
 * 两者都会原样出现在响应体里——<b>不要把令牌、口令或任何请求里的原文放进去</b>。
 * <p>
 * 处理器抛出别的异常时一律回 500 {@code internal_error}，异常内容不进响应。
 */
public class NovaApiException extends RuntimeException {
    private final int status;

    private final String code;

    /** 回包带的 {@code Retry-After}（秒）；0 表示不带 */
    private final long retryAfterSeconds;

    public NovaApiException(int status, String code, String message) {
        this(status, code, message, 0);
    }

    private NovaApiException(int status, String code, String message, long retryAfterSeconds) {
        super(message, null, false, false);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("错误状态码必须在 400 到 599 之间: " + status);
        }
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("错误代码只用小写英文、数字与下划线: " + code);
        }
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 回包要带的 {@code Retry-After} 秒数；0 表示不带 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 429：这个调用方此刻发得太多，过一会儿再来。回包带 {@code Retry-After}
     * @param message           给人看的说明
     * @param retryAfterSeconds 要等的秒数，不足 1 秒按 1 秒
     */
    public static NovaApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new NovaApiException(429, NovaApiErrors.TOO_MANY_REQUESTS, message, Math.max(1, retryAfterSeconds));
    }

    /** 400：请求参数或请求体不对。 */
    public static NovaApiException badRequest(String message) {
        return new NovaApiException(400, NovaApiErrors.BAD_REQUEST, message);
    }

    /** 403：令牌有效，但这件事不归它管（范围之外的另一类拒绝由底座自己回）。 */
    public static NovaApiException forbidden(String message) {
        return new NovaApiException(403, NovaApiErrors.FORBIDDEN, message);
    }

    /** 404：对象不存在，或对这个调用者不可见。两者回同样的话，不透露对象在不在。 */
    public static NovaApiException notFound(String message) {
        return new NovaApiException(404, NovaApiErrors.NOT_FOUND, message);
    }

    /** 409：对象此刻的状态不允许这样做。 */
    public static NovaApiException conflict(String message) {
        return new NovaApiException(409, NovaApiErrors.CONFLICT, message);
    }
}
