package com.explorationremix.world.lava;

import com.explorationremix.config.ERConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The falling blocks the quakes and eruptions let go of in the Basalt Galleries (0.9.0, spec 4.4.4): ceiling debris, and
 * the eruptions' ejecta. Every tick, at the end of the level's tick ({@code LavaFlood.tick}, after the quakes and
 * eruptions):
 * <ul>
 *     <li>a tracked block that is no longer on entity-ticking ground (its player moved off, died or logged out) is
 *     discarded: it vanishes, and its hole, which the change log holds, comes back with the reset;</li>
 *     <li>one its owner holds (a quake held by command, a staged drop) is frozen in the air: no gravity, no motion, and
 *     its clock kept at 1 so the game's 600-tick discard never comes; let go, it goes on with the motion it had;</li>
 *     <li>each other one's box, swept by its next tick's move, is checked for living things that are alive and neither
 *     spectators nor creative players. Each takes, once, vanilla's falling-block damage for the distance it fell, up to
 *     {@code quakes.debrisDamage}, from a falling-block damage source with no entity (so armour counts and nobody is
 *     knocked back); then the block breaks there (discarded, and a {@link LavaNet.Debris} of kind SHATTER goes to the
 *     players within 64 blocks): it is never placed in someone's feet. With {@code debrisDamage} 0 it still breaks.</li>
 * </ul>
 * Tracked blocks carry the tag {@link #TAG}: one saved with its chunk is discarded when the chunk loads again
 * ({@link #joined}), so a block from before a stop or an unload never lands after a reset; and when the server stops,
 * every hold is let go and every tracked block discarded before the save ({@link #stopping}).
 */
public final class Falling {
    private Falling() {}

    /** What a tracked block is. */
    public static final int DEBRIS = 0, EJECTA = 1;
    /** The tag every tracked block carries, saved with it. */
    public static final String TAG = "explorationremix:debris";
    /** Speed a falling block loses to gravity at the start of its next tick (FallingBlockEntity.tick), in blocks a tick. */
    public static final double GRAVITY = 0.04;

    /** Whoever let a tracked block go: it says when the block is held and hears how it ended. */
    public interface Owner {
        /**
         * Asked once a tick, before the block's hits: whether it is held in the air now. It may move the block to where it
         * is to be held.
         */
        boolean holds(FallingBlockEntity e);

        /** It came to rest as a block in the cell {@code at} (or in the lava there). */
        void landed(FallingBlockEntity e, BlockPos at);

        /** It broke on someone at {@code at}. */
        void shattered(FallingBlockEntity e, BlockPos at);

        /** It is gone without landing or breaking on anyone (it could not land, or it was discarded). */
        void gone(FallingBlockEntity e);
    }

    private static final class Tracked {
        final FallingBlockEntity e;
        final int kind;
        @Nullable final Owner owner;
        /** While it is held, the motion it had when it was frozen. */
        @Nullable Vec3 saved;

        Tracked(FallingBlockEntity e, int kind, @Nullable Owner owner) {
            this.e = e;
            this.kind = kind;
            this.owner = owner;
        }
    }

    private static final List<Tracked> TRACKED = new ArrayList<>();

    /** Tracks a falling block just let go of ({@code kind} {@link #DEBRIS} or {@link #EJECTA}) for its hits and holds. */
    public static void track(FallingBlockEntity e, int kind, @Nullable Owner owner) {
        TRACKED.add(new Tracked(e, kind, owner));
    }

    /** How many tracked blocks are in the air now. */
    public static int count() { return TRACKED.size(); }

    /** How many tracked blocks of {@code kind} are in the air now. */
    public static int count(int kind) {
        int n = 0;
        for (Tracked t : TRACKED) if (t.kind == kind) n++;
        return n;
    }

    /** Every tick, after the quakes and eruptions. */
    public static void tick(ServerLevel level, LavaFlood.Band band) {
        if (TRACKED.isEmpty()) return;
        long now = level.getGameTime();
        int cap = ERConfig.INSTANCE.quakeDebrisDamage.get();
        Predicate<Entity> hittable = x -> EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(x) && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(x);
        DamageSource source = null;
        List<ServerPlayer> watchers = null;
        for (Iterator<Tracked> it = TRACKED.iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            FallingBlockEntity e = t.e;
            if (e.isRemoved() || e.level() != level) {
                it.remove();
                ended(level, t);
                continue;
            }
            BlockPos at = e.blockPosition();
            if (!level.isPositionEntityTicking(at)) {
                e.discard();
                it.remove();
                if (t.owner != null) t.owner.gone(e);
                continue;
            }
            if (t.owner != null && t.owner.holds(e)) {
                freeze(t);
                continue;
            }
            if (t.saved != null) thaw(t);
            Vec3 v = e.getDeltaMovement();
            AABB b = e.getBoundingBox();
            double[] s = sweep(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, v.x, v.y, v.z);
            List<LivingEntity> hit = level.getEntitiesOfClass(LivingEntity.class, new AABB(s[0], s[1], s[2], s[3], s[4], s[5]), hittable);
            if (hit.isEmpty()) continue;
            int damage = damage(e.fallDistance, cap);
            if (damage > 0) {
                if (source == null) {
                    source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.FALLING_BLOCK));
                }
                for (LivingEntity l : hit) l.hurt(source, damage);
            }
            e.discard();
            it.remove();
            if (watchers == null) watchers = NearChunks.players(level, band);
            LavaNet.Debris d = new LavaNet.Debris(LavaNet.Debris.SHATTER, at.asLong(), Block.getId(e.getBlockState()), now, at.getY(), Integer.MIN_VALUE, -1);
            send(watchers, d, at, LavaNet.Debris.SHATTER_RANGE);
            if (t.owner != null) t.owner.shattered(e, at);
        }
    }

    /** A tracked block went by itself: it landed if its block now stands where it was, else it is gone. */
    private static void ended(ServerLevel level, Tracked t) {
        if (t.owner == null) return;
        FallingBlockEntity e = t.e;
        BlockPos at = e.blockPosition();
        LevelChunk c = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        BlockState s = c == null ? null : c.getBlockState(at);
        if (e.getRemovalReason() == Entity.RemovalReason.DISCARDED && s == e.getBlockState()) t.owner.landed(e, at);
        else t.owner.gone(e);
    }

    private static void freeze(Tracked t) {
        FallingBlockEntity e = t.e;
        if (t.saved == null) {
            t.saved = e.getDeltaMovement();
            e.setNoGravity(true);
            e.setDeltaMovement(Vec3.ZERO);
            // sends the motion on the next update, so clients stop it too
            e.hurtMarked = true;
        }
        e.time = 1;
    }

    private static void thaw(Tracked t) {
        FallingBlockEntity e = t.e;
        e.setNoGravity(false);
        e.setDeltaMovement(t.saved);
        e.hurtMarked = true;
        t.saved = null;
    }

    /** Sends a debris message to the watchers within {@code range} blocks of {@code at}. */
    public static void send(List<ServerPlayer> watchers, LavaNet.Debris d, BlockPos at, int range) {
        double r2 = (double) range * range;
        for (ServerPlayer p : watchers) {
            if (p.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) > r2) continue;
            LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), d);
        }
    }

    // ================================================================================================ the listeners

    /** A tracked block saved with its chunk is discarded as the chunk loads, before it can land (spec 4.4.4). */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk()) return;
        if (event.getEntity() instanceof FallingBlockEntity f && f.getTags().contains(TAG)) {
            f.discard();
            event.setCanceled(true);
        }
    }

    /** Before the save: every hold is let go and every tracked block discarded, so nothing is saved frozen (spec 6.6). */
    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        for (Tracked t : TRACKED) {
            if (t.saved != null) thaw(t);
            t.e.discard();
        }
        TRACKED.clear();
    }

    // ================================================================================================ pure, tested

    /**
     * The box a falling block's box sweeps through on its next tick's move: its motion now, less the gravity its next tick
     * starts with. Returned as {minX, minY, minZ, maxX, maxY, maxZ}.
     */
    static double[] sweep(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double vx, double vy, double vz) {
        double dy = vy - GRAVITY;
        return new double[]{minX + Math.min(0, vx), minY + Math.min(0, dy), minZ + Math.min(0, vz),
                maxX + Math.max(0, vx), maxY + Math.max(0, dy), maxZ + Math.max(0, vz)};
    }

    /** True when a swept box ({@link #sweep}) meets the box given, as the game's boxes meet (overlapping, not touching). */
    static boolean meets(double[] s, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return s[0] < maxX && s[3] > minX && s[1] < maxY && s[4] > minY && s[2] < maxZ && s[5] > minZ;
    }

    /**
     * The damage of a block that fell {@code fallDistance} blocks: vanilla's own for falling blocks, 2 for each block
     * after the first, rounded up, and at most {@code cap}.
     */
    static int damage(float fallDistance, int cap) {
        int blocks = (int) Math.ceil(fallDistance - 1.0F);
        return Math.max(0, Math.min((int) Math.floor(blocks * 2.0F), cap));
    }
}
