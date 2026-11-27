package org.frostnova.nova.core.api;

/**
 * 统一接口面的错误代码
 * <p>
 * 写在一处而不是散在各个调用点：调用方按这些代码分派，散着写迟早会有一处拼错，
 * 而认不出的代码在调用方那边只会落进「未知错误」。代码表同时写在 {@code docs/api.md} 里。
 */
public final class NovaApiErrors {
    /** 400 请求参数、请求体或路径写法不对 */
    public static final String BAD_REQUEST = "bad_request";

    /** 401 没带令牌、令牌不存在、已吊销、已过期，或所属账号已停用／删除 */
    public static final String UNAUTHENTICATED = "unauthenticated";

    /** 403 请求不是从本机直接发来的，请求的这组接口也没有对本机以外的地址开放 */
    public static final String SOURCE_NOT_ALLOWED = "source_not_allowed";

    /** 403 令牌有效，但没勾这个接口要求的范围 */
    public static final String INSUFFICIENT_SCOPE = "insufficient_scope";

    /** 403 其余的权限不足 */
    public static final String FORBIDDEN = "forbidden";

    /** 404 地址不存在，或对象对这个调用者不可见 */
    public static final String NOT_FOUND = "not_found";

    /** 405 地址存在，但不接受这种请求方法 */
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";

    /** 409 对象状态不允许 */
    public static final String CONFLICT = "conflict";

    /** 429 同一来源连续出示无效令牌太多次；或某组接口给调用方定的量用完了（由具体接口说明） */
    public static final String TOO_MANY_REQUESTS = "too_many_requests";

    /** 500 处理时出错 */
    public static final String INTERNAL_ERROR = "internal_error";

    /** 502 交给了发送去向，去向明确说没发出（推送接口用） */
    public static final String DELIVERY_FAILED = "delivery_failed";

    private NovaApiErrors() {
    }
}
