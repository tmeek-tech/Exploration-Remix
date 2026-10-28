package com.explorationremix.rune.cast;

import com.explorationremix.combat.CombatTracker;
import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneGrammar;
import com.explorationremix.rune.RuneIds;
import com.explorationremix.rune.RuneTool;
import com.explorationremix.run.RunManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/** Wires the game's events to rune triggers, and applies the three passives. */
public final class RuneEvents {
    private RuneEvents() {}

    /** Passive rune count on a held tool, alive in the player's current run. */
    private static int passiveCount(ServerPlayer p, ItemStack tool, String id) {
        long run = RunManager.currentRun(p);
        if (run == 0L || !RuneTool.canHoldRunes(tool)) return 0;
        int n = 0;
        for (Rune r : RuneTool.liveRunes(tool, run)) if (r.id().getPath().equals(id)) n++;
        return n;
    }

    private static int passiveCountEitherHand(ServerPlayer p, String id) {
        return passiveCount(p, p.getMainHandItem(), id) + passiveCount(p, p.getOffhandItem(), id);
    }

    private static Rune passive(ServerPlayer p, String id) {
        long run = RunManager.currentRun(p);
        for (InteractionHand h : InteractionHand.values())
            for (Rune r : RuneTool.liveRunes(p.getItemInHand(h), run)) if (r.id().getPath().equals(id)) return r;
        return null;
    }

    // ------------------------------------------------------------------ hits, kills, hurt

    /** Runs late so the combat layer has already decided momentum and stagger. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent e) {
        if (RuneCaster.isCasting()) return;
        LivingEntity victim = e.getEntity();
        if (e.getSource().getEntity() instanceof ServerPlayer attacker && e.getSource().is(DamageTypes.PLAYER_ATTACK) && victim != attacker) {
            ItemStack weapon = attacker.getMainHandItem();
            // Keen Edge: more damage per rune.
            int keen = passiveCount(attacker, weapon, RuneIds.KEEN_EDGE);
            if (keen > 0) {
                Rune r = passive(attacker, RuneIds.KEEN_EDGE);
                e.setAmount(e.getAmount() * (float) (1.0 + r.param("damage", 0.5) * keen));
            }
            TriggerContext ctx = TriggerContext.of(attacker, victim).withDamage(e.getAmount());
            RuneCaster.fireTool(attacker, weapon, RuneIds.ON_HIT, ctx);
            CombatTracker.State s = CombatTracker.of(attacker);
            long now = attacker.level().getGameTime();
            boolean finisher = CombatTracker.isFinisher(attacker);
            if (!finisher && !com.explorationremix.compat.BetterCombatCompat.loaded()) {
                if (now - s.lastHitTime > 40) s.comboCount = 0;
                s.comboCount++;
                finisher = s.comboCount % 3 == 0;
            }
            s.lastHitTime = now;
            if (finisher) {
                s.finisherWindowUntil = 0;
                RuneCaster.fireTool(attacker, weapon, RuneIds.ON_COMBO_FINISHER, ctx);
            }
        }
        if (victim instanceof ServerPlayer p) {
            // Thick Hide: less damage while the tool is held.
            int hide = passiveCountEitherHand(p, RuneIds.THICK_HIDE);
            if (hide > 0) {
                Rune r = passive(p, RuneIds.THICK_HIDE);
                e.setAmount(e.getAmount() * (float) Math.pow(1.0 - r.param("reduction", 0.3), hide));
            }
            LivingEntity attacker = e.getSource().getEntity() instanceof LivingEntity le && le != p ? le : null;
            RuneCaster.fire(p, RuneIds.WHEN_HURT, TriggerContext.of(p, attacker).withDamage(e.getAmount()));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (RuneCaster.isCasting()) return;
        if (e.getSource().getEntity() instanceof ServerPlayer killer && e.getEntity() != killer) {
            RuneCaster.fireTool(killer, killer.getMainHandItem(), RuneIds.ON_KILL, TriggerContext.of(killer, e.getEntity()));
        }
    }

    @SubscribeEvent
    public static void onBlock(ShieldBlockEvent e) {
        if (RuneCaster.isCasting() || !(e.getEntity() instanceof ServerPlayer p)) return;
        LivingEntity attacker = e.getDamageSource().getEntity() instanceof LivingEntity le ? le : null;
        RuneCaster.fire(p, RuneIds.ON_BLOCK, TriggerContext.of(p, attacker).withDamage(e.getBlockedDamage()));
    }

    // ------------------------------------------------------------------ movement

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && !RuneCaster.isCasting()) RuneCaster.fire(p, RuneIds.ON_JUMP, TriggerContext.of(p, null));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || RuneCaster.isCasting()) return;
        if (RunManager.currentRun(p) == 0L) return;
        if (p.fallDistance > 3.0F && !p.onGround() && !p.isInWater()) RuneCaster.fire(p, RuneIds.WHILE_FALLING, TriggerContext.of(p, null));
        if (p.isShiftKeyDown() && p.tickCount % 5 == 0) RuneCaster.fire(p, RuneIds.WHILE_SNEAKING, TriggerContext.of(p, null));
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (SafeLanding.isSafe(p)) {
            SafeLanding.consume(p);
            e.setDamageMultiplier(0);
            e.setCanceled(true);
            return;
        }
        if (passiveCountEitherHand(p, RuneIds.CATS_FEET) > 0) {
            e.setDamageMultiplier(0);
            e.setCanceled(true);
        }
    }

    /** The sentences a tool holds, parsed, for other systems (tooltips, HUD). */
    public static RuneGrammar.Parsed parsedFor(ItemStack tool, long run) {
        List<Rune> live = RuneTool.liveRunes(tool, run);
        return RuneGrammar.parse(live);
    }
}
