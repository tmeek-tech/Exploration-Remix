package com.explorationremix.client.rune;

import com.explorationremix.rune.effect.RuneMote;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * One of Siphon's motes: a small white point of light facing the camera, a quarter of a block across at size 1 (bigger
 * for a mote carrying more), pulsing a little. Full bright, like the bolts.
 */
final class RuneMoteRenderer extends EntityRenderer<RuneMote> {
    private static final ResourceLocation TEXTURE = GlowSprite.texture("mote");
    private static final float SIDE = 0.25F;

    RuneMoteRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(RuneMote m) { return TEXTURE; }

    @Override
    public void render(RuneMote m, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!m.isInvisible()) {
            // each mote on its own beat
            float wave = Mth.sin((m.tickCount + partialTick) * 0.6F + m.getId() * 1.3F);
            float half = 0.5F * SIDE * Mth.clamp(m.size(), 0.3F, 2.0F) * (1.0F + 0.12F * wave);
            pose.pushPose();
            pose.translate(0.0F, m.getBbHeight() * 0.5F, 0.0F);
            GlowSprite.facing(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE)), entityRenderDispatcher.cameraOrientation(),
                    half, 0.0F, 0.0F, 0xFFFFFF, 0.85F + 0.15F * wave);
            pose.popPose();
        }
        super.render(m, yaw, partialTick, pose, buffers, light);
    }
}
