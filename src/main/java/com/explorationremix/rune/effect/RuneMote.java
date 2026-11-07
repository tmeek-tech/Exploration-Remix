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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One of Siphon's white motes: energy knocked loose by a well-placed Siphon hit, on its way back to the gear that earned
 * it. It pops out of what was hit, hangs there for a moment, then streaks to its player from up to {@link #RANGE}
 * blocks, through anything in the way (Taran chose "snap to you fast", 2026-11-07). Only the player who earned it can
 * collect it, and it refills only that piece of gear. A player further off, or gone, leaves it to settle on the ground
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
    /** Roughly how much energy one mote carries; a refund splits into 1 to 5 of them. */
    private static final float PER_MOTE = 3.0F;

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
     * Knocks {@code energy} loose at {@code at} for {@code owner}'s gear {@code gear}: a few motes, or, when the player
     * already has too many out (on their way, or lying where far-off foes were struck), more energy in the nearest one.
     */
    public static void spawn(ServerLevel level, Vec3 at, ServerPlayer owner, int gear, float energy) {
        if (energy <= 0.0F || gear == 0) return;
        int cap = RuneConfig.get(RuneConfig.INSTANCE.maxMotes);
        java.util.function.Predicate<RuneMote> theirs = m -> owner.getUUID().equals(m.owner) && m.isAlive();
        Set<RuneMote> out = new LinkedHashSet<>(level.getEntities(RuneRegistry.MOTE.get(), owner.getBoundingBox().inflate(RANGE * 2), theirs));
        out.addAll(level.getEntities(RuneRegistry.MOTE.get(), new AABB(at, at).inflate(RANGE * 2), theirs));
        int n = Mth.clamp(Math.round(energy / PER_MOTE), 1, 5);
        if (out.size() + n > cap) {
            RuneMote nearest = null;
            for (RuneMote m : out) {
                if (m.gear == gear && (nearest == null || m.distanceToSqr(at) < nearest.distanceToSqr(at))) nearest = m;
            }
            if (nearest != null) {
                nearest.energy += energy;
                nearest.resize();
                return;
            }
            n = 1;
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
            level.addFreshEntity(m);
        }
    }

    private void resize() { entityData.set(DATA_SIZE, Mth.clamp(0.55F + energy / 8.0F, 0.6F, 1.4F)); }

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (level().isClientSide) {
            // the client moves it along its last known motion between updates
            if (noPhysics) setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
            RuneFx.moteTrail(this);
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
            level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.12F,
                    1.25F + 0.75F * full + (random.nextFloat() - 0.5F) * 0.1F);
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

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
