package org.bxteam.divinemc.util;

import com.mojang.serialization.Codec;
import net.minecraft.Util;
import net.minecraft.world.phys.AABB;

import java.util.List;

public interface Codecs {
    Codec<AABB> AABB_CODEC = Codec.DOUBLE
        .listOf()
        .comapFlatMap(
            list -> Util.fixedSize(list, 6).map(listx -> new AABB(listx.getFirst(), listx.get(1), listx.get(2), listx.get(3), listx.get(4), listx.get(5))),
            aabb -> List.of(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ)
        );
}
