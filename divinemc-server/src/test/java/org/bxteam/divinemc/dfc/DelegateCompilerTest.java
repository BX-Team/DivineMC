package org.bxteam.divinemc.dfc;

import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@VanillaFeature
class DelegateCompilerTest {

    private record Wave(double scale) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(final FunctionContext context) {
            return Math.sin(context.blockX() * this.scale) + context.blockY() * 0.01;
        }

        @Override
        public double minValue() {
            return -2.0;
        }

        @Override
        public double maxValue() {
            return 5.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class Checker implements DensityFunction.SimpleFunction {
        @Override
        public double compute(final FunctionContext context) {
            return ((context.blockX() ^ context.blockZ()) & 1) == 0 ? 1.5 : -0.5;
        }

        @Override
        public double minValue() {
            return -0.5;
        }

        @Override
        public double maxValue() {
            return 1.5;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static List<DensityFunction> compile(final List<DensityFunction> originals) {
        final BytecodeGen.Context context = BytecodeGen.initContext();
        final List<DensityFunction> compiled = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            compiled.add(context.compileDelayed("root_" + i, originals.get(i)));
        }
        BytecodeGen.finalizeCompilation(context);
        return compiled;
    }

    private static List<DensityFunction> roots() {
        final DensityFunction wave = new Wave(0.3);
        final DensityFunction checker = new Checker();
        final DensityFunction gradient = DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0);
        return List.of(
            DensityFunctions.add(wave, checker),
            DensityFunctions.mul(DensityFunctions.add(new Wave(0.7), gradient), checker),
            DensityFunctions.add(DensityFunctions.mul(wave, DensityFunctions.constant(2.0)), DensityFunctions.mul(checker, gradient))
        );
    }

    @Test
    void computeMatchesEveryDelegate() {
        final List<DensityFunction> originals = roots();
        final List<DensityFunction> compiled = compile(originals);
        for (int i = 0; i < originals.size(); i++) {
            assertNotSame(originals.get(i), compiled.get(i), "root " + i + " was not compiled");
            for (int x = -20; x <= 20; x += 3) {
                for (int z = -20; z <= 20; z += 7) {
                    for (int y = -64; y <= 320; y += 31) {
                        final DensityFunction.FunctionContext ctx = new DensityFunction.SinglePointContext(x, y, z);
                        final int root = i, bx = x, by = y, bz = z;
                        assertEquals(originals.get(i).compute(ctx), compiled.get(i).compute(ctx), 0.0,
                            () -> "root " + root + " mismatch at " + bx + "," + by + "," + bz);
                    }
                }
            }
        }
    }

    @Test
    void fillArrayMatchesEveryDelegate() {
        final List<DensityFunction> originals = roots();
        final List<DensityFunction> compiled = compile(originals);
        final int size = 97;
        final DensityFunction.ContextProvider provider = new DensityFunction.ContextProvider() {
            @Override
            public DensityFunction.FunctionContext forIndex(final int index) {
                return new DensityFunction.SinglePointContext(index * 3 - 140, index * 4 - 64, 50 - index);
            }

            @Override
            public void fillAllDirectly(final double[] output, final DensityFunction function) {
                for (int i = 0; i < output.length; i++) {
                    output[i] = function.compute(this.forIndex(i));
                }
            }
        };
        for (int i = 0; i < originals.size(); i++) {
            final double[] expected = new double[size];
            final double[] actual = new double[size];
            originals.get(i).fillArray(expected, provider);
            compiled.get(i).fillArray(actual, provider);
            assertArrayEquals(expected, actual, 0.0, "root " + i);
        }
    }
}
