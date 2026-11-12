package org.bxteam.divinemc.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class ThreadLocalFlagTest {
    // the fast path must never answer for another thread: block capture on one region thread must not cancel physics on another
    @Test
    void staysPerThreadWithFastPath() throws Exception {
        final ThreadLocalFlag flag = new ThreadLocalFlag();
        assertFalse(flag.get());

        flag.set(false);
        flag.set(true);
        flag.set(true);
        assertTrue(flag.get());

        final CountDownLatch otherSet = new CountDownLatch(1);
        final CountDownLatch mainChecked = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread other = new Thread(() -> {
            try {
                assertFalse(flag.get());
                flag.set(true);
                otherSet.countDown();
                mainChecked.await();
                flag.set(false);
                assertFalse(flag.get());
            } catch (final Throwable t) {
                failure.set(t);
                otherSet.countDown();
            }
        });

        flag.set(false);
        other.start();
        otherSet.await();
        assertFalse(flag.get());
        mainChecked.countDown();
        other.join();
        assertNull(failure.get());

        flag.set(true);
        assertTrue(flag.get());
        flag.remove();
        assertFalse(flag.get());
        flag.set(true);
        assertTrue(flag.get());
        flag.set(false);
        assertFalse(flag.get());
    }
}
