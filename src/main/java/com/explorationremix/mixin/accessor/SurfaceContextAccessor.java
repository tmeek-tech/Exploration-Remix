package com.explorationremix.mixin.accessor;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SurfaceRules.Context.class)
public interface SurfaceContextAccessor {
    @Accessor("blockX") int er$blockX();
    @Accessor("blockY") int er$blockY();
    @Accessor("blockZ") int er$blockZ();
}
