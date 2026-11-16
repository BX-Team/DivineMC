package org.bxteam.divinemc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The navigating mobs of a level for {@code ServerLevel.sendBlockUpdated}, with the sphere each one's
 * {@link PathNavigation#shouldRecomputePath} tests cached in flat arrays.
 * <p>
 * A slot is recomputed when its mob moves or starts riding, or its navigation's path or delayed flag changes. Paths
 * are mutable and can share their node list, so a change to any path recomputes every slot. Riders (they use the
 * vehicle's navigation) and unprocessed async paths are checked live every time. A cached hit is confirmed with
 * {@code shouldRecomputePath}; only a cached miss has to be exact. Level thread only, so not used under RCT.
 */
@NullMarked
public final class NavigatingMobIndex {
    private static final AtomicLong PATH_EPOCH = new AtomicLong();

    private @Nullable Mob[] mobs = new Mob[64];
    private double[] middleX = new double[64];
    private double[] middleY = new double[64];
    private double[] middleZ = new double[64];
    private double[] radiusSqr = new double[64];
    private boolean[] dirty = new boolean[64];
    private int size;
    private long epoch = -1L;

    public static void pathChanged() {
        PATH_EPOCH.incrementAndGet();
    }

    public void add(final Mob mob) {
        if (mob.navigationIndex != null) {
            return;
        }
        if (this.size == this.mobs.length) {
            final int capacity = this.size * 2;
            this.mobs = Arrays.copyOf(this.mobs, capacity);
            this.middleX = Arrays.copyOf(this.middleX, capacity);
            this.middleY = Arrays.copyOf(this.middleY, capacity);
            this.middleZ = Arrays.copyOf(this.middleZ, capacity);
            this.radiusSqr = Arrays.copyOf(this.radiusSqr, capacity);
            this.dirty = Arrays.copyOf(this.dirty, capacity);
        }
        final int slot = this.size++;
        this.mobs[slot] = mob;
        this.dirty[slot] = true;
        mob.navigationIndex = this;
        mob.navigationIndexSlot = slot;
    }

    public void remove(final Mob mob) {
        if (mob.navigationIndex != this) {
            return;
        }
        final int slot = mob.navigationIndexSlot;
        final int last = --this.size;
        if (slot != last) {
            final Mob moved = this.mobs[last];
            this.mobs[slot] = moved;
            this.middleX[slot] = this.middleX[last];
            this.middleY[slot] = this.middleY[last];
            this.middleZ[slot] = this.middleZ[last];
            this.radiusSqr[slot] = this.radiusSqr[last];
            this.dirty[slot] = this.dirty[last];
            moved.navigationIndexSlot = slot;
        }
        this.mobs[last] = null;
        mob.navigationIndex = null;
        mob.navigationIndexSlot = -1;
    }

    public void markDirty(final int slot) {
        // a plugin may move a mob from another level's thread under PWT: never index past the array
        final boolean[] dirty = this.dirty;
        if (slot >= 0 && slot < dirty.length) {
            dirty[slot] = true;
        }
    }

    public void collect(final BlockPos pos, final List<PathNavigation> out) {
        final long currentEpoch = PATH_EPOCH.get();
        if (currentEpoch != this.epoch) {
            this.epoch = currentEpoch;
            Arrays.fill(this.dirty, 0, this.size, true);
        }

        final double x = pos.getX() + 0.5;
        final double y = pos.getY() + 0.5;
        final double z = pos.getZ() + 0.5;
        // fields are re-read: completing an async path runs callbacks that may add or remove mobs
        for (int i = 0; i < this.size; ++i) {
            if (this.dirty[i]) {
                this.refresh(i);
            }
            if (inSphere(x, y, z, this.middleX[i], this.middleY[i], this.middleZ[i], this.radiusSqr[i])) {
                final PathNavigation navigation = this.mobs[i].getNavigation();
                if (navigation.shouldRecomputePath(pos)) {
                    out.add(navigation);
                }
            }
        }
    }

    private void refresh(final int slot) {
        final Mob mob = this.mobs[slot];
        final PathNavigation navigation = mob.getNavigation();
        final Path path = navigation.getPath();
        boolean stable = mob.getVehicle() == null;
        double radiusSqr = -1.0;
        // the same checks in the same order as PathNavigation.shouldRecomputePath: isProcessed completes async paths
        if (!navigation.hasDelayedRecomputation() && path != null) {
            if (!path.isProcessed()) {
                stable = false;
            } else if (!path.isDone() && path.getNodeCount() != 0) {
                final Node target = path.getEndNode();
                final Mob navigating = navigation.getMob();
                this.middleX[slot] = middle(target.x, navigating.getX());
                this.middleY[slot] = middle(target.y, navigating.getY());
                this.middleZ[slot] = middle(target.z, navigating.getZ());
                radiusSqr = radiusSqr(path.getNodeCount() - path.getNextNodeIndex());
            }
        }
        this.radiusSqr[slot] = radiusSqr;
        this.dirty[slot] = !stable;
    }

    // the same operations as shouldRecomputePath, Vec3i.distToCenterSqr and closerToCenterThan, so a cached miss is a
    // real miss; the center is the block's coordinate + 0.5
    public static double middle(final int node, final double mob) {
        return (node + mob) / 2.0;
    }

    public static double radiusSqr(final int remainingNodes) {
        final double radius = remainingNodes;
        return radius * radius;
    }

    public static boolean inSphere(final double centerX, final double centerY, final double centerZ, final double middleX, final double middleY, final double middleZ, final double radiusSqr) {
        final double dx = centerX - middleX;
        final double dy = centerY - middleY;
        final double dz = centerZ - middleZ;
        return dx * dx + dy * dy + dz * dz < radiusSqr;
    }
}
