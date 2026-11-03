package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.Geometry;
import com.explorationremix.client.render.MossbackRenderer;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERParticles;
import net.minecraft.client.renderer.entity.NoopRenderer;
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
        event.registerSpriteSet(ERParticles.VOLCANIC_SMOKE.get(), CaveParticles.VolcanicSmoke.Provider::new);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(MossbackRenderer.LAYER, () -> Geometry.load("mossback"));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EREntities.MOSSBACK.get(), MossbackRenderer::new);
        // 0.5.1's lava bomb is a skeleton nothing throws yet (the eruptions build gives it a look): drawn as nothing, so
        // a client that sees one (/summon) has a renderer for it and does not crash
        event.registerEntityRenderer(EREntities.LAVA_BOMB.get(), NoopRenderer::new);
    }
}
