package com.explorationremix.mixin;

import com.explorationremix.world.feature.RuneStoneStructures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once a structure's pieces are built in a chunk, a stronghold's or an ancient city's rune stones go into that chunk's
 * share of its walls (0.23.1, {@link RuneStoneStructures}).
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Inject(method = "placeInChunk", at = @At("TAIL"))
    private void er$runeStones(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box,
                               ChunkPos chunkPos, CallbackInfo ci) {
        RuneStoneStructures.placed((StructureStart) (Object) this, level, box, chunkPos);
    }
}
