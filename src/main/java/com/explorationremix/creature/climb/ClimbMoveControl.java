package com.explorationremix.creature.climb;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Moves a climbing creature straight at its waypoint, up walls and across ceilings as readily as over the floor. The
 * movement speed attribute is read as blocks per tick at a speed modifier of 1; climbing and swimming are a little
 * slower. On a floor gravity keeps its pull; elsewhere the creature's own surface tracking turns gravity off, and
 * once it touches nothing at all it falls. A creature that barely moves for a few ticks when sent somewhere steps
 * clear of whatever holds it.
 */
public class ClimbMoveControl extends MoveControl {
    public interface Climber {
        /** True while the creature clings to a floor (gravity on), false on walls, ceilings or in the air. */
        boolean onFloor();
        /** False while something else (a lunge, a knock, a fall) drives the creature's motion. */
        boolean canSteer();
        /** True when, with nowhere to go, it should hold still where it clings (on a wall or ceiling nothing else stops it). */
        boolean holdStill();
        /** True once it has touched no rock (and no water) for a few ticks: it falls, steering only a little. */
        boolean airborne();
    }

    /** Ticks in a row that it was sent somewhere and barely moved, before it tries a way out. */
    static final int WEDGED_TICKS = 6;

    private final Climber climber;
    private double lastX, lastY, lastZ, lastStep;
    private int wedged;

    /** Ticks in a row it has barely moved while sent somewhere (for the dev listing). */
    public int wedged() { return wedged; }

    public <T extends Mob & Climber> ClimbMoveControl(T mob) {
        super(mob);
        this.climber = mob;
    }

    @Override
    public void tick() {
        if (this.operation != Operation.MOVE_TO || !climber.canSteer()) {
            mob.setSpeed(0.0F);
            mob.setZza(0.0F);
            lastStep = 0.0;
            wedged = 0;
            if (this.operation == Operation.MOVE_TO && !climber.canSteer()) this.operation = Operation.WAIT;
            // off the floor no friction stops it: without this it would coast on for blocks
            else if (climber.canSteer() && climber.holdStill()) mob.setDeltaMovement(Vec3.ZERO);
            return;
        }
        this.operation = Operation.WAIT;
        double dx = this.wantedX - mob.getX(), dy = this.wantedY - mob.getY(), dz = this.wantedZ - mob.getZ();
        // swimming it never dives for a waypoint below it: it heads across and lets the water settle it (it sinks
        // until it floats)
        boolean settle = mob.isInWater() && dy < 0.0;
        if (settle) dy = 0.0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        mob.setSpeed(0.0F);
        mob.setZza(0.0F);
        // sent somewhere last tick and hardly moved: it is wedged (its box caught by a hair on an edge, say)
        double moved = Math.sqrt(Mth.square(mob.getX() - lastX) + Mth.square(mob.getY() - lastY) + Mth.square(mob.getZ() - lastZ));
        wedged = lastStep > 0.02 && moved < 0.2 * lastStep ? wedged + 1 : 0;
        lastX = mob.getX(); lastY = mob.getY(); lastZ = mob.getZ(); lastStep = 0.0;
        if (len < 1.0E-3) return;
        double speed = this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        boolean floor = climber.onFloor() && Math.abs(dy) < 0.6;
        if (!floor) speed *= 0.85;
        if (mob.isInWater()) speed *= 0.8;
        // the navigation's stuck check and path timeout read the mob's speed; the input stays zero (the velocity is set
        // straight below), so travel adds nothing of its own
        mob.setSpeed((float) speed);
        mob.setZza(0.0F);
        double step = Math.min(speed, len);
        lastStep = step;
        // a small way off along one axis is made up at once, so a box a hair out of line lines up with its waypoint
        // (moving there in proportion would leave it caught on the edge it overhangs)
        Vec3 v = new Vec3(exact(dx, dx / len * step), exact(dy, dy / len * step), exact(dz, dz / len * step));
        if (climber.airborne()) {
            // touching nothing: gravity has it, and it steers only a little on the way down
            mob.setDeltaMovement(v.x * 0.5, mob.getDeltaMovement().y, v.z * 0.5);
            turn(dx, dz);
            return;
        }
        if (settle) v = new Vec3(v.x, mob.getDeltaMovement().y, v.z);
        // rounding the top of what it climbs: while the way across is still blocked by the edge, it climbs straight up
        // first (aiming straight at the waypoint would leave it hanging just under the edge)
        if (dy > -0.1 && v.horizontalDistanceSqr() > 1.0E-6
                && !mob.level().noCollision(mob, mob.getBoundingBox().move(v.x, 0.0, v.z).deflate(1.0E-4)))
            v = new Vec3(v.x * 0.1, speed, v.z * 0.1);
        else if (floor) v = new Vec3(v.x, mob.getDeltaMovement().y, v.z);
        if (wedged >= WEDGED_TICKS) {
            Vec3 out = wayOut(v, step);
            if (out != null) v = out;
            wedged = WEDGED_TICKS / 2;
        }
        mob.setDeltaMovement(v);
        turn(dx, dz);
    }

    private static double exact(double d, double share) {
        return Math.abs(d) < 0.1 && Math.abs(d) > Math.abs(share) ? d : share;
    }

    /**
     * Wedged: a short step that frees it, the clear one that loses least ground towards its waypoint (straight up, or
     * one of the four ways across); null when every one is blocked too.
     */
    private Vec3 wayOut(Vec3 v, double step) {
        double s = Math.max(0.12, step);
        Vec3[] tries = {new Vec3(0, s, 0), new Vec3(s, 0, 0), new Vec3(-s, 0, 0), new Vec3(0, 0, s), new Vec3(0, 0, -s)};
        Vec3 best = null;
        double bestDot = -Double.MAX_VALUE;
        for (Vec3 t : tries) {
            if (!mob.level().noCollision(mob, mob.getBoundingBox().move(t))) continue;
            double d = t.dot(v);
            if (d > bestDot) { bestDot = d; best = t; }
        }
        return best;
    }

    private void turn(double dx, double dz) {
        if (dx * dx + dz * dz > 1.0E-4) {
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            mob.setYRot(rotlerp(mob.getYRot(), yaw, 25.0F));
            mob.yBodyRot = mob.getYRot();
        }
    }
}
