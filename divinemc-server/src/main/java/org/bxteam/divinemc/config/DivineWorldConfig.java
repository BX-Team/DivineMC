package org.bxteam.divinemc.config;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;

import java.io.IOException;

@SuppressWarnings("unused")
public class DivineWorldConfig {
    private final String legacyWorldName;
    private final String worldName;
    private final World.Environment environment;

    public DivineWorldConfig(String legacyWorldName, World.Environment environment, Key worldKey) throws IOException {
        this.legacyWorldName = legacyWorldName;
        this.worldName = worldKey.asString();
        this.environment = environment;
        init();
    }

    public void init() throws IOException {
        if (DivineConfig.configVersion < 8) {
            ConfigurationSection section = DivineConfig.config.getConfigurationSection("world-settings." + this.legacyWorldName);
            if (section != null) {
                DivineConfig.config.set("world-settings." + this.legacyWorldName, null);
                DivineConfig.config.set("world-settings." + this.worldName, section);
                Bukkit.getLogger().info("NOTE: Migrated DivineMC world config %s -> %s".formatted(this.legacyWorldName, this.worldName));
            }
        }

        DivineConfig.readConfig(DivineWorldConfig.class, this);
    }

    public @Nullable String getString(String path, String def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getString("world-settings." + this.worldName + "." + path, DivineConfig.config.getString("world-settings.default." + path));
    }

    public boolean getBoolean(String path, boolean def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getBoolean("world-settings." + this.worldName + "." + path, DivineConfig.config.getBoolean("world-settings.default." + path));
    }

    public double getDouble(String path, double def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getDouble("world-settings." + this.worldName + "." + path, DivineConfig.config.getDouble("world-settings.default." + path));
    }

    public int getInt(String path, int def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getInt("world-settings." + this.worldName + "." + path, DivineConfig.config.getInt("world-settings.default." + path));
    }

    public boolean snowballCanKnockback = true;
    public boolean disableSnowballSaving = false;
    public boolean eggCanKnockback = true;
    public boolean disableFireworkSaving = false;
    private void projectilesSettings() {
        snowballCanKnockback = getBoolean("gameplay-mechanics.projectiles.snowball.knockback", snowballCanKnockback);
        disableSnowballSaving = getBoolean("gameplay-mechanics.projectiles.snowball.disable-saving", disableSnowballSaving);
        eggCanKnockback = getBoolean("gameplay-mechanics.projectiles.egg.knockback", eggCanKnockback);
        disableFireworkSaving = getBoolean("gameplay-mechanics.projectiles.firework.disable-saving", disableFireworkSaving);
    }

    public int projectilePerTick = 10;
    public int projectileMax = 10;
    public boolean resetMovementAfterReachLimit = false;
    public boolean removeFromWorldAfterReachLimit = false;
    private void reduceProjectileChunkLoading() {
        projectilePerTick = getInt("gameplay-mechanics.reduce-projectile-chunk-loading.per-tick", projectilePerTick,
            "The maximum number of chunks that can be synchronously loaded by all projectiles in one world in a tick");
        projectileMax = getInt("gameplay-mechanics.reduce-projectile-chunk-loading.per-projectile.max", projectileMax,
            "The maximum number of chunks that can be synchronously loaded by a projectile throughout its lifetime. If a value < 0, the feature is disabled.");
        resetMovementAfterReachLimit = getBoolean("gameplay-mechanics.reduce-projectile-chunk-loading.per-projectile.reset-movement-after-reach-limit", resetMovementAfterReachLimit,
            "Whether to set the planar velocity of projectiles that cross the projectileMax threshold to 0, so that they stop attempting to cross chunk boundaries");
        removeFromWorldAfterReachLimit = getBoolean("gameplay-mechanics.reduce-projectile-chunk-loading.per-projectile.remove-from-world-after-reach-limit", removeFromWorldAfterReachLimit,
            "Whether to remove projectiles that cross the projectileMax threshold from the world entirely");
    }

    public boolean allowEntityPortalWithPassenger = true;
    public boolean allowTripwireDupe = false;
    private void unsupportedFeatures() {
        allowEntityPortalWithPassenger = getBoolean("unsupported-features.allow-entity-portal-with-passenger", allowEntityPortalWithPassenger,
            "Fixes MC-67: https://bugs-legacy.mojang.com/browse/MC-67",
            "Entities with passengers cannot travel through portals");
        allowTripwireDupe = getBoolean("unsupported-features.allow-tripwire-dupe", allowTripwireDupe,
            "Bring back MC-59471, MC-129055 on 1.21.2+, which fixed in 1.21.2 snapshots 24w33a and 24w36a");
    }

    public boolean spectatorDontGetAdvancement = false;
    private void worldFeatures() {
        spectatorDontGetAdvancement = getBoolean("features.spectator-dont-get-advancement", spectatorDontGetAdvancement,
            "Prevents spectators from getting advancements");
    }
}
