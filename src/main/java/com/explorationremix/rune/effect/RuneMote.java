package com.explorationremix.rune.effect;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One white mote: energy a hit knocked loose (a sword's swing, an arrow, a rune; most with Siphon), on its way back to
 * the gear that earned it. It pops out of what was hit, hangs there for a moment, then streaks to its player from up to
 * {@link #RANGE} blocks, through anything in the way (Taran chose "snap to you fast", 2026-11-07). Only the player who
 * earned it can collect it, and it fills only that piece of gear. A player further off, or gone, leaves it to settle on the ground
 * like an experience orb until they come back or it fades after a minute. Never saved: it is gone when its chunk
 * unloads (its type is never written, and a chunk unload removes it as any entity).
 */
public class RuneMote extends Entity {
    private static final EntityDataAccessor<Float> DATA_SIZE = SynchedEntityData.defineId(RuneMote.class, EntityDataSerializers.FLOAT);

    /** Ticks it hangs where it was knocked loose before it streaks home. */
    public static final int HANG = 8;
    /** It streaks to its player from this far (blocks). */
    public static final double RANGE = 24.0;
    /** Ticks before an uncollected mote fades. */
    public static final int LIFE = 1200;
    /** Roughly how much energy one mote carries; a hit's energy splits into 1 to 5 of them. */
    private static final float PER_MOTE = 4.0F;
    /** Every player's motes out now, oldest first, wherever they lie: the cap holds per player. Server side only. */
    private static final Map<UUID, Set<RuneMote>> OUT = new HashMap<>();
    /** The tick each player last heard motes land: a hit's motes land together, and are heard once. */
    private static final Map<UUID, Long> HEARD = new HashMap<>();

    @Nullable
    private UUID owner;
    /** The gear id ({@link RuneGear#gearId}) it refills. */
    private int gear;
    private float energy;
    private int age;

    public RuneMote(EntityType<? extends RuneMote> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() { entityData.define(DATA_SIZE, 1.0F); }

    /** How big it is drawn: about 0.6 for a small refund, up to 1.4. */
    public float size() { return entityData.get(DATA_SIZE); }

    public float energy() { return energy; }

    @Nullable
    public UUID owner() { return owner; }

    /**
     * Knocks {@code energy} loose at {@code at} for {@code owner}'s gear {@code gear}: a few motes. When the player
     * already has too many out, wherever they lie, it goes into the nearest of that gear's motes near the hit or the
     * player instead; with none near, it comes as one mote, and the player's oldest mote left lying (out of their reach,
     * or in another world) fades to make room. A mote on its way home is never cut short: with none to spare, the cap
     * is passed by one until those land.
     */
    public static void spawn(ServerLevel level, Vec3 at, ServerPlayer owner, int gear, float energy) {
        if (energy <= 0.0F || gear == 0) return;
        int cap = RuneConfig.get(RuneConfig.INSTANCE.maxMotes);
        Set<RuneMote> out = OUT.computeIfAbsent(owner.getUUID(), u -> new LinkedHashSet<>());
        out.removeIf(Entity::isRemoved);
        int n = Mth.clamp(Math.round(energy / PER_MOTE), 1, 5);
        if (out.size() + n > cap) {
            double reach = RANGE * 2;
            RuneMote nearest = null;
            for (RuneMote m : out) {
                if (m.gear != gear || m.level() != level) continue;
                if (m.distanceToSqr(at) > reach * reach && m.distanceToSqr(owner) > reach * reach) continue;
                if (nearest == null || m.distanceToSqr(at) < nearest.distanceToSqr(at)) nearest = m;
            }
            if (nearest != null) {
                nearest.energy += energy;
                nearest.resize();
                return;
            }
            n = 1;
            for (RuneMote old : new ArrayList<>(out)) {
                if (out.size() < cap) break;
                if (old.level() == owner.level() && old.distanceToSqr(owner) < RANGE * RANGE) continue;
                out.remove(old);
                old.fade();
            }
        }
        RandomSource r = level.random;
        for (int i = 0; i < n; i++) {
            RuneMote m = RuneRegistry.MOTE.get().create(level);
            if (m == null) return;
            m.owner = owner.getUUID();
            m.gear = gear;
            m.energy = energy / n;
            m.setPos(at.x, at.y, at.z);
            m.setDeltaMovement((r.nextDouble() - 0.5) * 0.35, 0.12 + r.nextDouble() * 0.18, (r.nextDouble() - 0.5) * 0.35);
            m.resize();
            if (level.addFreshEntity(m)) out.add(m);
        }
    }

    /** However it ends (collected, faded, its chunk unloaded), it stops counting against its player. */
    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (level().isClientSide || owner == null) return;
        Set<RuneMote> out = OUT.get(owner);
        if (out != null && out.remove(this) && out.isEmpty()) OUT.remove(owner);
    }

    public static void clearAll() {
        OUT.clear();
        HEARD.clear();
    }

    private void resize() { entityData.set(DATA_SIZE, Mth.clamp(0.55F + energy / 8.0F, 0.6F, 1.4F)); }

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (level().isClientSide) {
            // the client moves it along its last known motion between updates
            if (noPhysics) setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
            RuneFx.client.moteTick(this);
            return;
        }
        age++;
        if (age > LIFE || owner == null || energy <= 0.0F) {
            fade();
            return;
        }
        ServerPlayer p = player();
        if (age < HANG) {
            // knocked loose: slowing to a hang
            v = v.scale(0.78);
            noPhysics = true;
        } else if (p != null && distanceToSqr(p) < RANGE * RANGE) {
            Vec3 target = p.position().add(0, p.getBbHeight() * 0.55, 0);
            Vec3 to = target.subtract(position());
            double d = to.length();
            if (d < 0.5 + p.getBbWidth() * 0.5) {
                collect(p);
                return;
            }
            double speed = Math.min(1.6, Math.max(0.35, v.length() + 0.16));
            v = v.lerp(to.scale(speed / d), 0.45);
            if (v.length() > d) v = to;
            noPhysics = true;
        } else {
            // nobody near to go to: settle on the ground like an experience orb
            noPhysics = false;
            v = v.add(0, -0.02, 0).multiply(0.9, 0.98, 0.9);
        }
        setDeltaMovement(v);
        if (noPhysics) {
            setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
        } else {
            move(MoverType.SELF, v);
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.6, -0.3, 0.6));
        }
    }

    @Nullable
    private ServerPlayer player() {
        if (owner == null) return null;
        Player p = level().getPlayerByUUID(owner);
        return p instanceof ServerPlayer sp && sp.isAlive() && !sp.isSpectator() ? sp : null;
    }

    /** Into the gear that earned it, if its player still has it; else it fizzles. */
    private void collect(ServerPlayer p) {
        ItemStack stack = RuneGear.findGear(p, gear);
        HostSpec spec = stack == null ? null : RuneGear.spec(stack);
        if (stack != null && spec != null) {
            long now = level().getGameTime();
            CastState s = RuneGear.state(stack, spec, now);
            CastMachine.add(s, spec, now, energy);
            RuneGear.setState(stack, s);
            float full = spec.maxEnergy() <= 0 ? 1.0F : s.energy / spec.maxEnergy();
            Long heard = HEARD.get(p.getUUID());
            if (heard == null || now - heard >= 3 || now < heard) {
                HEARD.put(p.getUUID(), now);
                // the pitch climbs as the gear fills
                level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.22F,
                        1.1F + 0.8F * full + (random.nextFloat() - 0.5F) * 0.1F);
                level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.35F, 0.9F + 0.9F * full);
                RuneFx.moteIn((ServerLevel) level(), p.position().add(0, p.getBbHeight() * 0.55, 0), full);
            }
        } else {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.1F, 2.0F);
        }
        discard();
    }

    private void fade() {
        if (level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 1, 0.05, 0.05, 0.05, 0.01);
        discard();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) { return d < 64 * 64; }

    @Override
    public boolean isPickable() { return false; }

    /** A portal never takes it: it belongs to the world it was knocked loose in. */
    @Override
    public boolean canChangeDimensions() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
