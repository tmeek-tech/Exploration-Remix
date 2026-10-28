package com.explorationremix.world.feature;

import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.entity.FlockerEntity;
import com.explorationremix.registry.EREntities;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Hangs a flock of cave flockers under the ceiling at the placed position. */
public class FlockRoostFeature extends Feature<NoneFeatureConfiguration> {
    public FlockRoostFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        if (random.nextInt(100) >= ERConfig.INSTANCE.flocksPerChunk.get()) return false;
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        int size = ERConfig.INSTANCE.flockSize.get();
        int placed = 0;
        for (int i = 0; i < size * 3 && placed < size; i++) {
            int x = origin.getX() + random.nextInt(7) - 3, z = origin.getZ() + random.nextInt(7) - 3;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, origin.getY() - 2, z);
            BlockPos roost = null;
            for (int dy = 0; dy < 8; dy++) {
                if (!level.getBlockState(pos).isAir()) { pos.move(Direction.UP); continue; }
                BlockPos above = pos.above();
                if (level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)) { roost = pos.immutable(); break; }
                pos.move(Direction.UP);
            }
            if (roost == null) continue;
            FlockerEntity f = EREntities.FLOCKER.get().create(level.getLevel());
            if (f == null) continue;
            f.moveTo(roost.getX() + 0.5, roost.getY() + 1.0 - f.getBbHeight() - 0.05, roost.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            f.setPersistenceRequired();
            level.addFreshEntity(f);
            placed++;
        }
        return placed > 0;
    }
}
