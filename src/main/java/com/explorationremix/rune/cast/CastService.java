package com.explorationremix.rune.cast;

import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.effect.RuneFx;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
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
 * <p>Also here: a full-strength sword hit on a creature fighting you charges the sword; a bow regains energy while it
 * is held; the run depth is checked once a second. Fake players (a Create deployer holding a sword) never cast or
 * charge.
 */
public final class CastService {
    private static final Map<UUID, Caster> CASTERS = new HashMap<>();

    public enum Result { CAST, WAIT, NONE }

    private CastService() {}

    static Caster caster(ServerPlayer p) { return CASTERS.computeIfAbsent(p.getUUID(), u -> new Caster()); }

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
    }

    /**
     * An arrow came into the world: if a player's runed bow loosed it fully drawn, or their runed crossbow fired it,
     * the gear casts and the cast rides the arrow. Anything else (a half-drawn shot, a bow still recovering, short of
     * energy) stays a plain arrow.
     */
    public static void arrowLoosed(AbstractArrow arrow) {
        if (ArrowRides.spawning() || !(arrow.getOwner() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        Caster c = CASTERS.get(p.getUUID());
        if (c == null) return;
        long now = p.level().getGameTime();
        InteractionHand hand;
        boolean full;
        if (p.isUsingItem() && RuneGear.host(p.getUseItem()) == Host.BOW) {
            hand = p.getUsedItemHand();
            full = arrow.isCritArrow();
        } else if (c.crossbowTick == now && c.crossbowHand != null && arrow.shotFromCrossbow()) {
            hand = c.crossbowHand;
            full = true;
            c.crossbowTick = -1;                 // a multishot's other arrows fly plain
        } else {
            return;
        }
        ItemStack gear = p.getItemInHand(hand);
        if (!full || !RuneGear.isGear(gear) || !RuneGear.hasRunes(gear) || p.containerMenu instanceof RuneEditorMenu) return;
        if (!inHandLongEnough(p, c, hand, gear, now)) return;
        cast(p, c, hand, gear, now, arrow);
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

    /** The cast leaves the hand: the arm flicks, a spell sound, a puff of the first rune's colour. */
    private static void castFx(ServerPlayer p, InteractionHand hand, Plan plan) {
        if (plan.roots.isEmpty()) return;
        Act first = plan.roots.get(0).act;
        Vec3 at = p.getEyePosition().add(p.getLookAngle().scale(0.8)).add(0, -0.3, 0);
        RuneFx.puff(p.serverLevel(), at, first.rune, first.element, 4, 0.08);
        if (first.rune.shape == Shape.BOLT || first.rune == RuneId.REEL) {
            RuneFx.play(p.serverLevel(), at, SoundEvents.ILLUSIONER_CAST_SPELL, 0.45F, 1.5F + p.getRandom().nextFloat() * 0.3F);
        }
        p.swing(hand, true);
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

    // ------------------------------------------------------------------------------------------------ the sword's charge

    /** The player swings at {@code target}: noted, so the damage that follows knows whether it was a full-strength hit. */
    public static void swing(ServerPlayer p, Entity target) {
        if (p instanceof FakePlayer) return;
        Caster c = caster(p);
        LivingEntity body = RuneHits.body(target);
        c.swingTarget = body == null ? -1 : body.getId();
        c.swingTick = p.level().getGameTime();
        c.swingFull = p.getAttackStrengthScale(0.5F) >= 0.9F;
    }

    /** Melee damage landed: a full-strength hit with a runed sword on a creature fighting you charges the sword. */
    public static void meleeDamage(ServerPlayer p, LivingEntity target, float amount) {
        Caster c = CASTERS.get(p.getUUID());
        if (c == null || amount <= 0.0F || p instanceof FakePlayer) return;
        long now = p.level().getGameTime();
        if (c.swingTarget != target.getId() || c.swingTick != now || !c.swingFull) return;
        c.swingTarget = -1;
        ItemStack gear = p.getMainHandItem();
        if (RuneGear.host(gear) != Host.SWORD || !RuneGear.hasRunes(gear) || !RuneHits.isFoe(p, target)) return;
        HostSpec spec = RuneGear.spec(gear);
        if (spec == null) return;
        float add = (float) Math.min(RuneConfig.get(RuneConfig.INSTANCE.swordChargeMaxPerHit), amount * RuneConfig.get(RuneConfig.INSTANCE.swordChargePerDamage));
        CastState s = RuneGear.state(gear, spec, now);
        float got = CastMachine.add(s, spec, now, add);
        if (got <= 0.0F) return;
        RuneGear.ensureGearId(gear, p.getRandom());
        RuneGear.setState(gear, s);
        float fill = s.energy / Math.max(1.0F, spec.maxEnergy());
        p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 0.7F + fill);
        Vec3 at = target.position().add(0, target.getBbHeight() * 0.6, 0);
        p.serverLevel().sendParticles(RuneFx.dust(0xFFFFFF, 0.7F), at.x, at.y, at.z, 5, 0.25, 0.25, 0.25, 0.0);
    }

    // ------------------------------------------------------------------------------------------------ ticks

    /** Each player's tick: which gear is in hand, dashes, safe landings from their own throws, and once a second the run depth. */
    public static void tick(ServerPlayer p) {
        if (p instanceof FakePlayer) return;
        Caster c = caster(p);
        long now = p.level().getGameTime();
        trackHands(p, c, now);
        if (!c.dashes.isEmpty() || c.airDashUsed) Dashes.tick(p, c, now);
        if (now <= c.safeUntil) p.fallDistance = 0.0F;
        if (c.airBlastUsed && (p.onGround() || p.isInWater())) c.airBlastUsed = false;
        if (p.tickCount % 20 == 0) depth(p);
    }

    /** The end of each server tick: payloads that waited their turn, and the arrows' casts. */
    public static void serverTick(MinecraftServer server) {
        long tick = server.getTickCount();
        for (Caster c : CASTERS.values()) if (!c.queue.isEmpty()) Executor.drain(c, tick);
        ArrowRides.tick();
    }

    private static void trackHands(ServerPlayer p, Caster c, long now) {
        int beforeMain = c.main.id, beforeOff = c.off.id;
        int main = handGear(p, InteractionHand.MAIN_HAND), off = handGear(p, InteractionHand.OFF_HAND);
        // gear put away stops regaining energy; gear taken up starts
        if (beforeMain != 0 && beforeMain != main && beforeMain != off) putAway(p, beforeMain, now);
        if (beforeOff != 0 && beforeOff != main && beforeOff != off && beforeOff != beforeMain) putAway(p, beforeOff, now);
        if (main != 0 && main != beforeMain && main != beforeOff) takeUp(p.getMainHandItem(), now);
        if (off != 0 && off != beforeMain && off != beforeOff && off != main) takeUp(p.getOffhandItem(), now);
        update(c.main, main, p.getInventory().selected, now);
        update(c.off, off, 40, now);
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

    /** Taken in hand: a bow starts regaining energy. Anything it says about regaining while it was away is stale. */
    private static void takeUp(ItemStack gear, long now) {
        HostSpec spec = RuneGear.spec(gear);
        if (spec == null || spec.regenPerTick() <= 0) return;
        CastState s = RuneGear.state(gear, spec, now);
        s.regenSince = -1;
        CastMachine.setHeld(s, spec, now, true);
        RuneGear.setState(gear, s);
    }

    /** Put away: a bow stops regaining energy (if the player still has it; if not, it is stale and dropped later). */
    private static void putAway(ServerPlayer p, int id, long now) {
        ItemStack gear = RuneGear.findGear(p, id);
        if (gear == null) return;
        HostSpec spec = RuneGear.spec(gear);
        if (spec == null || spec.regenPerTick() <= 0) return;
        CastState s = RuneGear.state(gear, spec, now);
        CastMachine.setHeld(s, spec, now, false);
        RuneGear.setState(gear, s);
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
    }
}
