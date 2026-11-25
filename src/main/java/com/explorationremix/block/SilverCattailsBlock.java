package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Silver cattails (0.21.7, the Blue Reaches polish; Taran's card picks "Silver cattails", "Two to three blocks, mixed"
 * and "Hidden while you're in them"): reeds by the rifts' water, two or three blocks tall, silver-blue blades with
 * cattail heads that glow light blue. A player standing in them is hidden from the Skeins and the giant Skeins unless
 * right next to one ({@link #hides}), and one hunting them loses them.
 *
 * <p>Each block is one {@link Part} of a plant, and every block of a plant is seeded from its foot ({@link #getSeed}),
 * so the game shows slices of one drawing (tools/plants_upper.py) that stand together. Any block of it may stand in
 * water (the foot in a pond's shallows, mostly). Breaking any block of it breaks the whole plant, which drops once,
 * from its foot (its loot table).
 */
public class SilverCattailsBlock extends Block implements SimpleWaterloggedBlock {
    /** The blocks of a plant two tall (short_bottom, short_top) and three tall (bottom, middle, top). */
    public enum Part implements StringRepresentable {
        SHORT_BOTTOM("short_bottom", 2, 0), SHORT_TOP("short_top", 2, 1), BOTTOM("bottom", 3, 0), MIDDLE("middle", 3, 1), TOP("top", 3, 2);

        private final String id;
        /** The plant's height and this block's place in it, from 0 at its foot. */
        public final int height, index;

        Part(String id, int height, int index) {
            this.id = id;
            this.height = height;
            this.index = index;
        }

        @Override
        public String getSerializedName() { return id; }

        public boolean foot() { return index == 0; }

        public boolean top() { return index == height - 1; }

        /** The block {@code index} up a plant {@code height} tall. */
        public static Part of(int height, int index) {
            for (Part p : values()) if (p.height == height && p.index == index) return p;
            throw new IllegalArgumentException("no cattail part " + index + " of " + height);
        }

        /** The part above this one (null at the top) and below it (null at the foot). */
        @Nullable public Part above() { return top() ? null : of(height, index + 1); }
        @Nullable public Part below() { return foot() ? null : of(height, index - 1); }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 16, 14), TOP_SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    public SilverCattailsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, Part.SHORT_BOTTOM).setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, WATERLOGGED);
    }

    // ------------------------------------------------------------------------------------------------ hiding

    /** Whether {@code e} stands in cattails: the block at its feet or at its eyes is one. */
    public static boolean inCattails(Entity e) {
        Level l = e.level();
        return l.getBlockState(e.blockPosition()).getBlock() instanceof SilverCattailsBlock
                || l.getBlockState(BlockPos.containing(e.getEyePosition())).getBlock() instanceof SilverCattailsBlock;
    }

    /**
     * Whether {@code e} is hidden from a hunter looking from {@code from}: in cattails, and further than {@code near}
     * blocks from it (right next to it they are found all the same: Taran's card).
     */
    public static boolean hides(Entity e, Vec3 from, double near) {
        return e.getBoundingBox().distanceToSqr(from) > near * near && inCattails(e);
    }

    // ------------------------------------------------------------------------------------------------ the plant

    /** Every block of a plant is seeded from its foot, so all of them show the same drawing. */
    @Override
    public long getSeed(BlockState state, BlockPos pos) {
        return Mth.getSeed(pos.getX(), pos.getY() - state.getValue(PART).index, pos.getZ());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Vec3 off = state.getOffset(level, pos);
        return (state.getValue(PART).top() ? TOP_SHAPE : SHAPE).move(off.x, off.y, off.z);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(WATERLOGGED);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return type == PathComputationType.AIR || type == PathComputationType.LAND ? true : super.isPathfindable(state, level, pos, type);
    }

    /** Its foot stands on any floor with a solid top (rock ledges, gravel, a pond's bed); every other block on the one below. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Part part = state.getValue(PART);
        if (!part.foot()) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(PART) == part.below();
        }
        BlockPos b = pos.below();
        return level.getBlockState(b).isFaceSturdy(level, b, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        Part part = state.getValue(PART);
        boolean whole = switch (dir) {
            case UP -> part.top() || (other.is(this) && other.getValue(PART) == part.above());
            case DOWN -> canSurvive(state, level, pos);
            default -> true;
        };
        return whole ? state : emptied(state);
    }

    /** What is left where a block of the plant goes: its water, or air. */
    private static BlockState emptied(BlockState state) {
        return state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    /**
     * Placed by hand, a plant three tall where there is room and the spot says so (half the spots), two tall where
     * there is room for that, and none where there is not.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        if (!fits(level, pos.above(), ctx)) return null;
        int height = fits(level, pos.above(2), ctx) && (Mth.getSeed(pos) & 16L) != 0 ? 3 : 2;
        return state(height, 0, level.getFluidState(pos).getType() == Fluids.WATER);
    }

    private static boolean fits(Level level, BlockPos p, BlockPlaceContext ctx) {
        return p.getY() < level.getMaxBuildHeight() && level.getBlockState(p).canBeReplaced(ctx);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        int height = state.getValue(PART).height;
        for (int i = 1; i < height; i++) {
            BlockPos p = pos.above(i);
            level.setBlock(p, state(height, i, level.getFluidState(p).getType() == Fluids.WATER), 3);
        }
    }

    /** The block {@code index} up a plant {@code height} tall, standing in water or not. */
    public BlockState state(int height, int index, boolean wet) {
        return defaultBlockState().setValue(PART, Part.of(height, index)).setValue(WATERLOGGED, wet);
    }

    /**
     * Breaking any block above the foot breaks the foot, which drops the plant (once: only the foot's loot drops it),
     * and the rest of the plant goes with it. In creative it goes without a drop.
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        Part part = state.getValue(PART);
        if (!level.isClientSide && !part.foot()) {
            BlockPos foot = pos.below(part.index);
            BlockState f = level.getBlockState(foot);
            if (f.is(this) && f.getValue(PART).foot()) {
                if (player.isCreative()) {
                    level.setBlock(foot, emptied(f), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                    level.levelEvent(player, 2001, foot, Block.getId(f));
                } else {
                    level.destroyBlock(foot, true, player);
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
