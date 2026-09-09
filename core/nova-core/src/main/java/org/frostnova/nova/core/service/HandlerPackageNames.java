package org.frostnova.nova.core.service;

/**
 * 处理器全类名的包根：迁包之前的根与现行 {@code org.frostnova.nova.}。
 * <p>
 * 旧根保留明文，供读旧配置时对照。全仓扫描旧包前缀时将本类排除。
 */
public final class HandlerPackageNames {
    /**
     * 迁包之前的处理器包根
     */
    public static final String OLD_BOT = "com.starlwr.bot.";

    /**
     * 现行处理器包根
     */
    public static final String NEW_BOT = "org.frostnova.nova.";

    private HandlerPackageNames() {
    }

    /**
     * 旧包根下的全类名：{@code rest} 从第四段起，如 {@code bilibili.handler.Foo}
     * @param rest 旧根之后的段
     * @return 旧全类名
     */
    public static String oldBot(String rest) {
        return OLD_BOT + rest;
    }

    /**
     * 把旧包根换成现行包根；不是旧根则原样返回
     * @param name 全类名
     * @return 现行包根下的名字，或原串
     */
    public static String toNewPackage(String name) {
        if (name != null && name.startsWith(OLD_BOT)) {
            return NEW_BOT + name.substring(OLD_BOT.length());
        }
        return name;
    }

    /**
     * 把现行包根换成旧包根；不是现行根则原样返回
     * @param name 全类名
     * @return 旧包根下的名字，或原串
     */
    public static String toOldPackage(String name) {
        if (name != null && name.startsWith(NEW_BOT)) {
            return OLD_BOT + name.substring(NEW_BOT.length());
        }
        return name;
    }

    /**
     * 两个全类名是不是同一个处理器：字面相同，或只差这一次包根迁移
     * @param left 全类名
     * @param right 全类名
     * @return 是否同一处理器
     */
    public static boolean sameHandler(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        String mappedLeft = toNewPackage(left);
        String mappedRight = toNewPackage(right);
        return mappedLeft != null && mappedLeft.equals(mappedRight);
    }
}
