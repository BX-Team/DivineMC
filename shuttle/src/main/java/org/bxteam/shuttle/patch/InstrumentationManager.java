package org.bxteam.shuttle.patch;

import java.lang.instrument.Instrumentation;

public final class InstrumentationManager {
    private static volatile Instrumentation instrumentation;
    private static final Object LOCK = new Object();

    private InstrumentationManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static void premain(final String agentArgs, final Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    public static void agentmain(final String agentArgs, final Instrumentation inst) {
        if (inst == null) {
            throw new IllegalArgumentException("Instrumentation instance cannot be null");
        }

        synchronized (LOCK) {
            if (instrumentation == null) {
                instrumentation = inst;
            }
        }
    }

    public static Instrumentation getInstrumentation() {
        final Instrumentation inst = instrumentation;
        if (inst == null) {
            throw new IllegalStateException(
                "Instrumentation has not been initialized. " +
                "Ensure the agent is properly loaded via -javaagent or attach mechanism."
            );
        }
        return inst;
    }
}
