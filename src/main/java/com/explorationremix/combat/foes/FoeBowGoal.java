package com.explorationremix.combat.foes;

import com.explorationremix.combat.Fighters;
import com.explorationremix.combat.guard.Guard;
import com.explorationremix.mixin.accessor.AbstractSkeletonAccessor;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A skeleton's (or a stray's) fight, bow or not. With a bow it keeps its quarry in sight from up to
 * {@link FoeRules#BOW_RANGE}, stepping back from one nearer than {@link FoeRules#BACK_OFF} and to the sides as vanilla's
 * do; each draw has a tell {@link FoeRules#SHOT_TELL} ticks before the arrow goes (the string creaks, a glint at the
 * bow: time to raise a guard and turn it back), and the arrow is aimed where a moving player will be
 * ({@link FoeRules#lead}). A player who reaches it is kicked away ({@link FoeRules#KICK}). With nothing to shoot with,
 * it fights as a zombie does, quicker and with a shorter reach.
 */
final class FoeBowGoal extends FoeFight {
    private final AbstractSkeleton archer;
    private int seeTime, shotCool, kickCool = 20, strafeTicks = -1;
    private boolean strafeLeft, drawing;
    /** When this draw's tell went (game time); below 0: not yet. */
    private long toldAt = -1L;

    FoeBowGoal(AbstractSkeleton archer) {
        super(archer, 1.0);
        this.archer = archer;
    }

    private boolean bow() { return archer.isHolding(is -> is.getItem() instanceof BowItem); }

    /** With a bow: any quarry (as vanilla's). Without: one it can get to, as the melee fight asks. */
    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (!valid(t)) return false;
        return bow() || super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        if (!valid(t)) return false;
        return bow() || super.canContinueToUse();
    }

    @Override
    public void stop() {
        super.stop();
        letDown();
        seeTime = 0;
        strafeTicks = -1;
    }

    @Override
    protected double chaseSpeed() { return bow() ? 1.0 : 1.2; }

    @Override
    protected void fight(LivingEntity t) {
        if (kickCool > 0) kickCool--;
        if (!bow()) {
            letDown();
            meleeFight(t, FoeRules.SKELETON, 1.0);
            return;
        }
        if (moving(t)) return;
        double d = Math.sqrt(mob.distanceToSqr(t));
        boolean sees = mob.getSensing().hasLineOfSight(t);
        if (kickCool <= 0 && sees && d <= FoeRules.KICK.start() && level(t) && FoeTurns.take(mob, t, Foes.strikers())) {
            letDown();
            kickCool = FoeRules.KICK.cooldown(mob.getRandom().nextDouble());
            windUp(t, FoeRules.KICK, 1.0, 0);
            return;
        }
        shooting(t, d, sees);
    }

    /** As vanilla's bow goal: in range and in sight it stands and strafes, else it closes in; it draws, tells, and lets go. */
    private void shooting(LivingEntity t, double d, boolean sees) {
        if (sees != seeTime > 0) seeTime = 0;
        seeTime += sees ? 1 : -1;
        if (d <= FoeRules.BOW_RANGE && seeTime >= 20) {
            mob.getNavigation().stop();
            strafeTicks++;
        } else {
            chase(t, 1.0);
            strafeTicks = -1;
        }
        if (strafeTicks >= 20) {
            if (mob.getRandom().nextFloat() < 0.3F) strafeLeft = !strafeLeft;
            strafeTicks = 0;
        }
        if (strafeTicks > -1) {
            // a player who comes near is backed away from; one far off is closed on a little
            float fwd = d < FoeRules.BACK_OFF ? -0.5F : d > FoeRules.BOW_RANGE * 0.75 ? 0.5F : 0.0F;
            mob.getMoveControl().strafe(fwd, strafeLeft ? 0.5F : -0.5F);
            mob.lookAt(t, 30.0F, 30.0F);
        }
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        long now = mob.level().getGameTime();
        if (archer.isUsingItem()) {
            if (!sees && seeTime < -60) {
                letDown();
                return;
            }
            int drawn = archer.getTicksUsingItem();
            if (toldAt >= 0L && !sees) {
                // its quarry ducked out of sight after the tell: the shot waits, and is told again when it is seen
                toldAt = -1L;
                Foes.phase(mob, FoeMove.SHOT, FoeMove.Phase.IDLE, 0);
            }
            if (toldAt < 0L && sees && drawn >= FoeRules.DRAW - FoeRules.SHOT_TELL) {
                // the last of the draw: the string creaks and the bow glints, the tell to raise a guard on
                toldAt = now;
                Vec3 at = mob.getEyePosition().add(mob.getViewVector(1.0F).scale(0.7)).add(0.0, -0.25, 0.0);
                Foes.tellAt(mob, at, t.getEyePosition().subtract(at), 1.0F);
                Foes.phase(mob, FoeMove.SHOT, FoeMove.Phase.WINDUP, FoeRules.SHOT_TELL);
            }
            if (toldAt >= 0L && sees && now - toldAt >= FoeRules.SHOT_TELL) {
                archer.stopUsingItem();
                drawing = false;
                toldAt = -1L;
                shoot(t, BowItem.getPowerForTime(drawn));
                shotCool = mob.level().getDifficulty() == Difficulty.HARD ? 20 : 40;
                Foes.phase(mob, FoeMove.SHOT, FoeMove.Phase.RECOVER, 6);
            }
        } else if (--shotCool <= 0 && seeTime >= -60) {
            archer.startUsingItem(ProjectileUtil.getWeaponHoldingHand(archer, item -> item instanceof BowItem));
            drawing = true;
            toldAt = -1L;
        }
    }

    /** A draw under way is let down (it kicks, it lost sight of its quarry for long, or its bow went). */
    private void letDown() {
        if (drawing && archer.isUsingItem()) archer.stopUsingItem();
        if (toldAt >= 0L) Foes.phase(mob, FoeMove.SHOT, FoeMove.Phase.IDLE, 0);
        drawing = false;
        toldAt = -1L;
    }

    /** Its arrow, aimed {@link FoeRules#LEAD} of the way to where a moving quarry will be when it gets there. */
    private void shoot(LivingEntity t, float power) {
        ItemStack bow = archer.getItemInHand(ProjectileUtil.getWeaponHoldingHand(archer, item -> item instanceof BowItem));
        AbstractArrow arrow = ((AbstractSkeletonAccessor) archer).er$getArrow(archer.getProjectile(bow), power);
        if (archer.getMainHandItem().getItem() instanceof BowItem b) arrow = b.customArrow(arrow);
        Vec3 v = t instanceof ServerPlayer sp ? Fighters.velocity(sp) : t.getDeltaMovement();
        double[] aim = FoeRules.lead(archer.getX(), arrow.getY(), archer.getZ(), t.getX(), t.getY(1.0 / 3.0), t.getZ(), v.x, v.z,
                FoeRules.ARROW_SPEED);
        double dx = aim[0] - archer.getX(), dy = aim[1] - arrow.getY(), dz = aim[2] - archer.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        // vanilla's arc and spread (less on harder difficulties)
        arrow.shoot(dx, dy + flat * 0.2, dz, (float) FoeRules.ARROW_SPEED, (float) (14 - archer.level().getDifficulty().getId() * 4));
        if (FoeKind.SKELETON.harder) arrow.setBaseDamage(arrow.getBaseDamage() * Foes.damage());
        archer.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (archer.getRandom().nextFloat() * 0.4F + 0.8F));
        archer.level().addFreshEntity(arrow);
    }

    /** A kick only pushes (and stings): a player is thrown back about three blocks, less into a raised guard. */
    @Override
    protected void blow(LivingEntity t, FoeRules.Melee spec) {
        if (spec != FoeRules.KICK) {
            super.blow(t, spec);
            return;
        }
        Vec3 was = t.getDeltaMovement();
        if (!t.hurt(mob.damageSources().mobAttack(mob), 1.0F)) return;
        Vec3 away = new Vec3(t.getX() - mob.getX(), 0.0, t.getZ() - mob.getZ());
        away = away.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0.0F, mob.getYRot()) : away.normalize();
        double k = t instanceof Player p && Guard.guarding(p) ? 0.5 : 1.0;
        double share = t.onGround() ? 1.0 : 0.65;
        Vec3 push = new Vec3(away.x * FoeRules.KICK_PUSH * k * share, FoeRules.KICK_LIFT * k, away.z * FoeRules.KICK_PUSH * k * share);
        if (t instanceof ServerPlayer sp && sp.connection != null) {
            // a push from a creature's tick reaches the player's game a tick late and half spent: sent at once instead
            sp.setDeltaMovement(was);
            sp.hurtMarked = false;
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), push));
        } else {
            t.setDeltaMovement(push);
            t.hurtMarked = true;
        }
        mob.playSound(SoundEvents.SKELETON_STEP, 1.0F, 0.6F);
    }

    @Override
    protected void idle() {
        // after a kick it steps back to shoot
        strafeTicks = 0;
    }
}
