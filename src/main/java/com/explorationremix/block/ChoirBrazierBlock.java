package com.explorationremix.block;

import com.explorationremix.registry.ERParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A brazier of the Hollow Choir's arena (0.21.9): a shallow deepslate bowl holding a cold pale blue flame, the only
 * light in the bowl. Lit braziers are the arena at rest; the Choir puts them out one phase at a time as it is driven
 * down, so the fight ends by the light of the Choir itself ({@link com.explorationremix.world.feature.ChoirArena}).
 * Unlit it gives no light and shows the ash in the bowl.
 *
 * <p>Nothing about it is warm: the flame is light blue (Taran's standing look rule), it sets nothing alight and it
 * burns nobody who steps in it.
 */
public class ChoirBrazierBlock extends Block {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** The light a lit brazier gives: enough to read the bowl by, dim enough that going out is felt. */
    public static final int LIGHT = 12;

    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 7, 13);

    public ChoirBrazierBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) return;
        if (random.nextInt(3) != 0) return;
        double x = pos.getX() + 0.35 + random.nextDouble() * 0.3;
        double z = pos.getZ() + 0.35 + random.nextDouble() * 0.3;
        // the mod's drifting mote, which carries its colour in the three speeds: the Choir's pale blue
        int c = random.nextInt(4) == 0 ? ChoirLook.BONE : ChoirLook.GLOW;
        level.addParticle(ERParticles.SPORE.get(), x, pos.getY() + 0.45, z,
                ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
    }
}
