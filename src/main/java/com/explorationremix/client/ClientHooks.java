package com.explorationremix.client;

import com.explorationremix.client.screen.RunSummaryScreen;
import com.explorationremix.network.CombatFxPacket;
import com.explorationremix.network.RunStatePacket;
import com.explorationremix.run.RunSummary;
import net.minecraft.client.Minecraft;

/** Entry points for packets that touch client-only classes. Only ever loaded on the client. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void showRunSummary(RunSummary summary) {
        Minecraft mc = Minecraft.getInstance();
        ClientRunState.lastSummary = summary;
        mc.execute(() -> mc.setScreen(new RunSummaryScreen(summary, mc.screen)));
    }

    public static void combatFx(CombatFxPacket packet) {
        ClientFx.hitStop(packet.hitStopTicks());
        ClientFx.kick(packet.kick(), packet.dirX(), packet.dirZ());
    }

    public static void runState(RunStatePacket packet) {
        ClientRunState.runId = packet.runId();
        ClientRunState.layerIndex = packet.layerIndex();
        ClientRunState.startedAt = packet.startedAt();
        // A client on a dedicated server lays the layers out from the pack floor it is told about.
        if (packet.installed() && !com.explorationremix.world.DeepWorld.installed()) {
            com.explorationremix.world.DeepWorld.rebuild(packet.packFloor());
            com.explorationremix.world.DeepWorld.setInstalled(true);
        }
    }
}
