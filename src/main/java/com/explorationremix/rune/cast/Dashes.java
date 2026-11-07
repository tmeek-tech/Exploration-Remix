package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;

/**
 * Dash: throws the caster along the aim (faster with Swift, with a hop off the ground), once per jump while in the
 * air, and no fall damage from the throw. What it carries goes off at the first creature or wall the caster runs into
 * within {@value #HIT_TICKS} ticks, or where they are when that ends (On hit), or a moment after the cast (After a
 * moment).
 */
final class Dashes {
    static final int HIT_TICKS = 10;

    /** One dash under way. */
    static final class Dash {
        final CastContext ctx;
        final Plan.Node node;
        final Vec3 dir;
        final long start;
        boolean released;

        Dash(CastContext ctx, Plan.Node node, Vec3 dir, long start) {
            this.ctx = ctx;
            this.node = node;
            this.dir = dir;
            this.start = start;
        }

        boolean hasPayload() { return node.relay != null && !node.children.isEmpty(); }
    }

    private Dashes() {}

    /** Whether the caster could dash now: on the ground, or not yet dashed since they left it. */
    static boolean canDash(ServerPlayer p, Caster c) { return p.onGround() || !c.airDashUsed || p.isInWater(); }

    static void start(CastContext ctx, Plan.Node node, Origin o) {
        ServerPlayer p = ctx.owner;
        Caster c = CastService.caster(p);
        if (!canDash(p, c)) {
            Executor.fizzle(ctx, p.position().add(0, 1, 0));
            return;
        }
        if (!p.onGround() && !p.isInWater()) c.airDashUsed = true;
        Vec3 dir = Origin.turn(o.dir(), node.yaw);
        double speed = 1.2 + 0.4 * node.act.count(RuneId.SWIFT);
        Vec3 v = dir.scale(speed);
        if (p.onGround()) v = v.add(0, 0.2, 0);
        p.setDeltaMovement(v);
        p.hurtMarked = true;
        p.fallDistance = 0;
        long now = ctx.level.getGameTime();
        c.dashes.add(new Dash(ctx, node, dir, now));
        Vec3 at = p.position().add(0, 0.6, 0);
        ctx.level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 6, 0.3, 0.2, 0.3, 0.02);
        RuneFx.puff(ctx.level, at, node.act.rune, node.act.element, 8, 0.3);
        RuneFx.play(ctx.level, at, SoundEvents.PHANTOM_FLAP, 0.8F, 1.6F);
    }

    /** Each tick of the caster's: no fall damage while thrown, and each dash's payload when its moment comes. */
    static void tick(ServerPlayer p, Caster c, long now) {
        if (p.onGround() || p.isInWater()) c.airDashUsed = false;
        for (Iterator<Dash> it = c.dashes.iterator(); it.hasNext(); ) {
            Dash d = it.next();
            long age = now - d.start;
            if (Executor.gone(d.ctx) || age > 40) {
                it.remove();
                continue;
            }
            if (age <= HIT_TICKS + 2) p.fallDistance = 0;
            if (d.released || !d.hasPayload()) {
                if (age > HIT_TICKS + 2) it.remove();
                continue;
            }
            Vec3 mid = p.position().add(0, p.getBbHeight() * 0.5, 0);
            if (d.node.relay == RuneId.AFTER) {
                if (age > FlyingRune.AFTER_TICKS) release(d, new Origin(mid, d.dir, null));
                continue;
            }
            // On hit: the first creature they run into, or a wall
            RuneHits.Touch hit = null;
            for (RuneHits.Touch t : RuneHits.touched(d.ctx, p.getBoundingBox().inflate(0.6), mid)) {
                if (hit == null || t.distSqr() < hit.distSqr()) hit = t;
            }
            if (hit != null) {
                release(d, new Origin(RuneHits.middle(hit.box()), d.dir, hit.body()));
            } else if (p.horizontalCollision && age > 0) {
                // off the wall they ran into, the payload faces back out
                Vec3 f = Origin.flat(d.dir);
                Vec3 out = f == Vec3.ZERO ? d.dir.reverse() : Origin.reflect(d.dir, f.reverse());
                release(d, new Origin(mid.add(f.scale(0.3)), out, null));
            } else if (age >= HIT_TICKS) {
                release(d, new Origin(mid, d.dir, null));
            }
        }
    }

    private static void release(Dash d, Origin o) {
        d.released = true;
        Executor.release(d.ctx, d.node.children, o);
    }
}
