package com.explorationremix.combat.foes;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * What the reworked mobs' fights share (0.23.6): getting to their quarry, waiting their turn on a ring round it
 * ({@link FoeTurns}) while others strike, holding their ground between blows, and the melee move itself: the wind-up
 * (told to every game, which poses the mob for it), the strike with its lunge, the blow once within reach, and the
 * recovery, longer after a miss. A mob that reels (its mind stops, see Stagger) loses the move it was making.
 */
abstract class FoeFight extends Goal implements Foes.FoeGoal {
    protected final PathfinderMob mob;
    protected final double speed;
    /** The move under way, and where in it ({@code IDLE}: none). */
    protected FoeMove move = FoeMove.NONE;
    protected FoeMove.Phase phase = FoeMove.Phase.IDLE;
    /** Ticks left in the phase. */
    protected int left;
    /** Ticks until the next melee move may start. */
    protected int cool;
    /** The blow of the strike under way has been dealt (or met a guard). */
    protected boolean landed;
    /** The quarry it fights now (its turn there is given back when it changes). */
    @Nullable protected LivingEntity quarry;

    @Nullable private FoeRules.Melee melee;
    private Vec3 lungeDir = Vec3.ZERO;
    private double lungeV;
    private long lastTick = Long.MIN_VALUE;
    /** When it last looked for a way to its quarry (far enough back at first that it looks at once, with no overflow). */
    private long lastCanUse = Long.MIN_VALUE / 4;
    private int repath, sideTicks;
    private float side = 1.0F;
    private double slotX = Double.NaN, slotZ = Double.NaN;

    FoeFight(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    // ------------------------------------------------------------------------------------------------ the goal

    /** Whether {@code t} is one this mob fights now, besides being alive and fair game (a drowned only by night or in water). */
    protected boolean fights(LivingEntity t) { return true; }

    protected final boolean valid(@Nullable LivingEntity t) { return t != null && Foes.fair(t) && fights(t); }

    /** As vanilla's melee goal: a target it can find a way to, or one already within reach; asked once a second. */
    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (!valid(t)) return false;
        long now = mob.level().getGameTime();
        if (now - lastCanUse < 20L) return false;
        lastCanUse = now;
        if (mob.getNavigation().createPath(t, 0) != null) return true;
        return mob.distanceToSqr(t) <= reachSqr(t) * 2.0;
    }

