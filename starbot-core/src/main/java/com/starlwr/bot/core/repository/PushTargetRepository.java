package com.starlwr.bot.core.repository;

import com.starlwr.bot.core.model.PushTarget;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 推送目标 JPA 接口
 */
@Profile("mysql")
@Repository
public interface PushTargetRepository extends JpaRepository<PushTarget, Long> {
}
