package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.client.rune.fx.RuneFxClient;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The runes' client registrations, on the mod bus: the enchanting table's rune view, the cast and peer keys, the HUD,
 * the energy line on weapons in slots, a Quickdraw bow's pull, and how bolts, Fields, Siphon's motes and the runes freed
 * from rune stones are drawn.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RuneClientSetup {
    private RuneClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(RuneRegistry.TABLE.get(), com.explorationremix.client.rune.table.RuneTableScreen::new);
            // the bow's pull, drawn at Quickdraw's pace on a bow that has it (the game's own for every other)
            ItemProperties.register(Items.BOW, new ResourceLocation("pull"), (stack, level, entity, seed) -> {
                if (entity == null || entity.getUseItem() != stack) return 0.0F;
                float per = RuneGear.passive(stack) == RuneId.QUICKDRAW ? 10.0F : 20.0F;
                return (stack.getUseDuration() - entity.getUseItemRemainingTicks()) / per;
            });
        });
        // effects the server sends, and the trails behind bolts, motes and Fields
        RuneFx.client = RuneFxClient.INSTANCE;
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(RuneKeys.CAST);
        event.register(RuneKeys.PEER);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        // drawn just after the hotbar, beside it (see RuneHud)
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "rune_energy", RuneHud::render);
    }

    @SubscribeEvent
    public static void decorations(RegisterItemDecorationsEvent event) {
        // every sword, bow and crossbow, other mods' too: the same items RuneGear.host accepts
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem) event.register(item, RuneSlotEnergy.INSTANCE);
        }
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RuneRegistry.BOLT.get(), RuneBoltRenderer::new);
        event.registerEntityRenderer(RuneRegistry.FIELD.get(), RuneFieldRenderer::new);
        event.registerEntityRenderer(RuneRegistry.MOTE.get(), RuneMoteRenderer::new);
        event.registerEntityRenderer(RuneRegistry.FREED_RUNE.get(), com.explorationremix.client.rune.stone.FreedRuneRenderer::new);
    }
}
