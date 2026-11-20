package org.bxteam.divinemc.region;

import org.bukkit.support.environment.Normal;
import org.bxteam.divinemc.region.format.LinearFormat;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class RegionFormatCheckTest {
    @TempDir
    Path dir;

    @Test
    void refusesWorldsInRemovedLayouts() throws IOException {
        Files.write(this.dir.resolve("r.0.0.linear"), ByteBuffer.allocate(64).putLong(LinearFormat.LEGACY_MAGIC).array());
        final IllegalStateException legacy = assertThrows(IllegalStateException.class, () -> RegionFormatCheck.verify(this.dir, RegionFormat.LINEAR));
        assertTrue(legacy.getMessage().contains("old Linear layout"), legacy.getMessage());

        Files.delete(this.dir.resolve("r.0.0.linear"));
        Files.write(this.dir.resolve("r.0.0.b_linear"), new byte[64]);
        final IllegalStateException buffered = assertThrows(IllegalStateException.class, () -> RegionFormatCheck.verify(this.dir, RegionFormat.LINEAR));
        assertTrue(buffered.getMessage().contains("b_linear"), buffered.getMessage());
    }

    @Test
    void acceptsCurrentLinearFiles() throws IOException {
        Files.write(this.dir.resolve("r.0.0.linear"), LinearFormat.header(0, 0));
        assertDoesNotThrow(() -> RegionFormatCheck.verify(this.dir, RegionFormat.LINEAR));
    }
}
