package org.bxteam.divinemc.concurrent.state;

import ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.entity.activation.ActivationType;
import io.papermc.paper.redstone.RedstoneWireTurbo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.phys.AABB;
import org.bukkit.TreeType;
import org.bxteam.divinemc.async.rct.NeighborChainLock;
import org.bxteam.divinemc.async.rct.UnitRandoms;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Per-thread copies of the statics that vanilla, CraftBukkit and Paper set around a call and read further down the
 * stack. Used only while {@link LevelThreads#SHARED}; otherwise the upstream statics are used as they are. Threads
 * that are not tick threads share one instance, like they share the statics.
 */
@NullMarked
public final class ThreadState {
    private static final ThreadState OTHER = new ThreadState();

    public boolean ignoreBlockEntityUpdates;
    public boolean redstoneWireSignals = true;
    public @Nullable TreeType treeType;
    public @Nullable BlockPos sourceBlockOverride;
    public final AABB[] activationBoxes = new AABB[ActivationType.values().length];
    private @Nullable RedstoneWireTurbo redstoneWireTurbo;
    public @Nullable UnitRandoms unitRandoms;
    public @Nullable NeighborChainLock heldChainLock;
    public @Nullable NeighborChainLock waitingChainLock;
    public @Nullable CollectingNeighborUpdater neighborChain;
    public @Nullable CollectingNeighborUpdater neighborChainOwner;
    private @Nullable ThreadUnsafeRandom entityRandom;

    public ThreadState() {
        Arrays.fill(this.activationBoxes, new AABB(0, 0, 0, 0, 0, 0));
    }

    /**
     * The Eigencraft wire evaluator keeps its work queues in the instance, which upstream holds on the wire block.
     */
    public RedstoneWireTurbo redstoneWireTurbo(final RedstoneWireBlock wire) {
        RedstoneWireTurbo turbo = this.redstoneWireTurbo;
        if (turbo == null) {
            turbo = new RedstoneWireTurbo(wire);
            this.redstoneWireTurbo = turbo;
        }
        return turbo;
    }

    /**
     * This thread's stand-in for {@code Entity.SHARED_RANDOM}, see {@link UnitRandoms#entityRandom()}.
     */
    public ThreadUnsafeRandom entityRandom() {
        ThreadUnsafeRandom random = this.entityRandom;
        if (random == null) {
            random = new ThreadUnsafeRandom(RandomSupport.generateUniqueSeed());
            this.entityRandom = random;
        }
        return random;
    }

    public static ThreadState current() {
        return Thread.currentThread() instanceof TickThread thread ? thread.threadState : OTHER;
    }
}
