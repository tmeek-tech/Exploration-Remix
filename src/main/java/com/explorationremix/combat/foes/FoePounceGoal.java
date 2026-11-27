package com.explorationremix.combat.foes;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

/**
 * A spider's fight (a cave spider's quicker, {@link FoeRules#QUICK}): from {@link FoeRules#POUNCE_NEAR} to
 * {@link FoeRules#POUNCE_FAR} away it crouches with its front legs up and pounces, coming down on its quarry; up close it
 * rears and bites ({@link FoeRules#BITE}); from a ledge or wall above its quarry it lets go onto it. Each winds up
 * first, and a pounce that misses leaves it a while on the ground. As vanilla's, it won't fight with a rider on its
 * back, and in bright light it may lose interest.
 */
final class FoePounceGoal extends FoeFight {
    /** How long it lies after a pounce or drop, landed on its quarry or not (ticks). */
    private static final int LANDED = 10, MISSED = 22;

    private final boolean cave;
    private int pounceCool = 20, air;
    /** Where the leap under way comes down (set as it sets off). */
    private double aimX, aimY, aimZ;

    FoePounceGoal(PathfinderMob mob, boolean cave) {
        super(mob, 1.0);
        this.cave = cave;
    }

    private double quick() { return cave ? FoeRules.QUICK : 1.0; }

    @Override
    public boolean canUse() { return !mob.isVehicle() && super.canUse(); }

    @Override
    public boolean canContinueToUse() {
        if (phase == FoeMove.Phase.IDLE && mob.getLightLevelDependentMagicValue() >= 0.5F && mob.getRandom().nextInt(100) == 0) {
            mob.setTarget(null);
            return false;
        }
        return super.canContinueToUse();
    }

    /** Vanilla's spider reach: two blocks and its quarry's half width. */
    @Override
    protected double reachSqr(LivingEntity t) { return 4.0 + t.getBbWidth(); }

    @Override
    protected void fight(LivingEntity t) {
        if (pounceCool > 0) pounceCool--;
        if ((move == FoeMove.POUNCE || move == FoeMove.DROP) && phase != FoeMove.Phase.IDLE) {
            leaping(t);
            return;
        }
        if (moving(t)) return;
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz), dy = t.getY() - mob.getY();
        double d = Math.sqrt(mob.distanceToSqr(t));
        boolean sees = mob.getSensing().hasLineOfSight(t);
        int most = Foes.strikers();
        if (pounceCool <= 0 && sees && -dy >= FoeRules.DROP_ABOVE && flat <= FoeRules.DROP_NEAR && FoeTurns.take(mob, t, most)) {
            crouch(t, FoeMove.DROP);
            return;
        }
        if (cool <= 0 && sees && d <= FoeRules.BITE.start() && level(t) && FoeTurns.take(mob, t, most)) {
            windUp(t, FoeRules.BITE, quick(), 0);
            return;
        }
        if (pounceCool <= 0 && sees && mob.onGround() && flat >= FoeRules.POUNCE_NEAR && flat <= FoeRules.POUNCE_FAR
                && Math.abs(dy) < 2.5 && FoeTurns.take(mob, t, most)) {
            crouch(t, FoeMove.POUNCE);
            return;
        }
        approach(t, d, sees, FoeRules.BITE.start(), FoeRules.BITE.ring(), most);
    }

    /** The crouch before a pounce (front legs up) or a drop (it gathers itself at the edge). */
    private void crouch(LivingEntity t, FoeMove m) {
        int ticks = FoeRules.scaled((int) Math.round(FoeRules.POUNCE_WINDUP * quick()), Foes.windupScale());
        if (m == FoeMove.DROP) ticks = Math.max(3, ticks - 2);
        mob.getNavigation().stop();
        face(t);
        landed = false;
        to(m, FoeMove.Phase.WINDUP, ticks);
        Foes.tell(mob, m, ticks);
    }

    private void leaping(LivingEntity t) {
        switch (phase) {
            case WINDUP -> {
                FoeTurns.take(mob, t, Foes.strikers());
                mob.getNavigation().stop();
                face(t);
                if (--left <= 0) leap(t);
            }
            case STRIKE -> {
                if (++air == 1) aimAgain();
                if (!landed && mob.getBoundingBox().inflate(0.2).intersects(t.getBoundingBox()) && mob.getSensing().hasLineOfSight(t)) {
                    landed = true;
                    mob.doHurtTarget(t);
                }
                boolean down = air > 2 && (mob.onGround() || mob.isInWater() || mob.onClimbable());
                if (down || air >= FoeRules.POUNCE_FLIGHT) to(move, FoeMove.Phase.RECOVER, landed ? LANDED : MISSED);
            }
            case RECOVER -> {
                mob.getNavigation().stop();
                if (--left <= 0) {
                    FoeTurns.give(mob, t);
                    pounceCool = FoeRules.POUNCE_COOL_MIN + mob.getRandom().nextInt(FoeRules.POUNCE_COOL_MAX - FoeRules.POUNCE_COOL_MIN + 1);
                    cool = Math.max(cool, 8);
                    to(move, FoeMove.Phase.IDLE, 0);
                    move = FoeMove.NONE;
                }
            }
            default -> {}
        }
    }

    /** Off it goes, at where its quarry is now: lifted, and fast enough along to come down on it (with the air's drag). */
    private void leap(LivingEntity t) {
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = flat < 1.0E-4 ? Vec3.directionFromRotation(0.0F, mob.getYRot()) : new Vec3(dx / flat, 0.0, dz / flat);
        double lift = move == FoeMove.DROP ? 0.12 : FoeRules.pounceLift(flat);
        // onto its quarry, not past it
        double to = Math.max(0.0, flat - (mob.getBbWidth() + t.getBbWidth()) * 0.35);
        double along = FoeRules.leapSpeed(to, t.getY() - mob.getY(), lift, Foes.firstDrag(mob));
        mob.setDeltaMovement(dir.x * along, lift, dir.z * along);
        mob.hasImpulse = true;
        aimX = mob.getX() + dir.x * to;
        aimY = t.getY();
        aimZ = mob.getZ() + dir.z * to;
        air = 0;
        to(move, FoeMove.Phase.STRIKE, FoeRules.POUNCE_FLIGHT);
        mob.playSound(SoundEvents.SPIDER_STEP, 0.9F, cave ? 1.6F : 1.2F);
    }

    /**
     * Its first tick off the ground: set again for where it was to come down, from where it is now (a spider letting go
     * of a wall is held to a crawl that first tick, and the ground under it may have held it more or less than reckoned).
     * Not at where its quarry has gone since: a leap seen coming can be stepped out of.
     */
    private void aimAgain() {
        Vec3 dm = mob.getDeltaMovement();
        double dx = aimX - mob.getX(), dz = aimZ - mob.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.05 || dx * dm.x + dz * dm.z < 0.0) return;
        double along = FoeRules.leapSpeed(flat, aimY - mob.getY(), dm.y, FoeRules.AIR_DRAG);
        mob.setDeltaMovement(dx / flat * along, dm.y, dz / flat * along);
    }

    @Override
    protected void drop() {
        super.drop();
        air = 0;
    }
}
