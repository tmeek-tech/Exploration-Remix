package com.explorationremix.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Turns a slime the way it hops: its move control is a private class of {@code Slime}, so a slime of a group can only be
 * turned towards its leader through this.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Slime$SlimeMoveControl")
public interface SlimeMoveControlAccessor {
    @Invoker("setDirection") void er$setDirection(float yRot, boolean aggressive);
}
