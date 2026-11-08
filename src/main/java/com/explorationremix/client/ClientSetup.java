package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.Geometry;
import com.explorationremix.client.render.FireflySwarmRenderer;
import com.explorationremix.client.render.LavaBombRenderer;
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
        event.registerSpriteSet(ERParticles.VOLCANIC_SMOKE.get(), CaveParticles.VolcanicSmoke.Provider::new);
        // 0.9.0: the quakes' grit
        event.registerSpriteSet(ERParticles.GRIT.get(), CaveParticles.Grit.Provider::new);
        // 0.9.0: the eruptions' lava spatter
        event.registerSpriteSet(ERParticles.LAVA_SPATTER.get(), CaveParticles.Spatter.Provider::new);
        // 0.9.0 (pack look fixes): the fountain's lava gobs, the fire at the mouth, the plume's dark ash, the blasts' ash
        // and the crashes' rock dust
        event.registerSpriteSet(ERParticles.LAVA_GOB.get(), CaveParticles.LavaGob.Provider::new);
        event.registerSpriteSet(ERParticles.FIREBALL.get(), CaveParticles.Fireball.Provider::new);
        event.registerSpriteSet(ERParticles.ERUPTION_ASH.get(), CaveParticles.EruptionAsh.Provider::new);
        event.registerSpriteSet(ERParticles.BLAST_ASH.get(), CaveParticles.Dust.BlastProvider::new);
        event.registerSpriteSet(ERParticles.ROCK_DUST.get(), CaveParticles.Dust.RockProvider::new);
        // 0.9.0 (eruption effects, round 2): the blasts' flash and the vents' glow, and the rubble's chunks
        event.registerSpriteSet(ERParticles.FLASH.get(), CaveParticles.Flash.Provider::new);
        event.registerSpriteSet(ERParticles.RUBBLE.get(), CaveParticles.Rubble.Provider::new);
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
        // the blue fireflies: points of light drawn round the swarm's centre (no model)
        event.registerEntityRenderer(EREntities.FIREFLY_SWARM.get(), FireflySwarmRenderer::new);
        // the Skein
        event.registerEntityRenderer(EREntities.SKEIN.get(), SkeinRenderer::new);
        // 0.9.0: the eruptions' lava bombs, a spinning glowing block of magma in a halo
        event.registerEntityRenderer(EREntities.LAVA_BOMB.get(), LavaBombRenderer::new);
    }
}
