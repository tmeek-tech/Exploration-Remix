package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.BoltLogic;
import com.explorationremix.rune.effect.RuneBolt;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A Spark, Shard, Orb or Glow mote in flight. Each tick it steers (Seek), falls (Arc), and sweeps the stretch it flies
 * through for creatures (its own width wide) and walls:
 * <ul>
 *   <li>a creature: struck (once per cast by this rune: a Fan's other copies pass on through it), then the bolt stops,
 *   or with Pierce carries on through;</li>
 *   <li>a wall: with Bounce it glances off, otherwise it stops there;</li>
 *   <li>old age: it stops where it has got to.</li>
 * </ul>
 * Where it stops it lets go what it carries (On hit), or earlier, a moment after it was cast (After a moment). Off a
 * wall the payload faces back out (and a Reel there pulls the caster to the wall); through a creature it carries on
 * and its bolts pass that creature by. A Glow mote leaves a light where it stops, or sticks to the creature it hits,
 * which then glows for a minute.
 */
final class FlyingRune implements BoltLogic {
    static final int AFTER_TICKS = 12;
    /** How long a Glow mote stuck on a creature makes it glow. */
    static final int GLOW_TAG_TICKS = 20 * 60;

    private final CastContext ctx;
    private final Plan.Node node;
    private final Act a;
    private final Caster caster;
    private Vec3 vel;
    private final double radius, gravity;
    private final float damage, push;
    private final int life, seek;
    private int bounces, pierces, age;
    private boolean done;
    /** Creatures it has passed (pierced, glanced off, or the one it was let go at). */
    private final Set<Integer> passed = new HashSet<>();
    @Nullable
    private LivingEntity target;

    private FlyingRune(CastContext ctx, Plan.Node node, Caster caster, Vec3 vel, double radius, double gravity, float damage, float push,
                       int life) {
        this.ctx = ctx;
        this.node = node;
        this.a = node.act;
        this.caster = caster;
        this.vel = vel;
        this.radius = radius;
        this.gravity = gravity;
        this.damage = damage;
        this.push = push;
        this.life = life;
        this.seek = a.count(RuneId.SEEK);
        this.bounces = 3 * a.count(RuneId.BOUNCE);
        this.pierces = 2 * a.count(RuneId.PIERCE);
    }

    // ------------------------------------------------------------------------------------------------ numbers

    static double speed(RuneId r) {
        return switch (r) {
            case SHARD -> 2.0;
            case ORB -> 0.8;
            case GLOW -> 0.55;
            default -> 1.5;
        };
    }

    static int life(RuneId r) {
        return switch (r) {
            case SHARD -> 25;
            case ORB -> 70;
            case GLOW -> 50;
            default -> 40;
        };
    }

    static double radius(RuneId r) {
        return switch (r) {
            case SHARD -> 0.2;
            case ORB -> 0.45;
            case GLOW -> 0.3;
            default -> 0.25;
        };
    }

    static float size(RuneId r) {
        return switch (r) {
            case SHARD -> 0.8F;
            case ORB -> 1.6F;
            default -> 1.0F;
        };
    }

    static float push(RuneId r) {
        return switch (r) {
            case SHARD -> 0.15F;
            case ORB -> 0.7F;
            case GLOW -> 0.0F;
            default -> 0.3F;
        };
    }

    /** A rune's damage on this gear: its base, the gear's power, and Heavy. */
    static float damage(CastContext ctx, Act a) { return a.rune.damage * ctx.power * (1.0F + 0.35F * a.count(RuneId.HEAVY)); }

    // ------------------------------------------------------------------------------------------------ launch

    static void launch(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        Caster c = CastService.caster(ctx.owner);
        if (c.carriers.size() >= RuneConfig.get(RuneConfig.INSTANCE.maxCarriers) || !ctx.level.isPositionEntityTicking(BlockPos.containing(o.pos()))) {
            Executor.fizzle(ctx, o.pos());
            return;
        }
        Act a = node.act;
        Vec3 dir = Origin.turn(o.dir(), node.yaw);
        int arc = a.count(RuneId.ARC);
        if (arc > 0) dir = tilt(dir, 12.0F * arc);
        Vec3 start = o.pos();
        if (root) {
            // a little in front of the eyes and below the crosshair, never inside a wall
            Vec3 want = start.add(dir.scale(0.6)).add(0, -0.12, 0);
            BlockHitResult bh = clip(ctx.level, start, want, ctx.owner);
            start = bh.getType() == HitResult.Type.MISS ? want : bh.getLocation().subtract(dir.scale(0.05));
        }
        int swift = a.count(RuneId.SWIFT), heavy = a.count(RuneId.HEAVY), wide = a.count(RuneId.WIDE);
        double speed = speed(a.rune) * Math.pow(1.3, swift) * Math.pow(0.85, heavy);
        double radius = radius(a.rune) + 0.25 * wide;
        float size = size(a.rune) + 0.5F * wide;
        float push = push(a.rune) + (a.rune == RuneId.GLOW ? 0.0F : 0.2F * heavy);
        FlyingRune f = new FlyingRune(ctx, node, c, dir.scale(speed), radius, 0.035 * arc, a.rune.damaging() ? damage(ctx, a) : 0.0F, push,
                life(a.rune));
        LivingEntity skip = RuneHits.body(o.hit());
        if (skip != null) f.passed.add(skip.getId());
        RuneBolt b = new RuneBolt(RuneRegistry.BOLT.get(), ctx.level);
        b.setPos(start);
        b.setDeltaMovement(f.vel);
        f.face(b);
        b.start(f, a.rune, a.element, size);
        if (ctx.level.addFreshEntity(b)) c.carriers.add(b);
    }

    /** {@code dir} tipped up by {@code degrees} (Arc's lob), never past nearly straight up. */
    static Vec3 tilt(Vec3 dir, float degrees) {
        double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (h < 1.0E-4) return dir;
        double pitch = Math.min(Math.toRadians(85), Math.atan2(dir.y, h) + Math.toRadians(degrees));
        return new Vec3(dir.x / h * Math.cos(pitch), Math.sin(pitch), dir.z / h * Math.cos(pitch));
    }

    static BlockHitResult clip(ServerLevel level, Vec3 from, Vec3 to, Entity by) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, by));
    }

    // ------------------------------------------------------------------------------------------------ flight

    private Vec3 dir() { return vel.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : vel.normalize(); }

    private boolean hasPayload() { return node.relay != null && !node.children.isEmpty(); }

    @Override
    public void tick(RuneBolt b) {
        if (done || Executor.gone(ctx)) {
            done = true;
            b.discard();
            return;
        }
        age++;
        Vec3 pos = b.position();
        if ((node.relay == RuneId.AFTER && hasPayload() && age > AFTER_TICKS) || age > life) {
            stop(b, pos, dir(), null, null);
            return;
        }
        steer(pos);
        if (gravity > 0) vel = vel.add(0, -gravity, 0);
        Vec3 from = pos, to = pos.add(vel);
        // never on into ground that is loaded but still (past the simulation distance): it would hang there, out
        if (!ctx.level.isPositionEntityTicking(BlockPos.containing(to))) {
            done = true;
            b.discard();
            return;
        }
        // a tick can meet several things: creatures it pierces or glances through, then whatever stops it
        for (int step = 0; step < 6; step++) {
            BlockHitResult bh = clip(ctx.level, from, to, b);
            Vec3 end = bh.getType() == HitResult.Type.MISS ? to : bh.getLocation();
            Hit h = firstCreature(b, from, end);
            if (h != null) {
                passed.add(h.body.getId());
                if (!ctx.firstStrike(a, h.body)) {
                    // this rune already struck it in this cast: the copy passes on through
                    ctx.level.sendParticles(RuneFx.dust(RuneFx.colour(a.rune, a.element), 0.6F), h.at.x, h.at.y, h.at.z, 2, 0.05, 0.05, 0.05, 0.0);
                    from = h.at;
                    continue;
                }
                if (a.rune.damaging()) RuneHits.strike(ctx, a, h.body, h.box, h.at, dir(), damage, push, b, null);
                if (pierces > 0 && a.rune != RuneId.GLOW && h.body.isAlive()) {
                    pierces--;
                    ctx.level.sendParticles(ParticleTypes.CRIT, h.at.x, h.at.y, h.at.z, 4, 0.1, 0.1, 0.1, 0.1);
                    from = h.at;
                    continue;
                }
                stop(b, h.at, dir(), h.body, null);
                return;
            }
            if (bh.getType() != HitResult.Type.MISS) {
                Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
                if (bounces > 0) {
                    bounces--;
                    vel = Origin.reflect(dir(), n).scale(vel.length() * 0.92);
                    Vec3 at = bh.getLocation().add(n.scale(0.06));
                    RuneFx.puff(ctx.level, at, a.rune, a.element, 3, 0.05);
                    RuneFx.play(ctx.level, at, SoundEvents.AMETHYST_BLOCK_HIT, 0.4F, 1.6F + 0.1F * bounces);
                    b.setPos(at);
                    b.setDeltaMovement(vel);
                    face(b);
                    return;
                }
                stop(b, bh.getLocation().add(n.scale(0.1)), Origin.reflect(dir(), n), null, bh);
                return;
            }
            b.setPos(end);
            b.setDeltaMovement(vel);
            face(b);
            return;
        }
        b.setPos(to);
        b.setDeltaMovement(vel);
        face(b);
    }

    @Override
    public void removed(RuneBolt b) {
        done = true;
        caster.carriers.remove(b);
    }

    private record Hit(LivingEntity body, Entity box, Vec3 at) {}

    /** The first creature its width meets between two points, not counting ones it has passed. */
    @Nullable
    private Hit firstCreature(RuneBolt b, Vec3 from, Vec3 end) {
        AABB sweep = new AABB(from, end).inflate(radius + 0.5);
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : ctx.level.getEntities(b, sweep, e -> RuneHits.canHit(ctx.owner, e))) {
            LivingEntity body = RuneHits.body(e);
            if (body == null || passed.contains(body.getId())) continue;
            AABB box = e.getBoundingBox().inflate(radius);
            Vec3 at;
            if (box.contains(from)) at = from;
            else {
                Optional<Vec3> o = box.clip(from, end);
                if (o.isEmpty()) continue;
                at = o.get();
            }
            double d = at.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = new Hit(body, e, at);
            }
        }
        return best;
    }

    /** Seek: bends toward the nearest creature fighting the caster in front of it, looking again every few ticks. */
    private void steer(Vec3 pos) {
        if (seek <= 0) return;
        if (target == null || !target.isAlive() || passed.contains(target.getId()) || age % 5 == 1) target = findTarget(ctx, pos, dir(), seek, passed);
        if (target == null) return;
        Vec3 to = target.getBoundingBox().getCenter().subtract(pos);
        if (to.lengthSqr() < 1.0E-6) return;
        Vec3 d = dir();
        Vec3 nd = d.add(to.normalize().subtract(d).scale(seek >= 2 ? 0.28 : 0.15)).normalize();
        vel = nd.scale(vel.length());
    }

    /** The creature a Seek rune bends toward: fighting the caster, ahead, in sight, within 10 blocks (16 with two). */
    @Nullable
    static LivingEntity findTarget(CastContext ctx, Vec3 pos, Vec3 dir, int seek, Set<Integer> skip) {
        double range = seek >= 2 ? 16.0 : 10.0;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : ctx.level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(range), e -> RuneHits.isFoe(ctx.owner, e))) {
            if (skip.contains(e.getId())) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 to = c.subtract(pos);
            double dist = to.length();
            if (dist > range || dist < 1.0E-3) continue;
            double dot = dir.dot(to.scale(1.0 / dist));
            if (dot < 0.2) continue;
            if (!RuneHits.clear(ctx.level, pos, c, ctx.owner)) continue;
            double score = dist * (2.0 - dot);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private void face(RuneBolt b) {
        double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        b.setYRot((float) (Mth.atan2(vel.x, vel.z) * Mth.RAD_TO_DEG));
        b.setXRot((float) (Mth.atan2(vel.y, h) * Mth.RAD_TO_DEG));
    }

    /** It stops: its look and sound, its element on the world round it, a Glow mote's light, and its payload. */
    private void stop(RuneBolt b, Vec3 at, Vec3 d, @Nullable LivingEntity hit, @Nullable BlockHitResult wall) {
        done = true;
        b.setPos(at);
        ServerLevel l = ctx.level;
        RuneFx.puff(l, at, a.rune, a.element, a.rune == RuneId.ORB ? 14 : 6, 0.12 + radius * 0.5);
        RuneFx.play(l, at, RuneFx.sound(a.rune, a.element), a.rune == RuneId.ORB ? 0.7F : 0.45F, 1.1F + l.random.nextFloat() * 0.3F);
        if (a.element != Element.NONE) RuneHits.world(ctx, a.element, at, 1.0 + 0.5 * a.count(RuneId.WIDE));
        if (a.rune == RuneId.GLOW) {
            if (hit != null) {
                // on a creature it sticks: it glows, through walls, for a minute
                hit.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TAG_TICKS, 0, false, false, true), ctx.owner);
            } else {
                BlockPos p = BlockPos.containing(at);
                if (!TempBlocks.place(l, p, TempBlock.Kind.LIGHT, ctx.owner)) TempBlocks.place(l, p.above(), TempBlock.Kind.LIGHT, ctx.owner);
            }
        }
        if (hasPayload()) Executor.release(ctx, node.children, new Origin(at, d, hit, wall != null));
        b.discard();
    }
}
