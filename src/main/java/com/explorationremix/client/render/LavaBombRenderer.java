package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.entity.LavaBomb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a lava bomb in flight (0.9.0, spec 5.5): a block of magma, 0.6 of a block across, tumbling about an axis of its
 * own (drawn from its entity id, so no data is sent for it) at about 25 degrees a tick, full-bright, and drawn a second
 * time as eyes are, so under Complementary with Oculus it glows rather than being lit like rock (the house rule for
 * anything that glows, as {@link FireflySwarmRenderer} has it). Round it, a square of orange glow that always faces the
 * camera, drawn as eyes too, which grows with the distance so a bomb across the Caldera still reads as a glowing spark
 * rather than a pixel lost in the fog. No shadow. The game draws it out to 153.6 blocks (a projectile's own reach for
 * its size).
 */
public class LavaBombRenderer extends EntityRenderer<LavaBomb> {
    /** The halo's glow (tools/particles.py paints it). */
    public static final ResourceLocation HALO = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/lava_bomb/halo.png");
    /** The block's size, its middle over the entity's feet, and how fast it tumbles (degrees a tick). */
    static final float SIZE = 0.6F, MIDDLE = 0.3F, SPIN = 25.0F;
    /** Half the width of the halo up close, in blocks, and how bright it is. */
    static final float HALO_HALF = 0.65F, HALO_LEVEL = 0.9F;
    /**
     * Seen from further than {@link #HALO_NEAR} blocks the halo grows with the distance, to {@link #HALO_GROWTH} times its
     * size at {@link #HALO_FAR} and no more.
     */
    static final double HALO_NEAR = 8.0, HALO_FAR = 96.0, HALO_GROWTH = 3.0;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final BlockRenderDispatcher blocks;

    public LavaBombRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
        this.blocks = ctx.getBlockRenderDispatcher();
    }

    @Override
    public ResourceLocation getTextureLocation(LavaBomb bomb) { return TextureAtlas.LOCATION_BLOCKS; }

    @Override
    public void render(LavaBomb bomb, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0F, MIDDLE, 0.0F);
        // the tumbling block: solid, then the same quads again as glowing eyes (the block is looked up here, not held in a
        // static field, so the pure sums below can be tested without the game's registries)
        BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
        pose.pushPose();
        float[] a = axis(bomb.getId());
        float angle = (float) Math.toRadians((bomb.tickCount + partialTick) * SPIN + a[3]);
        pose.mulPose(new Quaternionf().rotationAxis(angle, a[0], a[1], a[2]));
        pose.scale(SIZE, SIZE, SIZE);
        pose.translate(-0.5F, -0.5F, -0.5F);
        blocks.renderSingleBlock(magma, pose, buffers, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        blocks.renderSingleBlock(magma, pose, buffers, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY,
                RenderType.eyes(TextureAtlas.LOCATION_BLOCKS));
        pose.popPose();
        // the halo, facing the camera
        float grow = haloGrowth(Math.sqrt(entityRenderDispatcher.distanceToSqr(bomb)));
        pose.pushPose();
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        quad(buffers.getBuffer(RenderType.eyes(HALO)), pose, HALO_HALF * grow, HALO_LEVEL);
        pose.popPose();
        pose.popPose();
        super.render(bomb, yaw, partialTick, pose, buffers, light);
    }

    /**
     * How many times its own size the halo is drawn seen from {@code distance} blocks: 1 up to {@link #HALO_NEAR},
     * growing evenly to {@link #HALO_GROWTH} at {@link #HALO_FAR}, and no more beyond.
     */
    static float haloGrowth(double distance) {
        double k = Math.max(0.0, Math.min(1.0, (distance - HALO_NEAR) / (HALO_FAR - HALO_NEAR)));
        return (float) (1.0 + (HALO_GROWTH - 1.0) * k);
    }

    /**
     * The axis bomb {@code id} tumbles about (a unit vector) and the angle it starts at, in degrees: drawn from its id,
     * so every client and every frame agree without any data sent.
     */
    static float[] axis(int id) {
        long z = id * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        // a point on the sphere from two of its draws, and a third for the start angle
        double u = ((z >>> 11) & 0xFFFFF) / (double) 0x100000, v = ((z >>> 31) & 0xFFFFF) / (double) 0x100000;
        double w = ((z >>> 44) & 0xFFFFF) / (double) 0x100000;
        double cos = 2 * u - 1, sin = Math.sqrt(Math.max(0, 1 - cos * cos)), phi = 2 * Math.PI * v;
        Vector3f n = new Vector3f((float) (sin * Math.cos(phi)), (float) cos, (float) (sin * Math.sin(phi))).normalize();
        return new float[]{n.x, n.y, n.z, (float) (360 * w)};
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
