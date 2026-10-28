package com.explorationremix.client;

import com.explorationremix.client.screen.RuneBlockScreen;
import com.explorationremix.registry.ERMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only registration: screens, render layers, and the camera kick. Registered on the mod bus from the client only. */
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> {
            MenuScreens.register(ERMenus.RUNE_BLOCK.get(), RuneBlockScreen::new);
        });
        MinecraftForge.EVENT_BUS.register(ForgeClientEvents.class);
        com.explorationremix.compat.BetterCombatCompat.registerClient();
    }

    @SubscribeEvent
    public static void onKeys(net.minecraftforge.client.event.RegisterKeyMappingsEvent e) {
        e.register(ERKeys.RUNES);
    }

    @SubscribeEvent
    public static void onOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("run_hud", RunHud.INSTANCE);
    }

    private static final String[] CREATURES = {"flocker", "swarmer", "armored", "spitter", "climber", "ambusher", "burrower", "smasher"};

    @SubscribeEvent
    public static void onLayers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions e) {
        for (String id : CREATURES) {
            e.registerLayerDefinition(com.explorationremix.client.render.CreatureRenderer.layer(id), () -> com.explorationremix.client.model.CreatureShape.load(id).toLayerDefinition());
        }
    }

    @SubscribeEvent
    public static void onRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.FLOCKER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "flocker"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.SWARMER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "swarmer"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.ARMORED.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "armored"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.SPITTER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "spitter"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.CLIMBER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "climber"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.AMBUSHER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "ambusher"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.BURROWER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "burrower"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.SMASHER.get(), ctx -> new com.explorationremix.client.render.CreatureRenderer<>(ctx, "smasher"));
        e.registerEntityRenderer(com.explorationremix.registry.EREntities.SPIT.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
    }

    public static final class ForgeClientEvents {
        private ForgeClientEvents() {}

        @SubscribeEvent
        public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
            if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            while (ERKeys.RUNES.consumeClick()) {
                if (mc.player != null && mc.screen == null) mc.setScreen(new com.explorationremix.client.screen.RunesScreen());
            }
        }

        @SubscribeEvent
        public static void onTooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent e) {
            RuneTooltips.append(e.getItemStack(), e.getToolTip());
        }

        @SubscribeEvent
        public static void onRenderFog(ViewportEvent.RenderFog e) { LayerFog.onRenderFog(e); }

        @SubscribeEvent
        public static void onFogColor(ViewportEvent.ComputeFogColor e) { LayerFog.onFogColor(e); }

        @SubscribeEvent
        public static void onCamera(ViewportEvent.ComputeCameraAngles e) {
            float pitch = ClientFx.kickPitch();
            float yaw = ClientFx.kickYaw();
            if (pitch != 0) e.setPitch(e.getPitch() + pitch);
            if (yaw != 0) e.setYaw(e.getYaw() + yaw);
        }
    }
}
