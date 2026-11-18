package com.explorationremix.block;

import com.explorationremix.rune.world.FrondBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A silver fern hanging down the side of a block, in three sizes (Taran, 17 Nov: "three different sizes, with the
 * smallest size being the size that there is now"). Each hangs from {@link #TOP} pixels up its block, as wide as
 * {@link #WIDTH} and as long as {@link #LENGTH}, centred on its block: the small one a block wide and a block and a half
 * long, the medium a block and a half by two, the large two blocks by nearly three. Its outline stays in its own block.
 * Placed by hand it is the small one; world generation picks the size and {@link #fit} shrinks it to the room it has.
 *
 * <p>Struck by a rune it unrolls (0.18.7, RunePlants): its fronds run down the rock below it to the floor as a curtain
 * you can climb, and while they are down ({@code unrolled}) the fern itself is climbed too, so the curtain has no gap.
 * RunePlants rolls it up again; if that is lost (the server stopped), a tick it set rolls it up on its own.
 */
public class HangingFernBlock extends ShelfFungusBlock {
    public static final IntegerProperty SIZE = IntegerProperty.create("size", 0, 2);
    public static final BooleanProperty UNROLLED = BooleanProperty.create("unrolled");
    /** The latest a fern stays unrolled, if nothing rolls it up first (ticks). */
    public static final int UNROLLED_AT_MOST = 1200;
    /** In pixels, by size: the fronds' width, centred on the block, and their length down from TOP. */
    public static final int[] WIDTH = {16, 24, 32}, LENGTH = {24, 34, 46};
    public static final int TOP = 14;

    public HangingFernBlock(Properties properties, VoxelShape north) {
        super(properties, north);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIZE, UNROLLED);
    }

    /** Unrolled, it is part of its curtain of fronds, and climbed like it. */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return state.getValue(UNROLLED); }

    @Override
    public void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && state.getValue(UNROLLED) && !(old.is(this) && old.getValue(UNROLLED))) level.scheduleTick(pos, this, UNROLLED_AT_MOST);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(UNROLLED)) return;
        // struck again since, its first timer still set: it waits while its fronds still hang from it
        if (level.getBlockState(pos.below()).getBlock() instanceof FrondBlock || level.getBlockState(pos.above()).getBlock() instanceof FrondBlock) {
            level.scheduleTick(pos, this, 100);
            return;
        }
        level.setBlock(pos, state.setValue(UNROLLED, false), Block.UPDATE_ALL);
    }

    /**
     * The state at its size, or the biggest smaller one, that fits at {@code pos}: open air under it as far as its
     * fronds reach, and no other hanging fern on the same face of rock whose fronds would lie over its own (the planes
     * would flicker through each other). Null if not even the small one fits.
     */
    public static BlockState fit(LevelReader level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction along = facing.getClockWise();
        for (int size = state.getValue(SIZE); size >= 0; size--) {
            if (hasRoom(level, pos, facing, along, size)) return state.setValue(SIZE, size);
        }
        return null;
    }

    private static boolean hasRoom(LevelReader level, BlockPos pos, Direction facing, Direction along, int size) {
        int below = (TOP - LENGTH[size]) >> 4;  // the lowest block row the fronds reach into: -1, or -2
        for (int dy = -1; dy >= below; dy--) {
            if (!level.getBlockState(pos.above(dy)).isAir()) return false;
        }
        // ferns side by side or above and below on the same rock: their planes' rectangles (pixels from this block's
        // corner) must not overlap
        double x0 = 8 - WIDTH[size] / 2.0, x1 = 8 + WIDTH[size] / 2.0, y0 = TOP - LENGTH[size], y1 = TOP;
        for (int a = -1; a <= 1; a++) {
            for (int b = -2; b <= 2; b++) {
                if (a == 0 && b == 0) continue;
                BlockState other = level.getBlockState(pos.relative(along, a).above(b));
                if (!(other.getBlock() instanceof HangingFernBlock) || other.getValue(FACING) != facing) continue;
                int s = other.getValue(SIZE);
                double ox0 = a * 16 + 8 - WIDTH[s] / 2.0, ox1 = a * 16 + 8 + WIDTH[s] / 2.0;
                double oy0 = b * 16 + TOP - LENGTH[s], oy1 = b * 16 + TOP;
                if (ox0 < x1 && x0 < ox1 && oy0 < y1 && y0 < oy1) return false;
            }
        }
        return true;
    }
}