    /** While its quarry is fair game and it is in a move, can see it, or has a way to it. */
    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        if (!valid(t)) return false;
        if (phase != FoeMove.Phase.IDLE) return true;
        return mob.getSensing().hasLineOfSight(t) || !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.setAggressive(aggressive());
        repath = 0;
        lastTick = Long.MIN_VALUE;
        slotX = Double.NaN;
    }

    /** Whether it looks set to fight all along (a vindicator's axe out, a skeleton's bow up), not only in its moves. */
    protected boolean aggressive() { return true; }

    @Override
    public void stop() {
        LivingEntity t = mob.getTarget();
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(t)) mob.setTarget(null);
        if (quarry != null) FoeTurns.give(mob, quarry);
        quarry = null;
        if (phase != FoeMove.Phase.IDLE) Foes.phase(mob, move, FoeMove.Phase.IDLE, 0);
        phase = FoeMove.Phase.IDLE;
        move = FoeMove.NONE;
        melee = null;
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    /** Not in the middle of a blow: a goal that matters more (a creeper fleeing a cat, a skeleton the sun) may take over. */
    @Override
    public boolean isInterruptable() { return phase == FoeMove.Phase.IDLE || phase == FoeMove.Phase.RECOVER; }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public final void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        long now = mob.level().getGameTime();
        // its mind stopped for a while (it reeled): whatever it was in the middle of is gone
        boolean resumed = lastTick != Long.MIN_VALUE && now - lastTick > 2L;
        lastTick = now;
        if (quarry != t) {
            if (quarry != null) {
                FoeTurns.give(mob, quarry);
                if (phase != FoeMove.Phase.IDLE) drop();
            }
            quarry = t;
        }
        // a strong attack caught its wind-up (its stagger may not have taken, just after another)
        if (Foes.takeInterrupt(mob) || resumed) {
            if (phase != FoeMove.Phase.IDLE) drop();
        }
        if (cool > 0) cool--;
        fight(t);
    }

    /** One tick of the fight with {@code t}. */
    protected abstract void fight(LivingEntity t);

    /** The move under way is dropped: back to the fight after a breath, its turn given back. */
    protected void drop() {
        if (quarry != null) FoeTurns.give(mob, quarry);
        Foes.phase(mob, move, FoeMove.Phase.IDLE, 0);
        phase = FoeMove.Phase.IDLE;
        left = 0;
        melee = null;
        move = FoeMove.NONE;
        cool = Math.max(cool, 10);
        idle();
    }

    /** {@code move} is now in {@code phase}, for {@code ticks}: noted, and told to every game that sees the mob. */
    protected void to(FoeMove m, FoeMove.Phase p, int ticks) {
        move = m;
        phase = p;
        left = ticks;
        Foes.phase(mob, m, p, ticks);
    }

    /** Back out of its move (called once it has recovered, or the move was dropped). */
    protected void idle() {}

    // ------------------------------------------------------------------------------------------------ getting about

    /** After its quarry: a way to it found again every so often (less often the further it is), as vanilla's goal does. */
    protected void chase(LivingEntity t, double speedMul) {
        slotX = Double.NaN;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (--repath > 0) return;
        double d = mob.distanceToSqr(t);
        repath = 4 + mob.getRandom().nextInt(7) + (d > 1024.0 ? 10 : d > 256.0 ? 5 : 0);
        if (!mob.getNavigation().moveTo(t, speed * speedMul)) repath += 15;
    }

    /**
     * Waiting its turn: to its place on a ring {@code ring} blocks round its quarry, by its place among those waiting
     * (behind it first, then its flanks, see {@link FoeRules#slotDegrees}), facing it. A place it can't get to: it holds
     * where it is.
     */
    protected void circle(LivingEntity t, double ring) {
        int rank = FoeTurns.rank(mob, t);
        double[] p = FoeRules.slotPoint(t.getX(), t.getZ(), t.getYRot(), FoeRules.slotDegrees(rank), ring);
        double hx = mob.getX() - p[0], hz = mob.getZ() - p[1];
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (hx * hx + hz * hz < 0.8 * 0.8) {
            mob.getNavigation().stop();
            face(t);
            slotX = p[0];
            slotZ = p[1];
            return;
        }
        double moved = Double.isNaN(slotX) ? 99.0 : (p[0] - slotX) * (p[0] - slotX) + (p[1] - slotZ) * (p[1] - slotZ);
        if (--repath <= 0 || moved > 1.5 * 1.5) {
            slotX = p[0];
            slotZ = p[1];
            repath = 10 + mob.getRandom().nextInt(6);
            if (!mob.getNavigation().moveTo(p[0], t.getY(), p[1], speed * 0.9)) {
                repath += 10;
                mob.getNavigation().stop();
            }
        }
    }

    /**
     * Between blows, near its quarry: it stands its ground facing it, with a step to one side now and then, and a step
     * back when it is nearer than {@code near}.
     */
    protected void hold(LivingEntity t, double d, double near) {
        slotX = Double.NaN;
        mob.getNavigation().stop();
        if (--sideTicks <= 0) {
            sideTicks = 20 + mob.getRandom().nextInt(25);
            side = mob.getRandom().nextFloat() < 0.35F ? 0.0F : mob.getRandom().nextBoolean() ? 1.0F : -1.0F;
        }
        mob.getMoveControl().strafe(d < near ? -0.5F : 0.0F, side * 0.35F);
        face(t);
    }

    /** Turns its head and body to {@code t}. */
    protected void face(LivingEntity t) {
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        mob.lookAt(t, 30.0F, 30.0F);
    }

    /** Its quarry is about level with it (within a block and a half up or down): a lunge can carry to it. */
    protected boolean level(LivingEntity t) { return Math.abs(t.getY() - mob.getY()) < 1.5; }

    // ------------------------------------------------------------------------------------------------ melee moves

    /** Vanilla's reach (squared, middle to feet), a rider's from its mount's width. */
    protected double reachSqr(LivingEntity t) {
        if (mob.getVehicle() instanceof LivingEntity v) {
            float f = v.getBbWidth() - 0.1F;
            return f * 2.0F * f * 2.0F + t.getBbWidth();
        }
        return mob.getMeleeAttackRangeSqr(t);
    }

    /** Whether its blow reaches {@code t} now ({@code extra} blocks past vanilla's reach), with nothing in between. */
    protected boolean reaches(LivingEntity t, double extra) {
        double r = Math.sqrt(reachSqr(t)) + extra;
        return mob.getPerceivedTargetDistanceSquareForMeleeAttack(t) <= r * r && mob.getSensing().hasLineOfSight(t);
    }

    /**
     * A melee fight as zombies, endermen and vindicators fight: the move under way goes on; otherwise, near enough,
     * not cooling down and with a turn free, it winds up ({@code quick} shortens the wind-up); otherwise it closes in,
     * waits its turn, or holds its ground.
     */
    protected void meleeFight(LivingEntity t, FoeRules.Melee spec, double quick) {
        if (moving(t)) return;
        double d = Math.sqrt(mob.distanceToSqr(t));
        boolean sees = mob.getSensing().hasLineOfSight(t);
        int most = Foes.strikers();
        if (cool <= 0 && sees && d <= spec.start() && level(t) && FoeTurns.take(mob, t, most)) {
            windUp(t, spec, quick, 0);
            return;
        }
        approach(t, d, sees, spec.start(), spec.ring(), most);
    }

    /** How fast it closes in (times its walk): a skeleton with no bow comes on faster, as vanilla's does. */
    protected double chaseSpeed() { return 1.0; }

    /** Not in a move: after its quarry, round it waiting its turn, or holding its ground between blows. */
    protected void approach(LivingEntity t, double d, boolean sees, double start, double ring, int most) {
        if (FoeTurns.full(mob, t, most) && d < ring + 4.0) {
            circle(t, ring);
            return;
        }
        if (!sees || d > start - 0.3 || !level(t)) chase(t, chaseSpeed());
        else hold(t, d, Math.sqrt(reachSqr(t)) * 0.8);
    }

    /** The melee move under way goes on a tick: true if there is one. */
    protected boolean moving(LivingEntity t) {
        if (melee == null || phase == FoeMove.Phase.IDLE) return false;
        FoeRules.Melee spec = melee;
        switch (phase) {
            case WINDUP -> {
                FoeTurns.take(mob, t, Foes.strikers());
                mob.getNavigation().stop();
                face(t);
                if (--left <= 0) strike(t, spec);
            }
            case STRIKE -> {
                // within reach any tick of the strike, the last tick's lunge too: the blow lands
                if (!landed && reaches(t, 0.25)) {
                    landed = true;
                    blow(t, spec);
                }
                if (left <= 0) {
                    to(spec.move(), FoeMove.Phase.RECOVER, landed ? spec.recover() : spec.whiff());
                    recovering(t, spec, landed);
                    return true;
                }
                left--;
                if (lungeV > 0.0 && !mob.isPassenger()) {
                    Vec3 dm = mob.getDeltaMovement();
                    mob.setDeltaMovement(lungeDir.x * lungeV, dm.y, lungeDir.z * lungeV);
                }
            }
            case RECOVER -> {
                // it doesn't turn while it comes back from its blow: the opening
                mob.getNavigation().stop();
                if (--left <= 0) {
                    FoeTurns.give(mob, t);
                    cool = spec.cooldown(mob.getRandom().nextDouble());
                    to(spec.move(), FoeMove.Phase.IDLE, 0);
                    move = FoeMove.NONE;
                    melee = null;
                    idle();
                }
            }
            default -> {}
        }
        return true;
    }

    /** It winds up {@code spec}'s blow at {@code t}: {@code extra} ticks longer than the move's own. */
    protected void windUp(LivingEntity t, FoeRules.Melee spec, double quick, int extra) {
        melee = spec;
        landed = false;
        int ticks = FoeRules.scaled((int) Math.round(spec.windup() * quick), Foes.windupScale()) + extra;
        mob.getNavigation().stop();
        face(t);
        to(spec.move(), FoeMove.Phase.WINDUP, ticks);
        Foes.tell(mob, spec.move(), ticks);
        windingUp(t, spec);
    }

    /** The wind-up is over: it lunges at where {@code t} is now, far enough to bring it within reach (at most the move's lunge). */
    private void strike(LivingEntity t, FoeRules.Melee spec) {
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        lungeDir = flat < 1.0E-4 ? Vec3.directionFromRotation(0.0F, mob.getYRot()) : new Vec3(dx / flat, 0.0, dz / flat);
        double gap = Math.sqrt(mob.getPerceivedTargetDistanceSquareForMeleeAttack(t)) - Math.sqrt(reachSqr(t));
        lungeV = FoeRules.lungeSpeed(gap, spec.lunge(), spec.strike());
        to(spec.move(), FoeMove.Phase.STRIKE, spec.strike());
        mob.swing(InteractionHand.MAIN_HAND);
        striking(t, spec);
    }

    /** The blow lands on {@code t}: vanilla's (a raised sword or shield parries or blocks it there). */
    protected void blow(LivingEntity t, FoeRules.Melee spec) { mob.doHurtTarget(t); }

    /** Hooks for each kind: the wind-up has begun, the strike, the recovery. */
    protected void windingUp(LivingEntity t, FoeRules.Melee spec) {}

    protected void striking(LivingEntity t, FoeRules.Melee spec) {}

    protected void recovering(LivingEntity t, FoeRules.Melee spec, boolean hit) {}
}
