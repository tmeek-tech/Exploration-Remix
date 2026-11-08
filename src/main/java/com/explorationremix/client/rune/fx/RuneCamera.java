package com.explorationremix.client.rune.fx;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The view's answer to a fight: a short shake when a blast, a slam or a stagger goes off near you (harder the nearer
 * and bigger), and a kick of the view when your own hit lands, a little one for a light hit and a firm one for a full
 * swing or a critical. Both fade within a few ticks. Off with the client setting {@code runeShake}, and scaled by the
 * game's Distortion Effects slider, as the lava's tremor is. Your own Dash widens the view for a moment, by the game's
 * FOV Effects slider, as sprinting does.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneCamera {
    /** The most shake, in degrees, however much piles up. */
    private static final float MAX_SHAKE = 3.0F;
    private static float shake, shakeBefore;
    private static float kickPitch, kickPitchBefore, kickYaw, kickYawBefore;
    private static long ticks;
    /** When your last Dash threw you (in this class's ticks), for the widening of the view. */
    private static long dashAt = Long.MIN_VALUE / 2;

    private RuneCamera() {}

    /** Shakes the view by about {@code degrees} at its start. */
    public static void shake(float degrees) {
        if (degrees > 0.0F) shake = Math.min(MAX_SHAKE, shake + degrees);
    }

    /** Kicks the view: down by {@code pitch} degrees and aside by {@code yaw}. */
    public static void kick(float pitch, float yaw) {
        kickPitch = Mth.clamp(kickPitch + pitch, -4.0F, 4.0F);
        kickYaw = Mth.clamp(kickYaw + yaw, -3.0F, 3.0F);
    }

    /** Your own Dash throws you: the view widens at once and eases back as you slow. */
    public static void dash() { dashAt = ticks; }

    /** Your own swing lands on a creature: the view kicks, by how strong the swing was. */
    @SubscribeEvent
    public static void attack(AttackEntityEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!e.getEntity().level().isClientSide() || e.getEntity() != mc.player || !(e.getTarget() instanceof LivingEntity t) || !t.isAlive()) return;
        float strength = mc.player.getAttackStrengthScale(0.5F);
        boolean crit = strength > 0.9F && mc.player.fallDistance > 0.0F && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater();
        float k = strength < 0.9F ? 0.25F * strength : crit ? 1.5F : 0.9F;
        kick(k, (mc.player.getRandom().nextFloat() - 0.5F) * 0.5F * k);
        if (crit) shake(0.35F);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        shakeBefore = shake;
        kickPitchBefore = kickPitch;
        kickYawBefore = kickYaw;
        shake *= 0.78F;
        if (shake < 0.01F) shake = 0.0F;
        kickPitch *= 0.55F;
        kickYaw *= 0.55F;
        if (Math.abs(kickPitch) < 0.01F) kickPitch = 0.0F;
        if (Math.abs(kickYaw) < 0.01F) kickYaw = 0.0F;
    }

    /** A Dash widens the world's view (not the hand's), by the game's FOV Effects slider. */
    @SubscribeEvent
    public static void fov(ViewportEvent.ComputeFov e) {
        if (!e.usedConfiguredFov()) return;
        double t = ticks + e.getPartialTick() - dashAt;
        if (t < 0.0 || t > 24.0) return;
        float scale = Minecraft.getInstance().options.fovEffectScale().get().floatValue();
        if (scale <= 0.0F) return;
        double k = t < 1.5 ? t / 1.5 : Math.exp(-(t - 1.5) / 4.0);
        e.setFOV(e.getFOV() * (1.0 + 0.16 * k * scale));
    }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles e) {
        float pt = (float) e.getPartialTick();
        float s = Mth.lerp(pt, shakeBefore, shake), kp = Mth.lerp(pt, kickPitchBefore, kickPitch), ky = Mth.lerp(pt, kickYawBefore, kickYaw);
        if (s <= 0.0F && kp == 0.0F && ky == 0.0F) return;
        if (!ERClientConfig.INSTANCE.runeShake.get()) return;
        float scale = Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        if (scale <= 0.0F) return;
        double t = ticks + pt;
        // fast and uneven: a jolt, not a sway
        float yaw = (float) (Math.sin(t * 2.9 + 1.3) * 0.6 + Math.sin(t * 5.3 + 0.4) * 0.4);
        float pitch = (float) (Math.sin(t * 3.4 + 2.9) * 0.6 + Math.sin(t * 6.1 + 1.7) * 0.4);
        float roll = (float) (Math.sin(t * 4.1 + 0.6) * 0.7 + Math.sin(t * 7.3 + 2.4) * 0.3);
        e.setYaw(e.getYaw() + scale * (s * yaw + ky));
        e.setPitch(e.getPitch() + scale * (s * pitch + kp));
        e.setRoll(e.getRoll() + scale * 0.7F * s * roll);
    }
}
