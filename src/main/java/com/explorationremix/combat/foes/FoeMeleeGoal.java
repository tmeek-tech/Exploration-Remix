package com.explorationremix.combat.foes;

import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.EntityTeleportEvent;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * The melee fight of zombies (and husks, zombie villagers and drowned), endermen, vindicators, and skeletons with
 * nothing to shoot with (see {@link FoeBowGoal}): each blow winds up, lunges and recovers ({@link FoeFight}). A zombie's
 * slam that misses drives its fists into the ground. An enderman now and then blinks behind its quarry before it
 * strikes ({@link FoeRules#BLINK_NEAR}): the blow comes a little later than usual, time to turn round.
 */
final class FoeMeleeGoal extends FoeFight {
    /** Where an enderman tries to come out, degrees from its quarry's facing: right behind, then a little to a side. */
    private static final double[] BEHIND = {180.0, 150.0, 210.0, 120.0, 240.0};
    /** A blink's blow comes this much later than the move's own (ticks). */
    private static final int BLINK_EXTRA = 4;

    private final FoeRules.Melee spec;
    @Nullable private final Predicate<LivingEntity> ok;
    private final boolean blinks;
    /** Ticks of fighting before it may blink: half a second at first, so one angered from afar comes out behind at once. */
    private int blinkCool = 10;

    FoeMeleeGoal(PathfinderMob mob, FoeRules.Melee spec, double speed, @Nullable Predicate<LivingEntity> ok, boolean blinks) {
        super(mob, speed);
        this.spec = spec;
        this.ok = ok;
        this.blinks = blinks;
    }

    @Override
    protected boolean fights(LivingEntity t) { return ok == null || ok.test(t); }

    /**
     * A zombie never shows vanilla's arms-up: its slam's own pose raises them (client.foes), from where they hang, with
     * no jump at the start of it.
     */
    @Override
    protected boolean aggressive() { return spec.move() != FoeMove.SLAM; }

    @Override
    protected void fight(LivingEntity t) {
        if (blinkCool > 0) blinkCool--;
        if (blinks && phase == FoeMove.Phase.IDLE && blinkCool <= 0 && blink(t)) return;
        meleeFight(t, spec, 1.0);
    }


    /** A zombie's slam that found nothing: its fists hit the ground in front of it, in a puff of what it stands on. */
    @Override
    protected void recovering(LivingEntity t, FoeRules.Melee m, boolean hit) {
        if (hit || m.move() != FoeMove.SLAM || !(mob.level() instanceof ServerLevel l) || !mob.onGround()) return;
        Vec3 flat = Vec3.directionFromRotation(0.0F, mob.getYRot());
        Vec3 at = mob.position().add(flat.scale(mob.getBbWidth() * 0.5 + 0.7)).add(0.0, 0.05, 0.0);
        BlockPos below = BlockPos.containing(at.x, mob.getY() - 0.2, at.z);
        BlockState ground = l.getBlockState(below);
        if (ground.isAir()) ground = l.getBlockState(mob.getOnPos());
        SoundEvent crack = ground.isAir() ? SoundEvents.STONE_BREAK : ground.getSoundType(l, below, mob).getBreakSound();
        RuneFx.smash(l, at, flat, 0.45F, crack, Block.getId(ground), mob.getId());
    }

    /**
     * An enderman blinks behind {@code t} and winds up at once (its quarry hears it go, and has the wind-up to turn
     * round): only from between {@link FoeRules#BLINK_NEAR} and {@link FoeRules#BLINK_FAR} away, seeing it, with a turn
     * free, and only where it comes out on the same floor.
     */
    private boolean blink(LivingEntity t) {
        double d = Math.sqrt(mob.distanceToSqr(t));
        if (d < FoeRules.BLINK_NEAR || d > FoeRules.BLINK_FAR || mob.isPassenger() || !mob.getSensing().hasLineOfSight(t)) return false;
        int most = Foes.strikers();
        if (FoeTurns.full(mob, t, most)) return false;
        blinkCool = FoeRules.BLINK_COOL_MIN + mob.getRandom().nextInt(FoeRules.BLINK_COOL_MAX - FoeRules.BLINK_COOL_MIN + 1);
        double ox = mob.getX(), oy = mob.getY(), oz = mob.getZ();
        for (double deg : BEHIND) {
            double[] p = FoeRules.slotPoint(t.getX(), t.getZ(), t.getYRot(), deg, FoeRules.BLINK_BEHIND);
            EntityTeleportEvent.EnderEntity ev = ForgeEventFactory.onEnderTeleport(mob, p[0], t.getY() + 0.5, p[1]);
            if (ev.isCanceled()) return false;
            if (!mob.randomTeleport(ev.getTargetX(), ev.getTargetY(), ev.getTargetZ(), false)) continue;
            // (seen afresh: what it saw this tick is remembered from where it stood)
            if (Math.abs(mob.getY() - t.getY()) > 1.5 || !mob.hasLineOfSight(t)) {
                // it came out down a hole behind them, or round a corner: not this one
                mob.teleportTo(ox, oy, oz);
                continue;
            }
            // the trail of purple where it went from, only for the try it kept
            mob.level().broadcastEntityEvent(mob, (byte) 46);
            mob.level().gameEvent(GameEvent.TELEPORT, new Vec3(ox, oy, oz), GameEvent.Context.of(mob));
            mob.level().playSound(null, ox, oy, oz, SoundEvents.ENDERMAN_TELEPORT, mob.getSoundSource(), 1.0F, 1.0F);
            mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
            float yaw = (float) (Mth.atan2(t.getZ() - mob.getZ(), t.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90.0F;
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            if (FoeTurns.take(mob, t, most)) windUp(t, spec, 1.0, BLINK_EXTRA);
            return true;
        }
        return false;
    }
}
