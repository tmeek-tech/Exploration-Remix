package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.BlockPathTypes;

import javax.annotation.Nullable;

/**
 * Pointed black dripstone, the Deep Gardens' vent field's: vanilla's pointed dripstone in black dripstone (Taran, round
 * 3: "the same as pointed dripstone but recolored"), drawn on vanilla's own models (tools/gen_data.py, kind pointed).
 * It stacks and thickens like vanilla's, hangs from a sturdy underside or stands on a sturdy top, falls when the rock
 * it hangs from goes (the whole stalactite, its tip hurting what it lands on), hurts what falls on a stalagmite's tip,
 * and breaks under a fast trident. Unlike vanilla's it never grows and never drips: the vent field is dry, so it has
 * no random ticks and shows no drips.
 * <p>
 * Vanilla's {@link PointedDripstoneBlock} finds the dripstone round it with private static helpers that test for its
 * own block ({@code Blocks.POINTED_DRIPSTONE}), so a subclass that kept them would never thicken, stack or fall as one
 * piece. As Immersive Weathering's icicles do, the helpers those behaviours need are copied here testing for this
 * block, and the methods calling them are overridden: placement, survival, the shape updates and the falling tick. The
 * others need no copy: the shapes, the falling and stalagmite damage, the landing sound, the trident and waterlogging
 * read only the state's properties. Vanilla tests its dripstone block only for growth, which this block never does.
 */
public class PointedBlackDripstoneBlock extends PointedDripstoneBlock {
    public PointedBlackDripstoneBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return isValidPlacement(level, pos, state.getValue(TIP_DIRECTION));
    }

    /** Vanilla's: an unsupported stalactite falls two ticks on, a stalagmite breaks one tick on, and the dripstone above
     *  and below thickens or thins this one. */
    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (dir != Direction.UP && dir != Direction.DOWN) return state;
        Direction tip = state.getValue(TIP_DIRECTION);
        if (tip == Direction.DOWN && level.getBlockTicks().hasScheduledTick(pos, this)) return state;
        if (dir == tip.getOpposite() && !canSurvive(state, level, pos)) {
            level.scheduleTick(pos, this, tip == Direction.DOWN ? 2 : 1);
            return state;
        }
        boolean merged = state.getValue(THICKNESS) == DripstoneThickness.TIP_MERGE;
        return state.setValue(THICKNESS, thickness(level, pos, tip, merged));
    }

    /** Vanilla's: it points away from the face it was put against if it can hang or stand there, else the other way;
     *  sneaking keeps a tip from merging with the tip it meets. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelAccessor level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction tip = tipDirection(level, pos, context.getNearestLookingVerticalDirection().getOpposite());
        if (tip == null) return null;
        return defaultBlockState().setValue(TIP_DIRECTION, tip)
                .setValue(THICKNESS, thickness(level, pos, tip, !context.isSecondaryUseActive()))
                .setValue(WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
    }

    /** Vanilla's: an unsupported stalagmite breaks, a stalactite falls. */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (pointsWith(state, Direction.UP) && !canSurvive(state, level, pos)) {
            level.destroyBlock(pos, true);
        } else {
            dropStalactite(state, level, pos);
        }
    }

    /** Never grows: the vent field is dry (and the block has no random ticks). */
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {}

    /** Never drips: the vent field is dry. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {}

    /** Mobs walk round it as round vanilla's, which the walk evaluator knows by name: they keep off it... */
    @Nullable
    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        return BlockPathTypes.DAMAGE_CAUTIOUS;
    }

    /** ...and from beside it, unless it is waterlogged: the evaluator tests for water before it tests for dripstone,
     *  so beside a waterlogged one is beside water. */
    @Nullable
    @Override
    public BlockPathTypes getAdjacentBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob, BlockPathTypes originalType) {
        return state.getValue(WATERLOGGED) ? null : BlockPathTypes.DAMAGE_CAUTIOUS;
    }

    // ------------------------------------------------------------------------------------------- vanilla's helpers
    // PointedDripstoneBlock's private statics of the same use, testing for this block instead of vanilla's.

    /** Every block of the stalactite from here down falls, each as itself; the tip hurts what it lands on, more the
     *  longer the stalactite (vanilla's 1 a block, at least 6, at most 40). Vanilla's spawnFallingStalactite. */
    private void dropStalactite(BlockState state, ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        for (BlockState s = state; pointsWith(s, Direction.DOWN); s = level.getBlockState(at)) {
            FallingBlockEntity falling = FallingBlockEntity.fall(level, at, s);
            if (isTip(s, true)) {
                int length = Math.max(1 + pos.getY() - at.getY(), 6);
                falling.setHurtsEntities(1.0F * length, 40);
                break;
            }
            at.move(Direction.DOWN);
        }
    }

    /** It can point this way here: the block it would hang from or stand on is sturdy there, or is this dripstone
     *  pointing the same way. Vanilla's isValidPointedDripstonePlacement. */
    private boolean isValidPlacement(LevelReader level, BlockPos pos, Direction tip) {
        BlockPos rootPos = pos.relative(tip.getOpposite());
        BlockState root = level.getBlockState(rootPos);
        return root.isFaceSturdy(level, rootPos, tip) || pointsWith(root, tip);
    }

    /** The way it points when put here, preferring {@code wanted}, or null if it can neither hang nor stand here.
     *  Vanilla's calculateTipDirection. */
    @Nullable
    private Direction tipDirection(LevelReader level, BlockPos pos, Direction wanted) {
        if (isValidPlacement(level, pos, wanted)) return wanted;
        if (isValidPlacement(level, pos, wanted.getOpposite())) return wanted.getOpposite();
        return null;
    }

    /** Its thickness from the dripstone beyond its tip and behind it: a tip meeting another tip merges with it (or
     *  stays a tip if {@code merge} is false and the other has not merged), a tip with nothing beyond it is a tip, then
     *  frustum, middle and base going back up a stalactite (or down a stalagmite). Vanilla's
     *  calculateDripstoneThickness. */
    private DripstoneThickness thickness(LevelReader level, BlockPos pos, Direction tip, boolean merge) {
        Direction back = tip.getOpposite();
        BlockState beyond = level.getBlockState(pos.relative(tip));
        if (pointsWith(beyond, back)) {
            return !merge && beyond.getValue(THICKNESS) != DripstoneThickness.TIP_MERGE ? DripstoneThickness.TIP : DripstoneThickness.TIP_MERGE;
        }
        if (!pointsWith(beyond, tip)) return DripstoneThickness.TIP;
        DripstoneThickness next = beyond.getValue(THICKNESS);
        if (next == DripstoneThickness.TIP || next == DripstoneThickness.TIP_MERGE) return DripstoneThickness.FRUSTUM;
        return pointsWith(level.getBlockState(pos.relative(back)), tip) ? DripstoneThickness.MIDDLE : DripstoneThickness.BASE;
    }

    /** A tip of this dripstone (merged tips too if {@code merged}). Vanilla's isTip. */
    private boolean isTip(BlockState state, boolean merged) {
        if (!state.is(this)) return false;
        DripstoneThickness thickness = state.getValue(THICKNESS);
        return thickness == DripstoneThickness.TIP || merged && thickness == DripstoneThickness.TIP_MERGE;
    }

    /** This dripstone, pointing this way. Vanilla's isPointedDripstoneWithDirection (and with DOWN its isStalactite,
     *  with UP its isStalagmite). */
    private boolean pointsWith(BlockState state, Direction tip) {
        return state.is(this) && state.getValue(TIP_DIRECTION) == tip;
    }
}
