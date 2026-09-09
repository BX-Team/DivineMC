package org.bxteam.divinemc.dfc;

import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The noise router is compiled as a batch: every function becomes a root of one
 * generated class, sharing its subtrees. A wrong root index or a subtree shared
 * between two roots that should not be would silently hand back another
 * function's values, so each root is checked against its own vanilla original.
 */
@VanillaFeature
class MultiRootCompilerTest {

    private static DensityFunction gradient(double from, double to) {
        return DensityFunctions.yClampedGradient(-64, 320, from, to);
    }

    private static void assertAllRootsMatch(List<DensityFunction> originals) {
        BytecodeGen.Context context = BytecodeGen.initContext();
        List<DensityFunction> compiled = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            compiled.add(context.compileDelayed("root_" + i, originals.get(i)));
        }
        BytecodeGen.finalizeCompilation(context);

        for (int i = 0; i < originals.size(); i++) {
            DensityFunction original = originals.get(i);
            DensityFunction actual = compiled.get(i);
            assertNotSame(original, actual, "root " + i + " was not compiled");
            for (int x = -32; x <= 32; x += 11) {
                for (int z = -32; z <= 32; z += 11) {
                    for (int y = -64; y <= 320; y += 17) {
                        DensityFunction.FunctionContext ctx = new DensityFunction.SinglePointContext(x, y, z);
                        final int ri = i, bx = x, by = y, bz = z;
                        assertEquals(
                            original.compute(ctx),
                            actual.compute(ctx),
                            0.0,
                            () -> "root " + ri + " mismatch at " + bx + "," + by + "," + bz
                        );
                    }
                }
            }
        }
    }

    @Test
    void distinctRootsKeepTheirOwnValues() {
        assertAllRootsMatch(List.of(
            DensityFunctions.add(gradient(1.0, -1.0), DensityFunctions.constant(5.0)),
            DensityFunctions.mul(gradient(2.0, -2.0), DensityFunctions.constant(3.0)),
            DensityFunctions.add(gradient(-1.0, 1.0), DensityFunctions.constant(-7.0))
        ));
    }

    @Test
    void rootsSharingASubtreeStayIndependent() {
        // The shared node is the whole point of batching: it must be compiled once
        // and still produce the right value in each root that uses it.
        DensityFunction shared = DensityFunctions.mul(gradient(1.0, -1.0), gradient(0.5, 2.0));
        assertAllRootsMatch(List.of(
            shared,
            DensityFunctions.add(shared, DensityFunctions.constant(100.0)),
            DensityFunctions.mul(shared, DensityFunctions.constant(-1.0)),
            DensityFunctions.add(DensityFunctions.mul(shared, shared), shared)
        ));
    }

    @Test
    void manyRootsKeepTheirIndices() {
        List<DensityFunction> functions = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            functions.add(DensityFunctions.add(gradient(i, -i), DensityFunctions.constant(i * 10.0)));
        }
        assertAllRootsMatch(functions);
    }

    @Test
    void findTopSurfaceAmongOtherRoots() {
        assertAllRootsMatch(List.of(
            DensityFunctions.add(gradient(1.0, -1.0), DensityFunctions.constant(1.0)),
            DensityFunctions.findTopSurface(gradient(1.0, -1.0), DensityFunctions.constant(256.0), -64, 8),
            DensityFunctions.mul(gradient(3.0, -3.0), DensityFunctions.constant(2.0))
        ));
    }
}
