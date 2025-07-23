package com.ishland.c2me.opts.dfc.common;

import net.minecraft.world.level.levelgen.NoiseRouterData;

public interface IDensityFunctionsCaveScaler {
    static double invokeScaleCaves(double value) {
        return NoiseRouterData.QuantizedSpaghettiRarity.getSphaghettiRarity2D(value);
    }

    static double invokeScaleTunnels(double value) {
        return NoiseRouterData.QuantizedSpaghettiRarity.getSpaghettiRarity3D(value);
    }
}
