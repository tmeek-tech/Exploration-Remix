package com.explorationremix.rune.cast;

import com.explorationremix.combat.Fighters;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneTags;
import com.explorationremix.rune.armor.ArmorEffects;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Limits;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Shape;
import com.explorationremix.rune.menu.RuneTableMenu;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where weapons cast, on the server, which alone decides: a sword when its player presses the cast key (R by default,
 * and again each time it is ready while they hold it), a bow or crossbow when it looses a fully drawn shot. Every cast
 * is paid in full from the weapon's own energy before anything happens (CastMachine), and only a weapon that has been
 * in the hand for {@code swapTicks} casts, so swapping weapons can't skip their waits. The active rune's second phase
 * is the player's XP level's to wake (Reader), read each time it casts.
 *
 * <p>Also here: the passives that aren't an element on the cast (Overflow makes the cast after energy came in past full
 * a third stronger; On hit casts for free every third full hit; Recall brings a bow's arrows back) and an element passive on the weapon's
 * own hits; a hit with a runed weapon on a creature fighting you knocks energy loose as motes that fly back into it
 * (more with Thirst awake on the armor); weapons still holding a row from before the sockets have it set into them,
 * and gear keeping runes that no longer fit it hands them back. Fake players (a Create deployer holding a sword) never
 * cast or fill weapons.
 */
public final class CastService {
    private static final Map<UUID, Caster> CASTERS = new HashMap<>();

    public enum Result { CAST, WAIT, NONE }

    /** On hit: every this many full hits on foes casts the active rune for free. */
    public static final int ON_HIT_EVERY = 3;

    private CastService() {}

    static Caster caster(ServerPlayer p) { return CASTERS.computeIfAbsent(p.getUUID(), u -> new Caster()); }

    @Nullable
    static Caster casterOrNull(ServerPlayer p) { return CASTERS.get(p.getUUID()); }

    /**
     * Runs {@code run} at the end of the server tick {@code ticks} from now (0: this one's end): an aftershock, a wave's
     * next stretch. What is set to run checks for itself that its cast still stands. A player has at most
     * {@link Caster#MAX_LATER} waiting; past that, the new one is dropped.
     */
    static void later(ServerPlayer owner, int ticks, Runnable run) {
        Caster c = caster(owner);
        if (c.later.size() >= Caster.MAX_LATER) return;
        c.later.add(new Caster.Later(owner.server.getTickCount() + Math.max(0, ticks), run));
    }

    // ------------------------------------------------------------------------------------------------ the sword

    /**
     * The cast key (R by default; Taran, 2026-11-11 14:45: no longer right click, which stays the game's own, a shield's
     * included) went down or came up. Down casts the runed sword in hand at once (the main hand's, or else the off
     * hand's); while it stays down, the sword casts again each time it is ready ({@link #tick}).
     */
    public static void castKey(ServerPlayer p, boolean down) {
        if (p instanceof FakePlayer) return;
        caster(p).castHeld = down;
        if (down) castHeldSword(p);
    }

