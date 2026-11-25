package com.explorationremix.block;

import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The hollow bell (0.21.9): what is left of a Hollow Choir when it is beaten, and the only one of these there is per
 * Choir. Set it down and it hangs in its frame, lit from inside, and tolls once, very softly, when someone comes near
 * it, so a base keeps a small memory of the fight.
 */
public class ChoirBellBlock extends HorizontalDirectionalBlock {
    /** How near someone must be for the bell to toll by itself (blocks). */
    static final double HEARD = 4;
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 15, 14);
    /** The game time from which this world's next idle toll may start, so a wall of bells is still one bell. */
    private static long next;

    public ChoirBellBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
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
        if (random.nextInt(4) == 0) {
            int c = ChoirLook.GLOW;
            level.addParticle(ERParticles.SPORE.get(), pos.getX() + 0.4 + random.nextDouble() * 0.2,
                    pos.getY() + 0.5, pos.getZ() + 0.4 + random.nextDouble() * 0.2,
                    ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
        }
        if (random.nextInt(20) != 0) return;
        long now = level.getGameTime();
        if (next - now > 400) next = now;
        if (now < next) return;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        boolean near = false;
        for (Player p : level.players()) if (p.distanceToSqr(x, y, z) < HEARD * HEARD) near = true;
        if (!near) return;
        next = now + 220 + random.nextInt(200);
        level.playLocalSound(x, y, z, ERSounds.CHOIR_TOLL_FAR.get(), SoundSource.BLOCKS, 0.35F,
                1.05F + 0.1F * random.nextFloat(), false);
    }
}
