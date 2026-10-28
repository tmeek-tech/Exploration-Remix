package com.explorationremix.mixin.accessor;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NoiseGeneratorSettings.class)
public interface NoiseGeneratorSettingsAccessor {
    @Accessor("noiseSettings") @Mutable void er$setNoiseSettings(NoiseSettings settings);
    @Accessor("noiseRouter") @Mutable void er$setNoiseRouter(NoiseRouter router);
    @Accessor("surfaceRule") @Mutable void er$setSurfaceRule(SurfaceRules.RuleSource rule);
}
