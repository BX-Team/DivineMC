package gg.pufferfish.pufferfish.sentry;

import io.sentry.Sentry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;

public class SentryManager {
    private static final Logger LOGGER = LogManager.getLogger("SentryManager");
    private static boolean initialized = false;

    private SentryManager() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    public static synchronized void init(Level logLevel) {
        if (initialized) {
            return;
        }
        if (logLevel == null) {
            LOGGER.error("Invalid log level, defaulting to WARN.");
            logLevel = Level.WARN;
        }
        try {
            initialized = true;

            Sentry.init(options -> {
                options.setDsn(DivineConfig.MiscCategory.sentryDsn);
                options.setMaxBreadcrumbs(100);
            });

            PufferfishSentryAppender appender = new PufferfishSentryAppender(logLevel);
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
            LOGGER.info("Sentry logging started!");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize sentry!", e);
            initialized = false;
        }
    }
}
