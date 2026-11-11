package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.combat.SwingAnim.Hand;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.combat.swing.SwingKind;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The player's own swings in first person: while one is under way the main hand's item is drawn here, in the swing's
 * pose ({@link SwingAnim}) instead of vanilla's, and through the cut a pale sweep trails the blade through the air.
 * Vanilla draws the hand whenever no swing is under way (it rests where vanilla rests it).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SwingRender {
    /** The sweep: how far back it reaches (ticks of the swing), in how many pieces, and its light at its brightest. */
    static final float TRAIL_TICKS = 1.6F;
    static final int TRAIL_STEPS = 10;
    static final float TRAIL_LIGHT = 0.75F;
    /** Item pixels of a sword's grip (where it turns) and its blade's base and tip, as vanilla draws swords. */
    private static final float GRIP_X = 2.5F, GRIP_Y = 13.5F;
    private static final float BASE_X = 6.0F, BASE_Y = 10.0F, TIP_X = 15.5F, TIP_Y = 0.5F;

    private SwingRender() {}

    /** Where the hand is in swing {@code s} at {@code now} (the swings' clock). */
    static Hand handAt(SwingClient.Swing s, float now) {
        if (s.cancelAt >= 0.0F) {
            float k = (now - s.cancelAt) / SwingClient.CANCEL_EASE;
            return k >= 1.0F ? Hand.REST : s.cancelFrom.lerp(Hand.REST, SwingAnim.smooth(Mth.clamp(k, 0.0F, 1.0F)));
        }
        float tau = Math.max(0.0F, now - s.start);
        boolean strong = s.kind == SwingKind.STRONG;
        SwingAnim.Keys light = SwingAnim.keys(SwingKind.LIGHT, s.lightCombo, s.weapon);
        return SwingAnim.hand(s.keys(), light, s.from, tau, s.land(), s.weapon.lightWindup(), s.hold(), strong, s.upgradeAt, now);
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent e) {
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.isScoping() || p.isUsingItem() || p.isAutoSpinAttack()) return;
        SwingClient.Swing s = SwingClient.swing(p.getId());
        ItemStack stack = e.getItemStack();
        if (s == null || stack.isEmpty() || !ItemStack.isSameItem(stack, p.getMainHandItem())) return;
        float now = SwingClient.now(e.getPartialTick());
        if (s.over(now)) return;
        e.setCanceled(true);

        boolean right = p.getMainArm() == HumanoidArm.RIGHT;
        int i = right ? 1 : -1;
        ItemDisplayContext ctx = right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        BakedModel model = mc.getItemRenderer().getModel(stack, p.level(), p, p.getId() + ctx.ordinal());
        Vector3f grip = itemPoint(model, ctx, !right, GRIP_X, GRIP_Y);
        PoseStack pose = e.getPoseStack();
        float equip = e.getEquipProgress();

        pose.pushPose();
        place(pose, handAt(s, now), i, equip, grip);
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(p, stack, ctx, !right, pose, e.getMultiBufferSource(), e.getPackedLight());
        pose.popPose();

        trail(s, now, pose, e.getMultiBufferSource(), model, ctx, !right, i, equip, grip);
    }

    /** The hand's place: vanilla's spot for an item in hand, then the swing's pose, turned about the grip. */
    private static void place(PoseStack pose, Hand h, int i, float equip, Vector3f grip) {
        pose.translate(i * 0.56F, -0.52F - equip * 0.6F, -0.72F);
        pose.translate(i * h.x(), h.y(), h.z());
        pose.translate(grip.x, grip.y, grip.z);
        pose.mulPose(i > 0 ? h.turn() : h.mirroredTurn());
        pose.translate(-grip.x, -grip.y, -grip.z);
    }

    /** Where item pixel (x, y) of an item drawn in {@code ctx} lies, in the space it is placed in (its middle plane). */
    private static Vector3f itemPoint(BakedModel model, ItemDisplayContext ctx, boolean leftHand, float x, float y) {
        PoseStack ps = new PoseStack();
        model.applyTransform(ctx, ps, leftHand);
        ps.translate(-0.5F, -0.5F, -0.5F);
        Vector4f v = new Vector4f(x / 16.0F, 1.0F - y / 16.0F, 0.5F, 1.0F);
        ps.last().pose().transform(v);
        return new Vector3f(v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------------------------------------ the sweep

    /**
     * The sweep behind the blade through its cut: the blade's line (base to tip) at a run of moments back to
     * {@link #TRAIL_TICKS} ago, joined into a ribbon that fades with age, drawn with the swing's own poses (so it is
     * smooth at any frame rate).
     */
    private static void trail(SwingClient.Swing s, float now, PoseStack pose, MultiBufferSource buffers, BakedModel model, ItemDisplayContext ctx,
                              boolean leftHand, int i, float equip, Vector3f grip) {
        if (s.cancelAt >= 0.0F) return;
        float land = s.land(), hold = s.hold();
        boolean strong = s.kind == SwingKind.STRONG;
        float tau = now - s.start;
        float light = SwingAnim.cutting(tau, land, hold, strong);
        // a moment more: the sweep fades out after the cut rather than vanishing with it
        float tail = SwingAnim.cutting(tau - TRAIL_TICKS * 0.5F, land, hold, strong);
        float k = Math.max(light, tail * 0.6F);
        if (k <= 0.01F) return;
        float strength = strong ? 1.0F : s.combo >= s.weapon.chain() - 1 ? 0.9F : 0.75F;

        Vector3f[] base = new Vector3f[TRAIL_STEPS + 1], tip = new Vector3f[TRAIL_STEPS + 1];
        PoseStack ps = new PoseStack();
        for (int j = 0; j <= TRAIL_STEPS; j++) {
            float at = now - TRAIL_TICKS * j / TRAIL_STEPS;
            ps.pushPose();
            place(ps, handAt(s, at), i, equip, grip);
            model.applyTransform(ctx, ps, leftHand);
            ps.translate(-0.5F, -0.5F, -0.5F);
            Matrix4f m = ps.last().pose();
            base[j] = point(m, BASE_X, BASE_Y);
            tip[j] = point(m, TIP_X, TIP_Y);
            ps.popPose();
        }
        VertexConsumer vc = buffers.getBuffer(GlowRender.SWEEP);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        for (int j = 0; j < TRAIL_STEPS; j++) {
            float u0 = (float) j / TRAIL_STEPS, u1 = (float) (j + 1) / TRAIL_STEPS;
            int c0 = shade(k * strength), c1 = shade(k * strength);
            vertex(vc, m, n, base[j], u0, 0.0F, c0);
            vertex(vc, m, n, tip[j], u0, 1.0F, c0);
            vertex(vc, m, n, tip[j + 1], u1, 1.0F, c1);
            vertex(vc, m, n, base[j + 1], u1, 0.0F, c1);
        }
    }

    private static Vector3f point(Matrix4f m, float x, float y) {
        Vector4f v = new Vector4f(x / 16.0F, 1.0F - y / 16.0F, 0.5F, 1.0F);
        m.transform(v);
        return new Vector3f(v.x, v.y, v.z);
    }

    /** The sweep's grey for a light of {@code k} (it is drawn added on, so grey is how bright). */
    private static int shade(float k) { return Mth.clamp(Math.round(255.0F * TRAIL_LIGHT * k), 0, 255); }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, Vector3f p, float u, float v, int c) {
        vc.vertex(m, p.x, p.y, p.z).color(c, c, c, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
