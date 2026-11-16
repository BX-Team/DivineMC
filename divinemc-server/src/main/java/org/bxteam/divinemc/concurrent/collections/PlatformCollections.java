package org.bxteam.divinemc.concurrent.collections;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.bxteam.divinemc.concurrent.LevelThreads;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Factory for collections that only need to be thread-safe while a level can be touched by more than one thread
 * ({@link LevelThreads#SHARED}); otherwise the plain upstream implementations are returned.
 */
public final class PlatformCollections {
    private PlatformCollections() { }

    private static boolean shared() {
        return LevelThreads.SHARED;
    }

    public static <T> Queue<T> priorityQueue(final int initialCapacity, final Comparator<? super T> comparator) {
        return shared() ? new PriorityBlockingQueue<>(initialCapacity, comparator) : new PriorityQueue<>(initialCapacity, comparator);
    }

    public static <T> Queue<T> queue() {
        return shared() ? new ConcurrentLinkedQueue<>() : new ArrayDeque<>();
    }

    public static <T> Set<T> referenceSet() {
        return shared() ? Collections.newSetFromMap(new ConcurrentHashMap<>()) : new ReferenceOpenHashSet<>();
    }

    public static Long2LongMap long2LongMap(final long defaultReturnValue) {
        final Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(defaultReturnValue);
        return shared() ? Long2LongMaps.synchronize(map) : map;
    }

    public static <T> Set<T> customHashSet(final Hash.Strategy<? super T> strategy) {
        final ObjectOpenCustomHashSet<T> set = new ObjectOpenCustomHashSet<>(strategy);
        return shared() ? ObjectSets.synchronize(set) : set;
    }

    public static <V> Long2ObjectMap<V> long2ObjectMap() {
        final Long2ObjectMap<V> map = new Long2ObjectOpenHashMap<>();
        return shared() ? Long2ObjectMaps.synchronize(map) : map;
    }

    public static LongSet longSet() {
        final LongOpenHashSet set = new LongOpenHashSet();
        return shared() ? LongSets.synchronize(set) : set;
    }
}
