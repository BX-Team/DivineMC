package org.bxteam.divinemc.concurrent;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A view of a random source that several threads draw from, such as a loot random sequence shared by every level.
 * Each draw locks the wrapped source itself, so all views of the same source exclude each other.
 */
@NullMarked
public final class SynchronizedRandomSource implements RandomSource {
    private final RandomSource random;

    public SynchronizedRandomSource(final RandomSource random) {
        this.random = random;
    }

    @Override
    public RandomSource fork() {
        synchronized (this.random) {
            return this.random.fork();
        }
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        synchronized (this.random) {
            return this.random.forkPositional();
        }
    }

    @Override
    public void setSeed(final long seed) {
        synchronized (this.random) {
            this.random.setSeed(seed);
        }
    }

    @Override
    public int nextInt() {
        synchronized (this.random) {
            return this.random.nextInt();
        }
    }

    @Override
    public int nextInt(final int bound) {
        synchronized (this.random) {
            return this.random.nextInt(bound);
        }
    }

    @Override
    public long nextLong() {
        synchronized (this.random) {
            return this.random.nextLong();
        }
    }

    @Override
    public boolean nextBoolean() {
        synchronized (this.random) {
            return this.random.nextBoolean();
        }
    }

    @Override
    public float nextFloat() {
        synchronized (this.random) {
            return this.random.nextFloat();
        }
    }

    @Override
    public double nextDouble() {
        synchronized (this.random) {
            return this.random.nextDouble();
        }
    }

    @Override
    public double nextGaussian() {
        synchronized (this.random) {
            return this.random.nextGaussian();
        }
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        return this == obj || obj instanceof SynchronizedRandomSource other && this.random.equals(other.random);
    }

    @Override
    public int hashCode() {
        return this.random.hashCode();
    }
}
