package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the active runes do in their second phase (awake while the caster's XP level is the rune's level or more), where
 * it is more than a number: Spark forks, Shard splinters, Glow scatters, Orb pulls and bursts, Burst's aftershock,
 * Cleave's wave, Field's pull and Dash's charge. Barrier's bulwark and Reel's long line are in Instants and Grapples.
 * What a second phase makes is first phase (a splinter doesn't splinter) and strikes under its own number, so it may
 * strike what the rune itself struck (an aftershock hits what the burst hit).
 */
final class PhaseTwo {
    /** The second phases' own strike numbers (Act.slot): one rune each, as far as "strikes once per cast" goes. */
    static final int SPLINTER = 8, AFTERSHOCK = 9, FORK = 10, WAVE = 11, ORB_BURST = 12, DASH_SLAM = 13;

    /** Spark: how far a fork leaps, how many, and how hard. */
    static final double FORK_REACH = 7.0;
    static final int FORKS = 2;
    static final float FORK_DAMAGE = 0.6F;
    /** Shard: splinters, their spread either side, and their share of the shard's damage. */
    static final float SPLINTER_SPREAD = 22.0F, SPLINTER_SCALE = 0.5F;
    /** Glow: lights scattered round where it stops, how far they reach, and how far a mark spreads. */
    static final int SCATTER_LIGHTS = 5;
    static final double SCATTER_REACH = 10.0, MARK_REACH = 8.0;
    /** Orb: how far it pulls, how hard, and its burst. */
    static final double WELL_REACH = 5.0, WELL_PULL = 0.11, ORB_BURST_RADIUS = 3.0;
    static final float ORB_BURST_DAMAGE = 0.6F;
    /** Burst: the aftershock's wait, size and strength. */
    static final int AFTERSHOCK_TICKS = 10;
    static final double AFTERSHOCK_SIZE = 1.6;
    static final float AFTERSHOCK_DAMAGE = 0.7F;
    /** Cleave: the wave's stretch a tick, its stretches, and its strength. */
    static final double WAVE_STEP = 1.25;
    static final int WAVE_STEPS = 8;
    static final float WAVE_DAMAGE = 0.7F;
    /** Field: how hard it draws creatures in, and how far beyond its edge. */
    static final double DRAW = 0.05, DRAW_BEYOND = 1.5;
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

    // ------------------------------------------------------------------------------------------------ Shard: splinter

    /** A shard in its second phase stopped at {@code at}: three splinters fly on (through a creature, off a wall). */
    static void splinter(CastContext ctx, Act a, Vec3 at, Vec3 dir, @Nullable LivingEntity hit) {
        Act s = a.plain(SPLINTER);
        if (hit != null) ctx.firstStrike(s, hit);
        Origin o = new Origin(at, dir, hit);
        for (float yaw : new float[]{0.0F, -SPLINTER_SPREAD, SPLINTER_SPREAD}) {
            FlyingRune.launch(ctx, Plan.Node.single(s, yaw), o, false, SPLINTER_SCALE);
        }
        RuneFx.play(ctx.level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6F, 1.8F);
    }

    // ------------------------------------------------------------------------------------------------ Glow: scatter

    /** A glow mote in its second phase stopped: on rock, lights spray onto the rock round it; on a foe, its kin glow too. */
    static void scatter(CastContext ctx, Act a, Vec3 at, @Nullable LivingEntity hit) {
        ServerLevel l = ctx.level;
        int colour = RuneFx.colour(a.rune, a.element);
        if (hit != null) {
            for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, hit.getBoundingBox().inflate(MARK_REACH), e -> e != hit && RuneHits.isFoe(ctx.owner, e))) {
                if (e.distanceTo(hit) > MARK_REACH) continue;
                e.addEffect(new MobEffectInstance(MobEffects.GLOWING, FlyingRune.GLOW_TAG_TICKS, 0, false, false, true), ctx.owner);
                RuneFx.beam(l, RuneHits.middle(hit), RuneHits.middle(e), colour, a.element);
            }
            return;
        }
        List<BlockPos> lit = new ArrayList<>();
        lit.add(BlockPos.containing(at));
        // rays spread evenly over a sphere (a golden-angle spiral), each to the rock it meets
        int rays = 18;
        for (int i = 0; i < rays && lit.size() <= SCATTER_LIGHTS; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / rays, r = Math.sqrt(1.0 - y * y), t = i * 2.399963;
            Vec3 d = new Vec3(Math.cos(t) * r, y, Math.sin(t) * r);
            BlockHitResult bh = FlyingRune.clip(l, at, at.add(d.scale(SCATTER_REACH)), ctx.owner);
            if (bh.getType() == HitResult.Type.MISS) continue;
            BlockPos p = bh.getBlockPos().relative(bh.getDirection());
            boolean close = false;
            for (BlockPos q : lit) close |= q.distSqr(p) < 9.0;
            if (close || !TempBlocks.place(l, p, TempBlock.Kind.LIGHT, ctx.owner)) continue;
            lit.add(p);
            RuneFx.beam(l, at, Vec3.atCenterOf(p), colour, a.element);
        }
        if (lit.size() > 1) RuneFx.play(l, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8F, 1.4F);
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

    // ------------------------------------------------------------------------------------------------ Burst: aftershock

    /** A burst in its second phase went off round {@code c}: a wider, softer blast follows a moment later. */
    static void aftershock(CastContext ctx, Act a, Vec3 c, double r, float base) {
        Act s = a.withSlot(AFTERSHOCK);
        CastService.later(ctx.owner, AFTERSHOCK_TICKS, () -> {
            if (Executor.gone(ctx)) return;
            Instants.blast(ctx, s, c, r * AFTERSHOCK_SIZE, base * AFTERSHOCK_DAMAGE, 0.9F, false);
            RuneFx.play(ctx.level, c, SoundEvents.WARDEN_SONIC_BOOM, 0.25F, 1.7F);
        });
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

    // ------------------------------------------------------------------------------------------------ Field: pull

    /** A field in its second phase draws the creatures round it in to its middle {@code c}. */
    static void draw(CastContext ctx, Act a, Vec3 c, double radius, int age) {
        double reach = radius + DRAW_BEYOND;
        AABB box = new AABB(c.x - reach, c.y - 1.0, c.z - reach, c.x + reach, c.y + 3.0, c.z + reach);
        for (RuneHits.Touch t : RuneHits.touched(ctx, box, c)) {
            LivingEntity e = t.body();
            Vec3 to = new Vec3(c.x - e.getX(), 0, c.z - e.getZ());
            double d = to.length();
            if (d > reach || d < 0.5) continue;
            drag(e, to.scale(1.0 / d), DRAW);
        }
        if (age % 6 == 1) RuneFx.well(ctx.level, c.add(0, 0.15, 0), (float) reach, RuneFx.colour(a.rune, a.element), a.element);
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
