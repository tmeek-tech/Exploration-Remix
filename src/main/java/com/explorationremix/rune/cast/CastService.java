package com.explorationremix.rune.cast;

import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Limits;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Shape;
import com.explorationremix.rune.menu.RuneEditorMenu;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where gear casts, on the server, which alone decides: a sword when its player right-clicks (and again each time it
 * is ready while they hold the button), a bow or crossbow when it looses a fully drawn shot. Every cast is paid in full
 * from the gear's own energy before anything happens (CastMachine), and only gear that has been in the hand for
 * {@code swapTicks} casts, so swapping between pieces of gear can't skip their delays.
 *
 * <p>Also here: a hit with runed gear on a creature fighting you (a sword's swing, a bow's or crossbow's arrow) knocks
 * energy loose as motes that fly back into that gear; the run depth is checked once a second. Fake players (a Create
 * deployer holding a sword) never cast or fill gear.
 */
public final class CastService {
    private static final Map<UUID, Caster> CASTERS = new HashMap<>();

    public enum Result { CAST, WAIT, NONE }

    private CastService() {}

    static Caster caster(ServerPlayer p) { return CASTERS.computeIfAbsent(p.getUUID(), u -> new Caster()); }

    @Nullable
    static Caster casterOrNull(ServerPlayer p) { return CASTERS.get(p.getUUID()); }

    // ------------------------------------------------------------------------------------------------ the sword

    /**
     * Whether a right-click with the sword in {@code hand} casts it (both sides ask, and answer alike). The other hand
     * keeps its turn when the player sneaks with something in it, holds a shield there (tap to cast, hold to block),
     * or holds a block there while aiming at a block (to place it).
     */
    public static boolean swordTakesRightClick(Player p, InteractionHand hand) {
        ItemStack gear = p.getItemInHand(hand);
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear)) return false;
        if (hand == InteractionHand.MAIN_HAND) {
            ItemStack off = p.getOffhandItem();
            if (!off.isEmpty()) {
                if (p.isShiftKeyDown()) return false;
                if (off.canPerformAction(ToolActions.SHIELD_BLOCK)) return false;
                if (off.getItem() instanceof BlockItem && p.pick(p.getBlockReach(), 1.0F, false).getType() == HitResult.Type.BLOCK) return false;
            }
        }
        return true;
    }

    /** The player casts the sword in this hand: a right-click, held or tapped. */
    public static Result castSword(ServerPlayer p, InteractionHand hand) {
        if (p instanceof FakePlayer) return Result.NONE;
        ItemStack gear = p.getItemInHand(hand);
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear)) return Result.NONE;
        if (p.isSpectator() || !p.isAlive() || p.containerMenu instanceof RuneEditorMenu) return Result.NONE;
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

    /** On an arrow runed gear loosed (never on a copy Fan made): the gear's id, which its hit fills. */
    public static final String GEAR_TAG = "explorationremix_gear";

    /**
     * An arrow came into the world: if a player's runed bow loosed it fully drawn, or their runed crossbow fired it,
     * the gear casts and the cast rides the arrow. Anything else (a half-drawn shot, a bow still recovering, short of
     * energy) stays a plain arrow. Either way, when it hits a creature fighting its player it fills the gear.
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
        if (full && !(p.containerMenu instanceof RuneEditorMenu) && inHandLongEnough(p, c, hand, gear, now)) cast(p, c, hand, gear, now, arrow);
        // marked once the cast has made its copies, so only the arrow itself fills the gear
        arrow.getPersistentData().putInt(GEAR_TAG, id);
    }

    // ------------------------------------------------------------------------------------------------ casting

    /** Tries the gear's next cast; for a bow or crossbow {@code arrow} is the shot that carries it. */
    private static Result cast(ServerPlayer p, Caster c, InteractionHand hand, ItemStack gear, long now, @Nullable AbstractArrow arrow) {
        HostSpec spec = RuneGear.spec(gear);
        Reading r = RuneGear.reading(gear, RunDepth.get(p));
        if (spec == null || r == null) return Result.NONE;
        Limits lim = RuneConfig.limits();
        if (r.casts().isEmpty()) {
            if (arrow == null) tell(p, c, now, Component.translatable("message.explorationremix.runes.nothing"));
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
        // checks that cost nothing: too many runes out already, a second dash in the air
        int out = 0;
        boolean dash = false;
        for (Plan.Node n : plan.roots) {
            Shape sh = n.act.rune.shape;
            if ((sh == Shape.BOLT && n.act.rune != RuneId.ARROW) || sh == Shape.FIELD) out++;
            if (sh == Shape.SELF) dash = true;
        }
        if (c.carriers.size() + out > RuneConfig.get(RuneConfig.INSTANCE.maxCarriers)) {
            tell(p, c, now, Component.translatable("message.explorationremix.runes.busy"));
            return Result.WAIT;
        }
        if (dash && !Dashes.canDash(p, c)) return Result.WAIT;
        // the caster's own ground isn't running yet (the first ticks after a long teleport): wait, rather than pay for
        // a cast that could only fizzle
        if (!p.serverLevel().isPositionEntityTicking(p.blockPosition())) return Result.WAIT;
        CastMachine.Outcome o = CastMachine.attempt(s, r, now, false, lim);
        if (o.kind() == CastMachine.Kind.SPUTTER) {
            sputter(p, c, now, o);
            return Result.WAIT;
        }
        if (!o.fired()) return Result.WAIT;
        int id = RuneGear.ensureGearId(gear, p.getRandom());
        RuneGear.setState(gear, s);
        CastContext ctx = new CastContext(p, spec.host(), spec.power(), id, cast, o.cost());
        if (arrow != null) {
            ArrowRides.shoot(ctx, plan.roots, arrow);
        } else {
            castFx(p, hand, plan);
            Executor.cast(ctx, plan.roots, Origin.eyes(p));
        }
        return Result.CAST;
    }

    /** The cast leaves the hand: the arm flicks, a spell sound, a flash and sparks of the first rune's colour at the hand. */
    private static void castFx(ServerPlayer p, InteractionHand hand, Plan plan) {
        if (plan.roots.isEmpty()) return;
        Act first = plan.roots.get(0).act;
        Vec3 look = p.getLookAngle();
        // beside the view, where the hand is, not over the crosshair
        boolean right = (hand == InteractionHand.MAIN_HAND) == (p.getMainArm() == HumanoidArm.RIGHT);
        Vec3 side = Origin.flat(new Vec3(-look.z, 0, look.x)).scale(right ? 0.45 : -0.45);
        Vec3 at = p.getEyePosition().add(look.scale(0.9)).add(side).add(0, -0.45, 0);
        RuneFx.cast(p.serverLevel(), at, look, first.rune, first.element, first.rune.shape == Shape.BOLT || first.rune == RuneId.REEL, p.getId(), hand);
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

    // ------------------------------------------------------------------------------------------------ hits fill gear

    /** Melee damage landed: a runed sword's hit on a creature fighting you knocks energy loose into the sword. */
    public static void meleeDamage(ServerPlayer p, LivingEntity target, float amount) {
        if (p instanceof FakePlayer) return;
        ItemStack gear = p.getMainHandItem();
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear)) return;
        knockLoose(p, target, amount, RuneGear.ensureGearId(gear, p.getRandom()));
    }

    /** An arrow's own hit landed: if runed gear loosed it, the hit knocks energy loose into that gear. */
    public static void arrowDamage(ServerPlayer p, AbstractArrow arrow, LivingEntity target, float amount) {
        if (p instanceof FakePlayer) return;
        knockLoose(p, target, amount, arrow.getPersistentData().getInt(GEAR_TAG));
    }

    /**
     * A hit for {@code amount} with the gear {@code gearId} on {@code target}: if it is fighting the player, energy for
     * the damage it took flies back as motes. No more than the creature had left counts (still its health before this
     * hit), so overkill on a weak one gives nothing extra.
     */
    private static void knockLoose(ServerPlayer p, LivingEntity target, float amount, int gearId) {
        if (gearId == 0 || amount <= 0.0F || !RuneHits.isFoe(p, target)) return;
        float dealt = Math.min(amount, target.getHealth());
        float energy = (float) Math.min(RuneConfig.get(RuneConfig.INSTANCE.energyMaxPerHit), dealt * RuneConfig.get(RuneConfig.INSTANCE.energyPerDamage));
        if (energy <= 0.0F) return;
        RuneMote.spawn(p.serverLevel(), target.position().add(0, target.getBbHeight() * 0.6, 0), p, gearId, energy);
    }

    // ------------------------------------------------------------------------------------------------ ticks

    /**
     * Each player's tick, at its end (after the server has moved them): which gear is in hand, dashes, a Reel's pull,
     * throws set this tick, safe landings from their own blasts, and once a second the run depth.
     */
    public static void tick(ServerPlayer p) {
        if (p instanceof FakePlayer) return;
        Caster c = caster(p);
        long now = p.level().getGameTime();
        trackHands(p, c, now);
        if (!c.dashes.isEmpty() || c.airDashUsed) Dashes.tick(p, c, now);
        if (c.pull != null) Grapples.tick(p, c, now);
        if (c.launch != null) {
            // not sent yet: the move the server just made slowed it (by ground friction, even), so it goes as it was set
            if (p.hurtMarked) p.setDeltaMovement(c.launch);
            c.launch = null;
        }
        if (now <= c.safeUntil && p.getY() > c.safeAboveY) p.fallDistance = 0.0F;
        if (c.airBlastUsed && (p.onGround() || p.isInWater())) c.airBlastUsed = false;
        if (c.airPullUsed && c.pull == null && now - c.pullTick > 4 && (p.onGround() || p.isInWater())) c.airPullUsed = false;
        if (p.tickCount % 20 == 0) depth(p);
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

    /** The end of each server tick: payloads that waited their turn, carriers left on still ground, the arrows' casts. */
    public static void serverTick(MinecraftServer server) {
        long tick = server.getTickCount();
        for (Caster c : CASTERS.values()) {
            if (!c.queue.isEmpty()) Executor.drain(c, tick);
            if (!c.carriers.isEmpty() || !c.stillSince.isEmpty()) dropStill(c, tick);
        }
        ArrowRides.tick();
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

    private static void depth(ServerPlayer p) {
        int before = RunDepth.get(p);
        RunDepth.update(p);
        int after = RunDepth.get(p);
        if (after > before) {
            String name = Explain.depthName(after);
            p.displayClientMessage(Component.translatable("message.explorationremix.runes.deeper",
                    Character.toUpperCase(name.charAt(0)) + name.substring(1)), true);
            p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.8F);
        } else if (after == 0 && before > 0) {
            p.displayClientMessage(Component.translatable("message.explorationremix.runes.home"), true);
        }
    }

    // ------------------------------------------------------------------------------------------------ leaving

    /** The player left: their runes in flight end, their blocks stop counting, their arrows fly plain. */
    public static void forget(ServerPlayer p) {
        Caster c = CASTERS.remove(p.getUUID());
        if (c != null) {
            for (Entity e : new ArrayList<>(c.carriers)) e.discard();
            c.queue.clear();
        }
        ArrowRides.forget(p.getUUID());
        TempBlocks.forget(p.getUUID());
    }

    public static void clear() {
        CASTERS.clear();
        ArrowRides.clear();
        TempBlocks.clear();
        Statuses.clear();
        RuneMote.clearAll();
    }
}
