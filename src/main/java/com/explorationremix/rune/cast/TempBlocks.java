package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The blocks runes make for a while (Barrier glass, Frost's ice, Glow's light), kept to a few per player: a new one
 * past the limit ends that player's oldest still standing first. Runes only ever fill air (and still water, for ice),
 * never round anything standing or hanging there, and only where their caster could build (not in adventure mode,
 * under spawn protection, or where a claim refuses them), so they never break or replace anything built or grown.
 * Each block undoes itself on a timer saved with its chunk (TempBlock).
 */
public final class TempBlocks {
    /** One block a player made. It counts against them while {@link #STANDING} holds this very one for its place. */
    private static final class Placed {
        final GlobalPos at;

        Placed(GlobalPos at) { this.at = at; }
    }

    private static final Map<UUID, Map<TempBlock.Kind, Deque<Placed>>> OWNED = new HashMap<>();
    /** The rune blocks standing now, by place; however one ends (its timer, mined, blown up, melted), it leaves. */
    private static final Map<GlobalPos, Placed> STANDING = new HashMap<>();

    private TempBlocks() {}

    private static int cap(TempBlock.Kind k) {
        return switch (k) {
            case BARRIER -> RuneConfig.get(RuneConfig.INSTANCE.maxBarrierBlocks);
            case ICE -> RuneConfig.get(RuneConfig.INSTANCE.maxIceBlocks);
            case LIGHT -> RuneConfig.get(RuneConfig.INSTANCE.maxGlowBlocks);
        };
    }

    private static Block block(TempBlock.Kind k) {
        return switch (k) {
            case BARRIER -> RuneRegistry.BARRIER.get();
            case ICE -> RuneRegistry.ICE.get();
            case LIGHT -> RuneRegistry.LIGHT.get();
        };
    }

    /** Whether a rune may make this kind of block here: air for glass and light, the top of still water for ice. */
    public static boolean fits(ServerLevel level, BlockPos pos, TempBlock.Kind k) {
        if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (k == TempBlock.Kind.ICE) {
            FluidState f = s.getFluidState();
            return s.is(Blocks.WATER) && f.isSource() && f.getType() == Fluids.WATER && level.getBlockState(pos.above()).isAir();
        }
        return s.isAir();
    }

    /**
     * Whether something is where a solid block would go that it would trap or knock down: a creature (a swimmer's head
     * in the top of the water, too), a boat or cart, an item frame or painting.
     */
    static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntities((Entity) null, new AABB(pos), e -> !e.isRemoved() && !e.isSpectator() && (e.blocksBuilding || e instanceof HangingEntity)).isEmpty();
    }

    /**
     * Whether a rune of {@code owner}'s may change the block at {@code pos}: they could build there (not in adventure
     * mode, not under spawn protection) and nothing guarding it (a claim) refuses, asked the way Frost Walker asks.
     */
    public static boolean mayChange(ServerLevel level, BlockPos pos, ServerPlayer owner) {
        if (!owner.mayBuild() || !level.mayInteract(owner, pos)) return false;
        return !ForgeEventFactory.onBlockPlace(owner, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP);
    }

    /**
     * Makes one block for {@code owner}, ending their oldest standing one of that kind past the limit. False if it
     * doesn't fit, something is in the way, or the owner may not change that block.
     */
    public static boolean place(ServerLevel level, BlockPos pos, TempBlock.Kind k, ServerPlayer owner) {
        int cap = cap(k);
        if (cap <= 0 || !fits(level, pos, k)) return false;
        // glass and ice are solid: never round anyone (they would be stuck in it) or over a frame (it would drop)
        if (k != TempBlock.Kind.LIGHT && occupied(level, pos)) return false;
        if (!mayChange(level, pos, owner)) return false;
        Deque<Placed> q = OWNED.computeIfAbsent(owner.getUUID(), u -> new HashMap<>()).computeIfAbsent(k, kk -> new ArrayDeque<>());
        // the ones that already ended no longer count, and are never ended again (another block may stand there now)
        q.removeIf(p -> STANDING.get(p.at) != p);
        while (q.size() >= cap) {
            Placed old = q.pollFirst();
            STANDING.remove(old.at);
            ServerLevel l = level.getServer().getLevel(old.at.dimension());
            BlockPos op = old.at.pos();
            if (l != null && l.isLoaded(op) && l.getBlockState(op).getBlock() instanceof TempBlock tb && tb.kind == k) tb.fade(l, op);
        }
        if (!level.setBlock(pos, block(k).defaultBlockState(), Block.UPDATE_ALL)) return false;
        Placed p = new Placed(GlobalPos.of(level.dimension(), pos.immutable()));
        STANDING.put(p.at, p);
        q.addLast(p);
        return true;
    }

    /** A rune block ended, however it ended: it no longer counts against whoever made it. */
    public static void gone(ResourceKey<Level> dim, BlockPos pos) {
        if (!STANDING.isEmpty()) STANDING.remove(GlobalPos.of(dim, pos));
    }

    /** A player left: their blocks stay until their timers end, but no longer count against anyone. */
    public static void forget(UUID owner) { OWNED.remove(owner); }

    public static void clear() {
        OWNED.clear();
        STANDING.clear();
    }
}
