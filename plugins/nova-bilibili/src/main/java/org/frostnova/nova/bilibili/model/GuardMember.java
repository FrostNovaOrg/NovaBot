package org.frostnova.nova.bilibili.model;

/**
 * 大航海名单里的一位
 *
 * @param uid 观众 uid
 * @param name 昵称
 * @param level 舰种：1 总督、2 提督、3 舰长
 * @param score 亲密度一类的排序分，接口没给时为 0
 */
public record GuardMember(long uid, String name, int level, long score) {
}
