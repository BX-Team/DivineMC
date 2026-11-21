package org.bxteam.divinemc.async.rct;

import org.bxteam.divinemc.concurrent.state.ThreadState;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lets one neighbour-update chain of a level run at a time while its region workers tick: workers chain under this
 * lock, and so does the level's thread when it runs chunk tasks for them during the join. Like a monitor's wait, a
 * thread that blocks in the middle of a chain (for a chunk, for the server state lock) lets the lock go and takes it
 * back afterwards, so no thread ever waits while holding it, and the thread it waits for can chain meanwhile. Each
 * worker chain keeps its own state, so a chain started meanwhile runs to the end instead of joining the waiting one.
 */
@NullMarked
public final class NeighborChainLock {
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean workersRunning;

    void workersRunning(final boolean running) {
        this.workersRunning = running;
    }

    public void lockWorker() {
        this.lock();
    }

    public void unlockWorker() {
        this.unlock();
    }

    /**
     * Called by any other thread before the outermost update of a chain.
     *
     * @return false when no worker of this level runs and nothing has to be taken
     */
    public boolean enterOther() {
        if (!this.workersRunning) {
            return false;
        }
        this.lock();
        return true;
    }

    public void exitOther() {
        this.unlock();
    }

    private void lock() {
        this.lock.lock();
        if (this.lock.getHoldCount() == 1) {
            ThreadState.current().heldChainLock = this;
        }
    }

    private void unlock() {
        if (this.lock.getHoldCount() == 1) {
            ThreadState.current().heldChainLock = null;
        }
        this.lock.unlock();
    }

    /**
     * Before the calling thread blocks: lets go of the chain it is in, if any.
     *
     * @return what {@link #reacquireAfterWait} needs, 0 if the thread is in no chain
     */
    public static int releaseForWait() {
        final ThreadState state = ThreadState.current();
        final NeighborChainLock held = state.heldChainLock;
        if (held == null) {
            return 0;
        }
        final int holds = held.lock.getHoldCount();
        for (int i = 0; i < holds; i++) {
            held.lock.unlock();
        }
        state.waitingChainLock = held;
        state.heldChainLock = null;
        return holds;
    }

    public static void reacquireAfterWait(final int holds) {
        if (holds == 0) {
            return;
        }
        final ThreadState state = ThreadState.current();
        final NeighborChainLock held = Objects.requireNonNull(state.waitingChainLock);
        state.waitingChainLock = null;
        for (int i = 0; i < holds; i++) {
            held.lock.lock();
        }
        state.heldChainLock = held;
    }
}
