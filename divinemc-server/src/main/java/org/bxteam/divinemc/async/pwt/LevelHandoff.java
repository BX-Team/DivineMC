package org.bxteam.divinemc.async.pwt;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bxteam.divinemc.async.rct.RegionizedChunkTicking;
import org.bxteam.divinemc.concurrent.lock.ServerStateLock;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets a level's tick thread, or a region worker ticking part of a level, work in another level instead of failing.
 * While levels tick in parallel every level is owned by one thread: its own tick thread during its tick, or another
 * thread that took it over. A thread that needs a level it does not own takes it as soon as nobody holds it, waiting
 * for that level's tick to end if necessary, and keeps it until its own tick ends (a region worker: until it leaves
 * its level); those two levels then tick one after the other in this tick.
 * <p>
 * A level thread that is parked here, or on the server state lock in a listener or a command, is inside a plugin or
 * command call, where its levels may be changed anyway. Its levels can then be borrowed: by any thread while it waits
 * for a level (until the borrower's tick ends or it leaves its level), by the holder of the server state lock while it
 * waits for that lock (until the holder's outermost unlock, even if the holder lets the lock go in between to wait for
 * something else; the lender takes the lock only once all its levels are back, and until then any thread may borrow
 * its other levels). A level whose region workers are running is never lent. A wait
 * that no thread can resolve throws {@link IllegalStateException} in the thread that waits for a level, once none of
 * its levels is lent any more; a thread waiting for the server state lock never throws.
 */
@NullMarked
public final class LevelHandoff {
    private static final Object MONITOR = new Object();
    private static final Object STATE_LOCK = new Object();
    private static final Map<Thread, Object> WAITING_REGION_WORKERS = new IdentityHashMap<>();
    private static final Map<Thread, ServerLevel> JOINING = new IdentityHashMap<>();
    private static volatile @Nullable Thread stateLockOwner;

    private LevelHandoff() {
    }

    /**
     * Ownership of one level, guarded by {@link #MONITOR}.
     */
    static final class Slot {
        final Object level;
        final String name;
        @Nullable Thread owner;
        @Nullable Thread lentTo;
        int activeWorkers;

        Slot(final ServerLevel level) {
            this(level, level.getWorld().getName());
        }

        Slot(final Object level, final String name) {
            this.level = level;
            this.name = name;
        }

        @Nullable Thread effectiveOwner() {
            return this.lentTo != null ? this.lentTo : this.owner;
        }
    }

    /**
     * What one level thread or region worker holds, borrows and waits for. {@link #held} and the borrowed lists are changed by their
     * thread only and read without the monitor by it; the other fields are guarded by {@link #MONITOR}.
     */
    public static final class Holder {
        private final List<Slot> held = new ArrayList<>();
        private final List<Slot> borrowedForTick = new ArrayList<>();
        private final List<Slot> borrowedForStateLock = new ArrayList<>();
        private final List<Slot> lent = new ArrayList<>();
        private @Nullable Object waitingFor;
        private boolean inAcquire;
        private boolean lendable;

        boolean holds(final Object level) {
            return contains(this.held, level) || contains(this.borrowedForTick, level) || contains(this.borrowedForStateLock, level);
        }

        private static boolean contains(final List<Slot> slots, final Object level) {
            for (int i = 0, len = slots.size(); i < len; i++) {
                if (slots.get(i).level == level) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean holds(final Thread thread, final Level level) {
        final Holder holder = holder(thread);
        return holder != null && holder.holds(level);
    }

    private static @Nullable Holder holder(final Thread thread) {
        if (thread instanceof LevelTickThread levelThread) {
            return levelThread.handoff;
        }
        return thread instanceof TickThread.RegionTickThread worker ? worker.handoff : null;
    }

    private static @Nullable ServerLevel ownLevel(final Thread thread) {
        if (thread instanceof TickThread.ServerLevelTickThread levelThread) {
            return levelThread.currentlyTickingServerLevel;
        }
        return thread instanceof TickThread.RegionTickThread worker ? worker.currentlyTickingServerLevel : null;
    }

    /**
     * Makes the current level thread or region worker the owner of {@code world}, waiting if another thread holds it.
     *
     * @return false if the current thread cannot take over levels; the caller then fails as before
     */
    public static boolean acquire(final Level world) {
        if (!ParallelWorldTicking.ENABLED || !(world instanceof ServerLevel level)) {
            return false;
        }
        final Thread thread = Thread.currentThread();
        final Holder holder = holder(thread);
        final ServerLevel own = ownLevel(thread);
        if (holder == null || own == null) {
            return false;
        }
        if (own == level || holder.holds(level)) {
            return true;
        }
        final WorldTickScheduler scheduler = MinecraftServer.getServer().worldTickScheduler;
        final Slot slot = scheduler == null ? null : scheduler.slot(level);
        if (slot == null) {
            return false;
        }
        return acquireSlot(thread, holder, own, slot);
    }

    static boolean acquireSlot(final Thread thread, final Holder holder, final Object own, final Slot slot) {
        synchronized (MONITOR) {
            if (holder.lent.isEmpty() && tryTake(thread, holder, slot)) {
                return true;
            }
        }
        // a thread never waits for a level while holding the server state lock, so that lock is never part of a cycle;
        // for the plugin this is like another listener running during an API call, which can happen without threads too
        final int stateLockHolds = ServerStateLock.releaseForWait();
        try {
            return waitAndTake(thread, holder, own, slot);
        } finally {
            ServerStateLock.reacquireAfterWait(stateLockHolds);
        }
    }

    private static boolean tryTake(final Thread thread, final Holder holder, final Slot slot) {
        if (slot.owner == null && slot.lentTo == null) {
            slot.owner = thread;
            holder.held.add(slot);
            return true;
        }
        if (canBorrow(thread, slot)) {
            borrow(thread, holder, slot);
            return true;
        }
        return false;
    }

    private static boolean waitAndTake(final Thread thread, final Holder holder, final Object own, final Slot slot) {
        boolean compensated = false;
        @Nullable InterruptedException interrupted = null;
        synchronized (MONITOR) {
            holder.waitingFor = slot;
            holder.inAcquire = true;
            if (thread instanceof TickThread.RegionTickThread) {
                WAITING_REGION_WORKERS.put(thread, own);
            }
            MONITOR.notifyAll();
            try {
                while (true) {
                    if (holder.lent.isEmpty() && tryTake(thread, holder, slot)) {
                        return true;
                    }
                    // leaving while a level is lent would let this thread and the borrower run in it at once
                    if (holder.lent.isEmpty()) {
                        if (interrupted != null) {
                            throw new IllegalStateException("Interrupted while waiting for level " + slot.name, interrupted);
                        }
                        final String cycle = findCycle(thread);
                        if (cycle != null) {
                            throw new IllegalStateException("Cross-world wait that no level can resolve, cannot take level " + slot.name + ": " + cycle);
                        }
                    }
                    if (!compensated && thread instanceof TickThread.RegionTickThread) {
                        RegionizedChunkTicking.beginBlocking();
                        compensated = true;
                    }
                    try {
                        MONITOR.wait();
                    } catch (final InterruptedException exception) {
                        interrupted = exception;
                    }
                }
            } finally {
                if (interrupted != null) {
                    Thread.currentThread().interrupt();
                }
                holder.waitingFor = null;
                holder.inAcquire = false;
                WAITING_REGION_WORKERS.remove(thread);
                if (compensated) {
                    RegionizedChunkTicking.endBlocking();
                }
            }
        }
    }

    static void beginTick(final LevelTickThread thread, final Slot slot) {
        final Holder holder = thread.handoff;
        boolean interrupted = false;
        synchronized (MONITOR) {
            holder.waitingFor = slot;
            try {
                // an interrupt must not end the wait: taking a held level would give it two owners
                while (slot.owner != null || slot.lentTo != null) {
                    try {
                        MONITOR.wait();
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                holder.waitingFor = null;
            }
            slot.owner = thread;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void endTick(final LevelTickThread thread, final Slot slot) {
        synchronized (MONITOR) {
            if (slot.owner == thread) {
                slot.owner = null;
            }
            releaseAll(thread.handoff);
            MONITOR.notifyAll();
        }
    }

    /**
     * Called when a region worker starts running work of {@code level}; while any does, the level is not lent.
     */
    public static void enterLevel(final ServerLevel level) {
        final WorldTickScheduler scheduler = MinecraftServer.getServer().worldTickScheduler;
        final Slot slot = scheduler == null ? null : scheduler.slot(level);
        if (slot != null) {
            synchronized (MONITOR) {
                slot.activeWorkers++;
            }
        }
    }

    /**
     * Called when a region worker stops running work of {@code level}; releases the levels it took over meanwhile.
     */
    public static void leaveLevel(final ServerLevel level) {
        final WorldTickScheduler scheduler = MinecraftServer.getServer().worldTickScheduler;
        final Slot slot = scheduler == null ? null : scheduler.slot(level);
        final Holder holder = holder(Thread.currentThread());
        synchronized (MONITOR) {
            if (slot != null) {
                slot.activeWorkers--;
            }
            if (holder != null) {
                releaseAll(holder);
            }
            MONITOR.notifyAll();
        }
    }

    private static void releaseAll(final Holder holder) {
        returnAll(holder.borrowedForTick);
        returnAll(holder.borrowedForStateLock);
        for (final Slot held : holder.held) {
            held.owner = null;
        }
        holder.held.clear();
    }

    public static void stateLockAcquired(final Thread thread) {
        stateLockOwner = thread;
    }

    /**
     * Called by the outermost unlock of the server state lock, before it is released.
     */
    public static void stateLockReleasing(final Thread thread) {
        stateLockOwner = null;
        final Holder holder = holder(thread);
        if (holder != null && !holder.borrowedForStateLock.isEmpty()) {
            synchronized (MONITOR) {
                returnAll(holder.borrowedForStateLock);
                MONITOR.notifyAll();
            }
        }
    }

    /**
     * Called when the holder of the server state lock lets it go to wait for something else in the middle of its
     * operation. The levels it borrowed for the lock stay with it until its outermost unlock.
     */
    public static void stateLockSuspending() {
        stateLockOwner = null;
    }

    public static boolean hasLentLevels(final Thread thread) {
        final Holder holder = holder(thread);
        if (holder == null) {
            return false;
        }
        synchronized (MONITOR) {
            return !holder.lent.isEmpty();
        }
    }

    /**
     * Blocks a thread registered by {@link #waitForStateLock} until every level it lent has come back.
     */
    public static void awaitLentReturned() {
        final Holder holder = holder(Thread.currentThread());
        if (holder == null) {
            return;
        }
        boolean interrupted = false;
        synchronized (MONITOR) {
            while (!holder.lent.isEmpty()) {
                try {
                    MONITOR.wait();
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Ends the wait of a thread that has just taken the server state lock, unless one of its levels is still lent.
     *
     * @return false if a level is still lent; the caller then lets the lock go and waits again
     */
    public static boolean finishStateLockWait() {
        final Thread current = Thread.currentThread();
        final Holder holder = holder(current);
        synchronized (MONITOR) {
            if (holder != null) {
                if (!holder.lent.isEmpty()) {
                    return false;
                }
                holder.waitingFor = null;
                holder.lendable = false;
            }
            WAITING_REGION_WORKERS.remove(current);
        }
        return true;
    }

    /**
     * Registers the current thread as waiting for the server state lock, which it is about to block on.
     *
     * @return whether {@link #stopWaitingForStateLock} has to be called once the lock is taken
     */
    public static boolean waitForStateLock(final boolean lendable) {
        final Thread current = Thread.currentThread();
        final Holder holder = holder(current);
        if (holder == null) {
            return false;
        }
        synchronized (MONITOR) {
            holder.waitingFor = STATE_LOCK;
            // a region worker shares its level with the level thread, so it has nothing of its own to lend
            holder.lendable = lendable && current instanceof LevelTickThread;
            final ServerLevel own = ownLevel(current);
            if (current instanceof TickThread.RegionTickThread && own != null) {
                WAITING_REGION_WORKERS.put(current, own);
            }
            MONITOR.notifyAll();
        }
        return true;
    }

    public static void stopWaitingForStateLock() {
        final Thread current = Thread.currentThread();
        final Holder holder = holder(current);
        synchronized (MONITOR) {
            if (holder != null) {
                holder.waitingFor = null;
                holder.lendable = false;
            }
            WAITING_REGION_WORKERS.remove(current);
        }
    }

    /**
     * Marks the current level thread as waiting for the region workers of {@code level}.
     */
    public static void beginJoin(final ServerLevel level) {
        synchronized (MONITOR) {
            JOINING.put(Thread.currentThread(), level);
            MONITOR.notifyAll();
        }
    }

    public static void endJoin() {
        synchronized (MONITOR) {
            JOINING.remove(Thread.currentThread());
        }
    }

    private static boolean canBorrow(final Thread thread, final Slot slot) {
        final Thread owner = slot.owner;
        final Holder holder = owner == null ? null : holder(owner);
        if (slot.lentTo != null || holder == null || owner == thread || slot.activeWorkers != 0) {
            return false;
        }
        if (holder.waitingFor == STATE_LOCK) {
            // an owner with a level still lent does not continue before it is back, so any thread may borrow meanwhile
            return holder.lendable && (stateLockOwner == thread || !holder.lent.isEmpty());
        }
        return holder.inAcquire;
    }

    private static void borrow(final Thread thread, final Holder holder, final Slot slot) {
        final Holder owner = slot.owner == null ? null : holder(slot.owner);
        if (owner == null) {
            throw new IllegalStateException("Borrowing a level nobody owns");
        }
        slot.lentTo = thread;
        owner.lent.add(slot);
        if (owner.waitingFor == STATE_LOCK && stateLockOwner == thread) {
            holder.borrowedForStateLock.add(slot);
        } else {
            holder.borrowedForTick.add(slot);
        }
    }

    private static void returnAll(final List<Slot> borrowed) {
        for (final Slot slot : borrowed) {
            slot.lentTo = null;
            final Holder owner = slot.owner == null ? null : holder(slot.owner);
            if (owner != null) {
                owner.lent.remove(slot);
            }
        }
        borrowed.clear();
    }

    private static @Nullable String findCycle(final Thread start) {
        final ArrayDeque<Thread> pending = new ArrayDeque<>();
        final Map<Thread, Thread> reachedFrom = new IdentityHashMap<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            final Thread thread = pending.poll();
            for (final Thread next : blockedBy(thread)) {
                if (next == start) {
                    return describe(start, thread, reachedFrom);
                }
                if (!reachedFrom.containsKey(next)) {
                    reachedFrom.put(next, thread);
                    pending.add(next);
                }
            }
        }
        return null;
    }

    /**
     * The threads that must move before {@code thread} can; empty when it can move by itself.
     */
    private static List<Thread> blockedBy(final Thread thread) {
        final List<Thread> next = new ArrayList<>(2);
        final Holder holder = holder(thread);
        if (holder != null && holder.waitingFor != null) {
            if (holder.waitingFor instanceof Slot slot) {
                final boolean free = slot.owner == null && slot.lentTo == null;
                final boolean takeable = free || (holder.inAcquire && canBorrow(thread, slot));
                if (holder.lent.isEmpty() && takeable) {
                    return next;
                }
                // a takeable level only waits for this thread's lent levels to come back, not for its owner
                final Thread owner = takeable ? null : slot.effectiveOwner();
                if (owner != null) {
                    next.add(owner);
                }
                for (final Slot lent : holder.lent) {
                    if (lent.lentTo != null) {
                        next.add(lent.lentTo);
                    }
                }
            } else if (holder.waitingFor == STATE_LOCK) {
                final Thread owner = stateLockOwner;
                if (owner != null) {
                    next.add(owner);
                }
                for (final Slot lent : holder.lent) {
                    if (lent.lentTo != null) {
                        next.add(lent.lentTo);
                    }
                }
            }
        }
        final ServerLevel joined = JOINING.get(thread);
        if (joined != null) {
            for (final Map.Entry<Thread, Object> worker : WAITING_REGION_WORKERS.entrySet()) {
                if (worker.getValue() == joined) {
                    next.add(worker.getKey());
                }
            }
        }
        return next;
    }

    private static String describe(final Thread start, final Thread last, final Map<Thread, Thread> reachedFrom) {
        final List<String> chain = new ArrayList<>();
        for (Thread thread = last; thread != null && thread != start; thread = reachedFrom.get(thread)) {
            chain.add(0, thread.getName() + " (" + describeWait(thread) + ")");
        }
        chain.add(0, start.getName() + " (" + describeWait(start) + ")");
        return String.join(" -> ", chain);
    }

    private static String describeWait(final Thread thread) {
        final Holder holder = holder(thread);
        final Object waitingFor = holder == null ? null : holder.waitingFor;
        if (waitingFor instanceof Slot slot) {
            return "waits for level " + slot.name;
        }
        if (waitingFor == STATE_LOCK) {
            return "waits for the server state lock";
        }
        final ServerLevel joined = JOINING.get(thread);
        return joined != null ? "waits for region workers of " + joined.getWorld().getName() : "running";
    }
}
