package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.FireflySwarm;
import com.explorationremix.creature.firefly.Flock;
import com.explorationremix.creature.firefly.Pulse;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws a swarm's fireflies (the client's {@link Flock}) as tiny points of cyan-blue light: each one a small bright
 * core with a wider, fainter halo round it, two squares that always face the camera, both cut from one glow texture.
 * Flying they shine steadily; settled on the rock each one breathes on its own ({@link Pulse}), its halo shrinking a
 * little as it dims.
 *
 * <p>They are drawn as eyes are (full-bright, added to what is behind them), the render type shader packs treat as
 * glowing: under Complementary with Oculus the fireflies glow like a spider's eyes rather than being lit like rock.
 * The shader's block light is warm, so all of their colour is in the texture; the vertex colour only scales it.
 */
public class FireflySwarmRenderer extends EntityRenderer<FireflySwarm> {
    public static final ResourceLocation TEXTURE = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/firefly_swarm/firefly.png");
    /** Half the width of a firefly's core and of its halo (blocks), and how bright the halo is next to the core. */
    static final float CORE = 0.075F, HALO = 0.28F, HALO_LEVEL = 0.5F;
    /** A dim firefly's halo shrinks to this share of its full size. */
    static final float HALO_DIM = 0.6F;
    private static final int FULL_BRIGHT = 0xF000F0;

    public FireflySwarmRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(FireflySwarm e) {
        return TEXTURE;
    }

    @Override
    public void render(FireflySwarm e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        Flock f = e.flock();
        if (f != null && f.size() > 0 && !e.isInvisible()) {
            // the pose stands at the entity's drawn position; each firefly is placed relative to it
            double ex = Mth.lerp(partialTick, e.xOld, e.getX());
            double ey = Mth.lerp(partialTick, e.yOld, e.getY());
            double ez = Mth.lerp(partialTick, e.zOld, e.getZ());
            Quaternionf facing = entityRenderDispatcher.cameraOrientation();
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(TEXTURE));
            double ticks = e.tickCount + partialTick;
            long seed = e.seed();
            for (int i = 0; i < f.size(); i++) {
                float b = (float) Pulse.brightness(seed, f.id(i), ticks, f.settledness(i, partialTick));
                pose.pushPose();
                pose.translate(f.x(i, partialTick) - ex, f.y(i, partialTick) - ey, f.z(i, partialTick) - ez);
                pose.mulPose(facing);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F));
                quad(vc, pose, HALO * (HALO_DIM + (1.0F - HALO_DIM) * b), b * HALO_LEVEL);
                quad(vc, pose, CORE, b);
                pose.popPose();
            }
        }
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    /** One square of glow, {@code r} from its middle to each side, facing the camera, scaled to {@code level}. */
    private static void quad(VertexConsumer vc, PoseStack pose, float r, float level) {
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f n = last.normal();
        vertex(vc, m, n, -r, -r, 0.0F, 1.0F, level);
        vertex(vc, m, n, r, -r, 1.0F, 1.0F, level);
        vertex(vc, m, n, r, r, 1.0F, 0.0F, level);
        vertex(vc, m, n, -r, r, 0.0F, 0.0F, level);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float u, float v, float level) {
        vc.vertex(m, x, y, 0.0F).color(level, level, level, 1.0F).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
