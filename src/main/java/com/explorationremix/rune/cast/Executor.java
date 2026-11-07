package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Plan;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Lets a cast's actions go: the roots when the gear casts, each carrier's payload when its relay's moment comes. Every
 * action goes through one queue per player, at most {@code releasesPerTick} a tick; the rest wait their turn (a few
 * ticks at most, since a cast holds at most {@code maxActions}). A queue that somehow grows past {@link #MAX_QUEUE}
 * lets the extra fizzle, so nothing can pile up without bound.
 */
public final class Executor {
    /** One action waiting to go: its cast, its copy in the plan, where, and whether it is a root (cast from the gear). */
    record Pending(CastContext ctx, Plan.Node node, Origin origin, boolean root) {}

    static final int MAX_QUEUE = 256;

    private Executor() {}

    /** The gear cast: its part 0 goes from the caster. */
    static void cast(CastContext ctx, List<Plan.Node> roots, Origin o) { enqueue(ctx, roots, o, true); }

    /** A carrier's moment came: what it carries goes from {@code o}. */
    public static void release(CastContext ctx, List<Plan.Node> nodes, Origin o) { enqueue(ctx, nodes, o, false); }

    private static void enqueue(CastContext ctx, List<Plan.Node> nodes, Origin o, boolean root) {
        if (nodes.isEmpty() || gone(ctx)) return;
        Caster c = CastService.caster(ctx.owner);
        for (Plan.Node n : nodes) {
            if (!c.queue.offer(new Pending(ctx, n, o, root), MAX_QUEUE)) fizzle(ctx, o.pos());
        }
        drain(c, ctx.level.getServer().getTickCount());
    }

    /** Lets go as many waiting actions as this tick allows. Never re-entered: actions let go while draining queue up. */
    static void drain(Caster c, long tick) {
        c.queue.drain(tick, RuneConfig.get(RuneConfig.INSTANCE.releasesPerTick), p -> {
            if (!gone(p.ctx())) fire(p);
        });
    }

    private static void fire(Pending p) {
        switch (p.node().act.rune.shape) {
            case BOLT -> FlyingRune.launch(p.ctx(), p.node(), p.origin(), p.root());
            case FIELD -> LingeringField.place(p.ctx(), p.node(), p.origin(), p.root());
            case SELF -> Dashes.start(p.ctx(), p.node(), p.origin());
            case INSTANT -> Instants.run(p.ctx(), p.node(), p.origin(), p.root());
        }
    }

    /** The caster has left, died or changed world: their runes do nothing more. */
    static boolean gone(CastContext ctx) {
        return ctx.owner.isRemoved() || !ctx.owner.isAlive() || ctx.owner.level() != ctx.level;
    }

    /** A payload that couldn't go (too many out): a wisp of smoke where it would have been. */
    static void fizzle(CastContext ctx, Vec3 at) {
        ctx.level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.01);
        RuneFx.play(ctx.level, at, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, 0.2F, 2.0F);
    }
}
