package org.bxteam.divinemc.util.entity;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class HopperMinecartHelper {
    private HopperMinecartHelper() {
    }

    public static boolean skipPull(MinecartHopper minecart) {
        if (!DivineConfig.PerformanceCategory.skipFullHopperMinecart) {
            return false;
        }

        // The stacks are read directly: getItem() would unpack a pending loot table without a player
        NonNullList<ItemStack> stacks = minecart.getItemStacks();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }
}
