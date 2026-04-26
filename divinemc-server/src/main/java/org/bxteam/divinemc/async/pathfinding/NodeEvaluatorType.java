package org.bxteam.divinemc.async.pathfinding;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

public enum NodeEvaluatorType {
    WALK,
    SWIM,
    AMPHIBIOUS,
    FLY;

    public static NodeEvaluatorType fromNodeEvaluator(NodeEvaluator nodeEvaluator) {
        return switch (nodeEvaluator) {
            case SwimNodeEvaluator swim -> SWIM;
            case AmphibiousNodeEvaluator amphibious -> AMPHIBIOUS;
            case FlyNodeEvaluator fly -> FLY;
            default -> WALK;
        };
    }
}
