package com.explorationremix.rune.cast;

import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.ProjectileImpactEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A bow's or crossbow's cast riding its arrow. The arrow is the first carrier: the modifiers at the front of the row
 * shape it (Swift flies faster, Heavy hits harder, Pierce passes through, Seek bends toward a foe, Ember sets it
 * alight, the other elements and Siphon act where it hits), and what follows goes off where it lands (On hit), or a
 * moment after the shot, wherever it has got to, and the arrow with it (After a moment). Fan at the front looses
 * copies to either side, made from the arrow as it left the bow; copies can't be picked up. The arrow deals its own
 * damage. Swift makes it faster, not harder-hitting (an arrow's hit grows with its speed, so its damage is scaled
 * back), as Swift does for every rune.
 *
 * <p>Runes never touch who they mustn't (RuneHits.canHit): a copy Fan made passes them by, and the shot itself hits
 * them as the plain arrow it was, its runes spent.
 *
 * <p>Hits are noted when the arrow strikes and settled at the end of the tick, once the arrow's own damage has landed:
 * that is when a kill or a throat hit is known for Siphon.
 */
public final class ArrowRides {
    private static final class Ride {
        final CastContext ctx;
        final Plan.Node node;
        final Act a;
        final AbstractArrow arrow;
        int age;
        boolean released;
        @Nullable
        LivingEntity seekTarget;
        // a hit waiting for the end of the tick
        @Nullable
        LivingEntity hitBody;
        @Nullable
        Vec3 hitAt, hitDir;
        boolean hitWall, hitFoe;
        /** The creatures it struck this tick, for its runes to act on once its own hit has landed. */
        final List<Struck> struck = new ArrayList<>(2);
        Vec3 last;
        /** The arrow as it left the bow, before the runes shaped it. */
        final Plain plain;

        Ride(CastContext ctx, Plan.Node node, AbstractArrow arrow, Plain plain) {
            this.ctx = ctx;
            this.node = node;
            this.a = node.act;
            this.arrow = arrow;
            this.last = arrow.position();
            this.plain = plain;
        }

        boolean hasPayload() { return node.relay != null && !node.children.isEmpty(); }
    }

    /** What the runes change on an arrow, as it was before they did (Swift's speed is undone by its own factor). */
    private record Plain(double damage, byte pierce, boolean onFire) {}

    /** A creature the arrow struck (through the box {@code box}), and whether it was fighting the caster then. */
    private record Struck(LivingEntity body, Entity box, Vec3 at, Vec3 dir, boolean foe) {}

    private static final Map<Integer, Ride> RIDES = new HashMap<>();
    /** Set while this class adds Fan's copies, so they aren't taken for new shots. */
    private static boolean spawning;

    private ArrowRides() {}

    public static boolean spawning() { return spawning; }

    /** The cast fired with this shot: its arrow, and copies for a Fan, carry it. */
    static void shoot(CastContext ctx, List<Plan.Node> roots, AbstractArrow arrow) {
        // copies are made from the arrow as it left the bow, never from one the runes have shaped already
        Vec3 v = arrow.getDeltaMovement();
        CompoundTag asLoosed = arrow.saveWithoutId(new CompoundTag());
        Plain plain = new Plain(arrow.getBaseDamage(), arrow.getPierceLevel(), arrow.isOnFire());
        for (Plan.Node n : roots) {
            if (n.act.rune != RuneId.ARROW) continue;
            AbstractArrow carrier = n.copy == 0 ? arrow : copy(arrow, asLoosed, Origin.turn(v, n.yaw));
            if (carrier == null) continue;
            shape(carrier, n.act);
            RIDES.put(carrier.getId(), new Ride(ctx, n, carrier, plain));
        }
        RuneFx.play(ctx.level, arrow.position(), RuneFx.sound(RuneId.SPARK, roots.isEmpty() ? Element.NONE : roots.get(0).act.element), 0.5F, 1.4F);
    }

