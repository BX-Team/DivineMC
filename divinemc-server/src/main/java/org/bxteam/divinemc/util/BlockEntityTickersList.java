package org.bxteam.divinemc.util;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.Arrays;
import java.util.Collection;

/**
 * A list for ServerLevel's blockEntityTickers
 * <p>
 * This list is behaving identically to ObjectArrayList, but it has an additional method, `removeAllByIndex`, that allows a list of integers to be passed indicating what
 * indexes should be deleted from the list
 * <p>
 * This is faster than using removeAll, since we don't need to compare the identity of each block entity, and faster than looping thru each index manually and deleting with remove,
 * since we don't need to resize the array every single remove.
 */
public final class BlockEntityTickersList extends ObjectArrayList<TickingBlockEntity> {
    private final IntOpenHashSet toRemove = new IntOpenHashSet();
    private int startSearchFromIndex = -1;

    /** Creates a new array list with {@link #DEFAULT_INITIAL_CAPACITY} capacity. */
    public BlockEntityTickersList() {
        super();
    }

    /**
     * Creates a new array list and fills it with a given collection.
     *
     * @param c a collection that will be used to fill the array list.
     */
    public BlockEntityTickersList(final Collection<? extends TickingBlockEntity> c) {
        super(c);
    }

    /**
     * Marks an entry as removed
     *
     * @param index the index of the item on the list to be marked as removed
     */
    public void markAsRemoved(final int index) {
        // The block entities list always loop starting from 0, so we only need to check if the startSearchFromIndex is -1 and that's it
        if (this.startSearchFromIndex == -1)
            this.startSearchFromIndex = index;
        this.toRemove.add(index);
    }

    /**
     * Removes elements that have been marked as removed.
     */
    public void removeMarkedEntries() {
        if (this.startSearchFromIndex == -1) // No entries in the list, skip
            return;

        removeAllByIndex(startSearchFromIndex, toRemove);
        toRemove.clear();
        this.startSearchFromIndex = -1; // Reset the start search index
    }

    /**
     * Removes elements by their index.
     */
    private void removeAllByIndex(final int startSearchFromIndex, final IntOpenHashSet c) { // can't use Set<Integer> because we want to avoid autoboxing when using contains
        final int requiredMatches = c.size();
        if (requiredMatches == 0)
            return; // exit early, we don't need to do anything

        final Object[] a = this.a;
        int destPos = startSearchFromIndex;
        int copyStart = startSearchFromIndex;
        int matches = 0;

        for (int i = startSearchFromIndex; i < size; i++) {
            if (c.contains(i)) {
                int copyLength = i - copyStart;
                if (copyLength > 0) {
                    System.arraycopy(a, copyStart, a, destPos, copyLength);
                    destPos += copyLength;
                }

                copyStart = i + 1;
                matches++;

                if (matches == requiredMatches) {
                    break;
                }
            }
        }

        int remainingLength = size - copyStart;
        if (remainingLength > 0) {
            System.arraycopy(a, copyStart, a, destPos, remainingLength);
            destPos += remainingLength;
        }

        // Clear remaining references and update size
        if (destPos < size) Arrays.fill(a, destPos, size, null);
        size = destPos;
    }
}
