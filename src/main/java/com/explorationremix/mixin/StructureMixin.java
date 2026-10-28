package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Below the pack's floor the only structures are the ancient cities, which moved down with the deep dark into the
 * Blue Reaches. A structure may start there only when it is made for the deep dark alone (it accepts the deep dark but
 * not the Rootvaults, so general overworld structures stay out), the deep world puts the deep dark at its start, and the
 * start is well clear of the layer's route and refuges.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "isValidBiome", at = @At("HEAD"), cancellable = true)
    private static void er$deepStructures(Structure.GenerationStub stub, Structure.GenerationContext ctx, CallbackInfoReturnable<Boolean> cir) {
        if (!DeepWorld.installed() || stub.position().getY() >= DeepWorld.packFloor()) return;
        if (!(ctx.chunkGenerator() instanceof NoiseBasedChunkGenerator g) || !DeepWorld.isDeepGenerator(g)) return;
        Terrain t = DeepWorld.terrain();
        Holder<Biome> deepDark = DeepBiomes.holder(DeepBiome.DEEP_DARK), rootvaults = DeepBiomes.rootvaults();
        BlockPos p = stub.position();
        boolean ok = t != null && deepDark != null && ctx.validBiome().test(deepDark)
                && (rootvaults == null || !ctx.validBiome().test(rootvaults))
                && t.biomeAt(p.getX(), p.getY(), p.getZ()) == DeepBiome.DEEP_DARK
                && t.clearOfRoute(p.getX(), p.getY(), p.getZ(), 90);
        cir.setReturnValue(ok);
    }
}
