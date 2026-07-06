package org.bxteam.divinemc.async.rct;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

public final class AvgTimeLogger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_DIR = "tracking";
    private static final int MAX_LOG_AGE_DAYS = 30;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final String levelName;
    private FileWriter regionTickLogWriter;
    private LocalDate currentDate;

    public AvgTimeLogger(String levelName) {
        this.levelName = levelName;
        try {
            File logDir = new File(LOG_DIR + "/" + levelName);
            if (!logDir.exists() && !logDir.mkdirs()) {
                LOGGER.warn("Failed to create log directory {}", logDir.getAbsolutePath());
            }
            currentDate = LocalDate.now();
            initializeLogWriter();
            cleanupOldLogs();
        } catch (IOException e) {
            LOGGER.error("Failed to initialize region tick time log file", e);
        }
    }

    private void cleanupOldLogs() {
        File logDir = new File(LOG_DIR + "/" + levelName);
        File[] files = logDir.listFiles();
        if (files == null) return;

        LocalDate cutoffDate = LocalDate.now().minusDays(MAX_LOG_AGE_DAYS);

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("region-tick-") && name.endsWith(".log")) {
                String dateStr = name.substring("region-tick-".length(), name.length() - ".log".length());
                try {
                    LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                    if (!fileDate.equals(LocalDate.now())) {
                        compressLogFile(file);
                    }
                } catch (Exception ignored) {}
            } else if (name.startsWith("region-tick-") && name.endsWith(".log.gz")) {
                String dateStr = name.substring("region-tick-".length(), name.length() - ".log.gz".length());
                try {
                    LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                    if (fileDate.isBefore(cutoffDate) && !file.delete()) {
                        LOGGER.warn("Failed to delete old log file: {}", file.getName());
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void compressLogFile(File file) {
        File gzFile = new File(file.getAbsolutePath() + ".gz");
        try (FileInputStream fis = new FileInputStream(file);
             FileOutputStream fos = new FileOutputStream(gzFile);
             GZIPOutputStream gzipOS = new GZIPOutputStream(fos)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzipOS.write(buffer, 0, len);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to compress old log: {}", file.getName(), e);
            return;
        }
        if (!file.delete()) {
            LOGGER.warn("Failed to delete original log file after compression: {}", file.getName());
        }
    }

    private void initializeLogWriter() throws IOException {
        String filename = "region-tick-" + currentDate.format(DATE_FORMATTER) + ".log";
        File logFile = new File(LOG_DIR + "/" + levelName, filename);
        regionTickLogWriter = new FileWriter(logFile, true);
    }

    public void logTickTime(String data) {
        try {
            LocalDate today = LocalDate.now();
            if (!today.equals(currentDate)) {
                currentDate = today;
                if (regionTickLogWriter != null) {
                    regionTickLogWriter.close();
                }
                cleanupOldLogs();
                initializeLogWriter();
            }

            if (regionTickLogWriter != null) {
                String timestamp = LocalTime.now().format(TIME_FORMATTER);
                regionTickLogWriter.write("[" + timestamp + "]\n" + data);
                regionTickLogWriter.flush();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to log region tick time", e);
        }
    }

    public void close() throws IOException {
        if (regionTickLogWriter != null) {
            regionTickLogWriter.close();
        }
    }
}
