package net.caffeinemc.mods.lithium.common.shapes;

import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Collision shapes of moving blocks and moving pistons. Vanilla offsets and simplifies the moved block's shape on every
 * call; with the vanilla progress steps there are only a few distinct results per shape, so they are kept on the
 * original shape. The retracting piston base with its moving head has 18 possible shapes, computed once.
 *
 * @author 2No2Name
 */
@NullMarked
public final class MovingBlockShapes {
    private static final VoxelShape[] PISTON_BASE_WITH_MOVING_HEAD_SHAPES = precomputePistonBaseWithMovingHeadShapes();

    private MovingBlockShapes() {
    }

    /**
     * @return the vanilla result of {@code PistonMovingBlockEntity#getCollisionShape} after the noclip check, or
     * {@code null} when the inputs are outside the cached cases and the vanilla code has to run
     */
    public static @Nullable VoxelShape getCollisionShape(final VoxelShape movedShape, final float extendedProgress, final Direction direction,
                                                         final boolean extending, final boolean isSourcePiston, final BlockState movedState) {
        final float absOffset = Math.abs(extendedProgress);
        if (absOffset != 0.0F && absOffset != 0.5F && absOffset != 1.0F) {
            return null;
        }
        if (extending || !isSourcePiston || !(movedState.getBlock() instanceof PistonBaseBlock)) {
            // the piston head shape is empty here: vanilla's union is the moved shape, simplified
            return getOffsetAndSimplified(movedShape, absOffset, extendedProgress < 0.0F ? direction.getOpposite() : direction);
        }
        // the base keeps its own facing: only the vanilla case where it matches the movement shares the precomputed shapes
        if (movedState.getValue(PistonBaseBlock.FACING) != direction) {
            return null;
        }
        return PISTON_BASE_WITH_MOVING_HEAD_SHAPES[getIndexForMergedShape(extendedProgress, direction)];
    }

    private static VoxelShape getOffsetAndSimplified(final VoxelShape blockShape, final float offset, final Direction direction) {
        final int index = getIndexForOffsetSimplifiedShapes(offset, direction);
        final VoxelShape @Nullable [] cache = blockShape.lithium$offsetAndSimplified;
        if (cache != null) {
            final VoxelShape cached = cache[index];
            if (cached != null) {
                return cached;
            }
        }
        final VoxelShape offsetShape = blockShape.move(direction.getStepX() * offset, direction.getStepY() * offset, direction.getStepZ() * offset).optimize();
        // copy on write: a reader on another thread sees either the old array or the complete new one
        final VoxelShape[] updated = cache == null ? new VoxelShape[1 + 2 * 6] : cache.clone();
        updated[index] = offsetShape;
        blockShape.lithium$offsetAndSimplified = updated;
        return offsetShape;
    }

    private static int getIndexForOffsetSimplifiedShapes(final float offset, final Direction direction) {
        if (offset == 0.0F) {
            return 0;
        }
        return (int) (2 * offset) + 2 * direction.get3DDataValue();
    }

    private static VoxelShape[] precomputePistonBaseWithMovingHeadShapes() {
        final float[] offsets = {0.0F, 0.5F, 1.0F};
        final VoxelShape[] mergedShapes = new VoxelShape[offsets.length * Direction.values().length];
        for (final Direction facing : Direction.values()) {
            final VoxelShape baseShape = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.EXTENDED, true)
                .setValue(PistonBaseBlock.FACING, facing).getCollisionShape(EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
            for (final float offset : offsets) {
                // vanilla: short = extending != 1 - progress < 0.25, with extending false and offset = 1 - progress
                final boolean isShort = offset < 0.25F;
                final VoxelShape headShape = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, facing)
                    .setValue(PistonHeadBlock.SHORT, isShort).getCollisionShape(EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
                final VoxelShape offsetHead = headShape.move(facing.getStepX() * offset, facing.getStepY() * offset, facing.getStepZ() * offset);
                mergedShapes[getIndexForMergedShape(offset, facing)] = Shapes.or(baseShape, offsetHead);
            }
        }
        return mergedShapes;
    }

    private static int getIndexForMergedShape(final float offset, final Direction direction) {
        return (int) (2 * offset) + 3 * direction.get3DDataValue();
    }
}
