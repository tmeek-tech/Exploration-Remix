package com.explorationremix.rune.cast;

import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneGrammar;
import com.explorationremix.rune.RuneIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One effect about to run, with every modifier in front of it already folded into numbers. */
public final class EffectContext {
    public final ServerPlayer player;
    public final ServerLevel level;
    public final ItemStack tool;
    public final Rune effect;
    public final RuneGrammar.Sentence sentence;
    @Nullable public final LivingEntity target;
    public final Vec3 origin;
    public final Vec3 direction;
    public final int repeats;
    public final double areaScale;
    public final int chainJumps;
    public final double chainRange;
    public final int delayTicks;
    public final boolean quiet;
    public final int heavy;
    public final double force;
    public final double poise;

    public EffectContext(ServerPlayer player, ItemStack tool, RuneGrammar.Sentence sentence, RuneGrammar.Clause clause, TriggerContext trigger) {
        this.player = player;
        this.level = player.serverLevel();
        this.tool = tool;
        this.effect = clause.effect();
        this.sentence = sentence;
        this.target = trigger.target();
        this.origin = trigger.origin();
        this.direction = trigger.direction();
        int repeats = 1; double area = 1.0; int jumps = 0; double range = 0; int delay = 0; boolean quiet = false; int heavy = 0; double force = 1.0; double poise = 0;
        for (Rune m : clause.modifiers()) {
            switch (m.id().getPath()) {
                case RuneIds.TWICE -> repeats *= (int) Math.max(1, m.param("repeats", 2));
                case RuneIds.WIDE -> area *= m.param("scale", 1.8);
                case RuneIds.CHAIN -> { jumps += (int) m.param("jumps", 3); range = Math.max(range, m.param("range", 6)); }
                case RuneIds.DELAYED -> delay += (int) m.param("ticks", 15);
                case RuneIds.QUIET -> quiet = true;
                case RuneIds.HEAVY -> { heavy++; force *= m.param("force", 1.75); poise += m.param("poise", 12); }
                default -> {}
            }
        }
        this.repeats = Math.min(repeats, 64);
        this.areaScale = Math.min(area, 12);
        this.chainJumps = Math.min(jumps, 24);
        this.chainRange = range;
        this.delayTicks = Math.min(delay, 200);
        this.quiet = quiet;
        this.heavy = heavy;
        this.force = force;
        this.poise = poise;
    }

    public double param(String key, double fallback) { return effect.param(key, fallback); }

    /** The creatures an effect acts on: the trigger's target if there was one, otherwise everything near the origin. */
    public List<LivingEntity> targets(double radius) {
        if (target != null && target.isAlive()) return List.of(target);
        return nearby(origin, radius, null);
    }

    public List<LivingEntity> nearby(Vec3 center, double radius, @Nullable LivingEntity exclude) {
        AABB box = new AABB(center, center).inflate(radius);
        List<LivingEntity> list = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e != exclude && e.isAlive() && !e.isSpectator() && e.distanceToSqr(center) <= radius * radius));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(center)));
        return list;
    }
}
