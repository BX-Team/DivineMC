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
import com.ishland.c2me.opts.dfc.common.ducks.IBlendingAwareVisitor;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Objects;
import java.util.function.Supplier;

public class SubCompiledDensityFunction extends AbstractCompiledDensityFunction {

    protected final ISingleMethod singleMethod;
    protected final IMultiMethod multiMethod;

    // also called from generated code
    public SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, DensityFunction blendingFallback) {
        this(singleMethod, multiMethod, unwrapFallback(blendingFallback));
    }

    protected SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, Supplier<DensityFunction> blendingFallback) {
        super(blendingFallback);
        this.singleMethod = Objects.requireNonNull(singleMethod);
        this.multiMethod = Objects.requireNonNull(multiMethod);
    }

    @Override
    public ISingleMethod getSingleMethod() {
        return this.singleMethod;
    }

    @Override
    public IMultiMethod getMultiMethod() {
        return this.multiMethod;
    }

    @Override
    public DensityFunction mapChildren(Visitor visitor) {
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return visitor.apply(fallback1);
        }
        if (this.blendingFallback == null) {
            return this;
        }
        Supplier<DensityFunction> fallback = Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? visitor.apply(densityFunction) : null;
        });
        return new SubCompiledDensityFunction(this.singleMethod, this.multiMethod, fallback);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return fallback1.mapAll(visitor);
        }
        if (this.blendingFallback == null) {
            return this;
        }
        Supplier<DensityFunction> fallback = Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? densityFunction.mapAll(visitor) : null;
        });
        return new SubCompiledDensityFunction(this.singleMethod, this.multiMethod, fallback);
    }
}
