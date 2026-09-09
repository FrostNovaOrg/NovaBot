package org.frostnova.nova.core.alert;

/**
 * 告警通道申报的一栏收件人配置
 * <p>
 * 核心把收件人栏当一张通用表来渲染：每一栏自带键名、标签、类型、占位、校验与来源，
 * 不认识任何一家适配器的具体键。没有申报时这一路在设置页没有收件人栏。
 *
 * @param key 配置键完整路径
 * @param label 人话标签；{@code hidden} 时界面不画，空串即可
 * @param type 控件类型：{@code hidden}／{@code text}／{@code select}
 * @param placeholder 占位提示；没有就空串
 * @param pattern 现值校验；没有就空串。药丸要把 num 那一栏判成「已配置」时会用到
 * @param fill 名单拾取往这一键里写目标行的哪一栏：{@code sender}／{@code kind}／{@code num}；
 *             空串＝手填，不参与名单拆分
 */
public record AlertRecipientField(
        String key,
        String label,
        String type,
        String placeholder,
        String pattern,
        String fill) {
    public AlertRecipientField {
        key = key == null ? "" : key;
        label = label == null ? "" : label;
        type = type == null ? "" : type;
        placeholder = placeholder == null ? "" : placeholder;
        pattern = pattern == null ? "" : pattern;
        fill = fill == null ? "" : fill;
    }
}
