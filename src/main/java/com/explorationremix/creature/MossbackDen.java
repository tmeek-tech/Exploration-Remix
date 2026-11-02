package com.explorationremix.creature;

import com.explorationremix.registry.EREntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A Mossback's den: a patch of trampled moss and gnawed bone on a plant cave's floor, placed with the world, about one
 * to a plant cave. The den keeps one lizard. It brings its lizard into being when a player comes within a few chunks
 * (never in plain sight), and when that lizard is killed the den lies empty for a few in-game days before a new one
 * moves in. Breaking the den drops nothing.
 */
public class MossbackDen extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);
    /** How long a den lies empty after its lizard is killed: three in-game days. */
    public static final long EMPTY_TICKS = 3 * 24000L;
    /** A den whose lizard has not checked in for this long has lost it (it is gone, or wandered off for good). */
    static final long LOST_TICKS = 6000L;

    public MossbackDen(Properties props) {
        super(props);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Tile(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, EREntities.DEN_TILE.get(), (l, p, s, t) -> t.tick((ServerLevel) l));
    }

    /** The lizard says it is still about. */
    static void checkIn(MossbackEntity lizard) {
        Tile t = tile(lizard.level(), lizard.den());
        if (t == null) {
            if (lizard.den() != null && lizard.level().isLoaded(lizard.den())) lizard.setDen(null);   // its den is gone
            return;
        }
        if (t.lizard == null || t.lizard.equals(lizard.getUUID())) {
            t.lizard = lizard.getUUID();
            t.lastSeen = lizard.level().getGameTime();
            t.setChanged();
        } else {
            lizard.becomeStray();          // its den has taken in another: it roams on alone, a rival to the newcomer
        }
    }

    static void lizardDied(MossbackEntity lizard) {
        Tile t = tile(lizard.level(), lizard.den());
        if (t != null && (t.lizard == null || t.lizard.equals(lizard.getUUID()))) {
            t.lizard = null;
            t.emptyUntil = lizard.level().getGameTime() + EMPTY_TICKS;
            t.setChanged();
        }
    }

    @Nullable
    private static Tile tile(Level level, @Nullable BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof Tile t ? t : null;
    }

    public static class Tile extends BlockEntity {
        @Nullable UUID lizard;
        long lastSeen, emptyUntil;
        /** False until its first tick after loading: time spent unloaded never counts towards losing its lizard. */
        private boolean awake;
        /** The game time of its last tick: a den can stop ticking without being unloaded (out of simulation range). */
        private long lastTick;

        public Tile(BlockPos pos, BlockState state) {
            super(EREntities.DEN_TILE.get(), pos, state);
        }

        void tick(ServerLevel level) {
            long now = level.getGameTime();
            if (!awake || now - lastTick > 20) {
                // waking (loaded again, or back in range): its lizard gets the full time to turn up again
                awake = true;
                if (lizard != null) lastSeen = Math.max(lastSeen, now);
            }
            lastTick = now;
            if (Math.floorMod(now + worldPosition.hashCode(), 40L) != 0) return;
            if (lizard != null) {
                Entity e = level.getEntity(lizard);
                if (e instanceof MossbackEntity m && m.isAlive()) {
                    lastSeen = now;
                    return;
                }
                if (now - lastSeen < LOST_TICKS) return;
                // lost (killed out of reach of its den, or gone for good): the den lies empty as after a death, and
                // takes it back if it ever returns
                lizard = null;
                emptyUntil = Math.max(emptyUntil, lastSeen + EMPTY_TICKS);
                setChanged();
                return;
            }
            if (now < emptyUntil || level.getDifficulty() == Difficulty.PEACEFUL) return;
            // only when a player is near enough to meet it, and none close enough to watch it appear
            double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY(), cz = worldPosition.getZ() + 0.5;
            Player near = level.getNearestPlayer(cx, cy, cz, 64.0, p -> !p.isSpectator());
            if (near == null || near.distanceToSqr(cx, cy, cz) < 16 * 16) return;
            MossbackEntity m = EREntities.MOSSBACK.get().create(level);
            if (m == null) return;
            // the middle of the 2 x 2 the world generation checked (the den is its low corner)
            m.moveTo(worldPosition.getX() + 1.0, cy + 0.1, worldPosition.getZ() + 1.0, level.random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(m)) return;
            m.setDen(worldPosition);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(worldPosition), MobSpawnType.STRUCTURE, null, null);
            level.addFreshEntityWithPassengers(m);
            lizard = m.getUUID();
            lastSeen = now;
            setChanged();
        }

        public String describe(long now) {
            if (lizard != null) return "kept by " + lizard.toString().substring(0, 8) + ", seen " + (now - lastSeen) / 20 + "s ago";
            if (now < emptyUntil) return "empty for " + (emptyUntil - now) / 20 + "s more";
            return "waiting for a player";
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            if (lizard != null) tag.putUUID("Lizard", lizard);
            tag.putLong("LastSeen", lastSeen);
            tag.putLong("EmptyUntil", emptyUntil);
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            lizard = tag.hasUUID("Lizard") ? tag.getUUID("Lizard") : null;
            lastSeen = tag.getLong("LastSeen");
            emptyUntil = tag.getLong("EmptyUntil");
        }
    }
}
