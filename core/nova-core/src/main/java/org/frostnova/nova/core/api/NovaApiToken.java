package org.frostnova.nova.core.api;

import java.time.Instant;
import java.util.List;

/**
 * 一把访问令牌的记录
 * <p>
 * 只有摘要，没有明文：明文只在签发那一刻交给使用者，此后谁也取不回来。
 * {@code id} 是另起的随机编号，与令牌明文、摘要都无关——控制台列表、吊销、操作记录只用它，
 * 摘要从不离开 {@link NovaApiTokenStore}。
 *
 * @param id         编号，列表与吊销用
 * @param accountId  所属账号
 * @param name       使用者起的名字，可以重名
 * @param scopes     勾选的范围
 * @param digest     令牌明文的 SHA-256 摘要（十六进制）
 * @param issuedAt   签发时刻
 * @param expiresAt  到期时刻，不过期为 null
 * @param lastUsedAt 最后一次通过校验的时刻，没用过为 null；落盘精确到分钟
 * @param revokedAt  吊销时刻，未吊销为 null
 */
public record NovaApiToken(String id, String accountId, String name, List<String> scopes, String digest,
                           Instant issuedAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
    public NovaApiToken {
        scopes = List.copyOf(scopes);
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** 未吊销且未过期 */
    public boolean active(Instant now) {
        return !revoked() && !expired(now);
    }

    NovaApiToken usedAt(Instant when) {
        return new NovaApiToken(id, accountId, name, scopes, digest, issuedAt, expiresAt, when, revokedAt);
    }

    NovaApiToken revokedAt(Instant when) {
        return new NovaApiToken(id, accountId, name, scopes, digest, issuedAt, expiresAt, lastUsedAt, when);
    }
}
