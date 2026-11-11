package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reading;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A rune sword lights up once it holds the energy for its next cast (Taran's pick, 2026-11-11: pale blue-white on every
 * sword). As it wakes, light runs up the blade from the guard to the tip, which flares, with a soft chime; while it stays
 * ready the blade glows, breathing, a shimmer running up it every couple of seconds and sparks of light winking along it;
 * while it waits out a cast's delay the glow sinks low; spent below its next cast, the light drains away.
 *
 * <p>The light is two animated sheets ({@code textures/misc/blade_glow.png}: the wake, then the loop), drawn over the
 * blade of the sword in hand in first and third person, front and back, added onto what is there. The sheet is shaped
 * to a sword's blade (diagonal from the guard at the bottom left to the tip at the top right, as swords are drawn),
 * its light spilling a little past the edges.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneGlow {
    /** The sheet: frames of 72 x 72 (18 item pixels at 4 each: the sword's 16 and one round it), the wake's then the loop's. */
    static final int WAKE_FRAMES = 14, LOOP_FRAMES = 32, FRAMES = WAKE_FRAMES + LOOP_FRAMES;
    /** Ticks a frame of the wake shows, and of the loop. */
    static final float WAKE_TICK = 1.0F, LOOP_TICK = 1.25F;
    /** The overlay's square reaches this far past the item's own (item pixels / 16). */
    static final float SPILL = 1.0F / 16.0F;
    /** The light's colour at full strength (pale blue-white), and how much of it a sword waiting out a delay keeps. */
    static final int RGB = 0xD6ECFF;
    static final float WAITING = 0.35F;

    private static final class Glow {
        boolean ready;
        /** The swings' clock when it last woke (-1: never seen waking). */
        float wakeAt = -1000.0F;
        /** 0 to 1, eased each tick, and the last tick's. */
        float level, levelO;
        /** What it eases towards. */
        float target;
        boolean seen;
    }

    private static final Map<Long, Glow> GLOWS = new HashMap<>();
    private static int clock;

    private RuneGlow() {}

    private static long key(int entity, InteractionHand hand) { return ((long) entity << 1) | (hand == InteractionHand.OFF_HAND ? 1 : 0); }

    // ------------------------------------------------------------------------------------------------ readiness

    /** How lit a sword should be now: 1 ready, {@link #WAITING} with the energy but waiting out a delay, 0 not. */
    static float readiness(ItemStack stack, long now) {
        if (RuneGear.host(stack) != Host.SWORD || !RuneGear.hasRunes(stack)) return 0.0F;
        HostSpec spec = RuneGear.spec(stack);
        Reading r = RuneGear.reading(stack, RunDepth.clientDepth());
        if (spec == null || r == null) return 0.0F;
        CastState s = RuneGear.state(stack, spec, now);
        int next = CastMachine.next(s, r, now, RuneConfig.limits());
        if (next < 0) return 0.0F;
        CastReading c = r.casts().get(next);
        if (CastMachine.energyAt(s, spec, now) + 1.0E-3F < c.cost()) return 0.0F;
        return now < s.readyAt ? WAITING : 1.0F;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        clock++;
        long now = mc.level.getGameTime();
        for (Glow g : GLOWS.values()) g.seen = false;
        for (Player p : mc.level.players()) {
            if (p.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > 64.0 * 64.0) continue;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = p.getItemInHand(hand);
                float want = readiness(stack, now);
                Glow g = GLOWS.get(key(p.getId(), hand));
                if (g == null) {
                    if (want <= 0.0F) continue;
                    g = new Glow();
                    GLOWS.put(key(p.getId(), hand), g);
                }
                g.seen = true;
                boolean ready = want > 0.0F;
                if (ready && !g.ready && g.level < 0.5F) {
                    g.wakeAt = clock;
                    if (p == mc.player) {
                        mc.level.playLocalSound(p.getX(), p.getEyeY(), p.getZ(), ERSounds.RUNES_READY.get(), SoundSource.PLAYERS, 0.55F,
                                0.95F + p.getRandom().nextFloat() * 0.1F, false);
                    }
                }
                g.ready = ready;
                g.target = want;
                g.levelO = g.level;
                // up quickly, down slowly (a drained blade's light dies away)
                g.level += (g.target - g.level) * (g.target > g.level ? 0.35F : 0.12F);
                if (Math.abs(g.target - g.level) < 0.005F) g.level = g.target;
            }
        }
        for (Iterator<Glow> it = GLOWS.values().iterator(); it.hasNext(); ) {
            Glow g = it.next();
            if (!g.seen || (!g.ready && g.level <= 0.0F)) it.remove();
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) { GLOWS.clear(); }

    // ------------------------------------------------------------------------------------------------ drawing

    /**
     * After a held item is drawn ({@code pose} as the item was given it): a ready sword's light over its blade. Called for
     * first and third person; nothing for anything else.
     */
    public static void draw(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack pose, MultiBufferSource buffers) {
        if (GLOWS.isEmpty() || !(holder instanceof Player p) || stack.isEmpty()) return;
        if (!ctx.firstPerson() && ctx != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && ctx != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return;
        InteractionHand hand = p.getMainHandItem() == stack ? InteractionHand.MAIN_HAND : p.getOffhandItem() == stack ? InteractionHand.OFF_HAND : null;
        if (hand == null) return;
        Glow g = GLOWS.get(key(p.getId(), hand));
        if (g == null) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = mc.getFrameTime();
        float level = Mth.lerp(pt, g.levelO, g.level);
        float since = clock + pt - g.wakeAt;
        float wakeLen = WAKE_FRAMES * WAKE_TICK;
        boolean waking = g.ready && since >= 0.0F && since < wakeLen;
        if (level < 0.01F && !waking) return;

        int frame;
        float bright;
        if (waking) {
            frame = Mth.clamp((int) (since / WAKE_TICK), 0, WAKE_FRAMES - 1);
            bright = 1.0F;
        } else {
            // the loop runs on from the wake (its first frame follows the wake's last), or from any moment if never seen waking
            float loopT = (since - wakeLen) / LOOP_TICK;
            frame = WAKE_FRAMES + Math.floorMod((int) Math.floor(loopT), LOOP_FRAMES);
            bright = level;
        }
        BakedModel model = mc.getItemRenderer().getModel(stack, p.level(), p, p.getId() + ctx.ordinal());
        pose.pushPose();
        model = model.applyTransform(ctx, pose, leftHand);
        pose.translate(-0.5F, -0.5F, -0.5F);
        VertexConsumer vc = buffers.getBuffer(GlowRender.BLADE);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        float v0 = (float) frame / FRAMES, v1 = (float) (frame + 1) / FRAMES;
        int r = Math.round((RGB >> 16 & 255) * bright), gr = Math.round((RGB >> 8 & 255) * bright), b = Math.round((RGB & 255) * bright);
        // a sword drawn for the left hand is the right hand's turned round: the light follows the item's own square
        float lo = -SPILL, hi = 1.0F + SPILL;
        float front = 8.5F / 16.0F + 0.004F, back = 7.5F / 16.0F - 0.004F;
        quad(vc, m, n, lo, hi, front, v0, v1, r, gr, b);
        quad(vc, m, n, lo, hi, back, v0, v1, r, gr, b);
        pose.popPose();
    }

    /** One square of light in the item's plane at depth {@code z}, the sheet's frame from v0 to v1 (its top at the item's top). */
    private static void quad(VertexConsumer vc, Matrix4f m, Matrix3f n, float lo, float hi, float z, float v0, float v1, int r, int g, int b) {
        vertex(vc, m, n, lo, lo, z, 0.0F, v1, r, g, b);
        vertex(vc, m, n, hi, lo, z, 1.0F, v1, r, g, b);
        vertex(vc, m, n, hi, hi, z, 1.0F, v0, r, g, b);
        vertex(vc, m, n, lo, hi, z, 0.0F, v0, r, g, b);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v, int r, int g, int b) {
        vc.vertex(m, x, y, z).color(r, g, b, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
