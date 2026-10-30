package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava in the Upper Caves: a lava spring in a cave above the old floor runs with water instead. Lava springs that
 * open onto the surface, the Nether's, and water springs are untouched.
 */
@Mixin(SpringFeature.class)
public abstract class SpringFeatureMixin {
    /** A spring this far under the surface counts as being in a cave. */
    private static final int CAVE_DEPTH = 6;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void er$waterInUpperCaves(FeaturePlaceContext<SpringConfiguration> ctx, CallbackInfoReturnable<Boolean> cir) {
        SpringConfiguration c = ctx.config();
        if (!c.state.is(FluidTags.LAVA) || !DeepWorld.installed()) return;
        WorldGenLevel level = ctx.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return;
        BlockPos p = ctx.origin();
        if (p.getY() < DeepWorld.packFloor()) return;
        if (p.getY() >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, p.getX(), p.getZ()) - CAVE_DEPTH) return;
        SpringConfiguration water = new SpringConfiguration(Fluids.WATER.defaultFluidState(), c.requiresBlockBelow, c.rockCount, c.holeCount, c.validBlocks);
        cir.setReturnValue(((SpringFeature) (Object) this).place(
                new FeaturePlaceContext<>(ctx.topFeature(), level, ctx.chunkGenerator(), ctx.random(), p, water)));
    }
}
