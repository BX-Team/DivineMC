package org.bxteam.divinemc.region.linear;

import org.bxteam.divinemc.region.Flusher;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class LinearRegionFileFlusher extends Flusher<LinearRegionFile> {
    private final Queue<LinearRegionFile> savingQueue = new LinkedBlockingQueue<>();

    public LinearRegionFileFlusher(int maxThreadCount, long flushOfWriteTimeoutMs) {
        super(maxThreadCount, flushOfWriteTimeoutMs, flushOfWriteTimeoutMs);
    }

    @Override
    public void run() {
        while (!savingQueue.isEmpty()) {
            LinearRegionFile regionFile = savingQueue.poll();
            if (!regionFile.closed && regionFile.isMarkedToSave())
                executor.execute(regionFile::flushWrapper);
        }
    }

    @Override
    public void addFile(LinearRegionFile regionFile) {
        if (savingQueue.contains(regionFile)) return;
        savingQueue.add(regionFile);
    }

    @Override
    public void removeFile(LinearRegionFile linearRegionFile) {

    }

    @Override
    public void shutdown() {
        run();
        shutdownExecutor(executor);
        shutdownExecutor(scheduler);
    }
}
