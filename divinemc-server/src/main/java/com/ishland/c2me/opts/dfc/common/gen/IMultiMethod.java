package com.ishland.c2me.opts.dfc.common.gen;

import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.util.ArrayCache;

@FunctionalInterface
public interface IMultiMethod {
    void evalMulti(double[] var1, int[] var2, int[] var3, int[] var4, EvalType var5, ArrayCache var6);
}
