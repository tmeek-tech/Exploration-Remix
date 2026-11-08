package com.explorationremix.rune.cast;

import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.creature.SkeinEntity;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Reactions;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens when a rune strikes a creature: its damage (credited to the caster, never blocked by a shield), its own
 * push, its element and the reaction that sets off, and the energy it knocks loose as white motes (much more with
 * Siphon). Also who runes touch at all, and what the elements do to the world nearby (Frost freezes still water, Tide
 * puts out fire, Ember lights campfires and candles).
 *
 * <p>Rune hits never wait out a creature's hit immunity and never give it any: each rune lands, and a sword swing
 * right after still lands too. One rune strikes a creature once per cast (CastContext).
 */
public final class RuneHits {
    private RuneHits() {}

    // ------------------------------------------------------------------------------------------------ who runes touch

    /** The creature a box belongs to (a Skein's section is the Skein), or null if it is no creature. */
    @Nullable
    public static LivingEntity body(@Nullable Entity e) {
        if (e instanceof PartEntity<?> p) e = p.getParent();
        return e instanceof LivingEntity le ? le : null;
    }

    /**
     * Runes touch every creature but their caster, pets (anyone's), an allay working for a player, villagers, golems,
     * and other players unless the server turns rune damage between players on (and then only those the game itself
     * lets them hurt: not with pvp off, not a teammate without friendly fire, not one in creative). Bats and armor
     * stands are passed through (fireflies are no creature at all). A shulker is a golem that fights, so runes touch it.
     */
    public static boolean canHit(ServerPlayer owner, @Nullable Entity e) {
        LivingEntity t = body(e);
        if (t == null || t == owner || !t.isAlive() || t.isSpectator()) return false;
        if (t instanceof Player pt) {
            return RuneConfig.get(RuneConfig.INSTANCE.runesHurtPlayers) && !pt.getAbilities().invulnerable && owner.canHarmPlayer(pt);
        }
        if (t instanceof OwnableEntity o && o.getOwnerUUID() != null) return false;
        if (t instanceof Allay al && al.getBrain().hasMemoryValue(MemoryModuleType.LIKED_PLAYER)) return false;
        if (t instanceof AbstractVillager || t instanceof AmbientCreature || t instanceof ArmorStand) return false;
        return !(t instanceof AbstractGolem) || t instanceof Enemy;
    }

    /** A creature that is fighting the player: a monster, or anything hunting or angry at them. Only these feed Siphon. */
    public static boolean isFoe(ServerPlayer owner, @Nullable Entity e) {
        LivingEntity t = body(e);
        if (t == null || !canHit(owner, t)) return false;
        if (t instanceof Player) return true;
        if (t instanceof Enemy) return true;
        if (t instanceof Mob m && m.getTarget() == owner) return true;
        return t instanceof NeutralMob n && n.isAngryAt(owner);
    }

    /** The cave hunters (and anything big) are held for half as long. */
    public static boolean hunter(LivingEntity e) { return e instanceof MossbackEntity || e instanceof SkeinEntity || Statuses.hunter(e); }

    // ------------------------------------------------------------------------------------------------ damage

    public static Holder<DamageType> damageType(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(RuneRegistry.RUNE_DAMAGE);
    }

    /**
     * Hurts {@code t} through {@code box} (the part that was hit, or {@code t} itself) for {@code amount}, credited to
     * the caster, then pushes it along {@code dir} by {@code push} instead of the game's usual knockback. The rune's
     * point of origin is {@code direct}'s position (a flying rune), else {@code from} (where a burst or slash happened).
     */
    public static boolean hurt(CastContext ctx, LivingEntity t, Entity box, float amount, @Nullable Entity direct, @Nullable Vec3 from, Vec3 dir,
                               float push) {
        if (amount <= 0.0F || !t.isAlive()) return false;
        DamageSource src = new DamageSource(damageType(ctx.level), direct, ctx.owner, direct == null ? from : null);
        Vec3 v0 = t.getDeltaMovement();
        int immunity = t.invulnerableTime;
        t.invulnerableTime = 0;
        boolean ok = box.hurt(src, amount);
        t.invulnerableTime = immunity;
        t.setDeltaMovement(v0);
        if (ok) {
            // as a sword or an arrow does: their tamed wolves join in
            ctx.owner.setLastHurtMob(t);
            if (push > 0.0F) push(t, dir, push);
        }
        return ok;
    }

