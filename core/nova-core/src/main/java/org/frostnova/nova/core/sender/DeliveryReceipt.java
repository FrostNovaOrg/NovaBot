package org.frostnova.nova.core.sender;

/**
 * 一次当场投递的结果，见 {@link NovaMessageSender#deliverForCaller}
 *
 * @param outcome 结果
 * @param id      去向给的消息编号；只在 {@link Outcome#SENT} 时可能有，去向没给时为 null
 * @param reason  去向或发送器给的原因；给人排障看，<b>不要原样交给外部调用方</b>
 *                （里面可能有去向回包里的原话、连接地址之类）
 */
public record DeliveryReceipt(Outcome outcome, String id, String reason) {
    /** 结果 */
    public enum Outcome {
        /** 去向明确说发出了 */
        SENT,

        /** 交出去了，去向没给明确结果：可能已经发出，不要重发 */
        UNKNOWN,

        /** 去向明确说没发出（号码不对、机器人不在群里、内容不被接受之类） */
        FAILED,

        /** 去向此刻被停用，没有交出去 */
        BLOCKED,

        /** 每一次都没交到去向手里（去向没连上），没有发出 */
        UNREACHABLE,

        /** 没有这个去向 */
        MISSING,

        /** 摘掉发不出的 @全体成员 之后没有内容，没有交出去 */
        EMPTY
    }

    static DeliveryReceipt of(Outcome outcome, String reason) {
        return new DeliveryReceipt(outcome, null, reason);
    }
}
