package org.bxteam.divinemc.async.rct;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;

public final class AvgTimeLogger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_DIR = "tracking";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final String levelName;
    private FileWriter regionTickLogWriter;
    private LocalDate currentDate;

    public AvgTimeLogger(String levelName) {
        this.levelName = levelName;
        try {
            File logDir = new File(LOG_DIR + "/" + levelName);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            currentDate = LocalDate.now();
            initializeLogWriter();
        } catch (IOException e) {
            LOGGER.error("Failed to initialize region tick time log file", e);
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
