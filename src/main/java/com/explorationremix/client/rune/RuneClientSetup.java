package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.client.rune.fx.RuneFxClient;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.client.gui.screens.MenuScreens;
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
 * The runes' client registrations, on the mod bus: the rune screen, the runes' keys, the HUD, the energy line on gear in
 * slots, and how bolts, Fields and Siphon's motes are drawn.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RuneClientSetup {
    private RuneClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(RuneRegistry.EDITOR.get(), RuneEditorScreen::new));
        // effects the server sends, and the trails behind bolts, motes and Fields
        RuneFx.client = RuneFxClient.INSTANCE;
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(RuneKeys.CAST);
        event.register(RuneKeys.OPEN);
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
    }
}