    /** A copy of the arrow flying along {@code v} (Fan's spread). It can't be picked up. */
    @Nullable
    private static AbstractArrow copy(AbstractArrow arrow, CompoundTag asLoosed, Vec3 v) {
        Entity e = arrow.getType().create(arrow.level());
        if (!(e instanceof AbstractArrow c)) return null;
        CompoundTag tag = asLoosed.copy();
        tag.remove("UUID");
        c.load(tag);
        c.setUUID(Mth.createInsecureUUID(arrow.level().random));
        c.setOwner(arrow.getOwner());
        c.setPos(arrow.position());
        c.setDeltaMovement(v);
        double h = v.horizontalDistance();
        c.setYRot((float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG));
        c.setXRot((float) (Mth.atan2(v.y, h) * Mth.RAD_TO_DEG));
        c.yRotO = c.getYRot();
        c.xRotO = c.getXRot();
        c.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        spawning = true;
        try {
            return arrow.level().addFreshEntity(c) ? c : null;
        } finally {
            spawning = false;
        }
    }

    /** The front modifiers and element on the arrow itself. */
    private static void shape(AbstractArrow arrow, Act a) {
        int swift = a.count(RuneId.SWIFT), heavy = a.count(RuneId.HEAVY), pierce = a.count(RuneId.PIERCE);
        if (swift > 0) {
            // faster and further, not harder: an arrow hits for its speed times its base damage
            double k = Math.pow(1.3, swift);
            arrow.setDeltaMovement(arrow.getDeltaMovement().scale(k));
            arrow.setBaseDamage(arrow.getBaseDamage() / k);
        }
        if (heavy > 0) arrow.setBaseDamage(arrow.getBaseDamage() * (1.0 + 0.35 * heavy));
        if (pierce > 0) arrow.setPierceLevel((byte) Math.min(127, arrow.getPierceLevel() + 2 * pierce));
        if (a.element == Element.EMBER) arrow.setSecondsOnFire(100);
        arrow.hasImpulse = true;
    }

