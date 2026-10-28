package com.explorationremix.loophole;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERTags;
import com.explorationremix.run.RunManager;
import com.explorationremix.run.RunState;
import com.explorationremix.world.DeepWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;

/**
 * Closes the ways around the layers: no portals or beds below the pack's floor, no teleport through a band, no
 * vanilla hostile spawns or spawner farms in the overworld, and creative mode is not a run.
 */
public final class LoopholeEvents {
    private LoopholeEvents() {}

    private static void tell(Player p, String key) {
        p.displayClientMessage(Component.translatable("run.explorationremix." + key).withStyle(ChatFormatting.GRAY), true);
    }

    // ------------------------------------------------------------------ portals

    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent e) {
        if (!ERConfig.INSTANCE.blockPortalsBelowBand.get() || !DeepWorld.installed()) return;
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (e.getPos().getY() >= DeepWorld.packFloor()) return;
        e.setCanceled(true);
        BlockPos pos = e.getPos();
        for (Player p : level.getEntitiesOfClass(Player.class, new AABB(pos).inflate(8))) tell(p, "no_portal");
    }

    /** Whatever brought a player back into the overworld, they never arrive under the pack's floor. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!ERConfig.INSTANCE.blockPortalsBelowBand.get() || !DeepWorld.installed()) return;
        if (e.getTo() != Level.OVERWORLD || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (p.getBlockY() >= DeepWorld.packFloor()) return;
        ServerLevel level = p.serverLevel();
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockPosition());
        p.teleportTo(level, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, p.getYRot(), p.getXRot());
        tell(p, "no_portal");
    }

    // ------------------------------------------------------------------ beds and spawn points

    static boolean surfaceSpawnAllowed(Level level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return true;
        if (pos.getY() >= ERConfig.INSTANCE.surfaceBedMinY.get()) return true;
        return level.canSeeSky(pos.above());
    }

    @SubscribeEvent
    public static void onSleep(PlayerSleepInBedEvent e) {
        Optional<BlockPos> pos = e.getOptionalPos();
        if (pos.isEmpty() || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (surfaceSpawnAllowed(p.level(), pos.get())) return;
        e.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
        tell(p, "no_bed");
    }

    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent e) {
        if (e.isForced() || e.getNewSpawn() == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getSpawnLevel() != Level.OVERWORLD) return;
        ServerLevel level = p.server.getLevel(e.getSpawnLevel());
        if (level == null || surfaceSpawnAllowed(level, e.getNewSpawn())) return;
        e.setCanceled(true);
        tell(p, "no_bed");
    }

    // ------------------------------------------------------------------ teleports

    /** Ender pearls, chorus fruit, endermen and modded blinks all stop at unbreakable stone. Commands are exempt. */
    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent e) {
        if (e instanceof EntityTeleportEvent.TeleportCommand || e instanceof EntityTeleportEvent.SpreadPlayersCommand) return;
        Entity en = e.getEntity();
        if (!DeepWorld.installed() || en.level().dimension() != Level.OVERWORLD) return;
        if (!crossesBand(e.getPrevY(), e.getTargetY())) return;
        e.setCanceled(true);
        if (en instanceof ServerPlayer p) tell(p, "no_teleport");
    }

    /** True when a straight move from y1 to y2 would pass through, or end inside, an unbreakable band. */
    public static boolean crossesBand(double y1, double y2) {
        int lo = (int) Math.floor(Math.min(y1, y2)), hi = (int) Math.floor(Math.max(y1, y2));
        if (hi >= DeepWorld.packFloor() && lo >= DeepWorld.packFloor()) return false;
        for (int band = 1; band <= DeepWorld.bandCount(); band++) {
            int top = DeepWorld.bandTopY(band), bottom = DeepWorld.bandBottomY(band);
            if (lo <= top && hi >= bottom) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ vanilla spawning

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn e) {
        Mob mob = e.getEntity();
        if (!(mob instanceof Enemy)) return;
        if (e.getLevel().getLevel().dimension() != Level.OVERWORLD) return;
        if (mob.getType().is(ERTags.REMIX_CREATURES)) return;
        MobSpawnType t = e.getSpawnType();
        boolean natural = t == MobSpawnType.NATURAL || t == MobSpawnType.PATROL || t == MobSpawnType.CHUNK_GENERATION;
        boolean block = (natural && ERConfig.INSTANCE.disableNaturalHostiles.get())
                || (t == MobSpawnType.SPAWNER && ERConfig.INSTANCE.disableSpawners.get());
        if (!block) return;
        e.setSpawnCancelled(true);
        e.setCanceled(true);
    }

    // ------------------------------------------------------------------ game modes

    /** Switching to creative or spectator ends the run: a run is a survival trip. */
    @SubscribeEvent
    public static void onGameMode(PlayerEvent.PlayerChangeGameModeEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        GameType mode = e.getNewGameMode();
        if (mode != GameType.CREATIVE && mode != GameType.SPECTATOR) return;
        RunState s = RunManager.state(p);
        if (s.active) RunManager.end(p, s, "command");
    }
}
