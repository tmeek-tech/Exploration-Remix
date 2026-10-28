package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only setup on the mod bus. */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void particles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ERParticles.STEAM.get(), CaveParticles.Steam.Provider::new);
        event.registerSpriteSet(ERParticles.EMBER.get(), CaveParticles.Ember.Provider::new);
        event.registerSpriteSet(ERParticles.POLLEN.get(), CaveParticles.Pollen.Provider::new);
    }
}