    /** An arrow struck something (before its own damage lands). */
    public static void impact(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;
        Ride r = RIDES.get(arrow.getId());
        if (r == null || r.released || Executor.gone(r.ctx)) return;
        HitResult hr = e.getRayTraceResult();
        Vec3 dir = arrow.getDeltaMovement().lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : arrow.getDeltaMovement().normalize();
        if (hr instanceof EntityHitResult eh) {
            LivingEntity body = RuneHits.body(eh.getEntity());
            if (body == null || !RuneHits.canHit(r.ctx.owner, body)) {
                spare(e, r, arrow);
                return;
            }
            if (r.ctx.firstStrike(r.a, body) && (r.a.element != Element.NONE || r.a.has(RuneId.SIPHON))) {
                r.struck.add(new Struck(body, eh.getEntity(), eh.getLocation(), dir, RuneHits.isFoe(r.ctx.owner, body)));
            }
            if (r.hitAt == null) {
                r.hitBody = body;
                r.hitFoe = RuneHits.isFoe(r.ctx.owner, body);
                r.hitAt = eh.getLocation();
                r.hitDir = dir;
            }
        } else if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && r.hitAt == null) {
            Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
            r.hitAt = bh.getLocation().add(n.scale(0.1));
            r.hitDir = Origin.reflect(dir, n);
            r.hitWall = true;
        }
    }

    /**
     * The arrow meets someone runes never touch (or something that isn't a creature): a copy Fan made passes them by;
     * the shot itself hits as the plain arrow it was, and its runes are spent.
     */
    private static void spare(ProjectileImpactEvent e, Ride r, AbstractArrow arrow) {
        if (r.node.copy > 0) {
            e.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
            return;
        }
        Plain p = r.plain;
        arrow.setBaseDamage(p.damage());
        arrow.setPierceLevel(p.pierce());
        int swift = r.a.count(RuneId.SWIFT);
        if (swift > 0) arrow.setDeltaMovement(arrow.getDeltaMovement().scale(Math.pow(1.3, -swift)));
        if (!p.onFire()) arrow.clearFire();
        r.released = true;
    }

    /** At the end of each server tick: steer Seek arrows, settle hits, let payloads go. */
    public static void tick() {
        if (RIDES.isEmpty()) return;
        for (Iterator<Map.Entry<Integer, Ride>> it = RIDES.entrySet().iterator(); it.hasNext(); ) {
            Ride r = it.next().getValue();
            if (Executor.gone(r.ctx)) {
                it.remove();
                continue;
            }
            r.age++;
            if (r.hitAt != null && !r.released) {
                settle(r);
            } else if (!r.released) {
                if (r.arrow.isRemoved()) {
                    // gone without a hit we saw: out of the world, its payload goes where it was; packed away with
                    // its chunk, the payload is spent (it would go off in ground nobody is near)
                    if (r.hasPayload() && r.last.y > r.ctx.level.getMinBuildHeight() && r.arrow.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                        release(r, new Origin(r.last, new Vec3(0, -1, 0), null));
                    }
                    r.released = true;
                } else if (!r.ctx.level.isPositionEntityTicking(r.arrow.blockPosition())) {
                    // flown into ground that is loaded but still (past the simulation distance): it hangs there as a
                    // plain arrow, its runes spent
                    r.released = true;
                } else {
                    r.last = r.arrow.position();
                    steer(r);
                    if (r.node.relay == RuneId.AFTER && r.hasPayload() && r.age > FlyingRune.AFTER_TICKS) {
                        Vec3 d = r.arrow.getDeltaMovement();
                        release(r, new Origin(r.arrow.position(), d.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : d.normalize(), null));
                        RuneFx.puff(r.ctx.level, r.arrow.position(), RuneId.SPARK, r.a.element, 8, 0.2);
                        r.arrow.discard();
                    } else if (r.age > 200) {
                        if (r.hasPayload()) release(r, new Origin(r.arrow.position(), new Vec3(0, 0, 1), null));
                        r.released = true;
                    }
                }
            }
            if (r.released && (r.arrow.isRemoved() || r.age > 40)) it.remove();
        }
    }

    /**
     * A hit noted this tick, now that the arrow's own hit has landed or been refused: its runes on each creature whose
     * hit landed (a guarded creature, a shield or a dodge leaves nothing), Siphon's kill and throat refunds, then the
     * payload.
     */
    private static void settle(Ride r) {
        for (Struck s : r.struck) {
            if (landed(s.body(), r.arrow)) RuneHits.strike(r.ctx, r.a, s.body(), s.box(), s.at(), s.dir(), 0.0F, 0.0F, r.arrow, null, s.foe());
        }
        r.struck.clear();
        LivingEntity t = r.hitBody;
        if (t != null && r.hitFoe && r.a.has(RuneId.SIPHON)) {
            int id = t.getId();
            float back = 0.0F;
            if (t instanceof MossbackEntity m && m.lastHitWasThroat()) back += r.ctx.siphon.weakSpot(id);
            if (t.isDeadOrDying()) back += r.ctx.siphon.kill(id);
            if (back > 0.0F) RuneMote.spawn(r.ctx.level, t.position().add(0, t.getBbHeight() * 0.6, 0), r.ctx.owner, r.ctx.gearId, back);
        }
        if (r.hasPayload()) release(r, new Origin(r.hitAt, r.hitDir, r.hitWall ? null : t, r.hitWall));
        r.released = true;
    }

    /** Whether the arrow's own hit on {@code t} landed: the game took it as {@code t}'s latest damage. */
    private static boolean landed(LivingEntity t, AbstractArrow arrow) {
        DamageSource last = t.getLastDamageSource();
        return last != null && last.getDirectEntity() == arrow;
    }

    private static void release(Ride r, Origin o) {
        r.released = true;
        Executor.release(r.ctx, r.node.children, o);
    }

    /** Seek on an arrow: it bends toward the nearest foe ahead, like a Seek bolt. */
    private static void steer(Ride r) {
        int seek = r.a.count(RuneId.SEEK);
        if (seek <= 0) return;
        Vec3 v = r.arrow.getDeltaMovement();
        if (v.lengthSqr() < 0.01) return;
        Vec3 pos = r.arrow.position(), d = v.normalize();
        if (r.seekTarget == null || !r.seekTarget.isAlive() || r.age % 5 == 1) {
            r.seekTarget = FlyingRune.findTarget(r.ctx, pos, d, seek, java.util.Set.of());
        }
        if (r.seekTarget == null) return;
        Vec3 to = r.seekTarget.getBoundingBox().getCenter().subtract(pos);
        if (to.lengthSqr() < 1.0E-6) return;
        Vec3 nd = d.add(to.normalize().subtract(d).scale(seek >= 2 ? 0.28 : 0.15)).normalize();
        r.arrow.setDeltaMovement(nd.scale(v.length()));
        r.arrow.hasImpulse = true;
    }

    /** The player left: their arrows fly on as plain arrows. */
    static void forget(UUID owner) { RIDES.values().removeIf(r -> r.ctx.owner.getUUID().equals(owner)); }

    public static void clear() { RIDES.clear(); }
}
