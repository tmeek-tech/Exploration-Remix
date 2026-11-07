package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import javax.annotation.Nullable;

/**
 * The actions that happen on the spot and hand what follows on at once, there: Burst (a blast all round), Cleave (a
 * slash in front), Barrier (a wall of rune glass across the aim) and Reel (yanks a creature toward the caster, or the
 * caster to a wall). Cast from the gear, a Burst goes off on the block aimed at when it is close (the ground at your
 * feet: a blast jump), else round the caster, and a Barrier stands a few blocks ahead; let go by a carrier, both happen
 * where it was. A blast of your own never hurts you but does throw you.
 */
final class Instants {
    /** How close the block aimed at must be for a Burst cast from the gear to go off on it. */
    static final double BLAST_REACH = 2.2;
    /** How far a Reel let go at a wall can pull its caster. */
    static final double GRAPPLE_RANGE = 24.0;
    /** How far a Reel cast from the gear reaches. */
    static final double REEL_REACH = 16.0;
    /** How long after a blast of your own landing can't hurt you (if no lower than you were). */
    static final int SAFE_TICKS = 30;

    private Instants() {}

    static void run(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        Act a = node.act;
        Vec3 dir = Origin.turn(o.dir(), node.yaw);
        Origin next = switch (a.rune) {
            case BURST -> burst(ctx, a, root ? burstCentre(ctx, dir) : o.pos(), o);
            case CLEAVE -> cleave(ctx, a, o.pos(), dir, o);
            case BARRIER -> barrier(ctx, a, o.pos(), dir, root, o);
            case REEL -> reel(ctx, a, o, dir, root);
            default -> o;
        };
        if (node.relay != null && !node.children.isEmpty()) Executor.release(ctx, node.children, next);
    }

    // ------------------------------------------------------------------------------------------------ Burst

    private static Origin burst(CastContext ctx, Act a, Vec3 c, Origin o) {
        ServerLevel l = ctx.level;
        int wide = a.count(RuneId.WIDE), heavy = a.count(RuneId.HEAVY);
        double r = 2.5 + wide;
        float base = FlyingRune.damage(ctx, a), push = 0.5F + 0.3F * heavy;
        for (RuneHits.Touch t : RuneHits.touched(ctx, new AABB(c, c).inflate(r), c)) {
            double d = Math.sqrt(t.distSqr());
            if (d > r) continue;
            Vec3 m = RuneHits.middle(t.box());
            if (!RuneHits.clear(l, c, m, ctx.owner)) continue;
            if (!ctx.firstStrike(a, t.body())) continue;
            float falloff = (float) (1.0 - 0.4 * Math.min(1.0, d / r));
            RuneHits.strike(ctx, a, t.body(), t.box(), m, m.subtract(c), base * falloff, push, null, c);
        }
        l.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        RuneFx.ring(l, c, r, a.rune, a.element);
        RuneFx.puff(l, c, a.rune, a.element, 16 + 6 * wide, r * 0.35);
        RuneFx.play(l, c, RuneFx.sound(a.rune, a.element), 0.9F, 0.9F + l.random.nextFloat() * 0.2F);
        if (a.element != Element.NONE) RuneHits.world(ctx, a.element, c, r);
        blastSelf(ctx, c, r, heavy);
        return new Origin(c, o.dir(), o.hit(), o.wall());
    }

