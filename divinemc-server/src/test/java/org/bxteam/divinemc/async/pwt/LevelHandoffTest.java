package org.bxteam.divinemc.async.pwt;

import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Normal
@NullMarked
class LevelHandoffTest {
    private static final long TIMEOUT_SECONDS = 10;

    // two level threads that each want the other's level: one borrows, the other waits for its tick to end
    @Test
    void crossedWaitsResolveWithoutThrowing() throws Throwable {
        final LevelHandoff.Slot a = new LevelHandoff.Slot(new Object(), "a");
        final LevelHandoff.Slot b = new LevelHandoff.Slot(new Object(), "b");
        final CountDownLatch ticking = new CountDownLatch(2);
        final Failure failure = new Failure();
        final LevelTickThread threadA = levelThread("a", failure, self -> crossTick(self, a, b, ticking));
        final LevelTickThread threadB = levelThread("b", failure, self -> crossTick(self, b, a, ticking));
        threadA.start();
        threadB.start();
        join(failure, threadA, threadB);
        assertSame(null, a.owner);
        assertSame(null, b.owner);
    }

    private static void crossTick(final LevelTickThread self, final LevelHandoff.Slot own, final LevelHandoff.Slot other, final CountDownLatch ticking) throws Exception {
        LevelHandoff.beginTick(self, own);
        ticking.countDown();
        assertTrue(ticking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(LevelHandoff.acquireSlot(self, self.handoff, own.level, other));
        assertSame(self, other.effectiveOwner());
        assertTrue(self.handoff.holds(other.level));
        LevelHandoff.endTick(self, own);
    }

    // a real cycle through a non-lendable state lock wait is reported to the thread waiting for a level
    @Test
    void cycleThroughStateLockThrows() throws Throwable {
        final LevelHandoff.Slot a = new LevelHandoff.Slot(new Object(), "a");
        final LevelHandoff.Slot b = new LevelHandoff.Slot(new Object(), "b");
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
        final Failure failure = new Failure();
        final LevelTickThread holder = levelThread("holder", failure, self -> {
            LevelHandoff.beginTick(self, b);
            LevelHandoff.stateLockAcquired(self);
            locked.countDown();
            assertTrue(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            try {
                LevelHandoff.acquireSlot(self, self.handoff, b.level, a);
            } catch (final IllegalStateException exception) {
                thrown.set(exception);
            }
            LevelHandoff.stateLockReleasing(self);
            done.countDown();
            LevelHandoff.endTick(self, b);
        });
        final LevelTickThread waiter = levelThread("waiter", failure, self -> {
            LevelHandoff.beginTick(self, a);
            assertTrue(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(LevelHandoff.waitForStateLock(false));
            waiting.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            LevelHandoff.stopWaitingForStateLock();
            LevelHandoff.endTick(self, a);
        });
        holder.start();
        waiter.start();
        join(failure, holder, waiter);
        final Throwable exception = thrown.get();
        assertTrue(exception != null && exception.getMessage().contains("no level can resolve"), String.valueOf(exception));
    }

    // a level thread interrupted while it waits for its own level, held by another thread, keeps waiting
    @Test
    void interruptDoesNotEndBeginTick() throws Throwable {
        final LevelHandoff.Slot own = new LevelHandoff.Slot(new Object(), "own");
        final LevelHandoff.Slot other = new LevelHandoff.Slot(new Object(), "other");
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Failure failure = new Failure();
        final LevelTickThread holder = levelThread("holder", failure, self -> {
            LevelHandoff.beginTick(self, other);
            assertTrue(LevelHandoff.acquireSlot(self, self.handoff, other.level, own));
            held.countDown();
            assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            LevelHandoff.endTick(self, other);
        });
        final boolean[] interruptedAfter = new boolean[1];
        final LevelTickThread ticker = levelThread("ticker", failure, self -> {
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            LevelHandoff.beginTick(self, own);
            interruptedAfter[0] = Thread.interrupted();
            LevelHandoff.endTick(self, own);
        });
        holder.start();
        assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        ticker.start();
        awaitState(ticker, Thread.State.WAITING);
        ticker.interrupt();
        Thread.sleep(200);
        assertEquals(Thread.State.WAITING, ticker.getState());
        assertSame(holder, own.owner);
        release.countDown();
        join(failure, holder, ticker);
        assertTrue(interruptedAfter[0]);
    }

    // a lock holder that lets the lock go mid-operation keeps the levels it borrowed for it; the lender waits for them
    @Test
    void levelsBorrowedForTheLockStayUntilOutermostUnlock() throws Throwable {
        final ReentrantLock lock = new ReentrantLock();
        final LevelHandoff.Slot lender1 = new LevelHandoff.Slot(new Object(), "lender1");
        final LevelHandoff.Slot lender2 = new LevelHandoff.Slot(new Object(), "lender2");
        final LevelHandoff.Slot holderLevel = new LevelHandoff.Slot(new Object(), "holder");
        final CountDownLatch lenderWaiting = new CountDownLatch(1);
        final CountDownLatch suspended = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch tickEnded = new CountDownLatch(1);
        final CountDownLatch lenderContinued = new CountDownLatch(1);
        final CountDownLatch holderLocked = new CountDownLatch(1);
        final Failure failure = new Failure();
        final LevelTickThread lender = levelThread("lender", failure, self -> {
            LevelHandoff.beginTick(self, lender1);
            assertTrue(LevelHandoff.acquireSlot(self, self.handoff, lender1.level, lender2));
            assertTrue(LevelHandoff.waitForStateLock(true));
            lenderWaiting.countDown();
            while (true) {
                LevelHandoff.awaitLentReturned();
                lock.lock();
                if (LevelHandoff.finishStateLockWait()) {
                    break;
                }
                lock.unlock();
            }
            lenderContinued.countDown();
            assertSame(self, lender1.owner);
            assertTrue(self.handoff.holds(lender2.level));
            assertSame(null, lender1.lentTo);
            assertSame(null, lender2.lentTo);
            lock.unlock();
            LevelHandoff.endTick(self, lender1);
        });
        final LevelTickThread holder = levelThread("holder", failure, self -> {
            LevelHandoff.beginTick(self, holderLevel);
            lock.lock();
            LevelHandoff.stateLockAcquired(self);
            holderLocked.countDown();
            assertTrue(lenderWaiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(LevelHandoff.acquireSlot(self, self.handoff, holderLevel.level, lender1));
            assertSame(self, lender1.lentTo);

            LevelHandoff.stateLockSuspending();
            lock.unlock();
            suspended.countDown();
            // the lender, parked until its levels return, lends the rest to a holder without the lock too
            assertTrue(LevelHandoff.acquireSlot(self, self.handoff, holderLevel.level, lender2));
            assertSame(self, lender1.lentTo);
            Thread.sleep(200);

            lock.lock();
            LevelHandoff.stateLockAcquired(self);
            LevelHandoff.stateLockReleasing(self);
            lock.unlock();
            released.countDown();
            Thread.sleep(200);
            tickEnded.countDown();
            LevelHandoff.endTick(self, holderLevel);
        });
        holder.start();
        assertTrue(holderLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        lender.start();
        assertTrue(suspended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), () -> String.valueOf(failure.first.get()));
        assertFalse(lenderContinued.await(150, TimeUnit.MILLISECONDS), "lender continued while the holder was mid-operation in its level");
        assertTrue(released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(lenderContinued.await(100, TimeUnit.MILLISECONDS), "lender continued while a level was still borrowed for a tick");
        assertTrue(tickEnded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(lenderContinued.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        join(failure, lender, holder);
    }

    @FunctionalInterface
    private interface Body {
        void run(LevelTickThread self) throws Exception;
    }

    private static final class Failure {
        final AtomicReference<@Nullable Throwable> first = new AtomicReference<>();
    }

    private static LevelTickThread levelThread(final String name, final Failure failure, final Body body) {
        final LevelTickThread[] self = new LevelTickThread[1];
        self[0] = new LevelTickThread(() -> {
            try {
                body.run(self[0]);
            } catch (final Throwable throwable) {
                failure.first.compareAndSet(null, throwable);
            }
        }, "handoff-test-" + name);
        self[0].setDaemon(true);
        return self[0];
    }

    private static void join(final Failure failure, final Thread... threads) throws Throwable {
        for (final Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            if (thread.isAlive()) {
                fail(thread.getName() + " did not finish");
            }
        }
        final Throwable throwable = failure.first.get();
        if (throwable != null) {
            throw throwable;
        }
    }

    private static void awaitState(final Thread thread, final Thread.State state) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail(thread.getName() + " never reached " + state);
            }
            Thread.sleep(1);
        }
    }
}
