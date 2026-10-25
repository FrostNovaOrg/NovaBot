package org.frostnova.nova.core.service;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushUser;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** 每次查当前通道，取消本人标记后立即失效；不缓存发件号码与主播的关系。 */
@Service
public class StreamerSelfResolver {
    private final AbstractDataSource source;
    public StreamerSelfResolver(AbstractDataSource source) { this.source = source; }

    public Optional<PushUser> resolve(CommandContext context) {
        if (context.getType() != PushTargetType.FRIEND || context.getSenderUid() == null
                || !context.getSenderUid().equals(context.getNum())) return Optional.empty();
        PushUser found = null;
        for (var user : source.getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled()) || user.getUid() == null || user.getPlatform() == null) continue;
            var self = user.getTargets().stream().filter(t -> Boolean.TRUE.equals(t.getStreamerSelf())).toList();
            if (self.size() != 1) continue;
            var target = self.get(0);
            if (target.getType() != PushTargetType.FRIEND || Boolean.FALSE.equals(target.getEnabled())
                    || !Objects.equals(context.getPlatform(), target.getPlatform())
                    || !context.getSenderUid().equals(target.getNum())) continue;
            if (found != null) return Optional.empty();
            found = user;
        }
        return Optional.ofNullable(found);
    }
}
