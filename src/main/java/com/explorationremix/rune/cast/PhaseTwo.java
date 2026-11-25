package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the sword actives do in their second phase (awake while the caster's XP level is the rune's level or more), where
 * it is more than a number: Spark forks, Orb pulls and bursts, Burst's shockwave, Cleave's wave, Field's mire and Dash's
 * charge. Shard's hail is in FlyingRune, Barrier's bulwark and Reel's long line in Instants and Grapples, and the bow
 * actives' second phases in BowRunes. What a second phase makes strikes under its own number, so it may strike what the
 * rune itself struck (a shockwave hits what the burst hit).
 */
final class PhaseTwo {
    /** The second phases' own strike numbers (Act.slot): one rune each, as far as "strikes once per cast" goes. */
    static final int SHOCKWAVE = 9, FORK = 10, WAVE = 11, ORB_BURST = 12, DASH_SLAM = 13;

    /** Spark: how far a fork leaps, how many, and how hard. */
    static final double FORK_REACH = 7.0;
    static final int FORKS = 2;
    static final float FORK_DAMAGE = 0.6F;
    /** Orb: how far it pulls, how hard, and its burst. */
    static final double WELL_REACH = 5.0, WELL_PULL = 0.11, ORB_BURST_RADIUS = 3.0;
    static final float ORB_BURST_DAMAGE = 0.6F;
    /** Burst: the shockwave rolls out from the blast's edge to this many times its radius, a stretch a tick, this strong. */
    static final double SHOCKWAVE_REACH = 2.6;
    static final int SHOCKWAVE_TICKS = 8;
    static final float SHOCKWAVE_DAMAGE = 0.5F, SHOCKWAVE_THROW = 0.55F, SHOCKWAVE_LIFT = 0.5F;
    /** Cleave: the wave's stretch a tick, its stretches, and its strength. */
    static final double WAVE_STEP = 1.25;
    static final int WAVE_STEPS = 8;
    static final float WAVE_DAMAGE = 0.7F;
    /** Field's mire: how long its hold on a creature lasts once it steps out, and how slow it holds them (Slowness). */
    static final int MIRE_TICKS = 12, MIRE_SLOWNESS = 3;
    /** Dash: the charge's hit and throw. */
    static final float CHARGE_DAMAGE = 5.0F, CHARGE_PUSH = 1.1F;

    private PhaseTwo() {}

    // ------------------------------------------------------------------------------------------------ Spark: fork

