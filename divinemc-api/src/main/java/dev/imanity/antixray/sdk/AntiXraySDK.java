package dev.imanity.antixray.sdk;

import org.jspecify.annotations.Nullable;

public class AntiXraySDK {
    private static @Nullable AntiXrayAdapter ADAPTER;

    /**
     * Gets the currently registered adapter.
     *
     * @return the adapter, or {@code null} if none has been registered
     */
    public static @Nullable AntiXrayAdapter getAdapter() {
        return ADAPTER;
    }

    /**
     * Sets the adapter that block changes and interactions are forwarded to.
     *
     * @param adapter the adapter, or {@code null} to unregister the current one
     */
    public static void setAdapter(@Nullable AntiXrayAdapter adapter) {
        ADAPTER = adapter;
    }
}
