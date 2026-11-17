package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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
 * Blue Reaches' network planned a city there: a CITY chamber at an eligible site, with its reserve kept clear of every
 * other chamber and tunnel (network-final.md, section 11.1).
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "isValidBiome", at = @At("HEAD"), cancellable = true)
    private static void er$deepStructures(Structure.GenerationStub stub, Structure.GenerationContext ctx, CallbackInfoReturnable<Boolean> cir) {
        if (!DeepWorld.installed() || stub.position().getY() >= DeepWorld.packFloor()
                || !(ctx.chunkGenerator() instanceof NoiseBasedChunkGenerator g) || !DeepWorld.isDeepGenerator(g)) {
            // without the deep world the cities' structure set (a start every 6 chunks) keeps vanilla's own starts only
            if (er$isCity(stub, ctx) && !Network.vanillaCityChunk(ctx.seed(), ctx.chunkPos().x, ctx.chunkPos().z)) cir.setReturnValue(false);
            return;
        }
        Terrain t = DeepWorld.terrain();
        Holder<Biome> deepDark = DeepBiomes.holder(DeepBiome.DEEP_DARK), rootvaults = DeepBiomes.rootvaults();
        BlockPos p = stub.position();
        boolean ok = t != null && deepDark != null && ctx.validBiome().test(deepDark)
                && (rootvaults == null || !ctx.validBiome().test(rootvaults))
                && t.biomeAt(p.getX(), p.getY(), p.getZ()) == DeepBiome.DEEP_DARK
                && t.citySite(ctx.chunkPos().x, ctx.chunkPos().z, p.getX(), p.getY(), p.getZ());
        cir.setReturnValue(ok);
    }

    /**
     * True for a structure made for the game's own deep dark alone, as the ancient city is: it accepts the deep dark
     * but neither the plains nor the dripstone caves (mineshafts and strongholds take every overworld biome).
     */
    private static boolean er$isCity(Structure.GenerationStub stub, Structure.GenerationContext ctx) {
        var biomes = ctx.registryAccess().registryOrThrow(Registries.BIOME);
        return er$accepts(ctx, biomes, Biomes.DEEP_DARK) && !er$accepts(ctx, biomes, Biomes.PLAINS) && !er$accepts(ctx, biomes, Biomes.DRIPSTONE_CAVES);
    }

    private static boolean er$accepts(Structure.GenerationContext ctx, net.minecraft.core.Registry<Biome> biomes, net.minecraft.resources.ResourceKey<Biome> key) {
        return biomes.getHolder(key).map(h -> ctx.validBiome().test(h)).orElse(false);
    }
}
