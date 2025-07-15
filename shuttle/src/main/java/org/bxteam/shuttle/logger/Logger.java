package org.bxteam.shuttle.logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;

public class Logger {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("Shuttle");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    static {
        LOGGER.setUseParentHandlers(false);

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new ShuttleFormatter());
        consoleHandler.setLevel(Level.ALL);
        LOGGER.addHandler(consoleHandler);

        LOGGER.setLevel(Level.INFO);
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warning(message);
    }

    public static void error(String message) {
        LOGGER.severe(message);
    }

    public static void error(String message, Throwable throwable) {
        LOGGER.log(Level.SEVERE, message, throwable);
    }

    public static void debug(String message) {
        LOGGER.fine(message);
    }

    public static void setLevel(Level level) {
        LOGGER.setLevel(level);
        LOGGER.getHandlers()[0].setLevel(level);
    }

    private static class ShuttleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String time = LocalTime.now().format(TIME_FORMATTER);
            String level = record.getLevel().getName();
            String message = record.getMessage();

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(time).append(" ").append(level).append("]: ");
            sb.append("[Shuttle] ");
            sb.append(message);
            sb.append(System.lineSeparator());

            if (record.getThrown() != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                record.getThrown().printStackTrace(pw);
                sb.append(sw.toString());
            }

            return sb.toString();
        }
    }
}
