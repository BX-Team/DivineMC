package org.bxteam.divinemc.region;

import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;

public record RegionFileInfo(RegionStorageInfo info, Path filePath, Path folder, boolean sync) { }
