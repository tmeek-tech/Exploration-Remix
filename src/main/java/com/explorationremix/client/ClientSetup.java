package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.Geometry;
import com.explorationremix.client.render.MossbackRenderer;
import com.explorationremix.client.render.SkeinRenderer;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
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

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(MossbackRenderer.LAYER, () -> Geometry.load("mossback"));
        // the Skein: its loose pieces (head, plates, tail), drawn along its trail
        event.registerLayerDefinition(SkeinRenderer.LAYER, () -> Geometry.load("skein"));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EREntities.MOSSBACK.get(), MossbackRenderer::new);
        // the Skein
        event.registerEntityRenderer(EREntities.SKEIN.get(), SkeinRenderer::new);
    }
}
