package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

/**
 * A level's random under regionized chunk ticking: a region worker draws from the unit it ticks, everything else
 * from this random as upstream does. Every other draw method of {@link ThreadUnsafeRandom} goes through these three.
 */
@NullMarked
public final class RegionLevelRandom extends ThreadUnsafeRandom {
    private final Level level;

    private RegionLevelRandom(final long seed, final Level level) {
        super(seed);
        this.level = level;
    }

    public static RandomSource create(final long seed, final Level level) {
        return DivineConfig.AsyncCategory.enableRegionizedChunkTicking ? new RegionLevelRandom(seed, level) : new ThreadUnsafeRandom(seed);
    }

    @Override
    public int next(final int bits) {
        final UnitRandoms unit = UnitRandoms.current(this.level);
        return unit != null ? unit.levelRandom.next(bits) : super.next(bits);
    }

    @Override
    public int nextInt() {
        final UnitRandoms unit = UnitRandoms.current(this.level);
        return unit != null ? unit.levelRandom.nextInt() : super.nextInt();
    }

    @Override
    public double nextGaussian() {
        final UnitRandoms unit = UnitRandoms.current(this.level);
        return unit != null ? unit.levelRandom.nextGaussian() : super.nextGaussian();
    }
}