    /**
     * Where a Burst cast from the gear goes off: on the block aimed at when it is within reach (the ground at your feet,
     * a wall you stand by), at your feet when you look down in the air, else round you.
     */
    private static Vec3 burstCentre(CastContext ctx, Vec3 dir) {
        ServerPlayer p = ctx.owner;
        Vec3 eye = p.getEyePosition();
        BlockHitResult bh = FlyingRune.clip(ctx.level, eye, eye.add(dir.scale(BLAST_REACH)), p);
        if (bh.getType() != HitResult.Type.MISS) return bh.getLocation().subtract(dir.scale(0.1));
        if (dir.y < -0.8) return p.position();
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

    /**
     * A blast of your own throws you away from it, harder the closer it is: one under you throws you up, and breaks a
     * fall just before landing. One throw a tick however many blasts, and one while off the ground until you land.
     */
    private static void blastSelf(CastContext ctx, Vec3 c, double r, int heavy) {
        ServerPlayer p = ctx.owner;
        Vec3 mid = p.position().add(0, p.getBbHeight() * 0.5, 0);
        Vec3 away = mid.subtract(c);
        double d = away.length();
        if (d < 0.3 || d > r || !RuneHits.clear(ctx.level, c, mid, p)) return;
        Caster cs = CastService.caster(p);
        long now = ctx.level.getGameTime();
        boolean air = !p.onGround() && !p.isInWater();
        if (cs.blastTick == now || (air && cs.airBlastUsed)) return;
        cs.blastTick = now;
        if (air) cs.airBlastUsed = true;
        Vec3 u = away.scale(1.0 / d);
        double s = (0.85 + 0.2 * heavy) * (1.0 - 0.5 * d / r);
        Vec3 v = p.getDeltaMovement();
        CastService.launch(p, new Vec3(v.x * 0.3 + u.x * s, Math.max(0.0, v.y) * 0.3 + u.y * s + 0.1, v.z * 0.3 + u.z * s));
        p.fallDistance = 0.0F;
        // coming back down to about where they were doesn't hurt; a fall on past that counts from there
        cs.safeUntil = now + SAFE_TICKS;
        cs.safeAboveY = p.getY() - 1.5;
    }

    // ------------------------------------------------------------------------------------------------ Cleave

    private static Origin cleave(CastContext ctx, Act a, Vec3 from, Vec3 dir, Origin o) {
        ServerLevel l = ctx.level;
        int wide = a.count(RuneId.WIDE), heavy = a.count(RuneId.HEAVY);
        double range = 3.5 + wide;
        double cosHalf = Math.cos(Math.toRadians(Math.min(170.0, 55.0 + 15.0 * wide)));
        Vec3 flat = Origin.flat(dir);
        if (flat == Vec3.ZERO) flat = Origin.flat(ctx.owner.getLookAngle());
        float dmg = FlyingRune.damage(ctx, a), push = 0.4F + 0.2F * heavy;
        LivingEntity first = null;
        for (RuneHits.Touch t : RuneHits.touched(ctx, new AABB(from, from).inflate(range), from)) {
            Vec3 m = RuneHits.middle(t.box());
            Vec3 to = m.subtract(from);
            if (Math.sqrt(t.distSqr()) > range || Math.abs(to.y) > 2.5) continue;
            Vec3 tf = Origin.flat(to);
            if (tf != Vec3.ZERO && tf.dot(flat) < cosHalf && t.distSqr() > 0.5) continue;
            if (!RuneHits.clear(l, from, m, ctx.owner)) continue;
            if (!ctx.firstStrike(a, t.body())) continue;
            RuneHits.strike(ctx, a, t.body(), t.box(), m, flat, dmg, push, null, from);
            if (first == null) first = t.body();
        }
        // the slash: sweeps along its arc
        double half = Math.acos(cosHalf);
        int n = 5 + 2 * wide;
        for (int i = 0; i < n; i++) {
            double ang = -half + 2 * half * i / (n - 1);
            Vec3 d = Origin.turn(flat, (float) Math.toDegrees(ang));
            Vec3 p = from.add(d.scale(range * 0.65)).add(0, -0.3, 0);
            l.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            l.sendParticles(RuneFx.dust(RuneFx.colour(a.rune, a.element), 1.0F), p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0);
        }
        RuneFx.play(l, from, a.element == Element.NONE ? SoundEvents.PLAYER_ATTACK_SWEEP : RuneFx.sound(a.rune, a.element), 0.9F, 1.0F);
        if (a.element != Element.NONE) RuneHits.world(ctx, a.element, from.add(flat.scale(range * 0.5)), range * 0.5);
        return new Origin(from.add(flat.scale(1.5)), flat, first != null ? first : o.hit());
    }

    // ------------------------------------------------------------------------------------------------ Barrier

    private static Origin barrier(CastContext ctx, Act a, Vec3 at, Vec3 dir, boolean root, Origin o) {
        ServerLevel l = ctx.level;
        int wide = a.count(RuneId.WIDE);
        int width = 3 + 2 * wide, height = 2 + wide;
        Vec3 flat = Origin.flat(dir);
        if (flat == Vec3.ZERO) flat = Origin.flat(ctx.owner.getLookAngle());
        if (flat == Vec3.ZERO) flat = new Vec3(0, 0, 1);
        Vec3 right = new Vec3(-flat.z, 0, flat.x);
        Vec3 centre;
        int baseY;
        if (root) {
            centre = ctx.owner.position().add(flat.scale(2.5));
            baseY = Mth.floor(ctx.owner.getY() + 0.01);
        } else {
            centre = at;
            baseY = Mth.floor(at.y - (height - 1) * 0.5);
        }
        int made = 0;
        for (int i = 0; i < width; i++) {
            double off = i - (width - 1) * 0.5;
            Vec3 p = centre.add(right.scale(off));
            for (int h = 0; h < height; h++) {
                // never round a creature (or the caster), nor over a frame or painting: TempBlocks.place
                if (TempBlocks.place(l, BlockPos.containing(p.x, baseY + h, p.z), TempBlock.Kind.BARRIER, ctx.owner)) made++;
            }
        }
        Vec3 mid = new Vec3(centre.x, baseY + height * 0.5, centre.z);
        if (made > 0) RuneFx.play(l, mid, SoundEvents.AMETHYST_BLOCK_PLACE, 1.0F, 0.8F);
        else Executor.fizzle(ctx, mid);
        RuneFx.puff(l, mid, a.rune, a.element, 10, width * 0.25);
        return new Origin(mid, flat, o.hit());
    }

    // ------------------------------------------------------------------------------------------------ Reel

    /**
     * Reel. Cast from the gear it yanks the creature aimed at toward you, or, aimed at a wall nearer than any creature,
     * pulls you to the wall. Let go by a carrier that stopped against a wall it pulls you to that wall; otherwise it
     * yanks the creature just hit, or the nearest one in sight.
     */
    private static Origin reel(CastContext ctx, Act a, Origin o, Vec3 dir, boolean root) {
        int swift = a.count(RuneId.SWIFT);
        if (root) {
            double range = REEL_REACH + 2 * swift;
            BlockHitResult bh = FlyingRune.clip(ctx.level, o.pos(), o.pos().add(dir.scale(range)), ctx.owner);
            boolean wall = bh.getType() != HitResult.Type.MISS;
            LivingEntity t = aimedCreature(ctx, o.pos(), dir, wall ? Math.min(range, bh.getLocation().distanceTo(o.pos()) + 0.5) : range);
            if (t != null) return yank(ctx, a, t, dir, swift);
            if (wall) return grapple(ctx, a, bh.getLocation().subtract(dir.scale(0.3)), dir, swift, o);
        } else if (o.wall()) {
            return grapple(ctx, a, o.pos(), dir, swift, o);
        } else {
            LivingEntity t = reelTarget(ctx, o, 6.0 + 2 * swift);
            if (t != null) return yank(ctx, a, t, dir, swift);
        }
        Executor.fizzle(ctx, o.pos().add(dir.scale(2)));
        return o;
    }

    /** Yanks a creature toward the caster: knockback resistance holds it back, and bosses don't budge. */
    private static Origin yank(CastContext ctx, Act a, LivingEntity t, Vec3 dir, int swift) {
        Vec3 from = RuneHits.middle(t);
        Vec3 to = ctx.owner.position().subtract(t.position());
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        double resist = t.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE) ? t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
        double k = Math.max(0.0, 1.0 - resist) * (1.0 + 0.25 * swift);
        if (k > 0 && !t.getType().is(Tags.EntityTypes.BOSSES)) {
            Vec3 flat = Origin.flat(to);
            double h = Math.min(1.8, 0.2 + 0.13 * dist) * k;
            t.setDeltaMovement(flat.x * h, 0.32 + 0.02 * Math.min(dist, 12.0), flat.z * h);
            t.hurtMarked = true;
            if (t instanceof Mob m) m.getNavigation().stop();
        }
        RuneFx.line(ctx.level, ctx.owner.getEyePosition().add(0, -0.3, 0), from, RuneFx.dust(RuneFx.colour(a.rune, a.element), 0.8F), 0.35);
        RuneFx.play(ctx.level, from, SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0F, 0.8F);
        return new Origin(from, dir, t);
    }

