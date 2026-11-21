package org.bxteam.divinemc.util;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * MC-172801: vanilla accelerates every flying mob in the air by a constant, so changing its {@code flying_speed}
 * attribute does nothing there. The constant is scaled by how far the attribute is from the entity type's default,
 * which keeps unchanged mobs exactly vanilla.
 */
@NullMarked
public final class FlyingSpeed {
    private FlyingSpeed() {
    }

    @SuppressWarnings("unchecked")
    public static float airAcceleration(final LivingEntity entity, final float vanillaSpeed) {
        final @Nullable AttributeInstance instance = entity.getAttribute(Attributes.FLYING_SPEED);
        if (instance == null) {
            return vanillaSpeed;
        }

        final @Nullable AttributeSupplier defaults = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) entity.getType());
        if (defaults == null || !defaults.hasAttribute(Attributes.FLYING_SPEED)) {
            return vanillaSpeed;
        }

        final double defaultValue = defaults.getBaseValue(Attributes.FLYING_SPEED);
        if (defaultValue <= 0.0) {
            return vanillaSpeed;
        }

        return (float) (vanillaSpeed * (instance.getValue() / defaultValue));
    }
}
