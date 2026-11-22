package org.bxteam.divinemc.region;

import com.mojang.logging.LogUtils;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.region.format.LinearFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NullMarked
public final class RegionFormatCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.-?[0-9]+\\.-?[0-9]+\\.([a-z_]+)$");
    private static final String LEGACY_BUFFERED = "b_linear";
    private static final String CONVERT_HINT = "Convert the world with linear-converter (https://github.com/BX-Team/linear-converter) before starting, or keep running the previous DivineMC version on it.";

    private RegionFormatCheck() {
    }

    public static void verify(Path folder) {
        // the file fixer opens chunk storage before divinemc.yml is loaded, while the type is still the default
        if (!DivineConfig.isLoaded()) {
            return;
        }
        verify(folder, RegionFormat.configured());
    }

    static void verify(Path folder, RegionFormat configured) {
        if (!Files.isDirectory(folder)) {
            return;
        }

        final String expected = configured.extension();
        boolean hasExpected = false;
        @Nullable String other = null;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                final Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }

                final String extension = matcher.group(1);
                if (extension.equals(LEGACY_BUFFERED)) {
                    throw new IllegalStateException(String.format(
                        "Region folder %s holds .b_linear files (Buffered Linear), which this DivineMC no longer reads. %s", folder, CONVERT_HINT));
                }
                if (extension.equals(RegionFormat.LINEAR.extension()) && isLegacyLinear(file)) {
                    throw new IllegalStateException(String.format(
                        "Region file %s is in an old Linear layout (V1/V2/V3), which this DivineMC no longer reads. %s", file, CONVERT_HINT));
                }
                if (extension.equals(expected)) {
                    hasExpected = true;
                } else if (other == null && isRegionExtension(extension)) {
                    other = extension;
                }

            }
        } catch (IOException e) {
            LOGGER.warn("Failed to check the region format of {}", folder, e);
            return;
        }

        if (other == null) {
            return;
        }

        if (!hasExpected) {
            throw new IllegalStateException(String.format(
                "Region folder %s holds .%s files, but misc.region-settings.type in divinemc.yml is %s. Starting would generate new terrain next to the existing world: convert the world or set the type back.",
                folder, other, configured
            ));
        }

        LOGGER.warn("Region folder {} holds both .{} and .{} files; only .{} files are used (misc.region-settings.type in divinemc.yml)", folder, expected, other, expected);
    }

    private static boolean isLegacyLinear(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] magic = in.readNBytes(8);
            return magic.length == 8 && ByteBuffer.wrap(magic).getLong() == LinearFormat.LEGACY_MAGIC;
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}", file, e);
            return false;
        }
    }

    private static boolean isRegionExtension(String extension) {
        for (RegionFormat type : RegionFormat.values()) {
            if (type.extension().equals(extension)) {
                return true;
            }
        }
        return false;
    }
}
