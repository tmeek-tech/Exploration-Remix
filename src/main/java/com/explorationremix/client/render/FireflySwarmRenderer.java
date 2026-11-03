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
 * core with a wider, fainter blue-cyan halo round it, two squares that always face the camera, each cut from its own
 * half of one glow texture. Flying they shine steadily; settled on the rock each one breathes on its own ({@link Pulse}),
 * its halo shrinking a little as it dims. Seen from across a cave the halo grows with the distance (the core does not),
 * so the swarm still reads as blue lights rather than shrinking to a few pale specks; close up it is as it always was.
 *
 * <p>They are drawn as eyes are (full-bright, added to what is behind them), the render type shader packs treat as
 * glowing: under Complementary with Oculus the fireflies glow like a spider's eyes rather than being lit like rock.
 * The shader's block light is warm, so all of their colour is in the texture; the vertex colour only scales it.
 */
public class FireflySwarmRenderer extends EntityRenderer<FireflySwarm> {
    public static final ResourceLocation TEXTURE = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/firefly_swarm/firefly.png");
    /** Half the width of a firefly's core and of its halo (blocks), and how bright the halo is next to the core. */
    static final float CORE = 0.09F, HALO = 0.36F, HALO_LEVEL = 0.8F;
    /** A dim firefly's halo shrinks to this share of its full size, so a settled one's breath swells as well as brightens. */
    static final float HALO_DIM = 0.5F;
    /**
     * Seen from further than {@link #HALO_NEAR} blocks a halo grows with the distance, to {@link #HALO_GROWTH} times its
     * size at {@link #HALO_FAR} and no more: it keeps more of its apparent size, where the core and the halo's middle
     * would shrink to a pale speck.
     */
    static final double HALO_NEAR = 6.0, HALO_FAR = 20.0, HALO_GROWTH = 2.0;
    /** The texture's halves: the core on the left, the halo on the right (tools/fireflies.py paints them). */
    private static final float CORE_U0 = 0.0F, CORE_U1 = 0.5F, HALO_U0 = 0.5F, HALO_U1 = 1.0F;
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
                double x = f.x(i, partialTick), y = f.y(i, partialTick), z = f.z(i, partialTick);
                float grow = haloGrowth(Math.sqrt(entityRenderDispatcher.distanceToSqr(x, y, z)));
                pose.pushPose();
                pose.translate(x - ex, y - ey, z - ez);
                pose.mulPose(facing);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F));
                quad(vc, pose, HALO * grow * (HALO_DIM + (1.0F - HALO_DIM) * b), b * HALO_LEVEL, HALO_U0, HALO_U1);
                quad(vc, pose, CORE, b, CORE_U0, CORE_U1);
                pose.popPose();
            }
        }
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    /**
     * How many times its own size a halo is drawn seen from {@code distance} blocks: 1 up to {@link #HALO_NEAR}, growing
     * evenly to {@link #HALO_GROWTH} at {@link #HALO_FAR}, and no more beyond.
     */
    static float haloGrowth(double distance) {
        double k = Math.max(0.0, Math.min(1.0, (distance - HALO_NEAR) / (HALO_FAR - HALO_NEAR)));
        return (float) (1.0 + (HALO_GROWTH - 1.0) * k);
    }

    /**
     * One square of glow, {@code r} from its middle to each side, facing the camera, scaled to {@code level}, cut from
     * the texture between {@code u0} and {@code u1}.
     */
    private static void quad(VertexConsumer vc, PoseStack pose, float r, float level, float u0, float u1) {
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f n = last.normal();
        vertex(vc, m, n, -r, -r, u0, 1.0F, level);
        vertex(vc, m, n, r, -r, u1, 1.0F, level);
        vertex(vc, m, n, r, r, u1, 0.0F, level);
        vertex(vc, m, n, -r, r, u0, 0.0F, level);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float u, float v, float level) {
        vc.vertex(m, x, y, 0.0F).color(level, level, level, 1.0F).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
