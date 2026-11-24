package org.frostnova.nova.core.config.ui;

import java.time.Instant;
import java.util.List;

/** A bot plugin's lists of available push targets. */
public interface PushTargetDirectory {
    /** Unique, stable identifier used as the sender if the whole directory fails. */
    String id();

    /**
     * Returns one snapshot per sender. If a sender cannot be updated, return a stale snapshot
     * for it with the reason and any cached targets.
     *
     * @param force whether to fetch again even when the cached snapshot is still current
     */
    List<Snapshot> snapshots(boolean force);

    /** Member count and administrator status may be null if unknown. */
    record Group(long num, String name, Integer memberCount, Boolean admin) {}

    record Friend(long num, String nickname, String remark) {}

    /**
     * The target lists are non-null. {@code fetchedAt} follows the data, including cached data,
     * and is null until the first successful fetch. {@code attemptedAt} tracks the latest attempt.
     * A stale snapshot can include a message explaining why the latest fetch failed.
     */
    record Snapshot(String sender, List<Group> groups, List<Friend> friends,
                    Instant fetchedAt, Instant attemptedAt, boolean stale, String message) {}
}
