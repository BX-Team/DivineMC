package org.bxteam.divinemc.util;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * {@code level.getNearestPlayer(x, y, z, -1.0, ignoreCreative)} without visiting every player of the level: natural
 * spawning asks for it at each attempted position.
 */
@NullMarked
public final class SpawnPlayerLookup {
    private static final double SMALL_RANGE_SQR = square(NearbyPlayers.GENERAL_SMALL_AREA_VIEW_DISTANCE_BLOCKS);
    private static final double GENERAL_RANGE_SQR = square(NearbyPlayers.GENERAL_AREA_VIEW_DISTANCE_BLOCKS);

    private SpawnPlayerLookup() {
    }

    public static @Nullable Player nearestPlayer(final ServerLevel level, final double x, final double y, final double z, final boolean ignoreCreative) {
        final Predicate<Entity> predicate = ignoreCreative ? EntitySelector.NO_CREATIVE_OR_SPECTATOR : EntitySelector.NO_SPECTATORS;
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final int blockX = Mth.floor(x);
        final int blockZ = Mth.floor(z);

        // A player missing from a list is more than the list's range away along one axis, so a listed player
        // within that range is nearer than every unlisted one.
        Player nearest = nearestOf(nearbyPlayers.getPlayersByBlock(blockX, blockZ, NearbyPlayers.NearbyMapType.GENERAL_SMALL), predicate, x, y, z);
        if (nearest != null && nearest.distanceToSqr(x, y, z) <= SMALL_RANGE_SQR) {
            return nearest;
        }
        nearest = nearestOf(nearbyPlayers.getPlayersByBlock(blockX, blockZ, NearbyPlayers.NearbyMapType.GENERAL), predicate, x, y, z);
        if (nearest != null && nearest.distanceToSqr(x, y, z) <= GENERAL_RANGE_SQR) {
            return nearest;
        }
        return level.getNearestPlayer(x, y, z, -1.0, ignoreCreative);
    }

    private static @Nullable Player nearestOf(final @Nullable ReferenceList<ServerPlayer> players, final Predicate<Entity> predicate, final double x, final double y, final double z) {
        if (players == null) {
            return null;
        }
        final ServerPlayer[] raw = players.getRawDataUnchecked();
        double best = Double.MAX_VALUE;
        Player result = null;
        for (int i = 0, size = Math.min(players.size(), raw.length); i < size; i++) {
            final ServerPlayer player = raw[i];
            if (predicate.test(player)) {
                final double distance = player.distanceToSqr(x, y, z);
                if (distance < best) {
                    best = distance;
                    result = player;
                }
            }
        }
        return result;
    }

    private static double square(final double value) {
        return value * value;
    }
}