    /** A spark in its second phase struck {@code struck}: it leaps on to the nearest foes, in sight, a beam to each. */
    static void fork(CastContext ctx, Act a, LivingEntity struck, float damage) {
        Act f = a.plain(FORK);
        ctx.firstStrike(f, struck);
        Vec3 from = RuneHits.middle(struck);
        List<LivingEntity> near = new ArrayList<>(ctx.level.getEntitiesOfClass(LivingEntity.class, struck.getBoundingBox().inflate(FORK_REACH),
                e -> e != struck && RuneHits.isFoe(ctx.owner, e)));
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(struck)));
        int made = 0;
        int colour = RuneFx.colour(a.rune, a.element);
        for (LivingEntity t : near) {
            if (made >= FORKS) break;
            Vec3 to = RuneHits.middle(t);
            if (to.distanceTo(from) > FORK_REACH || !RuneHits.clear(ctx.level, from, to, ctx.owner) || !ctx.firstStrike(f, t)) continue;
            RuneFx.beam(ctx.level, from, to, colour, a.element);
            RuneHits.strike(ctx, f, t, t, to, to.subtract(from), damage * FORK_DAMAGE, 0.25F, null, from);
            made++;
        }
        if (made > 0) RuneFx.play(ctx.level, from, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F, 1.9F);
    }

    // ------------------------------------------------------------------------------------------------ Orb: gravity

    /** An orb in its second phase, in flight at {@code pos}: it drags the foes near it in toward it. */
    static void gravity(CastContext ctx, Act a, Vec3 pos, int age) {
        double reach = WELL_REACH + a.count(RuneId.WIDE);
        for (LivingEntity e : ctx.level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(reach), e -> RuneHits.isFoe(ctx.owner, e))) {
            Vec3 to = pos.subtract(RuneHits.middle(e));
            double d = to.length();
            if (d > reach || d < 0.6) continue;
            drag(e, to.scale(1.0 / d), WELL_PULL * (1.0 - 0.5 * d / reach));
        }
        if (age % 4 == 1) RuneFx.well(ctx.level, pos, (float) reach, RuneFx.colour(a.rune, a.element), a.element);
    }

    /** An orb in its second phase stopped: it bursts. */
    static void orbBurst(CastContext ctx, Act a, Vec3 at) {
        double r = ORB_BURST_RADIUS + a.count(RuneId.WIDE);
        Instants.blast(ctx, a.withSlot(ORB_BURST), at, r, FlyingRune.damage(ctx, a) * ORB_BURST_DAMAGE, 0.8F, false);
    }

    // ------------------------------------------------------------------------------------------------ Burst: shockwave

    /**
     * A burst in its second phase went off round {@code c}, {@code r} across: a ring of force rolls out from its edge
     * along the ground, a stretch a tick, striking each creature it reaches once and throwing it up and away.
     */
    static void shockwave(CastContext ctx, Act a, Vec3 c, double r, float base) {
        Act s = a.withSlot(SHOCKWAVE);
        double reach = r * SHOCKWAVE_REACH;
        RuneFx.shockwave(ctx.level, c, (float) (r * 0.6), (float) reach, RuneFx.colour(a.rune, a.element), a.element);
        RuneFx.play(ctx.level, c, SoundEvents.WARDEN_SONIC_BOOM, 0.25F, 1.7F);
        roll(ctx, s, c, r * 0.6, reach, base * SHOCKWAVE_DAMAGE, 0);
    }

    private static void roll(CastContext ctx, Act s, Vec3 c, double from, double reach, float damage, int k) {
        if (Executor.gone(ctx) || k >= SHOCKWAVE_TICKS) return;
        double in = from + (reach - from) * k / SHOCKWAVE_TICKS, out = from + (reach - from) * (k + 1) / SHOCKWAVE_TICKS;
        AABB box = new AABB(c.x - out, c.y - 2.0, c.z - out, c.x + out, c.y + 2.5, c.z + out);
        for (RuneHits.Touch t : RuneHits.touched(ctx, box, c)) {
            LivingEntity e = t.body();
            double d = Math.hypot(e.getX() - c.x, e.getZ() - c.z);
            // it rolls along the ground: what stands near the blast's height, not what flies over it
            if (d < in - 0.5 || d > out + e.getBbWidth() * 0.5 || e.getY() > c.y + 1.5 || e.getY() + e.getBbHeight() < c.y - 2.0) continue;
            if (!ctx.firstStrike(s, e)) continue;
            Vec3 m = RuneHits.middle(t.box());
            Vec3 away = Origin.flat(m.subtract(c));
            if (away == Vec3.ZERO) away = new Vec3(1, 0, 0);
            RuneHits.strike(ctx, s, e, t.box(), m, away, damage, SHOCKWAVE_THROW, null, c);
            // off their feet: a throw up as well as away (bosses and the knockback-proof keep theirs)
            if (!e.getType().is(Tags.EntityTypes.BOSSES)) {
                double resist = e.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE) ? e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
                double lift = SHOCKWAVE_LIFT * Math.max(0.0, 1.0 - resist);
                if (lift > 0.05) {
                    e.setDeltaMovement(e.getDeltaMovement().x, Math.max(e.getDeltaMovement().y, lift), e.getDeltaMovement().z);
                    e.hurtMarked = true;
                }
            }
        }
        CastService.later(ctx.owner, 1, () -> roll(ctx, s, c, from, reach, damage, k + 1));
    }

    // ------------------------------------------------------------------------------------------------ Cleave: wave

    /** A cleave in its second phase: its slash flies on along {@code flat} as a wave, a stretch a tick, until a wall. */
    static void wave(CastContext ctx, Act a, Vec3 from, Vec3 flat, double halfWidth, float damage) {
        Act w = a.withSlot(WAVE);
        Vec3 start = from.add(flat.scale(1.5)).add(0, -0.5, 0);
        step(ctx, w, start, flat, halfWidth, damage, 0);
        RuneFx.play(ctx.level, start, SoundEvents.TRIDENT_RIPTIDE_2, 0.6F, 1.6F);
    }

    private static void step(CastContext ctx, Act w, Vec3 at, Vec3 flat, double halfWidth, float damage, int k) {
        if (Executor.gone(ctx) || k >= WAVE_STEPS) return;
        ServerLevel l = ctx.level;
        Vec3 next = at.add(flat.scale(WAVE_STEP));
        BlockHitResult bh = FlyingRune.clip(l, at, next, ctx.owner);
        boolean wall = bh.getType() != HitResult.Type.MISS;
        Vec3 end = wall ? bh.getLocation() : next;
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        AABB box = new AABB(at, end).inflate(halfWidth + 0.5, 1.4, halfWidth + 0.5);
        for (RuneHits.Touch t : RuneHits.touched(ctx, box, end)) {
            Vec3 m = RuneHits.middle(t.box());
            Vec3 rel = m.subtract(at);
            double along = rel.dot(flat), across = Math.abs(rel.dot(side));
            if (along < -0.6 || along > WAVE_STEP + 0.6 || across > halfWidth + t.body().getBbWidth() * 0.5 || Math.abs(rel.y) > 1.6) continue;
            if (!ctx.firstStrike(w, t.body())) continue;
            RuneHits.strike(ctx, w, t.body(), t.box(), m, flat, damage, 0.5F, null, at);
        }
        RuneFx.wave(l, end, flat, (float) halfWidth, RuneFx.colour(w.rune, w.element), w.element);
        if (wall) {
            RuneFx.impact(l, end, flat.scale(-1), RuneId.CLEAVE, w.element, 1.0F, false);
            return;
        }
        CastService.later(ctx.owner, 1, () -> step(ctx, w, end, flat, halfWidth, damage, k + 1));
    }

    // ------------------------------------------------------------------------------------------------ Field: mire

    /** A field in its second phase holds the creatures standing in it, round its middle {@code c}, to a crawl. */
    static void mire(CastContext ctx, Act a, Vec3 c, double radius, int age) {
        AABB box = new AABB(c.x - radius, c.y - 1.0, c.z - radius, c.x + radius, c.y + 2.0, c.z + radius);
        for (RuneHits.Touch t : RuneHits.touched(ctx, box, c)) {
            LivingEntity e = t.body();
            if (Math.hypot(e.getX() - c.x, e.getZ() - c.z) > radius + e.getBbWidth() * 0.5 || e.getY() > c.y + 1.2) continue;
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MIRE_TICKS, MIRE_SLOWNESS, false, false, false), ctx.owner);
            if (e.getDeltaMovement().y > 0.0 && e.onGround()) e.setDeltaMovement(e.getDeltaMovement().multiply(0.5, 0.3, 0.5));
        }
        if (age % 6 == 1) RuneFx.well(ctx.level, c.add(0, 0.15, 0), (float) radius, RuneFx.colour(a.rune, a.element), a.element);
    }

    /**
     * Pulls a creature along {@code way} (unit length) by {@code strength} a tick: knockback resistance holds it back,
     * bosses don't budge, and its own walking stops while it is drawn.
     */
    static void drag(LivingEntity e, Vec3 way, double strength) {
        if (e.getType().is(Tags.EntityTypes.BOSSES)) return;
        double resist = e.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE) ? e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
        double s = strength * Math.max(0.0, 1.0 - resist);
        if (s <= 0.0) return;
        Vec3 v = e.getDeltaMovement().add(way.x * s, way.y * s * 0.5, way.z * s);
        double h = Math.sqrt(v.x * v.x + v.z * v.z), most = 0.6;
        if (h > most) v = new Vec3(v.x * most / h, v.y, v.z * most / h);
        e.setDeltaMovement(v);
        e.hurtMarked = true;
        if (e instanceof Mob m) m.getNavigation().stop();
    }

    // ------------------------------------------------------------------------------------------------ Dash: charge

    /** A dash in its second phase: the caster slams through each creature they run into, once each. */
    static void charge(CastContext ctx, Act a, Vec3 dir) {
        Act s = a.withSlot(DASH_SLAM);
        LivingEntity p = ctx.owner;
        Vec3 mid = p.position().add(0, p.getBbHeight() * 0.5, 0);
        for (RuneHits.Touch t : RuneHits.touched(ctx, p.getBoundingBox().inflate(0.7), mid)) {
            if (!ctx.firstStrike(s, t.body())) continue;
            Vec3 m = RuneHits.middle(t.box());
            RuneHits.strike(ctx, s, t.body(), t.box(), m, dir, CHARGE_DAMAGE * ctx.power, CHARGE_PUSH, null, mid);
            RuneFx.impact(ctx.level, m, dir.scale(-1), RuneId.DASH, a.element, 1.2F, true);
        }
    }
}
