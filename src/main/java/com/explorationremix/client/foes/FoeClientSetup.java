package com.explorationremix.client.foes;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.foes.FoeNet;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** The fights' client side (0.23.6), on the mod bus: what the server tells of them goes to {@link FoeClient}. */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FoeClientSetup {
    private FoeClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        FoeNet.client = FoeClient.INSTANCE;
    }
}
