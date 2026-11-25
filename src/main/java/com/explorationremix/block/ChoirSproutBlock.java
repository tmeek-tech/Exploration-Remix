package com.explorationremix.block;

import com.explorationremix.registry.ERParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A rooted sprout of the Hollow Choir (0.21.9): a bone white stem with a pale blue bud, which rises out of the sculk
 * the Choir spreads over its arena. While a sprout stands the Choir mends itself and takes less of a blow
 * ({@code HollowChoirEntity}); every sprout falls to one hit, so the fight is to keep the floor clear, not to kill
 * another creature (Taran's pick: only rooted sprouts, no crawlers).
 *
 * <p>Sprouts are the Choir's, not the world's: they are never generated, they drop nothing, and they go with it.
 */
public class ChoirSproutBlock extends CavePlantBlock {
    public ChoirSproutBlock(Properties properties) {
        super(properties, Block.box(4, 0, 4, 12, 14, 12));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) != 0) return;
        int c = random.nextInt(3) == 0 ? ChoirLook.BONE : ChoirLook.GLOW;
        level.addParticle(ERParticles.SPORE.get(), pos.getX() + 0.35 + random.nextDouble() * 0.3,
                pos.getY() + 0.75, pos.getZ() + 0.35 + random.nextDouble() * 0.3,
                ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
    }
}
