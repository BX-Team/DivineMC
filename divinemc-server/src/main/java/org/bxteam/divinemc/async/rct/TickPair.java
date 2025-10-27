package org.bxteam.divinemc.async.rct;

import net.minecraft.world.entity.Entity;
import java.util.Set;

record TickPair(RegionData[] regions, Set<Entity> entities) {
}
