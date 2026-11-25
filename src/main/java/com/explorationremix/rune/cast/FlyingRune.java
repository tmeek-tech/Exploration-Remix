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
import net.minecraft.server.level.ServerLevel;
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
 * A Spark, an Orb or one of Shard's needles in flight. Each tick it steers (Seek), comes back (Return), and sweeps the
 * stretch it flies through for creatures (its own width wide) and walls:
 * <ul>
 *   <li>a creature: struck (once per cast by this rune: a Fan's other copies pass on through it), then the bolt stops,
 *   or with Pierce carries on through;</li>
 *   <li>a wall: with Bounce it glances off, otherwise it stops there;</li>
 *   <li>old age: it stops where it has got to.</li>
 * </ul>
 * Where it stops it flashes and throws sparks ({@link RuneFx#impact}), and lets go what it carries (On hit). Off a
 * wall the payload faces back out (and a Reel there pulls the caster to the wall); through a creature it carries on
 * and its bolts pass that creature by.
 *
 * <p>Shard throws a spray of needles in a short cone, each a bolt of its own that strikes for itself (so up close
 * all of them land in one foe), harder the closer it is; only the middle needle carries the payload. With Return a
 * bolt flies out, turns where it would stop (a creature, a wall, or half its range) and comes back to its caster,
 * passing through what it meets on the way back and striking it again; it lets its payload go where it turned. In
 * their second phase a Spark forks to the foes near what it strikes, Shard's hail staggers a foe four needles strike,
 * and an Orb drags foes in as it flies and bursts where it stops (PhaseTwo).
 */
final class FlyingRune implements BoltLogic {
    /** How long a flare stuck on a creature makes it glow (Glow, a bow's flare: Instants). */
    static final int GLOW_TAG_TICKS = 20 * 60;
    /** Shard: needles in its spray (more in its second phase), the cone's half width either way, and their own strike numbers. */
    static final int NEEDLES = 5, HAIL_NEEDLES = 7, NEEDLE_SLOT = 20;
    static final float CONE = 13.0F;
    /** Shard: within this many blocks a needle hits for all of its damage, past it less, down to {@link #NEEDLE_FAR} at its reach. */
    static final double NEEDLE_CLOSE = 3.0;
    static final float NEEDLE_FAR = 0.45F;
    /** Shard's hail: needles of one cast that must strike a foe to knock it reeling. */
    static final int HAIL_STAGGER = 4;
    /** Return: the share of its usual flight a bolt flies out before it turns, and the strike number of its way back. */
    static final double RETURN_OUT = 0.5;
    static final int RETURN_SLOT = 14;
    /** Return: how hard it turns back each tick, and how close to its caster it is caught. */
    static final double RETURN_TURN = 0.22, CAUGHT = 1.3;

    private final CastContext ctx;
    private final Plan.Node node;
    private final Act a;
    private final Caster caster;
    private Vec3 vel;
    private final double radius, speed;
    private final float damage, push;
    private final int life, seek, turnAt;
    private int bounces, pierces, age;
    private boolean done;
    /** Return: it has turned and comes back to its caster, its payload let go. */
    private boolean returning;
    /** Shard: which needle of the spray (-1 not a needle); only needle 0, the middle one, carries the payload. */
    private final int needle;
    /** It may let go its payload (a Volley's arrows of light carry none). */
    private final boolean carries;
    /** Where it set off, for a needle's falloff. */
    private Vec3 setOff = Vec3.ZERO;
    /** Creatures it has passed (pierced, glanced off, or the one it was let go at). */
    private final Set<Integer> passed = new HashSet<>();
    @Nullable
    private LivingEntity target;

    private FlyingRune(CastContext ctx, Plan.Node node, Act a, Caster caster, Vec3 vel, double radius, float damage, float push, int life,
                       int needle, boolean carries) {
        this.ctx = ctx;
        this.node = node;
        this.a = a;
        this.caster = caster;
        this.vel = vel;
        this.speed = vel.length();
        this.radius = radius;
        this.damage = damage;
        this.push = push;
        this.needle = needle;
        this.carries = carries && needle <= 0;
        this.seek = a.count(RuneId.SEEK);
        this.bounces = 3 * a.count(RuneId.BOUNCE);
        this.pierces = 2 * a.count(RuneId.PIERCE);
        // Return: out for half its flight, then back, with time to get there
        boolean ret = a.has(RuneId.ARC);
        this.turnAt = ret ? Math.max(3, (int) Math.round(life * RETURN_OUT)) : Integer.MAX_VALUE;
        this.life = ret ? life * 2 + 20 : life;
    }

    // ------------------------------------------------------------------------------------------------ numbers

    static double speed(RuneId r) {
        return switch (r) {
            case SHARD -> 1.7;
            case ORB -> 0.8;
            default -> 1.5;
        };
    }

    /** Ticks it flies: a needle only a few, so a spray reaches about nine blocks. */
    static int life(RuneId r) {
        return switch (r) {
            case SHARD -> 6;
            case ORB -> 70;
            default -> 40;
        };
    }

    static double radius(RuneId r) {
        return switch (r) {
            case SHARD -> 0.18;
            case ORB -> 0.45;
            default -> 0.25;
        };
    }

    static float size(RuneId r) {
        return switch (r) {
            case SHARD -> 0.65F;
            case ORB -> 1.6F;
            default -> 1.0F;
        };
    }

    /** How hard a bolt throws what it hits (an Orb hard enough to slam a creature into a wall close behind it). */
    static float push(RuneId r) {
        return switch (r) {
            case SHARD -> 0.3F;
            case ORB -> 1.1F;
            default -> 0.4F;
        };
    }

    /** A rune's damage on this gear: its base, the gear's power, and Heavy. */
    static float damage(CastContext ctx, Act a) { return a.rune.damage * ctx.power * (1.0F + 0.35F * a.count(RuneId.HEAVY)); }

    // ------------------------------------------------------------------------------------------------ launch

    static void launch(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        if (node.act.rune == RuneId.SHARD) spray(ctx, node, o, root);
        else launch(ctx, node, node.act, o, root, Origin.turn(o.dir(), node.yaw), -1, true, true, speed(node.act.rune), life(node.act.rune));
    }

    /**
     * Shard: its needles in a short cone round the aim, one in the middle and the rest spread evenly round it. Only
     * the first needle counts against the bolts a player may have out (they are gone in a few ticks).
     */
    private static void spray(CastContext ctx, Plan.Node node, Origin o, boolean root) {
        Act a = node.act;
        int n = a.empowered ? HAIL_NEEDLES : NEEDLES;
        Vec3 aim = Origin.turn(o.dir(), node.yaw);
        Vec3 side = Origin.flat(new Vec3(-aim.z, 0, aim.x));
        if (side == Vec3.ZERO) side = new Vec3(1, 0, 0);
        Vec3 up = side.cross(aim).normalize();
        float cone = CONE * (1.0F + 0.35F * a.count(RuneId.WIDE));
        for (int i = 0; i < n; i++) {
            Vec3 d = aim;
            if (i > 0) {
                // round the middle one: alternately wide and a little tighter, so the spray has no gap
                double turn = 2.0 * Math.PI * (i - 1) / (n - 1) + 0.4;
                double r = Math.toRadians(cone) * ((i & 1) == 1 ? 1.0 : 0.62);
                d = aim.add(side.scale(Math.cos(turn) * Math.tan(r))).add(up.scale(Math.sin(turn) * Math.tan(r) * 0.6)).normalize();
            }
            launch(ctx, node, a.withSlot(NEEDLE_SLOT + i), o, root, d, i, i == 0, i == 0, speed(a.rune), life(a.rune));
        }
        RuneFx.play(ctx.level, o.pos(), net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_BREAK, 0.7F, 1.7F);
    }

    /**
     * One of a Volley's arrows of light, falling from {@code from} along {@code dir}: it strikes as {@code a} (its own
     * strike number), carries nothing, and doesn't count against the bolts a player may have out.
     */
    static void fall(CastContext ctx, Plan.Node node, Act a, Vec3 from, Vec3 dir, double speed, int life) {
        launch(ctx, node, a, new Origin(from, dir, null), false, dir, -1, false, false, speed, life);
    }

    /**
     * Launches one bolt along {@code dir} acting as {@code a} (a needle: its own strike number), {@code speed} blocks a
     * tick for {@code life} ticks. {@code counted}: it takes one of the bolts a player may have out; {@code carries}: it
     * may let go its node's payload.
     */
    private static void launch(CastContext ctx, Plan.Node node, Act a, Origin o, boolean root, Vec3 dir, int needle, boolean counted,
                               boolean carries, double baseSpeed, int life) {
        Caster c = CastService.caster(ctx.owner);
        if ((counted && c.carriers.size() >= RuneConfig.get(RuneConfig.INSTANCE.maxCarriers)) || !ctx.level.isPositionEntityTicking(BlockPos.containing(o.pos()))) {
            if (counted) Executor.fizzle(ctx, o.pos());
            return;
        }
        Vec3 start = o.pos();
        if (root) {
            // a little in front of the eyes and below the crosshair, never inside a wall
            Vec3 want = start.add(dir.scale(0.6)).add(0, -0.12, 0);
            BlockHitResult bh = clip(ctx.level, start, want, ctx.owner);
            start = bh.getType() == HitResult.Type.MISS ? want : bh.getLocation().subtract(dir.scale(0.05));
        }
        int swift = a.count(RuneId.SWIFT), heavy = a.count(RuneId.HEAVY), wide = a.count(RuneId.WIDE);
        double speed = baseSpeed * Math.pow(1.3, swift) * Math.pow(0.85, heavy);
        double radius = radius(a.rune) + (needle >= 0 ? 0.1 : 0.25) * wide;
        float size = (size(a.rune) + (needle >= 0 ? 0.15F : 0.5F) * wide) * (a.empowered ? 1.25F : 1.0F);
        float push = push(a.rune) + 0.2F * heavy;
        FlyingRune f = new FlyingRune(ctx, node, a, c, dir.scale(speed), radius, a.rune.damaging() ? damage(ctx, a) : 0.0F, push, life, needle,
                carries);
        f.setOff = start;
        LivingEntity skip = RuneHits.body(o.hit());
        if (skip != null) f.passed.add(skip.getId());
        RuneBolt b = new RuneBolt(RuneRegistry.BOLT.get(), ctx.level);
        b.setPos(start);
        b.setDeltaMovement(f.vel);
        f.face(b);
        b.start(f, a.rune, a.element, size);
        if (ctx.level.addFreshEntity(b) && counted) c.carriers.add(b);
    }

    static BlockHitResult clip(ServerLevel level, Vec3 from, Vec3 to, Entity by) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, by));
    }

    // ------------------------------------------------------------------------------------------------ flight

    private Vec3 dir() { return vel.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : vel.normalize(); }

    /** Only the middle needle of a spray carries it, and a returning bolt let it go where it turned. */
    private boolean hasPayload() { return carries && !returning && node.relay != null && !node.children.isEmpty(); }

    @Override
    public void tick(RuneBolt b) {
        if (done || Executor.gone(ctx)) {
            done = true;
            b.discard();
            return;
        }
        age++;
        Vec3 pos = b.position();
        if (age > life) {
            if (returning) vanish(b);
            else stop(b, pos, dir(), null, null);
            return;
        }
        if (!returning && age >= turnAt) turn(b, pos, dir().scale(-1), null, false);
        if (returning) {
            // back to the caster's chest, turning harder the nearer it comes; caught there
            Vec3 chest = ctx.owner.position().add(0, ctx.owner.getBbHeight() * 0.6, 0);
            Vec3 to = chest.subtract(pos);
            if (to.lengthSqr() < CAUGHT * CAUGHT) {
                vanish(b);
                return;
            }
            Vec3 want = to.normalize().scale(speed);
            vel = vel.add(want.subtract(vel).scale(RETURN_TURN)).normalize().scale(speed);
        } else {
            steer(pos);
        }
        if (a.empowered && a.rune == RuneId.ORB) PhaseTwo.gravity(ctx, a, pos, age);
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
            // the plants it flies through (plants don't stop it): a glowbell rings, a fern unrolls (RunePlants)
            RunePlants.path(ctx, from, end);
            Hit h = firstCreature(b, from, end);
            if (h != null) {
                passed.add(h.body.getId());
                Act strikeAs = returning ? a.withSlot(RETURN_SLOT + (needle >= 0 ? needle * 100 : 0)) : a;
                if (!ctx.firstStrike(strikeAs, h.body)) {
                    // this rune already struck it in this cast: the copy passes on through
                    RuneFx.pierce(ctx.level, h.at, dir(), a.rune, a.element);
                    from = h.at;
                    continue;
                }
                if (a.rune.damaging()) RuneHits.strike(ctx, strikeAs, h.body, h.box, h.at, dir(), hitDamage(h.at), push, b, null);
                if (needle >= 0 && a.empowered) hail(h.body);
                if (a.empowered && a.rune == RuneId.SPARK && !returning) PhaseTwo.fork(ctx, a, h.body, damage);
                if (returning) {
                    // on its way back it passes through everything, striking it again
                    RuneFx.pierce(ctx.level, h.at, dir(), a.rune, a.element);
                    from = h.at;
                    continue;
                }
                if (pierces > 0 && h.body.isAlive()) {
                    pierces--;
                    RuneFx.pierce(ctx.level, h.at, dir(), a.rune, a.element);
                    from = h.at;
                    continue;
                }
                if (turnAt != Integer.MAX_VALUE) {
                    // Return: off the creature it struck, back the way it came
                    turn(b, h.at, dir().scale(-1), h.body, false);
                    from = h.at;
                    to = h.at.add(vel);
                    continue;
                }
                stop(b, h.at, dir(), h.body, null);
                return;
            }
            if (bh.getType() != HitResult.Type.MISS) {
                Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
                if (bounces > 0 && !returning) {
                    bounces--;
                    vel = Origin.reflect(dir(), n).scale(vel.length() * 0.92);
                    Vec3 at = bh.getLocation().add(n.scale(0.06));
                    RuneFx.bounce(ctx.level, at, dir(), a.rune, a.element, bounces);
                    b.setPos(at);
                    b.setDeltaMovement(vel);
                    face(b);
                    return;
                }
                Vec3 at = bh.getLocation().add(n.scale(0.1));
                if (turnAt != Integer.MAX_VALUE && !returning) {
                    // Return: off the wall, back toward its caster
                    turn(b, at, Origin.reflect(dir(), n), null, true);
                    RuneFx.bounce(ctx.level, at, dir(), a.rune, a.element, 0);
                    b.setPos(at);
                    b.setDeltaMovement(vel);
                    face(b);
                    return;
                }
                if (returning) {
                    // a wall between it and its caster: it breaks there, its payload long gone
                    RuneFx.impact(ctx.level, at, Origin.reflect(dir(), n), a.rune, a.element, b.size() * 0.7F, false);
                    vanish(b);
                    return;
                }
                stop(b, at, Origin.reflect(dir(), n), null, bh);
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

    /** A needle's damage where it strikes: all of it up close, less the further it has flown. */
    private float hitDamage(Vec3 at) {
        if (needle < 0) return damage;
        double d = at.distanceTo(setOff), reach = speed(RuneId.SHARD) * life(RuneId.SHARD);
        if (d <= NEEDLE_CLOSE) return damage;
        double k = Math.min(1.0, (d - NEEDLE_CLOSE) / Math.max(1.0, reach - NEEDLE_CLOSE));
        return (float) (damage * (1.0 - (1.0 - NEEDLE_FAR) * k));
    }

    /** Shard's hail: the fourth needle of one cast to strike a foe knocks it reeling. */
    private void hail(LivingEntity body) {
        if (ctx.countNeedle(body) == HAIL_STAGGER && RuneHits.isFoe(ctx.owner, body)) com.explorationremix.combat.Stagger.interrupt(body);
    }

    /**
     * Return: it turns at {@code at} and comes back, letting its payload go there (off a creature, a wall, or the end of
     * its way out), facing {@code out}.
     */
    private void turn(RuneBolt b, Vec3 at, Vec3 out, @Nullable LivingEntity hit, boolean wall) {
        if (returning) return;
        if (hasPayload()) Executor.release(ctx, node.children, new Origin(at, out, hit, wall));
        returning = true;
        // on its way back it may strike again what it passed on its way out, but not what it just turned off
        passed.clear();
        if (hit != null) passed.add(hit.getId());
        if (a.element != Element.NONE && hit != null) RuneHits.world(ctx, a.element, at, 1.0);
        Vec3 back = ctx.owner.position().add(0, ctx.owner.getBbHeight() * 0.6, 0).subtract(at);
        if (back.lengthSqr() > 1.0E-6) vel = vel.add(back.normalize().scale(speed).subtract(vel).scale(0.5)).normalize().scale(speed);
        RuneFx.play(ctx.level, at, net.minecraft.sounds.SoundEvents.TRIDENT_RETURN, 0.5F, 1.6F);
    }

    /** Back in its caster's hand, or broken on the way: gone, quietly. */
    private void vanish(RuneBolt b) {
        done = true;
        ctx.level.sendParticles(RuneFx.dust(RuneFx.colour(a.rune, a.element), 0.7F), b.getX(), b.getY(), b.getZ(), 4, 0.1, 0.1, 0.1, 0.0);
        b.discard();
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

    /** It stops: its look and sound, its element on the world round it, and its payload. */
    private void stop(RuneBolt b, Vec3 at, Vec3 d, @Nullable LivingEntity hit, @Nullable BlockHitResult wall) {
        done = true;
        b.setPos(at);
        ServerLevel l = ctx.level;
        // off a wall the sparks fly back out of it; from a creature, back the way the bolt came
        // a spray's other needles only spark where they land: one flash and one sound for the whole spray
        if (needle <= 0) RuneFx.impact(l, at, hit != null ? d.scale(-1) : d, a.rune, a.element, b.size(), hit != null);
        else RuneFx.pierce(l, at, d, a.rune, a.element);
        if (a.element != Element.NONE && needle <= 0) RuneHits.world(ctx, a.element, at, 1.0 + 0.5 * a.count(RuneId.WIDE));
        if (a.empowered && a.rune == RuneId.ORB) PhaseTwo.orbBurst(ctx, a, at);
        if (hasPayload()) Executor.release(ctx, node.children, new Origin(at, d, hit, wall != null));
        b.discard();
    }
}
