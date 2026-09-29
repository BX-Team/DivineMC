package org.bxteam.divinemc.util.structure;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparators;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public final class GeneralUtils {
    private GeneralUtils() {}

    // Since 26.3 JigsawBlockInfo is pre-parsed, so vanilla no longer reads NBT here and is already optimal
    public static boolean canJigsawsAttach(StructureTemplate.@NotNull JigsawBlockInfo jigsaw1, StructureTemplate.@NotNull JigsawBlockInfo jigsaw2) {
        return JigsawBlock.canAttach(jigsaw1, jigsaw2);
    }

    public static void shuffleAndPrioritize(@NotNull List<StructureTemplate.JigsawBlockInfo> list, RandomSource random) {
        Int2ObjectArrayMap<List<StructureTemplate.JigsawBlockInfo>> buckets = new Int2ObjectArrayMap<>();

        // Add entries to the bucket
        for (StructureTemplate.JigsawBlockInfo structureBlockInfo : list) {
            int key = structureBlockInfo.selectionPriority();

            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(structureBlockInfo);
        }

        // Shuffle the entries in the bucket
        for (List<StructureTemplate.JigsawBlockInfo> bucketList : buckets.values()) {
            Util.shuffle(bucketList, random);
        }

        if (buckets.size() == 1) {
            list.clear();
            copyAll(buckets.int2ObjectEntrySet().fastIterator().next().getValue(), list);
        }
        else if (buckets.size() > 1) {
            // Priorities found. Concat them into a single new master list in reverse order to match vanilla behavior
            list.clear();

            IntArrayList keys = new IntArrayList(buckets.keySet());
            keys.sort(IntComparators.OPPOSITE_COMPARATOR);

            for (int i = 0; i < keys.size(); i++) {
                copyAll(buckets.get(keys.getInt(i)), list);
            }
        }
    }

    public static <T> void copyAll(@NotNull List<T> src, List<T> dest) {
        // Do not listen to IDE. This is faster than addAll
        for (int i = 0; i < src.size(); i++) {
            dest.add(src.get(i));
        }
    }
}
