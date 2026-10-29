package com.explorationremix.run;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.network.ERNetwork;
import com.explorationremix.network.RunSummaryPacket;
import com.explorationremix.registry.ERTags;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.CellPlan;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import com.explorationremix.network.RunStatePacket;
import com.explorationremix.rune.RuneIds;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.RuneTool;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a run when a player passes below the first band, tracks what happens, and ends it on death, on reaching
 * bedrock, or on climbing back above the first band. Inventory is kept on death; runes and XP are what you lose.
 */
public final class RunManager {
    private RunManager() {}

    public static RunState state(ServerPlayer p) { return RunData.get(p.server).state(p.getUUID()); }

    /** The run id the player is in, or 0. */
    public static long currentRun(ServerPlayer p) {
        RunState s = state(p);
        return s.active ? s.runId : 0L;
    }

    public static long currentRun(@Nullable LivingEntity e) {
        return e instanceof ServerPlayer p ? currentRun(p) : 0L;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || !DeepWorld.installed()) return;
        if (p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        if (p.isSpectator()) return;
        if (p.tickCount % 10 != 0) return;
        RunState s = state(p);
        int y = p.getBlockY();
        if (!s.active) {
            if (y < DeepWorld.band1Bottom() && !p.isCreative()) start(p, s);
            return;
        }
        // Progress tracking.
        int depth = DeepWorld.depthIndex(y);
        if (depth > s.deepestLayer) {
            s.deepestLayer = depth;
            Layer l = DeepWorld.layers().get(depth);
            p.displayClientMessage(Component.translatable("run.explorationremix.entered", Component.translatable(l.displayKey())).withStyle(ChatFormatting.GOLD), true);
            growSlots(p, s);
            RunData.get(p.server).setDirty();
            syncState(p, s);
        }
        int band = DeepWorld.bandAt(y);
        if (band != 0) {
            long bit = 1L << band;
            if ((s.lastBandCrossed & bit) == 0) {
                s.lastBandCrossed |= bit;
                s.gatesPassed++;
                RunXp.award(p, ERConfig.INSTANCE.xpPerGate.get(), s);
                p.displayClientMessage(Component.translatable("run.explorationremix.gate", band).withStyle(ChatFormatting.AQUA), true);
                RunData.get(p.server).setDirty();
            }
        }
        if (y >= DeepWorld.packFloor()) {
            end(p, s, "climbed_out");
            return;
        }
        // Bedrock landing: the deepest layer's goal chamber.
        Layer last = DeepWorld.layers().get(DeepWorld.layers().size() - 1);
        if (last.contains(y)) {
            CellPlan plan = DeepWorld.plans().cellAt(last.index(), p.getX(), p.getZ());
            if (plan.bedrockLanding != null && plan.bedrockLanding.dist(p.getX(), p.getY(), p.getZ()) < -2) {
                end(p, s, "bedrock");
                if (ERConfig.INSTANCE.returnHomeFromBedrock.get()) {
                    p.displayClientMessage(Component.translatable("run.explorationremix.going_home").withStyle(ChatFormatting.GOLD), false);
                    HomeReturn.schedule(p, 100);
                }
            }
        }
    }

    public static void start(ServerPlayer p, RunState s) {
        RunData data = RunData.get(p.server);
        s.runId = data.newRunId();
        s.active = true;
        s.startedAt = p.level().getGameTime();
        s.endedAt = 0;
        s.deepestLayer = -1;
        s.gatesPassed = 0; s.kills = 0; s.xpEarned = 0; s.xpSpent = 0; s.runeBlocksWoken = 0; s.damageTaken = 0;
        s.endReason = "";
        s.runesTaken.clear();
        s.lastBandCrossed = 0;
        data.setDirty();
        RunXp.clear(p);
        starterSet(p, s);
        syncState(p, s);
        p.displayClientMessage(Component.translatable("run.explorationremix.started").withStyle(ChatFormatting.GOLD), false);
        p.playNotifySound(SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6F, 0.6F);
        ExplorationRemix.LOGGER.info("Run {} started for {}", s.runId, p.getGameProfile().getName());
    }

