/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021-2026 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.c2me.opts.dfc.common.gen.jvm;

import com.google.common.base.Suppliers;
import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.ducks.IDfcObjectCacheCapable;
import com.ishland.c2me.opts.dfc.common.ducks.IBlendingAwareVisitor;
import com.ishland.c2me.opts.dfc.common.ducks.ICoordinatesFilling;
import com.ishland.c2me.opts.dfc.common.ducks.IPreloadedCoordinates;
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
import com.ishland.c2me.opts.dfc.common.gen.jvm.vif.EachApplierVanillaInterface;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

public class SubCompiledDensityFunction implements DensityFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubCompiledDensityFunction.class);

    private final ISingleMethod singleMethod;
    private final IMultiMethod multiMethod;
    protected final Supplier<DensityFunction> blendingFallback;

    // also called from generated code
    public SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, DensityFunction blendingFallback) {
        this(singleMethod, multiMethod, unwrap(blendingFallback));
    }

    protected SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, Supplier<DensityFunction> blendingFallback) {
        this.singleMethod = Objects.requireNonNull(singleMethod);
        this.multiMethod = Objects.requireNonNull(multiMethod);
        this.blendingFallback = blendingFallback;
    }

    private static Supplier<DensityFunction> unwrap(DensityFunction densityFunction) {
        if (densityFunction instanceof SubCompiledDensityFunction scdf) {
            return scdf.blendingFallback;
        } else {
            return densityFunction != null ? Suppliers.ofInstance(densityFunction) : null;
        }
    }

    @Override
    public double compute(FunctionContext pos) {
        if (pos instanceof NoiseChunk sampler) {
            if (!sampler.blender.isEmpty()) { // DivineMC - 26.2: FunctionContext.getBlender() removed
                DensityFunction fallback = this.getFallback();
                if (fallback == null) {
                    throw new IllegalStateException("blendingFallback is no more");
                }
                return fallback.compute(pos);
            }
        }
        DfcObjectCache cache = pos instanceof IDfcObjectCacheCapable cacheCapable ? cacheCapable.c2me$getDfcObjectCache() : DfcObjectCache.Noop.INSTANCE;
        return this.singleMethod.evalSingle(pos.blockX(), pos.blockY(), pos.blockZ(), EvalType.from(pos), cache);
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        if (applier instanceof NoiseChunk sampler) {
            if (!sampler.blender.isEmpty()) { // DivineMC - 26.2: FunctionContext.getBlender() removed
                DensityFunction fallback = this.getFallback();
                if (fallback == null) {
                    throw new IllegalStateException("blendingFallback is no more");
                }
                fallback.fillArray(densities, applier);
                return;
            }
        }
        if (applier instanceof EachApplierVanillaInterface vanillaInterface) {
            this.multiMethod.evalMulti(densities, vanillaInterface.getX(), vanillaInterface.getY(), vanillaInterface.getZ(), EvalType.from(applier), vanillaInterface.c2me$getDfcObjectCache());
            return;
        }

        DfcObjectCache cache = applier instanceof IDfcObjectCacheCapable cacheCapable ? cacheCapable.c2me$getDfcObjectCache() : DfcObjectCache.Noop.INSTANCE;
        int[] x;
        int[] y;
        int[] z;
        boolean allocatedOnDemand;
        if (applier instanceof IPreloadedCoordinates preloadedCoordinates) {
            x = preloadedCoordinates.c2me$getXArray();
            y = preloadedCoordinates.c2me$getYArray();
            z = preloadedCoordinates.c2me$getZArray();
            allocatedOnDemand = false;
        } else {
            x = cache.getIntArray(densities.length, false);
            y = cache.getIntArray(densities.length, false);
            z = cache.getIntArray(densities.length, false);
            if (applier instanceof ICoordinatesFilling coordinatesFilling) {
                coordinatesFilling.c2me$fillCoordinates(x, y, z);
            } else {
                for (int i = 0; i < densities.length; i ++) {
                    FunctionContext pos = applier.forIndex(i);
                    x[i] = pos.blockX();
                    y[i] = pos.blockY();
                    z[i] = pos.blockZ();
                }
            }
            allocatedOnDemand = true;
        }
        try {
            this.multiMethod.evalMulti(densities, x, y, z, EvalType.from(applier), cache);
        } finally {
            if (allocatedOnDemand) {
                cache.recycle(x);
                cache.recycle(y);
                cache.recycle(z);
            }
        }
    }

    // DivineMC start - 26.2: mapChildren is the abstract method, mapAll became a default
    @Override
    public DensityFunction mapChildren(Visitor visitor) {
        if (this.getClass() != SubCompiledDensityFunction.class) {
            throw new AbstractMethodError();
        }
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return visitor.apply(fallback1);
        }
        boolean modified = false;
        Supplier<DensityFunction> fallback = this.blendingFallback != null ? Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? visitor.apply(densityFunction) : null;
        }) : null;
        if (fallback != this.blendingFallback) {
            modified = true;
        }
        if (modified) {
            return new SubCompiledDensityFunction(this.singleMethod, this.multiMethod, fallback);
        } else {
            return this;
        }
    }
    // DivineMC end - 26.2: mapChildren is the abstract method, mapAll became a default

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // DivineMC - 26.1.2 has no applyInternal/apply split; this is upstream's apply()
        if (this.getClass() != SubCompiledDensityFunction.class) {
            throw new AbstractMethodError();
        }
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return fallback1.mapAll(visitor);
        }
        boolean modified = false;
        Supplier<DensityFunction> fallback = this.blendingFallback != null ? Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? densityFunction.mapAll(visitor) : null;
        }) : null;
        if (fallback != this.blendingFallback) {
            modified = true;
        }
        if (modified) {
            return new SubCompiledDensityFunction(this.singleMethod, this.multiMethod, fallback);
        } else {
            return this;
        }
    }

    @Override
    public double minValue() {
//        DensityFunction fallback = this.getFallback();
//        return fallback != null ? fallback.minValue() : Double.MIN_VALUE;
        return Double.MIN_VALUE;
    }

    @Override
    public double maxValue() {
//        DensityFunction fallback = this.getFallback();
//        return fallback != null ? fallback.maxValue() : Double.MAX_VALUE;
        return Double.MAX_VALUE;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException();
    }

    protected DensityFunction getFallback() {
        return this.blendingFallback != null ? this.blendingFallback.get() : null;
    }
}
