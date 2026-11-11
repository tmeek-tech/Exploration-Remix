package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.swing.SwingNet;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** The timed swings' client registrations, on the mod bus: other players' swings from the server, and the HUD marks. */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SwingClientSetup {
    private SwingClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        SwingNet.client = SwingClient::show;
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        // drawn with the crosshair, where vanilla's attack indicator sits
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "swings", SwingHud::render);
    }
}
