package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.MossbackEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** While a Mossback holds you in its jaws, your view is shaken from side to side with it. */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HeldShake {
    private HeldShake() {}

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        Player me = mc.player;
        if (me == null || mc.level == null) return;
        for (MossbackEntity m : mc.level.getEntitiesOfClass(MossbackEntity.class, me.getBoundingBox().inflate(6))) {
            if (m.heldId() != me.getId() || m.state() != MossbackEntity.GRAB) continue;
            float t = (float) (m.tickCount + event.getPartialTick());
            event.setRoll(event.getRoll() + Mth.sin(t * 1.1F) * 9.0F);
            event.setYaw(event.getYaw() + Mth.sin(t * 0.55F) * 4.0F);
            return;
        }
    }
}
