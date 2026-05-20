package org.bxteam.divinemc.region;

import net.minecraft.world.level.chunk.storage.RegionFile;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.region.buffered.BufferedRegionFile;
import org.bxteam.divinemc.region.buffered.BufferedRegionFileFlusher;
import org.bxteam.divinemc.region.linear.LinearRegionFile;
import org.jetbrains.annotations.Nullable;
import java.util.Arrays;

public enum EnumRegionFileExtension {
    MCA("mca", "mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    LINEAR("linear", "linear", (info) -> new LinearRegionFile(info.filePath(), DivineConfig.RegionSettingsCategory.compressionLevel, DivineConfig.RegionSettingsCategory.linearImplementation)),
    B_LINEAR("b_linear", "b_linear", (info) -> new BufferedRegionFile(info.filePath(), DivineConfig.RegionSettingsCategory.compressionLevel, (BufferedRegionFileFlusher) DivineConfig.RegionSettingsCategory.flusher));

    private final String name;
    private final String argument;
    private final IRegionCreateFunction creator;

    EnumRegionFileExtension(String name, String argument, IRegionCreateFunction creator) {
        this.name = name;
        this.argument = argument;
        this.creator = creator;
    }

    @Nullable
    public static EnumRegionFileExtension fromString(String string) {
        return Arrays.stream(values()).filter(format -> format.name.equalsIgnoreCase(string)).findFirst().orElse(null);
    }

    public IRegionCreateFunction getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}
