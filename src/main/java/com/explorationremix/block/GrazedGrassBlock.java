package com.explorationremix.block;

import com.explorationremix.registry.ERBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Filament grass a stilt mantis has cropped (0.20.2, Taran's card pick: "Crops it short, it regrows"): stubble whose
 * cut ends have lost their glow, a few new shoots coming back. It remembers what it was ({@link #REGROW}: the tuft,
 * the grass or the tall grass) and grows back into it over several minutes (one random tick in {@link #ROLL} grows it,
 * so about three and a half minutes on average at vanilla's tick speed), or at once with bone meal. Tall grass comes back tall only with
 * room over it. No item and no drop: breaking it clears the ground.
 */
public class GrazedGrassBlock extends NeonPlantBlock implements BonemealableBlock {
    public enum Regrow implements StringRepresentable {
        TUFT("tuft"), GRASS("grass"), TALL("tall");

        private final String name;

        Regrow(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }
    }

    public static final EnumProperty<Regrow> REGROW = EnumProperty.create("regrow", Regrow.class);
    /** One random tick in this grows it back. */
    public static final int ROLL = 3;

    public GrazedGrassBlock(Properties properties, VoxelShape shape) {
        super(properties, shape, 0);
        registerDefaultState(stateDefinition.any().setValue(NeonBlock.NEON, Neon.MAGENTA).setValue(REGROW, Regrow.GRASS));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(REGROW);
    }

    /** Pick block gives the grass it grows back into. */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        Block grown = switch (state.getValue(REGROW)) {
            case TUFT -> ERBlocks.FILAMENT_TUFT.get();
            case GRASS -> ERBlocks.FILAMENT_GRASS.get();
            case TALL -> ERBlocks.TALL_FILAMENT_GRASS.get();
        };
        return NeonBlock.stack(grown, state.getValue(NeonBlock.NEON));
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) { return true; }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(ROLL) == 0) regrow(level, pos, state);
    }

    /** Whether a block is filament grass a stilt mantis can crop: a tuft, the grass or either half of tall grass. */
    public static boolean croppable(BlockState s) {
        return s.is(ERBlocks.FILAMENT_TUFT.get()) || s.is(ERBlocks.FILAMENT_GRASS.get()) || s.is(ERBlocks.TALL_FILAMENT_GRASS.get());
    }

    /**
     * A stilt mantis crops the filament grass at {@code pos} to stubble that remembers it (tall grass from either half:
     * its upper half goes without a drop): the break's puff and sound, as a sheep's bite. False if there was none.
     */
    public static boolean crop(Level level, BlockPos pos, BlockState state) {
        Regrow r;
        BlockPos at = pos;
        if (state.is(ERBlocks.FILAMENT_TUFT.get())) r = Regrow.TUFT;
        else if (state.is(ERBlocks.FILAMENT_GRASS.get())) r = Regrow.GRASS;
        else if (state.is(ERBlocks.TALL_FILAMENT_GRASS.get())) {
            r = Regrow.TALL;
            if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
                at = pos.below();
                state = level.getBlockState(at);
                if (!state.is(ERBlocks.TALL_FILAMENT_GRASS.get())) return false;
            }
            BlockPos up = at.above();
            // the upper half first, quietly (no drop, and no shape update that would break the lower half)
            if (level.getBlockState(up).is(ERBlocks.TALL_FILAMENT_GRASS.get()))
                level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        } else return false;
        level.levelEvent(2001, at, Block.getId(state));
        level.setBlock(at, ERBlocks.GRAZED_FILAMENT_GRASS.get().defaultBlockState().setValue(NeonBlock.NEON, state.getValue(NeonBlock.NEON))
                .setValue(REGROW, r), Block.UPDATE_ALL);
        return true;
    }

    /** It grows back into what it was (tall grass only with air over it; else the grass). */
    public static void regrow(Level level, BlockPos pos, BlockState state) {
        Neon neon = state.getValue(NeonBlock.NEON);
        switch (state.getValue(REGROW)) {
            case TUFT -> level.setBlock(pos, ERBlocks.FILAMENT_TUFT.get().defaultBlockState().setValue(NeonBlock.NEON, neon), 3);
            case GRASS -> level.setBlock(pos, ERBlocks.FILAMENT_GRASS.get().defaultBlockState().setValue(NeonBlock.NEON, neon), 3);
            case TALL -> {
                if (level.isEmptyBlock(pos.above()))
                    ((TallNeonPlantBlock) ERBlocks.TALL_FILAMENT_GRASS.get()).placeAt(level, pos, neon, 3);
                else level.setBlock(pos, ERBlocks.FILAMENT_GRASS.get().defaultBlockState().setValue(NeonBlock.NEON, neon), 3);
            }
        }
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, boolean client) { return true; }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) { return true; }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) { regrow(level, pos, state); }
}
