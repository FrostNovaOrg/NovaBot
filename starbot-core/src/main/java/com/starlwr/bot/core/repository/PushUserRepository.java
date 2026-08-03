package com.starlwr.bot.core.repository;

import com.starlwr.bot.core.model.PushUser;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 推送用户 JPA 接口
 */
@Profile("mysql")
@Repository
public interface PushUserRepository extends JpaRepository<PushUser, Long> {
}
