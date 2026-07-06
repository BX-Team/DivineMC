package org.bxteam.divinemc.async.rct;

import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.stream.LongStream;

public final class RollingLongBuffer {
    private final long[] buffer;
    private final int capacity;
    private int head = 0;    // index of oldest element
    private int count = 0;   // number of elements in buffer (<= capacity)
    private long sum = 0L;

    public RollingLongBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new long[capacity];
    }

    /**
     * Adds a value to the buffer.
     * If the buffer is full, overwrites the oldest element.
     * Returns true (keeps same signature semantics as List.add).
     */
    public synchronized boolean add(long value) {
        if (count < capacity) {
            int idx = (head + count) % capacity;
            buffer[idx] = value;
            sum += value;
            count++;
        } else {
            // overwrite oldest at head
            long old = buffer[head];
            sum -= old;
            buffer[head] = value;
            sum += value;
            head = (head + 1) % capacity;
        }
        return true;
    }

    /**
     * Current number of elements stored.
     */
    public synchronized int size() {
        return count;
    }

    /**
     * Returns a LongStream of elements in order from oldest to newest.
     * Note: this creates an IntStream-range and maps via index; it's lightweight.
     */
    public LongStream longStream() {
        // We avoid synchronizing the entire stream creation by copying the
        // head/count/capacity locally under sync so the stream is stable.
        final long[] snapshot;
        final int snapCount;
        final int snapHead;
        synchronized (this) {
            snapCount = count;
            snapHead = head;
            if (snapCount == 0) {
                return LongStream.empty();
            }
            snapshot = new long[snapCount];
            for (int i = 0; i < snapCount; i++) {
                snapshot[i] = buffer[(snapHead + i) % capacity];
            }
        }
        return Arrays.stream(snapshot, 0, snapshot.length);
    }

    /**
     * Returns the arithmetic average of the last {@code n} entries as OptionalDouble.
     * If the buffer is empty, returns OptionalDouble.empty().
     * If n > size(), averages over the available entries.
     *
     * Time: O(n) where n is the requested number of entries (bounded by buffer size).
     *
     * @throws IllegalArgumentException if n <= 0
     */
    public synchronized OptionalDouble averageLast(int n) {
        if (n <= 0) throw new IllegalArgumentException("n must be > 0");
        if (count == 0) return OptionalDouble.empty();

        int use = Math.min(n, count);
        // accumulate in double to reduce risk of overflow for large sums
        double acc = 0.0;
        int start = (head + count - use) % capacity;
        for (int i = 0; i < use; i++) {
            acc += buffer[(start + i) % capacity];
        }
        return OptionalDouble.of(acc / (double) use);
    }

    /**
     * Returns the last (newest) entry as OptionalLong, or OptionalLong.empty() if buffer is empty.
     */
    public synchronized OptionalLong last() {
        if (count == 0) return OptionalLong.empty();
        int idx = (head + count - 1) % capacity;
        return OptionalLong.of(buffer[idx]);
    }

    /**
     * Returns the arithmetic average as OptionalDouble; O(1).
     */
    public synchronized OptionalDouble average() {
        return (count == 0) ? OptionalDouble.empty() : OptionalDouble.of((double) sum / (double) count);
    }

    /**
     * Returns a snapshot array of current contents from oldest to newest.
     */
    public synchronized long[] toArray() {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = buffer[(head + i) % capacity];
        }
        return out;
    }

    /**
     * Clears buffer.
     */
    public synchronized void clear() {
        head = 0;
        count = 0;
        sum = 0L;
    }
}
