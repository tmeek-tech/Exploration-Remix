package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.Neon;
import com.explorationremix.block.NeonBlock;
import com.explorationremix.client.model.Geometry;
import com.explorationremix.client.model.TubeWormsModel;
import com.explorationremix.client.render.ChimneyLurkerRenderer;
import com.explorationremix.client.render.HollowChoirRenderer;
import com.explorationremix.client.render.FireflySwarmRenderer;
import com.explorationremix.client.render.GiantSkeinRenderer;
import com.explorationremix.client.render.LavaBombRenderer;
import com.explorationremix.client.render.MossbackRenderer;
import com.explorationremix.client.render.SkeinRenderer;
import com.explorationremix.client.render.StiltMantisRenderer;
import com.explorationremix.client.render.TubeWormsRenderer;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERParticles;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.RegistryObject;

/** Client-only setup on the mod bus. */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        // A neon block's item shows the colour it places (NeonBlock.neon: its BlockStateTag's) through its model's
        // overrides on explorationremix:neon (tools/gen_data.py): 0 magenta, 1/3 violet, 2/3 lime, 1 light blue.
        event.enqueueWork(() -> {
            // 0.20.6: the tube worms' block entities are made on this thread, and only here
            TubeWormsClient.init();
            ResourceLocation neon = new ResourceLocation(ExplorationRemix.MOD_ID, "neon");
            ItemPropertyFunction colour = (stack, level, entity, seed) -> NeonBlock.neon(stack).ordinal() / (float) (Neon.values().length - 1);
            for (RegistryObject<Item> item : ERBlocks.ITEMS.getEntries()) {
                // the neon blocks, and since 0.20.2 the vent field's neon plants
                if (item.get() instanceof BlockItem b && b.getBlock().defaultBlockState().hasProperty(NeonBlock.NEON)) ItemProperties.register(b, neon, colour);
            }
        });
    }

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
        // 0.20.2: the spores off the Deep Gardens' grass and small mushrooms
        event.registerSpriteSet(ERParticles.SPORE.get(), CaveParticles.Spore.Provider::new);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // (the Mossback needs none: since 0.15.0 its shape is rigged to its body, see MossbackModel)
        // the Skein: its loose pieces (head, plates, tail), drawn along its trail
        event.registerLayerDefinition(SkeinRenderer.LAYER, () -> Geometry.load("skein"));
        // 0.18.8: the giant Skein of the Deep Blue Rift, drawn the same way
        event.registerLayerDefinition(GiantSkeinRenderer.LAYER, () -> Geometry.load("giant_skein"));
        // 0.21.9: the Hollow Choir's loose parts (a crown plate, a rib, a bell, the core, a tendril segment, a foot)
        event.registerLayerDefinition(HollowChoirRenderer.LAYER, () -> Geometry.load("hollow_choir"));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EREntities.MOSSBACK.get(), MossbackRenderer::new);
        // the blue fireflies: points of light drawn round the swarm's centre (no model)
        event.registerEntityRenderer(EREntities.FIREFLY_SWARM.get(), FireflySwarmRenderer::new);
        // the Skein
        event.registerEntityRenderer(EREntities.SKEIN.get(), SkeinRenderer::new);
        // 0.18.8: the giant Skein
        event.registerEntityRenderer(EREntities.GIANT_SKEIN.get(), GiantSkeinRenderer::new);
        // 0.20.0: the stilt mantis of the Deep Gardens
        event.registerEntityRenderer(EREntities.STILT_MANTIS.get(), StiltMantisRenderer::new);
        // 0.21.0: the chimney lurker of the Deep Gardens' vents
        event.registerEntityRenderer(EREntities.CHIMNEY_LURKER.get(), ChimneyLurkerRenderer::new);
        // 0.23.7: the ember urchin of the Wasteland
        event.registerEntityRenderer(EREntities.EMBER_URCHIN.get(), com.explorationremix.client.render.EmberUrchinRenderer::new);
        // 0.21.9: the Hollow Choir of the Deep Dark
        event.registerEntityRenderer(EREntities.HOLLOW_CHOIR.get(), HollowChoirRenderer::new);
        // 0.9.0: the eruptions' lava bombs, a spinning glowing block of magma in a halo
        event.registerEntityRenderer(EREntities.LAVA_BOMB.get(), LavaBombRenderer::new);
        // 0.20.6: the tube worms' plumes, moving
        event.registerBlockEntityRenderer(EREntities.TUBE_WORMS_TILE.get(), TubeWormsRenderer::new);
    }

    /** 0.20.6: the tube worms' blockstate models leave their plumes out where they are drawn moving. */
    @SubscribeEvent
    public static void models(ModelEvent.ModifyBakingResult event) {
        TubeWormsModel.wrap(event);
        TubeWormsRenderer.bakes++;
    }
}
