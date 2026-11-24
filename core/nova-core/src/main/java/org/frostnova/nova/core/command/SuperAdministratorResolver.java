package org.frostnova.nova.core.command;

/** 由适配器提供群消息发言人的超级管理员身份。 */
public interface SuperAdministratorResolver {
    /** 是否负责此消息平台。 */
    boolean supports(String platform);

    /** 发言人是否在该平台拥有超级管理员权限。 */
    boolean isSuperAdministrator(long senderUid);
}
