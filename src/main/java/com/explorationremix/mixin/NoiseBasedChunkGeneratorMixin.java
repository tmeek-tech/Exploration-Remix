package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomeResolver;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes biome placement below the pack's floor through the deep terrain, and the Upper Caves' own biomes above it; keeps
 * whole-column scans for structure placement above the pack's floor.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Redirect(method = "doCreateBiomes",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
    private void er$fillBiomes(ChunkAccess chunk, BiomeResolver resolver, Climate.Sampler sampler) {
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        com.explorationremix.world.terrain.Terrain terrain = DeepWorld.terrain();
        if (terrain != null && DeepWorld.installed() && DeepWorld.isDeepGenerator(self)) {
            chunk.fillBiomesFromNoise(new DeepBiomeResolver(resolver, terrain), sampler);
        } else {
            chunk.fillBiomesFromNoise(resolver, sampler);
        }
    }

    /**
     * A base column (the ruined portals' corner scans, from /locate and from each start chunk's structure step) covers
     * only the rows from the pack's floor up; the rows below read as air. The deep world's rows would cost the Deep
     * Gardens' vent decisions and block tiles round the column, seconds of work on the server thread or in the chunk
     * system's one structure queue, and no start may stand there anyway: below the floor {@link StructureMixin} lets
     * only the planned ancient cities stand, and they scan no columns. The rows kept are the same cells as before (the
     * floor is on a cell boundary), so every start that can stand stands where it did.
     */
    @ModifyArg(method = "getBaseColumn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;iterateNoiseColumn(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;IILorg/apache/commons/lang3/mutable/MutableObject;Ljava/util/function/Predicate;)Ljava/util/OptionalInt;"),
            index = 0)
    private LevelHeightAccessor er$columnAboveFloor(LevelHeightAccessor height) {
        if (!DeepWorld.installed() || !DeepWorld.isDeepGenerator(this)) return height;
        int floor = DeepWorld.packFloor();
        if (height.getMinBuildHeight() >= floor || height.getMaxBuildHeight() <= floor) return height;
        return LevelHeightAccessor.create(floor, height.getMaxBuildHeight() - floor);
    }
}
