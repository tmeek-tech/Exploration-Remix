package com.explorationremix.client.rune;

import com.explorationremix.rune.effect.RuneBolt;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.RuneId;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * A flying rune: its own sprite (Spark, Shard, Orb or Glow) facing the camera and turning slowly, in its element's
 * colour, over a faint wider halo of the same colour. Full bright and drawn as glowing, so it reads in a dark cave; the
 * trail behind it is particles ({@link RuneFx#boltTrail}).
 */
final class RuneBoltRenderer extends EntityRenderer<RuneBolt> {
    private static final ResourceLocation SPARK = GlowSprite.texture("spark"), SHARD = GlowSprite.texture("shard"),
            ORB = GlowSprite.texture("orb"), GLOW = GlowSprite.texture("glow");
    /** The halo: this many times the sprite's size, at this strength. */
    private static final float HALO = 1.9F, HALO_ALPHA = 0.3F;
    /** How far the sprite's colour is taken towards white, so its middle reads hotter than the halo. */
    private static final float CORE_WHITE = 0.25F;

    RuneBoltRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(RuneBolt b) {
        return switch (b.rune()) {
            case SHARD -> SHARD;
            case ORB -> ORB;
            case GLOW -> GLOW;
            default -> SPARK;
        };
    }

    /** The sprite's side in blocks at size 1: about half a block, an orb larger. */
    private static float side(RuneId rune) {
        return switch (rune) {
            case SHARD -> 0.45F;
            case ORB -> 0.8F;
            default -> 0.5F;
        };
    }

    /** Degrees it turns each tick. */
    private static float spin(RuneId rune) {
        return switch (rune) {
            case SPARK -> 4.0F;
            case SHARD -> 3.0F;
            case ORB -> 1.5F;
            default -> 2.0F;
        };
    }

    /** Half the sprite's side, in blocks. */
    private static float half(RuneBolt b) { return 0.5F * side(b.rune()) * Mth.clamp(b.size(), 0.25F, 4.0F); }

    @Override
    public boolean shouldRender(RuneBolt b, Frustum frustum, double camX, double camY, double camZ) {
        // the halo is wider than the bolt's box
        return b.shouldRender(camX, camY, camZ) && frustum.isVisible(b.getBoundingBox().inflate(half(b) * HALO + 0.25F));
    }

    @Override
    public void render(RuneBolt b, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!b.isInvisible()) {
            RuneId rune = b.rune();
            int rgb = RuneFx.colour(rune, b.element());
            float half = half(b);
            float turn = Math.floorMod(b.getId() * 47, 360) + (b.tickCount + partialTick) * spin(rune);
            Quaternionf camera = entityRenderDispatcher.cameraOrientation();
            pose.pushPose();
            // its middle, where the trail comes from
            pose.translate(0.0F, b.getBbHeight() * 0.5F, 0.0F);
            GlowSprite.facing(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(GLOW)), camera, half * HALO, -0.5F * turn,
                    0.02F, rgb, HALO_ALPHA);
            GlowSprite.facing(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(getTextureLocation(b))), camera, half, turn,
                    0.0F, RuneLook.mix(0xFF000000 | rgb, 0xFFFFFFFF, CORE_WHITE) & 0xFFFFFF, 1.0F);
            pose.popPose();
        }
        super.render(b, yaw, partialTick, pose, buffers, light);
    }
}
