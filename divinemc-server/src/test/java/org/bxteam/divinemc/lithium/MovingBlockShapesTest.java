package org.bxteam.divinemc.lithium;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@VanillaFeature
@NullMarked
class MovingBlockShapesTest {
    private static final float[] PROGRESS = {0.0F, 0.5F, 1.0F, 0.25F, 0.5F, 0.0F};

    // shapes cached on the moved shape and the precomputed piston shapes must give the vanilla shape for every input, also when one block entity changes between calls
    @Test
    void cachedShapeMatchesVanilla() throws ReflectiveOperationException {
        final Field progress = field("progress");
        final Field direction = field("direction");
        final Field extending = field("extending");
        final Field movedState = field("movedState");
        final BlockPos pos = BlockPos.ZERO;

        final Map<VoxelShape, BlockState> distinctShapes = new IdentityHashMap<>();
        for (final Block block : BuiltInRegistries.BLOCK) {
            for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                distinctShapes.putIfAbsent(state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos), state);
            }
        }
        distinctShapes.putIfAbsent(Shapes.empty(), Blocks.AIR.defaultBlockState());
        distinctShapes.putIfAbsent(Shapes.block(), Blocks.STONE.defaultBlockState());
        final BlockState[] states = distinctShapes.values().toArray(BlockState[]::new);

        for (final boolean source : new boolean[] {false, true}) {
            final PistonMovingBlockEntity entity = new PistonMovingBlockEntity(pos, Blocks.MOVING_PISTON.defaultBlockState(), Blocks.PISTON.defaultBlockState(), Direction.UP, true, source);
            int checked = 0;
            // a source piston moves its own base: facing the movement (vanilla), or another way (a block entity from NBT)
            final int variants = source ? 3 : states.length;
            for (int i = 0; i < variants; ++i) {
                for (final Direction dir : Direction.values()) {
                    final BlockState moved = !source ? states[i]
                        : (i == 1 ? Blocks.PISTON : Blocks.STICKY_PISTON).defaultBlockState().setValue(PistonBaseBlock.FACING, i == 2 ? dir.getOpposite() : dir);
                    movedState.set(entity, moved);
                    direction.set(entity, dir);
                    for (final boolean ext : new boolean[] {true, false}) {
                        extending.set(entity, ext);
                        for (final float p : PROGRESS) {
                            progress.setFloat(entity, p);
                            final VoxelShape expected = vanilla(moved, dir, ext, source, p, pos);
                            final VoxelShape actual = entity.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
                            final VoxelShape again = entity.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
                            final String where = moved + " " + dir + " extending=" + ext + " source=" + source + " progress=" + p;
                            assertFalse(Shapes.joinIsNotEmpty(expected, actual, BooleanOp.NOT_SAME), where);
                            assertFalse(Shapes.joinIsNotEmpty(expected, again, BooleanOp.NOT_SAME), where);
                            assertEquals(expected.isEmpty(), actual.isEmpty(), where);
                            if (!expected.isEmpty()) {
                                assertEquals(expected.bounds(), actual.bounds(), where);
                            }
                            ++checked;
                        }
                    }
                }
            }
            assertFalse(checked == 0);
        }
    }

    // the vanilla formula of PistonMovingBlockEntity#getCollisionShape, outside the noclip branch
    private static VoxelShape vanilla(final BlockState movedState, final Direction direction, final boolean extending, final boolean source, final float progress, final BlockPos pos) {
        final VoxelShape head = !extending && source && movedState.getBlock() instanceof PistonBaseBlock
            ? movedState.setValue(PistonBaseBlock.EXTENDED, true).getCollisionShape(EmptyBlockGetter.INSTANCE, pos)
            : Shapes.empty();
        final BlockState state = source
            ? Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, direction).setValue(PistonHeadBlock.SHORT, extending != 1.0F - progress < 0.25F)
            : movedState;
        final float extendedProgress = extending ? progress - 1.0F : 1.0F - progress;
        return Shapes.or(head, state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).move(direction.getStepX() * extendedProgress, direction.getStepY() * extendedProgress, direction.getStepZ() * extendedProgress));
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field field = PistonMovingBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
