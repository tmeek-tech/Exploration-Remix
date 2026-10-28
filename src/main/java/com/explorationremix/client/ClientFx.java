package com.explorationremix.client;

import com.explorationremix.config.ERConfig;
import net.minecraft.util.Mth;

/** Hit-stop and camera kick state. The Timer mixin reads {@link #consumeHitStop()}, the camera event reads the kick. */
public final class ClientFx {
    private static int hitStopTicks;
    private static float kick;        // remaining strength 0..1
    private static float kickX, kickZ;
    private static long kickStart;
    private ClientFx() {}

    public static void hitStop(int ticks) {
        if (ERConfig.INSTANCE.hitStopTicks.get() <= 0) return;
        hitStopTicks = Math.max(hitStopTicks, Math.min(ticks, ERConfig.INSTANCE.hitStopTicks.get()));
    }

    /** True while a hit-stop is in progress; each call burns one tick of it. */
    public static boolean consumeHitStop() {
        if (hitStopTicks <= 0) return false;
        hitStopTicks--;
        return true;
    }

    public static void kick(float strength, float dirX, float dirZ) {
        if (!ERConfig.INSTANCE.screenShake.get()) return;
        kick = Mth.clamp(strength, 0, 1);
        kickX = dirX; kickZ = dirZ;
        kickStart = System.currentTimeMillis();
    }

    /** Pitch (degrees) to add to the camera this frame, decaying over ~160 ms. */
    public static float kickPitch() {
        if (kick <= 0) return 0;
        float t = (System.currentTimeMillis() - kickStart) / 160f;
        if (t >= 1) { kick = 0; return 0; }
        return (float) (kick * 2.2 * Math.sin(t * Math.PI) * (1 - t));
    }

    public static float kickYaw() {
        if (kick <= 0) return 0;
        float t = (System.currentTimeMillis() - kickStart) / 160f;
        if (t >= 1) return 0;
        return (float) (kick * 1.2 * kickX * Math.sin(t * Math.PI) * (1 - t));
    }
}
