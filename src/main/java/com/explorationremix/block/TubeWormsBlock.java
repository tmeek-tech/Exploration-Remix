package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A clump of tube worms round a live vent of the Deep Gardens (0.20.2): pale ringed tubes with feathery plumes in
 * the vent's neon ({@link NeonBlock#NEON}). Taran's card pick, "Yes, they pull in as you near": a player who comes
 * within {@link #REACH} blocks ({@link #REACH_SNEAK} sneaking, so one can creep up to watch) makes the worms snap into
 * their tubes ({@link #OUT} false; {@link TubeWormWatch} looks round each player); once nobody is that near they come
 * out again a few seconds later, each clump in its own time. Like the other plants it gives no light: only the plumes'
 * glow shines.
 *
 * <p>0.20.6 (Taran, 2026-11-23: "Tube worms should play a pulling in animation and send a propagating signal to nearby
 * tube worms to pull in. They should also be in clusters and mostly on the lower half of vents. Also give them an
 * animation so that they wiggle when out."): a startled clump sends the scare on to the clumps round it
 * ({@link TubeWormSignals}), and the plumes move: they snap down into their tubes, peek out and unfurl again, and wiggle
 * while out (client.TubeWormsClient draws them near the player; far off the chunk's model shows them still). The
 * worms grow in clumps ({@link #SPARSE} the thin edge of one, two to four tubes; else its thick middle, eight or nine),
 * standing on floors and ledges, or on a vent's steep wall as {@link WallTubeWormsBlock}.
 */
public class TubeWormsBlock extends BushBlock implements EntityBlock {
    public static final BooleanProperty OUT = BooleanProperty.create("out");
    /** A clump's thin edge (two to four tubes) rather than its thick middle (0.20.6). */
    public static final BooleanProperty SPARSE = BooleanProperty.create("sparse");
    /** A player this near makes them pull in (blocks, to the clump's middle); sneaking, only this near. */
    public static final double REACH = 4.5, REACH_SNEAK = 2.0;
    /** They stay in for this long at least, then look again for players (ticks). */
    static final int IN_MIN = 50, IN_SPREAD = 70;
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 12, 14);
    /**
     * The game's own thread on a client (set by client.ClientSetup): only the client's worlds keep the worms' block
     * entities, which hold nothing but how their plumes move. None on a server, so nothing is saved for them.
     */
    public static volatile Thread clientThread;

    public TubeWormsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NeonBlock.NEON, Neon.MAGENTA).setValue(OUT, true).setValue(SPARSE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NeonBlock.NEON, OUT, SPARSE); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Vec3 off = state.getOffset(level, pos);
        return SHAPE.move(off.x, off.y, off.z);
    }

    @Override
    protected boolean mayPlaceOn(BlockState below, BlockGetter level, BlockPos pos) {
        return below.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return mayPlaceOn(level.getBlockState(below), level, below);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) { return NeonBlock.stack(this, state.getValue(NeonBlock.NEON)); }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return Thread.currentThread() == clientThread ? new TubeWormsBlockEntity(pos, state) : null;
    }

    /** Whether a player is near enough to the clump at {@code pos} to keep its worms in. */
    static boolean watched(Level level, BlockPos pos) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.4, z = pos.getZ() + 0.5;
        for (Player p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double r = (p.isShiftKeyDown() ? REACH_SNEAK : REACH) + 1.0;   // a little further than pulls them in
            if (p.distanceToSqr(x, y, z) < r * r) return true;
        }
        return false;
    }

    /** The worms at {@code pos} pull in, and look again for players after a while ({@link #onPlace}). */
    public static void pullIn(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(OUT, false), 2);
    }

    /** Worms set down pulled in (by pullIn, or a command) look again for players after a while, so none stay in for good. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!state.getValue(OUT) && !level.getBlockTicks().hasScheduledTick(pos, this))
            level.scheduleTick(pos, this, IN_MIN + level.random.nextInt(IN_SPREAD));
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(OUT)) return;
        if (watched(level, pos)) {
            level.scheduleTick(pos, this, 20 + random.nextInt(30));
            return;
        }
        level.setBlock(pos, state.setValue(OUT, true), 2);
        if (random.nextInt(3) == 0)
            level.playSound(null, pos, SoundEvents.BIG_DRIPLEAF_TILT_UP, SoundSource.BLOCKS, 0.12F, 1.5F + random.nextFloat() * 0.3F);
    }
}
