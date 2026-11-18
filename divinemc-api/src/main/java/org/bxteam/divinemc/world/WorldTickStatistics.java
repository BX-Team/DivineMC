package org.bxteam.divinemc.world;

/**
 * A snapshot of how a world ticked next to other worlds (parallel world ticking) and on region workers (regionized
 * chunk ticking). The time and count values are cumulative since the world was loaded: compare two snapshots to get
 * a rate. Every value is zero while the feature it describes is disabled.
 * <p>
 * A world whose tick keeps waiting for other worlds, or that is often taken over, effectively ticks one after the
 * other with them; that usually comes from a plugin or a command that works in another world during the tick.
 *
 * @param crossWorldWaitNanos      time this world's tick spent waiting to take over another world (a plugin or a
 *                                 command in this world used another world that was ticking)
 * @param tickStartDelayNanos      time the start of this world's tick waited because another world's thread was
 *                                 still working in it
 * @param takeOverCount            how many times a thread of another world took over or borrowed this world
 * @param serverStateLockWaitNanos time this world's threads waited for the lock that serializes server-wide state:
 *                                 commands, scoreboards, and plugin listeners when listener serialization is enabled
 * @param regionizedUnitCount      the number of tiles or player regions ticked in parallel in the last tick, zero
 *                                 when the last tick ran serially
 * @param regionizedTickNanos      the duration of the regionized part (blocks and entities) of the last tick, zero
 *                                 when the last tick ran serially
 */
public record WorldTickStatistics(
    long crossWorldWaitNanos,
    long tickStartDelayNanos,
    long takeOverCount,
    long serverStateLockWaitNanos,
    int regionizedUnitCount,
    long regionizedTickNanos
) {
    /**
     * Statistics of a world that ticks without parallel world ticking or regionized chunk ticking.
     */
    public static final WorldTickStatistics EMPTY = new WorldTickStatistics(0L, 0L, 0L, 0L, 0, 0L);
}
