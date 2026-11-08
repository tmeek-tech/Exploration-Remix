package com.explorationremix.entity;

import com.explorationremix.world.eruption.Blast;
import com.explorationremix.world.eruption.BombFlight;
import com.explorationremix.world.eruption.Eruptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A lava bomb thrown by an eruption of the Caldera (0.9.0, spec 5.4.1; {@code world.eruption.Eruptions} throws it, and
 * {@link BombFlight} aims it). A projectile with no owner and no data of its own, never saved: one in a chunk that
 * unloads is gone. It flies under its own gravity ({@link BombFlight#GRAVITY}, on both sides, so each client's own
 * flight of it matches the server's) and the projectile's drag, and ends in one of two ways:
 * <ul>
 *     <li>it <b>fizzles</b> (entity event {@link #FIZZLE}, no blast) when it reaches the top of a fluid (its own test
 *     each tick, before the game's hit test, which passes through lava), when it has flown {@link BombFlight#MAX_TICKS}
 *     ticks, or when the eruptions let it go (off entity-ticking ground, or eruptions turned off);</li>
 *     <li>it <b>bursts</b> (entity event {@link #BURST}) when the game's hit test finds a block or a living thing in its
 *     way: a blast there ({@link Blast#burst}), which may leave a crater.</li>
 * </ul>
 * While its eruption is held by command it hangs still in the air with its clock stopped, and goes on as it was when
 * let go. A common class: it names no client type, and reaches the client's effects ({@code client.BombEffects}) only
 * through {@code DistExecutor} (spec 3.6).
 */
public class LavaBomb extends ThrowableProjectile {
    /** The entity events it sends its clients as it ends. */
    public static final byte BURST = 3, FIZZLE = 4;

    /** Ticks it has flown, on the server (stopped while held). */
    private int life;
    /** While held, the motion it had when it was stopped. */
    @Nullable private Vec3 saved;
    /** Its own draws as it bursts (the blast's power, the crater's magma and ejecta), from the seed it was thrown with. */
    @Nullable private Random draws;

    /**
     * Set as it bursts, for the blast's handler ({@link Blast}, spec 5.4.4): whether its blast may break blocks (the reset
     * on and no way down near), the blast's power, and the crater's blocks the handler took out of the blast's list
     * with their states, which {@link Blast} then removes. Server only, never saved.
     */
    public boolean breaks;
    public float power;
    public final List<BlockPos> crater = new ArrayList<>();
    public final List<BlockState> craterStates = new ArrayList<>();

    public LavaBomb(EntityType<? extends LavaBomb> type, Level level) { super(type, level); }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected float getGravity() { return (float) BombFlight.GRAVITY; }

    @Override
    public void tick() {
        if (level().isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.BombEffects.tick(this));
            super.tick();
            return;
        }
        if (Eruptions.holds(this)) {
            freeze();
            return;
        }
        if (saved != null) thaw();
        if (++life >= BombFlight.MAX_TICKS) {
            fizzle();
            return;
        }
        // the lava test: the game's own hit test passes through fluids, so a bomb would sink through the lake and burst
        // on its bed; the top of a fluid (a cell holding one with nothing to collide with) ends it here instead
        Vec3 from = position(), to = from.add(getDeltaMovement());
        BlockHitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = hit.getBlockPos();
            BlockState s = level().getBlockState(p);
            if (!s.getFluidState().isEmpty() && s.getCollisionShape(level(), p).isEmpty()) {
                Eruptions.intoLava(this);
                // returns at once: the game's hit test would otherwise run on the bomb just ended and burst it on the
                // rock under a shallow pool
                fizzle();
                return;
            }
        }
        super.tick();
    }

    /** Ends it without a blast: its clients play the fizzle. Server only. */
    public void fizzle() {
        if (isRemoved() || level().isClientSide) return;
        level().broadcastEntityEvent(this, FIZZLE);
        discard();
    }

    @Override
    protected void onHit(HitResult hit) {
        if (isRemoved() || level().isClientSide) return;
        Blast.burst(this, hit);
        level().broadcastEntityEvent(this, BURST);
        discard();
    }

    /** Seeds its own draws (from its eruption's, or a command's seed), so a seeded eruption's craters repeat. */
    public void seed(long seed) { draws = new Random(seed); }

    /** Its own draws: from its seed, or from the level's random when it was given none. */
    public Random draws() {
        if (draws == null) draws = new Random(level().getRandom().nextLong());
        return draws;
    }

    /** Only a living thing stops it: a falling block in the air, which can be picked, would burst it in mid-flight. */
    @Override
    protected boolean canHitEntity(Entity e) { return e instanceof LivingEntity && super.canHitEntity(e); }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == BURST) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.BombEffects.burst(this));
        else if (id == FIZZLE) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.BombEffects.fizzle(this));
        else super.handleEntityEvent(id);
    }

    /**
     * Held: no gravity (synched, so its clients stop it too), no motion (sent to them with {@code hurtMarked}), and its
     * clock stopped.
     */
    private void freeze() {
        if (saved != null) return;
        saved = getDeltaMovement();
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        hurtMarked = true;
    }

    /** Let go: its gravity and the motion it had come back. */
    private void thaw() {
        setNoGravity(false);
        setDeltaMovement(saved);
        hurtMarked = true;
        saved = null;
    }

    /** Let go of a hold now, so it is not left without gravity (the server stops, or the eruptions are turned off). */
    public void letGo() {
        if (saved != null) thaw();
    }
}