    private static void castHeldSword(ServerPlayer p) {
        InteractionHand hand = runedSword(p.getMainHandItem()) ? InteractionHand.MAIN_HAND
                : runedSword(p.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
        if (hand != null) castSword(p, hand);
    }

    private static boolean runedSword(ItemStack s) { return RuneGear.host(s) == Host.SWORD && RuneGear.hasRunes(s); }

    /** The player casts the sword in this hand (the cast key, pressed or held). */
    public static Result castSword(ServerPlayer p, InteractionHand hand) {
        if (p instanceof FakePlayer) return Result.NONE;
        ItemStack gear = p.getItemInHand(hand);
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear)) return Result.NONE;
        if (p.isSpectator() || !p.isAlive() || p.containerMenu instanceof RuneTableMenu) return Result.NONE;
        Caster c = caster(p);
        long now = p.level().getGameTime();
        if (!inHandLongEnough(p, c, hand, gear, now)) return Result.WAIT;
        return cast(p, c, hand, gear, now, null);
    }

    // ------------------------------------------------------------------------------------------------ bows

    /** A charged crossbow with runes is about to fire from this hand. */
    public static void crossbowAboutToFire(ServerPlayer p, InteractionHand hand) {
        if (p instanceof FakePlayer) return;
        Caster c = caster(p);
        c.crossbowHand = hand;
        c.crossbowTick = p.level().getGameTime();
        c.crossbowCast = false;
    }

    /** On an arrow a runed weapon loosed (never on a copy Fan made): the weapon's id, which its hit fills. */
    public static final String GEAR_TAG = "explorationremix_gear";

    /**
     * An arrow came into the world: if a player's runed bow loosed it fully drawn, or their runed crossbow fired it,
     * the weapon casts and the cast rides the arrow. Anything else (a half-drawn shot, a bow still recovering, short of
     * energy) stays a plain arrow, though an Ember passive sets every arrow alight. Either way, when it hits a creature
     * fighting its player it fills the weapon.
     */
    public static void arrowLoosed(AbstractArrow arrow) {
        if (ArrowRides.spawning() || !(arrow.getOwner() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        Caster c = CASTERS.get(p.getUUID());
        if (c == null) return;
        long now = p.level().getGameTime();
        InteractionHand hand;
        boolean full;
        // a crossbow's arrows only ever take the crossbow's cast, even with a bow drawn in the other hand
        if (!arrow.shotFromCrossbow() && p.isUsingItem() && RuneGear.host(p.getUseItem()) == Host.BOW) {
            hand = p.getUsedItemHand();
            full = arrow.isCritArrow();
        } else if (c.crossbowTick == now && c.crossbowHand != null && arrow.shotFromCrossbow()) {
            hand = c.crossbowHand;
            full = !c.crossbowCast;             // a multishot's other arrows fly plain
            c.crossbowCast = true;
        } else {
            return;
        }
        ItemStack gear = p.getItemInHand(hand);
        if (!RuneGear.isGear(gear) || !RuneGear.hasRunes(gear)) return;
        int id = RuneGear.ensureGearId(gear, p.getRandom());
        Reading r = RuneGear.reading(gear, p);
        // before the cast shapes it: what the arrow is as it left the bow is what it goes back to
        if (r != null && r.passiveIs(RuneId.EMBER)) arrow.setSecondsOnFire(100);
        if (full && !(p.containerMenu instanceof RuneTableMenu) && inHandLongEnough(p, c, hand, gear, now)) cast(p, c, hand, gear, now, arrow);
        // marked once the cast has made its copies, so only the arrow itself fills the weapon
        arrow.getPersistentData().putInt(GEAR_TAG, id);
        if (r != null && r.passiveIs(RuneId.RECALL) && arrow.pickup == AbstractArrow.Pickup.ALLOWED) Recall.track(arrow, p);
    }

    // ------------------------------------------------------------------------------------------------ casting

    /** Tries the weapon's cast; for a bow or crossbow {@code arrow} is the shot that carries it. */
    private static Result cast(ServerPlayer p, Caster c, InteractionHand hand, ItemStack gear, long now, @Nullable AbstractArrow arrow) {
        HostSpec spec = RuneGear.spec(gear);
        Reading r = RuneGear.reading(gear, p);
        if (spec == null || r == null) return Result.NONE;
        Limits lim = RuneConfig.limits();
        if (r.casts().isEmpty()) {
            if (arrow == null) tell(p, c, now, Component.translatable("message.explorationremix.runes.nothing", spec.host().word));
            return Result.NONE;
        }
        CastState s = RuneGear.state(gear, spec, now);
        if (now < s.readyAt) return Result.WAIT;
        int next = CastMachine.next(s, r, now, lim);
        if (next < 0) {
            tell(p, c, now, Component.translatable("message.explorationremix.runes.red", spec.host().word));
            return Result.NONE;
        }
        CastReading cast = r.casts().get(next);
        Plan plan = Plan.expand(cast, lim);
        Result fits = fits(p, c, plan);
        if (fits != Result.CAST) {
            if (fits == Result.WAIT && busy(p, c, plan)) tell(p, c, now, Component.translatable("message.explorationremix.runes.busy"));
            return fits;
        }
        CastMachine.Outcome o = CastMachine.attempt(s, r, now, false, lim);
        if (o.kind() == CastMachine.Kind.SPUTTER) {
            sputter(p, c, now, o);
            return Result.WAIT;
        }
        if (!o.fired()) return Result.WAIT;
        int id = RuneGear.ensureGearId(gear, p.getRandom());
        // Overflow's charge goes into this cast (and is spent, even if Overflow has since come out)
        boolean brim = s.brim && r.passiveIs(RuneId.AFTER);
        s.brim = false;
        RuneGear.setState(gear, s);
        CastContext ctx = new CastContext(p, spec.host(), spec.power(), id, o.cost(), brim ? Explain.OVERFLOW : 1.0F);
        if (brim) RuneFx.play(p.serverLevel(), p.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8F, 1.9F);
        if (arrow != null) {
            if (brim) arrow.setBaseDamage(arrow.getBaseDamage() * Explain.OVERFLOW);
            ArrowRides.shoot(ctx, plan.roots, arrow);
        } else {
            castFx(p, hand, plan, true);
            Executor.cast(ctx, plan.roots, Origin.eyes(p));
        }
        return Result.CAST;
    }

    /**
     * The checks that cost nothing, before a cast is paid for: CAST if it may go, WAIT if not now (too many runes out
     * already, a second dash in the air, the caster's own ground not running yet after a long teleport, when it could
     * only fizzle).
     */
    private static Result fits(ServerPlayer p, Caster c, Plan plan) {
        boolean dash = false, dashTwo = false;
        for (Plan.Node n : plan.roots) {
            if (n.act.rune.shape == Shape.SELF) {
                dash = true;
                dashTwo |= n.act.empowered;
            }
        }
        if (busy(p, c, plan)) return Result.WAIT;
        if (dash && !Dashes.canDash(p, c, dashTwo)) return Result.WAIT;
        if (!p.serverLevel().isPositionEntityTicking(p.blockPosition())) return Result.WAIT;
        return Result.CAST;
    }

    /** Whether the cast would put more bolts and fields out than a player may have at once. */
    private static boolean busy(ServerPlayer p, Caster c, Plan plan) {
        int out = 0;
        for (Plan.Node n : plan.roots) {
            Shape sh = n.act.rune.shape;
            if ((sh == Shape.BOLT && n.act.rune != RuneId.ARROW) || sh == Shape.FIELD) out++;
        }
        return out > 0 && c.carriers.size() + out > RuneConfig.get(RuneConfig.INSTANCE.maxCarriers);
    }

    /**
     * The cast leaves the hand: the arm flicks, a spell sound, a flash and sparks of the first rune's colour at the hand,
     * brighter for a second phase. Not {@code own} shows the same light, softer, and doesn't flick the arm.
     */
    private static void castFx(ServerPlayer p, InteractionHand hand, Plan plan, boolean own) {
        if (plan.roots.isEmpty()) return;
        Act first = plan.roots.get(0).act;
        Vec3 look = p.getLookAngle();
        // beside the view, where the hand is, not over the crosshair
        boolean right = (hand == InteractionHand.MAIN_HAND) == (p.getMainArm() == HumanoidArm.RIGHT);
        Vec3 side = Origin.flat(new Vec3(-look.z, 0, look.x)).scale(right ? 0.45 : -0.45);
        Vec3 at = p.getEyePosition().add(look.scale(0.9)).add(side).add(0, -0.45, 0);
        boolean loud = own && (first.rune.shape == Shape.BOLT || first.rune == RuneId.REEL);
        RuneFx.cast(p.serverLevel(), at, look, first.rune, first.element, loud, own ? p.getId() : -1, hand, first.empowered);
        if (!own) return;
        // others see the arm flick from here; the caster's own game flicks it on the cast's effect, since a swing sent to
        // the caster comes back as one of theirs, and the server would take it for a swing and spend their attack charge
        p.swing(hand, false);
    }

    private static void sputter(ServerPlayer p, Caster c, long now, CastMachine.Outcome o) {
        if (now - c.toldAt < 10) return;
        c.toldAt = now;
        p.displayClientMessage(Component.translatable("message.explorationremix.runes.sputter", o.cost(), Mth.floor(o.have())), true);
        p.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.5F, 1.7F);
        Vec3 at = p.getEyePosition().add(p.getLookAngle().scale(0.7)).add(0, -0.3, 0);
        p.serverLevel().sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 3, 0.05, 0.05, 0.05, 0.01);
    }

    /** A short word on the action bar, at most twice a second. */
    private static void tell(ServerPlayer p, Caster c, long now, Component msg) {
        if (now - c.toldAt < 10) return;
        c.toldAt = now;
        p.displayClientMessage(msg, true);
    }

    private static boolean inHandLongEnough(ServerPlayer p, Caster c, InteractionHand hand, ItemStack gear, long now) {
        int swap = RuneConfig.get(RuneConfig.INSTANCE.swapTicks);
        if (swap <= 0) return true;
        Caster.Hand h = c.hand(hand);
        int id = RuneGear.gearId(gear);
        int slot = hand == InteractionHand.MAIN_HAND ? p.getInventory().selected : 40;
        return id != 0 && h.id == id && h.slot == slot && now - h.since >= swap;
    }

    // ------------------------------------------------------------------------------------------------ the weapon's own hits

    /**
     * Melee damage landed. A runed sword's hit on a creature fighting you knocks energy loose into it (more with Thirst
     * awake on the armor);
     * a full swing at the creature (not a sweep's edge) carries the sword's element passive, and with On hit every third
     * one on a foe casts the active rune for free. What hits back waits for the end of the tick, after this hit.
     */
    public static void meleeDamage(ServerPlayer p, LivingEntity target, float amount) {
        if (p instanceof FakePlayer) return;
        ItemStack gear = p.getMainHandItem();
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear)) return;
        int id = RuneGear.ensureGearId(gear, p.getRandom());
        Reading r = RuneGear.reading(gear, p);
        if (r == null) return;
        knockLoose(p, target, amount, id, ArmorEffects.thirst(p));
        boolean full = Fighters.swungAt(p, target) && Fighters.swingStrength(p, target) >= 0.9F;
        if (!full) return;
        if (r.element() != Element.NONE) weaponElement(p, r, id, target, p.getLookAngle());
        if (r.passiveIs(RuneId.ON_HIT) && RuneHits.isFoe(p, target) && counted(p, id)) {
            later(p, 0, () -> freeCast(p, id));
        }
    }

    /**
     * An arrow's own hit landed. If a runed weapon loosed it, the hit knocks energy loose into the weapon (more with
     * Thirst awake on the armor); a plain arrow (one no cast rode) carries the weapon's element passive; with On hit
     * every third arrow that hits a foe sets the active rune off there, for free; and with Recall the arrow comes back.
     */
    public static void arrowDamage(ServerPlayer p, AbstractArrow arrow, LivingEntity target, float amount) {
        if (p instanceof FakePlayer) return;
        int id = arrow.getPersistentData().getInt(GEAR_TAG);
        if (id == 0) return;
        ItemStack gear = RuneGear.findGear(p, id);
        Reading r = gear == null ? null : RuneGear.reading(gear, p);
        knockLoose(p, target, amount, id, ArmorEffects.thirst(p));
        if (r == null) return;
        if (r.passiveIs(RuneId.RECALL)) Recall.struck(p, arrow);
        Vec3 dir = arrow.getDeltaMovement().lengthSqr() < 1.0E-8 ? p.getLookAngle() : arrow.getDeltaMovement().normalize();
        if (r.element() != Element.NONE && !ArrowRides.riding(arrow)) weaponElement(p, r, id, target, dir);
        if (r.passiveIs(RuneId.ON_HIT) && RuneHits.isFoe(p, target) && counted(p, id)) {
            Vec3 at = RuneHits.middle(target);
            later(p, 0, () -> freeRelease(p, id, target, at, dir));
        }
    }

    /** On hit: counts a hit for this weapon; true on every third. */
    private static boolean counted(ServerPlayer p, int gearId) {
        Caster c = caster(p);
        int n = c.hits.merge(gearId, 1, Integer::sum);
        if (n < ON_HIT_EVERY) return false;
        c.hits.put(gearId, 0);
        return true;
    }

    /** The weapon's element passive on its own hit, at the end of the tick: its mark, and any reaction it sets off. */
    private static void weaponElement(ServerPlayer p, Reading r, int gearId, LivingEntity target, Vec3 dir) {
        Element e = r.element();
        Host host = r.spec().host();
        float power = r.spec().power();
        later(p, 0, () -> {
            if (!target.isAlive() || p.isRemoved() || target.level() != p.level() || !RuneHits.canHit(p, target)) return;
            CastContext ctx = new CastContext(p, host, power, gearId, 0, 1.0F, false);
            // the weapon's own hit carries it: no rune of its own, so an ARROW act (no damage; the hit dealt its own)
            Act a = new Act(RuneId.ARROW, -1, 0, new int[Act.MODS.length], e, 1, false);
            Vec3 at = RuneHits.middle(target);
            RuneHits.strike(ctx, a, target, target, at, dir, 0.0F, 0.0F, null, at.subtract(dir));
        });
    }

    /** On hit's free cast for the sword: the active rune, cast from where they look, costing nothing and waiting on nothing. */
    private static void freeCast(ServerPlayer p, int gearId) {
        if (p.isRemoved() || !p.isAlive() || p.isSpectator()) return;
        ItemStack gear = p.getMainHandItem();
        if (RuneGear.gearId(gear) != gearId || RuneGear.host(gear) != Host.SWORD) return;
        Reading r = RuneGear.reading(gear, p);
        CastReading cast = r == null ? null : r.cast();
        if (cast == null || cast.red()) return;
        Plan plan = Plan.expand(cast, RuneConfig.limits());
        Caster c = caster(p);
        if (fits(p, c, plan) != Result.CAST) return;
        CastContext ctx = new CastContext(p, r.spec().host(), r.spec().power(), gearId, 0, 1.0F);
        castFx(p, InteractionHand.MAIN_HAND, plan, true);
        RuneFx.play(p.serverLevel(), p.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6F, 1.7F);
        Executor.cast(ctx, plan.roots, Origin.eyes(p));
    }

    /** On hit's free cast for a bow or crossbow: the active rune goes off where its arrow struck, costing nothing. */
    private static void freeRelease(ServerPlayer p, int gearId, LivingEntity target, Vec3 at, Vec3 dir) {
        if (p.isRemoved() || !p.isAlive() || target.level() != p.level()) return;
        ItemStack gear = RuneGear.findGear(p, gearId);
        Reading r = gear == null ? null : RuneGear.reading(gear, p);
        CastReading cast = r == null ? null : r.cast();
        if (cast == null || cast.red() || r.active() == null) return;
        Plan plan = Plan.expand(cast, RuneConfig.limits());
        if (plan.roots.isEmpty()) return;
        List<Plan.Node> payload = plan.roots.get(0).children;
        if (payload.isEmpty()) return;
        CastContext ctx = new CastContext(p, r.spec().host(), r.spec().power(), gearId, 0, 1.0F);
        RuneFx.play(p.serverLevel(), at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6F, 1.7F);
        Executor.release(ctx, payload, new Origin(target.isAlive() ? RuneHits.middle(target) : at, dir, target.isAlive() ? target : null));
    }

    /**
     * A hit for {@code amount} with the weapon {@code gearId} on {@code target}: if it is fighting the player, energy for
     * the damage it took flies back as motes ({@code thirst} times as much: Thirst on the armor). No more than the
     * creature had left counts (still its health before this hit), so overkill on a weak one gives nothing extra.
     */
    private static void knockLoose(ServerPlayer p, LivingEntity target, float amount, int gearId, float thirst) {
        if (gearId == 0 || amount <= 0.0F || !RuneHits.isFoe(p, target)) return;
        float dealt = Math.min(amount, target.getHealth());
        float energy = (float) Math.min(RuneConfig.get(RuneConfig.INSTANCE.energyMaxPerHit), dealt * RuneConfig.get(RuneConfig.INSTANCE.energyPerDamage));
        energy *= thirst;
        if (energy <= 0.0F) return;
        RuneMote.spawn(p.serverLevel(), target.position().add(0, target.getBbHeight() * 0.6, 0), p, gearId, energy);
    }

    // ------------------------------------------------------------------------------------------------ ticks

    /**
     * Each player's tick, at its end (after the server has moved them): which weapon is in hand, a held cast key,
     * dashes, a Reel's pull, throws set this tick, safe landings from their own blasts, a second phase waking or
     * sleeping, and once a second old rows to set into sockets and runes that no longer fit to hand back. (What worn
     * runes do each tick is ArmorEffects'.)
     */
    public static void tick(ServerPlayer p) {
        if (p instanceof FakePlayer) return;
        Caster c = caster(p);
        long now = p.level().getGameTime();
        trackHands(p, c, now);
        if (c.castHeld) castHeldSword(p);
        if (!c.dashes.isEmpty() || c.airDashes > 0) Dashes.tick(p, c, now);
        if (c.pull != null) Grapples.tick(p, c, now);
        if (c.launch != null) {
            // not sent yet: the move the server just made slowed it (by ground friction, even), so it goes as it was set
            if (p.hurtMarked) p.setDeltaMovement(c.launch);
            c.launch = null;
        }
        if (now <= c.safeUntil && p.getY() > c.safeAboveY) p.fallDistance = 0.0F;
        if (c.airBlastUsed && (p.onGround() || p.isInWater())) c.airBlastUsed = false;
        if (c.airPulls > 0 && c.pull == null && now - c.pullTick > 4 && (p.onGround() || p.isInWater())) c.airPulls = 0;
        if (p.tickCount % 10 == 0) phase(p, c);
        if (p.tickCount % 20 == 0) {
            convertOldRows(p, c);
            // kept up to date though nothing reads it for now: the sockets don't wait on depth (a card's answer may change that)
            RunDepth.update(p);
        }
    }

    /**
     * The runed weapon in hand (the main hand's, else the off hand's) woke its second phase as they reached its active
     * rune's level, or it went back to sleep as they spent levels: a ring of light and a word on the action bar.
     */
    private static void phase(ServerPlayer p, Caster c) {
        ItemStack s = runed(p.getMainHandItem()) ? p.getMainHandItem() : runed(p.getOffhandItem()) ? p.getOffhandItem() : ItemStack.EMPTY;
        int id = RuneGear.gearId(s);
        Reading r = s.isEmpty() ? null : RuneGear.reading(s, p);
        RuneId a = r == null ? null : r.active();
        boolean awake = a != null && r.empowered();
        if (id == 0 || a == null || id != c.phaseGear || a != c.phaseRune) {
            // a different weapon, or new runes: nothing woke, it was simply picked up so
            c.phaseGear = id;
            c.phaseRune = a;
            c.phaseAwake = awake;
            return;
        }
        if (awake == c.phaseAwake) return;
        c.phaseAwake = awake;
        int rgb = RuneFx.colour(a, r.element());
        RuneFx.phase(p.serverLevel(), p.position(), rgb, awake, p.getId());
        p.displayClientMessage(Component.translatable(awake ? "message.explorationremix.runes.phase_wakes" : "message.explorationremix.runes.phase_sleeps",
                a.title, a.phaseLevel).withStyle(st -> st.withColor(awake ? 0xBFD9FF : 0x8C9BB3)), true);
    }

    private static boolean runed(ItemStack s) { return RuneGear.isGear(s) && RuneGear.hasRunes(s); }

    /**
     * Weapons still holding a row from before the sockets: its first active rune, modifier and passive are set in, and
     * the rest come back as items. The player is told once a session. Gear keeping runes that don't fit it since 0.22.6
     * (a sword's active on a bow, Lantern or Thirst in a weapon's passive socket, where armor runes now go) hands those
     * back, and the player is told which.
     */
    private static void convertOldRows(ServerPlayer p, Caster c) {
        net.minecraft.world.entity.player.Inventory inv = p.getInventory();
        boolean any = false;
        List<RuneId> back = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (RuneTags.hasOldRow(s.getTag())) {
                if (RuneGear.convertOldRow(s, p)) any = true;
            } else if (RuneGear.takesRunes(s) && RuneGear.hasRunes(s)) {
                back.addAll(RuneGear.handBack(s, p));
            }
        }
        if (any && !c.toldConverted) {
            c.toldConverted = true;
            p.sendSystemMessage(Component.translatable("message.explorationremix.runes.converted").withStyle(st -> st.withColor(0xBFD9FF)));
        }
        if (!back.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (RuneId r : back) names.add(r.title);
            p.sendSystemMessage(Component.translatable("message.explorationremix.runes.handed_back", String.join(", ", names))
                    .withStyle(st -> st.withColor(0xBFD9FF)));
        }
    }

    /**
     * Throws the player (a dash, a blast). A speed set in the middle of a tick, as a carrier's payload is, would be
     * slowed by the server's own move of the player before it is sent; this keeps it until it goes. A throw ends a pull.
     */
    static void launch(ServerPlayer p, Vec3 v) {
        Caster c = caster(p);
        Grapples.letGo(p, c);
        c.launch = v;
        p.setDeltaMovement(v);
        p.hurtMarked = true;
    }

    /**
     * The end of each server tick: what was set to run now (aftershocks, a wave's stretches, the hits that wait
     * for the end of the tick), payloads that waited their turn, carriers left on still ground, the arrows' casts.
     */
    public static void serverTick(MinecraftServer server) {
        long tick = server.getTickCount();
        for (Caster c : new ArrayList<>(CASTERS.values())) {
            if (!c.later.isEmpty()) runLater(c, tick);
            if (!c.queue.isEmpty()) Executor.drain(c, tick);
            if (!c.carriers.isEmpty() || !c.stillSince.isEmpty()) dropStill(c, tick);
        }
        ArrowRides.tick();
        Recall.tick(server);
        RunePlants.tick(server);
    }

    /** Runs what is due, in the order it was set; what that sets to run now waits for the next tick. */
    private static void runLater(Caster c, long tick) {
        List<Caster.Later> due = null;
        for (Iterator<Caster.Later> it = c.later.iterator(); it.hasNext(); ) {
            Caster.Later l = it.next();
            if (l.at() > tick) continue;
            if (due == null) due = new ArrayList<>();
            due.add(l);
            it.remove();
        }
        if (due != null) for (Caster.Later l : due) l.run().run();
    }

    /** How long a carrier may wait on ground that isn't running before it goes (five seconds). */
    private static final int STILL_TICKS = 100;

    /**
     * A bolt or field whose ground stopped running while it was out (its caster went far off, past the simulation
     * distance), or hasn't started yet (a field cast into ground still loading after a teleport): it waits a few
     * seconds for it to run, and then, rather than hang there holding its slot, it goes, its runes spent.
     */
    private static void dropStill(Caster c, long tick) {
        if (!c.stillSince.isEmpty()) c.stillSince.keySet().retainAll(c.carriers);
        for (Entity e : new ArrayList<>(c.carriers)) {
            if (e.isRemoved()) {
                c.carriers.remove(e);
                c.stillSince.remove(e);
            } else if (e.level() instanceof ServerLevel l && !l.isPositionEntityTicking(e.blockPosition())) {
                if (tick - c.stillSince.computeIfAbsent(e, k -> tick) < STILL_TICKS) continue;
                e.discard();
                // a carrier on ground nobody is near isn't told it went, so its slot is freed here
                c.carriers.remove(e);
                c.stillSince.remove(e);
            } else {
                c.stillSince.remove(e);
            }
        }
    }

    private static void trackHands(ServerPlayer p, Caster c, long now) {
        update(c.main, handGear(p, InteractionHand.MAIN_HAND), p.getInventory().selected, now);
        update(c.off, handGear(p, InteractionHand.OFF_HAND), 40, now);
    }

    private static int handGear(ServerPlayer p, InteractionHand hand) {
        ItemStack s = p.getItemInHand(hand);
        if (!RuneGear.isGear(s) || !RuneGear.hasRunes(s)) return 0;
        return RuneGear.ensureGearId(s, p.getRandom());
    }

    private static void update(Caster.Hand h, int id, int slot, long now) {
        if (h.id != id || h.slot != slot) {
            h.id = id;
            h.slot = slot;
            h.since = now;
        }
    }

    // ------------------------------------------------------------------------------------------------ leaving

    /** The player left: their runes in flight end, their blocks stop counting, their arrows fly plain. */
    public static void forget(ServerPlayer p) {
        Caster c = CASTERS.remove(p.getUUID());
        if (c != null) {
            for (Entity e : new ArrayList<>(c.carriers)) e.discard();
            c.queue.clear();
            c.later.clear();
        }
        ArrowRides.forget(p.getUUID());
        Recall.forget(p.getUUID());
        TempBlocks.forget(p.getUUID());
    }

    public static void clear() {
        CASTERS.clear();
        ArrowRides.clear();
        Recall.clear();
        TempBlocks.clear();
        RunePlants.clear();
        Statuses.clear();
        RuneMote.clearAll();
    }
}
