package org.bxteam.divinemc.util;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.PriorityQueue;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.bukkit.support.environment.Normal;
import org.bxteam.divinemc.async.LevelThreads;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

@Normal
@NullMarked
class UnsharedLevelCollectionsTest {
    // with every multithreaded level feature off, per-level structures must use upstream's collections, not the locked ones
    @Test
    void levelStructuresUseUpstreamCollections() throws ReflectiveOperationException {
        assertFalse(LevelThreads.SHARED);

        final LevelTicks<Object> levelTicks = new LevelTicks<>(pos -> true);
        assertSame(Long2ObjectOpenHashMap.class, field(levelTicks, "allContainers").getClass());
        assertSame(Long2LongOpenHashMap.class, field(levelTicks, "nextTickForContainer").getClass());
        assertSame(PriorityQueue.class, field(levelTicks, "containersToTick").getClass());
        assertSame(ArrayDeque.class, field(levelTicks, "toRunThisTick").getClass());

        final LevelChunkTicks<Object> chunkTicks = new LevelChunkTicks<>();
        assertSame(PriorityQueue.class, field(chunkTicks, "tickQueue").getClass());
        assertSame(ObjectOpenCustomHashSet.class, field(chunkTicks, "ticksPerPosition").getClass());
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
