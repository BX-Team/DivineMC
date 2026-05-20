package org.bxteam.divinemc.config;

public enum ConfigCategory {
    ASYNC("async"),
    PERFORMANCE("performance"),
    FIXES("fixes"),
    NETWORK("network"),
    MISC("misc"),
    REGION("region-settings");

    private final String name;

    ConfigCategory(String name) {
        this.name = name;
    }

    public String key(String sub) {
        return name + "." + sub;
    }
}
