package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.explorationremix.world.gen.SurfaceOracle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The Upper Caves' plant caves and entrances take the place of the surface biome carried underground, in their caves
 * and in the rock round them; the surface biome's own ores (a mountain's emeralds, the badlands' gold) still go into
 * that rock, as they would without them. Only ore features (WWOO's sand disks, snow and mycelium spreads share the
 * ore step) and only in the deepened overworld: the surface's plants and trees stay out of the caves.
 */
@Mixin(BiomeFilter.class)
public abstract class BiomeFilterMixin {
    @Inject(method = "shouldPlace", at = @At("RETURN"), cancellable = true)
    private void er$surfaceOres(PlacementContext ctx, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !DeepWorld.installed() || ctx.getMinGenY() != DeepWorld.floorY() || pos.getY() < DeepWorld.packFloor()) return;
        SurfaceOracle oracle = DeepWorld.oracle();
        PlacedFeature f = ctx.topFeature().orElse(null);
        if (oracle == null || f == null || !(f.feature().value().config() instanceof OreConfiguration)) return;
        if (!DeepBiomes.upperCave(ctx.getLevel().getBiome(pos))) return;
        List<HolderSet<PlacedFeature>> steps = ctx.generator().getBiomeGenerationSettings(oracle.biome(pos.getX(), pos.getZ())).features();
        int ores = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        if (steps.size() <= ores) return;
        for (Holder<PlacedFeature> h : steps.get(ores)) {
            if (h.value() == f) {
                DeepBiomes.SURFACE_ORES.incrementAndGet();
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
