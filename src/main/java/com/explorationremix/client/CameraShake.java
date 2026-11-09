package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.world.lava.LavaNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The one camera hook for the Basalt Galleries' shaking (0.9.0): the lava's warning (LavaWarnings), the quakes
 * (QuakeEffects) and the eruptions' jolts, each with its own waveform, summed by {@link Shake} and added to the view's
 * angles. The gates are applied once, to the sum: nothing with the client's screenShake off; scaled by its
 * shakeStrength and the game's Distortion Effects; nothing while the lava clock, a quake or an eruption is held (a
 * picture is not shaken). A big quake's thump of the field of view follows the game's FOV Effects instead of Distortion Effects.
 *
 * <p>The angles hook runs at {@link EventPriority#LOW}: Create sets the yaw and pitch outright while its own camera
 * animates, and this adds after it. The player's real rotation is never touched, so aiming and the server are not
 * shaken. The Mossback's grab ({@link HeldShake}) is its own hook and stacks on this.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class CameraShake {
    private CameraShake() {}

    private static final Shake SUM = new Shake();
    private static final Shake.Jolts JOLTS = new Shake.Jolts();
    /** This client's unpaused ticks, the eruptions' jolts' clock. */
    private static long ticks;

    /**
     * An eruption's (or a lava bomb's) jolt of {@code degrees} that arrives {@code delay} client ticks from now, with
     * its boom. Main thread only.
     */
    static void jolt(int delay, double degrees) { JOLTS.add(ticks, delay, degrees); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            JOLTS.clear();
            return;
        }
        if (mc.isPaused()) return;
        ticks++;
        JOLTS.prune(ticks);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) { JOLTS.clear(); }

    /**
     * True while nothing may shake: the lava clock held by command (DevShots' setup holds it), or a quake or an eruption
     * held.
     */
    private static boolean still() {
        LavaNet.Sync s = LavaWarnings.lastSync();
        return s != null && s.frozen() || QuakeEffects.anyHeld() || EruptionEffects.anyHeld();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) return;
        ERClientConfig cfg = ERClientConfig.INSTANCE;
        double g = Shake.gate(cfg.screenShake.get(), cfg.shakeStrength.get(), mc.options.screenEffectScale().get(), still());
        if (g <= 0) return;
        float partial = (float) event.getPartialTick();
        SUM.clear();
        SUM.lava(LavaWarnings.amplitude(partial), LavaWarnings.clock(partial));
        QuakeEffects.shake(SUM, level, player, partial);
        JOLTS.sample(SUM, ticks + partial);
        if (SUM.yaw == 0 && SUM.pitch == 0 && SUM.roll == 0) return;
        event.setYaw((float) (event.getYaw() + g * SUM.yaw));
        event.setPitch((float) (event.getPitch() + g * SUM.pitch));
        event.setRoll((float) (event.getRoll() + g * SUM.roll));
    }

    /** A big quake's onset narrows the view for a moment (the world's view only, not the hand's). */
    @SubscribeEvent
    public static void fov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) return;
        ERClientConfig cfg = ERClientConfig.INSTANCE;
        double g = Shake.gate(cfg.screenShake.get(), cfg.shakeStrength.get(), mc.options.fovEffectScale().get(), still());
        if (g <= 0) return;
        SUM.clear();
        QuakeEffects.shake(SUM, level, player, (float) event.getPartialTick());
        if (SUM.fov != 0) event.setFOV(event.getFOV() + g * SUM.fov);
    }
}
