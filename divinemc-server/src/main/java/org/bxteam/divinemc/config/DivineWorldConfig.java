package org.bxteam.divinemc.config;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.IOException;

@SuppressWarnings("unused")
public class DivineWorldConfig {
    private final YamlFile config;
    private final String worldName;
    private final World.Environment environment;

    public DivineWorldConfig(String worldName, World.Environment environment) throws IOException {
        this.config = DivineConfig.config;
        this.worldName = worldName;
        this.environment = environment;
        init();
    }

    public void init() throws IOException {
        DivineConfig.readConfig(DivineWorldConfig.class, this);
    }

    public @Nullable String getString(String path, String def, String... comments) {
        this.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) this.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return this.config.getString("world-settings." + this.worldName + "." + path, this.config.getString("world-settings.default." + path));
    }

    public boolean getBoolean(String path, boolean def, String... comments) {
        this.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) this.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return this.config.getBoolean("world-settings." + this.worldName + "." + path, this.config.getBoolean("world-settings.default." + path));
    }

    public double getDouble(String path, double def, String... comments) {
        this.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) this.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return this.config.getDouble("world-settings." + this.worldName + "." + path, this.config.getDouble("world-settings.default." + path));
    }

    public int getInt(String path, int def, String... comments) {
        this.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) this.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return this.config.getInt("world-settings." + this.worldName + "." + path, this.config.getInt("world-settings.default." + path));
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

    public boolean allowEntityPortalWithPassenger = true;
    public boolean allowTripwireDupe = false;
    private void unsupportedFeatures() {
        allowEntityPortalWithPassenger = getBoolean("unsupported-features.allow-entity-portal-with-passenger", allowEntityPortalWithPassenger,
            "Enables or disables the fix of MC-67 bug: https://bugs-legacy.mojang.com/browse/MC-67");
        allowTripwireDupe = getBoolean("unsupported-features.allow-tripwire-dupe", allowTripwireDupe,
            "Bring back MC-59471, MC-129055 on 1.21.2+, which fixed in 1.21.2 snapshots 24w33a and 24w36a");
    }
}
