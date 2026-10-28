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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava springs in the upper caves: a lava spring in the overworld only places where it opens onto the surface,
 * never in a cave above the old floor. Water springs and the Nether's lava springs are untouched.
 */
@Mixin(SpringFeature.class)
public abstract class SpringFeatureMixin {
    /** A spring this far under the surface counts as being in a cave. */
    private static final int CAVE_DEPTH = 6;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void er$noLavaInUpperCaves(FeaturePlaceContext<SpringConfiguration> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (!ctx.config().state.is(FluidTags.LAVA) || !DeepWorld.installed()) return;
        WorldGenLevel level = ctx.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return;
        BlockPos p = ctx.origin();
        if (p.getY() < DeepWorld.packFloor()) return;
        if (p.getY() < level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, p.getX(), p.getZ()) - CAVE_DEPTH) cir.setReturnValue(false);
    }
}
