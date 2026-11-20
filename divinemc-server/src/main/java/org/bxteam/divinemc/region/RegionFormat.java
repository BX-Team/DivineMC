package org.bxteam.divinemc.region;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** The on-disk format of region, entities and poi folders, chosen by misc.region-settings.type. */
@NullMarked
public enum RegionFormat {
    MCA("mca"),
    LINEAR("linear");

    private final String extension;

    RegionFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return this.extension;
    }

    public static @Nullable RegionFormat parse(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static RegionFormat configured() {
        return DivineConfig.MiscCategory.regionFormat;
    }

    /** ".mca" or ".linear", for region file names. */
    public static String fileSuffix() {
        return "." + configured().extension;
    }

    /** Opens a region file of the configured format; the file name must carry its extension. */
    public static IRegionFile open(RegionStorageInfo info, Path path, Path folder, boolean sync) throws IOException {
        final RegionFormat format = configured();
        if (!path.getFileName().toString().endsWith("." + format.extension)) {
            throw new IOException("Region file " + path + " is not ." + format.extension + " (misc.region-settings.type is " + format + ")");
        }
        return switch (format) {
            case MCA -> new RegionFile(info, path, folder, sync);
            case LINEAR -> LinearRegionFile.open(path, LinearFlusher.get());
        };
    }
}
