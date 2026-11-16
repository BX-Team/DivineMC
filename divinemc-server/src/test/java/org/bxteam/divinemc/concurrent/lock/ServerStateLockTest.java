package org.bxteam.divinemc.concurrent.lock;

import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class ServerStateLockTest {
    // a region worker waiting for the level's thread must not keep the listener lock that thread needs,
    // also when only regionized chunk ticking is on
    @Test
    void lockIsFreeWhileItsHolderWaits() throws Exception {
        ServerStateLock.lockAlways();
        ServerStateLock.lockAlways();
        final int holds = ServerStateLock.releaseForWait();
        try {
            assertEquals(2, holds);
            assertFalse(ServerStateLock.isHeldByCurrentThread());
            CompletableFuture.runAsync(() -> {
                ServerStateLock.lockAlways();
                ServerStateLock.unlockAlways();
            }).get(10, TimeUnit.SECONDS);
        } finally {
            ServerStateLock.reacquireAfterWait(holds);
        }
        assertTrue(ServerStateLock.isHeldByCurrentThread());
        ServerStateLock.unlockAlways();
        assertTrue(ServerStateLock.isHeldByCurrentThread());
        ServerStateLock.unlockAlways();
        assertFalse(ServerStateLock.isHeldByCurrentThread());
    }
}
