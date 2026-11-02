package com.explorationremix.creature.climb;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Moves a climbing creature straight at its waypoint, up walls and across ceilings as readily as over the floor. The
 * movement speed attribute is read as blocks per tick at a speed modifier of 1; climbing and swimming are a little
 * slower. On a floor gravity keeps its pull; elsewhere the creature's own surface tracking turns gravity off.
 */
public class ClimbMoveControl extends MoveControl {
    public interface Climber {
        /** True while the creature clings to a floor (gravity on), false on walls, ceilings or in the air. */
        boolean onFloor();
        /** False while something else (a lunge, a knock, a fall) drives the creature's motion. */
        boolean canSteer();
    }

    private final Climber climber;

    public <T extends Mob & Climber> ClimbMoveControl(T mob) {
        super(mob);
        this.climber = mob;
    }

    @Override
    public void tick() {
        if (this.operation != Operation.MOVE_TO || !climber.canSteer()) {
            mob.setSpeed(0.0F);
            mob.setZza(0.0F);
            if (this.operation == Operation.MOVE_TO && !climber.canSteer()) this.operation = Operation.WAIT;
            return;
        }
        this.operation = Operation.WAIT;
        double dx = this.wantedX - mob.getX(), dy = this.wantedY - mob.getY(), dz = this.wantedZ - mob.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        mob.setSpeed(0.0F);
        mob.setZza(0.0F);
        if (len < 1.0E-3) return;
        double speed = this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        boolean floor = climber.onFloor() && Math.abs(dy) < 0.6;
        if (!floor) speed *= 0.85;
        if (mob.isInWater()) speed *= 0.8;
        double step = Math.min(speed, len);
        Vec3 v = new Vec3(dx / len * step, dy / len * step, dz / len * step);
        if (floor) v = new Vec3(v.x, mob.getDeltaMovement().y, v.z);
        mob.setDeltaMovement(v);
        if (dx * dx + dz * dz > 1.0E-4) {
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            mob.setYRot(rotlerp(mob.getYRot(), yaw, 25.0F));
            mob.yBodyRot = mob.getYRot();
        }
    }
}
