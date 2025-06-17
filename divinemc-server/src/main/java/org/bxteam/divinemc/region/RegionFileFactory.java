package org.bxteam.divinemc.region;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bxteam.divinemc.config.DivineConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

public class RegionFileFactory {
    @Contract("_, _, _, _ -> new")
    public static @NotNull IRegionFile getAbstractRegionFile(RegionStorageInfo storageKey, Path directory, Path path, boolean dsync) throws IOException {
        return getAbstractRegionFile(storageKey, directory, path, RegionFileVersion.getCompressionFormat(), dsync);
    }

    @Contract("_, _, _, _, _ -> new")
    public static @NotNull IRegionFile getAbstractRegionFile(RegionStorageInfo storageKey, @NotNull Path path, Path directory, RegionFileVersion compressionFormat, boolean dsync) throws IOException {
        final String fullFileName = path.getFileName().toString();
        final String[] fullNameSplit = fullFileName.split("\\.");
        final String extensionName = fullNameSplit[fullNameSplit.length - 1];
        switch (RegionFileFormat.fromExtension(extensionName)) {
            case LINEAR -> {
                return new LinearRegionFile(path, DivineConfig.MiscCategory.linearImplementation, DivineConfig.MiscCategory.linearCompressionLevel);
            }

            default -> {
                return new RegionFile(storageKey, path, directory, compressionFormat, dsync);
            }
        }
    }
}
