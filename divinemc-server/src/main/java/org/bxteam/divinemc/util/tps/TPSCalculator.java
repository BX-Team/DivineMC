package org.bxteam.divinemc.util.tps;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TPSCalculator {
    public Long lastTickNanos;
    public Long currentTickNanos;
    private double allMissedTicks = 0;
    private final List<Double> tpsHistory = new CopyOnWriteArrayList<>();
    private static final int historyLimit = 40;

    public static final int MAX_TPS = 20;
    public static final int FULL_TICK = 50;
    private static final long FULL_TICK_NANOS = 50_000_000L;
    private static final double MIN_TPS = 1.0;
    private static final double MAX_ACCUMULATED_MISSED = 5.0;

    public TPSCalculator() {}

    public void doTick() {
        if (currentTickNanos != null) {
            lastTickNanos = currentTickNanos;
        }

        currentTickNanos = System.nanoTime();
        addToHistory(getTPS());
        clearMissedTicks();
        missedTick();
    }

    private void addToHistory(double tps) {
        if (tpsHistory.size() >= historyLimit) {
            tpsHistory.remove(0);
        }

        tpsHistory.add(tps);
    }

    public long getMSPT() {
        if (lastTickNanos == null || currentTickNanos == null) return FULL_TICK;
        long diffMs = (currentTickNanos - lastTickNanos) / 1_000_000L;
        return diffMs <= 0 ? 1 : diffMs;
    }

    public double getAverageTPS() {
        return tpsHistory.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(MAX_TPS);
    }

    public double getTPS() {
        if (lastTickNanos == null || currentTickNanos == null) return MAX_TPS;
        long diffNanos = currentTickNanos - lastTickNanos;
        if (diffNanos <= 0) return MAX_TPS;
        double tps = 1_000_000_000.0 / (double) diffNanos;
        return Math.min(tps, MAX_TPS);
    }

    public void missedTick() {
        if (lastTickNanos == null) return;

        long diffNanos = currentTickNanos - lastTickNanos;
        if (diffNanos <= 0) return;
        double missedTicks = ((double) diffNanos / (double) FULL_TICK_NANOS) - 1.0;
        if (missedTicks > 0) allMissedTicks += missedTicks;
        if (allMissedTicks > MAX_ACCUMULATED_MISSED) allMissedTicks = MAX_ACCUMULATED_MISSED;
    }

    public double getMostAccurateTPS() {
        double tps = Math.min(getTPS(), getAverageTPS());
        if (tps < MIN_TPS) return MIN_TPS;
        if (tps > MAX_TPS) return MAX_TPS;
        return tps;
    }

    public double getAllMissedTicks() {
        return allMissedTicks;
    }

    public int applicableMissedTicks() {
        return (int) Math.floor(allMissedTicks);
    }

    public void clearMissedTicks() {
        allMissedTicks -= applicableMissedTicks();
    }

    public void resetMissedTicks() {
        allMissedTicks = 0;
    }
}
