package com.explorationremix.rune.stone;

import com.explorationremix.registry.ERParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;

/**
 * A rune stone (0.23.1): a block of its host's stone with a small rune carved into it, glowing faintly light blue, that
 * frees one or two of its layer's runes when broken (Taran, 2026-11-26: "Can you add a rune block for each layer that
 * drops 1-2 runes when broken? It should have a texture that blends into the blocks it's placed in."). Its state's
 * {@code host} is the stone it hides in; it sounds as that stone does, and pick-block in survival gives that stone, so
 * nothing but the carving and its faint light gives it away. Now and then a mote of light drifts off it while a player is
 * within 16 blocks (Taran's card pick).
 *
 * <p>It takes a beat longer to break than its host (hardness 5, a second with an iron pickaxe), so the carving's light has time to build while it is
 * dug, and any tool breaks it, so a rune is never lost to the wrong tool. A Gentle pickaxe (Silk Touch) breaks it all the
 * same: the stone never comes out whole. No tool frees more: a Bounty pickaxe did until 0.24.5, when Taran changed their
 * mind ("Can you make bounty not effect rune blocks?"). It gives 3 to 7 experience. What it frees is its loot table's;
 * how it goes is {@link RuneStones}'s.
 */
public class RuneStoneBlock extends Block {
    /** The kind whose property the block being made takes (createBlockStateDefinition runs inside Block's constructor). */
    private static final ThreadLocal<RuneStoneKind> MAKING = new ThreadLocal<>();

    public final RuneStoneKind kind;

    public RuneStoneBlock(RuneStoneKind kind, Properties props) {
        super(making(kind, props));
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(kind.host, kind.hosts.get(0)));
    }

    private static Properties making(RuneStoneKind kind, Properties props) {
        MAKING.set(kind);
        return props;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        RuneStoneKind kind = MAKING.get();
        MAKING.remove();
        builder.add(kind.host);
    }

    /** The stone it hides in. */
    public RuneStoneHost host(BlockState state) { return state.getValue(kind.host); }

    /** Placed by hand (creative), it takes the stone it is set against, if it can hide in that. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState against = ctx.getLevel().getBlockState(ctx.getClickedPos().relative(ctx.getClickedFace().getOpposite()));
        if (against.getBlock() instanceof RuneStoneBlock other && kind.takes(other.host(against))) return kind.state(other.host(against));
        RuneStoneHost h = RuneStoneHost.of(against);
        return kind.takes(h) ? kind.state(h) : defaultBlockState();
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        return host(state).block().getSoundType(host(state).state(), level, pos, entity);
    }

    /** Pick-block gives its host's stone, except in creative, where it gives the rune stone itself. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        if (player != null && !player.getAbilities().instabuild) return new ItemStack(host(state).block());
        return new ItemStack(this);
    }

    /** Any tool breaks it and frees its runes. */
    @Override
    public boolean canHarvestBlock(BlockState state, BlockGetter level, BlockPos pos, Player player) { return true; }

    @Override
    public int getExpDrop(BlockState state, LevelReader level, RandomSource random, BlockPos pos, int fortune, int silkTouch) {
        return 3 + random.nextInt(5);
    }

    /** As it goes, whoever breaks it: the burst, and the stones round it answer. */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        super.playerWillDestroy(level, pos, state, player);
        if (level instanceof ServerLevel sl) RuneStones.burst(sl, pos, state, player);
    }

    /** Broken by a player in survival: its runes rise and fly to them. */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity be, ItemStack tool) {
        player.awardStat(net.minecraft.stats.Stats.BLOCK_MINED.get(this));
        player.causeFoodExhaustion(0.005F);
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) RuneStones.free(sl, pos, state, sp, tool);
        else if (level instanceof ServerLevel sl) dropResources(state, sl, pos, be, player, tool, false);
    }

    /** Blown up: its runes fall out with the rest of the rubble, in its burst, and the stones round it answer. */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        super.onBlockExploded(state, level, pos, explosion);
        if (level instanceof ServerLevel sl) RuneStones.burst(sl, pos, state, null);
    }

    /** Now and then a mote of light drifts off a face open to the air, while a player is within 16 blocks. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
        if (r.nextInt(3) != 0) return;
        if (level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 16.0, false) == null) return;
        Direction d = Direction.getRandom(r);
        BlockPos out = pos.relative(d);
        if (level.getBlockState(out).isSolidRender(level, out)) return;
        double u = (r.nextDouble() - 0.5) * 0.7, w = (r.nextDouble() - 0.5) * 0.7;
        double x = pos.getX() + 0.5 + d.getStepX() * 0.56 + (d.getStepX() == 0 ? u : 0);
        double y = pos.getY() + 0.5 + d.getStepY() * 0.56 + (d.getStepY() == 0 ? (d.getStepX() == 0 ? w : u) : 0);
        double z = pos.getZ() + 0.5 + d.getStepZ() * 0.56 + (d.getStepZ() == 0 ? w : 0);
        // the carving's light blue (#8EC2FF), a little paler for a mote
        level.addParticle(ERParticles.SPORE.get(), x, y, z, 0.62, 0.80, 1.0);
    }
}