    /**
     * Pulls the caster to a point on a wall, in sight and within reach ({@link Grapples}): over onto its top when that
     * is within reach above the point, else to hang there a moment.
     */
    private static Origin grapple(CastContext ctx, Act a, Vec3 at, Vec3 dir, int swift, Origin o) {
        ServerPlayer p = ctx.owner;
        Vec3 chest = p.getEyePosition().add(0, -0.3, 0);
        double dist = at.distanceTo(chest);
        Vec3 back = dist < 1.0E-6 ? Vec3.ZERO : chest.subtract(at).scale(0.3 / dist);
        int colour = RuneFx.colour(a.rune, a.element);
        if (!RuneHits.clear(ctx.level, chest, at.add(back), p) || !Grapples.start(p, at, swift, colour)) {
            Executor.fizzle(ctx, at);
            return o;
        }
        RuneFx.line(ctx.level, chest, at, RuneFx.dust(colour, 0.8F), 0.35);
        RuneFx.puff(ctx.level, at, a.rune, a.element, 6, 0.15);
        RuneFx.play(ctx.level, chest, SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0F, 1.2F);
        return new Origin(at, dir, null, true);
    }

    /** The creature nearest the aim line, in sight, within {@code range}. */
    @Nullable
    private static LivingEntity aimedCreature(CastContext ctx, Vec3 eye, Vec3 dir, double range) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (RuneHits.Touch t : RuneHits.touched(ctx, new AABB(eye, eye).inflate(range), eye)) {
            Vec3 m = RuneHits.middle(t.box());
            Vec3 to = m.subtract(eye);
            double d = to.length();
            if (d > range || d < 1.0E-3) continue;
            double dot = dir.dot(to.scale(1.0 / d));
            if (dot < 0.9) continue;
            if (!RuneHits.clear(ctx.level, eye, m, ctx.owner)) continue;
            double score = d * (1.0 + 4.0 * (1.0 - dot));
            if (score < bestScore) {
                bestScore = score;
                best = t.body();
            }
        }
        return best;
    }

    /** What a carrier's Reel yanks: the creature it just hit, else the nearest in sight within {@code range}. */
    @Nullable
    private static LivingEntity reelTarget(CastContext ctx, Origin o, double range) {
        LivingEntity hit = RuneHits.body(o.hit());
        if (hit != null && RuneHits.canHit(ctx.owner, hit)) return hit;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (RuneHits.Touch t : RuneHits.touched(ctx, new AABB(o.pos(), o.pos()).inflate(range), o.pos())) {
            if (t.distSqr() > range * range || t.distSqr() >= bestD) continue;
            if (!RuneHits.clear(ctx.level, o.pos(), RuneHits.middle(t.box()), ctx.owner)) continue;
            bestD = t.distSqr();
            best = t.body();
        }
        return best;
    }
}
