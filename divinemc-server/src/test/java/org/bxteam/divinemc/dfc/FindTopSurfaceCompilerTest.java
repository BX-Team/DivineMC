package org.bxteam.divinemc.dfc;

import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * FindTopSurface sits in the vanilla overworld noise router, so a mistake in its
 * bytecode emitter silently changes generated terrain rather than throwing. These
 * cases evaluate the compiled function against the vanilla record directly.
 */
@VanillaFeature
class FindTopSurfaceCompilerTest {

    private static DensityFunction compile(DensityFunction function) {
        BytecodeGen.Context context = BytecodeGen.initContext();
        DensityFunction compiled = context.compileDelayed("test_find_top_surface", function);
        BytecodeGen.finalizeCompilation(context);
        return compiled;
    }

    private static void assertMatchesVanilla(DensityFunction vanilla) {
        DensityFunction compiled = compile(vanilla);
        assertInstanceOf(CompiledDensityFunction.class, compiled, "function was not compiled");

        for (int x = -40; x <= 40; x += 7) {
            for (int z = -40; z <= 40; z += 7) {
                for (int y = -64; y <= 320; y += 13) {
                    final int bx = x, by = y, bz = z;
                    DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(bx, by, bz);
                    assertEquals(
                        vanilla.compute(context),
                        compiled.compute(context),
                        0.0,
                        () -> "mismatch at " + bx + "," + by + "," + bz
                    );
                }
            }
        }
    }

    @Test
    void surfaceFoundInsideRange() {
        // Density crosses zero at y = 128, so the surface is inside the scanned range.
        assertMatchesVanilla(DensityFunctions.findTopSurface(
            DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0),
            DensityFunctions.constant(256.0),
            -64,
            8
        ));
    }

    @Test
    void densityNeverPositiveFallsBackToLowerBound() {
        assertMatchesVanilla(DensityFunctions.findTopSurface(
            DensityFunctions.constant(-1.0),
            DensityFunctions.constant(256.0),
            -64,
            8
        ));
    }

    @Test
    void upperBoundBelowLowerBound() {
        assertMatchesVanilla(DensityFunctions.findTopSurface(
            DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0),
            DensityFunctions.constant(-128.0),
            -64,
            8
        ));
    }

    @Test
    void cellHeightThatDoesNotDivideTheRange() {
        assertMatchesVanilla(DensityFunctions.findTopSurface(
            DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0),
            DensityFunctions.constant(199.0),
            -60,
            7
        ));
    }

    @Test
    void nestedInsideAnotherFunction() {
        DensityFunction inner = DensityFunctions.findTopSurface(
            DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0),
            DensityFunctions.constant(256.0),
            -64,
            8
        );
        assertMatchesVanilla(DensityFunctions.add(DensityFunctions.mul(inner, DensityFunctions.constant(0.5)), DensityFunctions.constant(3.0)));
    }
}
