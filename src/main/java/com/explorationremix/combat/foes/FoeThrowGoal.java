package com.explorationremix.combat.foes;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;

/**
 * A witch's fight: it keeps between {@link FoeRules#WITCH_NEAR} and {@link FoeRules#WITCH_FAR} of its quarry (backing off
 * one that comes close, stepping to the sides), and each throw winds up first ({@link FoeRules#WITCH_WINDUP}: its
 * cackle, a glint, the bottle drawn back over its shoulder), every {@link FoeRules#WITCH_INTERVAL} ticks as vanilla's.
 * The throw is vanilla's own (which potion, and where). A raised guard caught just as a bottle strikes sends it back
 * at the witch (combat.guard).
 */
final class FoeThrowGoal extends FoeFight {
    private final Witch witch;
    private int throwCool = 20, sideTicks;
    private float side = 1.0F;

    FoeThrowGoal(Witch witch) {
        super(witch, 1.0);
        this.witch = witch;
    }

    /** Any quarry it can see within throwing range, or find a way to. */
    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (!valid(t)) return false;
        if (mob.getSensing().hasLineOfSight(t) && mob.distanceToSqr(t) < FoeRules.WITCH_FAR * FoeRules.WITCH_FAR * 1.5) return true;
        return super.canUse();
    }

    @Override
    protected void fight(LivingEntity t) {
        double d = Math.sqrt(mob.distanceToSqr(t));
        boolean sees = mob.getSensing().hasLineOfSight(t);
        switch (phase) {
            case WINDUP -> {
                mob.getNavigation().stop();
                face(t);
                if (--left <= 0) {
                    if (sees && !witch.isDrinkingPotion()) witch.performRangedAttack(t, 1.0F);
                    to(FoeMove.THROW, FoeMove.Phase.RECOVER, 6);
                    throwCool = FoeRules.WITCH_INTERVAL - FoeRules.WITCH_WINDUP;
                }
                return;
            }
            case RECOVER -> {
                mob.getNavigation().stop();
                if (--left <= 0) {
                    to(FoeMove.THROW, FoeMove.Phase.IDLE, 0);
                    move = FoeMove.NONE;
                }
                return;
            }
            default -> {}
        }
        if (throwCool > 0) throwCool--;
        if (throwCool <= 0 && sees && d <= FoeRules.WITCH_FAR + 2.0 && !witch.isDrinkingPotion()) {
            int ticks = FoeRules.scaled(FoeRules.WITCH_WINDUP, Foes.windupScale());
            mob.getNavigation().stop();
            face(t);
            to(FoeMove.THROW, FoeMove.Phase.WINDUP, ticks);
            Foes.tell(mob, FoeMove.THROW, ticks);
            return;
        }
        if (!sees || d > FoeRules.WITCH_FAR) {
            chase(t, 1.0);
            return;
        }
        mob.getNavigation().stop();
        if (--sideTicks <= 0) {
            sideTicks = 25 + mob.getRandom().nextInt(30);
            side = mob.getRandom().nextFloat() < 0.3F ? 0.0F : mob.getRandom().nextBoolean() ? 1.0F : -1.0F;
        }
        // one that comes near is backed away from, as an archer does
        mob.getMoveControl().strafe(d < FoeRules.WITCH_NEAR ? -0.7F : 0.0F, side * 0.4F);
        face(t);
    }
}
