package net.caffeinemc.mods.lithium.common.block.entity.sleeping_sculk;

import net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class SculkSleeping {
    private SculkSleeping() {
    }

    public static boolean hasPendingVibration(VibrationSystem.Data data) {
        return data.getCurrentVibration() != null || data.getSelectionStrategy().chosenCandidate(Long.MAX_VALUE).isPresent();
    }

    public static void sleepIfIdle(SleepingBlockEntity blockEntity, VibrationSystem.Data data) {
        if (!hasPendingVibration(data)) {
            blockEntity.lithium$startSleeping();
        }
    }

    public static void wakeIfPending(SleepingBlockEntity blockEntity, VibrationSystem.Data data) {
        if (hasPendingVibration(data)) {
            blockEntity.wakeUpNow();
        }
    }
}