    /** A push along the flat of {@code dir}, with a little lift, less for creatures that resist knockback. */
    public static void push(LivingEntity t, Vec3 dir, float strength) {
        double resist = t.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE) ? t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
        double s = strength * Math.max(0.0, 1.0 - resist);
        if (s <= 0.0) return;
        Vec3 f = Origin.flat(dir).scale(s);
        Vec3 v = t.getDeltaMovement();
        t.setDeltaMovement(v.x * 0.5 + f.x, t.onGround() ? Math.min(0.4, 0.1 + s * 0.25) : v.y, v.z * 0.5 + f.z);
        t.hurtMarked = true;
    }

    // ------------------------------------------------------------------------------------------------ striking

    /**
     * A rune strikes {@code t} (through the box {@code box}) at {@code at}: its damage times any reaction's, its push,
     * its element, the reaction's effect, and Siphon's motes. Arrows pass 0 damage: they deal their own.
     */
    public static void strike(CastContext ctx, Act a, LivingEntity t, Entity box, Vec3 at, Vec3 dir, float damage, float push,
                              @Nullable Entity direct, @Nullable Vec3 from) {
        strike(ctx, a, t, box, at, dir, damage, push, direct, from, isFoe(ctx.owner, t));
    }

    /**
     * As above, with whether {@code t} was fighting the caster when it was struck ({@code foe}): an arrow's runes act
     * once its own hit has landed, and by then its hit may have killed it.
     */
    static void strike(CastContext ctx, Act a, LivingEntity t, Entity box, Vec3 at, Vec3 dir, float damage, float push,
                       @Nullable Entity direct, @Nullable Vec3 from, boolean foe) {
        boolean heavy = a.has(RuneId.HEAVY) || a.rune == RuneId.ORB;
        Reactions.Reaction r = Reactions.react(a.element, heavy, Statuses.wet(t), Statuses.frozen(t), Statuses.immune(t));
        float mult = r == Reactions.Reaction.SHATTER ? Reactions.SHATTER_MULT : r == Reactions.Reaction.THAW ? Reactions.THAW_MULT : 1.0F;
        if (damage <= 0.0F && mult > 1.0F) damage = 2.0F * ctx.power;      // an arrow's shatter or thaw still adds its bit
        else damage *= mult;
        if (a.element == Element.TIDE) push += 0.15F;
        boolean hurt;
        // the hit wears the creature's poise as this rune does: an Orb hard, a Field's pulse a little
        com.explorationremix.combat.CombatEvents.runePoise(poise(a));
        try {
            hurt = damage > 0.0F && hurt(ctx, t, box, damage, direct, from, dir, push);
        } finally {
            com.explorationremix.combat.CombatEvents.runePoise(1.0F);
        }
        // a hit the game refused (a guarded creature, a shield, one that dodged) does nothing else either
        if (damage > 0.0F && !hurt && t.isAlive()) return;
        if (damage <= 0.0F && push > 0.0F) push(t, dir, push);
        if (hurt) ctx.confirmHit(r != Reactions.Reaction.NONE);
        if (r != Reactions.Reaction.NONE) ctx.owner.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "rune.explorationremix.reaction." + r.name().toLowerCase(java.util.Locale.ROOT)).withStyle(s -> s.withColor(a.element.colour)), true);
        boolean weak = hurt && t instanceof MossbackEntity m && m.lastHitWasThroat();
        element(ctx, a, t, r, damage);
        // a rune that hurt a creature fighting you knocks a little of its cost loose; with Siphon, much more
        boolean siphon = a.has(RuneId.SIPHON);
        if (foe && (siphon || hurt)) {
            int id = t.getId();
            float back = ctx.siphon.hit(id, siphon);
            if (weak) back += ctx.siphon.weakSpot(id, siphon);
            if (r != Reactions.Reaction.NONE) back += ctx.siphon.reaction(id, siphon);
            if (hurt && t.isDeadOrDying()) back += ctx.siphon.kill(id, siphon);
            if (back > 0.0F) RuneMote.spawn(ctx.level, t.position().add(0, t.getBbHeight() * 0.6, 0), ctx.owner, ctx.gearId, back);
        }
    }

    /** How hard a rune's hit wears poise, per point of its damage. */
    static float poise(Act a) {
        float f = switch (a.rune) {
            case ORB -> 1.5F;
            case BURST -> 1.2F;
            case SPARK -> 0.8F;
            case SHARD -> 0.6F;
            case FIELD -> 0.2F;
            case ARROW -> 0.7F;
            default -> 1.0F;
        };
        return f + 0.5F * a.count(RuneId.HEAVY);
    }

    /** The element's mark on a creature, and the reaction it set off. */
    private static void element(CastContext ctx, Act a, LivingEntity t, Reactions.Reaction r, float damage) {
        ServerLevel l = ctx.level;
        Vec3 c = t.position().add(0, t.getBbHeight() * 0.5, 0);
        switch (r) {
            case STEAM -> Statuses.dry(t);
            case THAW, SHATTER -> Statuses.thaw(t);
            case FREEZE -> Statuses.freeze(t, Reactions.holdTicks(r, hunter(t)));
            case SHOCK -> Statuses.hold(t, Reactions.holdTicks(r, hunter(t)));
            default -> {}
        }
        if (r != Reactions.Reaction.NONE) RuneFx.reaction(l, c, r, a.element, t.getBbWidth());
        switch (a.element) {
            case EMBER -> {
                if (r != Reactions.Reaction.STEAM && r != Reactions.Reaction.THAW) t.setSecondsOnFire(a.has(RuneId.HEAVY) ? 5 : 3);
            }
            case FROST -> {
                if (r == Reactions.Reaction.NONE) {
                    // a chill: frost creeps over it and it slows
                    t.setTicksFrozen(Math.min(t.getTicksRequiredToFreeze() - 1, t.getTicksFrozen() + 60));
                    t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false, true), ctx.owner);
                }
            }
            case STORM -> chain(ctx, a, t, Math.max(1.0F, damage * 0.5F));
            case TIDE -> Statuses.soak(t);
            default -> {}
        }
    }

    /** Storm leaps from a struck creature to the next one fighting you: one leap, or up to three between wet creatures. */
    private static void chain(CastContext ctx, Act a, LivingEntity from, float damage) {
        LivingEntity at = from;
        int leaps = Statuses.wet(from) ? 3 : 1;
        List<LivingEntity> seen = new ArrayList<>();
        seen.add(from);
        for (int k = 0; k < leaps; k++) {
            double range = Statuses.wet(at) ? 8.0 : 4.0;
            LivingEntity next = null;
            double best = range * range;
            for (LivingEntity e : ctx.level.getEntitiesOfClass(LivingEntity.class, at.getBoundingBox().inflate(range), e -> isFoe(ctx.owner, e))) {
                if (seen.contains(e)) continue;
                double d = e.distanceToSqr(at);
                if (d < best && (k == 0 || Statuses.wet(e))) {
                    best = d;
                    next = e;
                }
            }
            if (next == null || !ctx.firstStrike(a, next)) return;
            seen.add(next);
            Vec3 p0 = at.position().add(0, at.getBbHeight() * 0.6, 0), p1 = next.position().add(0, next.getBbHeight() * 0.6, 0);
            RuneFx.beam(ctx.level, p0, p1, Element.STORM.colour, Element.STORM);
            // a leap the game refuses does nothing else: no hold, and the spark goes no further
            if (!hurt(ctx, next, next, damage, null, p0, p1.subtract(p0), 0.1F) && next.isAlive()) return;
            if (Statuses.wet(next) && !Statuses.immune(next)) Statuses.hold(next, Reactions.holdTicks(Reactions.Reaction.SHOCK, hunter(next)));
            at = next;
        }
    }

    // ------------------------------------------------------------------------------------------------ the world

    /**
     * What an element does to the world round {@code c}: Frost freezes still water into rune ice, Tide puts out fire,
     * Ember lights campfires and candles and melts rune ice. Only where the caster could change those blocks
     * themself (TempBlocks.mayChange). Bounded: a few hundred blocks looked at per call, and at most 24 frozen.
     */
    public static void world(CastContext ctx, Element e, Vec3 c, double radius) {
        if (e == Element.NONE || e == Element.STORM) return;
        ServerLevel l = ctx.level;
        int r = Mth.clamp((int) Math.ceil(radius), 1, 5);
        BlockPos o = BlockPos.containing(c);
        int changed = 0;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 0.5) continue;
                    BlockPos p = o.offset(dx, dy, dz);
                    if (!l.isLoaded(p)) continue;
                    BlockState s = l.getBlockState(p);
                    switch (e) {
                        case FROST -> {
                            if (changed < 24 && TempBlocks.place(l, p, TempBlock.Kind.ICE, ctx.owner)) changed++;
                        }
                        case TIDE -> {
                            if (s.getBlock() instanceof BaseFireBlock) {
                                if (!TempBlocks.mayChange(l, p, ctx.owner)) continue;
                                l.removeBlock(p, false);
                                changed++;
                            } else if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) {
                                if (!TempBlocks.mayChange(l, p, ctx.owner)) continue;
                                CampfireBlock.dowse(null, l, p, s);
                                l.setBlock(p, s.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
                                changed++;
                            }
                        }
                        case EMBER -> {
                            if ((s.getBlock() instanceof CampfireBlock || s.getBlock() instanceof CandleBlock || s.getBlock() instanceof CandleCakeBlock)
                                    && s.hasProperty(BlockStateProperties.LIT) && !s.getValue(BlockStateProperties.LIT)
                                    && !(s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED))) {
                                if (!TempBlocks.mayChange(l, p, ctx.owner)) continue;
                                l.setBlock(p, s.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL);
                                changed++;
                            } else if (s.getBlock() instanceof TempBlock tb && tb.kind == TempBlock.Kind.ICE) {
                                if (!TempBlocks.mayChange(l, p, ctx.owner)) continue;
                                tb.fade(l, p);
                                changed++;
                            }
                        }
                        default -> {}
                    }
                }
            }
        }
        if (changed > 0 && e == Element.TIDE) RuneFx.play(l, c, SoundEvents.FIRE_EXTINGUISH, 0.6F, 1.0F);
    }

    /** A creature a rune can touch, and the box of it nearest the rune (a Skein's nearest section). */
    public record Touch(LivingEntity body, Entity box, double distSqr) {}

    /**
     * Creatures in {@code area} a rune can touch, once each, with the box of each nearest {@code near}: a Skein is
     * touched where its body is, not only at its head.
     */
    public static List<Touch> touched(CastContext ctx, AABB area, Vec3 near) {
        java.util.Map<LivingEntity, Touch> best = new java.util.LinkedHashMap<>();
        for (Entity e : ctx.level.getEntities((Entity) null, area, e -> canHit(ctx.owner, e))) {
            LivingEntity b = body(e);
            if (b == null) continue;
            double d = e.getBoundingBox().distanceToSqr(near);
            Touch t = best.get(b);
            if (t == null || d < t.distSqr()) best.put(b, new Touch(b, e, d));
        }
        return new ArrayList<>(best.values());
    }

    /** Whether nothing solid stands between two points (a burst doesn't reach round a wall). */
    public static boolean clear(ServerLevel level, Vec3 a, Vec3 b, Entity ctxEntity) {
        if (a.distanceToSqr(b) < 0.01) return true;
        return level.clip(new net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, ctxEntity)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** The middle of a box (for aiming at a creature or one of its sections). */
    public static Vec3 middle(Entity e) { return e.getBoundingBox().getCenter(); }
}
