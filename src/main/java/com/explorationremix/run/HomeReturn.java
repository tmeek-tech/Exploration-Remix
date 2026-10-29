package com.explorationremix.run;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** The way home from bedrock: a short wait at the shrine, then the player is sent to their spawn point. */
public final class HomeReturn {
    private static final Map<UUID, Integer> PENDING = new HashMap<>();
    private HomeReturn() {}

    public static void schedule(ServerPlayer p, int ticks) { PENDING.put(p.getUUID(), ticks); }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p == null) { it.remove(); continue; }
            int left = entry.getValue() - 1;
            if (left > 0) { entry.setValue(left); continue; }
            it.remove();
            ServerLevel target = p.getRespawnDimension() != null ? e.getServer().getLevel(p.getRespawnDimension()) : null;
            if (target == null) target = e.getServer().overworld();
            BlockPos pos = p.getRespawnPosition() != null ? p.getRespawnPosition() : target.getSharedSpawnPos();
            p.teleportTo(target, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, p.getYRot(), p.getXRot());
            p.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.7F);
        }
    }
}
