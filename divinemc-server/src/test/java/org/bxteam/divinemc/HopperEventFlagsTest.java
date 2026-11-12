package org.bxteam.divinemc;

import org.bukkit.support.environment.VanillaFeature;
import org.bxteam.divinemc.async.HopperEventFlags;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@VanillaFeature
class HopperEventFlagsTest {
    @Test
    void flagsSetOnOneThreadAreNotSeenByAnother() throws InterruptedException {
        HopperEventFlags.get().skipPullModeEventFire = true;
        HopperEventFlags.get().skipPushModeEventFire = true;
        HopperEventFlags.ignoreBlockEntityUpdates(true);
        try {
            AtomicBoolean seen = new AtomicBoolean(true);
            Thread other = new Thread(() -> seen.set(HopperEventFlags.get().skipPullModeEventFire
                || HopperEventFlags.get().skipPushModeEventFire
                || HopperEventFlags.ignoreBlockEntityUpdates()));
            other.start();
            other.join();
            assertFalse(seen.get());
            assertTrue(HopperEventFlags.ignoreBlockEntityUpdates());
        } finally {
            HopperEventFlags.get().skipPullModeEventFire = false;
            HopperEventFlags.get().skipPushModeEventFire = false;
            HopperEventFlags.ignoreBlockEntityUpdates(false);
        }
        assertFalse(HopperEventFlags.ignoreBlockEntityUpdates());
    }
}
