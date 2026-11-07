package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The runes' glowing squares: bolts, Siphon's motes and a Field's ring, each one quad of a white texture tinted by its
 * vertex colour and drawn full bright. The entity shader still shades by the normal, so every square's normal points
 * straight up in the world (taken before the square is turned to face the camera): that is lit fully from any angle,
 * and the square shows exactly its tint.
 */
final class GlowSprite {
    private GlowSprite() {}

    /** explorationremix:textures/entity/rune/{@code name}.png */
    static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(ExplorationRemix.MOD_ID, "textures/entity/rune/" + name + ".png");
    }

    /**
     * A square facing the camera, centred on the pose's origin, {@code half} blocks from its middle to each side, turned
     * {@code spin} degrees about the line of sight, and set {@code back} blocks further from the camera (so of two squares
     * on one spot, the one set back is drawn first and the other shows over it).
     */
    static void facing(PoseStack pose, VertexConsumer vc, Quaternionf camera, float half, float spin, float back, int rgb, float alpha) {
        Matrix3f up = new Matrix3f(pose.last().normal());
        pose.pushPose();
        pose.mulPose(camera);
        // turned round, the square's +z points at the camera: x runs right, y up
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        if (spin != 0.0F) pose.mulPose(Axis.ZP.rotationDegrees(spin));
        Matrix4f m = pose.last().pose();
        int a = alpha(alpha);
        vertex(vc, m, up, -half, -half, -back, 0.0F, 1.0F, rgb, a);
        vertex(vc, m, up, half, -half, -back, 1.0F, 1.0F, rgb, a);
        vertex(vc, m, up, half, half, -back, 1.0F, 0.0F, rgb, a);
        vertex(vc, m, up, -half, half, -back, 0.0F, 0.0F, rgb, a);
        pose.popPose();
    }

    /**
     * A square lying flat, {@code y} blocks above the pose's origin, {@code half} blocks from its middle to each side,
     * turned {@code spin} degrees about the vertical. Seen from above or below alike (the render type draws both faces).
     */
    static void flat(PoseStack pose, VertexConsumer vc, float y, float half, float spin, int rgb, float alpha) {
        pose.pushPose();
        if (spin != 0.0F) pose.mulPose(Axis.YP.rotationDegrees(spin));
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f up = last.normal();
        int a = alpha(alpha);
        vertex(vc, m, up, -half, y, -half, 0.0F, 0.0F, rgb, a);
        vertex(vc, m, up, -half, y, half, 0.0F, 1.0F, rgb, a);
        vertex(vc, m, up, half, y, half, 1.0F, 1.0F, rgb, a);
        vertex(vc, m, up, half, y, -half, 1.0F, 0.0F, rgb, a);
        pose.popPose();
    }

    private static int alpha(float alpha) { return Mth.clamp(Math.round(alpha * 255.0F), 0, 255); }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v, int rgb, int a) {
        vc.vertex(m, x, y, z).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT).normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
