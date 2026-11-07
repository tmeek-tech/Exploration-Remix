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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The actions that happen on the spot and hand what follows on at once, there: Burst (a blast all round), Cleave (a
 * slash in front), Barrier (a wall of rune glass across the aim) and Reel (yanks a creature toward the caster). Cast
 * from the gear, a Burst goes off round the caster and a Barrier stands a few blocks ahead; let go by a carrier, both
 * happen where it was.
 */
final class Instants {
    private Instants() {}

    static void run(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        Act a = node.act;
        Vec3 dir = Origin.turn(o.dir(), node.yaw);
        Origin next = switch (a.rune) {
            case BURST -> burst(ctx, a, root ? ctx.owner.position().add(0, ctx.owner.getBbHeight() * 0.5, 0) : o.pos(), o);
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
        return new Origin(c, o.dir(), o.hit());
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
                BlockPos bp = BlockPos.containing(p.x, baseY + h, p.z);
                // never round a creature (or the caster): it would be stuck in the glass
                if (!l.getEntitiesOfClass(LivingEntity.class, new AABB(bp)).isEmpty()) continue;
                if (TempBlocks.place(l, bp, TempBlock.Kind.BARRIER, ctx.owner.getUUID())) made++;
            }
        }
        Vec3 mid = new Vec3(centre.x, baseY + height * 0.5, centre.z);
        if (made > 0) RuneFx.play(l, mid, SoundEvents.AMETHYST_BLOCK_PLACE, 1.0F, 0.8F);
        else Executor.fizzle(ctx, mid);
        RuneFx.puff(l, mid, a.rune, a.element, 10, width * 0.25);
        return new Origin(mid, flat, o.hit());
    }

    // ------------------------------------------------------------------------------------------------ Reel

    private static Origin reel(CastContext ctx, Act a, Origin o, Vec3 dir, boolean root) {
        ServerLevel l = ctx.level;
        int swift = a.count(RuneId.SWIFT);
        LivingEntity t = root ? aimedCreature(ctx, o.pos(), dir, 12.0 + 2 * swift) : reelTarget(ctx, o, 6.0 + 2 * swift);
        if (t == null) {
            Executor.fizzle(ctx, o.pos().add(dir.scale(2)));
            return o;
        }
        Vec3 from = RuneHits.middle(t);
        Vec3 home = ctx.owner.position();
        Vec3 to = home.subtract(t.position());
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        if (Statuses.holdable(t) || RuneHits.canHit(ctx.owner, t)) {
            double resist = t.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE) ? t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
            double k = Math.max(0.0, 1.0 - resist) * (1.0 + 0.25 * swift);
            if (k > 0 && !t.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES)) {
                Vec3 flat = Origin.flat(to);
                double h = Math.min(1.8, 0.2 + 0.13 * dist) * k;
                t.setDeltaMovement(flat.x * h, 0.32 + 0.02 * Math.min(dist, 12.0), flat.z * h);
                t.hurtMarked = true;
                if (t instanceof Mob m) m.getNavigation().stop();
            }
        }
        RuneFx.line(l, ctx.owner.getEyePosition().add(0, -0.3, 0), from, RuneFx.dust(RuneFx.colour(a.rune, a.element), 0.8F), 0.35);
        RuneFx.play(l, from, SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0F, 0.8F);
        return new Origin(from, dir, t);
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
