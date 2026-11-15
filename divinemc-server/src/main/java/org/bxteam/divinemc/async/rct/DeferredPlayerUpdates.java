package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a level's {@link NearbyPlayers} read-only while its region workers run. Region workers of other players read
 * its lists without locking (spawn range checks, mob backoff), so a change made by a worker that moved, added or
 * removed a player is queued and applied by the level's thread, in order, once the phase has joined.
 */
@NullMarked
public final class DeferredPlayerUpdates {
    public enum Kind {
        ADD,
        REMOVE,
        TICK
    }

    private record Update(Kind kind, ServerPlayer player) {
    }

    private final ServerLevel level;
    private final NearbyPlayers nearbyPlayers;
    private final ConcurrentLinkedQueue<Update> queue = new ConcurrentLinkedQueue<>();

    public DeferredPlayerUpdates(final ServerLevel level, final NearbyPlayers nearbyPlayers) {
        this.level = level;
        this.nearbyPlayers = nearbyPlayers;
    }

    /**
     * @return true if the update was queued; false if the caller may apply it right away
     */
    public boolean defer(final Kind kind, final ServerPlayer player) {
        // a worker that took over another level owns that level alone, so it writes there directly
        if (!(Thread.currentThread() instanceof TickThread.RegionTickThread worker) || worker.currentlyTickingServerLevel != this.level) {
            return false;
        }
        this.queue.add(new Update(kind, player));
        return true;
    }

    public void apply() {
        @Nullable Update update;
        while ((update = this.queue.poll()) != null) {
            switch (update.kind()) {
                case ADD -> this.nearbyPlayers.addPlayer(update.player());
                case REMOVE -> this.nearbyPlayers.removePlayer(update.player());
                case TICK -> this.applyTick(update.player());
            }
        }
    }

    private void applyTick(final ServerPlayer player) {
        // the player may have left the level later in the same phase; its removal is already applied then
        if (!player.isRemoved() && player.level() == this.level) {
            this.nearbyPlayers.tickPlayer(player);
        }
    }
}
