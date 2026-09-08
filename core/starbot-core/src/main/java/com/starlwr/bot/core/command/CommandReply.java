package com.starlwr.bot.core.command;

/**
 * 命令的回复
 * <p>
 * 内容沿用推送消息的占位符语法，因此命令同样可以回复图片
 * （如 {@code {image_base64=...}}）或 @ 某人。
 * @param content 回复内容，为空表示不回复
 */
public record CommandReply(String content) {
    /**
     * 不回复
     */
    private static final CommandReply NONE = new CommandReply("");

    /**
     * 构造文本回复
     * @param content 内容
     * @return 回复
     */
    public static CommandReply of(String content) {
        return content == null || content.isBlank() ? NONE : new CommandReply(content);
    }

    /**
     * 构造图片回复
     * @param base64 图片的 Base64 编码
     * @return 回复
     */
    public static CommandReply image(String base64) {
        return of("{image_base64=" + base64 + "}");
    }

    /**
     * 不回复，用于命令决定保持沉默的情形
     * @return 空回复
     */
    public static CommandReply none() {
        return NONE;
    }

    /**
     * 是否需要回复
     * @return 是否有内容
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
