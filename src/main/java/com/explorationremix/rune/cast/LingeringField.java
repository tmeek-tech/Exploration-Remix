package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.FieldLogic;
import com.explorationremix.rune.effect.RuneField;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A Field: a patch on the ground, {@value #LIFE} ticks long, that pulses every {@value #PULSE} ticks into every creature
 * standing in it (its damage, and above all its element: a Tide field soaks a group for a Storm to shock). It lets go
 * what it carries at the first creature that steps in (On hit), or at its middle a moment after it was cast (After a
 * moment); if neither came, when it ends.
 */
final class LingeringField implements FieldLogic {
    static final int LIFE = 60, PULSE = 10;

    private final CastContext ctx;
    private final Plan.Node node;
    private final Act a;
    private final Caster caster;
    private final float radius, damage;
    private int age;
    private boolean released, done;

    private LingeringField(CastContext ctx, Plan.Node node, Caster caster, float radius) {
        this.ctx = ctx;
        this.node = node;
        this.a = node.act;
        this.caster = caster;
        this.radius = radius;
        this.damage = FlyingRune.damage(ctx, a);
    }

    static void place(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        Caster c = CastService.caster(ctx.owner);
        if (c.carriers.size() >= RuneConfig.get(RuneConfig.INSTANCE.maxCarriers)) {
            Executor.fizzle(ctx, o.pos());
            return;
        }
        Vec3 at = root ? aimed(ctx) : ground(ctx.level, o.pos(), 4.0, ctx);
        float radius = 2.0F + a(node).count(RuneId.WIDE);
        LingeringField logic = new LingeringField(ctx, node, c, radius);
        RuneField f = new RuneField(RuneRegistry.FIELD.get(), ctx.level);
        f.setPos(at.x, at.y + 0.05, at.z);
        f.start(logic, node.act.element, radius, LIFE);
        if (ctx.level.addFreshEntity(f)) {
            c.carriers.add(f);
            RuneFx.ring(ctx.level, at.add(0, 0.1, 0), radius, node.act.rune, node.act.element);
            RuneFx.play(ctx.level, at, RuneFx.sound(node.act.rune, node.act.element), 0.6F, 1.3F);
            if (node.act.element != Element.NONE) RuneHits.world(ctx, node.act.element, at.add(0, 0.5, 0), radius);
        }
    }

    private static Act a(Plan.Node n) { return n.act; }

    /** Where the caster aims, on the ground: the floor they look at within 12 blocks, or below a point 6 ahead. */
    private static Vec3 aimed(CastContext ctx) {
        Vec3 eye = ctx.owner.getEyePosition(), look = ctx.owner.getLookAngle();
        BlockHitResult bh = ctx.level.clip(new ClipContext(eye, eye.add(look.scale(12)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, ctx.owner));
        if (bh.getType() != HitResult.Type.MISS) {
            Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
            Vec3 p = bh.getLocation().add(n.scale(0.2));
            return n.y > 0.5 ? bh.getLocation() : ground(ctx.level, p, 6.0, ctx);
        }
        return ground(ctx.level, eye.add(look.scale(6)), 6.0, ctx);
    }

    /** The floor under a point (within {@code depth} blocks), or the point itself if there is none. */
    static Vec3 ground(ServerLevel level, Vec3 p, double depth, CastContext ctx) {
        BlockHitResult bh = level.clip(new ClipContext(p, p.add(0, -depth, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, ctx.owner));
        return bh.getType() == HitResult.Type.MISS ? p : bh.getLocation();
    }

    private boolean hasPayload() { return node.relay != null && !node.children.isEmpty(); }

    @Override
    public void tick(RuneField f) {
        if (done || Executor.gone(ctx)) {
            done = true;
            f.discard();
            return;
        }
        age++;
        Vec3 c = f.position();
        AABB box = new AABB(c.x - radius, c.y - 0.5, c.z - radius, c.x + radius, c.y + 2.5, c.z + radius);
        boolean pulse = (age - 1) % PULSE == 0;
        if (pulse || (hasPayload() && !released && node.relay == RuneId.ON_HIT)) {
            for (RuneHits.Touch t : RuneHits.touched(ctx, box, c)) {
                Vec3 at = t.body().position();
                double dx = at.x - c.x, dz = at.z - c.z, reach = radius + t.body().getBbWidth() * 0.5;
                if (dx * dx + dz * dz > reach * reach) continue;
                if (hasPayload() && !released && node.relay == RuneId.ON_HIT) {
                    released = true;
                    Executor.release(ctx, node.children, new Origin(RuneHits.middle(t.box()), ctx.owner.getLookAngle(), t.body()));
                }
                if (pulse && ctx.firstPulse(a, t.body(), ctx.level.getGameTime())) {
                    Vec3 dir = new Vec3(dx, 0, dz);
                    RuneHits.strike(ctx, a, t.body(), t.box(), RuneHits.middle(t.box()), dir, damage, 0.05F, null, c);
                }
            }
            if (pulse) RuneFx.ring(ctx.level, c.add(0, 0.1, 0), radius * 0.85, a.rune, a.element);
        }
        if (hasPayload() && !released && node.relay == RuneId.AFTER && age > FlyingRune.AFTER_TICKS) {
            released = true;
            Executor.release(ctx, node.children, new Origin(c.add(0, 0.5, 0), ctx.owner.getLookAngle(), null));
        }
        if (age >= LIFE) {
            if (hasPayload() && !released) {
                released = true;
                Executor.release(ctx, node.children, new Origin(c.add(0, 0.5, 0), ctx.owner.getLookAngle(), null));
            }
            done = true;
            f.discard();
        }
    }

    @Override
    public void removed(RuneField f) {
        done = true;
        caster.carriers.remove(f);
    }
}
