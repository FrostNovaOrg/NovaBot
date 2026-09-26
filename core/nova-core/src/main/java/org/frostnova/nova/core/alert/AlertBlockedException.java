package org.frostnova.nova.core.alert;

/**
 * 告警被全局推送开关拦下
 * <p>
 * 告警的发送入口在「暂停全部推送」的总开关关着时抛出。做成
 * {@link IllegalStateException} 的子类，是为了让不认识它的调用方照旧把它当
 * 「这一路发不出去」处理；而 {@link AlertService} 认这个具体类型，是要把
 * <b>被开关拦下</b>与<b>通道坏了</b>两种失败分开：
 * <ul>
 *     <li>通道坏了才值得重投——那是在等一个会自己好的故障；</li>
 *     <li>开关是使用者自己关的，那是「先别发」，不是「等会儿再发」。被拦下的告警
 *     算这一次的最终失败：记一条没发出去及原因，不入队、开关打开后也不补发——
 *     攒着，正是闸门注释里不许的「结束瞬间集中轰炸」。</li>
 * </ul>
 */
public class AlertBlockedException extends IllegalStateException {
    public AlertBlockedException(String message) {
        super(message);
    }
}
