package com.explorationremix.client;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * Each layer has its own air: the Lantern Deeps are clear and warm, the Blue Reaches dim and cold, the Ember
 * Gardens hazy, the Basalt Galleries close and dark. Bands are the darkest of all.
 */
public final class LayerFog {
    private LayerFog() {}

    private static float farFor(int y) {
        if (!DeepWorld.installed() || y >= DeepWorld.packFloor()) return -1;
        Layer l = DeepWorld.layerAt(y);
        if (l == null) return 22.0F;                         // inside a band: the gate tubes
        return switch (l.index()) { case 0 -> 64.0F; case 1 -> 40.0F; case 2 -> 48.0F; default -> 30.0F; };
    }

    public static void onRenderFog(ViewportEvent.RenderFog e) {
        if (!ERConfig.INSTANCE.layerFog.get()) return;
        if (e.getMode() != FogRenderer.FogMode.FOG_TERRAIN || e.getType() != FogType.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        float far = farFor(mc.gameRenderer.getMainCamera().getBlockPosition().getY());
        if (far < 0) return;
        float strength = (float) Math.max(0.05, ERConfig.INSTANCE.fogStrength.get());
        far = Math.min(e.getFarPlaneDistance(), far / strength);
        e.setFarPlaneDistance(far);
        e.setNearPlaneDistance(far * 0.15F);
        e.setCanceled(true);
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor e) {
        if (!ERConfig.INSTANCE.layerFog.get() || !DeepWorld.installed()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        int y = mc.gameRenderer.getMainCamera().getBlockPosition().getY();
        if (y >= DeepWorld.packFloor()) return;
        if (DeepWorld.layerAt(y) == null) {                  // a band: near-black
            e.setRed(e.getRed() * 0.25F); e.setGreen(e.getGreen() * 0.25F); e.setBlue(e.getBlue() * 0.3F);
        }
    }
}