    public static void end(ServerPlayer p, RunState s, String reason) {
        if (!s.active) return;
        s.active = false;
        s.endedAt = p.level().getGameTime();
        s.endReason = reason;
        RunData.get(p.server).setDirty();
        RunSummary summary = RunSummary.of(s, s.endedAt);
        ERNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RunSummaryPacket(summary));
        RunXp.clear(p);
        syncState(p, s);
        com.explorationremix.creature.NestRegrowth.onRunEnded(p.server, s.runId);
        ExplorationRemix.LOGGER.info("Run {} ended for {} ({})", s.runId, p.getGameProfile().getName(), reason);
    }

    /** Total rune slots a tool gets this run: sentences times slots, plus one per sentence for each layer reached. */
    public static int slotsFor(RunState s) {
        int sentences = ERConfig.INSTANCE.startingSentences.get();
        return sentences * (ERConfig.INSTANCE.startingSlots.get() + Math.max(0, s.deepestLayer));
    }

    private static void growSlots(ServerPlayer p, RunState s) {
        int slots = slotsFor(s);
        for (ItemStack stack : p.getInventory().items) if (RuneTool.canHoldRunes(stack)) RuneTool.setSlots(stack, slots);
        for (ItemStack stack : p.getInventory().offhand) if (RuneTool.canHoldRunes(stack)) RuneTool.setSlots(stack, slots);
    }

    /** The weapon in hand gets a weak starter set so the first fight already has a sentence or two. */
    private static void starterSet(ServerPlayer p, RunState s) {
        ItemStack weapon = p.getMainHandItem();
        if (!RuneTool.canHoldRunes(weapon)) return;
        List<ResourceLocation> starter = new ArrayList<>();
        for (String id : List.of(RuneIds.ON_HIT, RuneIds.SPARK, RuneIds.ON_JUMP, RuneIds.LEAP)) {
            ResourceLocation rl = new ResourceLocation(ExplorationRemix.MOD_ID, id);
            if (RuneRegistry.get(rl) != null) starter.add(rl);
        }
        RuneTool.setSlots(weapon, slotsFor(s));
        RuneTool.setRunes(weapon, starter, s.runId);
        for (ResourceLocation r : starter) s.runesTaken.add(r.toString());
    }

    public static void syncState(ServerPlayer p, RunState s) {
        ERNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RunStatePacket(s.active ? s.runId : 0L, s.active ? s.deepestLayer : -1,
                s.active ? s.startedAt : 0L, DeepWorld.packFloor(), DeepWorld.installed()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) syncState(p, state(p));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            RunState s = state(p);
            if (s.active && ERConfig.INSTANCE.endRunOnLogout.get()) end(p, s, "logout");
        }
    }

    @SubscribeEvent
    public static void onDimensionTravel(EntityTravelToDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && ERConfig.INSTANCE.blockDimensionTravelDuringRun.get()) {
            RunState s = state(p);
            if (s.active && e.getDimension() != net.minecraft.world.level.Level.OVERWORLD) end(p, s, "dimension");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            RunState s = state(p);
            if (s.active) end(p, s, "death");
        } else if (e.getSource().getEntity() instanceof ServerPlayer killer) {
            RunState s = state(killer);
            if (s.active) {
                s.kills++;
                if (e.getEntity().getType().is(ERTags.REMIX_CREATURES)) {
                    int depth = Math.max(0, s.deepestLayer);
                    int xp = (int) Math.round(ERConfig.INSTANCE.xpPerKillBase.get() * Math.pow(ERConfig.INSTANCE.xpPerLayerMultiplier.get(), depth));
                    RunXp.award(killer, xp, s);
                } else if (killer.getBlockY() >= DeepWorld.packFloor()) {
                    RunXp.award(killer, ERConfig.INSTANCE.xpSurfaceClusterKill.get(), s);
                }
                RunData.get(killer.server).setDirty();
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            RunState s = state(p);
            if (s.active) s.damageTaken += Math.round(e.getAmount());
        }
    }

    // --- keep inventory on death ------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        if (ERConfig.INSTANCE.keepInventory.get() && e.getEntity() instanceof ServerPlayer p && !p.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onXpDrop(LivingExperienceDropEvent e) {
        if (e.getEntity() instanceof ServerPlayer) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        if (!e.isWasDeath() || !ERConfig.INSTANCE.keepInventory.get()) return;
        if (!(e.getEntity() instanceof ServerPlayer fresh) || !(e.getOriginal() instanceof ServerPlayer old)) return;
        if (old.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        old.reviveCaps();
        fresh.getInventory().replaceWith(old.getInventory());
        old.invalidateCaps();
    }

    public static MinecraftServer server(ServerPlayer p) { return p.server; }
}
