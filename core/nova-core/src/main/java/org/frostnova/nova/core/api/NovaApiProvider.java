package org.frostnova.nova.core.api;

import java.util.List;

/**
 * 插件往统一接口面 {@code /nova/api/v1/} 上挂接口的登记口
 * <p>
 * 实现本接口并注册为 Bean（例如加 {@code @NovaComponent}）。底座在启动时收齐全部登记：
 * <ul>
 *   <li>{@link #scopes()} 里的范围会出现在控制台签发访问令牌的勾选项里；</li>
 *   <li>{@link #routes()} 里的接口挂在 {@code /nova/api/v1/<segment>/} 下。</li>
 * </ul>
 * 鉴权、来源 IP 名单、无效令牌限速、范围核对、统一返回格式与错误包装都由底座做，
 * 处理器拿到的 {@link NovaApiRequest#caller()} 已经是认证好的调用者。
 * <p>
 * 登记不合规（段名不合规或与别家重名、接口要求了没登记的范围、同一接口登记两次）时，
 * 这一家的登记整个不予采用，日志里点名说明；不会让程序起不来。
 */
public interface NovaApiProvider {
    /**
     * 自己那一段，例如 {@code report}，接口就挂在 {@code /nova/api/v1/report/...}
     * <p>
     * 小写字母开头，只含小写字母、数字与连字符。{@code me} 归底座自己用。
     * @return 段名
     */
    String segment();

    /**
     * 自己的范围
     * @return 范围清单，可为空
     */
    default List<NovaApiScope> scopes() {
        return List.of();
    }

    /**
     * 自己的接口
     * @return 接口清单
     */
    List<NovaApiRoute> routes();
}
