package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava in the Upper Caves: a lava lake whose middle lies in a cave above the old floor (4 or more blocks under the
 * ground) fills with water instead. The pack's own overworld already leaves out vanilla's underground lava lakes; this
 * catches any other mod's. Lakes on the surface and in the Nether are untouched.
 */
@Mixin(LakeFeature.class)
public abstract class LakeFeatureMixin {
    private static final int CAVE_DEPTH = 4;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void er$waterInUpperCaves(FeaturePlaceContext<LakeFeature.Configuration> ctx, CallbackInfoReturnable<Boolean> cir) {
        LakeFeature.Configuration c = ctx.config();
        BlockPos p = ctx.origin();
        // Only plain lava: asking any other provider for its block would use up the feature's randomness.
        if (!(c.fluid() instanceof SimpleStateProvider) || !c.fluid().getState(ctx.random(), p).is(Blocks.LAVA) || !DeepWorld.installed()) return;
        WorldGenLevel level = ctx.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return;
        if (p.getY() < DeepWorld.packFloor()) return;
        if (p.getY() >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, p.getX(), p.getZ()) - CAVE_DEPTH) return;
        LakeFeature.Configuration water = new LakeFeature.Configuration(BlockStateProvider.simple(Blocks.WATER), c.barrier());
        cir.setReturnValue(((LakeFeature) (Object) this).place(
                new FeaturePlaceContext<>(ctx.topFeature(), level, ctx.chunkGenerator(), ctx.random(), p, water)));
    }
}
