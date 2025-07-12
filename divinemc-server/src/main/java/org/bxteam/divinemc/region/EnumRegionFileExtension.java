package org.bxteam.divinemc.region;

import net.minecraft.world.level.chunk.storage.RegionFile;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.region.type.BufferedRegionFile;
import org.bxteam.divinemc.region.type.LinearRegionFile;
import org.jetbrains.annotations.Nullable;

public enum EnumRegionFileExtension {
    MCA("mca", "mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    LINEAR("linear", "linear", (info) -> new LinearRegionFile(info.info(), info.filePath(), info.folder(), info.sync(), DivineConfig.MiscCategory.linearCompressionLevel)),
    B_LINEAR("b_linear", "b_linear", (info) -> new BufferedRegionFile(info.filePath(), DivineConfig.MiscCategory.linearCompressionLevel));

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
        for (EnumRegionFileExtension format : values()) {
            if (format.name.equalsIgnoreCase(string)) {
                return format;
            }
        }

        return null;
    }

    public IRegionCreateFunction getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}
