package com.explorationremix.combat.foes;

import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * A creeper's swell, as vanilla's (it lights within 3 blocks of its quarry, and lets its fuse fall back once it is 7
 * away or out of sight), but once lit it rushes the last blocks at its quarry, {@link FoeRules#CREEPER_RUSH} times its
 * walk, instead of standing still: backing off at a walk no longer saves you. Its hiss is the tell (with a glint at its
 * eyes, as every wind-up has); each game crouches and shakes it as it swells (client.foes reads the swell itself). A
 * strong attack while it swells staggers it and its fuse falls back, as any wind-up is stopped.
 */
final class FoeSwellGoal extends Goal implements Foes.FoeGoal {
    private final Creeper creeper;
    @Nullable private LivingEntity target;
    private boolean lit;
    private int repath;
    private long lastTick = Long.MIN_VALUE;

    FoeSwellGoal(Creeper creeper) {
        this.creeper = creeper;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = creeper.getTarget();
        return creeper.getSwellDir() > 0 || t != null && creeper.distanceToSqr(t) < 9.0;
    }

    @Override
    public void start() {
        creeper.getNavigation().stop();
        target = creeper.getTarget();
        lit = false;
        repath = 0;
        lastTick = Long.MIN_VALUE;
    }

    @Override
    public void stop() {
        target = null;
        unlit();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        long now = creeper.level().getGameTime();
        // it reeled meanwhile: its fuse fell back, and lighting it again is told again
        if (lastTick != Long.MIN_VALUE && now - lastTick > 2L) unlit();
        lastTick = now;
        if (target == null || !target.isAlive() || creeper.distanceToSqr(target) > 49.0 || !creeper.getSensing().hasLineOfSight(target)) {
            creeper.setSwellDir(-1);
            unlit();
            return;
        }
        creeper.setSwellDir(1);
        if (!lit) {
            lit = true;
            Foes.phase(creeper, FoeMove.SWELL, FoeMove.Phase.WINDUP, 30);
            if (Foes.glint() && creeper.level() instanceof ServerLevel l) RuneFx.windup(l, creeper.getEyePosition(), creeper.getViewVector(1.0F), 1.0F);
        }
        creeper.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (Math.sqrt(creeper.distanceToSqr(target)) > FoeRules.CREEPER_RUSH_FROM) {
            if (--repath <= 0) {
                repath = 5;
                creeper.getNavigation().moveTo(target, FoeRules.CREEPER_RUSH);
            }
        } else {
            creeper.getNavigation().stop();
        }
    }

    private void unlit() {
        if (!lit) return;
        lit = false;
        Foes.phase(creeper, FoeMove.SWELL, FoeMove.Phase.IDLE, 0);
        creeper.getNavigation().stop();
    }
}
