package net.caffeinemc.mods.lithium.api.inventory;

public interface LithiumCacheableInventory {

    /**
     * Implement this and return false to indicate that lithium's hopper optimizations must not reuse the inventory
     * in the next transfer. Only implement this when mod compatibility issues have been observed.
     */
    default boolean hopperCanReuseInventoryUntilBlockEntityInvalidatedOrBlockStateChangedLithium() {
        return true;
    }
}
