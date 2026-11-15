package org.bxteam.divinemc.util.cache;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The last collision shape of one moving piston block entity. The vanilla shape is a pure function of these inputs,
 * but is moved and optimized again on every call, by every entity that collides with it and every neighbour that
 * checks its support. Shapes are compared by identity: block states hand out their cached instances. The record is
 * immutable, so region threads reading one block entity at once at worst compute the same shape twice.
 */
@NullMarked
public record MovingPistonShapeCache(float progress, Direction direction, boolean extending, VoxelShape headShape, VoxelShape movedShape, VoxelShape result) {
    /**
     * @return {@code cache} if it holds the shape for these inputs, otherwise a new entry with the shape computed
     */
    public static MovingPistonShapeCache lookup(final @Nullable MovingPistonShapeCache cache, final float progress, final Direction direction, final boolean extending, final VoxelShape headShape, final VoxelShape movedShape) {
        if (cache != null && cache.progress == progress && cache.direction == direction && cache.extending == extending
            && cache.headShape == headShape && cache.movedShape == movedShape) {
            return cache;
        }
        return new MovingPistonShapeCache(progress, direction, extending, headShape, movedShape, compute(progress, direction, extending, headShape, movedShape));
    }

    public static VoxelShape compute(final float progress, final Direction direction, final boolean extending, final VoxelShape headShape, final VoxelShape movedShape) {
        final float extendedProgress = extending ? progress - 1.0F : 1.0F - progress;
        final double dx = direction.getStepX() * extendedProgress;
        final double dy = direction.getStepY() * extendedProgress;
        final double dz = direction.getStepZ() * extendedProgress;
        return Shapes.or(headShape, movedShape.move(dx, dy, dz));
    }
}
